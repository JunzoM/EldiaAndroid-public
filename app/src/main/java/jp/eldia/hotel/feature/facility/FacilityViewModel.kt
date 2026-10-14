package jp.eldia.hotel.feature.facility

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import jp.eldia.hotel.core.Connectivity
import jp.eldia.hotel.core.DateUtil
import jp.eldia.hotel.core.FacilityConst
import jp.eldia.hotel.core.Offline
import jp.eldia.hotel.core.Photos
import jp.eldia.hotel.core.Room
import jp.eldia.hotel.core.SB
import jp.eldia.hotel.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

data class FacilityStats(val open: Int, val progress: Int, val doneThisMonth: Int, val overdue: Int, val affectedRooms: Int)

@Serializable
private data class CommentsRow(val comments: List<IssueComment>? = null, val status: String? = null)

@Serializable
private data class TemplateSetting(val key: String, val value: List<IssueTemplate>)

/** 設備管理（共通プロジェクトの issues / room_ranks / app_settings）。iOS版 FacilityStore に相当 */
class FacilityViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("eldia_facility", Context.MODE_PRIVATE)
    private val db get() = SB.main

    private val _issues = MutableStateFlow<List<Issue>>(emptyList())
    val issues: StateFlow<List<Issue>> = _issues.asStateFlow()

    private val _ranks = MutableStateFlow<Map<String, String>>(emptyMap())
    val ranks: StateFlow<Map<String, String>> = _ranks.asStateFlow()

    private val _templates = MutableStateFlow(
        prefs.getString(KEY_TEMPLATES, null)?.let {
            runCatching { Offline.json.decodeFromString(ListSerializer(IssueTemplate.serializer()), it) }.getOrNull()
        } ?: defaultTemplates,
    )
    val templates: StateFlow<List<IssueTemplate>> = _templates.asStateFlow()

    private val _roster = MutableStateFlow<List<String>>(emptyList())
    /** 担当者の候補（スタッフ名簿の作業者） */
    val roster: StateFlow<List<String>> = _roster.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _live = MutableStateFlow(false)
    /** リアルタイム同期中か */
    val live: StateFlow<Boolean> = _live.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _pendingIssues = MutableStateFlow(loadPendingIssues())
    /** 未送信の新規不具合（電波がなくて端末に保存したもの） */
    val pendingIssues: StateFlow<List<PendingIssue>> = _pendingIssues.asStateFlow()

    private val _pendingCount = MutableStateFlow(_pendingIssues.value.size)
    /** 未送信の件数（ホームの表示用） */
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _sendingPending = MutableStateFlow(false)
    /** 未送信分を送信中か */
    val sendingPending: StateFlow<Boolean> = _sendingPending.asStateFlow()

    private val flushLock = Mutex()
    private val queueLock = Any()

    private var started = false
    private var channel: RealtimeChannel? = null
    private var lastRefresh = 0L
    /** コメント用の関数が未作成と分かったら false（従来の書き込みに切り替え） */
    @Volatile private var addRpc = true
    @Volatile private var removeRpc = true

    init {
        // 電波が戻ったら未送信の不具合を送る
        Connectivity.init(app)
        viewModelScope.launch {
            Connectivity.online.collect { on -> if (on && _pendingIssues.value.isNotEmpty()) flushPendingIssues() }
        }
    }

    /** 最初の読み込み（複数の画面から呼ばれても1回だけ）と、他の人の更新のリアルタイム反映 */
    fun start() {
        if (started) return
        started = true
        lastRefresh = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            coroutineScope {
                launch { loadIssues() }
                launch { loadRanks() }
                launch { loadTemplates() }
                launch { loadRoster() }
            }
            subscribe()
        }
    }

    private suspend fun subscribe() {
        runCatching {
            val ch = db.channel("issues-android")
            ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = "issues" }
                .onEach {
                    // 変更の内容に関わらず読み直す（件数は多くないため。二重反映を避けられる）
                    delay(300)
                    loadIssues(silent = true)
                }
                .launchIn(viewModelScope)
            // 接続とチャンネルの状態を LIVE 表示に使う（切断も反映）。つなぎ直したら取りこぼし分を読み直す
            var subscribedOnce = false
            combine(db.realtime.status, ch.status) { conn, st ->
                conn == Realtime.Status.CONNECTED && st == RealtimeChannel.Status.SUBSCRIBED
            }
                .onEach { on ->
                    val wasLive = _live.value
                    _live.value = on
                    if (on && !wasLive) {
                        if (subscribedOnce) loadIssues(silent = true)
                        subscribedOnce = true
                    }
                }
                .launchIn(viewModelScope)
            ch.subscribe()
            channel = ch
        }.onFailure { _live.value = false }
    }

    override fun onCleared() {
        val ch = channel
        // viewModelScope はもう止まっているため、別のスコープで後片付けする
        if (ch != null) CoroutineScope(Dispatchers.IO + NonCancellable).launch { runCatching { db.realtime.removeChannel(ch) } }
        super.onCleared()
    }

    /** 引っ張って更新（一覧・ランク・テンプレートを読み直す） */
    suspend fun refresh() {
        lastRefresh = SystemClock.elapsedRealtime()
        coroutineScope {
            launch { loadIssues() }
            launch { loadRanks() }
            launch { loadTemplates() }
        }
    }

    /** 画面に戻ったとき（30秒以内の再読み込みはしない） */
    fun onResume() {
        // 未送信の不具合があれば送る
        if (_pendingIssues.value.isNotEmpty()) viewModelScope.launch { flushPendingIssues() }
        if (!started) { start(); return }
        if (SystemClock.elapsedRealtime() - lastRefresh < 30_000) return
        lastRefresh = SystemClock.elapsedRealtime()
        viewModelScope.launch { loadIssues(silent = true) }
    }

    suspend fun loadIssues(silent: Boolean = false) {
        if (!silent) _loading.value = true
        try {
            // 1回の取得は最大1000件のため、未完了は全件・完了は新しい順に DONE_LIMIT 件だけ取る
            val (active, done) = coroutineScope {
                val a = async {
                    db.from("issues").select {
                        filter { neq("status", IssueStatus.DONE.raw) }
                        order("updated_at", Order.DESCENDING)
                    }.decodeList<Issue>()
                }
                val d = async {
                    db.from("issues").select {
                        filter { eq("status", IssueStatus.DONE.raw) }
                        order("updated_at", Order.DESCENDING)
                        limit(DONE_LIMIT)
                    }.decodeList<Issue>()
                }
                a.await() to d.await()
            }
            _issues.value = (active + done)
                .distinctBy { it.id }
                .sortedByDescending { i ->
                    i.updatedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: java.time.Instant.EPOCH
                }
            _error.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!silent) _error.value = "データ読み込みエラー：" + e.userMessage()
        } finally {
            if (!silent) _loading.value = false
        }
    }

    private suspend fun loadRanks() {
        runCatching { db.from("room_ranks").select().decodeList<RoomRank>() }.getOrNull()?.let { rows ->
            _ranks.value = rows.associate { "${it.store}:${it.roomId}" to it.rank }
        }
    }

    private suspend fun loadTemplates() {
        runCatching {
            db.from("app_settings").select(Columns.list("key", "value")) {
                filter { eq("key", "eldia_issue_templates") }
            }.decodeList<TemplateSetting>().firstOrNull()?.value
        }.getOrNull()?.let { v ->
            _templates.value = v
            prefs.edit().putString(KEY_TEMPLATES, Offline.json.encodeToString(ListSerializer(IssueTemplate.serializer()), v)).apply()
        }
    }

    @Serializable
    private data class NameRow(val name: String)

    private suspend fun loadRoster() {
        runCatching {
            db.from("staff").select(Columns.list("name")) {
                filter { eq("is_worker", true); eq("is_active", true) }
                order("name", Order.ASCENDING)
            }.decodeList<NameRow>().map { it.name }
        }.getOrNull()?.let { _roster.value = it }
    }

    // MARK: 集計・検索

    fun roomIssues(roomId: String, site: IssueSite, list: List<Issue> = _issues.value) =
        list.filter { it.site == site && it.roomId == roomId }

    fun stats(list: List<Issue>, site: IssueSite): FacilityStats {
        val l = list.filter { it.site == site }
        val now = DateUtil.today()
        return FacilityStats(
            open = l.count { it.issueStatus == IssueStatus.OPEN },
            progress = l.count { it.issueStatus == IssueStatus.PROGRESS },
            doneThisMonth = l.count { i ->
                // 完了日は「完了」に変えたコメントの日付（コメント追加で updated_at が変わっても動かない）
                i.issueStatus == IssueStatus.DONE && (i.doneDate ?: DateUtil.parse(i.reportDate))
                    ?.let { it.year == now.year && it.monthValue == now.monthValue } == true
            },
            overdue = l.count { it.isOverdue },
            affectedRooms = l.filter { it.issueStatus != IssueStatus.DONE }.map { it.roomId }.toSet().size,
        )
    }

    fun rank(ranks: Map<String, String>, site: IssueSite, room: Room) = ranks["${site.raw}:${room.id}"] ?: room.type

    suspend fun saveRank(site: IssueSite, roomId: String, rank: String): String? {
        _ranks.value = _ranks.value + ("${site.raw}:$roomId" to rank)
        return try {
            db.from("room_ranks").upsert(RoomRank(site.raw, roomId, rank)) { onConflict = "store,room_id" }
            null
        } catch (e: Exception) {
            loadRanks()
            e.userMessage()
        }
    }

    // MARK: 書き込み

    private fun now() = OffsetDateTime.now().toString()

    /** 入力欄の列（comments は含めない＝他の人のコメントを消さない。編集時は status も含めない） */
    private fun fields(i: Issue, withStatus: Boolean): JsonObject = buildJsonObject {
        put("store", i.store)
        put("room_id", i.roomId)
        put("area", i.area)
        put("title", i.title)
        if (withStatus) put("status", i.status)
        put("occur_date", i.occurDate?.takeIf { it.isNotBlank() })
        put("report_date", i.reportDate?.takeIf { it.isNotBlank() })
        put("assignee", i.assignee?.takeIf { it.isNotBlank() })
        put("memo", i.memo?.takeIf { it.isNotBlank() })
        put("priority", i.priority)
        put("photos", Offline.json.encodeToJsonElement(i.photoList))
        put("updated_at", now())
    }

    private fun replace(saved: Issue) {
        _issues.value = listOf(saved) + _issues.value.filter { it.id != saved.id }
    }

    /**
     * 新規登録または編集。編集では状態を書き戻さない（他の人の状態変更を戻さない）。
     * フォームで状態を変えたときだけ newStatus を渡す → 他の項目を保存したあと setStatus と同じ方法で変える（記録が残る）
     * @return 保存した不具合とエラーメッセージ（保存できて状態の変更だけ失敗したときは両方入る）
     */
    suspend fun saveIssue(issue: Issue, newStatus: IssueStatus? = null, author: String = ""): Pair<Issue?, String?> = try {
        val saved = if (issue.id == null) {
            val body = buildJsonObject {
                fields(issue, withStatus = true).forEach { (k, v) -> put(k, v) }
                put("comments", Offline.json.encodeToJsonElement(issue.commentList))
            }
            db.from("issues").insert(body) { select() }.decodeSingle<Issue>()
        } else {
            db.from("issues").update(fields(issue, withStatus = false)) {
                select()
                filter { eq("id", issue.id) }
            }.decodeSingle<Issue>()
        }
        replace(saved)
        val id = saved.id
        val err = if (issue.id != null && newStatus != null && id != null && saved.issueStatus != newStatus) {
            appendComment(id, author, "", emptyList(), newStatus)?.let { "状態を変更できませんでした：$it" }
        } else null
        saved to err
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null to e.userMessage()
    }

    // MARK: 新規登録（電波がなければ端末に保存）

    /** 新規の不具合を登録する（オンライン時は saveIssue と同じ）。insert 本体（失敗は例外のまま） */
    private suspend fun insertIssue(issue: Issue, updatedAt: String? = null): Issue {
        val body = buildJsonObject {
            fields(issue, withStatus = true).forEach { (k, v) -> put(k, v) }
            if (updatedAt != null) put("updated_at", updatedAt)
            put("comments", Offline.json.encodeToJsonElement(issue.commentList))
        }
        return db.from("issues").insert(body) { select() }.decodeSingle<Issue>()
    }

    /**
     * 新規登録。電波がなくて登録できないときは端末に保存して、電波が戻ったら送る。
     * @param issue photos はアップロード済みの写真の URL
     * @param localPhotos まだアップロードできていない写真（端末の Uri）。あれば最初から端末に保存する
     */
    suspend fun createIssue(issue: Issue, localPhotos: List<Uri> = emptyList()): CreateResult {
        if (localPhotos.isEmpty()) {
            try {
                val saved = insertIssue(issue)
                replace(saved)
                return CreateResult.Saved(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!Offline.isOffline(e)) return CreateResult.Failed(e.userMessage())
            }
        }
        return enqueueIssue(issue, localPhotos)
    }

    private fun pendingDir(id: String) = File(getApplication<Application>().filesDir, "$PENDING_DIR/$id")

    /** 端末に保存（写真は縮小して filesDir に書き出す） */
    private suspend fun enqueueIssue(issue: Issue, localPhotos: List<Uri>): CreateResult {
        val ctx: Context = getApplication()
        val id = UUID.randomUUID().toString()
        val dir = pendingDir(id)
        val names = try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                localPhotos.mapIndexed { i, uri ->
                    val bytes = Photos.jpeg(ctx, uri)
                    val name = "photo-$i.jpg"
                    File(dir, name).writeBytes(bytes)
                    name
                }
            }
        } catch (e: CancellationException) {
            dir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            dir.deleteRecursively()
            return CreateResult.Failed("写真を端末に保存できませんでした：" + e.userMessage())
        }
        val p = PendingIssue(
            id = id,
            createdAt = now(),
            updatedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).toString(),
            issue = issue.copy(id = null),
            localPhotos = names,
        )
        mutateQueue { it + p }
        return CreateResult.Queued
    }

    private fun loadPendingIssues(): List<PendingIssue> =
        Offline.load(getApplication(), KEY_PENDING, PendingIssue.serializer())

    /** 未送信の一覧を読み直して変更し、保存する（送信中の追加・破棄と重ならないよう同期） */
    private fun mutateQueue(f: (List<PendingIssue>) -> List<PendingIssue>): List<PendingIssue> = synchronized(queueLock) {
        val next = f(loadPendingIssues())
        Offline.save(getApplication(), KEY_PENDING, PendingIssue.serializer(), next)
        _pendingIssues.value = next
        _pendingCount.value = next.size
        next
    }

    /** 1件を更新（破棄されていたら false） */
    private fun updatePending(p: PendingIssue): Boolean {
        var found = false
        mutateQueue { q -> q.map { if (it.id == p.id) { found = true; p } else it } }
        return found
    }

    /** 未送信の不具合を破棄する（端末の写真と、アップロード済みの写真も消す） */
    fun discardPending(id: String) {
        var target: PendingIssue? = null
        mutateQueue { q -> target = q.firstOrNull { it.id == id }; q.filter { it.id != id } }
        val t = target ?: return
        pendingDir(t.id).deleteRecursively()
        Photos.deleteLater(BUCKET, t.issue.photoList + t.uploaded.values)
    }

    /**
     * 未送信の不具合を送る（電波が戻ったとき・画面に戻ったとき・「今すぐ送信」）。
     * 写真をアップロードしてから登録する。@param onlyId 指定した1件だけ送る
     * @return 最後のエラーメッセージ（すべて送れたら null）
     */
    suspend fun flushPendingIssues(onlyId: String? = null): String? = flushLock.withLock {
        val q = _pendingIssues.value.filter { onlyId == null || it.id == onlyId }
        if (q.isEmpty()) return@withLock null
        _sendingPending.value = true
        var lastErr: String? = null
        try {
            for (item in q) {
                var cur = item
                try {
                    // 1. 端末の写真をアップロード（済みのものは飛ばす）
                    for (name in cur.localPhotos) {
                        if (cur.uploaded.containsKey(name)) continue
                        val f = File(pendingDir(cur.id), name)
                        if (!f.exists()) continue
                        val bytes = withContext(Dispatchers.IO) { f.readBytes() }
                        val url = Photos.upload(bytes, BUCKET, Photos.randomName())
                        cur = cur.copy(uploaded = cur.uploaded + (name to url))
                        if (!updatePending(cur)) break
                    }
                    // 送信中に破棄されたら、アップロードした写真を消して次へ
                    if (_pendingIssues.value.none { it.id == cur.id }) {
                        Photos.deleteLater(BUCKET, cur.uploaded.values)
                        continue
                    }
                    // 2. 登録（前回の登録が実は届いていたら、それを使う）
                    val urls = cur.issue.photoList + cur.localPhotos.mapNotNull { cur.uploaded[it] }
                    val issue = cur.issue.copy(photos = urls)
                    val already = if (cur.insertTried) findSent(cur) else null
                    cur = cur.copy(insertTried = true)
                    if (!updatePending(cur)) {
                        Photos.deleteLater(BUCKET, cur.uploaded.values)
                        continue
                    }
                    val saved = already ?: insertIssue(issue, cur.updatedAt)
                    replace(saved)
                    // 3. 送れたので端末から消す
                    mutateQueue { list -> list.filter { it.id != cur.id } }
                    withContext(Dispatchers.IO) { pendingDir(cur.id).deleteRecursively() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e.userMessage()
                    updatePending(cur.copy(failCount = cur.failCount + 1, lastError = lastErr))
                    // 電波がなければ残りも送れないので止める
                    if (Offline.isOffline(e)) break
                }
            }
        } finally {
            _sendingPending.value = false
        }
        lastErr
    }

    /** 前回の送信で登録が届いていたか（同じ場所・内容・updated_at の不具合があるか） */
    private suspend fun findSent(p: PendingIssue): Issue? = try {
        db.from("issues").select {
            filter {
                eq("store", p.issue.store)
                eq("room_id", p.issue.roomId)
                eq("title", p.issue.title)
                eq("updated_at", p.updatedAt)
            }
            limit(1)
        }.decodeList<Issue>().firstOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (Offline.isOffline(e)) throw e
        null
    }

    suspend fun deleteIssue(id: Long): String? = try {
        db.from("issues").delete { filter { eq("id", id) } }
        _issues.value = _issues.value.filter { it.id != id }
        null
    } catch (e: Exception) {
        e.userMessage()
    }

    /** 状態だけ変える（コメントに変更の記録を残す） */
    suspend fun setStatus(issue: Issue, status: IssueStatus, author: String): String? {
        val id = issue.id ?: return "保存されていない不具合です"
        return appendComment(id, author, "", emptyList(), status)
    }

    /** 関数（RPC）がまだ作られていないときのエラーか */
    private fun isMissingFunction(e: Exception, name: String): Boolean {
        val m = e.message.orEmpty()
        return m.contains(name) && (m.contains("PGRST202") || m.contains("Could not find") || m.contains("does not exist"))
    }

    /**
     * コメントを追加（状態も変えるときは newStatus）。
     * 関数 issue_add_comment で1回の更新で追加する（同時に書いてもコメントが消えない）。
     * 関数が未作成のときは従来の「最新を読んで comments 列だけ更新」に切り替える
     */
    suspend fun appendComment(issueId: Long, author: String, text: String, photos: List<String>, newStatus: IssueStatus?): String? {
        if (addRpc) {
            try {
                // statusChange.from は関数の中で更新直前の状態に置き換わる
                val c = IssueComment.create(author, text, photos, newStatus?.let { StatusChange("open", it.raw) })
                val saved = db.postgrest.rpc("issue_add_comment", buildJsonObject {
                    put("p_issue", issueId)
                    put("p_comment", Offline.json.encodeToJsonElement(c))
                    if (newStatus != null) put("p_status", newStatus.raw)
                }).decodeAs<Issue>()
                replace(saved)
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isMissingFunction(e, "issue_add_comment")) return e.userMessage()
                addRpc = false
            }
        }
        return appendCommentLegacy(issueId, author, text, photos, newStatus)
    }

    /** 従来の方法（直前に最新のコメントを読み、comments 列だけ更新する） */
    private suspend fun appendCommentLegacy(issueId: Long, author: String, text: String, photos: List<String>, newStatus: IssueStatus?): String? = try {
        val latest = db.from("issues").select(Columns.list("comments", "status")) { filter { eq("id", issueId) } }
            .decodeSingle<CommentsRow>()
        val change = newStatus?.let { StatusChange(latest.status ?: "open", it.raw) }
        val comments = latest.comments.orEmpty() + IssueComment.create(author, text, photos, change)
        val body = buildJsonObject {
            put("comments", Offline.json.encodeToJsonElement(comments))
            if (newStatus != null) put("status", newStatus.raw)
            put("updated_at", now())
        }
        replace(db.from("issues").update(body) { select(); filter { eq("id", issueId) } }.decodeSingle())
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    /** コメントを削除（関数 issue_remove_comment で1回の更新。未作成なら従来の方法） */
    suspend fun deleteComment(issueId: Long, target: IssueComment): String? {
        if (removeRpc) {
            try {
                val saved = db.postgrest.rpc("issue_remove_comment", buildJsonObject {
                    put("p_issue", issueId)
                    put("p_key", target.key)
                    put("p_date", target.date)
                    put("p_author", target.author)
                    put("p_text", target.text)
                }).decodeAs<Issue>()
                replace(saved)
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isMissingFunction(e, "issue_remove_comment")) return e.userMessage()
                removeRpc = false
            }
        }
        return deleteCommentLegacy(issueId, target)
    }

    private suspend fun deleteCommentLegacy(issueId: Long, target: IssueComment): String? = try {
        val latest = db.from("issues").select(Columns.list("comments", "status")) { filter { eq("id", issueId) } }
            .decodeSingle<CommentsRow>()
        val comments = latest.comments.orEmpty().filterNot {
            it.key == target.key || (it.date == target.date && it.author == target.author && it.text == target.text)
        }
        val body = buildJsonObject {
            put("comments", Offline.json.encodeToJsonElement(comments))
            put("updated_at", now())
        }
        replace(db.from("issues").update(body) { select(); filter { eq("id", issueId) } }.decodeSingle())
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    /** 不具合テンプレートを保存（端末に即時 + app_settings）。@return エラーメッセージ（成功時 null） */
    suspend fun saveTemplates(v: List<IssueTemplate>): String? {
        _templates.value = v
        prefs.edit().putString(KEY_TEMPLATES, Offline.json.encodeToString(ListSerializer(IssueTemplate.serializer()), v)).apply()
        return try {
            db.from("app_settings").upsert(buildJsonObject {
                put("key", KEY_TEMPLATES)
                put("value", Offline.json.encodeToJsonElement(ListSerializer(IssueTemplate.serializer()), v))
                put("updated_at", now())
            }) { onConflict = "key" }
            null
        } catch (e: Exception) {
            "テンプレートを保存できませんでした：" + e.userMessage()
        }
    }

    companion object {
        private const val KEY_TEMPLATES = "eldia_issue_templates"
        private const val KEY_PENDING = "facility_pending_issues_v1"
        private const val PENDING_DIR = "issue_pending_photos"
        /** 不具合の写真のバケット */
        const val BUCKET = "issue-photos"
        /** 完了済みの不具合は新しい順にこの件数まで読む */
        private const val DONE_LIMIT = 500L
    }
}

/**
 * 場所ごとの未完了（未対応 + 対応中）の不具合。一覧が変わったときに 1 回だけ作り、
 * 部屋カード・階の件数・問題順の並べ替えはここを引く（カードごとに全件を走査しない）
 */
class ActiveIssueIndex(issues: List<Issue>) {
    private val byLocation: Map<String, List<Issue>> =
        issues.filter { it.issueStatus != IssueStatus.DONE }.groupBy { key(it.site, it.roomId) }

    /** 場所の未完了の不具合（元の一覧の並び順のまま） */
    fun active(site: IssueSite, roomId: String): List<Issue> = byLocation[key(site, roomId)].orEmpty()

    fun count(site: IssueSite, roomId: String): Int = active(site, roomId).size

    /** 階ごとの未完了件数（部屋 + その階の共用部） */
    fun floorCount(site: IssueSite, floor: String): Int {
        val h = site.hotel ?: return 0
        val ids = FacilityConst.rooms(h, floor).map { it.id } +
            FacilityConst.commons(h).filter { it.floor == floor }.map { it.id }
        return ids.distinct().sumOf { count(site, it) }
    }

    private fun key(site: IssueSite, roomId: String) = "${site.name}|$roomId"
}

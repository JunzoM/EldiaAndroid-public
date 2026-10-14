package jp.eldia.hotel.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * アプリが落ちたとき・固まって強制終了されたときの記録を、共通プロジェクトの app_diagnostics に送る
 * （iOS版の DiagnosticsReporter と同じ表）。
 * - 落ちた瞬間は通信できないので、端末に保存しておき、次に起動してログインしたあとで送る
 * - 固まって止められた（ANR）記録は Android 11 以降の「前回の終了理由」から拾う
 * - user_id は DB の既定値 auth.uid() に任せる（RLS で本人のみ追加できる）
 */
object CrashReporter {
    private const val DIR = "crash_pending"
    private const val PREFS = "eldia_crash"
    private const val KEY_LAST_EXIT = "last_exit_ts"
    private const val MAX_FILES = 20

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** Application.onCreate で1回呼ぶ */
    fun init(context: Context) {
        app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 落ちる直前なので、端末に書くだけ（通信しない）
            runCatching { saveCrash(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** ログイン後に呼ぶ。保存しておいた記録と、前回の強制終了（ANR）を送る */
    fun flush() {
        if (!::app.isInitialized) return
        scope.launch {
            lock.withLock {
                if (SB.main.auth.currentSessionOrNull() == null) return@withLock
                collectExitReasons()
                dir().listFiles()?.sortedBy { it.name }?.forEach { f ->
                    val row = runCatching { Offline.json.parseToJsonElement(f.readText()) as JsonObject }.getOrNull()
                    if (row == null) { f.delete(); return@forEach }
                    val sent = runCatching { SB.main.from("app_diagnostics").insert(row) }.isSuccess
                    if (sent) f.delete() else return@withLock   // 電波がないときは次回にまわす
                }
            }
        }
    }

    private fun dir(): File = File(app.filesDir, DIR).apply { mkdirs() }

    private fun saveCrash(thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val stack = sw.toString().take(30_000)
        val summary = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(300)
        write("crash", summary, buildJsonObject {
            put("platform", "android")
            put("thread", thread.name)
            put("stack", stack)
        }, Instant.now())
    }

    private fun write(kind: String, summary: String, payload: JsonObject, at: Instant) {
        val files = dir().listFiles()?.sortedBy { it.name }.orEmpty()
        // 同じ不具合が続いても端末の容量を使いすぎないよう、古いものから消す
        if (files.size >= MAX_FILES) files.take(files.size - MAX_FILES + 1).forEach { it.delete() }
        val row = buildJsonObject {
            put("kind", kind)
            put("summary", summary)
            put("occurred_at", at.toString())
            put("app_version", versionName())
            put("build", versionCode())
            put("os_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("payload_hash", (kind + summary).hashCode().toString())
            put("payload", payload)
        }
        File(dir(), "${at.toEpochMilli()}-$kind.json").writeText(row.toString())
    }

    /** Android 11 以降：前回以降の「ANR（固まって止められた）」とネイティブクラッシュを記録に加える */
    private fun collectExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_EXIT, 0L)
        if (last == 0L) {
            // 初回は過去の記録を送らず、基準の時刻だけ保存する
            prefs.edit().putLong(KEY_LAST_EXIT, System.currentTimeMillis()).apply()
            return
        }
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val infos = runCatching { am.getHistoricalProcessExitReasons(app.packageName, 0, 10) }.getOrNull() ?: return
        var newest = last
        infos.filter { it.timestamp > last }.forEach { info ->
            val kind = when (info.reason) {
                ApplicationExitInfo.REASON_ANR -> "hang"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash"
                else -> null
            }
            if (kind != null) {
                val trace = runCatching { info.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                write(kind, (info.description ?: kind).take(300), buildJsonObject {
                    put("platform", "android")
                    put("reason", info.reason)
                    put("importance", info.importance)
                    put("trace", trace?.take(30_000))
                }, Instant.ofEpochMilli(info.timestamp))
            }
            if (info.timestamp > newest) newest = info.timestamp
        }
        prefs.edit().putLong(KEY_LAST_EXIT, newest).apply()
    }

    private fun versionName(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun versionCode(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.toString()
    }.getOrDefault("")
}

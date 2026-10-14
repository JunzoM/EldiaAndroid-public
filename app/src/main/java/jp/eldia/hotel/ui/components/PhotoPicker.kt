package jp.eldia.hotel.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import jp.eldia.hotel.core.Offline
import jp.eldia.hotel.core.Photos
import jp.eldia.hotel.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

enum class PhotoStatus { UPLOADING, DONE, ERROR }

/** @property offline 電波がなくてアップロードできなかった（端末に保存して後で送れる） */
data class PhotoItem(
    val key: String, val url: String?, val preview: Uri?, val status: PhotoStatus, val error: String? = null,
    val offline: Boolean = false,
)

/** 複数写真のアップロード状態（Web版 usePhotoUpload / iOS版 PhotoUploadModel に相当）。選んだらすぐ裏でアップロードする */
@Stable
class PhotoUploadState(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val bucket: String,
    private val namePrefix: String,
) {
    val items = mutableStateListOf<PhotoItem>()
    val isUploading get() = items.any { it.status == PhotoStatus.UPLOADING }
    val hasError get() = items.any { it.status == PhotoStatus.ERROR }
    val uploadedUrls: List<String> get() = items.mapNotNull { if (it.status == PhotoStatus.DONE) it.url else null }
    /** 失敗した写真がすべて電波なしによるものか（端末に保存して後で送れる） */
    val failedOffline: Boolean get() = items.any { it.status == PhotoStatus.ERROR } &&
        items.filter { it.status == PhotoStatus.ERROR }.all { it.offline }
    /** まだアップロードできていない写真（端末の Uri） */
    val localUris: List<Uri> get() = items.mapNotNull { if (it.status != PhotoStatus.DONE) it.preview else null }

    /** この入力中にアップロードした写真（保存せずに外した・閉じたらバケットから消す） */
    private val sessionUploads = mutableSetOf<String>()
    /** 破棄した後に終わったアップロードも消すため */
    @Volatile private var discarded = false

    /** 保存済みの写真を入れる（これらは外しても消さない） */
    fun setExisting(urls: List<String>) {
        items.clear()
        sessionUploads.clear()
        discarded = false
        items += urls.map { PhotoItem(UUID.randomUUID().toString(), it, null, PhotoStatus.DONE) }
    }

    /** 回転・復元のときに、この入力中にアップロードした写真の印も戻す */
    internal fun restore(urls: List<String>, session: Collection<String>) {
        setExisting(urls)
        sessionUploads += session.filter { it in urls }
    }

    internal fun sessionUrls(): List<String> = uploadedUrls.filter { it in sessionUploads }

    /** すべて外す（保存後に呼ぶ想定。バケットからは消さない） */
    fun clear() {
        items.clear()
        sessionUploads.clear()
    }

    /** 送信済みの写真だけを外す（送信中に追加した写真は残す）。送信済みなのでバケットからは消さない */
    fun removeUploaded(urls: Collection<String>) {
        items.removeAll { it.status == PhotoStatus.DONE && it.url in urls }
        sessionUploads.removeAll(urls.toSet())
    }

    /** 保存できたとき：この入力中にアップロードした写真を「保存済み」とみなす（以後は消さない） */
    fun keepSessionUploads() {
        sessionUploads.clear()
    }

    /**
     * 保存せずに閉じるとき：この入力中にアップロードした写真をバケットから消す（失敗しても無視）。
     * 回転では呼ばないこと（キャンセル・戻るの操作から呼ぶ）
     */
    fun discardSessionUploads() {
        discarded = true
        Photos.deleteLater(bucket, sessionUploads.toList())
        sessionUploads.clear()
        items.clear()
    }

    fun add(uris: List<Uri>) {
        uris.forEach { uri ->
            val key = UUID.randomUUID().toString()
            items += PhotoItem(key, null, uri, PhotoStatus.UPLOADING)
            start(key, uri)
        }
    }

    fun retry(key: String) {
        val it = items.firstOrNull { p -> p.key == key } ?: return
        val uri = it.preview ?: return
        replace(key) { p -> p.copy(status = PhotoStatus.UPLOADING, error = null) }
        start(key, uri)
    }

    fun remove(key: String) {
        val p = items.firstOrNull { it.key == key } ?: return
        items.removeAll { it.key == key }
        // この入力中にアップロードした写真ならバケットからも消す（保存済みの写真は消さない）
        val url = p.url
        if (url != null && sessionUploads.remove(url)) Photos.deleteLater(bucket, listOf(url))
    }

    private fun replace(key: String, f: (PhotoItem) -> PhotoItem) {
        val i = items.indexOfFirst { it.key == key }
        if (i >= 0) items[i] = f(items[i])
    }

    private fun start(key: String, uri: Uri) {
        scope.launch {
            try {
                val bytes = Photos.jpeg(ctx, uri)
                val url = Photos.upload(bytes, bucket, Photos.randomName(namePrefix))
                if (discarded || items.none { it.key == key }) {
                    // アップロード中に外された・破棄された写真は残さない
                    Photos.deleteLater(bucket, listOf(url))
                } else {
                    sessionUploads += url
                    replace(key) { it.copy(url = url, status = PhotoStatus.DONE, error = null, offline = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                replace(key) { it.copy(status = PhotoStatus.ERROR, error = e.userMessage(), offline = Offline.isOffline(e)) }
            }
        }
    }
}

@Composable
fun rememberPhotoUploadState(bucket: String, namePrefix: String = ""): PhotoUploadState {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(bucket, namePrefix) { PhotoUploadState(ctx, scope, bucket, namePrefix) }
}

private const val SESSION_MARK = "session|"

/**
 * 回転・プロセス終了後も、アップロード済みの写真（URL）を残す版。
 * アップロード中・失敗の写真は、プロセスが終了すると消える（選び直しが必要）
 */
@Composable
fun rememberSaveablePhotoUploadState(bucket: String, namePrefix: String = ""): PhotoUploadState {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    // この入力中にアップロードした写真には印（SESSION_MARK）を付けて保存する
    return rememberSaveable(bucket, namePrefix, saver = listSaver<PhotoUploadState, String>(
        save = { st ->
            val session = st.sessionUrls().toSet()
            st.uploadedUrls.map { if (it in session) SESSION_MARK + it else it }
        },
        restore = { saved ->
            val urls = saved.map { it.removePrefix(SESSION_MARK) }
            val session = saved.filter { it.startsWith(SESSION_MARK) }.map { it.removePrefix(SESSION_MARK) }
            PhotoUploadState(ctx, scope, bucket, namePrefix).apply { restore(urls, session) }
        },
    )) { PhotoUploadState(ctx, scope, bucket, namePrefix) }
}

/**
 * 「撮影」「写真を選択」の2つのボタン。選んだ写真の Uri を返す
 * @param max 選べる枚数（1 なら1枚だけ）
 */
@Composable
fun PhotoSourceButtons(max: Int = 1, enabled: Boolean = true, onPick: (List<Uri>) -> Unit) {
    val ctx = LocalContext.current
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) onPick(listOf(Uri.parse(u)))
        cameraUri = null
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPick(listOf(uri))
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, max))) { uris ->
        if (uris.isNotEmpty()) onPick(uris.take(max))
    }
    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    fun launchCamera() {
        val f = Photos.newCameraFile(ctx)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        cameraUri = uri.toString()
        camera.launch(uri)
    }
    // アプリがカメラの権限を宣言しているため、撮影アプリを呼ぶ前にも許可が必要
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(ctx, "カメラの使用を許可してください（設定 → アプリ → ELDIA → 権限）", Toast.LENGTH_LONG).show()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = enabled, onClick = {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else permission.launch(Manifest.permission.CAMERA)
        }) {
            Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp))
            Text("撮影", Modifier.padding(start = 6.dp))
        }
        OutlinedButton(enabled = enabled, onClick = { if (max <= 1) pickOne.launch(request) else pickMany.launch(request) }) {
            Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp))
            Text("写真を選択", Modifier.padding(start = 6.dp))
        }
    }
}

/** 写真の一覧（サムネイル・アップロード中/失敗の表示・削除）と追加ボタン */
@Composable
fun PhotoEditor(state: PhotoUploadState, max: Int = 10, title: String = "写真") {
    var viewer by remember { mutableStateOf<Any?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (state.items.isNotEmpty()) {
                Text("${state.items.size}/$max", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.items.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.items, key = { it.key }) { p ->
                    PhotoThumb(p, onOpen = { viewer = p.url ?: p.preview }, onRetry = { state.retry(p.key) }, onRemove = { state.remove(p.key) })
                }
            }
        }
        if (state.items.size < max) {
            PhotoSourceButtons(max = max - state.items.size) { state.add(it) }
        }
        if (state.isUploading) {
            Text("アップロード中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (state.hasError) {
            Text("アップロードできなかった写真があります（タップで再試行）", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
    viewer?.let { PhotoViewerDialog(it) { viewer = null } }
}

@Composable
private fun PhotoThumb(p: PhotoItem, onOpen: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    Box(Modifier.size(88.dp)) {
        AsyncImage(
            model = p.preview ?: p.url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { if (p.status == PhotoStatus.ERROR) onRetry() else onOpen() },
        )
        when (p.status) {
            PhotoStatus.UPLOADING -> Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White) }
            PhotoStatus.ERROR -> Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f)).clickable { onRetry() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.ErrorOutline, "再試行", tint = Color.White) }
            PhotoStatus.DONE -> Unit
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Close, "削除", Modifier.size(14.dp), tint = Color.White) }
    }
}

/** 写真の拡大表示（URL または Uri） */
@Composable
fun PhotoViewerDialog(model: Any, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        text = { AsyncImage(model, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()) },
    )
}

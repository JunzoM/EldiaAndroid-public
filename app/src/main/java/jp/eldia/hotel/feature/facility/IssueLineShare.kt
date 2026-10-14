package jp.eldia.hotel.feature.facility

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** LINE などで送る不具合の報告文 */
fun issueReportText(issue: Issue, reporter: String): String {
    val site = IssueSite.from(issue.store)
    val lines = buildList {
        add("【不具合報告】")
        add("${site.label} ${locationLabel(issue.roomId, site)}（${issue.area}）")
        add("内容：${issue.title}")
        issue.issuePriority?.let { add("優先度：${it.label}") }
        issue.reportDate?.takeIf { it.isNotBlank() }?.let { add("報告日：$it") }
        reporter.takeIf { it.isNotBlank() }?.let { add("報告者：$it") }
        issue.memo?.takeIf { it.isNotBlank() }?.let { add("メモ：$it") }
        val photos = issue.photoList
        if (photos.isNotEmpty()) {
            add("写真：")
            photos.forEach { add(it) }
        }
    }
    return lines.joinToString("\n")
}

/** LINE 報告に渡す内容（写真はアップロード済みの URL か、端末内の写真） */
data class IssueShare(val text: String, val photoUrls: List<String> = emptyList(), val localPhotos: List<Uri> = emptyList())

private const val SHARE_DIR = "share"

/**
 * 写真を端末に用意して、報告文と画像を共有シートで渡す（LINE のグループを選んで送る）。
 * 画像と一緒だと LINE が文章を落とすことがあるため、報告文はクリップボードにも入れておく。
 */
suspend fun shareIssueReport(context: Context, share: IssueShare) {
    val clip = context.getSystemService(ClipboardManager::class.java)
    runCatching { clip?.setPrimaryClip(ClipData.newPlainText("不具合報告", share.text)) }

    val images = runCatching { prepareImages(context, share) }.getOrDefault(emptyList())
    val send = if (images.isEmpty()) {
        Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(images))
            // 受け取り側に読み取りを許可する
            clipData = ClipData.newRawUri("", images.first()).also { c -> images.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    send.putExtra(Intent.EXTRA_TEXT, share.text)
    try {
        context.startActivity(
            Intent.createChooser(send, "不具合をLINEで報告")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    } catch (_: ActivityNotFoundException) {
    }
}

/** 共有用の画像を cache/share に用意して、FileProvider の URI を返す（失敗した写真は飛ばす） */
private suspend fun prepareImages(context: Context, share: IssueShare): List<Uri> = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, SHARE_DIR)
    dir.deleteRecursively()
    dir.mkdirs()
    coroutineScope {
        val remote = share.photoUrls.mapIndexed { i, url ->
            async { runCatching { download(url, File(dir, "photo_${i + 1}.jpg")) }.getOrNull() }
        }
        val local = share.localPhotos.mapIndexed { i, uri ->
            async {
                runCatching {
                    val f = File(dir, "local_${i + 1}.jpg")
                    context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                    f
                }.getOrNull()
            }
        }
        (remote + local).awaitAll().filterNotNull()
    }.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
}

private fun download(url: String, to: File): File {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        if (conn.responseCode !in 200..299) error("download failed ${conn.responseCode}")
        conn.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        return to
    } finally {
        conn.disconnect()
    }
}

package jp.eldia.hotel.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** サーバーが受け取りを断った（電波の問題ではない）アップロードのエラー */
class UploadException(message: String) : Exception(message)

/** 写真の縮小とアップロード（iOS版 ImageCompressor / StorageUploader と同じ） */
object Photos {
    /** 長辺 maxPx に縮小して JPEG 化（Web版 compressImage: 1024px / 0.82 と同等）。撮影時の向きも反映される */
    suspend fun jpeg(ctx: Context, uri: Uri, maxPx: Int = 1024, quality: Int = 82): ByteArray = withContext(Dispatchers.IO) {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1.0, maxPx.toDouble() / max(w, h))
            decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    fun randomName(prefix: String = ""): String =
        "$prefix${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.jpg"

    /**
     * 共通プロジェクトのバケットへアップロードして公開 URL を返す。
     * 電波がないときは IOException（Offline.isOffline で判定できる）、サーバーが断ったときは UploadException。
     */
    suspend fun upload(bytes: ByteArray, bucket: String, path: String): String = withContext(Dispatchers.IO) {
        // 匿名キーでは送らない（ストレージはログイン必須）。期限が近ければ更新してから使う
        val token = freshAccessToken() ?: throw UploadException("ログインが必要です")
        val conn = URL("${AppConfig.SUPABASE_URL}/storage/v1/object/$bucket/$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("apikey", AppConfig.SUPABASE_ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "image/jpeg")
            conn.setRequestProperty("cache-control", "3600")
            conn.setRequestProperty("x-upsert", "false")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                val msg = body?.let {
                    runCatching { Offline.json.parseToJsonElement(it).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                }
                throw UploadException(msg ?: "アップロード失敗 ($code)")
            }
            "${AppConfig.SUPABASE_URL}/storage/v1/object/public/$bucket/$path"
        } finally {
            conn.disconnect()
        }
    }

    /** 画面を閉じたあとも削除を続けるためのスコープ */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 公開 URL（.../storage/v1/object/public/{bucket}/{path}）からバケット内のパスを取り出す */
    fun pathOf(bucket: String, url: String): String? {
        val marker = "/storage/v1/object/public/$bucket/"
        val i = url.indexOf(marker)
        if (i < 0) return null
        return url.substring(i + marker.length).substringBefore("?").takeIf { it.isNotBlank() }
    }

    /**
     * アップロード済みの写真をバケットから消す（保存しなかった写真の後片付け）。
     * 失敗しても何もしない（電波なし・権限なしなど）。@return 消せたら true
     */
    suspend fun delete(bucket: String, url: String): Boolean = withContext(Dispatchers.IO) {
        val path = pathOf(bucket, url) ?: return@withContext false
        try {
            val token = freshAccessToken() ?: return@withContext false
            val conn = URL("${AppConfig.SUPABASE_URL}/storage/v1/object/$bucket/$path").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "DELETE"
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("apikey", AppConfig.SUPABASE_ANON_KEY)
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** 削除を裏で行う（画面が閉じても続ける・結果は待たない） */
    fun deleteLater(bucket: String, urls: Collection<String>) {
        if (urls.isEmpty()) return
        val list = urls.toList()
        bgScope.launch { list.forEach { delete(bucket, it) } }
    }

    /** カメラで撮った写真を一時的に置くファイル（FileProvider の cache-path "photos/"） */
    fun newCameraFile(ctx: Context): File {
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        // 古い一時ファイルは片付ける（1日以上前のもの）
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        return File(dir, "camera-${System.currentTimeMillis()}.jpg")
    }
}

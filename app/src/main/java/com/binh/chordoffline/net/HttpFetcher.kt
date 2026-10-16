package com.binh.chordoffline.net

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Kết quả một lần tải. Chỉ giữ thông tin kỹ thuật cần hiển thị — KHÔNG giữ cookie,
 * header Set-Cookie hay bất kỳ header nào khác ngoài Content-Type.
 */
data class FetchResult(
    val requestedUrl: String,
    val finalUrl: String?,
    val status: Int?,
    val statusMessage: String?,
    val protocol: String?,
    val contentType: String?,
    val bytes: Long,
    val elapsedMs: Long,
    val redirects: Int,
    val truncated: Boolean,
    val fetchedAtMillis: Long,
    val body: String?,
    val error: String?,
) {
    val ok: Boolean get() = error == null && status != null && status in 200..299
}

class HttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(false)          // không cho phép hạ từ https xuống http
        .cookieJar(CookieJar.NO_COOKIES)    // không nhận/gửi/lưu cookie
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        /** User-Agent trung thực, không giả làm trình duyệt. */
        const val USER_AGENT = "ChordOffline/0.1 (Android; personal offline viewer; one request per user action)"
        const val MAX_BYTES = 8L * 1024 * 1024
    }

    fun fetch(url: String): FetchResult {
        val started = System.nanoTime()
        val at = System.currentTimeMillis()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                .header("Accept-Language", "vi,en;q=0.7")
                .get().build()
            client.newCall(req).execute().use { resp ->
                var redirects = 0
                var prior = resp.priorResponse
                while (prior != null) { redirects++; prior = prior.priorResponse }
                val body = resp.body
                val mediaType = body?.contentType()
                val (bytes, truncated) = body?.byteStream()?.use { readLimited(it, MAX_BYTES) } ?: (ByteArray(0) to false)
                val charset: Charset = mediaType?.charset() ?: Charsets.UTF_8
                FetchResult(
                    requestedUrl = url,
                    finalUrl = resp.request.url.toString(),
                    status = resp.code,
                    statusMessage = resp.message.ifBlank { null },
                    protocol = resp.protocol.toString(),
                    contentType = resp.header("Content-Type"),
                    bytes = bytes.size.toLong(),
                    elapsedMs = ms(),
                    redirects = redirects,
                    truncated = truncated,
                    fetchedAtMillis = at,
                    body = String(bytes, charset),
                    error = null,
                )
            }
        } catch (e: IOException) {
            FetchResult(url, null, null, null, null, null, 0, ms(), 0, false, at, null, "${e.javaClass.simpleName}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            FetchResult(url, null, null, null, null, null, 0, ms(), 0, false, at, null, "URL không hợp lệ: ${e.message}")
        }
    }

    /** Tải dữ liệu nhị phân nhỏ (ảnh thu nhỏ YouTube). Trả về bytes hoặc null nếu lỗi/không phải ảnh. */
    fun fetchImage(url: String, max: Long = 2L * 1024 * 1024): ByteArray? = try {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        client.newCall(req).execute().use { resp ->
            val type = resp.header("Content-Type").orEmpty()
            if (!resp.isSuccessful || !type.startsWith("image/")) null
            else resp.body?.byteStream()?.use { readLimited(it, max) }?.takeIf { !it.second }?.first
        }
    } catch (e: IOException) { null } catch (e: IllegalArgumentException) { null }

    private fun readLimited(input: InputStream, max: Long): Pair<ByteArray, Boolean> {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (total + n > max) { out.write(buf, 0, (max - total).toInt()); return out.toByteArray() to true }
            out.write(buf, 0, n); total += n
        }
        return out.toByteArray() to false
    }
}

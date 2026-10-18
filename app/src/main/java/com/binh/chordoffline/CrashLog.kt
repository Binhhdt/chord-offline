package com.binh.chordoffline

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.suspendCancellableCoroutine

/** Ghi stack trace khi app crash để hiển thị ở lần mở sau (chỉ lưu trong máy, không gửi đi). */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(appCtx, previous))
    }

    private class Handler(val ctx: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                File(ctx.filesDir, FILE).writeText(
                    "Thời điểm: ${java.util.Date()}\nThread: ${t.name}\n" +
                        "Thiết bị: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        "App: ${BuildConfigInfo.VERSION}\n\n" + stackTrace(e)
                )
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()
    fun clear(context: Context) { File(context.filesDir, FILE).delete() }

    fun stackTrace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
}

object BuildConfigInfo { const val VERSION = "0.10.0" }

/** Số phiên bản đọc từ chính gói APK đang cài (luôn đúng với bản đang chạy). */
object AppVersion {
    fun name(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: BuildConfigInfo.VERSION

    fun code(ctx: Context): Long = runCatching {
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    }.getOrDefault(0L)

    /** "Phiên bản 0.10.0 (bản dựng 18)". */
    fun label(ctx: Context): String = "Phiên bản ${name(ctx)} (bản dựng ${code(ctx)})"
}

class ChordApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Chạy [block] trên một luồng riêng có stack lớn (64 MB). Luồng nền mặc định trên Android
 * chỉ có ~1 MB stack, dễ StackOverflowError khi phân tích trang HTML lớn. Mọi Throwable
 * (kể cả StackOverflowError/OutOfMemoryError) được trả về cho coroutine thay vì làm app crash.
 */
suspend fun <T> onBigStack(name: String, block: () -> T): T = suspendCancellableCoroutine { cont ->
    Thread(null, { cont.resumeWith(runCatching(block)) }, name, 64L * 1024 * 1024).start()
}

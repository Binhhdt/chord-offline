package com.binh.chordoffline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Dịch vụ tiền cảnh: giữ app chạy khi tắt màn hình / chuyển sang app khác trong lúc quét hoặc tải,
 * hiện tiến độ trên thanh thông báo. Việc tải thật vẫn do ViewModel làm (tuần tự, có nghỉ giữa các request).
 * Vuốt tắt app khỏi danh sách đa nhiệm thì dừng; trạng thái hàng đợi vẫn được lưu để tải tiếp.
 */
class DownloadService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChordOffline:download")
            .apply { setReferenceCounted(false); acquire(10L * 60 * 60 * 1000) }   // tối đa 10 giờ
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, intent?.getStringExtra(EXTRA_TITLE) ?: "Đang tải hợp âm", intent?.getStringExtra(EXTRA_TEXT) ?: "",
            intent?.getIntExtra(EXTRA_DONE, 0) ?: 0, intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        running = true
        return START_NOT_STICKY
    }

    /** Android 15 giới hạn thời gian dịch vụ dataSync: hết giờ thì tự dừng (hàng đợi vẫn lưu, mở app để tiếp tục). */
    override fun onTimeout(startId: Int) { stopSelf() }

    override fun onDestroy() {
        running = false
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "download"
        private const val ID = 42
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"
        @Volatile var running = false; private set

        private fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tải hợp âm", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Tiến độ quét danh mục và tải bài" })
        }

        private fun build(ctx: Context, title: String, text: String, done: Int, total: Int): Notification {
            channel(ctx)
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(total, done.coerceAtMost(total), total == 0)
                .setContentIntent(open)
                .build()
        }

        /** Hiện/cập nhật thông báo; lần đầu thì khởi động dịch vụ tiền cảnh. */
        fun show(ctx: Context, title: String, text: String, done: Int, total: Int) {
            if (!running) {
                val i = Intent(ctx, DownloadService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
                    .putExtra(EXTRA_DONE, done).putExtra(EXTRA_TOTAL, total)
                try { ctx.startForegroundService(i); running = true } catch (e: Exception) { /* app đang ở nền: bỏ qua */ }
            } else {
                runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, title, text, done, total)) }
            }
        }

        fun stop(ctx: Context) {
            if (running) ctx.stopService(Intent(ctx, DownloadService::class.java))
            running = false
        }
    }
}

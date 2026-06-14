package com.inbox.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.DataOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 静默分享接收 — 无界面、无跳转、无闪烁。
 * 一进入立刻 finish()，所有操作在后台线程完成。
 */
class ShareReceiver : AppCompatActivity() {

    companion object {
        private const val API_URL = "https://inbox.oolool.com/api/inbox/share"
        private const val BOUNDARY = "----InboxBoundary"
        private const val LINE_END = "\r\n"
        private const val CHANNEL_ID = "inbox_share"
        private const val NOTIF_ID = 1001
    }

    private lateinit var db: InboxDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        // 抑制入场动画
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
        db = InboxDatabase(this)
        createNotificationChannel()

        val action = intent?.action
        // 立即关闭界面——所有操作在后台完成
        finish()
        overridePendingTransition(0, 0)

        when (action) {
            Intent.ACTION_SEND -> handleSingleShare(intent!!)
            Intent.ACTION_SEND_MULTIPLE -> handleMultiShare(intent!!)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "收件箱分享",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "收件箱文件接收通知" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun notify(title: String, text: String, success: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val icon = if (success) android.R.drawable.stat_sys_upload_done
                   else android.R.drawable.stat_sys_warning
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(icon).setContentTitle(title).setContentText(text)
                    .setAutoCancel(true).setContentIntent(pi).build())
        } catch (_: SecurityException) {}
    }

    private fun handleSingleShare(intent: Intent) {
        Thread {
            val type = intent.type ?: "text/plain"
            if (type.startsWith("text/")) {
                val text = buildString {
                    intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { append("$it\n\n") }
                    append(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
                }
                if (text.isBlank()) return@Thread
                val ok = postText(text)
                val name = text.take(30).replace("\n", " ") + ".md"
                db.insert(HistoryEntry(fileName = name, fileType = "text",
                    fileSize = text.length.toLong(), success = ok))
                notify(if (ok) "📥 已存入收件箱" else "❌ 存入失败", name, ok)
            } else {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return@Thread
                val name = getFileName(uri, type)
                val size = try { contentResolver.openInputStream(uri)?.available() ?: 0 } catch(_: Exception) { 0 }
                val ok = postFile(uri, type)
                db.insert(HistoryEntry(fileName = name, fileType = type,
                    fileSize = size.toLong(), success = ok))
                notify(if (ok) "📥 已存入收件箱" else "❌ 存入失败", name, ok)
            }
        }.start()
    }

    private fun handleMultiShare(intent: Intent) {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        Thread {
            var ok = 0
            for (uri in uris) {
                val type = contentResolver.getType(uri) ?: "application/octet-stream"
                val name = getFileName(uri, type)
                val size = try { contentResolver.openInputStream(uri)?.available() ?: 0 } catch(_: Exception) { 0 }
                val r = postFile(uri, type)
                db.insert(HistoryEntry(fileName = name, fileType = type,
                    fileSize = size.toLong(), success = r))
                if (r) ok++
            }
            notify("📥 已存入 $ok/${uris.size} 项", "收件箱收到 ${uris.size} 个文件", ok == uris.size)
        }.start()
    }

    private fun postText(text: String): Boolean = try {
        val data = buildString {
            append("--$BOUNDARY$LINE_END")
            append("Content-Disposition: form-data; name=\"text\"$LINE_END")
            append("Content-Type: text/plain; charset=UTF-8$LINE_END")
            append(LINE_END); append(text)
            append("$LINE_END--$BOUNDARY--$LINE_END")
        }.toByteArray(Charsets.UTF_8)
        httpPost(data)
    } catch (_: Exception) { false }

    private fun postFile(uri: Uri, mimeType: String): Boolean = try {
        contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            val name = getFileName(uri, mimeType)
            val header = "--$BOUNDARY$LINE_END" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$name\"$LINE_END" +
                    "Content-Type: $mimeType$LINE_END$LINE_END"
            val footer = "$LINE_END--$BOUNDARY--$LINE_END"
            httpPost(header.toByteArray() + bytes + footer.toByteArray())
        } ?: false
    } catch (_: Exception) { false }

    private fun httpPost(data: ByteArray): Boolean {
        val url = URL(API_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
        conn.connectTimeout = 30000; conn.readTimeout = 30000
        DataOutputStream(conn.outputStream).use { it.write(data) }
        val code = conn.responseCode
        conn.disconnect()
        return code == 200
    }

    private fun getFileName(uri: Uri, mimeType: String): String {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        val ext = when { mimeType.startsWith("image/") -> ".jpg"
            mimeType.startsWith("video/") -> ".mp4"
            mimeType.startsWith("audio/") -> ".m4a"
            else -> ".bin" }
        return "share_${System.currentTimeMillis()}$ext"
    }
}

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
 * 静默分享接收 — 无界面、无 Toast。
 * 发送成功后弹出通知，写入发送记录。
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
        super.onCreate(savedInstanceState)
        db = InboxDatabase(this)
        createNotificationChannel()

        when (intent?.action) {
            Intent.ACTION_SEND -> handleSingleShare(intent!!)
            Intent.ACTION_SEND_MULTIPLE -> handleMultiShare(intent!!)
            else -> finish()
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

    private fun sendNotification(title: String, text: String, success: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val icon = if (success) android.R.drawable.stat_sys_upload_done
                   else android.R.drawable.stat_sys_warning

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notif)
        } catch (_: SecurityException) {
            // 没有通知权限，静默忽略
        }
    }

    private fun handleSingleShare(intent: Intent) {
        val type = intent.type ?: "text/plain"
        if (type.startsWith("text/")) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
            val text = if (subject.isNotEmpty()) "$subject\n\n$sharedText" else sharedText
            if (text.isBlank()) { finish(); return }

            Thread {
                val ok = postText(text)
                val name = text.take(30).replace("\n", " ") + ".md"
                db.insert(HistoryEntry(
                    fileName = name, fileType = "text",
                    fileSize = text.length.toLong(), success = ok
                ))
                runOnUiThread {
                    sendNotification(
                        if (ok) "📥 已存入收件箱" else "❌ 存入失败",
                        name, ok
                    )
                    finish()
                }
            }.start()
        } else {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: run { finish(); return }
            Thread {
                val fileName = getFileName(uri, type)
                val size = try { contentResolver.openInputStream(uri)?.available() ?: 0 } catch(_: Exception) { 0 }
                val ok = postFile(uri, type)
                db.insert(HistoryEntry(fileName = fileName, fileType = type, fileSize = size.toLong(), success = ok))
                runOnUiThread {
                    sendNotification(if (ok) "📥 已存入收件箱" else "❌ 存入失败", fileName, ok)
                    finish()
                }
            }.start()
        }
    }

    private fun handleMultiShare(intent: Intent) {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: run { finish(); return }
        var success = 0
        Thread {
            for (uri in uris) {
                val type = contentResolver.getType(uri) ?: "application/octet-stream"
                val fileName = getFileName(uri, type)
                val size = try { contentResolver.openInputStream(uri)?.available() ?: 0 } catch(_: Exception) { 0 }
                val ok = postFile(uri, type)
                db.insert(HistoryEntry(fileName = fileName, fileType = type, fileSize = size.toLong(), success = ok))
                if (ok) success++
            }
            val total = uris.size
            val finalSuccess = success
            runOnUiThread {
                sendNotification("📥 已存入 $finalSuccess/$total 项", "收件箱收到 $total 个文件", finalSuccess == total)
                finish()
            }
        }.start()
    }

    private fun postText(text: String): Boolean = try {
        httpPost(buildTextFormData(text))
    } catch (_: Exception) { false }

    private fun postFile(uri: Uri, mimeType: String): Boolean = try {
        contentResolver.openInputStream(uri)?.use { stream ->
            httpPost(buildFileFormData(stream, getFileName(uri, mimeType), mimeType))
        } ?: false
    } catch (_: Exception) { false }

    private fun httpPost(data: ByteArray): Boolean {
        val url = URL(API_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
        conn.setRequestProperty("Connection", "close")
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        DataOutputStream(conn.outputStream).use { it.write(data) }
        return conn.responseCode == 200
    }

    private fun buildTextFormData(text: String): ByteArray = buildString {
        append("--$BOUNDARY$LINE_END")
        append("Content-Disposition: form-data; name=\"text\"$LINE_END")
        append("Content-Type: text/plain; charset=UTF-8$LINE_END")
        append(LINE_END)
        append(text)
        append("$LINE_END--$BOUNDARY--$LINE_END")
    }.toByteArray(Charsets.UTF_8)

    private fun buildFileFormData(stream: InputStream, fileName: String, mimeType: String): ByteArray {
        val bytes = stream.readBytes()
        val header = buildString {
            append("--$BOUNDARY$LINE_END")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"$LINE_END")
            append("Content-Type: $mimeType$LINE_END")
            append(LINE_END)
        }.toByteArray(Charsets.UTF_8)
        val footer = "$LINE_END--$BOUNDARY--$LINE_END".toByteArray(Charsets.UTF_8)
        return header + bytes + footer
    }

    private fun getFileName(uri: Uri, mimeType: String): String {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        val ext = when {
            mimeType.startsWith("image/") -> ".jpg"
            mimeType.startsWith("video/") -> ".mp4"
            mimeType.startsWith("audio/") -> ".m4a"
            else -> ".bin"
        }
        return "share_${System.currentTimeMillis()}$ext"
    }
}

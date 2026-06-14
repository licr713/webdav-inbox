package com.inbox.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 分享接收 Activity — 从任何 App 接收分享内容并自动发送到收件箱。
 * 透明主题，用户无感知，完成后自动关闭。
 */
class ShareReceiver : AppCompatActivity() {

    companion object {
        private const val API_URL = "https://inbox.oolool.com/api/inbox/share"
        private const val BOUNDARY = "----InboxBoundary"
        private const val LINE_END = "\r\n"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            Intent.ACTION_SEND -> handleSingleShare(intent!!)
            Intent.ACTION_SEND_MULTIPLE -> handleMultiShare(intent!!)
            else -> {
                Toast.makeText(this, "不支持的内容类型", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun handleSingleShare(intent: Intent) {
        val type = intent.type ?: "text/plain"

        if (type.startsWith("text/")) {
            // 文字/链接分享
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
            val text = if (sharedSubject.isNotEmpty()) {
                "$sharedSubject\n\n$sharedText"
            } else {
                sharedText
            }

            if (text.isBlank()) {
                Toast.makeText(this, "内容为空", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            Thread {
                val result = postText(text)
                runOnUiThread {
                    if (result) {
                        Toast.makeText(this, "✅ 已存入收件箱", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "❌ 存入失败", Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            }.start()

        } else {
            // 文件分享（单文件）
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri == null) {
                Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            Thread {
                val result = postFile(uri, type)
                runOnUiThread {
                    if (result) {
                        Toast.makeText(this, "✅ 已存入收件箱", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "❌ 存入失败", Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            }.start()
        }
    }

    private fun handleMultiShare(intent: Intent) {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (uris.isNullOrEmpty()) {
            Toast.makeText(this, "没有文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        var successCount = 0
        Thread {
            for (uri in uris) {
                val type = contentResolver.getType(uri) ?: "application/octet-stream"
                if (postFile(uri, type)) {
                    successCount++
                }
            }
            val finalCount = successCount
            val total = uris.size
            runOnUiThread {
                val msg = if (finalCount == total) {
                    "✅ 已存入 $total 项"
                } else {
                    "⚠️ 存入 $finalCount/$total 项"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    private fun postText(text: String): Boolean {
        try {
            val formData = buildTextFormData(text)
            return httpPost(formData)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun postFile(uri: Uri, mimeType: String): Boolean {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return false
            val fileName = getFileName(uri, mimeType)
            val formData = buildFileFormData(inputStream, fileName, mimeType)
            inputStream.close()
            return httpPost(formData)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun httpPost(formData: ByteArray): Boolean {
        val url = URL(API_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
        conn.setRequestProperty("Connection", "close")
        conn.connectTimeout = 30000
        conn.readTimeout = 30000

        val outputStream = DataOutputStream(conn.outputStream)
        outputStream.write(formData)
        outputStream.flush()
        outputStream.close()

        val responseCode = conn.responseCode
        conn.disconnect()
        return responseCode == 200
    }

    private fun buildTextFormData(text: String): ByteArray {
        val sb = StringBuilder()
        sb.append("--$BOUNDARY$LINE_END")
        sb.append("Content-Disposition: form-data; name=\"text\"$LINE_END")
        sb.append("Content-Type: text/plain; charset=UTF-8$LINE_END")
        sb.append(LINE_END)
        sb.append(text)
        sb.append("$LINE_END--$BOUNDARY--$LINE_END")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildFileFormData(stream: InputStream, fileName: String, mimeType: String): ByteArray {
        val fileBytes = stream.readBytes()
        val sb = StringBuilder()
        sb.append("--$BOUNDARY$LINE_END")
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"$LINE_END")
        sb.append("Content-Type: $mimeType$LINE_END")
        sb.append(LINE_END)
        val header = sb.toString().toByteArray(Charsets.UTF_8)
        val footer = "$LINE_END--$BOUNDARY--$LINE_END".toByteArray(Charsets.UTF_8)
        return header + fileBytes + footer
    }

    private fun getFileName(uri: Uri, mimeType: String): String {
        // Try to get display name from content resolver
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        // Fallback: generate from timestamp and mime type
        val ext = when {
            mimeType.startsWith("image/") -> ".jpg"
            mimeType.startsWith("video/") -> ".mp4"
            mimeType.startsWith("audio/") -> ".m4a"
            else -> ".bin"
        }
        return "share_${System.currentTimeMillis()}$ext"
    }
}

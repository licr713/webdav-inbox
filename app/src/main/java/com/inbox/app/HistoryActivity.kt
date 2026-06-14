package com.inbox.app

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

/** 发送记录页面 */
class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = InboxDatabase(this)
        val records = db.getAll()

        if (records.isEmpty()) {
            val tv = TextView(this).apply {
                text = "暂无发送记录"
                textSize = 16f
                setPadding(40, 80, 40, 40)
                gravity = android.view.Gravity.CENTER
                setTextColor(0xff8b949e.toInt())
            }
            setContentView(tv)
            return
        }

        val dateFmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        val items = records.map { entry ->
            val ts = dateFmt.format(Date(entry.timestamp))
            val icon = when {
                entry.fileType == "text" -> "📝"
                entry.fileType.startsWith("image/") -> "🖼️"
                entry.fileType.startsWith("video/") -> "🎬"
                entry.fileType.startsWith("audio/") -> "🎵"
                else -> "📄"
            }
            val status = if (entry.success) "✅" else "❌"
            val size = when {
                entry.fileSize > 1024 * 1024 -> "${entry.fileSize / 1024 / 1024}MB"
                entry.fileSize > 1024 -> "${entry.fileSize / 1024}KB"
                else -> "${entry.fileSize}B"
            }
            "$status $icon $ts  ${entry.fileName}  ($size)"
        }

        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xff0d1117.toInt())
        }

        val title = TextView(this).apply {
            text = "📥 发送记录 (${records.size})"
            textSize = 20f
            setPadding(0, 0, 0, 24)
            setTextColor(0xffe6edf3.toInt())
        }
        ll.addView(title)

        for (item in items) {
            val tv = TextView(this).apply {
                text = item
                textSize = 14f
                setPadding(8, 10, 8, 10)
                setTextColor(0xffc9d1d9.toInt())
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            ll.addView(tv)
        }

        val scroll = ScrollView(this)
        scroll.addView(ll)
        setContentView(scroll)
    }
}

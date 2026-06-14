package com.inbox.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 发送记录数据模型 */
data class HistoryEntry(
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val fileName: String,
    val fileType: String,    // "text", "image", "video", "audio", "file"
    val fileSize: Long = 0,
    val success: Boolean = true
)

/** 发送记录数据库 */
class InboxDatabase(context: Context) : SQLiteOpenHelper(
    context, "inbox_history.db", null, 1
) {
    companion object {
        const val TABLE = "history"
        const val COL_ID = "id"
        const val COL_TS = "ts"
        const val COL_NAME = "name"
        const val COL_TYPE = "type"
        const val COL_SIZE = "size"
        const val COL_OK = "ok"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TS INTEGER NOT NULL,
                $COL_NAME TEXT NOT NULL,
                $COL_TYPE TEXT NOT NULL,
                $COL_SIZE INTEGER DEFAULT 0,
                $COL_OK INTEGER DEFAULT 1
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    fun insert(entry: HistoryEntry) {
        val cv = ContentValues().apply {
            put(COL_TS, entry.timestamp)
            put(COL_NAME, entry.fileName)
            put(COL_TYPE, entry.fileType)
            put(COL_SIZE, entry.fileSize)
            put(COL_OK, if (entry.success) 1 else 0)
        }
        writableDatabase.insert(TABLE, null, cv)
    }

    fun getAll(): List<HistoryEntry> {
        val list = mutableListOf<HistoryEntry>()
        val cursor = readableDatabase.query(
            TABLE, null, null, null, null, null, "$COL_TS DESC", "100"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(HistoryEntry(
                    id = it.getLong(0),
                    timestamp = it.getLong(1),
                    fileName = it.getString(2),
                    fileType = it.getString(3),
                    fileSize = it.getLong(4),
                    success = it.getInt(5) == 1
                ))
            }
        }
        return list
    }
}

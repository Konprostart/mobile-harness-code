package com.mobileharness.code.devtools

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

/**
 * Persistent crash-history store for the Developer Tools panel.
 *
 * A global [CrashHandler] writes one JSON file per uncaught exception into
 * `filesDir/crashlogs/` (private app storage, never in the volatile cache), and this
 * store reads them back newest-first, enforcing a ring buffer so a crash loop cannot
 * fill the disk.
 *
 * The file-level functions take a [File] directory rather than a [Context] so the
 * persistence logic is unit-testable against a temp directory.
 */
data class CrashRecord(
    val id: String,
    val timestampMillis: Long,
    val appVersion: String,
    val threadName: String,
    val throwableSummary: String,
    val stackTrace: String,
) {
    companion object {
        fun from(thread: Thread?, throwable: Throwable, appVersion: String): CrashRecord {
            val writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            return CrashRecord(
                id = UUID.randomUUID().toString(),
                timestampMillis = System.currentTimeMillis(),
                appVersion = appVersion,
                threadName = thread?.name ?: "unknown",
                throwableSummary = "${throwable.javaClass.name}: ${throwable.message.orEmpty()}".trim(),
                stackTrace = writer.toString(),
            )
        }
    }
}

object CrashLogStore {
    /** Hard cap on retained crash files; oldest entries are evicted first. */
    const val MAX_RECORDS = 20

    fun directory(context: Context): File = File(context.filesDir, "crashlogs")

    fun write(context: Context, record: CrashRecord) = write(directory(context), record)

    fun write(dir: File, record: CrashRecord) {
        runCatching { dir.mkdirs() }
        val file = File(dir, "crash_${record.timestampMillis}_${record.id}.txt")
        runCatching {
            file.writeText(
                JSONObject()
                    .put("id", record.id)
                    .put("timestampMillis", record.timestampMillis)
                    .put("appVersion", record.appVersion)
                    .put("threadName", record.threadName)
                    .put("throwableSummary", record.throwableSummary)
                    .put("stackTrace", record.stackTrace)
                    .toString(),
            )
        }
        trim(dir)
    }

    fun read(context: Context): List<CrashRecord> = read(directory(context))

    fun read(dir: File): List<CrashRecord> {
        val records = dir.listFiles().orEmpty().mapNotNull { file ->
            runCatching {
                val obj = JSONObject(file.readText())
                CrashRecord(
                    id = obj.optString("id"),
                    timestampMillis = obj.optLong("timestampMillis"),
                    appVersion = obj.optString("appVersion"),
                    threadName = obj.optString("threadName"),
                    throwableSummary = obj.optString("throwableSummary"),
                    stackTrace = obj.optString("stackTrace"),
                )
            }.getOrNull()
        }
        return records.sortedByDescending { it.timestampMillis }
    }

    fun clear(context: Context) = clear(directory(context))

    fun clear(dir: File) {
        dir.listFiles().orEmpty().forEach { runCatching { it.delete() } }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles().orEmpty().sortedByDescending { timestampOf(it.name) }
        if (files.size <= MAX_RECORDS) return
        files.drop(MAX_RECORDS).forEach { runCatching { it.delete() } }
    }

    private fun timestampOf(fileName: String): Long =
        Regex("""crash_(\d+)_""").find(fileName)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
}

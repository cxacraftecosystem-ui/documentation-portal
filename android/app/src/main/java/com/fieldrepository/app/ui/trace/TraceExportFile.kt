package com.fieldrepository.app.ui.trace

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * **WHERE A SAVED TRACE LANDS, AND HOW THE ATTACHED ONE LEAVES THIS PANEL.**
 *
 * Two doors, and the difference between them is the whole of what a researcher needs to understand
 * about this feature:
 *
 *  * [traceSaveExport] writes to the device's public **Downloads** folder and offers the share sheet.
 *    Nothing reaches a record, nothing is uploaded, nothing syncs.
 *  * [traceAttachFile] writes ONE file into this app's own `filesDir` and returns a `content://` Uri
 *    for it. The panel hands that Uri to the host's `onAttach` and forgets it.
 *
 * ── THE ATTACH DOOR IS ONE Uri AND NOTHING ELSE, WHICH IS PROPERTY 1 OF THE FEATURE ───────────
 *
 * **This package contains no uploader, no queue and no record call, and it must stay that way.** The
 * host puts the returned Uri through the ordinary media door — the same `uploadMedia` a camera capture
 * takes — and eager pre-upload, per-file retry and the offline store then apply to it for free. A panel
 * that uploaded its own file would be a SECOND upload path to keep working offline, in an application
 * whose whole point is working offline, and the two would drift the first time one of them was fixed.
 *
 * It also never touches the photograph. The source is read, twice, for pixels; nothing writes to it,
 * re-encodes it or replaces it.
 *
 * ── WHY THE SAVE ROUTE IS WRITTEN HERE RATHER THAN CALLED FROM THE REPOSITORY ─────────────────
 *
 * `data/FieldRepository.kt` has a MediaStore writer of its own and it is `private`, and this work is
 * not permitted to edit that file to widen it. So this is a second implementation of a small,
 * well-understood platform dance, and that is a real cost worth naming: two copies of a MediaStore
 * insert are two places for the `IS_PENDING` handshake to be got wrong. It is bounded by writing down
 * exactly what the copy must do — write completely, `fd.sync()`, THEN publish — and by the fact that
 * the surface it serves is one card. **If these two are ever consolidated, the repository's is the one
 * to keep**: it is older, it already carries the pre-Q permission path, and it is the one five other
 * screens call.
 *
 * ── WRITE COMPLETELY, SYNC, THEN PUBLISH ──────────────────────────────────────────────────────
 *
 * The bytes go to a temp file, are flushed, and the file descriptor is SYNCED before anything is copied
 * into a MediaStore row that becomes visible to every app on the device the moment `IS_PENDING` clears.
 * A copy whose source bytes are still only in the page cache publishes a TRUNCATED file — which
 * Illustrator reports as a corrupt SVG, indistinguishable from every other way a trace can go wrong.
 * The temp file is deleted in a `finally`, so a failed save does not leave a half-built drawing filling
 * the cache directory of a phone that is already short of space.
 *
 * ── ONE SAVE AT A TIME, AND THAT IS THE CALLER'S JOB ──────────────────────────────────────────
 *
 * Two MediaStore writes racing into one folder is how one of them ends up truncated with no error
 * anywhere. [TraceExportCard] holds the flag that serialises this feature's writes. This function does
 * not serialise anything itself, because a lock here would be invisible to every other writer in the
 * app and would therefore be a false sense of one.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Where the temp file goes
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The cache subdirectory a half-written export lives in.
 *
 * A SUBDIRECTORY AND NOT `cacheDir` ITSELF. Other download paths in this app write their temp files
 * straight into `cacheDir` under their own names. Those cannot collide with a trace export's today —
 * they are named after reports and datasets and this one is named after a photograph — but "cannot
 * collide today" is a property of two naming rules that were never written to agree, and the failure if
 * they ever do is one export's bytes inside another export's file. A directory of our own costs nothing
 * and removes the question.
 */
private const val TRACE_EXPORT_CACHE_DIR = "trace-export"

/**
 * Where the ONE attachable file is written, under `filesDir`.
 *
 * ── `filesDir` AND NOT `cacheDir`, WHICH IS THE LOAD-BEARING HALF OF THIS CONSTANT ────────────
 *
 * The file handed to `onAttach` has to survive until the host has finished with it, and the host may
 * queue it: this app works offline, so an attachment can sit in an outbox for a fortnight before its
 * bytes are ever read. Android reclaims `cacheDir` under storage pressure WITHOUT ASKING, so a drawing
 * staged there could simply cease to exist between the press and the upload — and the record would then
 * name a file nothing can open.
 *
 * `res/xml/file_paths.xml` publishes `files-path name="capture_files" path="."`, so a file here is
 * reachable through the app's `FileProvider` and a `content://` Uri for it can be granted to whatever
 * reads it.
 */
private const val TRACE_ATTACH_DIR = "trace-attach"

/* ────────────────────────────────────────────────────────────────────────────
 * The attach door
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Write [svg] as a file this device holds and return a `content://` Uri for it.
 *
 * ── WHAT THE CALLER OWNS AFTERWARDS ───────────────────────────────────────────────────────────
 *
 * **This function does not delete the file and the panel does not either.** The Uri outlives the panel:
 * `onAttach` hands it to a host that may upload it now, queue it for a fortnight, or show it in a list
 * first. Deleting on a timer, on a callback, or when the panel closes would each be this package
 * guessing at a lifetime it cannot see, and the guess that is wrong costs a record a file it names and
 * cannot open. The bytes are an SVG of line art — kilobytes, not megabytes — so the cost of being
 * generous here is small and the cost of being clever is not.
 *
 * ── THE NAME IS THE PHOTOGRAPH'S, AND UNIQUENESS IS A TIMESTAMP AND NOT A COUNTER ─────────────
 *
 * [traceExportFileName] names the file after the photograph so a reviewer can tell which one it came
 * from. Two traces of one photograph in one session would otherwise write the same path twice, and the
 * second would overwrite the first — which is fine for the file on disk and NOT fine for a Uri the host
 * may still be holding for the first. So the directory is stamped with the moment of writing. A counter
 * would need state that survives the panel; the clock does not.
 *
 * @return the Uri, or null when the file could not be written. The caller says so in a sentence.
 */
internal suspend fun traceAttachFile(
    context: Context,
    svg: String,
    sourceName: String,
): Uri? = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val name = traceExportFileName(
        sourceName = sourceName,
        extension = "svg",
        suffix = TRACE_ATTACH_SUFFIX,
    )
    val dir = File(File(appContext.filesDir, TRACE_ATTACH_DIR), System.currentTimeMillis().toString())
    runCatching {
        dir.mkdirs()
        val target = File(dir, name)
        FileOutputStream(target).use { fos ->
            val buffered = BufferedOutputStream(fos)
            // `Charsets.UTF_8` and nothing else: the engine's writer encodes UTF-8, the SVG declares
            // `encoding="UTF-8"` in its own XML declaration, and a platform default charset here would
            // produce a file whose bytes disagree with its own header on any handset whose locale is
            // not UTF-8.
            buffered.write(svg.toByteArray(Charsets.UTF_8))
            buffered.flush()
            fos.fd.sync()
        }
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", target)
    }.getOrNull()
}

/** What to say when the one derived file could not be written to this device. */
const val TRACE_ATTACH_WRITE_FAILED: String =
    "The line art could not be written to this device, so nothing has been attached. The photograph " +
        "is unaffected. Free some space and trace it again."

/* ────────────────────────────────────────────────────────────────────────────
 * The result of a save
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Where a saved export landed and how to hand it on.
 *
 * [savedTo] is `"Downloads/<name>"` on Q and later, an absolute path below it — the same shape every
 * other download path in this app shows.
 *
 * [shareUri] is nullable and MUST be gated on rather than assumed. Below Q there is a real file and a
 * `FileProvider` can grant it; on Q and later the MediaStore row IS the Uri and there is one. A null
 * handed to `FileProvider` throws `IllegalArgumentException` at the moment somebody taps Share.
 *
 * [storedName] is the name MediaProvider ACTUALLY used, which is not always the one that was asked for:
 * a colliding `DISPLAY_NAME` is silently uniquified to `name (1).ext`. It is read back out of [savedTo]
 * rather than assumed, because showing the requested name beside a file that is not on disk is a lie
 * one screen further out.
 */
data class TraceExportSaved(
    val savedTo: String,
    val shareUri: Uri?,
    val storedName: String,
    val mime: String,
    val sizeBytes: Long,
)

/* ────────────────────────────────────────────────────────────────────────────
 * Saving
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Write [bytes] into the device's Downloads folder as [fileName] and say where it went.
 *
 * ── THROWS RATHER THAN REFUSES, WHICH IS THE OTHER WAY ROUND FROM THE EXPORTER ────────────────
 *
 * [TraceExportOutcome.Refused] exists because a writer that cannot write a format has a sentence
 * somebody wrote. A flash that is full, a revoked permission or a MediaStore insert that fails does
 * not: those are the cases nobody wrote a sentence for, which is exactly what an exception is for. The
 * card catches and prints.
 *
 * ── THE PRE-Q BRANCH IS NOT DEAD: `minSdk` IS 26 ──────────────────────────────────────────────
 *
 * Android 8, 9 and the first half of 10's fleet reach it. It writes into the public Downloads directory
 * directly, which on those versions needs `WRITE_EXTERNAL_STORAGE` — a permission this app's manifest
 * does NOT declare. So on those handsets the write throws, the card prints the exception's message, and
 * the attach door (which needs no permission at all, because it writes inside the app's own files
 * directory) still works. That is the honest outcome: the take-away copy is the optional half of this
 * feature and the record copy is not.
 */
suspend fun traceSaveExport(
    context: Context,
    bytes: ByteArray,
    fileName: String,
    mime: String,
): TraceExportSaved = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val staging = File(appContext.cacheDir, TRACE_EXPORT_CACHE_DIR).apply { mkdirs() }
    val tmp = File(staging, fileName)
    try {
        FileOutputStream(tmp).use { fos ->
            val buffered = BufferedOutputStream(fos)
            buffered.write(bytes)
            buffered.flush()
            // See the file header. Publishing a file whose bytes are still only in the page cache
            // publishes a truncated one.
            fos.fd.sync()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                // THE HANDSHAKE. While this is 1 the row is invisible to other apps, so nothing can
                // read a half-copied file; clearing it is what publishes.
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("This device would not create a Downloads entry.")
            resolver.openOutputStream(target)?.use { out ->
                tmp.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("This device would not open the Downloads file.")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(target, values, null, null)

            // The name MediaProvider settled on after any uniquifying, read back rather than assumed.
            val stored = traceStoredName(appContext, target) ?: fileName
            TraceExportSaved(
                savedTo = "Downloads/$stored",
                shareUri = target,
                storedName = stored,
                mime = mime,
                sizeBytes = bytes.size.toLong(),
            )
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            tmp.inputStream().use { input ->
                FileOutputStream(outFile).use { out -> input.copyTo(out) }
            }
            TraceExportSaved(
                savedTo = outFile.absolutePath,
                // A raw file below Q, so the app's own FileProvider is what can grant it.
                shareUri = runCatching {
                    FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        outFile,
                    )
                }.getOrNull(),
                // A raw file copy OVERWRITES rather than uniquifying, so the name asked for is the name
                // on disk. That is the one place the two branches genuinely differ.
                storedName = fileName,
                mime = mime,
                sizeBytes = bytes.size.toLong(),
            )
        }
    } finally {
        runCatching { tmp.delete() }
    }
}

/**
 * The `DISPLAY_NAME` MediaProvider actually used for [uri], or null when it would not say.
 *
 * Null is survivable and the caller falls back to the requested name: a name that is one character off
 * beside a file that exists is a smaller failure than a save reported as having failed.
 */
private fun traceStoredName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(MediaStore.Downloads.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val index = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }

/* ────────────────────────────────────────────────────────────────────────────
 * Sharing
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The `ACTION_SEND` intent for a saved export.
 *
 * THE FORMAT'S OWN MIME TYPE AND NOT A WILDCARD. `image/svg+xml`, `application/pdf`,
 * `application/postscript`, `image/vnd.dxf`, `image/png` — read off the table. A declared type is what
 * lets the receiving app offer the right handlers. A wildcard type would instead offer every app on the
 * phone.
 *
 * THAT WILDCARD IS DELIBERATELY NOT SPELLED OUT ABOVE. It contains a star followed by a slash, which
 * ends a KDoc block wherever it appears — including inside backticks. Describe it; do not type it.
 *
 * `FLAG_GRANT_READ_URI_PERMISSION` IS NOT OPTIONAL: without it the receiving app gets a Uri it has no
 * permission to read, and the share silently produces an empty attachment.
 *
 * NO `FLAG_ACTIVITY_NEW_TASK`. The chooser is started from the screen somebody is looking at and should
 * come back to it.
 *
 * ── WHAT THE SHARE SHEET CANNOT TELL US, WHICH THE CARD SAYS OUT LOUD ─────────────────────────
 *
 * `ACTION_SEND` is fire-and-forget: nothing here learns which target was chosen. Somebody with no
 * signal who picks a chat app gets a send that fails later, inside that app, and looks like this app's
 * defect. [TRACE_EXPORT_SHARE_CAVEAT] states that in words rather than trying to detect it, because the
 * chooser does not tell us and guessing would be worse.
 */
fun traceExportShareIntent(saved: TraceExportSaved): Intent? {
    val uri = saved.shareUri ?: return null
    return Intent(Intent.ACTION_SEND).apply {
        type = saved.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, saved.storedName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/**
 * What to say when there is no grantable Uri.
 *
 * NOT A DEAD END. The file is in the public Downloads folder and any file manager or share sheet can
 * pick it up from there.
 */
const val TRACE_EXPORT_NO_SHARE_SENTENCE: String =
    "Open it from the Downloads folder to send it — any app's file picker or share sheet will find " +
        "it there."

/**
 * The warning under the share button.
 *
 * The same fact about the same mechanism on the same phone that every other share control in this app
 * has to live with, so it is one wording rather than two accounts of one limitation.
 */
const val TRACE_EXPORT_SHARE_CAVEAT: String =
    "Pick nearby share, Bluetooth or a cable if you have no signal. A chat app will look like it " +
        "worked and then send nothing until you are back online — the share sheet does not tell this " +
        "app which one you chose, so it cannot warn you afterwards."

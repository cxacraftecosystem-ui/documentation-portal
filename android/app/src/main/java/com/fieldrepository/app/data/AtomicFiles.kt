package com.fieldrepository.app.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replace [target] with [bytes] in one step: write a sibling temp file, flush it to storage, then
 * move it over the real one.
 *
 * A plain `writeText` truncates the file first and then writes. A process killed between the two
 * (a low-memory kill, a flat battery, the app swiped away) leaves a truncated file, and the next read
 * turns it into an empty list. A move inside one directory is atomic, so a reader sees the old file
 * or the whole new one, never a mixture. The `sync()` matters as much as the move: a rename whose
 * bytes are still only in the page cache can publish an empty file after a power loss.
 *
 * `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` and not `File.renameTo`: the two agree on Android, but
 * `renameTo` refuses to replace an existing file on Windows, where the JVM unit tests run. If the
 * move fails, the old file is left exactly as it was. There is deliberately no delete-then-rename
 * fallback, because a failure between the two would leave no file at all, and a missing queue reads
 * as an empty one.
 *
 * The offline outbox and the staged-object journal both write through this.
 */
internal fun writeFileAtomically(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, "${target.name}.writing")
    try {
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Throwable) {
        runCatching { temp.delete() }
        throw e
    }
}

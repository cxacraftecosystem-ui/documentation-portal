package com.fieldrepository.app.ui

import java.io.File

internal fun repoFile(vararg relative: String): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        for (path in relative) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
        }
        dir = dir.parentFile
    }
    throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
}

internal fun repoSource(vararg relative: String): String =
    repoFile(*relative).readText(Charsets.UTF_8).replace("\r\n", "\n")

package com.fieldrepository.app.data

import java.util.concurrent.ConcurrentHashMap

object StagedObjects {
    private val deleted = ConcurrentHashMap.newKeySet<String>()
    private val kept = ConcurrentHashMap<String, StagedMedia>()

    fun claimDelete(objectKey: String): Boolean {
        if (!deleted.add(objectKey)) return false
        kept.entries.removeIf { it.value.objectKey == objectKey }
        return true
    }

    fun keep(source: String, staged: StagedMedia) {
        if (staged.objectKey !in deleted) kept[source] = staged
    }

    fun kept(source: String): StagedMedia? = kept[source]?.takeIf { it.objectKey !in deleted }

    fun settled(objectKey: String) {
        kept.entries.removeIf { it.value.objectKey == objectKey }
    }

    fun reset() {
        deleted.clear()
        kept.clear()
    }
}

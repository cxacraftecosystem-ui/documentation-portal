package com.fieldrepository.app.data

fun ToolDetailDto.toolArtisanIds(): List<String> {
    val ids = mutableListOf<String>()
    artisanId?.takeIf { it.isNotBlank() }?.let { ids += it }
    for (link in artisanLinks) {
        val id = link.artisan?.id ?: link.artisanId
        if (id.isNotBlank() && id !in ids) ids += id
    }
    return ids
}

fun ToolDetailDto.toolArtisanList(): List<String> {
    val names = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    val head = artisanId?.takeIf { it.isNotBlank() }
    val headName = artisanName.trim().ifEmpty { artisanLinks.firstOrNull { (it.artisan?.id ?: it.artisanId) == head }?.artisan?.name?.trim().orEmpty() }
    if (headName.isNotEmpty()) {
        names += headName
        head?.let { seen += it }
    }
    for (link in artisanLinks) {
        val id = link.artisan?.id ?: link.artisanId
        val name = link.artisan?.name?.trim().orEmpty()
        if (name.isEmpty() || id in seen || name in names) continue
        seen += id
        names += name
    }
    return names
}

fun ToolDetailDto.toolArtisanNames(): String? = toolArtisanList().takeIf { it.isNotEmpty() }?.joinToString(", ")

fun ToolDetailDto.toolArtisanLabel(): String = if (toolArtisanList().size > 1) "Artisans" else "Artisan"

fun ToolDetailDto.toolRowLabel(): String = toolArtisanNames()?.let { "$toolkitName · $it" } ?: toolkitName

fun ToolDetailDto.toolArtisanName(id: String): String? =
    artisanLinks.firstOrNull { (it.artisan?.id ?: it.artisanId) == id }?.artisan?.name?.trim()?.ifEmpty { null }
        ?: artisanName.trim().takeIf { it.isNotEmpty() && id == artisanId }

fun toolArtisansHeadFirst(tool: ToolDetailDto?, linked: List<ArtisanDto>): List<ArtisanDto> {
    if (tool == null) return linked.distinctBy { it.id }
    val pool = linked + tool.artisanLinks.mapNotNull { it.artisan }
    val head = tool.artisanId?.takeIf { it.isNotBlank() }?.let { id ->
        pool.firstOrNull { it.id == id }
            ?: tool.artisanName.trim().takeIf { it.isNotEmpty() }?.let { name -> ArtisanDto(id = id, name = name, place = tool.place, status = "PENDING") }
    }
    return (listOfNotNull(head) + linked).distinctBy { it.id }
}

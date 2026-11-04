package com.fieldrepository.app.data

fun ToolDetailDto.toolArtisanNames(): String? {
    val linked = artisanLinks.mapNotNull { it.artisan?.id }.toSet()
    val names = mutableListOf<String>()
    val primary = artisanName.trim()
    if (primary.isNotEmpty() && (artisanId == null || artisanId !in linked)) names += primary
    for (link in artisanLinks) {
        val name = link.artisan?.name?.trim().orEmpty()
        if (name.isNotEmpty() && name !in names) names += name
    }
    return names.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

fun ToolDetailDto.toolRowLabel(): String = toolArtisanNames()?.let { "$toolkitName · $it" } ?: toolkitName

package com.fieldrepository.app.data

import kotlinx.serialization.json.JsonObject

private fun String?.formText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun craftNameOf(id: String, crafts: List<CraftDto>, artisans: List<ArtisanDto>): String? =
    crafts.firstOrNull { it.id == id }?.name.formText()
        ?: artisans.firstOrNull { it.craft?.id == id }?.craft?.name.formText()
        ?: artisans.firstOrNull { it.craftId == id }?.craft?.name.formText()

internal fun linkedCraftName(
    craftIds: List<String>,
    typed: String?,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto> = emptyList(),
): String? = craftIds.firstOrNull()?.let { craftNameOf(it, crafts, artisans) } ?: typed.formText()

internal fun recordCraftName(
    craftIds: List<String>,
    typed: String?,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto> = emptyList(),
): String? = typed.formText()
    ?: craftIds.mapNotNull { craftNameOf(it, crafts, artisans) }.distinct().joinToString(", ").formText()

internal fun recordArtisanName(artisanIds: List<String>, typed: String?, artisans: List<ArtisanDto>): String? =
    typed.formText() ?: artisanIds.firstOrNull()?.let { id -> artisans.firstOrNull { it.id == id }?.name.formText() }

internal fun recordPlace(artisanIds: List<String>, typed: String?, artisans: List<ArtisanDto>): String? =
    typed.formText() ?: artisanIds.firstOrNull()?.let { id -> artisans.firstOrNull { it.id == id }?.place.formText() }

internal data class ToolFormFields(
    val toolkitName: String,
    val localName: String = "",
    val englishName: String = "",
    val craftName: String = "",
    val artisanName: String = "",
    val place: String = "",
    val processUsedIn: String = "",
    val material: String = "",
    val materialFamily: String = "",
    val yearsInUse: String = "",
    val height: String = "",
    val width: String = "",
    val heightInches: String = "",
    val length: String = "",
    val breadth: String = "",
    val thickness: String = "",
    val weight: String = "",
    val radius: String = "",
    val maker: String = "UNKNOWN",
    val traditionType: String = "",
    val replacementCost: String = "",
    val suggestions: String = "",
    val remarks: String = "",
    val craftIds: List<String> = emptyList(),
    val artisanIds: List<String> = emptyList(),
)

internal fun toolCreateBody(
    fields: ToolFormFields,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    measurementMethods: JsonObject? = null,
    sendCraftIds: Boolean = true,
    sendArtisanIds: Boolean = true,
    workshopId: String? = null,
    status: String = "PENDING",
    recordedAt: String? = null,
    location: LocationRequest? = null,
): ToolCreateRequest = ToolCreateRequest(
    toolkitName = fields.toolkitName.trim(),
    localName = fields.localName.formText(),
    englishName = fields.englishName.formText(),
    craftName = recordCraftName(fields.craftIds, fields.craftName, crafts, artisans),
    artisanName = recordArtisanName(fields.artisanIds, fields.artisanName, artisans),
    place = recordPlace(fields.artisanIds, fields.place, artisans),
    processUsedIn = fields.processUsedIn.formText(),
    material = fields.material.formText(),
    materialFamily = fields.materialFamily.formText(),
    yearsInUse = fields.yearsInUse.trim().toIntOrNull(),
    height = fields.height.trim().toDoubleOrNull(),
    width = fields.width.trim().toDoubleOrNull(),
    heightInches = fields.heightInches.trim().toDoubleOrNull(),
    lengthInches = fields.length.trim().toDoubleOrNull(),
    breadthInches = fields.breadth.trim().toDoubleOrNull(),
    measurementMethods = measurementMethods,
    thickness = fields.thickness.trim().toDoubleOrNull(),
    weight = fields.weight.trim().toDoubleOrNull(),
    radius = fields.radius.trim().toDoubleOrNull(),
    maker = fields.maker,
    traditionType = fields.traditionType.ifBlank { StandardValues.TRADITION_UNANSWERED },
    replacementCost = fields.replacementCost.trim().toDoubleOrNull(),
    suggestionsForToolImprovement = fields.suggestions.formText(),
    remarks = fields.remarks.formText(),
    artisanId = fields.artisanIds.firstOrNull().formText(),
    craftId = fields.craftIds.firstOrNull().formText(),
    craftIds = if (sendCraftIds) fields.craftIds else null,
    artisanIds = if (sendArtisanIds) fields.artisanIds else null,
    workshopId = workshopId,
    status = status,
    recordedAt = recordedAt,
    location = location,
)

internal data class ProductFormFields(
    val productName: String,
    val localName: String = "",
    val craftName: String = "",
    val artisanName: String = "",
    val place: String = "",
    val productType: String = "OTHER",
    val timeTaken: String = "",
    val size: String = "",
    val length: String = "",
    val breadth: String = "",
    val height: String = "",
    val costOfMaking: String = "",
    val sellingPrice: String = "",
    val marketDemand: String = "UNKNOWN",
    val rawMaterials: String = "",
    val mainTools: String = "",
    val functionUse: String = "",
    val remarks: String = "",
    val craftId: String = "",
    val artisanId: String = "",
)

internal fun productCreateBody(
    fields: ProductFormFields,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    measurementMethods: JsonObject? = null,
    workshopId: String? = null,
    status: String = "PENDING",
    recordedAt: String? = null,
    location: LocationRequest? = null,
): ProductCreateRequest {
    val craftIds = listOfNotNull(fields.craftId.formText())
    val artisanIds = listOfNotNull(fields.artisanId.formText())
    return ProductCreateRequest(
        productName = fields.productName.trim(),
        localName = fields.localName.formText(),
        craftName = recordCraftName(craftIds, fields.craftName, crafts, artisans),
        artisanName = recordArtisanName(artisanIds, fields.artisanName, artisans),
        place = recordPlace(artisanIds, fields.place, artisans),
        productType = fields.productType,
        timeTakenToCompleteProduct = fields.timeTaken.formText(),
        size = fields.size.formText(),
        lengthInches = fields.length.trim().toDoubleOrNull(),
        breadthInches = fields.breadth.trim().toDoubleOrNull(),
        heightInches = fields.height.trim().toDoubleOrNull(),
        measurementMethods = measurementMethods,
        costOfMaking = fields.costOfMaking.trim().toDoubleOrNull(),
        sellingPrice = fields.sellingPrice.trim().toDoubleOrNull(),
        marketDemand = fields.marketDemand,
        rawMaterialsUsed = fields.rawMaterials.formText(),
        mainToolsUsed = fields.mainTools.formText(),
        productFunctionUse = fields.functionUse.formText(),
        remarks = fields.remarks.formText(),
        artisanId = artisanIds.firstOrNull(),
        craftId = craftIds.firstOrNull(),
        workshopId = workshopId,
        status = status,
        recordedAt = recordedAt,
        location = location,
    )
}

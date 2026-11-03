package com.fieldrepository.app.ui

internal data class NextStepChoice(
    val modeName: String,
    val label: String,
)

internal fun nextStepChoices(savedMode: String, hasProduct: Boolean = true): List<NextStepChoice> = when (savedMode) {
    "ARTISAN" -> listOf(
        NextStepChoice("PRODUCT", "Add a product"),
        NextStepChoice("TOOL", "Add a tool"),
        NextStepChoice("QUESTIONNAIRE", "Take interview"),
    )
    "PRODUCT" -> listOfNotNull(
        if (hasProduct) NextStepChoice("PROCESS", "Document how it's made") else null,
        NextStepChoice("PRODUCT", "Add another product"),
        NextStepChoice("TOOL", "Add a tool"),
    )
    "CRAFT" -> listOf(
        NextStepChoice("ARTISAN", "Add an artisan"),
        NextStepChoice("PRODUCT", "Add a product"),
    )
    "TOOL" -> listOf(
        NextStepChoice("TOOL", "Add another tool"),
        NextStepChoice("PRODUCT", "Add a product"),
    )
    "PROCESS" -> listOf(
        NextStepChoice("PROCESS", "Document another process"),
        NextStepChoice("PRODUCT", "Add a product"),
    )
    else -> emptyList()
}

internal fun nextStepHeading(savedWord: String, name: String?): String =
    name?.trim()?.takeIf { it.isNotEmpty() }?.let { "Saved $it" } ?: "$savedWord saved"

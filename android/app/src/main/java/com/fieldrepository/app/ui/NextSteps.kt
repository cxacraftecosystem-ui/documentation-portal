package com.fieldrepository.app.ui

/**
 * THE NEXT-STEP PANEL'S CHOICES (A8, 2026-10-17): what the dashboard offers after a record is saved.
 *
 * Only an artisan save used to offer anything. After a product the researcher went back to the
 * dashboard, opened the process form and picked the same artisan and product again — six to eight
 * taps to say "now show how this is made". Every create now lands on a short panel whose buttons
 * open the next form with the sitting already filled in.
 *
 * The record types are passed as [modeName]s (the `EntryMode` constant names in MainActivity) so the
 * table is plain data a JVM test can read; the screen maps them back and drops any the user may not
 * create.
 */
internal data class NextStepChoice(
    /** The form to open, by `EntryMode` name. */
    val modeName: String,
    /** The button's words. */
    val label: String,
)

/**
 * The choices after saving a [savedMode] record, best first. [hasProduct] is false for a product that
 * was queued offline: it has no id yet, so no process can be filed against it and the option is
 * withheld rather than opening a form that cannot be completed (the web's CarryForwardCards rule).
 */
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

/** The panel's heading: the record's own name when it has one. */
internal fun nextStepHeading(savedWord: String, name: String?): String =
    name?.trim()?.takeIf { it.isNotEmpty() }?.let { "Saved $it" } ?: "$savedWord saved"

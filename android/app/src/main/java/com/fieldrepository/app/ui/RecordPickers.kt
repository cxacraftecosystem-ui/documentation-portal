package com.fieldrepository.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.FieldRepository

/**
 * THE CRAFT AND ARTISAN PICKERS THE RECORD FORMS SHARE — and the ceiling they used to hide.
 *
 * The web twin of this file is `frontend/components/forms/recordPickers.ts`, and the two were
 * written together on purpose. The product form and the tool form ask the identical question of the
 * API ("which crafts, and which artisans of the chosen craft"), on both surfaces, and had the
 * identical bug in it — four copies of the same defect, character for character.
 *
 * WHAT WAS WRONG. `FieldRepository.artisans()` asks for `pageSize = 100` and keeps `.items`.
 * `pageSize` is clamped to `MAX_PAGE_SIZE = 100` server-side (`backend/app/services/pagination.py`)
 * so 100 is the CEILING and not a tunable, and `GET /artisans` orders `createdAt desc`
 * (`routes/artisans.py:259`). The dropdown therefore held the newest hundred rows of the whole
 * table, and the in-memory craft filter (`artisans.filter { it.craftId == craftId }`) then cut into
 * THAT: a craft whose people were entered before the newest hundred offered nothing at all, beneath
 * the sentence "No artisans are linked to this craft yet." — a claim about the repository that the
 * form had no basis for. `total` was on the wire the whole time and thrown away.
 *
 * HOW BADLY IT BITES depends on the table. The sibling repository running this same schema counted
 * its own Postgres on 2026-08-15 and found **749 artisans over 178 crafts** — 649 artisans
 * unpickable. That is quoted as evidence of the SHAPE, not as a measurement of this deployment's
 * database, which was not reachable from the machine this was written on. The argument does not
 * depend on the number: past 100 rows the newest-hundred window exists, and below 100 this costs
 * nothing.
 *
 * THE DESTRUCTIVE HALF is [craftChangeClearsArtisan]. Read its own header before touching it — it
 * is the part that silently deleted stored links, and it is the reason this file has a unit test.
 *
 * KEEP THE TWO SURFACES TOGETHER. A picker that behaves differently on the phone than in the
 * browser is this product family's most repeated defect class; the web module names this file and
 * this file names it back so neither can be changed alone without the diff looking wrong.
 */

/**
 * Should a craft change clear the artisan link?
 *
 * ONLY when this form actually knows the artisan practises a different craft. Both record forms, on
 * both surfaces, asked `artisans.none { it.id == artisanId && it.craftId == nextCraftId }`, which is
 * true for two unrelated reasons — the craft differs, or the artisan is not in the loaded list at
 * all — and treated both as "wrong craft".
 *
 * Against a 100-row page of a longer table the SECOND reason is the ordinary one on any older
 * record. So opening a product or a tool merely to CORRECT ITS CRAFT blanked the artisan field, and
 * the save then wrote that blank through: `artisanId` is in the backend's `CLEARABLE_KEYS`
 * (`backend/app/services/records.py:249-265`), which exists precisely so that a null in the payload
 * means "unlink" rather than "unchanged". The link was destroyed under a 200 with nothing on screen
 * saying so, by a researcher who had come to fix a typo.
 *
 * When the artisan cannot be found even after the by-id lookup, the link is KEPT. That direction is
 * deliberate: an artisan wrongly left linked is visible on the form and one tap from being
 * corrected; an artisan silently unlinked is neither. Do not "tidy" this back into a `none {}` —
 * that expression is the defect, not a shorter spelling of the rule.
 */
fun craftChangeClearsArtisan(
    nextCraftId: String,
    artisanId: String,
    artisans: List<ArtisanDto>
): Boolean {
    if (nextCraftId.isBlank() || artisanId.isBlank()) return false
    val known = artisans.firstOrNull { it.id == artisanId } ?: return false
    return known.craftId != nextCraftId
}

/**
 * THE ONE SENTENCE UNDER A CAPPED LIST, or null when the screen must say nothing.
 *
 * The Kotlin twin of `cappedListNotice` in `frontend/components/data/cappedList.ts`, and the wording
 * is deliberately the same wording: two surfaces describing one cut in two different sentences is
 * how a researcher learns that neither of them means much.
 *
 * `null` is the common answer and the whole point of the return type — a complete list has nothing
 * to explain, and a standing note about pagination on every visit is padding this screen cannot
 * afford. Both numbers are always printed: "showing the first 100" alone still leaves the reader
 * guessing whether that is most of the corpus or an eighth of it, and the difference is whether they
 * go looking elsewhere or conclude the record was never created.
 *
 * The `loaded == 0` arm cannot be produced by page one of a non-empty list, which is exactly why the
 * decision lives in a pure function a test can reach rather than in a `if` inside a composable: the
 * one state nobody can get a screenshot of is the state where silence does the most damage.
 */
fun listCutNotice(loaded: Int, total: Int, noun: String): String? {
    if (total <= loaded) return null
    if (loaded == 0) {
        return "None of the $total $noun could be listed here — this is not an empty repository."
    }
    return "Showing $loaded of $total $noun — the other ${total - loaded} are not on this list, " +
        "and typing here searches only the $loaded shown."
}

/**
 * Add rows to a picker's options without ever removing one — the other half of living with a
 * ceiling.
 *
 * A picker that can only hold one page has to be allowed to hold SEVERAL: the repository-wide page
 * loaded at startup, the narrower page fetched once a craft was chosen, and the single row looked up
 * by id because the record being edited points at it. Those three overlap, arrive in any order, and
 * none is authoritative over the others.
 *
 * **Additive on purpose.** Replacing the list with the newest answer is what would make an edit form
 * forget the artisan it is editing the moment a craft is picked — and these lists are also handed to
 * `carryScope`, where a missing id reads as "not reachable from this form" and drops the carried
 * prefill. A narrower list must never be allowed to look like a shorter world.
 *
 * First writer wins on a duplicate id, so a row already on screen is not swapped mid-interaction.
 */
fun mergeArtisansById(previous: List<ArtisanDto>, incoming: List<ArtisanDto>): List<ArtisanDto> {
    if (incoming.isEmpty()) return previous
    val seen = previous.mapTo(HashSet()) { it.id }
    val added = incoming.filterNot { seen.contains(it.id) }
    return if (added.isEmpty()) previous else previous + added
}

/**
 * THIS RECORD'S OWN CRAFT IS ALWAYS AN OPTION, wherever it sorts.
 *
 * The craft dropdown needs the by-id rescue as badly as the artisan one, and for a worse reason.
 * `GET /crafts` is clamped to 100 rows and ordered NAME ASCENDING (deliberately — see the ordering
 * comment in `routes/crafts.py:82-87`), so unlike the recency-ordered artisan list the cut is STABLE
 * and always falls in the same place: the same crafts are missing on every device, every day. A
 * product or tool of a craft whose name sorts past the cut opens reading "Unlinked / type below"
 * beside a REQUIRED craft-name box holding the right name. The stored link is intact and would have
 * been saved untouched — but the form says it is not, and the obvious repair for a craft that looks
 * unlinked is to pick one, which is the single action that really does rewrite the link. On the
 * ARTISAN form it is worse still: the box under the dropdown is "Or new craft name", so the
 * reasonable response to a craft that cannot be found is to type it, minting a duplicate craft row.
 *
 * Returns `crafts` unchanged when the id is blank or already on the page — the common case — so a
 * caller can use the result everywhere and never think about it again.
 */
@Composable
fun rememberCraftOptions(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    craftId: String
): List<CraftDto> {
    var offPage by remember { mutableStateOf<CraftDto?>(null) }
    val attempted = remember { mutableSetOf<String>() }
    val onPage = craftId.isBlank() || crafts.any { it.id == craftId }

    LaunchedEffect(craftId, onPage) {
        if (onPage) return@LaunchedEffect
        // Without this the 403/404 case re-fires on every recomposition that changes `crafts`,
        // forever. It is not an optimisation.
        if (!attempted.add(craftId)) return@LaunchedEffect
        runCatching { repository.craft(craftId) }.onSuccess { offPage = it }
    }

    val fetched = offPage
    // A row fetched for a DIFFERENT id must never be offered: the researcher has moved on, and it
    // would appear as an option that is neither on a page nor selected.
    return if (fetched != null && fetched.id == craftId && !crafts.any { it.id == craftId }) {
        crafts + fetched
    } else {
        crafts
    }
}

/**
 * What a record form's artisan dropdown should actually offer, and what it is NOT offering.
 *
 * @param artisans everything the shared startup lookup loaded — never narrowed, because
 *   `CarryContextPrefill` reads it to decide whether a carried artisan is reachable AT ALL, and that
 *   judgement is about the repository rather than about one craft.
 * @param options the same list plus the chosen craft's roster and the record's own artisan.
 * @param craftRosterCut the sentence for the craft-scoped load, or null when it is whole.
 * @param loadedForCraft WHICH craft the roster belongs to — not a boolean. "No artisans are linked
 *   to this craft yet" is a claim about the repository, and printing it off the PREVIOUS craft's
 *   rows while the new craft's request is still in flight makes that claim before the answer
 *   exists. Callers must test `loadedForCraft == craftId` before saying anything about emptiness.
 */
data class ArtisanPickerState(
    val options: List<ArtisanDto>,
    val craftRosterCut: String?,
    val loadedForCraft: String?
)

/**
 * The two follow-up requests that close the ceiling defect, held for one form.
 *
 * 1. **The chosen craft's own roster**, asked for with the `craftId` the endpoint has always
 *    accepted (`routes/artisans.py:234-235`). This is the request that actually closes it: it turns
 *    a hundred-row window on the whole artisan table into, in practice, the complete answer for the
 *    craft in hand.
 * 2. **The record's own artisan, by id**, when neither page holds them — so that "this artisan is
 *    not in the list" and "this artisan does not practise that craft" stop being the same
 *    observation. They were the same observation, and [craftChangeClearsArtisan]'s predecessor read
 *    the first as the second and deleted the link.
 *
 * Failures are deliberately silent. The startup lookup's artisans are still a legitimate, narrower
 * offer; `loadedForCraft` stays put so the caller does not print "no artisans are linked to this
 * craft" off a failed request; and a by-id 403/404 means the link is intact but not editable from
 * here, which is an honest state and not an error banner. `attempted` stops a failed id being
 * re-requested on every recomposition.
 */
@Composable
fun rememberArtisanPicker(
    repository: FieldRepository,
    artisans: List<ArtisanDto>,
    craftId: String,
    artisanId: String
): ArtisanPickerState {
    var roster by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var rosterCut by remember { mutableStateOf<String?>(null) }
    var loadedForCraft by remember { mutableStateOf<String?>(null) }
    var offPage by remember { mutableStateOf<ArtisanDto?>(null) }
    val attempted = remember { mutableSetOf<String>() }

    LaunchedEffect(craftId) {
        if (craftId.isBlank()) return@LaunchedEffect
        runCatching { repository.artisansForCraftPage(craftId) }
            .onSuccess { page ->
                roster = page.items
                rosterCut = listCutNotice(page.items.size, page.total, "artisans of this craft")
                loadedForCraft = craftId
            }
    }

    val known = mergeArtisansById(mergeArtisansById(artisans, roster), listOfNotNull(offPage))

    LaunchedEffect(artisanId, known.size) {
        if (artisanId.isBlank()) return@LaunchedEffect
        if (known.any { it.id == artisanId }) return@LaunchedEffect
        if (!attempted.add(artisanId)) return@LaunchedEffect
        runCatching { repository.artisan(artisanId) }
            .onSuccess { detail ->
                offPage = ArtisanDto(
                    id = detail.id,
                    name = detail.name,
                    place = detail.place,
                    status = detail.status,
                    craftId = detail.craftId,
                    craft = detail.craft,
                    location = detail.location,
                    createdById = detail.createdById,
                    createdAt = detail.createdAt
                )
            }
    }

    // A row fetched for a DIFFERENT id must never stay in the options: the researcher has moved on
    // and it would show as an entry that is neither on a page nor selected.
    val options = if (offPage != null && offPage?.id != artisanId) {
        mergeArtisansById(artisans, roster)
    } else {
        known
    }
    return ArtisanPickerState(options = options, craftRosterCut = rosterCut, loadedForCraft = loadedForCraft)
}

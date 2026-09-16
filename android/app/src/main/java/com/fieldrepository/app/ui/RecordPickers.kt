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
import kotlinx.coroutines.CancellationException

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
 * THE SAME RULE FOR A MULTI-SELECT: which artisans a craft DESELECTION must drop.
 *
 * The tool form's craft box became a many-of-many on 2026-09-15, and [craftChangeClearsArtisan]
 * above cannot answer for it — its question is "does this ONE artisan practise this ONE craft", and
 * the multi-select's question is "which of these artisans did the craft that just went away account
 * for". Asking the singular per artisan against the FIRST ticked craft would drop everybody who
 * practises the second one.
 *
 * ADDED BESIDE THE SINGULAR RATHER THAN REPLACING IT. `ProductForm` is still a single-select on both
 * clients and still calls the singular, `RecordPickersTest` still drives it, and a rule with two
 * live callers is not improved by having one of them route through a list of one.
 *
 * THE FOUR REASONS AN ARTISAN IS KEPT, in the order they are asked — the same four, in the same
 * words, as `frontend/components/forms/recordPickers.ts`:
 *
 *  • NOT IN THE LOADED LIST AT ALL. "Not on the page" and "not of that craft" are different
 *    observations, and reading the first as the second is the silent link deletion the singular's
 *    header is written about. Against a 100-row page of a longer table this is the ORDINARY case on
 *    an older record.
 *  • NO CRAFT RECORDED ON THEIR ROW. A null `craftId` is a fact about that artisan, not about which
 *    crafts are ticked here. THE SINGULAR ANSWERS THE OTHER WAY on this one case and the difference
 *    is deliberate: a single-select that keeps them leaves the form asserting one link that
 *    contradicts the other with no room to show both, and a multi-select has that room.
 *  • THEIR CRAFT IS NOT ONE THIS GESTURE REMOVED — see the paragraph below.
 *  • THEIR CRAFT IS STILL TICKED SOMEWHERE in the new selection.
 *
 * ── `removedCraftIds` IS NOT A CONVENIENCE, IT IS THE WHOLE RULE ─────────────────────────────────
 *
 * This function used to ask only `craftId !in nextCraftIds` — *is this artisan of a craft that is
 * not ticked* — which is a different question and answers wrongly for everybody whose craft was
 * never ticked in the first place. A tool's artisans do not all arrive through this picker: "Assign
 * tools to artisans" links anybody, of any craft, and the form draws every one of them ticked. So a
 * tool linked to crafts [Bandhani, Block printing] and, through that panel, to a POTTER lost the
 * potter the moment a researcher unticked Block printing — his craft was not in the next list, he
 * was returned as dropped, the PATCH carried the shortened list, and `_replace_artisan_links`
 * deleted his row under a 200 with nothing on screen saying an assignment had been removed.
 *
 * `removedCraftIds` is everything the PREVIOUS selection held that the next one does not, which only
 * the caller can compute — this function never sees the selection it is being asked about the change
 * FROM. Both clauses are kept: the craft must be one that just went away AND must not still be
 * ticked under another entry.
 *
 * ── THE EMPTY-TICK-LIST ARM IS GONE, AND IT IS RETIRED WITH QUOTATION RATHER THAN DELETED ────────
 *
 * This function used to open `if (nextCraftIds.none { it.isNotBlank() }) emptyList() else …`, under a
 * paragraph headed "UNTICKING THE LAST CRAFT DROPS NOBODY" that called itself *"a DELIBERATE
 * DIVERGENCE from the cross-surface specification's own sketch of this function, which has no such
 * arm"* and argued: *"Without the arm, unticking the last craft drops every artisan this form knows
 * the craft of, the save sends `artisanIds: []`, and every `ToolArtisan` row for the tool is deleted
 * — under a 200, from an edit that was about a CRAFT. It is also order-dependent, which is worse:
 * untick A then tick B loses B's artisans, tick B then untick A keeps them."* It closed: *"If the
 * browser's twin lands without this arm the two clients will genuinely disagree; say so rather than
 * quietly deleting it here."*
 *
 * The browser's twin landed without it, so the two clients DID disagree — on every gesture that
 * empties the craft list and not only on "Clear all": the browser ended with no artisans and the
 * handset kept exactly one. Both sides now run ONE rule with no special case, and `removedCraftIds`
 * is what makes that safe rather than merely consistent: unticking the last craft drops THAT craft's
 * people and nobody else's, which is what unticking a craft means whether or not it was the last.
 * The arm was protecting against a harm the general rule no longer causes.
 *
 * THE ORDER-DEPENDENCE THE ARM WAS ALSO ARGUING AGAINST IS GONE, AND GONE PROPERLY. Both gestures
 * above remove the same craft, so both produce the same record. The tool form also applies a whole
 * sheet selection in ONE call now (`onCraftsChanged` in `MainActivity.kt`) instead of fanning it out
 * into one call per toggled id, so there are no intermediate craft lists for the answer to depend on.
 *
 * Returns the ids to DROP — not the ids to keep — so a caller that forgets to apply the answer
 * changes nothing, which is the safe direction to fail in.
 */
fun craftsChangeClearsArtisans(
    nextCraftIds: List<String>,
    removedCraftIds: List<String>,
    artisanIds: List<String>,
    artisans: List<ArtisanDto>
): List<String> = artisanIds.filter { id ->
    if (id.isBlank()) return@filter false
    val known = artisans.firstOrNull { it.id == id } ?: return@filter false
    val craftId = known.craftId
    if (craftId.isNullOrBlank()) return@filter false
    craftId in removedCraftIds && craftId !in nextCraftIds
}

/**
 * THE CRAFT NAME TO SORT AND LABEL AN ARTISAN BY, or "" when this form does not know one.
 *
 * Two sources and a stated order: the artisan's own hydrated `craft` if the server sent one, then
 * the ticked craft whose id their `craftId` column names. Only the TICKED crafts are consulted and
 * not the whole register — an artisan of an unticked craft cannot be in this list at all, so a name
 * found there would be a heading for a group with nothing in it.
 */
fun craftNameFor(artisan: ArtisanDto, selectedCrafts: List<CraftDto>): String {
    val hydrated = artisan.craft?.name.orEmpty()
    if (hydrated.isNotBlank()) return hydrated
    val id = artisan.craftId
    if (id.isNullOrBlank()) return ""
    return selectedCrafts.firstOrNull { it.id == id }?.name.orEmpty()
}

/**
 * Fold a name to its sort key: trimmed, lowercased at `Locale.ROOT` by the NO-ARGUMENT overload.
 *
 * `lowercase()` with no argument is ROOT and matches ECMAScript's locale-independent
 * `String.prototype.toLowerCase`, which is what the browser's twin of this ordering uses. NOT
 * `lowercase(Locale.getDefault())`: a handset set to Turkish would fold a capital I to a dotless ı
 * and order a craft list differently from the same data in the browser beside it.
 */
private fun sortFold(value: String): String = value.trim().lowercase()

/**
 * THE CANONICAL ARTISAN ORDERING FOR A MULTI-CRAFT PICKER — by craft name A→Z, then by artisan name
 * A→Z within each craft.
 *
 * IDENTICAL IN TYPESCRIPT AND KOTLIN, TO THE COMPARISON, and that is the whole reason it is a named
 * function in a file a unit test can reach rather than a `sortedBy` inside a composable. The rules,
 * none of them negotiable:
 *
 *  • Case folding is [sortFold] — `trim()` then the ROOT `lowercase()`.
 *  • String comparison is Kotlin's own `compareTo`, which is UTF-16 CODE-UNIT order and is what
 *    JavaScript's `<`/`>` on strings is. FORBIDDEN, here and in the twin: `localeCompare`,
 *    `Intl.Collator`, `java.text.Collator`, `String.CASE_INSENSITIVE_ORDER` and
 *    `compareTo(other, ignoreCase = true)`. Every one of them is ICU-version-dependent,
 *    locale-dependent, or char-by-char-with-both-cases, and each would make the two clients order a
 *    Devanagari or Gujarati craft name differently on the same data.
 *  • An artisan whose craft this form cannot name sorts LAST, never first — a blank key would
 *    otherwise sort before every real name and put the unknowns at the top of the sheet.
 *  • The key ends in the artisan's id, a unique cuid, so the order is TOTAL. Nothing here depends
 *    on `sortedWith` being stable.
 *
 * The result is also what makes the handset's grouping work without group headings: `SelectOption`
 * has no `group` on either client (see `ui/SearchableSelect.kt`), so the craft goes in the row's
 * `hint` and the visual grouping falls out of this order.
 */
fun artisansByCraftThenName(
    artisans: List<ArtisanDto>,
    selectedCrafts: List<CraftDto>
): List<ArtisanDto> {
    val craftKeys = artisans.associate { it.id to sortFold(craftNameFor(it, selectedCrafts)) }
    return artisans.sortedWith(
        compareBy(
            { a: ArtisanDto -> if (craftKeys[a.id].isNullOrEmpty()) 1 else 0 },
            { a: ArtisanDto -> craftKeys[a.id].orEmpty() },
            { a: ArtisanDto -> sortFold(a.name) },
            { a: ArtisanDto -> a.name },
            { a: ArtisanDto -> a.id },
        )
    )
}

/**
 * THE ONE SENTENCE UNDER A CAPPED LIST, or null when the screen must say nothing.
 *
 * The Kotlin twin of `cappedListNotice` in `frontend/components/data/cappedList.ts`, and the wording
 * is deliberately the same wording — all four sentences of it, one per [ListCutReach] arm plus the
 * "nothing loaded" arm above them: two surfaces describing one cut in two different sentences is how
 * a researcher learns that neither of them means much.
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
/**
 * HOW THE ROWS PAST THE CUT CAN BE GOT AT — which changes the sentence, because telling somebody to
 * do something impossible is worse than admitting the limit.
 *
 * The Kotlin twin of `CutReach` in `frontend/components/data/cappedList.ts`, arm for arm.
 *
 * [PAGER] HAS NO CALLER ON THIS CLIENT TODAY and is here anyway. That is the same decision the web
 * module makes about its `loaded == 0` arm and it is made for the same reason: these two files are
 * asserted against each other as a pair, and an enum that is missing an arm the web has is an enum
 * that quietly stops being comparable — the next person to add a paged picker here would find the
 * vocabulary already short and would coin a sixth sentence rather than notice. The cost is one
 * `when` branch; the alternative is a divergence nothing detects.
 */
enum class ListCutReach {
    /** One page, no way past it from this control. Every record picker in the forms is this. */
    NONE,

    /** A pager is on screen and moving it re-requests from the server. */
    PAGER,

    /**
     * The box above this sentence sends its term to the SERVER, so typing does reach the rows past
     * the cut. Only for a control that actually does it — `ui/RecordSwitcher.kt` is the one.
     */
    SEARCH
}

fun listCutNotice(
    loaded: Int,
    total: Int,
    noun: String,
    reach: ListCutReach = ListCutReach.NONE
): String? {
    if (total <= loaded) return null
    if (loaded == 0) {
        return "None of the $total $noun could be listed here — this is not an empty repository."
    }
    return when (reach) {
        ListCutReach.PAGER ->
            "Showing $loaded of $total $noun — use the pager to reach the rest, which are not " +
                "searched by the box above."
        // The one arm that does NOT end by admitting a limit, because there is not one to admit: the
        // term goes to the server, so every row counted in `total` is reachable by typing. The
        // arithmetic is still worth the line — a reader looking at 100 rows and no sentence cannot
        // tell whether that is the workshop or the ceiling, and the two call for different actions.
        ListCutReach.SEARCH -> "Showing $loaded of $total $noun — type to search all $total."
        ListCutReach.NONE ->
            "Showing $loaded of $total $noun — the other ${total - loaded} are not on this list, " +
                "and typing here searches only the $loaded shown."
    }
}

/**
 * THE PLURAL NOUN THE CAPPED-LIST SENTENCE IS BUILT AROUND \u2014 "Showing 100 of 240 \u2026".
 *
 * It names the SCOPE and not just the record type, because the two sentences answer different
 * questions: under a workshop the reader needs to know that this WORKSHOP has more people than are
 * listed, not that the repository does.
 *
 * Web twin: `artisanScopeNoun` in `frontend/components/questionnaires/interviewArtisans.ts`, same two
 * strings. It is here rather than beside the picker because the two clients printing two different
 * sentences about one cut is the kind of difference nobody files a bug about and everybody notices.
 */
fun artisanScopeNoun(workshopId: String): String = if (workshopId.isNotBlank()) "artisans at this workshop" else "artisans"

/**
 * WHICH TICKED ARTISANS THE WORKSHOP'S ROSTER DOES NOT ACCOUNT FOR \u2014 a SENTENCE, not a deletion.
 *
 * ── WHAT THIS ANSWERS, AND WHAT IT DELIBERATELY DOES NOT DO ─────────────────────────────────────
 *
 * The browser used to run this same ruling and then WRITE THE RESULT BACK into the form's selection:
 * a workshop change silently unticked anybody the new workshop's complete roster did not hold. This
 * client never did, and the two therefore saved different `QuestionnaireInterviewArtisan` rows for
 * one identical pair of taps. Keeping the tick won, on the argument written out in full as rule 6 in
 * `frontend/components/questionnaires/interviewArtisans.ts`; the short version is that
 * `artisan_workshop_clause` counts three links and the third is HAVING SAT IN AN INTERVIEW TAKEN AT
 * THE WORKSHOP \u2014 the link the questionnaire form itself creates \u2014 so a roster's silence about
 * somebody is not evidence that ticking them was a mistake, it is evidence they have not been
 * interviewed here before.
 *
 * What was missing on both clients was the sentence. A selection a form quietly disagrees with is a
 * form that has an opinion nobody can read.
 *
 * ── THE THREE-WAY RULING, WHICH IS UNCHANGED ────────────────────────────────────────────────────
 *
 * "Absent from the list" has three causes and only one of them is "this workshop does not know
 * them", so this stays silent unless it is certain:
 *
 *   \u2022 [loadedForWorkshop] != [workshopId] \u2014 the roster for the workshop now on screen has not landed
 *     (or the request failed, which leaves it null on purpose). Nothing is known, so nothing is said.
 *     Without this arm the line would appear on every mount and every workshop change for the length
 *     of a round trip, naming everybody, and a warning that is usually wrong is one nobody reads.
 *   \u2022 [cut] is non-null \u2014 the roster stopped at the page budget with rows behind it. An artisan
 *     absent from a truncated list may be perfectly well linked here and simply past the cut, and
 *     [listCutNotice] is already on screen saying the list is short. This one would be contradicting
 *     it.
 *   \u2022 Otherwise the roster is the complete answer for this workshop, so absence means absence.
 *
 * Returns ids IN THE ORDER GIVEN so the sentence lists people in the researcher's own tick order and
 * reads identically on both clients. Web twin: `artisansNotAtWorkshop`.
 */
fun artisansNotAtWorkshop(
    selectedIds: Collection<String>,
    offeredIds: Collection<String>,
    loadedForWorkshop: String?,
    workshopId: String,
    cut: String?
): List<String> {
    if (loadedForWorkshop != workshopId) return emptyList()
    if (cut != null) return emptyList()
    val offered = offeredIds.toHashSet()
    return selectedIds.filter { it.isNotBlank() && !offered.contains(it) }
}

/** How many names the sentence prints before it starts counting. A selection of thirty must not print a paragraph. */
const val OUT_OF_WORKSHOP_NAMES_SHOWN = 4

/**
 * THE SENTENCE UNDER THE PICKER naming the ticked artisans this workshop's roster does not hold, or
 * null when there is nothing to say.
 *
 * WHY IT DOES NOT READ AS A WARNING. Nothing is wrong yet, and in the commonest case nothing is wrong
 * at all: an interview taken at this workshop with somebody whose own record is filed at another one
 * is an ordinary event, and filing it is precisely what creates the link that would have put them on
 * this roster. So the sentence states the fact, states what saving will do, and stops. Wording it as
 * "these artisans do not belong here" would push a researcher into unticking a perfectly good
 * selection to make a message go away.
 *
 * NAMES AND NOT A COUNT. "1 artisan is not recorded at this workshop" makes the reader open the
 * control and compare it against a roster to find out who; the names are already in hand, and the
 * whole point of the sentence is that it can be acted on without looking anything up.
 *
 * WORD FOR WORD THE WEB'S `outOfWorkshopNotice`, and that is the requirement rather than a nicety \u2014
 * the same reason [listCutNotice] has a twin. Two screens describing the same situation in two
 * different sentences is how a researcher learns that neither of them means much.
 */
fun outOfWorkshopNotice(names: List<String>): String? {
    val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
    if (clean.isEmpty()) return null
    val shown = clean.take(OUT_OF_WORKSHOP_NAMES_SHOWN)
    val remainder = clean.size - shown.size
    val list = shown.joinToString(", ") + if (remainder > 0) " and $remainder more" else ""
    val subject = if (clean.size == 1) "$list is" else "$list are"
    return "$subject not recorded at this workshop yet. They stay ticked \u2014 saving this interview here " +
        "is what links them to it. Untick anyone who should not be on it."
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
 * Returns `crafts` unchanged when nothing is selected or every selection is already on the page —
 * the common case — so a caller can use the result everywhere and never think about it again.
 *
 * PLURAL SINCE 2026-09-15, because the tool form's craft box is a multi-select and EVERY id it holds
 * may be off the page, not just the first. Rescuing only `craftIds[0]` would draw a blank chip for a
 * craft the record genuinely holds, which is the same wrong reading the singular rescue exists to
 * stop — one selection along. The single-craft overload below is unchanged for its callers.
 *
 * @param hydrated the craft rows the record being edited ALREADY CARRIES — `craftLinks[].craft`, which
 *   `GET /tools/{id}` embeds in the very response this form was built from. Passing them makes the
 *   common case cost NOTHING: the picker opens with every linked craft drawn and ticked, and the
 *   serial by-id loop below has nothing left to ask for. Without them a tool linked to three off-page
 *   crafts fired three `GET /crafts/{id}` requests for rows that had already been parsed and thrown
 *   away, and drew "0 selected" over a record that genuinely holds three of them until they landed.
 *   Additive and optional: an older server that sends a null nested object simply falls through to the
 *   by-id loop, which is why that loop stays.
 */
@Composable
fun rememberCraftOptions(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    craftIds: List<String>,
    hydrated: List<CraftDto> = emptyList()
): List<CraftDto> {
    var offPage by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    val attempted = remember { mutableSetOf<String>() }
    val selected = craftIds.filter { it.isNotBlank() }.distinct()
    // A row supplied for an id that is no longer selected must never be offered — see `rescued`.
    val embedded = hydrated
        .filter { craft -> craft.id in selected && crafts.none { it.id == craft.id } }
        .distinctBy { it.id }
    val missing = selected.filterNot { id ->
        crafts.any { it.id == id } || embedded.any { it.id == id }
    }

    LaunchedEffect(missing.joinToString(",")) {
        for (id in missing) {
            if (id in attempted) continue
            // MARKED ATTEMPTED ONLY ONCE THE REQUEST HAS ACTUALLY ANSWERED, and a cancellation is
            // rethrown rather than swallowed. Marking it before the call meant that a loop cancelled
            // mid-flight — which this one is every time `missing` changes, because that is the effect
            // key — burned the id it was in the middle of fetching: the restarted loop skipped it, and
            // the craft was then absent from the options for the life of the form. A 404 still marks,
            // which is what the guard is actually for: without it an id that names nothing would
            // re-fire on every recomposition that changes `crafts`, forever.
            val fetched = try {
                repository.craft(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            attempted.add(id)
            if (fetched != null) offPage = offPage + fetched
        }
    }

    // A row fetched for an id that is no longer selected must never be offered: the researcher has
    // unticked it, and it would appear as an option that is neither on a page nor chosen.
    val rescued = offPage.filter { it.id in missing }
    return if (embedded.isEmpty() && rescued.isEmpty()) crafts else crafts + embedded + rescued
}

/**
 * The one-craft form of [rememberCraftOptions], for the pickers that are still single-select.
 *
 * `ProductForm`, `ArtisanForm` and the review editor each hold one craft id and are unchanged by the
 * tool form's move to a multi-select. A blank id means "nothing selected" and rescues nothing.
 */
@Composable
fun rememberCraftOptions(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    craftId: String
): List<CraftDto> = rememberCraftOptions(repository, crafts, listOfNotNull(craftId.ifBlank { null }))

/**
 * What a record form's artisan dropdown should actually offer, and what it is NOT offering.
 *
 * @param artisans everything the shared startup lookup loaded — never narrowed, because
 *   `CarryContextPrefill` reads it to decide whether a carried artisan is reachable AT ALL, and that
 *   judgement is about the repository rather than about one craft.
 * @param options the same list plus the chosen craft's roster and the record's own artisan.
 * @param craftRosterCut the sentence for the craft-scoped load, or null when it is whole.
 * @param loadedForCraft WHICH crafts the roster belongs to — not a boolean. "No artisans are linked
 *   to this craft yet" is a claim about the repository, and printing it off the PREVIOUS craft's
 *   rows while the new craft's request is still in flight makes that claim before the answer
 *   exists. Callers must test it before saying anything about emptiness.
 *
 *   IT HOLDS A SCOPE KEY AND NOT AN ID, since the tool form's box became a multi-select. The key is
 *   [craftScopeKey] — the ticked ids, blanks dropped, de-duplicated, SORTED and comma-joined — and
 *   for one craft it is that craft's id exactly, which is what the single-select callers have always
 *   compared against and still may. Use [isLoadedFor] rather than comparing by hand.
 */
data class ArtisanPickerState(
    val options: List<ArtisanDto>,
    val craftRosterCut: String?,
    val loadedForCraft: String?
) {
    /** Has the roster for exactly these crafts arrived? See [loadedForCraft]. */
    fun isLoadedFor(craftIds: List<String>): Boolean =
        loadedForCraft != null && loadedForCraft == craftScopeKey(craftIds)
}

/**
 * The ticked crafts as ONE stable key: blanks dropped, de-duplicated, sorted, comma-joined.
 *
 * SORTED, so that ticking Bandhani then Block Printing and ticking them the other way round are the
 * same scope and do not cost a second request. The WIRE order is the researcher's tick order and is
 * not this — see the tool form's payload, where `craftIds` is ordered and the server persists that
 * order — but which artisans a scope contains does not depend on it.
 */
private fun craftScopeKey(craftIds: List<String>): String =
    craftIds.filter { it.isNotBlank() }.distinct().sorted().joinToString(",")

/** "artisans of this craft" / "artisans of these crafts" — the noun [listCutNotice] prints. */
private fun craftRosterNoun(crafts: Int): String =
    if (crafts <= 1) "artisans of this craft" else "artisans of these crafts"

/**
 * The two follow-up requests that close the ceiling defect, held for one form.
 *
 * 1. **The chosen crafts' own roster**, asked for with the `craftIds` the endpoint gained on
 *    2026-09-15 beside the singular `craftId` it has always accepted. This is the request that
 *    actually closes it: it turns a hundred-row window on the whole artisan table into, in practice,
 *    the complete answer for the crafts in hand. ONE request for the whole tick list and never one
 *    per craft — "Select all 178" would otherwise fire 178 of them.
 * 2. **The record's own artisans, by id**, when neither page holds them — so that "this artisan is
 *    not in the list" and "this artisan does not practise that craft" stop being the same
 *    observation. They were the same observation, and [craftChangeClearsArtisan]'s predecessor read
 *    the first as the second and deleted the link.
 *
 *    PLURAL SINCE THE TOOL FORM'S ARTISAN BOX BECAME A MULTI-SELECT, and this half is now
 *    load-bearing in a way it was not: the picker sheet holds a DRAFT of the whole selection and its
 *    "Clear all" empties it outright, so an id that is selected but absent from `options` is an id a
 *    researcher cannot see, cannot untick deliberately, and can lose in one tap. Rescuing every
 *    selected id is what keeps `options` a superset of the selection at all times.
 *
 * @param hydrated the artisan rows the record being edited ALREADY CARRIES — `artisanLinks[].artisan`,
 *   embedded in the same `GET /tools/{id}` response the form was built from. They are merged in BEFORE
 *   `missing` is computed, so for an ordinary edit the serial by-id loop has nothing left to ask: the
 *   artisans that most need rescuing are exactly the ones assigned from "Assign tools to artisans",
 *   who may practise a craft that is not ticked here and whom the roster request therefore cannot
 *   return. Additive and optional — an older server sending a null nested object falls through to the
 *   loop, which is why the loop stays.
 *
 * FAILURES ARE DELIBERATELY SILENT, and the paragraph that stood here was wrong about one of them.
 * It read: *"a by-id 403/404 means the link is intact but not editable from here, which is an honest
 * state and not an error banner."* THE 403 HALF IS NOT REACHABLE: `GET /artisans/{id}` and
 * `GET /crafts/{id}` go through `require_record` (`backend/app/services/records.py`), which does a
 * bare lookup and answers 404 or the row — no visibility filter, no scope, no 403 to get. What is
 * reachable is a 404 (the row is gone) and a transport failure on a field connection, and those two
 * are NOT the same state: the first is permanent and the second is a blip. Both are still swallowed,
 * because a picker that cannot reach one row is not an error banner — but a transport failure now
 * costs the id only until the effect next restarts on a cancellation, rather than always. The
 * roster request is silent for its own reason: the startup lookup's artisans remain a legitimate,
 * narrower offer and `loadedForCraft` stays put, so the caller does not print "no artisans are linked
 * to this craft" off a request that never answered.
 */
@Composable
fun rememberArtisanPicker(
    repository: FieldRepository,
    artisans: List<ArtisanDto>,
    craftIds: List<String>,
    artisanIds: List<String>,
    hydrated: List<ArtisanDto> = emptyList()
): ArtisanPickerState {
    var roster by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var rosterCut by remember { mutableStateOf<String?>(null) }
    var loadedForCraft by remember { mutableStateOf<String?>(null) }
    var offPage by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    val attempted = remember { mutableSetOf<String>() }

    val scopeKey = craftScopeKey(craftIds)
    LaunchedEffect(scopeKey) {
        if (scopeKey.isEmpty()) return@LaunchedEffect
        val wanted = craftIds.filter { it.isNotBlank() }.distinct()
        runCatching { repository.artisansForCraftsPage(wanted) }
            .onSuccess { page ->
                roster = page.items
                rosterCut = listCutNotice(page.items.size, page.total, craftRosterNoun(wanted.size))
                loadedForCraft = scopeKey
            }
    }

    // A row supplied for an id that is no longer selected must never stay in the options: the
    // researcher has unticked it and it would show as an entry that is neither on a page nor chosen.
    // The rule is the same for a row the record embedded as for one fetched by id.
    val wantedArtisans = artisanIds.filter { it.isNotBlank() }.distinct()
    val embedded = hydrated.filter { it.id in wantedArtisans }
    val rescued = offPage.filter { it.id in wantedArtisans }
    val known = mergeArtisansById(
        mergeArtisansById(mergeArtisansById(artisans, roster), embedded),
        rescued
    )

    val missing = wantedArtisans.filterNot { id -> known.any { it.id == id } }
    LaunchedEffect(missing.joinToString(",")) {
        for (id in missing) {
            if (id in attempted) continue
            // MARKED ATTEMPTED ONLY ONCE THE REQUEST HAS ANSWERED — see the identical guard in
            // `rememberCraftOptions`. `missing` is this effect's own key, so the loop is cancelled
            // every time a roster page lands or the selection moves; marking before the call meant a
            // cancelled request burned its id and the artisan then never appeared in the options at
            // all. A cancellation is rethrown so the restarted loop asks again; a 404 still marks, so
            // an id that names nothing cannot re-fire forever.
            val detail = try {
                repository.artisan(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            attempted.add(id)
            if (detail != null) {
                offPage = offPage + ArtisanDto(
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
    }

    return ArtisanPickerState(options = known, craftRosterCut = rosterCut, loadedForCraft = loadedForCraft)
}

/**
 * The one-craft, one-artisan form of [rememberArtisanPicker], for the pickers that are still
 * single-select — `ProductForm` and the tool-assignment screen's own artisan box.
 *
 * A blank id means "nothing selected" on either argument, which is what the review editor's
 * `rememberArtisanPicker(repository, artisans, "", artisanId)` has always relied on.
 */
@Composable
fun rememberArtisanPicker(
    repository: FieldRepository,
    artisans: List<ArtisanDto>,
    craftId: String,
    artisanId: String
): ArtisanPickerState = rememberArtisanPicker(
    repository = repository,
    artisans = artisans,
    craftIds = listOfNotNull(craftId.ifBlank { null }),
    artisanIds = listOfNotNull(artisanId.ifBlank { null })
)

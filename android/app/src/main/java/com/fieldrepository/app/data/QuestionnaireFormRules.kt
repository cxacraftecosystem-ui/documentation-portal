package com.fieldrepository.app.data

import com.fieldrepository.app.ui.FieldPermissions

/**
 * The pure rules of the forms library and the unified questionnaire on Field Repository Android
 * (UNIFY 2.3 and the owner's decisions of 21 and 22 Oct 2026), kept here so JVM tests pin them and
 * every screen asks the same question. Each mirrors `backend/app/questionnaire/authoring.py`.
 */
object FormRights {
    /** D3: Professor and above (or the questionnaire grant) create their own forms. */
    fun mayCreate(user: UserDto?): Boolean =
        user != null && (FieldPermissions.rank(user.role) >= FieldPermissions.RANK_PROFESSOR || user.canManageQuestionnaire)

    /** A form and everything in it: its author or a content admin. No recorded author: content admins only. */
    fun mayChange(user: UserDto?, form: QForm?): Boolean {
        user ?: return false
        if (FieldPermissions.isContentAdmin(user)) return true
        val author = form?.authorId ?: return false
        return author == user.id
    }

    /** Deleting a form is the same rule as editing it (owner, 22 Oct 2026). */
    fun mayDelete(user: UserDto?, form: QForm?): Boolean = mayChange(user, form)

    /** Restoring a deleted form, and the Deleted tab: content admins. */
    fun mayRestore(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

    /**
     * Owner decision 5: share with everyone, workshop bindings and a workshop type's default form are
     * admin acts, and Field Repository serves them on Android (designer-portal Android does not).
     */
    fun mayAdminAct(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

    /** The global default form: `PUT /questionnaires/{id}/default` is `require_admin` on this server. */
    fun maySetDefault(user: UserDto?): Boolean = user != null && FieldPermissions.isAdmin(user)

    /** Sessions and recordings: delete and restore are content admins only (owner decision 3). */
    fun mayDeleteSession(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

    /** Section overrides (Marked complete, Needs review, Needs redo): `require_content_admin`. */
    fun mayOverride(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

    /** The corpus is a whole-instrument dump behind the dataset gate (audit H3). */
    fun mayExportCorpus(user: UserDto?): Boolean = user != null && FieldPermissions.canDownloadDataset(user)

    /** Read view and review: the reviewers. */
    fun mayReview(user: UserDto?): Boolean = user != null && FieldPermissions.canReview(user)
}

/** The library's tabs: Mine · Shared · Workshop, and Deleted for content admins (UNIFY 4.2). */
enum class FormTab(val wire: String, val label: String) {
    MINE("mine", "Mine"), SHARED("shared", "Shared"), WORKSHOP("workshop", "Workshop"), DELETED("deleted", "Deleted");

    companion object {
        fun visible(user: UserDto?): List<FormTab> = listOfNotNull(MINE, SHARED, WORKSHOP, DELETED.takeIf { FormRights.mayRestore(user) })
    }
}

object FormLibrary {
    /**
     * The forms one tab lists, from the whole list the server gave. The server filters by scope when it
     * knows `scope`; this filter makes an older server's answer read the same.
     *  * Mine: forms this person wrote.
     *  * Shared: forms shared with everyone (and Field Repository's own instruments, which have no author).
     *  * Workshop: forms bound to at least one workshop.
     *  * Deleted: content admins only.
     */
    fun tab(forms: List<QForm>, tab: FormTab, meId: String?, kind: String? = null): List<QForm> = forms
        .filter { form ->
            when (tab) {
                FormTab.DELETED -> form.deleted
                FormTab.MINE -> !form.deleted && meId != null && form.authorId == meId
                FormTab.SHARED -> !form.deleted && (form.isShared || form.authorId == null)
                FormTab.WORKSHOP -> !form.deleted && form.workshopCount > 0
            }
        }
        .filter { kind == null || it.kind == kind }
        .sortedWith(compareBy<QForm> { !it.isDefault }.thenBy { it.sortOrder }.thenBy { it.title.lowercase() })

    /** The badges a row carries, in this order. */
    fun badges(form: QForm): List<String> = listOfNotNull(
        FormsCopy.DEFAULT_BADGE.takeIf { form.isDefault },
        FormsCopy.SHARED_BADGE.takeIf { form.isShared },
        form.kind?.let(FormKinds::label)
    )

    /** "24 sections · 285 questions" */
    fun counts(form: QForm): String =
        "${form.sectionCount} ${if (form.sectionCount == 1) "section" else "sections"} · ${form.questionCount} ${if (form.questionCount == 1) "question" else "questions"}"
}

/** `shared/questionnaire-kinds.json`: the vocabulary of form kinds (A8). */
object FormKinds {
    const val WORKSHOP_INTERVIEW = "WORKSHOP_INTERVIEW"
    const val MARKET_SURVEY = "MARKET_SURVEY"
    const val NOT_STATED = "Kind not stated"
    val LABELS = linkedMapOf(WORKSHOP_INTERVIEW to "Workshop interview", MARKET_SURVEY to "Market survey")

    fun label(kind: String?): String = kind?.let { LABELS[it] } ?: NOT_STATED

    /** Forms grouped by kind, as the picker shows several bindings with no primary (rule 5). */
    fun grouped(forms: List<QForm>): List<Pair<String, List<QForm>>> =
        forms.groupBy { it.kind }.toList()
            .sortedBy { (k, _) -> LABELS.keys.indexOf(k).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { (k, list) -> label(k) to list }
}

/** Bindings (A9): several forms per workshop, at most one primary for each workshop. */
object FormBindings {
    /** Adds or updates [workshopId]; making it primary leaves it the only primary of THIS form's list for that workshop. */
    fun set(current: List<QBinding>, workshopId: String, bound: Boolean, primary: Boolean): List<QBinding> {
        val others = current.filterNot { it.workshopId == workshopId }
        return if (!bound) others else others + QBinding(workshopId = workshopId, isPrimary = primary)
    }

    fun body(bindings: List<QBinding>): QBindingsPut =
        QBindingsPut(bindings.filter { !it.workshopId.isNullOrBlank() }.distinctBy { it.workshopId }.map { QBinding(workshopId = it.workshopId, isPrimary = it.isPrimary) })
}

/**
 * The start sheet's form (B31, B32, D2): the explicit choice, else what the server resolved for the
 * workshop (primary binding, only binding, the workshop type's default, the global default), else the
 * offline answer from the cached list: the global default.
 */
object FormResolution {
    fun pick(explicit: String?, resolved: QFormFor?, forms: List<QForm>): String? {
        val live = forms.filter { !it.deleted }.map { it.id }.toSet()
        explicit?.takeIf { it in live }?.let { return it }
        resolved?.questionnaireId?.takeIf { it in live }?.let { return it }
        if (resolved?.reason == "PICKER" && resolved.bound.isNotEmpty()) return null
        return forms.firstOrNull { it.isDefault && !it.deleted }?.id
    }

    /** "Bound to this workshop" first, then the rest; a person may always pick any form they can see. */
    fun ordered(forms: List<QForm>, resolved: QFormFor?): List<QForm> {
        val bound = resolved?.bound.orEmpty().map { it.questionnaireId }
        return forms.filter { !it.deleted }.sortedWith(compareBy<QForm> { f -> bound.indexOf(f.id).let { if (it < 0) Int.MAX_VALUE else it } }.thenBy { !it.isDefault }.thenBy { it.title })
    }
}

/**
 * The one answer-lock rule (owner decision 2; UNIFY U9), as the client predicts it: an answer with
 * text belongs to its author, the session's LEAD researchers and content admins. The server is the
 * judge (`ANSWER_LOCKED` per item); this only decides what to draw before asking it.
 */
object AnswerLock {
    fun locked(text: String?, origin: String?, authorId: String?, meId: String?, isLead: Boolean, isContentAdmin: Boolean): Boolean {
        if (text.isNullOrBlank()) return false
        if (origin == "PREFILL") return false
        if (authorId != null && authorId == meId) return false
        if (isContentAdmin || isLead) return false
        return authorId != null
    }

    /** A per-item result the server locked. */
    fun isLockedResult(result: QAnswerResult): Boolean = result.status == "FORBIDDEN" && result.code == "ANSWER_LOCKED"

    /** P15, inline. */
    fun message(authorName: String?): String = FormsCopy.p15(authorName)
}

/** Workshop coverage marks (amendment 2 point 4): ● recorded, ✎ typed only, and "Add a recording?". */
object CoverageMarks {
    const val RECORDED = "●"
    const val TYPED = "✎"

    fun mark(cell: QGridCell): String = when (cell.source) {
        "RECORDED" -> RECORDED
        "TYPED" -> TYPED
        else -> ""
    }

    /** "14 of 24" counts recorded and typed alike. */
    fun count(cell: QGridCell): String = if (cell.total > 0) "${cell.covered} of ${cell.total}" else ""

    fun prompt(cell: QGridCell): String? = if (cell.addRecording || cell.source == "TYPED") FormsCopy.ADD_RECORDING else null

    fun accessibility(cell: QGridCell, artisan: String, section: String): String = listOfNotNull(
        artisan, section,
        when (cell.source) { "RECORDED" -> "Recorded"; "TYPED" -> "Typed"; else -> "Not yet" },
        count(cell).ifBlank { null },
        cell.override?.let(SectionOverride::label)
    ).joinToString(", ")
}

/** The admin's section override keeps three states (amendment 2 point 1). */
object SectionOverride {
    const val COMPLETED = "COMPLETED"
    const val NEEDS_REVIEW = "NEEDS_REVIEW"
    const val NEEDS_REDO = "NEEDS_REDO"
    val STATES = linkedMapOf(COMPLETED to "Marked complete", NEEDS_REVIEW to "Needs review", NEEDS_REDO to "Needs redo")

    fun label(state: String): String = STATES[state] ?: state

    /** The body of a change: a state, or null to clear back to what the data says. */
    fun body(artisanId: String, sectionId: String, state: String?): QCompletionPut {
        require(state == null || state in STATES) { "Not an override state: $state" }
        return QCompletionPut(artisanId, sectionId, state)
    }
}

/** A19: Finish reports required questions left, and never blocks. */
object RequiredQuestions {
    fun line(result: QRequiredUnanswered): String? = when {
        result.count <= 0 -> null
        result.count == 1 -> "1 required question unanswered"
        else -> "${result.count} required questions unanswered"
    }
}

/** Every new sentence of the forms library and the unified session screens on Android. */
object FormsCopy {
    const val FORMS = "Forms"
    const val SESSIONS = "Sessions"
    const val NEW_FORM = "New form"
    const val UPLOAD_WORKBOOK = "Upload workbook"
    const val DOWNLOAD_PRO_FORMA = "Download pro-forma"
    const val OPEN_BUNDLE = "Open bundle"
    const val NO_FORMS = "No forms here yet."
    const val DEFAULT_BADGE = "Default"
    const val SHARED_BADGE = "Shared with all"
    const val FORM_TITLE = "Title"
    const val FORM_DESCRIPTION = "Description"
    const val FORM_KIND = "Kind"
    const val CREATE = "Create"
    const val CANCEL = "Cancel"
    const val SAVE = "Save"
    const val DELETE = "Delete"
    const val RESTORE = "Restore"
    const val REUSE = "Reuse as a new form"
    const val REUSE_BODY = "Copies the sections and questions. No sessions or answers."
    const val REUSED = "Copied. The new form is in Mine."
    const val SEND_TO_PHONE = "Send to a phone"
    const val BINDINGS = "Workshops"
    const val BINDINGS_BODY = "Bound forms are offered for the workshop's sessions. One is primary."
    const val PRIMARY = "Primary"
    const val BOUND = "Bound"
    const val SHARE_ALL = "Share with all"
    const val SHARE_ALL_BODY = "Every researcher can see and use it."
    const val SET_DEFAULT = "Default form"
    const val SET_DEFAULT_BODY = "Offered when a workshop has no form of its own."
    const val TYPE_DEFAULT = "Default for a workshop type"
    const val WORKBOOK = "Workbook"
    const val WORKBOOK_EXPORT = "Download workbook"
    const val QUESTION_SET = "Download questions"
    const val REUPLOAD = "Re-upload workbook"
    const val SECTIONS = "Sections"
    const val ADD_SECTION = "Add section"
    const val SECTION_CODE = "Code"
    const val SECTION_TITLE = "Section title"
    const val ADD_QUESTION = "Add question"
    const val QUESTION_PROMPT = "Question"
    const val QUESTION_HELP = "Help text"
    const val REQUIRED = "Required"
    const val READ_ONLY = "Only the form's author or an admin can edit it."
    const val NEEDS_CONNECTION = "Needs a connection"
    const val ON_PHONE = "On this phone"
    const val SAVED_TO = "Saved to Downloads."
    const val UPLOADED = "Workbook uploaded."
    const val SESSIONS_ON_FORM = "Sessions on this form"
    const val DELETED_SESSIONS = "Deleted sessions"
    const val NOTHING_DELETED = "Nothing deleted."
    const val RESTORED = "Restored."
    const val P16_TITLE = "Delete this form?"
    const val P16_BODY = "Admins can restore it from Deleted."
    const val P17_TITLE = "Received a form"
    const val NOT_NOW = "Not now"
    const val ADD = "Add"
    const val BUNDLE_ADDED = "Added to Mine. It sends when there's a signal."
    const val BUNDLE_ALREADY = "You've added this form already."
    const val SESSION_DELETE_BODY = "Admins can restore it from Deleted sessions."
    const val ADD_RECORDING = "Add a recording?"
    const val COVERAGE = "Workshop coverage"
    const val COVERAGE_PICK = "Pick a workshop and a form."
    const val OVERRIDE = "Override"
    const val CLEAR_OVERRIDE = "Clear override"
    const val HISTORY = "History"
    const val NO_HISTORY = "No earlier versions."
    const val READ_VIEW = "Read view"
    const val APPROVE = "Approve"
    const val APPROVED = "Approved."
    const val CORPUS = "Download corpus"
    const val CORPUS_ANSWERS = "Answers (CSV)"
    const val CORPUS_RECORDINGS = "Recordings (CSV)"
    const val RESEARCHERS = "Researchers"
    const val ADD_RESEARCHER = "Add researcher"
    const val SOMEONE_ELSE = "Someone else"
    const val GUEST_NAME = "Their name"
    const val GUEST_NOTES = "Notes"
    const val EDIT_RESPONDENT = "Edit respondent"
    const val ANSWER_NOTE = "Note"
    const val ANNEXURE = "Questionnaires"
    const val RECORDING_HISTORY = "Recording history"

    fun p15(authorName: String?): String {
        val who = authorName?.trim()?.ifBlank { null } ?: "someone else"
        return "Answered by $who. Only they or a lead can change it."
    }

    fun p17(sender: String?, title: String, sections: Int): String {
        val noun = if (sections == 1) "section" else "sections"
        return if (sender.isNullOrBlank()) "'$title' ($sections $noun)." else "$sender sent '$title' ($sections $noun)."
    }
}

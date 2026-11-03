package com.fieldrepository.app.forms

import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.ui.FieldPermissions

object FormRights {
    fun mayCreate(user: UserDto?): Boolean =
        user != null && (FieldPermissions.rank(user.role) >= FieldPermissions.RANK_PROFESSOR || user.canManageQuestionnaire)

    fun mayChange(user: UserDto?, form: QForm?): Boolean {
        user ?: return false
        if (FieldPermissions.isContentAdmin(user)) return true
        val author = form?.authorId ?: return false
        return author == user.id
    }

    fun mayDelete(user: UserDto?, form: QForm?): Boolean = mayChange(user, form)

    fun mayRestore(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

    fun mayAdminAct(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

    fun maySetDefault(user: UserDto?): Boolean = user != null && FieldPermissions.isAdmin(user)

    fun mayDeleteSession(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

    fun mayOverride(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

    fun mayExportCorpus(user: UserDto?): Boolean = user != null && FieldPermissions.canDownloadDataset(user)

    fun mayReadAnnexure(user: UserDto?): Boolean = mayCreate(user) || mayExportCorpus(user)

    fun mayReview(user: UserDto?): Boolean = user != null && FieldPermissions.canReview(user)
}

enum class FormTab(val wire: String, val label: String) {
    MINE("mine", "Mine"), SHARED("shared", "Shared"), WORKSHOP("workshop", "Workshop"), DELETED("deleted", "Deleted");

    companion object {
        fun visible(user: UserDto?): List<FormTab> = listOfNotNull(MINE, SHARED, WORKSHOP, DELETED.takeIf { FormRights.mayRestore(user) })
    }
}

object FormLibrary {
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

    fun badges(form: QForm): List<String> = listOfNotNull(
        FormsCopy.DEFAULT_BADGE.takeIf { form.isDefault },
        FormsCopy.SHARED_BADGE.takeIf { form.isShared },
        form.kind?.let(FormKinds::label)
    )

    fun counts(form: QForm): String =
        "${form.sectionCount} ${if (form.sectionCount == 1) "section" else "sections"} · ${form.questionCount} ${if (form.questionCount == 1) "question" else "questions"}"
}

object FormKinds {
    const val WORKSHOP_INTERVIEW = "WORKSHOP_INTERVIEW"
    const val MARKET_SURVEY = "MARKET_SURVEY"
    const val NOT_STATED = "Kind not stated"
    val LABELS = linkedMapOf(WORKSHOP_INTERVIEW to "Workshop interview", MARKET_SURVEY to "Market survey")

    fun label(kind: String?): String = kind?.let { LABELS[it] } ?: NOT_STATED

    fun grouped(forms: List<QForm>): List<Pair<String, List<QForm>>> =
        forms.groupBy { it.kind }.toList()
            .sortedBy { (k, _) -> LABELS.keys.indexOf(k).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { (k, list) -> label(k) to list }
}

object FormBindings {
    fun set(current: List<QBinding>, workshopId: String, bound: Boolean, primary: Boolean): List<QBinding> {
        val others = current.filterNot { it.workshopId == workshopId }
        return if (!bound) others else others + QBinding(workshopId = workshopId, isPrimary = primary)
    }

    fun body(bindings: List<QBinding>): QBindingsPut =
        QBindingsPut(bindings.filter { !it.workshopId.isNullOrBlank() }.distinctBy { it.workshopId }.map { QBinding(workshopId = it.workshopId, isPrimary = it.isPrimary) })
}

object FormResolution {
    fun pick(explicit: String?, resolved: QFormFor?, forms: List<QForm>): String? {
        val live = forms.filter { !it.deleted }.map { it.id }.toSet()
        explicit?.takeIf { it in live }?.let { return it }
        resolved?.questionnaireId?.takeIf { it in live }?.let { return it }
        if (resolved?.reason == "PICKER" && resolved.bound.isNotEmpty()) return null
        return forms.firstOrNull { it.isDefault && !it.deleted }?.id
    }

    fun ordered(forms: List<QForm>, resolved: QFormFor?): List<QForm> {
        val bound = resolved?.bound.orEmpty().map { it.questionnaireId }
        return forms.filter { !it.deleted }.sortedWith(compareBy<QForm> { f -> bound.indexOf(f.id).let { if (it < 0) Int.MAX_VALUE else it } }.thenBy { !it.isDefault }.thenBy { it.title })
    }
}

object FormsCopy {
    const val FORMS = "Forms"
    const val SESSIONS = "Sessions"
    const val NEW_FORM = "New form"
    const val START_SESSION = "Start session"
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
    const val ANNEXURE = "Workshop annexure"
    const val DOWNLOAD_ANNEXURE = "Download annexure"
    const val NO_ANNEXURE = "No questionnaires for this workshop yet."
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

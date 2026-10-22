package com.fieldrepository.app.questionnaire.portal

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.forms.FormsNav
import com.fieldrepository.app.forms.FormsTab
import com.fieldrepository.app.questionnaire.data.QArtisanOption
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.ui.ArtisanQuestionnaireRecord
import com.fieldrepository.app.questionnaire.ui.QuestionnaireHome
import com.fieldrepository.app.questionnaire.ui.QuestionnaireTab
import com.fieldrepository.app.questionnaire.ui.QuestionnaireV2Nav
import com.fieldrepository.app.questionnaire.ui.openAppDownloadPage
import com.fieldrepository.app.ui.FieldPermissions
import com.fieldrepository.app.ui.LocalMayDelete

/** The height the questionnaire home is given inside the app's scrolling page. */
@Composable
fun questionnaireViewportHeight(): Dp =
    (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 190.dp).coerceAtLeast(420.dp)

/** Whether the server has sessions switched on (QUESTIONNAIRE_V2). */
@Composable
fun questionnaireSessionsOn(): Boolean {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val config by v2.config.collectAsState()
    return config?.v2Enabled == true
}

/** This app's artisans, as the shared screens read them. */
fun ArtisanDto.asQuestionnaireArtisan(): QArtisanOption = QArtisanOption(id = id, name = name, place = place, craft = craft?.name)

/**
 * The questionnaire home as Field Repository hosts it (UNIFY 4.2 Android): Sessions | Forms. The
 * Forms tab is this app's own library (`forms/`), with admin authoring served on Android (owner
 * decision 5). With sessions switched off on the server the library is the whole questionnaire.
 */
@Composable
fun FieldQuestionnaireHome(
    artisans: List<ArtisanDto>,
    user: UserDto?,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: QuestionnaireTab = QuestionnaireTab.SESSIONS,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val config by v2.config.collectAsState()
    LaunchedEffect(Unit) { runCatching { v2.refreshConfig() } }
    val contentAdmin = FieldPermissions.isContentAdmin(user)
    if (config?.v2Enabled != true) {
        androidx.compose.foundation.layout.Column(modifier.verticalScroll(rememberScrollState())) { FormsTab(user, null, onError) }
        return
    }
    val pending by QuestionnaireV2Nav.pending.collectAsState()
    val prefill = remember(pending) { listOfNotNull(pending) }
    LaunchedEffect(pending) { if (pending != null) QuestionnaireV2Nav.pending.value = null }
    CompositionLocalProvider(LocalMayDelete provides contentAdmin) {
        QuestionnaireHome(
            artisans = remember(artisans) { artisans.map { it.asQuestionnaireArtisan() } },
            meId = user?.id,
            meName = user?.name,
            prefillArtisanIds = prefill,
            canMakePrimary = FieldPermissions.rank(user?.role) >= FieldPermissions.RANK_PROFESSOR,
            isAdmin = contentAdmin,
            canReview = user?.let(FieldPermissions::canReview) == true,
            formsTab = { onStartSession -> FormsTab(user, onStartSession, onError) },
            onNewForm = { FormsNav.newForm.value = true },
            onUpdateApp = { openAppDownloadPage(context) },
            onError = onError,
            initialTab = initialTab,
            onBack = onBack,
            modifier = modifier
        )
    }
}

/** An artisan's questionnaire record (B26, C6) on their page; "Continue with…" opens the questionnaire home. */
@Composable
fun FieldArtisanQuestionnaireRecord(artisanId: String, artisanName: String, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    ArtisanQuestionnaireRecord(
        v2 = v2, artisanId = artisanId, artisanName = artisanName,
        onContinueWith = { QuestionnaireV2Nav.pending.value = it },
        onStartSession = { QuestionnaireV2Nav.pending.value = it },
        onOpenSession = { QuestionnaireV2Nav.openServerSession.value = it },
        onMessage = onMessage
    )
}

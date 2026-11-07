package com.fieldrepository.app

import com.fieldrepository.app.transcript.data.TranscribeChoices
import com.fieldrepository.app.transcript.data.UploadTranscribe
import com.fieldrepository.app.transcript.ui.TranscribeChoiceItem
import com.fieldrepository.app.transcript.ui.TranscribeChoiceRows
import com.fieldrepository.app.data.workshopDisplayTitle
import com.fieldrepository.app.data.toolArtisanNames
import com.fieldrepository.app.data.toolRowLabel
import com.fieldrepository.app.data.AnswerDrafts
import com.fieldrepository.app.ui.AnswerDraftsCard
import com.fieldrepository.app.ui.ArtisanCardPickerScan
import com.fieldrepository.app.ui.ArtisanCardScanDialog
import com.fieldrepository.app.ui.RecordingConsentRow
import com.fieldrepository.app.ui.SharingConsentRow
import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.FieldProgressDefaults
import com.fieldrepository.app.ui.CompletenessAnchor
import com.fieldrepository.app.ui.CompletenessFilterChips
import com.fieldrepository.app.ui.CompletenessFilterMemory
import com.fieldrepository.app.ui.CompletenessMeter
import com.fieldrepository.app.ui.CompletenessRing
import com.fieldrepository.app.ui.LiveCompletenessLine
import com.fieldrepository.app.ui.ProvideCompletenessFocus
import com.fieldrepository.app.ui.rememberCompletenessRules
import com.fieldrepository.app.ui.rememberImageCount
import com.fieldrepository.app.data.CompletenessDto
import com.fieldrepository.app.data.RecordMediaReviewDto
import com.fieldrepository.app.data.CompletenessFilter
import com.fieldrepository.app.data.CompletenessKind
import com.fieldrepository.app.data.RecordCompletenessStore
import com.fieldrepository.app.data.completenessSentence
import com.fieldrepository.app.data.evaluateCompleteness
import com.fieldrepository.app.data.liveCompletenessCounts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import com.fieldrepository.app.ui.FieldCheckbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Timeline
import com.fieldrepository.app.ui.trace.TracePanel
import com.fieldrepository.app.ui.ActionRow
import com.fieldrepository.app.ui.ReviewFeedbackCopy
import com.fieldrepository.app.ui.ReviewNoteBanner
import com.fieldrepository.app.ui.RecordReviewBar
import com.fieldrepository.app.ui.LocalMediaReview
import com.fieldrepository.app.ui.MediaRejectAction
import com.fieldrepository.app.ui.MediaReviewScope
import com.fieldrepository.app.ui.ReviewReasonPicker
import com.fieldrepository.app.ui.SentBackCard
import com.fieldrepository.app.ui.AppToggle
import com.fieldrepository.app.ui.MediaAction
import com.fieldrepository.app.ui.MediaActionButton
import com.fieldrepository.app.ui.MediaDownloadButton
import com.fieldrepository.app.ui.TILE_OUTLINE_ALPHA
import androidx.compose.foundation.BorderStroke
import com.fieldrepository.app.ui.OutboxPendingBanner
import com.fieldrepository.app.ui.FormDraft
import com.fieldrepository.app.ui.TranscriptInsightsCard
import com.fieldrepository.app.transcript.ui.TranscriptEntry
import com.fieldrepository.app.ui.RECORD_UPDATE_LABEL
import com.fieldrepository.app.ui.recordUpdateAllowed
import com.fieldrepository.app.ui.recordUpdateTitle
import com.fieldrepository.app.ui.recordViewTitle
import com.fieldrepository.app.ui.rememberFormDraft
import com.fieldrepository.app.ui.FormDraftAutosave
import com.fieldrepository.app.ui.DraftOfferCard
import com.fieldrepository.app.data.DraftStore
import com.fieldrepository.app.data.StandardValues
import com.fieldrepository.app.data.countNoun
import com.fieldrepository.app.data.DraftValues
import com.fieldrepository.app.data.OutboxStep
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import com.fieldrepository.app.ui.parseFieldDate
import com.fieldrepository.app.ui.CalendarField
import com.fieldrepository.app.ui.FieldDateRangeField
import com.fieldrepository.app.ui.FieldIslandNav
import com.fieldrepository.app.ui.NavGroup
import com.fieldrepository.app.ui.visibleNavItems
import com.fieldrepository.app.ui.IslandEntry
import com.fieldrepository.app.ui.IslandGroup
import com.fieldrepository.app.ui.IslandScaffold
import com.fieldrepository.app.ui.APP_SEARCH_NAME
import com.fieldrepository.app.ui.AppMenuFocusEffect
import com.fieldrepository.app.ui.AppSearchHeader
import com.fieldrepository.app.ui.QuickReturnHeader
import com.fieldrepository.app.ui.rememberQuickReturnHeader
import com.fieldrepository.app.ui.rememberIslandImeVisible
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import com.fieldrepository.app.ui.AppSearchItem
import com.fieldrepository.app.ui.AppSearchPanel
import com.fieldrepository.app.ui.AppSearchSettingTarget
import com.fieldrepository.app.ui.FieldRepoLogo
import com.fieldrepository.app.ui.appMenuBleed
import com.fieldrepository.app.ui.appMenuHidesPage
import com.fieldrepository.app.ui.appMenuShowing
import com.fieldrepository.app.ui.appSearchCachedItems
import com.fieldrepository.app.ui.appSearchRecordingItems
import com.fieldrepository.app.ui.appSearchRemoteItems
import com.fieldrepository.app.ui.RemoteRecordScreen
import com.fieldrepository.app.ui.appSearchScreenItems
import com.fieldrepository.app.ui.appSearchSettingItems
import com.fieldrepository.app.ui.closeAppMenu
import com.fieldrepository.app.ui.openAppMenu
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.RecordDangerZone
import com.fieldrepository.app.ui.RecordDangerZoneCard
import com.fieldrepository.app.ui.AppMessageBanner
import com.fieldrepository.app.ui.BANNER_STACK_GAP
import com.fieldrepository.app.ui.LocalBannerClearance
import com.fieldrepository.app.ui.bannerDrawn
import com.fieldrepository.app.ui.bannerAlreadySays
import com.fieldrepository.app.ui.bannerClearance
import com.fieldrepository.app.ui.readAdminView
import com.fieldrepository.app.ui.restoredAdminView
import com.fieldrepository.app.ui.writeAdminView
import com.fieldrepository.app.ui.pageBottomReserve
import com.fieldrepository.app.ui.AppMessageState
import com.fieldrepository.app.ui.AppMessageTone
import com.fieldrepository.app.ui.CAMERA_PERMISSION
import com.fieldrepository.app.ui.CAMERA_REFUSED_SENTENCE
import com.fieldrepository.app.ui.LOCATION_PERMISSIONS
import com.fieldrepository.app.ui.LoadFailedCard
import com.fieldrepository.app.ui.MICROPHONE_PERMISSION
import com.fieldrepository.app.ui.MICROPHONE_REFUSED_SENTENCE
import com.fieldrepository.app.ui.holdsLocation
import com.fieldrepository.app.ui.rememberPermissionGate
import com.fieldrepository.app.ui.rememberKeepShootingCamera
import com.fieldrepository.app.ui.KeepShootingToggle
import com.fieldrepository.app.ui.nextStepChoices
import com.fieldrepository.app.ui.nextStepHeading
import com.fieldrepository.app.ui.plural
import com.fieldrepository.app.ui.artisanPrefill
import com.fieldrepository.app.ui.orderByCaptureTime
import com.fieldrepository.app.ui.artisanDetailFromListed
import com.fieldrepository.app.ui.artisanFieldUpdate
import com.fieldrepository.app.ui.aadhaarForBody
import com.fieldrepository.app.ui.aadhaarNotSharedOffered
import com.fieldrepository.app.ui.automaticInterviewTitle
import com.fieldrepository.app.ui.questionTranscripts
import com.fieldrepository.app.ui.transcriptOffer
import com.fieldrepository.app.ui.TRANSCRIPT_SOURCE
import com.fieldrepository.app.ui.ARTISAN_FIELD_OPTIONS
import com.fieldrepository.app.ui.ArtisanLookupCard
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.LocalContentColor
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.SignInOutcome
import com.fieldrepository.app.data.PasswordChangeSignal
import com.fieldrepository.app.data.SessionEndedSignal
import com.fieldrepository.app.data.SessionVerdict
import com.fieldrepository.app.data.sessionVerdict
import com.fieldrepository.app.ui.ForgotPasswordScreen
import com.fieldrepository.app.ui.HelpThemBackInActions
import com.fieldrepository.app.ui.IssuedPasswordLinkPanel
import com.fieldrepository.app.ui.passwordLinkOffered
import com.fieldrepository.app.data.IssuedPasswordLinkDto
import com.fieldrepository.app.ui.AddPersonCard
import com.fieldrepository.app.ui.PasswordGateScreen
import com.fieldrepository.app.ui.SESSION_ENDED_SENTENCE
import com.fieldrepository.app.ui.SetPasswordLinkScreen
import com.fieldrepository.app.ui.isSetPasswordLinkAddress
import com.fieldrepository.app.ui.mustChangePasswordBlocks
import com.fieldrepository.app.data.ARTISAN_FORM_CONTRACT
import com.fieldrepository.app.data.ArtisanCreateRequest
import com.fieldrepository.app.data.DuplicateCandidateDto
import com.fieldrepository.app.data.DuplicateCheckRequest
import com.fieldrepository.app.data.PossibleDuplicates
import com.fieldrepository.app.data.possibleDuplicates
import com.fieldrepository.app.ui.PossibleDuplicateSheet
import com.fieldrepository.app.data.EXPERIENCE_MONTH_OPTIONS
import com.fieldrepository.app.data.EXPERIENCE_NOT_STATED
import com.fieldrepository.app.data.experienceMonthsLabel
import com.fieldrepository.app.data.missingArtisanDatesSentence
import com.fieldrepository.app.data.CarryContext
import com.fieldrepository.app.data.CarryContextStore
import com.fieldrepository.app.data.CarryNode
import com.fieldrepository.app.data.CompletionCellDto
import com.fieldrepository.app.data.CompletionMatrixDto
import com.fieldrepository.app.data.CraftCreateRequest
import com.fieldrepository.app.data.DataAccessGrantDto
import com.fieldrepository.app.data.DataAccessScopeItemDto
import com.fieldrepository.app.data.DataAccessTierInfo
import com.fieldrepository.app.data.EntryCommentDto
import com.fieldrepository.app.data.EntryCommentBody
import com.fieldrepository.app.data.OfflineMediaSpec
import com.fieldrepository.app.data.OutboxKind
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.data.TaskUpdateBody
import com.fieldrepository.app.data.MyGrantsDto
import com.fieldrepository.app.data.RecordRevisionDto
import com.fieldrepository.app.data.DashboardRecentSubmissionDto
import com.fieldrepository.app.data.DashboardStats
import com.fieldrepository.app.data.DashboardStatsMine
import com.fieldrepository.app.data.EVERY_PAGE_BUDGET
import com.fieldrepository.app.data.EVERY_WORKSHOP
import com.fieldrepository.app.data.REVIEW_PAGE_SIZE
import com.fieldrepository.app.data.MyRecordsPage
import com.fieldrepository.app.ui.MyActivityOlder
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.GoogleAuthClient
import com.fieldrepository.app.data.OidcAuthClient
import com.fieldrepository.app.data.OidcLoginRequest
import com.fieldrepository.app.data.OidcProvider
import com.fieldrepository.app.data.OidcProviderId
import com.fieldrepository.app.data.oidcCallbackErrorMessage
import com.fieldrepository.app.ui.LocalProviderReauth
import com.fieldrepository.app.ui.OidcProviderMark
import com.fieldrepository.app.ui.ProviderReauth
import com.fieldrepository.app.data.LocationRequest
import com.fieldrepository.app.data.MeasurementMarkers
import com.fieldrepository.app.data.geometryMarker
import com.fieldrepository.app.data.visionMarker
import com.fieldrepository.app.data.ProductCreateRequest
import com.fieldrepository.app.data.QuestionnaireDto
import com.fieldrepository.app.data.QuestionnaireInterviewCreateRequest
import com.fieldrepository.app.data.QuestionnaireInterviewDetailDto
import com.fieldrepository.app.data.QuestionnaireInterviewUpdateRequest
import com.fieldrepository.app.data.QuestionnaireQuestionCreateRequest
import com.fieldrepository.app.data.QuestionnaireQuestionDto
import com.fieldrepository.app.data.QuestionnaireQuestionUpdateRequest
import com.fieldrepository.app.data.QuestionnaireResponseRequest
import com.fieldrepository.app.data.QuestionnaireSectionCreateRequest
import com.fieldrepository.app.data.QuestionnaireSectionDto
import com.fieldrepository.app.data.QuestionnaireSectionUpdateRequest
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.data.LocalAlerts
import com.fieldrepository.app.data.NotificationInboxRules
import com.fieldrepository.app.data.NotificationState
import com.fieldrepository.app.data.NotificationSyncWorker
import com.fieldrepository.app.data.NotificationTarget
import com.fieldrepository.app.data.notificationTarget
import com.fieldrepository.app.ui.NotificationsScreen
import com.fieldrepository.app.data.ToolCreateRequest
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.WorkshopCreateRequest
import com.fieldrepository.app.data.apiErrorMessage
import com.fieldrepository.app.data.apiFailure
import com.fieldrepository.app.data.InterviewSetHolder
import com.fieldrepository.app.data.MergeAnswerConflict
import com.fieldrepository.app.data.artisanSetHolder
import com.fieldrepository.app.data.mergeAnswerConflicts
import com.fieldrepository.app.ui.answerBoxVisible
import com.fieldrepository.app.ui.mergeConflictLines
import com.fieldrepository.app.ui.mergeOfferQuestion
import com.fieldrepository.app.ui.sectionsToRevealOnEdit
import com.fieldrepository.app.ui.unsavedBeforeMergeNotice
import com.fieldrepository.app.data.occurrenceDate
import com.fieldrepository.app.ui.AccessRosterScreen
import com.fieldrepository.app.ui.RecordSwitchKind
import com.fieldrepository.app.ui.recordOptionLabel
import com.fieldrepository.app.ui.RecordSwitcher
import com.fieldrepository.app.ui.RecordSwitcherState
import com.fieldrepository.app.ui.recordSwitcherTitle
import com.fieldrepository.app.ui.rememberRecordSwitcher
import com.fieldrepository.app.ui.WorkshopWindowState
import com.fieldrepository.app.ui.WalkthroughDialog
import com.fieldrepository.app.ui.markWalkthroughSeen
import com.fieldrepository.app.ui.walkthroughSeen
import com.fieldrepository.app.ui.formatWorkshopDay
import com.fieldrepository.app.ui.workshopWindowNotice
import com.fieldrepository.app.ui.workshopWindowState
import com.fieldrepository.app.ui.ApiKeysScreen
import com.fieldrepository.app.ui.MyAiKeysScreen
import com.fieldrepository.app.ui.TwoStepChallengeForm
import com.fieldrepository.app.ui.TwoStepVerificationScreen
import com.fieldrepository.app.ui.LocalNetworkAccessForDevelopmentBackend
import com.fieldrepository.app.ui.AppPreferences
import com.fieldrepository.app.ui.AppPreferencesStore
import com.fieldrepository.app.ui.AppNavigationDrawerContent
import com.fieldrepository.app.ui.AppearanceScreen
import com.fieldrepository.app.ui.Body
import com.fieldrepository.app.ui.NavDestination
import com.fieldrepository.app.ui.ArtisanPhoneField
import com.fieldrepository.app.ui.artisanPhoneValidationError
import com.fieldrepository.app.ui.Canvas
import com.fieldrepository.app.ui.CarryPrefillBanner
import com.fieldrepository.app.ui.CarryPrefillDefaults
import com.fieldrepository.app.ui.CarryPrefillState
import com.fieldrepository.app.ui.CarryScope
import com.fieldrepository.app.ui.CarryScopeState
import com.fieldrepository.app.ui.carryScope
import com.fieldrepository.app.ui.PRODUCT_MEASURE_DIMENSIONS
import com.fieldrepository.app.ui.RecordMeasureField
import com.fieldrepository.app.ui.TOOL_MEASURE_DIMENSIONS
import com.fieldrepository.app.ui.artisansByCraftThenName
import com.fieldrepository.app.ui.cmTextFromInches
import com.fieldrepository.app.ui.craftChangeClearsArtisan
import com.fieldrepository.app.ui.craftNameFor
import com.fieldrepository.app.ui.craftsChangeClearsArtisans
import com.fieldrepository.app.ui.inchesTextFromCm
import com.fieldrepository.app.ui.propagateDimension
import com.fieldrepository.app.ui.artisansNotAtWorkshop
import com.fieldrepository.app.ui.mergeArtisansById
import com.fieldrepository.app.ui.outOfWorkshopNotice
import com.fieldrepository.app.ui.rememberArtisanPicker
import com.fieldrepository.app.ui.CraftPickerField
import com.fieldrepository.app.data.CraftCatalogueCache
import com.fieldrepository.app.ui.rememberServerSearch
import com.fieldrepository.app.ui.ServerSearch
import com.fieldrepository.app.ui.ServerSearchState
import com.fieldrepository.app.ui.artisanSelectOption
import com.fieldrepository.app.ui.artisanFirstOption
import com.fieldrepository.app.ui.ARTISAN_PICK_SENTENCE
import com.fieldrepository.app.ui.artisanSearchText
import com.fieldrepository.app.ui.craftSearchText
import com.fieldrepository.app.ui.carryScopeStateFor
import com.fieldrepository.app.ui.LazyPageChrome
import com.fieldrepository.app.ui.LazyScrollMark
import com.fieldrepository.app.ui.PageLazyColumn
import com.fieldrepository.app.ui.lazyRestoreReady
import com.fieldrepository.app.ui.SERVER_SEARCH_PAGE_SIZE
import com.fieldrepository.app.data.PageResponse
import com.fieldrepository.app.ui.rememberCarryPrefill
import com.fieldrepository.app.ui.Coral
import com.fieldrepository.app.ui.ConsolidatedQuestionnaireScreen
import com.fieldrepository.app.ui.DataBrowserScreen
import com.fieldrepository.app.ui.DataUnifiedSearch
import com.fieldrepository.app.ui.dataFolderArtisan
import com.fieldrepository.app.ui.FieldRepositoryTheme
import com.fieldrepository.app.ui.MapScreen
import com.fieldrepository.app.ui.RecordProseField
import com.fieldrepository.app.ui.RecordFormKind
import com.fieldrepository.app.ui.recordDictationFor
import com.fieldrepository.app.ui.WorkshopScopeSelect
import com.fieldrepository.app.ui.rememberWorkshopScope
import com.fieldrepository.app.ui.ProvideAppPreferences
import com.fieldrepository.app.ui.SearchRecordTypes
import com.fieldrepository.app.ui.SearchScreen
import com.fieldrepository.app.ui.SearchableMultiSelectField
import com.fieldrepository.app.ui.SegmentedChoice
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.asSelectOptions
import com.fieldrepository.app.ui.GrantAccessFields
import com.fieldrepository.app.ui.RequestAccessFields
import com.fieldrepository.app.ui.TaskAdminScreen
import com.fieldrepository.app.ui.field
import com.fieldrepository.app.ui.Muted
import com.fieldrepository.app.ui.resolveDarkTheme
import com.fieldrepository.app.ui.syncAppPreferences
import com.fieldrepository.app.ui.SurfaceCard
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import retrofit2.HttpException
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.fieldrepository.app.data.ArtisanCardCode
import com.fieldrepository.app.data.ArtisanCardResolution
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.ArtisanAnswerDto
import com.fieldrepository.app.data.ArtisanDetailDto
import com.fieldrepository.app.data.ArtisanQuestionnaireDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.CreatedRecordDto
import com.fieldrepository.app.data.AppScope
import com.fieldrepository.app.data.AddressReferenceDto
import com.fieldrepository.app.data.LocationDto
import com.fieldrepository.app.data.AppReleaseDto
import com.fieldrepository.app.data.FeedbackDto
import com.fieldrepository.app.data.FeedbackUpsertRequest
import com.fieldrepository.app.data.MediaFileDto
import com.fieldrepository.app.data.AppSettingUpdateRequest
import com.fieldrepository.app.data.PendingReviewDto
import com.fieldrepository.app.data.ReviewReasonDto
import com.fieldrepository.app.data.StagedMedia
import com.fieldrepository.app.data.TaskDto
import com.fieldrepository.app.data.WorkshopAccessLevelDto
import com.fieldrepository.app.data.WorkshopAssignmentDto
import com.fieldrepository.app.data.WorkshopMappingCounts
import com.fieldrepository.app.data.WorkshopMappingPlanDto
import com.fieldrepository.app.data.WorkshopSubmissionCheckDto
import com.fieldrepository.app.data.titleCasePreview
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.fieldrepository.app.data.ProductDetailDto
import com.fieldrepository.app.data.ProcessCreateRequest
import com.fieldrepository.app.data.ProcessDetailDto
import com.fieldrepository.app.data.ProcessStepDto
import com.fieldrepository.app.data.ProcessStepRequest
import com.fieldrepository.app.data.ToolDetailDto
import com.fieldrepository.app.data.WorkshopDetailDto
import com.fieldrepository.app.data.accountHasPassword
import androidx.compose.runtime.mutableStateListOf
import com.fieldrepository.app.ui.ArtisanQuestionnairePanel
import com.fieldrepository.app.ui.LocationFieldsSection
import com.fieldrepository.app.ui.artisanLocationRequirementError
import com.fieldrepository.app.ui.ARTISAN_LOCATION_HEADING
import com.fieldrepository.app.ui.InheritedPlace
import com.fieldrepository.app.ui.MediaThumb
import com.fieldrepository.app.ui.MediaViewerDialog
import com.fieldrepository.app.ui.ProvenanceSection
import com.fieldrepository.app.ui.RecordingIndicator
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.ui.LocalScreenMemory
import com.fieldrepository.app.questionnaire.ui.ScreenMemories
import com.fieldrepository.app.questionnaire.ui.rememberKept
import com.fieldrepository.app.questionnaire.portal.FieldArtisanQuestionnaireRecord
import com.fieldrepository.app.questionnaire.portal.FieldQuestionnaireHome
import com.fieldrepository.app.questionnaire.portal.questionnaireViewportHeight
import com.fieldrepository.app.questionnaire.ui.QuestionnaireV2Nav
import com.fieldrepository.app.ui.FieldScanControls
import com.fieldrepository.app.ui.TopBarScanSheet
import com.fieldrepository.app.ui.fieldScanServices
import com.fieldrepository.app.ui.runFieldScanAction
import com.fieldrepository.app.data.ScanCopy
import com.fieldrepository.app.data.ScanTarget
import com.fieldrepository.app.data.dispatchScan

class MainActivity : ComponentActivity() {
    private val incomingPasswordLink = mutableStateOf<String?>(null)

    private fun takePasswordLink(intent: Intent?, restored: Boolean = false) {
        if (restored || intent == null) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (!isSetPasswordLinkAddress(uri.scheme, uri.host, uri.path)) return
        incomingPasswordLink.value = uri.toString()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takePasswordLink(intent)
        LocalAlerts.takeTap(intent)
        com.fieldrepository.app.explorer.data.ExplorerDownloadsTap.take(intent)
        com.fieldrepository.app.explorer.ui.ExplorerViewLinks.take(intent)
        com.fieldrepository.app.forms.FormBundleInbox.take(applicationContext, intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        takePasswordLink(intent, restored = savedInstanceState != null)
        if (savedInstanceState == null) LocalAlerts.takeTap(intent)
        if (savedInstanceState == null) com.fieldrepository.app.explorer.data.ExplorerDownloadsTap.take(intent)
        if (savedInstanceState == null) com.fieldrepository.app.explorer.ui.ExplorerViewLinks.take(intent)
        if (savedInstanceState == null) com.fieldrepository.app.forms.FormBundleInbox.take(applicationContext, intent)
        val tokenStore = TokenStore(applicationContext)
        val repository = FieldRepository(ApiClient.create(tokenStore, OutboxSyncWorker.readCacheDir(applicationContext)), tokenStore)
        com.fieldrepository.app.data.PreviewCache.install(java.io.File(applicationContext.noBackupFilesDir, "media-previews")) {
            repository.cachedUser()?.id
        }
        val googleAuthClient = GoogleAuthClient(this)
        val oidcAuthClient = OidcAuthClient(this)
        val preferencesStore = AppPreferencesStore(applicationContext)
        val storedPreferences = preferencesStore.read()
        setContent {
            var preferences by remember { mutableStateOf(storedPreferences) }
            FieldRepositoryTheme(darkTheme = resolveDarkTheme(preferences.theme, isSystemInDarkTheme())) {
                ProvideAppPreferences(preferences) {
                    CompositionLocalProvider(LocalProviderReauth provides ProviderReauth(googleAuthClient, oidcAuthClient)) {
                    RepositoryApp(
                        repository = repository,
                        googleAuthClient = googleAuthClient,
                        oidcAuthClient = oidcAuthClient,
                        preferences = preferences,
                        onPreferencesChanged = { next ->
                            preferences = next
                            preferencesStore.write(next)
                        },
                        incomingPasswordLink = incomingPasswordLink.value,
                        onIncomingPasswordLinkConsumed = { incomingPasswordLink.value = null }
                    )
                    }
                }
            }
        }
    }
}

private enum class EntryMode(
    val label: String,
    val actionTitle: String,
    val editable: Boolean = false,
    val onDashboard: Boolean = true
) {
    ARTISAN("Artisan", "Record artisan", editable = true),
    PRODUCT("Product", "Record product", editable = true),
    PROCESS("Process", "Document process", editable = true),
    TOOL("Tool", "Record tool", editable = true),
    QUESTIONNAIRE("Questionnaire", "Take interview", editable = true),
    MEDIA("Miscellaneous Media", "Upload media"),
    VIEW_DATA("View Data", "Browse records"),
    SEARCH("Search", "Search", onDashboard = false),
    DATA_BROWSER("Data Browser", "Data Browser", onDashboard = false),
    MAP("Map", "Map"),
    CONSOLIDATED_QUESTIONNAIRE("Consolidated questionnaire", "Consolidated questionnaire"),
    TASKS("Tasks", "My tasks"),
    SHARING("Sharing", "Share data access"),
    WORKSHOP_ACCESS("Workshop access", "Request workshop access"),
    USERS("Users", "Manage users"),
    CRAFT("Craft", "Add craft", editable = true),
    WORKSHOP("Workshop", "Record workshop", editable = true)
}

private val ScreenSaver = Saver<Screen, String>(
    save = { s -> if (s is Screen.DataBrowser || (s is Screen.View && s.back is Screen.DataBrowser)) "data" else "home" },
    restore = { if (it == "data") Screen.DataBrowser else Screen.Dashboard },
)

private sealed interface Screen {
    data object Dashboard : Screen
    data class Create(
        val mode: EntryMode,
        val prefill: Prefill? = null,
        val searchFocus: String? = null,
        val fresh: Int = 0
    ) : Screen
    data class Browse(val mode: EntryMode) : Screen
    data class View(val mode: EntryMode, val recordId: String, val back: Screen = Dashboard, val section: String? = null) : Screen
    data class Edit(val mode: EntryMode, val recordId: String, val back: Screen? = null, val focus: String? = null) : Screen
    data object MyActivity : Screen
    data object ToolAssign : Screen
    data object Feedback : Screen
    data object Notifications : Screen
    data object Settings : Screen
    data object Appearance : Screen

    data object MyAiKeys : Screen

    data object TwoStepVerification : Screen
    data object DataBrowser : Screen
    data class AdminHub(val section: AdminHubEntry? = null) : Screen
    data class RemoteRecord(val type: String, val id: String, val from: Screen? = null) : Screen
}

private data class Prefill(
    val artisanId: String? = null,
    val artisanName: String? = null,
    val place: String? = null,
    val craftId: String? = null,
    val craftName: String? = null,
    val productId: String? = null,
    val productName: String? = null
)

private fun Prefill.forAnother(mode: EntryMode): Prefill = when (mode) {
    EntryMode.ARTISAN -> Prefill(craftId = craftId, craftName = craftName)
    EntryMode.CRAFT -> Prefill()
    else -> copy(productId = null, productName = null)
}

private data class NextStep(val saved: EntryMode, val name: String?, val prefill: Prefill)

private fun Prefill.toCarryContext(): CarryContext = CarryContext(
    artisanId = artisanId,
    artisanName = artisanName,
    place = place,
    craftId = craftId,
    craftName = craftName,
    productId = productId,
    productName = productName
)

@Composable
private fun rememberFormCarry(
    repository: FieldRepository,
    enabled: Boolean,
    applies: Set<CarryNode>,
    scopes: List<CarryScope>,
    handoff: Prefill? = null,
    onApply: (CarryContext) -> Unit
): CarryPrefillState {
    val appContext = LocalContext.current.applicationContext
    val store = remember(appContext) { CarryContextStore(appContext) }
    val userId = remember(repository) { repository.cachedUser()?.id }
    return rememberCarryPrefill(
        store = store,
        userId = userId,
        enabled = enabled,
        handoff = handoff?.toCarryContext(),
        applies = applies,
        scopes = scopes,
        onApply = onApply
    )
}

private fun String.exceptCarried(carried: String?): String = if (carried != null && this == carried) "" else this

private fun EntryMode.icon(): ImageVector = when (this) {
    EntryMode.ARTISAN -> Icons.Filled.Person
    EntryMode.PRODUCT -> Icons.Filled.Inventory2
    EntryMode.PROCESS -> Icons.Filled.AccountTree
    EntryMode.TOOL -> Icons.Filled.Build
    EntryMode.QUESTIONNAIRE -> Icons.Filled.Quiz
    EntryMode.WORKSHOP -> Icons.Filled.Groups
    EntryMode.CRAFT -> Icons.Filled.Brush
    EntryMode.MEDIA -> Icons.Filled.PermMedia
    EntryMode.VIEW_DATA -> Icons.Filled.Visibility
    EntryMode.SEARCH -> Icons.Filled.Search
    EntryMode.DATA_BROWSER -> Icons.Filled.Storage
    EntryMode.MAP -> Icons.Filled.Map
    EntryMode.CONSOLIDATED_QUESTIONNAIRE -> Icons.Filled.Layers
    EntryMode.TASKS -> Icons.AutoMirrored.Filled.Assignment
    EntryMode.SHARING -> Icons.Filled.Share
    EntryMode.WORKSHOP_ACCESS -> Icons.Filled.LockOpen
    EntryMode.USERS -> Icons.Filled.ManageAccounts
}

@Composable
private fun RepositoryApp(
    repository: FieldRepository,
    googleAuthClient: GoogleAuthClient,
    oidcAuthClient: OidcAuthClient,
    preferences: AppPreferences,
    onPreferencesChanged: (AppPreferences) -> Unit,
    incomingPasswordLink: String? = null,
    onIncomingPasswordLinkConsumed: () -> Unit = {}
) {
    LocalNetworkAccessForDevelopmentBackend()
    val scope = rememberCoroutineScope()
    val signOutContext = LocalContext.current.applicationContext
    var user by remember { mutableStateOf(repository.cachedUser()) }
    var loading by remember { mutableStateOf(user == null && repository.hasToken()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refusalCode by remember { mutableStateOf<String?>(null) }
    var challenge by rememberSaveable { mutableStateOf<String?>(null) }
    var doorPassword by remember { mutableStateOf("") }
    var redeemingLink by remember { mutableStateOf(false) }
    var pastedLink by remember { mutableStateOf("") }
    var forgotPassword by remember { mutableStateOf(false) }
    fun take(outcome: SignInOutcome) {
        when (outcome) {
            is SignInOutcome.SignedIn -> user = outcome.user
            is SignInOutcome.TwoStepRequired -> challenge = outcome.challengeToken
        }
    }

    var pendingOidc by rememberSaveable { mutableStateOf<String?>(null) }
    val oidcLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val started = pendingOidc?.split('|', limit = 2)
        pendingOidc = null
        val provider = started?.getOrNull(0)?.let { name -> OidcProviderId.entries.firstOrNull { it.name == name } }
        val rawNonce = started?.getOrNull(1)
        if (provider == null || rawNonce == null) {
            loading = false
            return@rememberLauncherForActivityResult
        }
        when (val outcome = oidcAuthClient.finish(result.data)) {
            is OidcAuthClient.Outcome.Failed -> {
                refusalCode = null
                error = oidcCallbackErrorMessage(provider, outcome.error)
                loading = false
            }
            is OidcAuthClient.Outcome.Code -> scope.launch {
                runCatching {
                    repository.loginWithOidc(
                        OidcLoginRequest(
                            oidcProvider = provider.name,
                            oidcCode = outcome.code,
                            oidcCodeVerifier = outcome.codeVerifier,
                            oidcRedirectUri = outcome.redirectUri,
                            oidcNonce = rawNonce,
                        )
                    )
                }
                    .onSuccess { take(it) }
                    .onFailure {
                        val failure = it.apiFailure(oidcCallbackErrorMessage(provider, null))
                        error = failure.message
                        refusalCode = failure.code
                    }
                loading = false
            }
        }
    }

    suspend fun refreshSession() {
        runCatching { repository.refreshUser() }
            .onSuccess { user = it }
            .onFailure { err ->
                when (err.sessionVerdict()) {
                    SessionVerdict.CHOOSE_NEW_PASSWORD -> {
                        val held = repository.holdForPasswordChange()
                        if (held != null) {
                            user = held
                        } else {
                            repository.logout(signOutContext)
                            user = null
                            error = err.apiErrorMessage("Sign in again to continue.")
                        }
                    }
                    SessionVerdict.REFUSED -> {
                        repository.logout(signOutContext)
                        user = null
                        val failure = err.apiFailure("Your access to this application has ended.")
                        error = failure.message
                        refusalCode = failure.code
                    }
                    SessionVerdict.EXPIRED -> {
                        repository.logout(signOutContext)
                        user = null
                        doorPassword = ""
                        error = SESSION_ENDED_SENTENCE
                    }
                    SessionVerdict.KEEP -> if (user == null) {
                        error = err.message ?: "Couldn't connect. Check your connection and try again."
                    }
                }
            }
    }
    LaunchedEffect(Unit) {
        if (repository.hasToken()) refreshSession()
        loading = false
    }
    LaunchedEffect(Unit) {
        PasswordChangeSignal.raises.collect { raised ->
            val current = user
            if (raised > 0L && current != null && !mustChangePasswordBlocks(current)) refreshSession()
        }
    }
    LaunchedEffect(Unit) {
        SessionEndedSignal.raises.collect { raised ->
            val current = user
            if (raised > 0L && current != null && !mustChangePasswordBlocks(current)) refreshSession()
        }
    }
    val linkContext = LocalContext.current.applicationContext
    LaunchedEffect(incomingPasswordLink) {
        val link = incomingPasswordLink ?: return@LaunchedEffect
        if (user == null) {
            pastedLink = link
            forgotPassword = false
            redeemingLink = true
        } else {
            Toast.makeText(linkContext, "Sign out first to use a set-password link.", Toast.LENGTH_LONG).show()
        }
        onIncomingPasswordLinkConsumed()
    }

    val latestPreferences by rememberUpdatedState(preferences)
    val preferencesContext = LocalContext.current.applicationContext
    val questionnaireV2Context = LocalContext.current.applicationContext
    LaunchedEffect(user?.id) {
        if (user != null) runCatching { QuestionnaireV2.get(questionnaireV2Context).refreshConfig() }
    }
    LaunchedEffect(user?.id) {
        if (user == null) return@LaunchedEffect
        onPreferencesChanged(syncAppPreferences(repository, latestPreferences, AppPreferencesStore(preferencesContext)))
    }

    val appContext = LocalContext.current.applicationContext
    var pendingUploads by remember { mutableStateOf(0) }
    var online by remember { mutableStateOf(repository.isOnline(appContext)) }
    val serverUnreachable by com.fieldrepository.app.data.ServerReachability.unreachable.collectAsState()
    val islandClearance = remember { mutableStateOf(0.dp) }
    var bannerStackHeight by remember { mutableStateOf(0.dp) }
    val rootImeOpen by com.fieldrepository.app.ui.rememberIslandImeVisible()
    val bannersShown = user != null && !rootImeOpen && (pendingUploads > 0 || !online || serverUnreachable)
    val bannerReserve = remember { mutableStateOf(0.dp) }
    bannerReserve.value = bannerClearance(bannerStackHeight, bannersShown)
    val rootDensity = LocalDensity.current
    LaunchedEffect(user) {
        if (user == null || mustChangePasswordBlocks(user)) return@LaunchedEffect
        OutboxSyncWorker.schedule(appContext)
        runCatching { DraftStore.sweep(appContext) }
        NotificationSyncWorker.schedule(appContext)
        while (true) {
            runCatching { repository.syncOutbox(appContext) }
            pendingUploads = runCatching { repository.pendingUploads(appContext) }.getOrDefault(0)
            runCatching { repository.syncNotifications(appContext) }
            delay(if (pendingUploads > 0) 12_000L else 45_000L)
        }
    }
    DisposableEffect(user) {
        if (user == null) return@DisposableEffect onDispose {}
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                scope.launch {
                    online = repository.isOnline(appContext)
                    val preferencesStore = AppPreferencesStore(appContext)
                    if (preferencesStore.pendingSince() != null) {
                        runCatching { onPreferencesChanged(syncAppPreferences(repository, latestPreferences, preferencesStore)) }
                    }
                    runCatching { repository.syncOutbox(appContext) }
                    pendingUploads = runCatching { repository.pendingUploads(appContext) }.getOrDefault(0)
                }
            }

            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                val usable = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                scope.launch { online = usable }
            }

            override fun onLost(network: android.net.Network) {
                scope.launch { online = false }
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        when {
            loading -> Text("Loading…", color = Muted, modifier = Modifier.align(Alignment.Center))
            user == null && challenge != null -> TwoStepChallengeForm(
                repository = repository,
                challengeToken = challenge!!,
                onSignedIn = {
                    challenge = null
                    user = it
                    if (!mustChangePasswordBlocks(it)) doorPassword = ""
                },
                onCancel = { challenge = null; doorPassword = "" }
            )
            user == null && redeemingLink -> SetPasswordLinkScreen(
                repository = repository,
                initialLink = pastedLink,
                onDone = {
                    redeemingLink = false
                    pastedLink = ""
                    error = null
                    refusalCode = null
                },
                onCancel = {
                    redeemingLink = false
                    pastedLink = ""
                }
            )
            user == null && forgotPassword -> ForgotPasswordScreen(
                repository = repository,
                onBack = { forgotPassword = false }
            )
            user == null -> LoginScreen(
                onForgotPassword = {
                    error = null
                    refusalCode = null
                    forgotPassword = true
                },
                onOpenSetPasswordLink = {
                    error = null
                    refusalCode = null
                    redeemingLink = true
                },
                error = error,
                refusalCode = refusalCode,
                busy = loading,
                oidcProviders = oidcAuthClient.providers,
                onOidcLogin = { provider ->
                    loading = true
                    error = null
                    refusalCode = null
                    runCatching { oidcAuthClient.start(provider) }
                        .onSuccess { started ->
                            pendingOidc = "${provider.id.name}|${started.rawNonce}"
                            oidcLauncher.launch(started.intent)
                        }
                        .onFailure {
                            loading = false
                            error = oidcCallbackErrorMessage(provider.id, null)
                        }
                },
                onLogin = { email, password ->
                    scope.launch {
                        loading = true
                        error = null
                        refusalCode = null
                        runCatching { repository.login(email, password) }
                            .onSuccess {
                                doorPassword = password
                                take(it)
                                if (it is SignInOutcome.SignedIn && !mustChangePasswordBlocks(it.user)) doorPassword = ""
                            }
                            .onFailure {
                                val failure = it.apiFailure("Login failed")
                                error = failure.message
                                refusalCode = failure.code
                            }
                        loading = false
                    }
                },
                onGoogleLogin = {
                    scope.launch {
                        loading = true
                        error = null
                        refusalCode = null
                        runCatching {
                            val idToken = googleAuthClient.getIdToken()
                            repository.loginWithGoogle(idToken)
                        }
                            .onSuccess { take(it) }
                            .onFailure {
                                val failure = it.apiFailure("Google sign-in failed")
                                error = failure.message
                                refusalCode = failure.code
                            }
                        loading = false
                    }
                }
            )
            mustChangePasswordBlocks(user) -> CompositionLocalProvider(
                LocalBannerClearance provides bannerReserve
            ) { PasswordGateScreen(
                repository = repository,
                user = user!!,
                doorPassword = doorPassword,
                onSatisfied = {
                    user = it
                    doorPassword = ""
                },
                onSignOut = {
                    scope.launch {
                        runCatching { googleAuthClient.clear() }
                        repository.logout(signOutContext)
                        doorPassword = ""
                        user = null
                    }
                },
                onSessionEnded = { message ->
                    scope.launch {
                        runCatching { googleAuthClient.clear() }
                        repository.logout(signOutContext)
                        doorPassword = ""
                        refusalCode = null
                        error = message
                        user = null
                    }
                }
            ) }
            else -> CompositionLocalProvider(
                LocalIslandClearance provides islandClearance,
                LocalBannerClearance provides bannerReserve
            ) { HomeScreen(
                repository = repository,
                user = user!!,
                preferences = preferences,
                onPreferencesChanged = onPreferencesChanged,
                onLogout = {
                    scope.launch {
                        val waiting = runCatching { repository.pendingUploads(appContext) }.getOrDefault(0)
                        runCatching { googleAuthClient.clear() }
                        repository.logout(signOutContext)
                        user = null
                        if (waiting > 0) {
                            Toast.makeText(
                                appContext,
                                "$waiting entr${if (waiting == 1) "y" else "ies"} waiting to upload. They'll send when you sign in.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            ) }
        }
        if (bannersShown) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = BANNER_STACK_GAP + islandClearance.value)
                    .onSizeChanged { bannerStackHeight = with(rootDensity) { it.height.toDp() } },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!online) {
                    OfflineNotice(modifier = Modifier.fillMaxWidth())
                } else if (serverUnreachable) {
                    OfflineNotice(
                        modifier = Modifier.fillMaxWidth(),
                        text = com.fieldrepository.app.data.ServerReachability.SERVER_UNREACHABLE_SENTENCE
                    )
                }
                if (pendingUploads > 0) {
                    OutboxPendingBanner(
                        pendingUploads = pendingUploads,
                        modifier = Modifier.fillMaxWidth(),
                        entries = { repository.pendingEntries(appContext) },
                        onRetry = { entryId ->
                            repository.retryRefused(appContext, entryId)
                            runCatching { repository.syncOutbox(appContext) }
                            pendingUploads = runCatching { repository.pendingUploads(appContext) }.getOrDefault(0)
                        },
                        onFix = { entryId, body ->
                            val ok = repository.fixRefused(appContext, entryId, body)
                            if (ok) runCatching { repository.syncOutbox(appContext) }
                            pendingUploads = runCatching { repository.pendingUploads(appContext) }.getOrDefault(0)
                            ok
                        },
                        onUseExisting = { entryId ->
                            val ok = repository.useExistingForRefused(appContext, entryId)
                            if (ok) runCatching { repository.syncOutbox(appContext) }
                            pendingUploads = runCatching { repository.pendingUploads(appContext) }.getOrDefault(0)
                            ok
                        },
                        onDiscard = { entryId ->
                            val ok = repository.discardRefused(appContext, entryId)
                            pendingUploads = runCatching { repository.pendingUploads(appContext) }.getOrDefault(0)
                            ok
                        },
                        validateAadhaar = { aadhaarValidationError(it) },
                        onRetypeAadhaar = { entryId, number ->
                            repository.retypeOutboxAadhaar(appContext, entryId, number)
                            runCatching { repository.syncOutbox(appContext) }
                            pendingUploads = runCatching { repository.pendingUploads(appContext) }.getOrDefault(0)
                        },
                        onAnswerDuplicate = { entryId, samePerson ->
                            repository.answerPossibleDuplicate(appContext, entryId, samePerson)
                            runCatching { repository.syncOutbox(appContext) }
                            pendingUploads = runCatching { repository.pendingUploads(appContext) }.getOrDefault(0)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(
    error: String?,
    refusalCode: String?,
    busy: Boolean,
    onLogin: (String, String) -> Unit,
    onGoogleLogin: () -> Unit,
    oidcProviders: List<OidcProvider> = emptyList(),
    onOidcLogin: (OidcProvider) -> Unit = {},
    onForgotPassword: () -> Unit = {},
    onOpenSetPasswordLink: () -> Unit = {}
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Field Repository",
            display = true,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "One shared archive for field documentation.",
            color = Body,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Canvas)) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (!error.isNullOrBlank()) {
                    if (refusalCode == "ACCESS_PENDING") {
                        Surface(
                            color = MaterialTheme.field.warningContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.HourglassEmpty,
                                        contentDescription = null,
                                        tint = MaterialTheme.field.warning,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        "Waiting for an administrator",
                                        color = MaterialTheme.field.warning,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Text(error, color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(
                    enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    onClick = { onLogin(email, password) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Signing in..." else "Login")
                }
                ActionRow {
                    TextButton(enabled = !busy, onClick = onForgotPassword) {
                        Text("Forgot password?", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(enabled = !busy, onClick = onOpenSetPasswordLink) {
                        Text("I have a link", style = MaterialTheme.typography.labelLarge)
                    }
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = onGoogleLogin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_google_g),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "Please wait..." else "Sign in with Google")
                    }
                }
                for (provider in oidcProviders) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { onOidcLogin(provider) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            OidcProviderMark(provider.id)
                            Spacer(Modifier.width(8.dp))
                            Text(if (busy) "Please wait..." else "Sign in with ${provider.label}")
                        }
                    }
                }
                Text(
                    "Researchers: use Google above. Email and password are for admin-issued accounts only.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    "Invitation only. Sign in once to request access.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    repository: FieldRepository,
    user: UserDto,
    preferences: AppPreferences,
    onPreferencesChanged: (AppPreferences) -> Unit,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<DashboardStats?>(null) }
    var statsError by remember { mutableStateOf<String?>(null) }
    var sections by remember { mutableStateOf<List<QuestionnaireSectionDto>>(emptyList()) }
    var crafts by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Dashboard) }
    val dataExplorer = key(user.id) { com.fieldrepository.app.explorer.ui.rememberDataExplorerState() }
    val dataHolder = rememberSaveableStateHolder()
    val notificationOpenRequest by NotificationState.openRequests.collectAsState()
    LaunchedEffect(notificationOpenRequest) {
        if (notificationOpenRequest > 0L) screen = Screen.Notifications
    }
    val downloadsOpenRequest by com.fieldrepository.app.explorer.data.ExplorerDownloadsTap.requests.collectAsState()
    LaunchedEffect(downloadsOpenRequest) {
        if (downloadsOpenRequest > com.fieldrepository.app.explorer.ui.ExplorerTapMemory.handled && screen !is Screen.DataBrowser) screen = Screen.DataBrowser
    }
    val notificationInbox by NotificationState.inbox.collectAsState()
    val unreadNotifications = notificationInbox?.takeIf { it.userId == user.id }?.let { NotificationInboxRules.unread(it) } ?: 0
    var scrollRestoreFor by remember { mutableStateOf<Screen?>(null) }
    val scrollMemory = remember { mutableMapOf<Screen, Int>() }
    val lazyScrollMemory = remember { mutableMapOf<Screen, LazyScrollMark>() }
    val screenMemories = remember { ScreenMemories<Screen>() }
    var nextStep by remember { mutableStateOf<NextStep?>(null) }
    var createNonce by remember { mutableIntStateOf(0) }
    var lookupState by remember { mutableStateOf(CarryScopeState.PENDING) }
    var craftSeed by remember { mutableStateOf<PageResponse<CraftDto>?>(null) }
    var artisanSeed by remember { mutableStateOf<PageResponse<ArtisanDto>?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val reducedMotion = preferences.reducedMotion
    var searchOpen by remember { mutableStateOf(false) }
    var scanOpen by remember { mutableStateOf(false) }
    val scanContext = LocalContext.current
    val appMessage = remember { AppMessageState() }
    val bannerClearanceState = LocalBannerClearance.current
    val bannerReserve = bannerClearanceState?.value ?: 0.dp
    var message by appMessage
    val isAdmin = user.isAdminUser()
    val isMasterAdmin = user.isMasterAdminUser()
    val isQuestionnaireManager = user.canManageTheQuestionnaire()
    val canReview = user.canAccessReview()
    val canViewProvenance = isAdmin || user.canViewProvenance
    val canDownloadDataset = user.canDownloadTheDataset()
    val canToggleAdminView = isAdmin || com.fieldrepository.app.ui.FieldPermissions.isContentAdmin(user)

    val adminViewContext = LocalContext.current.applicationContext
    var adminViewRequested by remember(user.id) {
        mutableStateOf(restoredAdminView(readAdminView(adminViewContext, user.id), isMasterAdmin))
    }
    fun requestAdminView(on: Boolean) {
        adminViewRequested = on
        writeAdminView(adminViewContext, user.id, on)
    }
    val adminView = canToggleAdminView && adminViewRequested

    val adminChrome = !isAdmin || adminView
    val canDelete = com.fieldrepository.app.ui.mayDelete(user)

    var pendingAccessRequests by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(isAdmin) {
        if (!isAdmin) {
            pendingAccessRequests = null
            return@LaunchedEffect
        }
        while (true) {
            runCatching { repository.accessRosterPendingCount() }
                .onSuccess { pendingAccessRequests = it }
            delay(60_000L)
        }
    }

    val unsavedGuard = remember { UnsavedGuard() }
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }

    val context = LocalContext.current
    var pendingUpdate by remember { mutableStateOf<AppReleaseDto?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var pushingUpdate by remember { mutableStateOf(false) }
    suspend fun checkForUpdate() {
        runCatching {
            val latest = repository.latestAppRelease()
            if (latest.versionCode > repository.installedVersionCode(context) && !latest.url.isNullOrBlank()) {
                pendingUpdate = latest
            }
        }
    }
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    LaunchedEffect(Unit) { if (lifecycleOwner == null) checkForUpdate() }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch { checkForUpdate() }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    var showWalkthrough by remember { mutableStateOf(!walkthroughSeen(context)) }
    fun finishWalkthrough() {
        showWalkthrough = false
        markWalkthroughSeen(context)
    }

    fun showMessage(text: String?, tone: AppMessageTone = AppMessageTone.ERROR, onRetry: (() -> Unit)? = null) {
        if (text.isNullOrBlank()) return
        val lower = text.lowercase()
        if ("left the composition" in lower || "was cancelled" in lower || "job was cancelled" in lower) return
        val bannerShowing = bannerDrawn(bannerClearanceState?.value ?: 0.dp)
        if (bannerAlreadySays(text, tone, bannerShowing)) return
        appMessage.show(text, tone, onRetry)
    }

    fun openRecordView(mode: EntryMode, recordId: String) {
        message = null
        screen = Screen.View(mode, recordId, back = screen)
    }

    fun openSearchRecord(recordType: String, recordId: String) {
        val mode = searchRecordEntryMode(recordType)
        if (mode == null) {
            showMessage(unroutableRecordLine(recordType))
        } else {
            openRecordView(mode, recordId)
        }
    }

    fun canCreate(mode: EntryMode): Boolean = when (mode) {
        EntryMode.CRAFT -> user.canManageTheCrafts()
        EntryMode.WORKSHOP -> user.canManageTheWorkshops()
        EntryMode.USERS -> user.canManageUsers() && adminChrome
        EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.TOOL -> user.canCreateRecords()
        EntryMode.DATA_BROWSER -> canDownloadDataset
        EntryMode.QUESTIONNAIRE, EntryMode.MEDIA, EntryMode.VIEW_DATA, EntryMode.TASKS,
        EntryMode.SHARING, EntryMode.WORKSHOP_ACCESS, EntryMode.SEARCH, EntryMode.MAP,
        EntryMode.CONSOLIDATED_QUESTIONNAIRE -> true
    }

    val dashboardModes = remember(user.role, user.canManageQuestionnaire) {
        EntryMode.entries.filter { it != EntryMode.USERS || user.canManageUsers() }
    }

    fun screenFor(mode: EntryMode): Screen =
        if (mode == EntryMode.DATA_BROWSER) Screen.DataBrowser else Screen.Create(mode)

    val questionnaireV2Request by QuestionnaireV2Nav.pending.collectAsState()
    LaunchedEffect(questionnaireV2Request) {
        if (questionnaireV2Request != null) screen = screenFor(EntryMode.QUESTIONNAIRE)
    }
    val receivedFormBundle by com.fieldrepository.app.forms.FormBundleInbox.pending.collectAsState()
    LaunchedEffect(receivedFormBundle) {
        if (receivedFormBundle != null) screen = screenFor(EntryMode.QUESTIONNAIRE)
    }
    val questionnaireV2Session by QuestionnaireV2Nav.openServerSession.collectAsState()
    LaunchedEffect(questionnaireV2Session) {
        if (questionnaireV2Session != null) screen = screenFor(EntryMode.QUESTIONNAIRE)
    }
    val recordingsRequest by QuestionnaireV2Nav.showRecordings.collectAsState()
    LaunchedEffect(recordingsRequest) {
        if (recordingsRequest != null) screen = screenFor(EntryMode.QUESTIONNAIRE)
    }

    fun refresh() {
        scope.launch {
            runCatching { repository.stats() }
                .onSuccess { stats = it; statsError = null }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    statsError = it.apiErrorMessage("Couldn't load the totals.")
                }
        }
    }

    val craftListContext = LocalContext.current.applicationContext

    suspend fun loadLookups() {
        val gotCrafts = runCatching { repository.craftLookup(craftListContext) }
            .onSuccess {
                crafts = it.items
                craftSeed = it
            }
            .isSuccess
        val gotArtisans = runCatching { repository.artisansPage() }
            .onSuccess {
                artisans = it.items
                artisanSeed = it
            }
            .isSuccess
        lookupState = if (gotCrafts && gotArtisans) carryScopeStateFor(craftSeed, artisanSeed) else CarryScopeState.UNAVAILABLE
    }

    fun refreshLookups() {
        scope.launch { loadLookups() }
    }

    LaunchedEffect(Unit) {
        refresh()
        loadLookups()
        runCatching { repository.questionnaireSections() }
            .onSuccess { sections = it }
            .onFailure { showMessage(it.apiErrorMessage("Couldn't load the questionnaire sections.")) }
    }

    fun attemptExit(leave: () -> Unit) {
        if (unsavedGuard.dirty && unsavedGuard.onSave != null) {
            pendingExit = leave
        } else {
            leave()
        }
    }

    val viewLink by com.fieldrepository.app.explorer.ui.ExplorerViewLinks.requests.collectAsState()
    var linkAsked by remember { mutableStateOf<com.fieldrepository.app.explorer.ui.ExplorerLinkRequest?>(null) }
    LaunchedEffect(viewLink, pendingExit) {
        val link = viewLink ?: return@LaunchedEffect
        if (screen is Screen.DataBrowser) return@LaunchedEffect
        when {
            linkAsked !== link -> { linkAsked = link; attemptExit { message = null; screen = Screen.DataBrowser } }
            pendingExit == null -> com.fieldrepository.app.explorer.ui.ExplorerViewLinks.consume(link)
        }
    }

    fun goDashboard() {
        message = null
        screen = Screen.Dashboard
    }

    fun afterCreate(mode: EntryMode, name: String?, prefill: Prefill, another: Boolean) {
        refresh()
        if (another) {
            screen = Screen.Create(mode, prefill.forAnother(mode), fresh = ++createNonce)
            message = "${mode.label} saved"
        } else {
            nextStep = NextStep(mode, name, prefill)
            goDashboard()
            message = "${mode.label} saved"
        }
    }

    fun openDestination(destination: NavDestination) {
        message = null
        when (destination) {
            NavDestination.DASHBOARD -> goDashboard()
            NavDestination.WALKTHROUGH -> showWalkthrough = true
            NavDestination.RECORD_ARTISAN -> screen = screenFor(EntryMode.ARTISAN)
            NavDestination.RECORD_PRODUCT -> screen = screenFor(EntryMode.PRODUCT)
            NavDestination.DOCUMENT_PROCESS -> screen = screenFor(EntryMode.PROCESS)
            NavDestination.RECORD_TOOL -> screen = screenFor(EntryMode.TOOL)
            NavDestination.TAKE_INTERVIEW -> screen = screenFor(EntryMode.QUESTIONNAIRE)
            NavDestination.UPLOAD_MEDIA -> screen = screenFor(EntryMode.MEDIA)
            NavDestination.ADD_CRAFT -> screen = screenFor(EntryMode.CRAFT)
            NavDestination.RECORD_WORKSHOP -> screen = screenFor(EntryMode.WORKSHOP)
            NavDestination.MY_ACTIVITY -> screen = Screen.MyActivity
            NavDestination.TASKS -> screen = screenFor(EntryMode.TASKS)
            NavDestination.BROWSE_RECORDS -> screen = screenFor(EntryMode.SEARCH)
            NavDestination.SCAN_CODE -> scanOpen = true
            NavDestination.MAP -> screen = screenFor(EntryMode.MAP)
            NavDestination.VIEW_DATA -> screen = screenFor(EntryMode.DATA_BROWSER)
            NavDestination.CONSOLIDATED_QUESTIONNAIRE -> screen = screenFor(EntryMode.CONSOLIDATED_QUESTIONNAIRE)
            NavDestination.SHARE_DATA_ACCESS -> screen = screenFor(EntryMode.SHARING)
            NavDestination.ASSIGN_TOOLS -> screen = Screen.ToolAssign
            NavDestination.REVIEW -> screen = screenFor(EntryMode.VIEW_DATA)
            NavDestination.SETTINGS_HUB -> screen = Screen.AdminHub()
            NavDestination.ACCESS_ROSTER -> screen = Screen.AdminHub(AdminHubEntry.ACCESS_ROSTER)
            NavDestination.MANAGE_USERS -> screen = screenFor(EntryMode.USERS)
            NavDestination.SETTINGS -> screen = Screen.Appearance
            NavDestination.GIVE_FEEDBACK -> screen = Screen.Feedback
            NavDestination.NOTIFICATIONS -> screen = Screen.Notifications
        }
    }

    fun navigate(destination: NavDestination) {
        scope.launch { drawerState.closeAppMenu(reducedMotion) }
        if (destination == NavDestination.WALKTHROUGH) {
            openDestination(destination)
        } else {
            attemptExit { openDestination(destination) }
        }
    }

    fun goBack() {
        message = null
        val leaving = screen
        val target = when (val s = screen) {
            is Screen.View -> s.back
            is Screen.Edit -> s.back ?: Screen.Browse(s.mode)
            is Screen.Browse -> Screen.Dashboard
            is Screen.Create -> Screen.Dashboard
            is Screen.MyActivity -> Screen.Dashboard
            is Screen.ToolAssign -> Screen.Dashboard
            is Screen.Feedback -> Screen.Dashboard
            is Screen.Notifications -> Screen.Dashboard
            is Screen.Settings -> Screen.Dashboard
            is Screen.Appearance -> Screen.Dashboard
            is Screen.MyAiKeys -> Screen.Appearance
            is Screen.TwoStepVerification -> Screen.Appearance
            is Screen.DataBrowser -> Screen.Dashboard
            is Screen.AdminHub -> if (s.section != null) Screen.AdminHub() else Screen.Dashboard
            is Screen.RemoteRecord -> s.from ?: Screen.Dashboard
            is Screen.Dashboard -> Screen.Dashboard
        }
        scrollRestoreFor = if (leaving is Screen.View || leaving is Screen.Edit || leaving is Screen.RemoteRecord) target else null
        screen = target
    }

    val headerTitle: String? = when (val s = screen) {
        is Screen.Dashboard -> null
        is Screen.Create -> s.mode.actionTitle
        is Screen.Browse -> "Update ${s.mode.label.lowercase()}"
        is Screen.View -> recordViewTitle(s.mode.label)
        is Screen.Edit -> if (s.mode.editable) recordUpdateTitle(s.mode.label) else s.mode.label
        is Screen.MyActivity -> "My Activity"
        is Screen.ToolAssign -> "Assign tools to artisans"
        is Screen.Feedback -> "App feedback"
        is Screen.Notifications -> "Notifications"
        is Screen.Settings -> "Settings"
        is Screen.Appearance -> "Appearance & accessibility"
        is Screen.MyAiKeys -> null
        is Screen.TwoStepVerification -> null
        is Screen.DataBrowser -> "Data"
        is Screen.RemoteRecord -> null
        is Screen.AdminHub -> if (s.section == AdminHubEntry.REVIEWS && !(isAdmin && adminChrome)) {
            AdminHubEntry.REVIEWS.label
        } else {
            "Admin tools"
        }
    }

    val currentDestination: NavDestination? = when (val s = screen) {
        is Screen.Dashboard -> NavDestination.DASHBOARD
        is Screen.MyActivity -> NavDestination.MY_ACTIVITY
        is Screen.ToolAssign -> NavDestination.ASSIGN_TOOLS
        is Screen.Feedback -> NavDestination.GIVE_FEEDBACK
        is Screen.Notifications -> NavDestination.NOTIFICATIONS
        is Screen.Appearance -> NavDestination.SETTINGS
        is Screen.MyAiKeys -> NavDestination.SETTINGS
        is Screen.TwoStepVerification -> NavDestination.SETTINGS
        is Screen.DataBrowser -> NavDestination.VIEW_DATA
        is Screen.AdminHub -> if (s.section == AdminHubEntry.ACCESS_ROSTER) {
            NavDestination.ACCESS_ROSTER
        } else {
            NavDestination.SETTINGS_HUB
        }
        is Screen.Settings -> null
        is Screen.RemoteRecord -> null
        is Screen.Browse, is Screen.Edit, is Screen.View -> null
        is Screen.Create -> when (s.mode) {
            EntryMode.ARTISAN -> NavDestination.RECORD_ARTISAN
            EntryMode.PRODUCT -> NavDestination.RECORD_PRODUCT
            EntryMode.PROCESS -> NavDestination.DOCUMENT_PROCESS
            EntryMode.TOOL -> NavDestination.RECORD_TOOL
            EntryMode.QUESTIONNAIRE -> NavDestination.TAKE_INTERVIEW
            EntryMode.MEDIA -> NavDestination.UPLOAD_MEDIA
            EntryMode.CRAFT -> NavDestination.ADD_CRAFT
            EntryMode.WORKSHOP -> NavDestination.RECORD_WORKSHOP
            EntryMode.TASKS -> NavDestination.TASKS
            EntryMode.SEARCH -> NavDestination.BROWSE_RECORDS
            EntryMode.MAP -> NavDestination.MAP
            EntryMode.CONSOLIDATED_QUESTIONNAIRE -> NavDestination.CONSOLIDATED_QUESTIONNAIRE
            EntryMode.SHARING -> NavDestination.SHARE_DATA_ACCESS
            EntryMode.VIEW_DATA -> NavDestination.REVIEW
            EntryMode.USERS -> NavDestination.MANAGE_USERS
            EntryMode.DATA_BROWSER -> NavDestination.VIEW_DATA
            EntryMode.WORKSHOP_ACCESS -> null
        }
    }

    fun searchLocalItems(): List<AppSearchItem> =
        appSearchScreenItems(visibleNavItems(user, adminView), ::navigate) +
            appSearchSettingItems { target ->
                attemptExit {
                    message = null
                    screen = when (target) {
                        AppSearchSettingTarget.APPEARANCE -> Screen.Appearance
                        AppSearchSettingTarget.AI_KEYS -> Screen.MyAiKeys
                        AppSearchSettingTarget.TWO_STEP -> Screen.TwoStepVerification
                    }
                }
            } +
            appSearchCachedItems(artisans, crafts) { type, id -> attemptExit { openSearchRecord(type, id) } }

    suspend fun searchRemote(query: String): List<AppSearchItem> =
        appSearchRecordingItems(context, query) { filters -> attemptExit { QuestionnaireV2Nav.showRecordings.value = filters } } +
        appSearchRemoteItems(
            repository = repository,
            query = query,
            openRecord = { type, id -> attemptExit { openSearchRecord(type, id) } },
            openRemote = { type, id ->
                attemptExit { message = null; screen = Screen.RemoteRecord(type, id, from = screen) }
            }
        )

    BackHandler(enabled = drawerState.appMenuShowing) {
        scope.launch { drawerState.closeAppMenu(reducedMotion) }
    }
    BackHandler(enabled = !drawerState.appMenuShowing && screen !is Screen.Dashboard) {
        attemptExit { goBack() }
    }
    AppMenuFocusEffect(drawerState)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            modifier = Modifier.appMenuBleed(16.dp),
            gesturesEnabled = drawerState.appMenuShowing,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    AppNavigationDrawerContent(
                        user = user,
                        adminMode = adminView,
                        currentDestination = currentDestination,
                        badges = buildMap {
                            pendingAccessRequests?.takeIf { it > 0 }?.let { put(NavDestination.ACCESS_ROSTER, it) }
                            unreadNotifications.takeIf { it > 0 }?.let { put(NavDestination.NOTIFICATIONS, it) }
                        },
                        onNavigate = ::navigate,
                        pushingUpdate = pushingUpdate,
                        onToggleAdminView = { requestAdminView(!adminView) },
                        onPushUpdate = {
                            scope.launch { drawerState.closeAppMenu(reducedMotion) }
                            if (!pushingUpdate) {
                                pushingUpdate = true
                                Toast.makeText(context, "Publishing this version as the update for everyone…", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    runCatching { repository.publishAppUpdate(context) }
                                        .onSuccess { rel ->
                                            Toast.makeText(
                                                context,
                                                "Update v${rel.versionName} published.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        .onFailure {
                                            Toast.makeText(context, it.apiErrorMessage("Unable to publish the update"), Toast.LENGTH_LONG).show()
                                        }
                                    pushingUpdate = false
                                }
                            }
                        },
                        onLogout = { scope.launch { drawerState.closeAppMenu(reducedMotion) }; onLogout() }
                    )
                }
            }
        ) {
            val richMediaUrl = remember(repository) { richMediaUrlResolver(repository) }
            val screenMemory = key(screen) {
                val here = screen
                remember { screenMemories.arrive(here, restoring = scrollRestoreFor == here) }
            }
            CompositionLocalProvider(
                LocalScreenMemory provides screenMemory,
                LocalLayoutDirection provides LayoutDirection.Ltr,
                LocalUnsavedGuard provides unsavedGuard,
                com.fieldrepository.app.ui.LocalContentAdminChrome provides com.fieldrepository.app.ui.contentAdminChrome(user, adminView),
                com.fieldrepository.app.ui.LocalMayDelete provides canDelete,
                com.fieldrepository.app.ui.LocalAudioExpand provides canDownloadDataset,
                com.fieldrepository.app.ui.richtext.LocalRichMediaUrl provides richMediaUrl
            ) {
            val ownsViewport = screen is Screen.DataBrowser || screen is Screen.Appearance ||
                screen is Screen.TwoStepVerification || screen is Screen.MyAiKeys
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .padding(bottom = if (ownsViewport) bannerReserve else 0.dp)
                    .appMenuHidesPage(drawerState.appMenuShowing)
            ) {
                when (screen) {
                    is Screen.DataBrowser -> dataHolder.SaveableStateProvider("data:${user.id}") { DataBrowserScreen(
                        repository = repository,
                        state = dataExplorer,
                        onBack = { attemptExit { goBack() } },
                        onMessage = { showMessage(it, AppMessageTone.SUCCESS) },
                        onError = { showMessage(it, AppMessageTone.ERROR) },
                        onOpenMedia = { mediaId -> openRecordView(EntryMode.MEDIA, mediaId) },
                        onOpenRemote = { type, id -> screen = Screen.RemoteRecord(type, id, from = Screen.DataBrowser) },
                        onOpenRecord = { kind, id ->
                            val mode = searchRecordEntryMode(kind) ?: recentSubmissionEntryMode(kind)
                            if (mode == null) showMessage(unroutableRecordLine(kind)) else screen = Screen.View(mode, id, back = Screen.DataBrowser)
                        },
                        onOpenSection = { artisanId, sectionKey ->
                            screen = Screen.View(EntryMode.ARTISAN, artisanId, back = Screen.DataBrowser, section = sectionKey)
                        },
                        onRecordNow = { path, name ->
                            val artisanId = dataFolderArtisan(path)
                            screen = Screen.Create(EntryMode.MEDIA, artisanId?.let { Prefill(artisanId = it, artisanName = name) }, fresh = ++createNonce)
                        }
                    ) }

                    is Screen.Appearance -> AppearanceScreen(
                        repository = repository,
                        current = preferences,
                        onChanged = onPreferencesChanged,
                        onBack = { attemptExit { goBack() } },
                        onOpenMyAiKeys = { screen = Screen.MyAiKeys },
                        onOpenTwoStepVerification = { screen = Screen.TwoStepVerification },
                        onSignOut = onLogout
                    )

                    is Screen.TwoStepVerification -> TwoStepVerificationScreen(
                        repository = repository,
                        email = user.email,
                        onBack = { attemptExit { goBack() } }
                    )

                    is Screen.MyAiKeys -> MyAiKeysScreen(
                        repository = repository,
                        onBack = { attemptExit { goBack() } }
                    )

                    else -> {
                val pageScroll = key(screen) { rememberScrollState() }
                val lazyPage = screen is Screen.MyActivity || screen is Screen.Browse ||
                    (screen as? Screen.Create)?.mode == EntryMode.CONSOLIDATED_QUESTIONNAIRE
                val pageList = key(screen) { rememberLazyListState() }
                val shownScreen = screen
                DisposableEffect(shownScreen) {
                    onDispose {
                        if (lazyPage) {
                            lazyScrollMemory[shownScreen] = LazyScrollMark(pageList.firstVisibleItemIndex, pageList.firstVisibleItemScrollOffset)
                        } else {
                            scrollMemory[shownScreen] = pageScroll.value
                        }
                    }
                }
                LaunchedEffect(shownScreen) {
                    val restoring = scrollRestoreFor == shownScreen
                    scrollRestoreFor = null
                    if (!restoring) return@LaunchedEffect
                    if (lazyPage) {
                        val mark = lazyScrollMemory[shownScreen] ?: return@LaunchedEffect
                        if (mark.index == 0 && mark.offset == 0) return@LaunchedEffect
                        withTimeoutOrNull(4_000L) {
                            snapshotFlow { pageList.layoutInfo.totalItemsCount }.first { lazyRestoreReady(mark, it) }
                        }
                        pageList.scrollToItem(mark.index, mark.offset)
                        return@LaunchedEffect
                    }
                    val target = scrollMemory[shownScreen]
                    if (target == null || target <= 0) return@LaunchedEffect
                    withTimeoutOrNull(4_000L) { snapshotFlow { pageScroll.maxValue }.first { it >= target } }
                    pageScroll.scrollTo(target.coerceAtMost(pageScroll.maxValue))
                }
                IslandScaffold(island = { islandModifier ->
        val navItems = visibleNavItems(user, adminView)
        FieldIslandNav(
            modifier = islandModifier,
            onOpenDrawer = { scope.launch { drawerState.openAppMenu(reducedMotion) } },
            visible = !searchOpen,
            currentLabel = navItems.firstOrNull { it.destination == currentDestination }?.label,
            roots = navItems.filter { it.group == null }
                .map { entry -> IslandEntry(entry.label, entry.icon) { navigate(entry.destination) } },
            groups = NavGroup.entries.map { group ->
                val entries = navItems.filter { it.group == group }
                    .map { entry -> IslandEntry(entry.label, entry.icon) { navigate(entry.destination) } }
                IslandGroup(
                    group.label,
                    if (group == NavGroup.ACCOUNT) {
                        entries + IslandEntry("Open the web portal", Icons.Filled.OpenInNew) { openWebPortal(context) }
                    } else {
                        entries
                    }
                )
            }
        )
                }) { islandReserve ->
        val quickReturn = if (lazyPage) {
            rememberQuickReturnHeader(pageList, pinned = searchOpen)
        } else {
            rememberQuickReturnHeader(pageScroll, pinned = searchOpen)
        }
        val pageImeOpen by rememberIslandImeVisible()
        val clearance = LocalIslandClearance.current
        val pageReserve = pageBottomReserve(islandReserve, bannerReserve)
        SideEffect { clearance?.value = islandReserve }
        DisposableEffect(clearance) { onDispose { clearance?.value = 0.dp } }
        val showBack = screen !is Screen.Dashboard
        val pageHeader: @Composable () -> Unit = {
        if (showBack || headerTitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (showBack) {
                    IconButton(onClick = { attemptExit { goBack() } }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                headerTitle?.let { title ->
                    Text(
                        title,
                        display = true,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
        }
        val pageTop = with(LocalDensity.current) { quickReturn.reservePx.toDp() }
        Box(Modifier.fillMaxSize()) {
        if (lazyPage) {
            val chrome = LazyPageChrome(
                state = pageList,
                connection = quickReturn.connection,
                topPadding = pageTop,
                bottomPadding = pageReserve,
                header = pageHeader
            )
            when (val s = screen) {
                is Screen.Browse -> RecordPickerScreen(
                    repository = repository,
                    mode = s.mode,
                    onPick = { recordId -> screen = Screen.Edit(s.mode, recordId) },
                    onView = { recordId -> openRecordView(s.mode, recordId) },
                    onError = { showMessage(it) },
                    chrome = chrome
                )
                is Screen.MyActivity -> MyActivityScreen(
                    repository = repository,
                    userId = user.id,
                    onOpen = { mode, recordId -> openRecordView(mode, recordId) },
                    onError = { showMessage(it) },
                    chrome = chrome
                )
                else -> ConsolidatedQuestionnaireScreen(
                    repository = repository,
                    onError = { showMessage(it) },
                    chrome = chrome
                )
            }
        } else Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(quickReturn.connection)
                .verticalScroll(pageScroll)
                .padding(top = pageTop)
                .padding(bottom = pageReserve),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

        pageHeader()

        when (val s = screen) {
            is Screen.Dashboard -> {
                nextStep?.let { step ->
                    NextStepPanel(
                        repository = repository,
                        step = step,
                        canCreate = { canCreate(it) },
                        onSelect = { mode ->
                            val prefill = if (mode == step.saved) step.prefill.forAnother(mode) else step.prefill
                            screen = Screen.Create(mode, prefill, fresh = ++createNonce)
                            nextStep = null
                        },
                        onDismiss = { nextStep = null }
                    )
                }
                DashboardScreen(
                    stats = stats,
                    statsError = statsError,
                    onRetryStats = { refresh() },
                    recentArtisans = artisans,
                    actions = dashboardModes.filter { canCreate(it) && it.onDashboard },
                    roleLabel = roleLabel(user.role),
                    canCreateRecords = user.canCreateRecords(),
                    showAdminHub = isAdmin && adminChrome,
                    onOpenAdminHub = { message = null; screen = Screen.AdminHub() },
                    onWalkthrough = { message = null; showWalkthrough = true },
                    onNew = { selected -> message = null; screen = Screen.Create(selected) },
                    onUpdateExisting = { selected -> message = null; screen = Screen.Browse(selected) },
                    onOpenSearchFor = { recordType ->
                        message = null
                        screen = Screen.Create(EntryMode.SEARCH, searchFocus = recordType)
                    },
                    onOpenReviews = if (canReview) {
                        ({ message = null; screen = Screen.AdminHub(AdminHubEntry.REVIEWS) })
                    } else null,
                    onOpenMyActivity = { message = null; screen = Screen.MyActivity },
                    onOpenRecord = { mode, recordId -> openRecordView(mode, recordId) },
                    onOpenArtisan = { artisanId -> openRecordView(EntryMode.ARTISAN, artisanId) },
                    sentBackSlot = {
                        val mine = stats?.mine
                        if (mine != null) {
                            SentBackCard(
                                repository = repository,
                                sentBack = mine.sentBack,
                                rejected = mine.rejected,
                                onOpen = { type, id ->
                                    recentSubmissionEntryMode(type)?.let { mode -> message = null; openRecordView(mode, id) }
                                }
                            )
                        }
                    }
                )
            }

            is Screen.Browse, is Screen.MyActivity -> Unit

            is Screen.View -> RecordViewScreen(
                repository = repository,
                mode = s.mode,
                recordId = s.recordId,
                section = s.section,
                canUpdate = s.mode.editable && recordUpdateAllowed(
                    s.mode.linkedRecordType(),
                    canManageCrafts = user.canManageTheCrafts(),
                    canManageWorkshops = user.canManageTheWorkshops()
                ),
                onUpdate = { focusKey -> message = null; screen = Screen.Edit(s.mode, s.recordId, back = s, focus = focusKey) },
                canOverrideCompletion = isAdmin && adminChrome,
                showProvenance = canViewProvenance,
                mayReview = canReview,
                canDelete = canDelete,
                onDeleted = {
                    message = "${deleteNoun(s.mode).replaceFirstChar { it.uppercase() }} deleted"
                    refresh()
                    screen = s.back
                },
                onMessage = { showMessage(it) },
                onError = { showMessage(it) }
            )

            is Screen.Create -> key(s) { when (s.mode) {
                EntryMode.CRAFT -> CraftForm(
                    repository = repository,
                    onCreated = { prefill, name, another -> refreshLookups(); afterCreate(EntryMode.CRAFT, name, prefill, another) },
                    onDone = { message = "Craft saved"; refresh(); refreshLookups(); goDashboard() },
                    onError = { showMessage(it) }
                )
                EntryMode.ARTISAN -> ArtisanForm(
                    repository = repository,
                    crafts = crafts,
                    lookupState = lookupState,
                    craftSeed = craftSeed,
                    prefill = s.prefill,
                    adminView = adminView,
                    onOpenExisting = { id -> message = null; openRecordView(EntryMode.ARTISAN, id) },
                    onArtisanCreated = { prefill, another ->
                        refreshLookups()
                        afterCreate(EntryMode.ARTISAN, prefill.artisanName, prefill, another)
                    },
                    onDone = { message = "Artisan saved"; refresh(); refreshLookups(); goDashboard() },
                    onError = { showMessage(it) }
                )
                EntryMode.WORKSHOP -> WorkshopForm(
                    repository = repository,
                    artisans = artisans,
                    artisanSeed = artisanSeed,
                    prefill = s.prefill,
                    adminView = adminView,
                    onDone = { message = "Workshop saved"; refresh(); goDashboard() },
                    onError = { showMessage(it) }
                )
                EntryMode.PRODUCT -> ProductForm(
                    repository = repository,
                    crafts = crafts,
                    artisans = artisans,
                    lookupState = lookupState,
                    craftSeed = craftSeed,
                    artisanSeed = artisanSeed,
                    prefill = s.prefill,
                    adminView = adminView,
                    onCreated = { prefill, name, another -> afterCreate(EntryMode.PRODUCT, name, prefill, another) },
                    onDone = { message = "Product saved"; refresh(); goDashboard() },
                    onError = { showMessage(it) }
                )
                EntryMode.PROCESS -> ProcessForm(
                    repository = repository,
                    adminView = adminView,
                    prefill = s.prefill,
                    onCreated = { prefill, name, another -> afterCreate(EntryMode.PROCESS, name, prefill, another) },
                    onDone = { message = "Process saved"; refresh(); goDashboard() },
                    onError = { showMessage(it) }
                )
                EntryMode.VIEW_DATA -> ViewDataScreen(
                    repository = repository,
                    canReview = canReview,
                    masterAdmin = isMasterAdmin,
                    isAdmin = isAdmin,
                    adminChrome = adminChrome,
                    showProvenance = canViewProvenance,
                    canDownloadDataset = canDownloadDataset,
                    onOpenDataBrowser = { message = null; screen = Screen.DataBrowser },
                    onOpenRecord = { mode, recordId -> openRecordView(mode, recordId) },
                    onError = { showMessage(it) }
                )
                EntryMode.SEARCH -> {
                    s.searchFocus?.let { focus ->
                        Text(
                            "All ${searchFocusLabel(focus)} records, newest first.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    SearchScreen(
                        repository = repository,
                        onOpenRecord = { recordType, recordId ->
                            message = null
                            openSearchRecord(recordType, recordId)
                        },
                        onBack = { attemptExit { goBack() } },
                        initialRecordType = s.searchFocus
                    )
                }
                EntryMode.DATA_BROWSER -> DataBrowserEntryCard(
                    onOpen = { message = null; screen = Screen.DataBrowser }
                )
                EntryMode.MAP -> MapScreen(
                    repository = repository,
                    onError = { showMessage(it) },
                    onOpenRecord = { recordType, recordId ->
                        message = null
                        openSearchRecord(recordType, recordId)
                    }
                )
                EntryMode.CONSOLIDATED_QUESTIONNAIRE -> Unit
                EntryMode.TOOL -> ToolForm(
                    repository = repository,
                    crafts = crafts,
                    artisans = artisans,
                    lookupState = lookupState,
                    craftSeed = craftSeed,
                    artisanSeed = artisanSeed,
                    prefill = s.prefill,
                    adminView = adminView,
                    onCreated = { prefill, name, another -> afterCreate(EntryMode.TOOL, name, prefill, another) },
                    onDone = { message = "Tool saved"; refresh(); goDashboard() },
                    onError = { showMessage(it) }
                )
                EntryMode.MEDIA -> AndroidMediaForm(
                    repository = repository,
                    onUploaded = { count ->
                        message = "$count media file${if (count == 1) "" else "s"} uploaded and queued"
                        refresh()
                    },
                    onQueued = { queued, uploaded ->
                        message = queuedMediaSentence(queued, uploaded)
                        if (uploaded > 0) refresh()
                    },
                    onError = { showMessage(it) }
                )
                EntryMode.QUESTIONNAIRE -> {
                val v2Context = LocalContext.current
                val v2Config by remember { QuestionnaireV2.get(v2Context) }.config.collectAsState()
                if (v2Config?.v2Enabled == true) {
                    FieldQuestionnaireHome(
                        artisans = artisans,
                        user = user,
                        onError = { showMessage(it) },
                        modifier = Modifier.fillMaxWidth().height(questionnaireViewportHeight())
                    )
                } else QuestionnaireForm(
                    repository = repository,
                    sections = sections,
                    artisans = artisans,
                    lookupState = lookupState,
                    prefill = s.prefill,
                    canManageQuestionnaire = isQuestionnaireManager,
                    adminView = adminView,
                    onRefreshSections = { instrumentId ->
                        runCatching { repository.questionnaireSections(instrumentId) }
                            .onSuccess { sections = it }
                            .onFailure { showMessage(it.apiErrorMessage("Couldn't load the questionnaire sections.")) }
                    },
                    onSync = { instrumentId ->
                        runCatching { repository.questionnaireSections(instrumentId) }
                            .onSuccess { sections = it }
                            .onFailure { showMessage(it.apiErrorMessage("Couldn't load the questionnaire sections.")) }
                        loadLookups()
                    },
                    onSubmit = { body, idempotencyKey ->
                        val created = repository.createQuestionnaireInterview(body, idempotencyKey)
                        refresh()
                        created.id
                    },
                    onError = { showMessage(it) },
                    onSaved = { message = "Questionnaire interview saved"; refresh(); goDashboard() }
                )
                }
                EntryMode.TASKS -> MyTasksScreen(
                    repository = repository,
                    canAssign = isAdmin && adminChrome,
                    onOpenAssignmentBoard = { message = null; screen = Screen.AdminHub(AdminHubEntry.TASKS) },
                    onMessage = { showMessage(it, AppMessageTone.SUCCESS) },
                    onError = { showMessage(it) }
                )
                EntryMode.SHARING -> SharingForm(
                    repository = repository,
                    isAdmin = isAdmin,
                    onMessage = { showMessage(it, AppMessageTone.SUCCESS) },
                    onError = { showMessage(it) }
                )
                EntryMode.WORKSHOP_ACCESS -> WorkshopAccessScreen(
                    repository = repository,
                    onMessage = { showMessage(it, AppMessageTone.SUCCESS) },
                    onError = { showMessage(it) }
                )
                EntryMode.USERS -> if (adminChrome) {
                    UserManagementForm(
                        repository = repository,
                        isMasterAdmin = isMasterAdmin,
                        onError = { showMessage(it) }
                    )
                } else {
                    AdminViewHiddenCard(
                        label = "User management",
                        blurb = "Roles, grants and accounts.",
                        canToggle = canToggleAdminView,
                        onEnable = { requestAdminView(true) }
                    )
                }
            } }

            is Screen.Edit -> ProvideCompletenessFocus(initial = s.focus, key = s.recordId) { if (s.mode == EntryMode.QUESTIONNAIRE) {
                InterviewEditLoader(
                    repository = repository,
                    recordId = s.recordId,
                    sections = sections,
                    artisans = artisans,
                    canManageQuestionnaire = isQuestionnaireManager,
                    adminView = adminView,
                    canDelete = canDelete,
                    onRefreshSections = { instrumentId ->
                        runCatching { repository.questionnaireSections(instrumentId) }
                            .onSuccess { sections = it }
                            .onFailure { showMessage(it.apiErrorMessage("Couldn't load the questionnaire sections.")) }
                    },
                    onError = { showMessage(it) },
                    onDone = { message = "Interview updated"; refresh(); goDashboard() }
                )
            } else EditScreen(
                repository = repository,
                mode = s.mode,
                recordId = s.recordId,
                crafts = crafts,
                artisans = artisans,
                craftSeed = craftSeed,
                artisanSeed = artisanSeed,
                adminView = adminView,
                canDelete = canDelete,
                onDone = { message = "${s.mode.label} updated"; refresh(); refreshLookups(); goDashboard() },
                onError = { showMessage(it) },
                onOpenRecord = { picked -> message = null; screen = Screen.Edit(s.mode, picked) }
            ) }

            is Screen.ToolAssign -> ToolAssignScreen(
                repository = repository,
                onError = { showMessage(it) }
            )

            is Screen.Feedback -> FeedbackScreen(
                repository = repository,
                onError = { showMessage(it) }
            )

            is Screen.Notifications -> NotificationsScreen(
                api = repository.notificationApi(),
                userId = user.id,
                onOpenLink = { link ->
                    when {
                        link.startsWith("/digest/") -> Unit
                        else -> when (notificationTarget(link)) {
                            NotificationTarget.TASKS -> screen = screenFor(EntryMode.TASKS)
                            NotificationTarget.REVIEW -> screen = screenFor(EntryMode.VIEW_DATA)
                            NotificationTarget.SETTINGS -> screen = Screen.Appearance
                            null -> Unit
                        }
                    }
                }
            )
            is Screen.RemoteRecord -> RemoteRecordScreen(repository = repository, type = s.type, id = s.id)

            is Screen.Settings -> SettingsScreen(
                repository = repository,
                onMessage = { showMessage(it, AppMessageTone.SUCCESS) },
                onError = { showMessage(it) }
            )

            is Screen.AdminHub -> when {
                isAdmin && adminChrome -> AdminHubScreen(
                    repository = repository,
                    user = user,
                    isMasterAdmin = isMasterAdmin,
                    canReview = canReview,
                    onOpenRecord = { mode, recordId -> openRecordView(mode, recordId) },
                    section = s.section,
                    onSectionChange = { next -> screen = Screen.AdminHub(next) },
                    onMessage = { showMessage(it, AppMessageTone.SUCCESS) },
                    onError = { showMessage(it) },
                    pendingAccessRequests = pendingAccessRequests,
                    onPendingCountChanged = { pendingAccessRequests = it }
                )
                s.section == AdminHubEntry.REVIEWS && canReview -> ReviewApprovalCard(
                    repository = repository,
                    onOpenRecord = { mode, recordId -> openRecordView(mode, recordId) },
                    onError = { showMessage(it) }
                )
                s.section == AdminHubEntry.ACCESS_ROSTER -> AdminViewHiddenCard(
                    label = "The access roster",
                    blurb = "Who may sign in, and pending requests.",
                    canToggle = canToggleAdminView,
                    onEnable = { requestAdminView(true) }
                )
                else -> AdminViewHiddenCard(
                    label = "The settings hub",
                    blurb = "Reviews, recordings, feedback, tools and users.",
                    canToggle = canToggleAdminView,
                    onEnable = { requestAdminView(true) }
                )
            }

            is Screen.Appearance, is Screen.MyAiKeys, is Screen.TwoStepVerification, is Screen.DataBrowser -> Unit
        }

                }
                QuickReturnHeader(quickReturn, hidden = pageImeOpen && !searchOpen) { elevated ->
                    AppSearchHeader(
                        appName = APP_SEARCH_NAME,
                        logo = { m -> FieldRepoLogo(modifier = m, cornerRadius = 10.dp) },
                        onOpenSearch = { searchOpen = true },
                        elevated = elevated,
                        onScan = { scanOpen = true }
                    )
                }
                if (scanOpen) {
                    val scanServices = remember(repository) { fieldScanServices(repository, scanContext) }
                    TopBarScanSheet(
                        dispatch = { raw -> dispatchScan(raw, scanServices) },
                        onAction = { action ->
                            runFieldScanAction(scanContext, action, user.id) { target ->
                                scanOpen = false
                                when (target) {
                                    is ScanTarget.Record -> attemptExit { openRecordView(EntryMode.ARTISAN, target.id) }
                                    is ScanTarget.Session -> attemptExit {
                                        QuestionnaireV2Nav.openSession.value = target.id
                                        screen = screenFor(EntryMode.QUESTIONNAIRE)
                                    }
                                    else -> showMessage(ScanCopy.DESIGNER_CODE)
                                }
                            }
                        },
                        onDismiss = { scanOpen = false },
                        scanner = { enabled, onText, onRefusal -> FieldScanControls(enabled, onText, onRefusal) }
                    )
                }
                if (searchOpen) {
                    AppSearchPanel(
                        appName = APP_SEARCH_NAME,
                        logo = { m -> FieldRepoLogo(modifier = m, cornerRadius = 7.dp) },
                        localItems = searchLocalItems(),
                        remoteSearch = { q -> searchRemote(q) },
                        onClose = { searchOpen = false },
                        onError = { showMessage(it) },
                        fullSearch = { q, back ->
                            DataUnifiedSearch(
                                repository = repository,
                                initial = q,
                            onOpenInData = { kind, id, _ ->
                                searchOpen = false
                                attemptExit {
                                    val mode = searchRecordEntryMode(kind) ?: recentSubmissionEntryMode(kind)
                                    if (mode == null) showMessage(unroutableRecordLine(kind)) else openRecordView(mode, id)
                                }
                            },
                                onPlay = { mediaId, _, _, _ -> searchOpen = false; attemptExit { openSearchRecord(SearchRecordTypes.MEDIA, mediaId) } },
                                onOpenRemote = { type, id -> searchOpen = false; attemptExit { message = null; screen = Screen.RemoteRecord(type, id, from = screen) } },
                                onClose = back,
                            )
                        }
                    )
                }
        AppMessageBanner(
            state = appMessage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = pageReserve)
        )
        }
                }
                }
                }

                if (showWalkthrough && pendingUpdate == null) {
                    WalkthroughDialog(
                        user = user,
                        onFinish = { finishWalkthrough() },
                        onOpen = { destination ->
                            finishWalkthrough()
                            navigate(destination)
                        },
                    )
                }

                pendingUpdate?.let { release ->
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("Update required") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Install version ${release.versionName} to continue.")
                                release.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                                updateError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                if (updateBusy) {
                                    FieldLinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text("Downloading and preparing the installer…", color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !updateBusy,
                                onClick = {
                                    updateBusy = true
                                    updateError = null
                                    scope.launch {
                                        runCatching {
                                            if (!canInstallUpdates(context)) {
                                                requestInstallPermission(context)
                                                throw IllegalStateException("Allow \"Install unknown apps\" for Field Repository, then tap Update now.")
                                            }
                                            val apk = repository.downloadApk(context, release.url!!, release.versionCode)
                                            launchApkInstaller(context, apk)
                                        }.onFailure { updateError = it.apiErrorMessage("Update didn't download. Check your connection.") }
                                        updateBusy = false
                                    }
                                }
                            ) { Text(if (updateBusy) "Updating…" else "Update now") }
                        }
                    )
                }

                pendingExit?.let { exit ->
                    AlertDialog(
                        onDismissRequest = { pendingExit = null },
                        title = { Text("Unsaved changes") },
                        text = {
                            Text(
                                "You have unsaved changes, including new recordings or media. Save or discard?"
                            )
                        },
                        confirmButton = {
                            ActionRow(
                                modifier = Modifier.fillMaxWidth(),
                                destructive = {
                                    TextButton(onClick = {
                                        pendingExit = null
                                        unsavedGuard.clear()
                                        exit()
                                    }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                                }
                            ) {
                                TextButton(onClick = {
                                    pendingExit = null
                                    unsavedGuard.onSave?.invoke()
                                }) { Text("Save") }
                                TextButton(onClick = { pendingExit = null }) { Text("Keep editing") }
                            }
                        }
                    )
                }
            }
            }
        }
    }
}

private fun canInstallUpdates(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

private fun requestInstallPermission(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun launchApkInstaller(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

private fun openWebPortal(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://field-repository.vercel.app"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "No app on this device can open web links.", Toast.LENGTH_LONG).show()
    }
}

private fun searchFocusLabel(recordType: String): String = when (recordType) {
    SearchRecordTypes.ARTISAN -> "artisans"
    SearchRecordTypes.WORKSHOP -> "workshops"
    SearchRecordTypes.PRODUCT -> "products"
    SearchRecordTypes.TOOL -> "tools"
    SearchRecordTypes.MEDIA -> "media"
    else -> "records"
}

private fun searchRecordEntryMode(recordType: String): EntryMode? = when (recordType) {
    SearchRecordTypes.ARTISAN -> EntryMode.ARTISAN
    SearchRecordTypes.WORKSHOP -> EntryMode.WORKSHOP
    SearchRecordTypes.PRODUCT -> EntryMode.PRODUCT
    SearchRecordTypes.TOOL -> EntryMode.TOOL
    SearchRecordTypes.MEDIA -> EntryMode.MEDIA
    SearchRecordTypes.CRAFT -> EntryMode.CRAFT
    SearchRecordTypes.PROCESS -> EntryMode.PROCESS
    SearchRecordTypes.QUESTIONNAIRE -> EntryMode.QUESTIONNAIRE
    else -> null
}

internal fun unroutableRecordLine(recordType: String): String =
    "Update the app to open this record, or open it in the web portal."

@Composable
private fun AdminViewHiddenCard(
    label: String,
    blurb: String,
    canToggle: Boolean,
    onEnable: () -> Unit
) {
    val title = if (canToggle) "$label is hidden while admin view is off" else "$label is not part of your role"
    RecordCard(title = title, icon = Icons.Filled.VisibilityOff) {
        Text(
            if (canToggle) {
                "$blurb Admin view is off."
            } else {
                "$blurb Admins only."
            },
            color = Body,
            style = MaterialTheme.typography.bodyMedium
        )
        if (canToggle) {
            Text(
                "Your access hasn't changed.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
                Text("Turn admin view back on")
            }
        }
    }
}

@Composable
private fun DataBrowserEntryCard(onOpen: () -> Unit) {
    RecordCard(title = "Data", icon = Icons.Filled.Storage) {
        Text(
            "Browse records as folders, preview files, download as zip.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open Data")
        }
    }
}

@Composable
private fun SettingsScreen(
    repository: FieldRepository,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("REFINED_TRANSLATED") }
    var windowEnabled by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf("02:00") }
    var endTime by remember { mutableStateOf("05:00") }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        runCatching { repository.appSettings() }
            .onSuccess { s ->
                mode = s.transcriptionMode
                windowEnabled = s.batchWindowEnabled
                startTime = s.batchWindowStart
                endTime = s.batchWindowEnd
                loaded = true
                loadError = null
            }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                loadError = it.apiErrorMessage("Couldn't load the settings.")
            }
        loading = false
    }

    RecordCard(title = "Settings") {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Loading settings…", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            return@RecordCard
        }
        val failure = loadError
        if (!loaded && failure != null) {
            LoadFailedCard(message = failure, onRetry = { attempt += 1 })
            return@RecordCard
        }
        Text("Transcription output", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "How audio becomes text. AI refinement always waits for your approval.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        SettingsRadioRow("Raw transcript only", "Fastest, lowest cost.", mode == "RAW") { mode = "RAW" }
        SettingsRadioRow("Refined transcript", "Cleaned into a readable dialogue.", mode == "REFINED") { mode = "REFINED" }
        SettingsRadioRow("Refined + translated to English", "Default.", mode == "REFINED_TRANSLATED") { mode = "REFINED_TRANSLATED" }

        HorizontalDivider()
        AppToggle(
            label = "Process during an off-peak window",
            description = "Run transcription only between these times (IST).",
            checked = windowEnabled,
            onCheckedChange = { windowEnabled = it }
        )
        if (windowEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) { TimePickerField("Start", startTime) { startTime = it } }
                Box(modifier = Modifier.weight(1f)) { TimePickerField("End", endTime) { endTime = it } }
            }
            Text("India Standard Time.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                scope.launch {
                    saving = true
                    runCatching {
                        repository.updateAppSettings(
                            AppSettingUpdateRequest(
                                transcriptionMode = mode,
                                batchWindowEnabled = windowEnabled,
                                batchWindowStart = startTime,
                                batchWindowEnd = endTime
                            )
                        )
                    }.onSuccess {
                        mode = it.transcriptionMode
                        windowEnabled = it.batchWindowEnabled
                        startTime = it.batchWindowStart
                        endTime = it.batchWindowEnd
                        onMessage("Settings saved")
                    }.onFailure { onError(it.apiErrorMessage("Couldn't save settings")) }
                    saving = false
                }
            },
            enabled = !saving && loaded,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving…" else "Save settings")
        }
    }
}

@Composable
private fun SettingsRadioRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val parts = remember(value) { value.split(":").mapNotNull { it.toIntOrNull() } }
    val hour = parts.getOrNull(0)?.coerceIn(0, 23) ?: 0
    val minute = parts.getOrNull(1)?.coerceIn(0, 59) ?: 0
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label: $value")
    }
    if (show) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange("%02d:%02d".format(state.hour, state.minute))
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) }
        )
    }
}

@Composable
private fun NextStepPanel(
    repository: FieldRepository,
    step: NextStep,
    canCreate: (EntryMode) -> Boolean,
    onSelect: (EntryMode) -> Unit,
    onDismiss: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(step) {
        CarryContextStore(appContext).remember(repository.cachedUser()?.id, step.prefill.toCarryContext())
    }
    val choices = nextStepChoices(step.saved.name, hasProduct = step.prefill.productId != null)
        .mapNotNull { choice ->
            runCatching { EntryMode.valueOf(choice.modeName) }.getOrNull()
                ?.takeIf(canCreate)
                ?.let { it to choice.label }
        }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(nextStepHeading(step.saved.label, step.name), display = true, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (choices.isNotEmpty()) {
                Text(
                    step.prefill.artisanName?.let { "Next, for $it. Details are filled in." } ?: "Next. Details are filled in.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            ActionRow {
                choices.forEachIndexed { index, (mode, label) ->
                    if (index == 0) Button(onClick = { onSelect(mode) }) { Text(label) }
                    else OutlinedButton(onClick = { onSelect(mode) }) { Text(label) }
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    stats: DashboardStats?,
    statsError: String?,
    onRetryStats: () -> Unit,
    recentArtisans: List<ArtisanDto>,
    actions: List<EntryMode>,
    roleLabel: String,
    canCreateRecords: Boolean,
    showAdminHub: Boolean = false,
    onOpenAdminHub: () -> Unit = {},
    onWalkthrough: () -> Unit = {},
    onNew: (EntryMode) -> Unit,
    onUpdateExisting: (EntryMode) -> Unit,
    onOpenSearchFor: (String) -> Unit,
    onOpenReviews: (() -> Unit)?,
    onOpenMyActivity: () -> Unit,
    onOpenRecord: (EntryMode, String) -> Unit,
    onOpenArtisan: (String) -> Unit,
    sentBackSlot: @Composable () -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val columns = when {
        configuration.screenWidthDp >= 840 -> 4
        configuration.screenWidthDp >= 600 -> 3
        else -> 2
    }
    val tiles = buildList {
        actions.forEach { entry ->
            add(
                DashboardTile(
                    label = entry.label,
                    icon = entry.icon(),
                    primaryIcon = Icons.Filled.Add,
                    primaryLabel = entry.createButtonLabel(),
                    onPrimary = { onNew(entry) },
                    onUpdate = if (entry.editable) ({ onUpdateExisting(entry) }) else null
                )
            )
        }
        if (showAdminHub) {
            add(
                DashboardTile(
                    label = "Settings",
                    icon = Icons.Filled.Tune,
                    primaryIcon = Icons.Filled.Tune,
                    primaryLabel = "Open",
                    onPrimary = onOpenAdminHub
                )
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text("What would you like to do?", display = true, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        sentBackSlot()
        tiles.chunked(columns).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
                rowItems.forEach { tile ->
                    DashboardActionCard(
                        tile = tile,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
                repeat(columns - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        if (!canCreateRecords) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "As $roleLabel you can answer interviews, upload media and comment. Creating records needs Researcher access — ask an admin.",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = onWalkthrough, contentPadding = PaddingValues(0.dp)) {
                        Text("Open the walkthrough")
                    }
                }
            }
        }
        StatsCard(
            stats = stats,
            statsError = statsError,
            onRetry = onRetryStats,
            onOpenSearchFor = onOpenSearchFor,
            onOpenReviews = onOpenReviews
        )
        MyContributionCard(mine = stats?.mine, onOpenMyActivity = onOpenMyActivity)
        RecentSubmissionsCard(stats = stats, statsError = statsError, onOpenRecord = onOpenRecord)
        if (recentArtisans.isNotEmpty()) {
            Text("Recent artisans", display = true, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            recentArtisans.take(6).forEach { artisan ->
                val interaction = remember(artisan.id) { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (pressed) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(SurfaceCard)
                        } else {
                            SurfaceCard
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                                role = Role.Button,
                                onClickLabel = "Open ${artisan.name}"
                            ) { onOpenArtisan(artisan.id) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${artisan.name}. Opens the artisan."
                            }
                            .padding(12.dp)
                    ) {
                        Text(artisan.name, display = true, fontWeight = FontWeight.SemiBold)
                        Text("${artisan.craft?.name ?: "No craft"} · ${artisan.place}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun EntryMode.createButtonLabel(): String = when (this) {
    EntryMode.MEDIA -> "Upload"
    EntryMode.QUESTIONNAIRE -> "New interview"
    EntryMode.USERS -> "Manage"
    EntryMode.VIEW_DATA -> "Open"
    EntryMode.TASKS -> "Open"
    EntryMode.WORKSHOP_ACCESS -> "Open"
    EntryMode.SEARCH, EntryMode.DATA_BROWSER -> "Open"
    EntryMode.MAP, EntryMode.CONSOLIDATED_QUESTIONNAIRE -> "Open"
    else -> "New"
}

private class DashboardTile(
    val label: String,
    val icon: ImageVector,
    val primaryIcon: ImageVector,
    val primaryLabel: String,
    val onPrimary: () -> Unit,
    val onUpdate: (() -> Unit)? = null
)

@Composable
private fun DashboardActionCard(tile: DashboardTile, modifier: Modifier = Modifier) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(role = Role.Button, onClickLabel = "${tile.primaryLabel} ${tile.label}") { tile.onPrimary() }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(color = MaterialTheme.field.brandTile, shape = MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    tile.icon,
                    contentDescription = null,
                    tint = MaterialTheme.field.onBrandTile,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                tile.label,
                display = true,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = tile.onPrimary,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) { CardButtonLabel(tile.primaryIcon, tile.primaryLabel) }
            tile.onUpdate?.let { update ->
                OutlinedButton(
                    onClick = update,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) { CardButtonLabel(Icons.Filled.Edit, RECORD_UPDATE_LABEL) }
            }
        }
    }
}

@Composable
private fun CardButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(6.dp))
    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
}

@Composable
private fun StatsCard(
    stats: DashboardStats?,
    statsError: String?,
    onRetry: () -> Unit,
    onOpenSearchFor: (String) -> Unit,
    onOpenReviews: (() -> Unit)?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.field.brandTile),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("At a glance", display = true, color = MaterialTheme.field.onBrandTile, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Every record, not only your own.",
                color = MaterialTheme.field.onBrandTileMuted,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            if (stats == null && statsError != null) {
                Text(statsError, color = MaterialTheme.field.onBrandTile, style = MaterialTheme.typography.bodyMedium)
                ActionRow {
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Try again", color = MaterialTheme.field.accentOnBrandTile, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else if (stats == null) {
                Text("Loading…", color = MaterialTheme.field.onBrandTileMuted)
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                ) {
                    Stat(
                        "Artisans", stats.totalArtisans, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.ARTISAN) },
                        openDescription = "Opens a search of the artisans"
                    )
                    Stat(
                        "Workshops", stats.totalWorkshops, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.WORKSHOP) },
                        openDescription = "Opens a search of the workshops"
                    )
                    Stat(
                        "Products", stats.totalProductRecords, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.PRODUCT) },
                        openDescription = "Opens a search of the products"
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                ) {
                    Stat(
                        "Tools", stats.totalToolRecords, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.TOOL) },
                        openDescription = "Opens a search of the tools"
                    )
                    Stat(
                        "Media files", stats.totalMediaFiles, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.MEDIA) },
                        openDescription = "Opens a search of the media"
                    )
                    Stat(
                        "Pending review", stats.pendingSubmissions, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = onOpenReviews,
                        openDescription = "Opens reviews and approvals"
                    )
                }
            }
        }
    }
}

@Composable
private fun MyContributionCard(mine: DashboardStatsMine?, onOpenMyActivity: () -> Unit) {
    if (mine == null) return
    val tiles = listOf(
        Triple("Artisans", mine.totalArtisans, Icons.Filled.Person),
        Triple("Workshops", mine.totalWorkshops, Icons.Filled.Groups),
        Triple("Products", mine.totalProductRecords, Icons.Filled.Inventory2),
        Triple("Tools", mine.totalToolRecords, Icons.Filled.Build),
        Triple("Media files", mine.totalMediaFiles, Icons.Filled.PermMedia),
        Triple("Awaiting review", mine.pendingSubmissions, Icons.Filled.RateReview)
    )
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Your contribution",
                    display = true,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onOpenMyActivity, contentPadding = PaddingValues(0.dp)) {
                    Text("Open My Activity", style = MaterialTheme.typography.labelMedium)
                }
            }
            tiles.chunked(3).forEach { rowTiles ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                ) {
                    rowTiles.forEach { (label, value, icon) ->
                        MineStat(
                            label = label,
                            value = value,
                            icon = icon,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onOpen = onOpenMyActivity
                        )
                    }
                    repeat(3 - rowTiles.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MineStat(
    label: String,
    value: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(
                MaterialTheme.colorScheme.primary
                    .copy(alpha = if (pressed) 0.16f else 0.07f)
                    .compositeOver(Canvas)
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = "Opens My Activity"
            ) { onOpen() }
            .semantics(mergeDescendants = true) {
                contentDescription = "Your ${label.lowercase()}: $value. Opens My Activity."
            }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                label,
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            value.toString(),
            display = true,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RecentSubmissionsCard(stats: DashboardStats?, statsError: String?, onOpenRecord: (EntryMode, String) -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Recent submissions",
                display = true,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "The newest records, whoever added them.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            when {
                stats == null && statsError != null -> Text(
                    "Couldn't load the newest records.",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium
                )
                stats == null -> Text("Loading…", color = Muted, style = MaterialTheme.typography.bodyMedium)
                stats.recentSubmissions.isEmpty() -> {
                    Text(
                        "No submissions yet",
                        display = true,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "New records will appear here.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                else -> stats.recentSubmissions.forEach { item ->
                    RecentSubmissionRow(item = item, onOpenRecord = onOpenRecord)
                }
            }
        }
    }
}

@Composable
private fun RecentSubmissionRow(
    item: DashboardRecentSubmissionDto,
    onOpenRecord: (EntryMode, String) -> Unit
) {
    val mode = remember(item.type) { recentSubmissionEntryMode(item.type) }
    val typeLabel = item.type.replaceFirstChar { it.uppercase() }
    val title = item.title?.takeIf { it.isNotBlank() }
        ?: "Untitled ${item.type.ifBlank { "record" }}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (mode == null) Modifier
                else Modifier.clickable(role = Role.Button, onClickLabel = "Open $title") { onOpenRecord(mode, item.id) }
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            title,
            display = true,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            color = if (mode == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val place = item.place?.takeIf { it.isNotBlank() }
        Text(
            listOfNotNull(typeLabel.takeIf { it.isNotBlank() }, place).joinToString(" · ").ifBlank { "Record" },
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Recorded by ${item.createdByName?.takeIf { it.isNotBlank() } ?: "-"}" +
                " · ${item.status.ifBlank { "-" }}" +
                " · ${formatIsoDate(item.createdAt) ?: "-"}",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun recentSubmissionEntryMode(type: String): EntryMode? = when (type.lowercase()) {
    "artisan" -> EntryMode.ARTISAN
    "product" -> EntryMode.PRODUCT
    "process" -> EntryMode.PROCESS
    "tool" -> EntryMode.TOOL
    "workshop" -> EntryMode.WORKSHOP
    "craft" -> EntryMode.CRAFT
    "questionnaire", "interview" -> EntryMode.QUESTIONNAIRE
    "media" -> EntryMode.MEDIA
    else -> null
}

@Composable
private fun Stat(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    openDescription: String? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .clip(shape)
            .background(color = MaterialTheme.field.accentOnBrandTile.copy(alpha = if (pressed) 0.34f else 0.18f))
            .then(
                if (onOpen == null) Modifier else Modifier
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClickLabel = openDescription
                    ) { onOpen() }
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOfNotNull("$label: $value", openDescription).joinToString(". ")
                    }
            )
            .padding(12.dp)
    ) {
        Text(value.toString(), display = true, color = MaterialTheme.field.onBrandTile, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(label, color = MaterialTheme.field.onBrandTileMuted, style = MaterialTheme.typography.bodySmall)
    }
}

private val productTypeOptions = listOf("FINISHED_GOOD", "SAMPLE", "RAW_MATERIAL", "COMPONENT", "PACKAGING", "OTHER")
private val marketDemandOptions = listOf("LOW", "MEDIUM", "HIGH", "SEASONAL", "UNKNOWN")
private val makerOptions = listOf("ARTISAN", "LOCAL_BLACKSMITH", "CARPENTER", "WORKSHOP", "FACTORY", "UNKNOWN", "OTHER")
private val statusOptions = listOf("DRAFT", "PENDING", "APPROVED", "REJECTED")

private fun recordStatusWord(status: String): String = when (status) {
    "NEEDS_REVISION" -> "Needs revision"
    else -> status.lowercase().replaceFirstChar { it.uppercase() }
}

private val ROLE_RANK = mapOf(
    "CROWDSOURCE_VOLUNTEER" to 10,
    "FIELD_CONTRIBUTOR" to 20,
    "RESEARCHER" to 30,
    "PROFESSOR" to 40,
    "ADMIN" to 50,
    "MASTER_ADMIN" to 60
)
private const val RANK_FIELD_CONTRIBUTOR = 20
private const val RANK_RESEARCHER = 30
private const val RANK_PROFESSOR = 40
private const val RANK_ADMIN = 50

private val ROLE_LABELS = mapOf(
    "CROWDSOURCE_VOLUNTEER" to "Crowdsource Volunteer",
    "FIELD_CONTRIBUTOR" to "Field Contributor",
    "RESEARCHER" to "Researcher",
    "PROFESSOR" to "Professor",
    "ADMIN" to "Admin",
    "MASTER_ADMIN" to "Master Admin"
)

private fun roleLabel(role: String?): String = ROLE_LABELS[role] ?: role.orEmpty()
private fun roleRank(role: String?): Int = ROLE_RANK[role] ?: 0
private fun canSetRecordStatus(role: String?): Boolean = roleRank(role) >= RANK_PROFESSOR

private fun UserDto.isAdminUser(): Boolean = roleRank(role) >= RANK_ADMIN

private fun UserDto.isMasterAdminUser(): Boolean = role == "MASTER_ADMIN"

private fun UserDto.canCreateRecords(): Boolean = roleRank(role) >= RANK_RESEARCHER

private fun UserDto.canAccessReview(): Boolean = roleRank(role) >= RANK_FIELD_CONTRIBUTOR || canReview

private fun UserDto.canDownloadTheDataset(): Boolean = roleRank(role) >= RANK_PROFESSOR || canDownloadDataset

private fun UserDto.canManageTheQuestionnaire(): Boolean =
    roleRank(role) >= RANK_PROFESSOR || canManageQuestionnaire

private fun UserDto.canManageTheCrafts(): Boolean = roleRank(role) >= RANK_PROFESSOR

private fun UserDto.canManageTheWorkshops(): Boolean = roleRank(role) >= RANK_PROFESSOR

private fun UserDto.canManageUsers(): Boolean = roleRank(role) >= RANK_PROFESSOR

private fun defaultCreateStatus(role: String?): String = if (canSetRecordStatus(role)) "APPROVED" else "PENDING"
private val genderOptions = StandardValues.GENDER_LABELS

private class UnsavedGuard {
    var dirty by mutableStateOf(false)
    var onSave: (() -> Unit)? = null

    fun clear() {
        dirty = false
        onSave = null
    }
}

private val LocalUnsavedGuard = staticCompositionLocalOf<UnsavedGuard?> { null }

@Composable
private fun RegisterUnsavedGuard(dirty: Boolean, onSave: () -> Unit) {
    val guard = LocalUnsavedGuard.current ?: return
    val currentSave by rememberUpdatedState(onSave)
    LaunchedEffect(dirty) { guard.dirty = dirty }
    DisposableEffect(Unit) {
        guard.onSave = { currentSave() }
        onDispose { guard.clear() }
    }
}

private class MediaCaptureState {
    var uris by mutableStateOf<List<Uri>>(emptyList())
    var location by mutableStateOf<LocationRequest?>(null)
    var measurementUri by mutableStateOf<Uri?>(null)

    val stagedDeferred = mutableMapOf<Uri, Deferred<StagedMedia?>>()
    var staged by mutableStateOf<Map<Uri, StagedMedia>>(emptyMap())
    var stagedProgress by mutableStateOf<Map<Uri, Float>>(emptyMap())
    var stagedFailed by mutableStateOf<Set<Uri>>(emptySet())

    fun forget(uri: Uri) {
        stagedDeferred.remove(uri)
        staged = staged - uri
        stagedProgress = stagedProgress - uri
        stagedFailed = stagedFailed - uri
        TranscribeChoices.forget(uri.toString())
    }

    fun reset() {
        uris = emptyList()
        location = null
        measurementUri = null
        stagedDeferred.clear()
        staged = emptyMap()
        stagedProgress = emptyMap()
        stagedFailed = emptySet()
    }
}

@Composable
private fun rememberMediaCaptureState(): MediaCaptureState = remember { MediaCaptureState() }

private fun startEagerUpload(
    repository: FieldRepository,
    context: Context,
    media: MediaCaptureState,
    uri: Uri,
    uiScope: CoroutineScope
) {
    media.stagedDeferred.remove(uri)
    media.staged = media.staged - uri
    media.stagedFailed = media.stagedFailed - uri
    media.stagedProgress = media.stagedProgress + (uri to 0f)
    val deferred = AppScope.io.async {
        var lastPct = -1
        runCatching {
            repository.preuploadObject(context, uri) { sent, total ->
                val pct = if (total > 0L) ((sent * 100) / total).toInt() else 0
                if (pct != lastPct) {
                    lastPct = pct
                    media.stagedProgress = media.stagedProgress + (uri to pct / 100f)
                }
            }
        }.getOrNull()
    }
    media.stagedDeferred[uri] = deferred
    uiScope.launch {
        val result = runCatching { deferred.await() }.getOrNull()
        if (result != null) {
            media.staged = media.staged + (uri to result)
            media.stagedProgress = media.stagedProgress + (uri to 1f)
            media.stagedFailed = media.stagedFailed - uri
        } else {
            media.stagedFailed = media.stagedFailed + uri
        }
    }
}

private fun LocalDate.toIsoInstant(): String =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toString()

private fun parseIsoToLocalDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return runCatching { java.time.OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}

private fun formatIsoDate(value: String?): String? {
    val date = parseIsoToLocalDate(value) ?: return null
    return runCatching {
        date.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale("en", "IN")))
    }.getOrNull()
}

private fun formatIsoDay(value: String?, daysBefore: Long = 0): String {
    val date = parseIsoToLocalDate(value) ?: return value.orEmpty()
    return formatIsoDate(date.minusDays(daysBefore).toString()) ?: value.orEmpty()
}

private fun numToText(value: String?): String {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    val number = raw.toDoubleOrNull() ?: return raw
    return if (number % 1.0 == 0.0) number.toLong().toString() else raw
}

private fun numToText(value: Double?): String {
    if (value == null) return ""
    return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}

private fun locationForBody(isEdit: Boolean, current: LocationRequest?, original: LocationDto?): LocationRequest? {
    if (!isEdit) return current
    if (current == null) return null
    if (original != null &&
        kotlin.math.abs(current.latitude - original.latitude) < 1e-6 &&
        kotlin.math.abs(current.longitude - original.longitude) < 1e-6 &&
        current.state == original.state &&
        current.pincode == original.pincode &&
        current.district == original.district &&
        current.village == original.village &&
        current.subjectLatitude == original.subjectLatitude &&
        current.subjectLongitude == original.subjectLongitude &&
        current.extraMetadata == original.extraMetadata
    ) {
        return null
    }
    return current
}

private fun newRecordLocationError(
    isEdit: Boolean,
    current: LocationRequest?,
    heading: String = ARTISAN_LOCATION_HEADING
): String? = if (isEdit) null else artisanLocationRequirementError(current, heading)

private fun ArtisanDto?.inheritedPlace(isEdit: Boolean): InheritedPlace? {
    val artisan = this ?: return null
    if (isEdit) return null
    val loc = artisan.location
    val village = loc?.village?.takeIf { it.isNotBlank() } ?: artisan.place.takeIf { it.isNotBlank() }
    if (loc?.state.isNullOrBlank() && loc?.district.isNullOrBlank() && village == null && loc?.pincode.isNullOrBlank()) return null
    return InheritedPlace(
        fromName = artisan.name,
        sourceId = artisan.id,
        state = loc?.state,
        district = loc?.district,
        village = village,
        pincode = loc?.pincode
    )
}

private fun LocationDto.toRequest(): LocationRequest =
    LocationRequest(
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracy = accuracy,
        address = address,
        placeName = placeName ?: address,
        state = state,
        district = district,
        village = village,
        pincode = pincode,
        subjectLatitude = subjectLatitude,
        subjectLongitude = subjectLongitude,
        capturedAt = capturedAt,
        extraMetadata = extraMetadata
    )

internal const val PINCODE_LENGTH = 6

internal fun pincodeValidationError(value: String?): String? {
    val digits = value?.trim().orEmpty()
    if (digits.isEmpty()) return null
    if (!digits.all { it in '0'..'9' }) return "Pincode must be 6 digits."
    if (digits.length != PINCODE_LENGTH) return "Pincode must be 6 digits (has ${digits.length})."
    if (digits[0] == '0') return "Pincodes don't start with 0."
    return null
}

private fun foldStateName(value: String): String =
    value.lowercase().replace("&", "and").filter { it in 'a'..'z' || it in '0'..'9' }

internal fun matchIndianState(text: String, states: List<String>): String {
    val wanted = foldStateName(text)
    if (wanted.isEmpty()) return ""
    states.firstOrNull { foldStateName(it) == wanted }?.let { return it }
    return states.firstOrNull { entry ->
        val name = foldStateName(entry)
        (name.length >= 5 && wanted.contains(name)) || (wanted.length >= 5 && name.contains(wanted))
    }.orEmpty()
}

@Composable
private fun LocationAddressEditor(
    repository: FieldRepository,
    value: LocationRequest?,
    @Suppress("UNUSED_PARAMETER") onUseGps: () -> LocationRequest?,
    onChange: (LocationRequest?) -> Unit,
    onMessage: (String) -> Unit = {},
    required: Boolean = true,
    isEdit: Boolean = false,
    showRequirementError: Boolean = false,
    heading: String = ARTISAN_LOCATION_HEADING,
    inherit: InheritedPlace? = null
) {
    LocationFieldsSection(
        repository = repository,
        value = value,
        onChange = onChange,
        required = required,
        isEdit = isEdit,
        showRequirementError = showRequirementError,
        onMessage = onMessage,
        heading = heading,
        inherit = inherit
    )
}

internal fun sameCoordinate(next: LocationRequest, previous: LocationRequest?): Boolean =
    previous != null &&
        kotlin.math.abs(next.latitude - previous.latitude) < 1e-6 &&
        kotlin.math.abs(next.longitude - previous.longitude) < 1e-6

private suspend fun uploadAttachments(
    repository: FieldRepository,
    context: Context,
    media: MediaCaptureState,
    recordType: String,
    recordId: String,
    titleHint: String?,
    caption: String?,
    customSegment: String? = null
) {
    val failures = mutableListOf<String>()
    val failedUris = mutableListOf<Pair<Int, Uri>>()
    media.uris.forEachIndexed { index, uri ->
        val staged = media.stagedDeferred[uri]?.let { runCatching { it.await() }.getOrNull() } ?: media.staged[uri]
        val result = runCatching {
            if (staged != null) {
                repository.completeStaged(
                    staged = staged,
                    linkedRecordType = recordType,
                    linkedRecordId = recordId,
                    recordName = titleHint,
                    caption = caption,
                    location = media.location,
                    batchIndex = index + 1,
                    customSegment = customSegment,
                    processingRequests = TranscribeChoices.processingFor(uri.toString(), staged.mediaType)
                )
            } else {
                repository.uploadMedia(
                    context = context,
                    uri = uri,
                    linkedRecordType = recordType,
                    linkedRecordId = recordId,
                    caption = caption,
                    location = media.location,
                    titleHint = titleHint,
                    batchIndex = index + 1,
                    customSegment = customSegment
                )
            }
        }
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            if (error is kotlinx.coroutines.CancellationException) throw error
            failures.add(uri.lastPathSegment ?: "file ${index + 1}")
            failedUris.add(index to uri)
        }
    }
    if (failedUris.isNotEmpty() && queueFailedAttachments(
            repository, context, recordType, recordId, titleHint,
            failedUris.map { (index, uri) ->
                OfflineMediaSpec(
                    uri = uri, caption = caption, recordName = titleHint, customSegment = customSegment,
                    batchIndex = index + 1, location = media.location
                )
            }
        )
    ) return
    if (failures.isNotEmpty()) {
        val allFailed = failures.size == media.uris.size
        val failed = failedUris.map { it.second }.toSet()
        media.uris.filterNot { it in failed }.forEach { media.forget(it) }
        media.uris = media.uris.filter { it in failed }
        val prefix = if (allFailed) "All ${countNoun(failures.size, "media file")} failed to upload" else "${countNoun(failures.size, "media file")} failed to upload"
        throw IllegalStateException(
            "$prefix (${failures.joinToString(", ")}). The record was saved — re-attach from \"Update existing\"."
        )
    }
}

private suspend fun queueFailedAttachments(
    repository: FieldRepository,
    context: Context,
    recordType: String,
    recordId: String,
    label: String?,
    items: List<OfflineMediaSpec>
): Boolean {
    if (items.isEmpty()) return true
    val queued = runCatching {
        repository.queueAttachments(context, recordType, recordId, label?.trim().orEmpty().ifBlank { "Attachments" }, items)
    }.isSuccess
    if (queued) {
        Toast.makeText(
            context.applicationContext,
            "Saved. ${countNoun(items.size, "file")} will attach when you're online.",
            Toast.LENGTH_LONG
        ).show()
    }
    return queued
}

private suspend fun queueSmallWrite(
    repository: FieldRepository,
    context: Context,
    failure: Throwable?,
    idempotent: Boolean,
    type: String,
    payloadJson: String,
    label: String,
    targetId: String? = null
): Boolean {
    val queue = if (failure == null) !repository.isOnline(context) else idempotent && failure is java.io.IOException
    if (!queue) return false
    return runCatching { repository.queueAction(context, type, payloadJson, label, targetId) }.isSuccess
}

private suspend fun uploadMeasurement(
    repository: FieldRepository,
    context: Context,
    media: MediaCaptureState,
    recordType: String,
    recordId: String,
    titleHint: String?
) {
    val uri = media.measurementUri ?: return
    repository.uploadMedia(
        context = context,
        uri = uri,
        linkedRecordType = recordType,
        linkedRecordId = recordId,
        caption = "Measurement grid image for ${titleHint.orEmpty()}".trim(),
        location = media.location,
        titleHint = "${titleHint.orEmpty()} measurement grid".trim(),
        batchIndex = 1,
        processingRequests = listOf("MEASUREMENT")
    )
}

@Composable
private fun GridMeasurementSection(
    repository: FieldRepository,
    media: MediaCaptureState,
    includeHeight: Boolean = true,
    onLengthBreadth: (length: Double?, breadth: Double?, marker: JsonObject?) -> Unit,
    onHeight: (value: Double, marker: JsonObject?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(setOf<String>()) }
    var capturedUris by remember { mutableStateOf<Map<String, Uri>>(emptyMap()) }
    var status by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingGroup by remember { mutableStateOf<String?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    fun analyze(group: String, uri: Uri) {
        capturedUris[group]?.let { previous ->
            if (previous != uri) {
                val deferred = media.stagedDeferred[previous]
                media.forget(previous)
                media.uris = media.uris.filterNot { it == previous }
                AppScope.io.launch { runCatching { deferred?.await()?.let { repository.deleteStaged(it.objectKey) } } }
            }
        }
        capturedUris = capturedUris + (group to uri)
        if (uri !in media.uris) media.uris = media.uris + uri
        status = status + (group to "Analyzing…")
        scope.launch {
            if (group == "lengthBreadth") {
                runCatching { repository.analyzeMeasurementLengthBreadth(context, uri) }
                    .onSuccess { reading ->
                        val length = reading.lengthInches
                        val breadth = reading.breadthInches
                        onLengthBreadth(length, breadth, reading.marker)
                        val parts = buildList {
                            if (length != null && length > 0) add("L ${"%.2f".format(length)}\"")
                            if (breadth != null && breadth > 0) add("B ${"%.2f".format(breadth)}\"")
                        }
                        status = status + (group to if (parts.isEmpty()) "Couldn't read a value — enter it manually"
                        else "Measured ${parts.joinToString(" · ")} — fields filled")
                    }
                    .onFailure { status = status + (group to "Analysis failed — enter it manually") }
            } else {
                runCatching { repository.analyzeMeasurement(context, uri, "height") }
                    .onSuccess { reading ->
                        val value = reading.valueInches
                        if (value != null && value > 0.0) {
                            onHeight(value, reading.marker)
                            status = status + (group to "Measured ${"%.2f".format(value)} in — field filled")
                        } else {
                            status = status + (group to "Couldn't read a value — enter it manually")
                        }
                    }
                    .onFailure { status = status + (group to "Analysis failed — enter it manually") }
            }
        }
    }

    fun discardGroup(group: String) {
        val uri = capturedUris[group] ?: return
        val deferred = media.stagedDeferred[uri]
        media.forget(uri)
        media.uris = media.uris.filterNot { it == uri }
        AppScope.io.launch { runCatching { deferred?.await()?.let { repository.deleteStaged(it.objectKey) } } }
        capturedUris = capturedUris - group
        status = status - group
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val g = pendingGroup
        if (uri != null && g != null) analyze(g, uri)
        pendingGroup = null
    }
    val permissionGate = rememberPermissionGate(onRefused = { refusal ->
        pendingGroup?.let { g -> status = status + (g to refusal) }
        pendingGroup = null
    })
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCaptureUri
        val g = pendingGroup
        if (success && uri != null && g != null) analyze(g, uri)
        pendingCaptureUri = null
        pendingGroup = null
    }

    @Composable
    fun GridGroupRow(key: String, label: String, hint: String) {
        AppToggle(
            label = label,
            checked = enabled.contains(key),
            onCheckedChange = { checked -> enabled = if (checked) enabled + key else enabled - key }
        )
        if (enabled.contains(key)) {
            Text(hint, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            ActionRow {
                OutlinedButton(
                    onClick = {
                        pendingGroup = key
                        permissionGate.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                            val uri = createAppFileUri(context, "grid-$key-", ".jpg")
                            pendingGroup = key
                            pendingCaptureUri = uri
                            takePhoto.launch(uri)
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text("Capture photo", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = { pendingGroup = key; pickImage.launch("image/*") },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text("Pick photo", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
            }
            status[key]?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            capturedUris[key]?.let { uri ->
                AndroidUriPreview(context = context, uri = uri, onRemove = { discardGroup(key) }, downloadable = true)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Document using grid", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Place the object on a 1-inch grid. A top-down photo gives length and breadth; a side-on photo gives height.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        GridGroupRow("lengthBreadth", "Length & breadth (one photo)", "Top-down photo on the grid.")
        if (includeHeight) {
            GridGroupRow("height", "Height (one photo)", "Side-on photo against the grid.")
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    placeholder: String = "Select",
    includeNone: Boolean = true,
    enabled: Boolean = true,
    server: ServerSearch? = null,
    onSelect: (String) -> Unit
) {
    SearchableSelectField(
        label = label,
        options = remember(options) { options.asSelectOptions() },
        selectedValue = selectedValue,
        placeholder = placeholder,
        includeNone = includeNone,
        enabled = enabled,
        server = server,
        onSelect = onSelect
    )
}

@Composable
private fun StatusDropdown(value: String, onSelect: (String) -> Unit) {
    DropdownField(
        label = "Status",
        options = statusOptions.map { it to recordStatusWord(it) },
        selectedValue = value,
        includeNone = false,
        onSelect = onSelect
    )
}

@Composable
private fun StatusControl(canSetStatus: Boolean, value: String, onSelect: (String) -> Unit) {
    if (canSetStatus) {
        StatusDropdown(value = value, onSelect = onSelect)
    } else {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status", color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .background(SurfaceCard, RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Box(modifier = Modifier.size(8.dp).background(MaterialTheme.field.warning, CircleShape))
                Text("Pending", color = Body, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Text("Reviewed before publishing.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal const val DEFAULT_PROBE_LIMIT = 5

internal class WorkshopPickerState(
    private val repository: FieldRepository,
    initialId: String,
    defaultWalkPending: Boolean = false
) {
    var workshops by mutableStateOf<List<WorkshopDetailDto>>(emptyList())
    var selectedId by mutableStateOf(initialId)
    var baselineId by mutableStateOf(initialId)

    var settled by mutableStateOf(!defaultWalkPending)
        private set

    internal fun markSettled() {
        settled = true
    }

    var check by mutableStateOf<WorkshopSubmissionCheckDto?>(null)
        private set

    var pendingConfirm by mutableStateOf<WorkshopSubmissionCheckDto?>(null)
        private set

    private var awaitingConfirm: CompletableDeferred<Boolean>? = null

    private val answers = mutableMapOf<String, WorkshopSubmissionCheckDto>()

    fun value(): String? = selectedId.ifBlank { null }

    fun isDirty(): Boolean = selectedId != baselineId

    fun applyDefault(id: String) {
        selectedId = id
        baselineId = id
    }

    suspend fun applyMostRecentSubmittable(list: List<WorkshopDetailDto>, runningOnly: Boolean = false) {
        if (list.isEmpty()) return
        for (workshop in list.take(DEFAULT_PROBE_LIMIT)) {
            val answer = answerFor(workshop.id)
            if (selectedId.isNotBlank()) return
            val running = !runningOnly ||
                workshopWindowState(answer, workshop) == WorkshopWindowState.IN_WINDOW
            if ((answer == null || answer.canSubmit) && running) {
                applyDefault(workshop.id)
                return
            }
        }
        if (selectedId.isBlank() && !runningOnly) applyDefault(list.first().id)
    }

    fun markSaved() {
        baselineId = selectedId
    }

    private suspend fun answerFor(workshopId: String): WorkshopSubmissionCheckDto? {
        if (workshopId.isBlank()) return null
        answers[workshopId]?.let { return it }
        return repository.workshopSubmissionCheck(workshopId)?.also { answers[workshopId] = it }
    }

    suspend fun refreshCheck() {
        val asked = selectedId
        check = null
        if (asked.isBlank()) return
        val answer = answerFor(asked)
        if (selectedId == asked) check = answer
    }

    suspend fun confirmSubmission(): Boolean {
        val asked = selectedId
        if (asked.isBlank()) return true
        val answer = answerFor(asked) ?: return true
        if (!answer.outOfWindow && !answer.isOver) return true
        val gate = CompletableDeferred<Boolean>()
        awaitingConfirm = gate
        pendingConfirm = answer
        return gate.await()
    }

    fun settleConfirm(confirmed: Boolean) {
        pendingConfirm = null
        awaitingConfirm?.complete(confirmed)
        awaitingConfirm = null
    }
}

@Composable
internal fun rememberWorkshopPicker(
    repository: FieldRepository,
    isEdit: Boolean,
    initialId: String?,
    resetKey: Any? = null,
    runningOnly: Boolean = false
): WorkshopPickerState {
    val state = remember(resetKey) {
        WorkshopPickerState(
            repository = repository,
            initialId = initialId.orEmpty(),
            defaultWalkPending = !isEdit && initialId.isNullOrBlank()
        )
    }
    LaunchedEffect(resetKey) {
        runCatching { repository.workshopsByOccurrence() }
            .onSuccess { list ->
                state.workshops = list
                if (!isEdit && state.selectedId.isBlank()) {
                    state.applyMostRecentSubmittable(list, runningOnly)
                }
            }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
            }
        state.markSettled()
    }
    LaunchedEffect(state, state.selectedId) { state.refreshCheck() }
    return state
}

@Composable
private fun WorkshopField(state: WorkshopPickerState, saving: Boolean = false) {
    val selected = state.workshops.firstOrNull { it.id == state.selectedId }
    val check = state.check
    val blocked = check != null && !check.canSubmit
    val windowState = if (state.selectedId.isBlank()) {
        WorkshopWindowState.IN_WINDOW
    } else {
        workshopWindowState(check, selected)
    }
    val notice = workshopWindowNotice(
        state = windowState,
        startLabel = formatWorkshopDay(selected?.startDate ?: selected?.date),
        endLabel = formatWorkshopDay(check?.endDate ?: selected?.endDate ?: selected?.date),
        needsAdminApproval = check?.needsAdminApproval ?: true
    )

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WorkshopDropdown(
            workshops = state.workshops,
            selectedValue = state.selectedId,
            placeholder = "Choose a workshop"
        ) { state.selectedId = it }
        if (blocked) {
            Text(
                "You're not assigned to this workshop. Pick another, or ask an admin.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        } else if (notice != null) {
            Text(notice, color = Coral, style = MaterialTheme.typography.bodySmall)
        }
    }

    state.pendingConfirm?.let { pending ->
        LateSubmissionDialog(
            workshopTitle = pending.title ?: selected?.title,
            endDate = pending.endDate ?: selected?.endDate ?: selected?.date,
            needsAdminApproval = pending.needsAdminApproval,
            saving = saving,
            onConfirm = { state.settleConfirm(true) },
            onCancel = { state.settleConfirm(false) }
        )
    }
}

@Composable
private fun LateSubmissionDialog(
    workshopTitle: String?,
    endDate: String?,
    needsAdminApproval: Boolean,
    saving: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val name = workshopTitle?.trim().orEmpty().ifBlank { "This workshop" }
    val ended = formatIsoDate(endDate)?.let { " ended on $it" } ?: " has already ended"
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text("Late submission") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$name$ended. " +
                        if (needsAdminApproval) {
                            "It will save as a late submission and needs an admin's approval."
                        } else {
                            "It will save as a late submission."
                        }
                )
                Text(
                    "Or pick a workshop that's still running.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = onConfirm) {
                Text(if (saving) "Saving…" else "Submit anyway")
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onCancel) { Text("Go back") }
        }
    )
}

@Composable
private fun WorkshopDropdown(
    workshops: List<WorkshopDetailDto>,
    selectedValue: String,
    label: String = "Workshop",
    placeholder: String = "Unlinked",
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    DropdownField(
        label = label,
        options = workshops.map { it.id to workshopOptionLabel(it) },
        selectedValue = selectedValue,
        placeholder = placeholder,
        enabled = enabled,
        onSelect = onSelect
    )
}

private fun workshopOptionLabel(workshop: WorkshopDetailDto): String {
    if (workshop.title.isBlank()) return workshopDisplayTitle(null, date = workshop.occurrenceDate(), place = workshop.place)
    val title = workshop.title
    val day = workshop.occurrenceDate().take(10)
    return if (day.isBlank()) title else "$title · $day"
}

private val EMAIL_RE = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")

private const val AADHAAR_LENGTH = 12

private val VERHOEFF_D = arrayOf(
    intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
    intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
    intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
    intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
    intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
    intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
    intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
    intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
    intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
    intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
)

private val VERHOEFF_P = arrayOf(
    intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
    intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
    intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
    intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
    intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
    intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
    intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
    intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8)
)

private fun verhoeffOk(digits: String): Boolean {
    var checksum = 0
    digits.reversed().forEachIndexed { index, char ->
        checksum = VERHOEFF_D[checksum][VERHOEFF_P[index % 8][char - '0']]
    }
    return checksum == 0
}

private fun aadhaarValidationError(value: String?): String? {
    val digits = value?.trim().orEmpty()
    if (digits.isEmpty()) return null
    if (!digits.all { it in '0'..'9' }) return "Aadhaar must be 12 digits."
    if (digits.length != AADHAAR_LENGTH) return "Aadhaar must be 12 digits (has ${digits.length})."
    if (digits[0] == '0' || digits[0] == '1') return "Aadhaar numbers don't start with 0 or 1."
    if (!verhoeffOk(digits)) {
        return "This Aadhaar number isn't valid. Check the card."
    }
    return null
}

private fun maskAadhaar(value: String?): String? {
    val digits = value?.trim().orEmpty()
    if (digits.isEmpty()) return null
    if (digits.length < 4) return "XXXX XXXX XXXX"
    return "XXXX XXXX ${digits.takeLast(4)}"
}

private object AadhaarGroupingTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val grouped = digits.chunked(4).joinToString(" ")
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                (offset + ((offset - 1) / 4).coerceIn(0, 2)).coerceIn(0, grouped.length)

            override fun transformedToOriginal(offset: Int): Int =
                (offset - (offset / 5).coerceIn(0, 2)).coerceIn(0, digits.length)
        }
        return TransformedText(AnnotatedString(grouped), mapping)
    }
}

@Composable
private fun ArtisanAadhaarField(
    value: String,
    error: String?,
    warning: String?,
    required: Boolean,
    focusRequester: FocusRequester,
    onRecord: String? = null,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { input -> onValueChange(input.filter { it in '0'..'9' }.take(AADHAAR_LENGTH)) },
            label = { Text(if (required) "Aadhaar number *" else "Aadhaar number") },
            placeholder = { Text("1234 5678 9012") },
            isError = error != null,
            supportingText = {
                if (error != null) {
                    Text(error)
                } else if (onRecord != null) {
                    Text("On record: $onRecord. Type a new number only to replace it.", color = Muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        if (required) {
                            "12 digits from the card. Stored securely and shown masked elsewhere."
                        } else {
                            "Optional for this older record — add only if the artisan agrees."
                        },
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = AadhaarGroupingTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
        )
        warning?.let { msg -> Text(msg, color = Coral, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CheckboxMultiSelectField(
    label: String,
    options: List<Pair<String, String>>,
    selectedIds: Set<String>,
    emptyMessage: String = "No options available.",
    server: ServerSearch? = null,
    onToggle: (String) -> Unit
) {
    CheckboxMultiSelectField(
        label = label,
        options = remember(options) { options.asSelectOptions() },
        selectedIds = selectedIds,
        emptyMessage = emptyMessage,
        server = server,
        onSelectedChange = { next ->
            (next - selectedIds).forEach(onToggle)
            (selectedIds - next).forEach(onToggle)
        }
    )
}

@Composable
private fun CheckboxMultiSelectField(
    label: String,
    options: List<SelectOption>,
    selectedIds: Set<String>,
    placeholder: String = "Select",
    emptyMessage: String = "No options available.",
    enabled: Boolean = true,
    server: ServerSearch? = null,
    onSelectedChange: (Set<String>) -> Unit
) {
    SearchableMultiSelectField(
        label = label,
        options = options,
        selected = selectedIds,
        placeholder = placeholder,
        emptyMessage = emptyMessage,
        enabled = enabled,
        server = server,
        onSelectedChange = onSelectedChange
    )
}

private enum class RecPhase { IDLE, RECORDING, PAUSED, RECORDED }

private enum class ActionStatus { IDLE, SUCCESS, ERROR }

private val SuccessGreen: Color get() = com.fieldrepository.app.ui.SuccessInk
private val FailureRed: Color get() = com.fieldrepository.app.ui.ErrorInk

@Composable
private fun AutoResetStatus(status: ActionStatus, onReset: () -> Unit) {
    LaunchedEffect(status) {
        if (status != ActionStatus.IDLE) {
            kotlinx.coroutines.delay(5000)
            onReset()
        }
    }
}

@Composable
private fun AudioClipRecorder(
    clips: List<Uri>,
    onAddClip: (Uri) -> Unit,
    onRemoveLast: () -> Unit,
    onError: (String) -> Unit,
    idleLabel: String = "Record ●"
) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf(RecPhase.IDLE) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { onAddClip(it) }
    }

    val addClip by rememberUpdatedState(onAddClip)

    fun startNew() {
        runCatching {
            val file = createAppFile(context, "question-audio-", ".m4a")
            val started = createAudioRecorder(context, file).also { it.start() }
            com.fieldrepository.app.legacyrecording.LegacyTake.begin(com.fieldrepository.app.legacyrecording.LegacyTake.Media(started), file)
            com.fieldrepository.app.legacyrecording.LegacyRecordingService.start(context)
            recorder = started
            recordingFile = file
            phase = RecPhase.RECORDING
        }.onFailure { onError(it.apiErrorMessage("Unable to start recording")); phase = RecPhase.IDLE }
    }

    fun finaliseTake(): Boolean {
        if (recorder == null) return false
        val file = com.fieldrepository.app.legacyrecording.LegacyTake.finalise()
        com.fieldrepository.app.legacyrecording.LegacyRecordingService.stop(context)
        recorder = null
        recordingFile = null
        if (file != null) addClip(uriForFile(context, file))
        return file != null
    }

    fun stopAndSave() {
        runCatching { if (!finaliseTake()) onError("Recording too short to keep") }
            .onFailure { onError(it.apiErrorMessage("Unable to stop recording")) }
        phase = RecPhase.RECORDED
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { finaliseTake() } }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (phase) {
            RecPhase.IDLE -> OutlinedButton(
                onClick = { startNew() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = pad
            ) { Text(if (clips.isNotEmpty()) "Record another ●" else idleLabel, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }

            RecPhase.RECORDING -> {
                ActionRow {
                    OutlinedButton(
                        onClick = { runCatching { com.fieldrepository.app.legacyrecording.LegacyTake.pause(); phase = RecPhase.PAUSED }.onFailure { onError(it.apiErrorMessage("Unable to pause")) } },
                        contentPadding = pad
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(onClick = { stopAndSave() }, contentPadding = pad) {
                        StopSquareLabel("Stop")
                    }
                }
            }

            RecPhase.PAUSED -> {
                ActionRow {
                    OutlinedButton(
                        onClick = { runCatching { com.fieldrepository.app.legacyrecording.LegacyTake.resume(); phase = RecPhase.RECORDING }.onFailure { onError(it.apiErrorMessage("Unable to resume")) } },
                        contentPadding = pad
                    ) { Text("Resume ●", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
                    OutlinedButton(onClick = { stopAndSave() }, contentPadding = pad) {
                        StopSquareLabel("Stop")
                    }
                }
            }

            RecPhase.RECORDED -> {
                Button(onClick = { onRemoveLast(); startNew() }, modifier = Modifier.fillMaxWidth(), contentPadding = pad) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Re-record", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = { startNew() }, modifier = Modifier.fillMaxWidth(), contentPadding = pad) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Record another", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = { onRemoveLast(); phase = RecPhase.IDLE }, modifier = Modifier.fillMaxWidth(), contentPadding = pad) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Discard", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (phase != RecPhase.RECORDING && phase != RecPhase.PAUSED) {
            OutlinedButton(
                onClick = { pickAudio.launch("audio/*") },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = pad
            ) {
                Icon(Icons.Filled.PermMedia, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add audio file", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
            }
            if (phase == RecPhase.IDLE && clips.isNotEmpty()) {
                TextButton(
                    onClick = onRemoveLast,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = pad
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove last clip", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (phase == RecPhase.RECORDING || phase == RecPhase.PAUSED) {
            RecordingIndicator(
                getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) },
                paused = phase == RecPhase.PAUSED
            )
        }
        Text(countNoun(clips.size, "clip"), color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun startHeldTake(context: Context, file: File): MediaRecorder {
    val started = createAudioRecorder(context, file).also { it.start() }
    com.fieldrepository.app.legacyrecording.LegacyTake.begin(com.fieldrepository.app.legacyrecording.LegacyTake.Media(started), file)
    com.fieldrepository.app.legacyrecording.LegacyRecordingService.start(context)
    return started
}

private fun stopHeldTake(context: Context): File? {
    val file = com.fieldrepository.app.legacyrecording.LegacyTake.finalise()
    com.fieldrepository.app.legacyrecording.LegacyRecordingService.stop(context)
    return file
}

@Composable
private fun StopSquareLabel(text: String = "Stop") {
    Box(
        modifier = Modifier
            .size(13.dp)
            .background(FailureRed, RoundedCornerShape(3.dp))
    )
    Spacer(Modifier.width(6.dp))
    Text(text, maxLines = 1, softWrap = false, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun MediaCaptureSection(
    repository: FieldRepository,
    media: MediaCaptureState,
    enableMeasurement: Boolean = false,
    emphasizeVideo: Boolean = false,
    beforeLocation: (@Composable () -> Unit)? = null,
    locationHeading: String = ARTISAN_LOCATION_HEADING,
    inheritPlace: InheritedPlace? = null,
    showKeepShootingToggle: Boolean = true,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMeasurement by remember { mutableStateOf(false) }

    LaunchedEffect(media.uris) {
        media.uris.forEach { uri ->
            if (!media.stagedDeferred.containsKey(uri)) {
                startEagerUpload(repository, context, media, uri, scope)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (recording) {
                runCatching { stopHeldTake(context)?.let { file -> media.uris = media.uris + uriForFile(context, file) } }
                recorder = null
                recording = false
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (media.uris.isNotEmpty()) {
                val pending = media.stagedDeferred.values.toList()
                AppScope.io.launch {
                    pending.forEach { d -> runCatching { d.await()?.let { repository.deleteStaged(it.objectKey) } } }
                }
            }
        }
    }

    val permissionGate = rememberPermissionGate(onRefused = onError)
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) media.uris = media.uris + uris
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) {
            if (pendingMeasurement) media.measurementUri = uri else media.uris = media.uris + uri
        }
        pendingCaptureUri = null
        pendingMeasurement = false
    }
    val camera = rememberKeepShootingCamera(
        newUri = { createAppFileUri(context, "field-photo-", ".jpg") },
        onShot = { uri -> media.uris = media.uris + uri },
        onRunEnded = onMessage
    )
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) media.uris = media.uris + uri
        pendingCaptureUri = null
    }
    val pickMeasurement = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) media.measurementUri = uri
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Attach media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Tick Transcribe and translate on a recording or video to get its text.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        ActionRow {
            OutlinedButton(onClick = { pickMedia.launch("*/*") }) { Text("Pick files") }
            OutlinedButton(
                onClick = { permissionGate.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) { camera.shoot() } },
            ) { Text("Take photo") }
        }
        if (showKeepShootingToggle) KeepShootingToggle()
        if (emphasizeVideo) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Videocam, contentDescription = null, tint = MaterialTheme.field.muted, modifier = Modifier.size(16.dp))
                Text("Video works best here.", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        ActionRow {
            if (emphasizeVideo) {
                Button(
                    onClick = {
                        permissionGate.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                            val uri = createAppFileUri(context, "field-video-", ".mp4")
                            pendingMeasurement = false
                            pendingCaptureUri = uri
                            takeVideo.launch(uri)
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { CardButtonLabel(Icons.Filled.Videocam, "Record video") }
            } else {
                OutlinedButton(
                    onClick = {
                        permissionGate.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                            val uri = createAppFileUri(context, "field-video-", ".mp4")
                            pendingMeasurement = false
                            pendingCaptureUri = uri
                            takeVideo.launch(uri)
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text("Record video", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
            }
            OutlinedButton(
                onClick = {
                    if (!recording && !permissionGate.holds(MICROPHONE_PERMISSION)) {
                        permissionGate.ask(MICROPHONE_PERMISSION, MICROPHONE_REFUSED_SENTENCE)
                    } else if (!recording) {
                        runCatching {
                            val file = createAppFile(context, "field-audio-", ".m4a")
                            recorder = startHeldTake(context, file)
                            recordingFile = file
                            recording = true
                            onMessage("Recording audio...")
                        }.onFailure { onError(it.apiErrorMessage("Unable to start audio recording")) }
                    } else {
                        runCatching {
                            stopHeldTake(context)?.let { file -> media.uris = media.uris + uriForFile(context, file) }
                        }.onFailure { onError(it.apiErrorMessage("Unable to stop audio recording")) }
                        recorder = null
                        recordingFile = null
                        recording = false
                        onMessage("Audio recording added")
                    }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) { if (recording) StopSquareLabel("Stop audio") else Text("Record audio ●", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
        }
        if (recording) {
            RecordingIndicator(getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) })
        }
        TracePanel(
            photograph = media.uris
                .lastOrNull { context.contentResolver.getType(it)?.startsWith("image/") == true },
            currentFileName = null,
            enabled = true,
            onAttach = { derived -> if (derived !in media.uris) media.uris = media.uris + derived },
            onMessage = onMessage,
            onError = onError,
        )
        beforeLocation?.invoke()
        LocationAddressEditor(
            repository = repository,
            value = media.location,
            onUseGps = {
                if (!holdsLocation(context)) locationLauncher.launch(LOCATION_PERMISSIONS)
                readLastKnownLocation(context)
            },
            onChange = { media.location = it },
            onMessage = onMessage,
            heading = locationHeading,
            inherit = inheritPlace
        )
        if (enableMeasurement) {
            HorizontalDivider()
            Text("Grid-sheet measurement image (optional)", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Fills length and breadth from the grid.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            ActionRow {
                OutlinedButton(
                    onClick = {
                        permissionGate.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                            val uri = createAppFileUri(context, "measure-grid-", ".jpg")
                            pendingMeasurement = true
                            pendingCaptureUri = uri
                            takePhoto.launch(uri)
                        }
                    },
                ) { Text("Capture grid") }
                OutlinedButton(onClick = { pickMeasurement.launch("image/*") }) { Text("Pick grid") }
            }
            media.measurementUri?.let { uri ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Measurement grid",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).background(SurfaceCard, RoundedCornerShape(8.dp))
                    )
                    Text("Grid image ready", color = Body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { media.measurementUri = null }) { Text("Remove") }
                }
            }
        }
        if (media.uris.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = MaterialTheme.field.brandTile, shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("${countNoun(media.uris.size, "file")} attached", color = MaterialTheme.field.onBrandTile, fontWeight = FontWeight.SemiBold)
                val overall = media.uris.map { (media.stagedProgress[it] ?: 0f).coerceIn(0f, 1f) }.average().toFloat()
                val allDone = media.staged.size >= media.uris.size
                val failedCount = media.uris.count { it in media.stagedFailed }
                val stalled = !allDone && media.staged.size + failedCount >= media.uris.size
                if (!allDone && !stalled) {
                    FieldLinearProgressIndicator(
                        progress = { overall },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = FieldProgressDefaults.onBrandTileIndicator,
                        trackColor = FieldProgressDefaults.onBrandTileTrack
                    )
                }
                Text(
                    when {
                        allDone -> "All uploaded ✓ — ready to save"
                        stalled -> uploadStalledSentence(failedCount)
                        else -> "Uploading… ${(overall * 100).toInt()}% (${media.staged.size}/${media.uris.size})"
                    },
                    color = MaterialTheme.field.onBrandTileMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                var attachmentsExpanded by remember { mutableStateOf(false) }
                val attachmentsCap = 6
                val shownAttachments = if (attachmentsExpanded) media.uris else media.uris.take(attachmentsCap)
                shownAttachments.forEach { uri ->
                    AndroidUriPreview(
                        context = context,
                        uri = uri,
                        progress = media.stagedProgress[uri],
                        failed = uri in media.stagedFailed,
                        onRetry = { startEagerUpload(repository, context, media, uri, scope) },
                        downloadable = true,
                        onTile = true,
                        onRemove = {
                            val deferred = media.stagedDeferred[uri]
                            media.forget(uri)
                            media.uris = media.uris.filterNot { it == uri }
                            AppScope.io.launch {
                                runCatching { deferred?.await()?.let { repository.deleteStaged(it.objectKey) } }
                            }
                        }
                    )
                }
                val tileInk = MaterialTheme.field.onBrandTile
                if (media.uris.size > attachmentsCap) {
                    ActionRow {
                        TextButton(
                            onClick = { attachmentsExpanded = !attachmentsExpanded },
                            colors = ButtonDefaults.textButtonColors(contentColor = tileInk)
                        ) {
                            Text(if (attachmentsExpanded) "Show fewer" else "+${media.uris.size - attachmentsCap} more", color = tileInk)
                        }
                    }
                }
                ActionRow {
                    OutlinedButton(
                        border = BorderStroke(1.dp, tileInk.copy(alpha = TILE_OUTLINE_ALPHA)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = tileInk),
                        onClick = {
                            val pending = media.stagedDeferred.values.toList()
                            media.stagedDeferred.clear()
                            media.staged = emptyMap()
                            media.stagedProgress = emptyMap()
                            media.stagedFailed = emptySet()
                            media.uris = emptyList()
                            AppScope.io.launch {
                                pending.forEach { d -> runCatching { d.await()?.let { repository.deleteStaged(it.objectKey) } } }
                            }
                        }
                    ) { Text("Clear attachments", color = tileInk, fontWeight = FontWeight.SemiBold) }
                }
            }
            UriTranscribeChoices(media.uris)
        }
    }
}

@Composable
private fun UriTranscribeChoices(uris: List<Uri>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items = remember(uris) {
        uris.map { uri ->
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
            TranscribeChoiceItem(
                key = uri.toString(),
                name = name ?: uri.lastPathSegment ?: "File",
                typeOrMime = context.contentResolver.getType(uri),
            )
        }
    }
    TranscribeChoiceRows(items, modifier)
}

@Composable
private fun MediaStagingEffect(repository: FieldRepository, media: MediaCaptureState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(media.uris) {
        media.uris.forEach { uri ->
            if (!media.stagedDeferred.containsKey(uri)) {
                startEagerUpload(repository, context, media, uri, scope)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (media.uris.isNotEmpty()) {
                val pending = media.stagedDeferred.values.toList()
                AppScope.io.launch {
                    pending.forEach { d -> runCatching { d.await()?.let { repository.deleteStaged(it.objectKey) } } }
                }
            }
        }
    }
}

@Composable
private fun AttachedUploadsCard(
    context: Context,
    media: MediaCaptureState,
    label: String,
    repository: FieldRepository,
    uris: List<Uri> = media.uris,
    onRemove: (Uri) -> Unit
) {
    if (uris.isEmpty()) return
    val scope = rememberCoroutineScope()
    val doneCount = uris.count { media.staged.containsKey(it) }
    val allDone = doneCount >= uris.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = MaterialTheme.field.brandTile, shape = RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("${countNoun(uris.size, label)} attached", color = MaterialTheme.field.onBrandTile, fontWeight = FontWeight.SemiBold)
        val overall = uris.map { (media.stagedProgress[it] ?: 0f).coerceIn(0f, 1f) }.average().toFloat()
        val failedCount = uris.count { it in media.stagedFailed }
        val stalled = !allDone && doneCount + failedCount >= uris.size
        if (!allDone && !stalled) {
            FieldLinearProgressIndicator(
                progress = { overall },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = FieldProgressDefaults.onBrandTileIndicator,
                trackColor = FieldProgressDefaults.onBrandTileTrack
            )
        }
        Text(
            when {
                allDone -> "All uploaded ✓ — ready to save"
                stalled -> uploadStalledSentence(failedCount)
                else -> "Uploading… ${(overall * 100).toInt()}% ($doneCount/${uris.size})"
            },
            color = MaterialTheme.field.onBrandTileMuted,
            style = MaterialTheme.typography.bodySmall
        )
        var expanded by remember { mutableStateOf(false) }
        val cap = 8
        val shown = if (expanded) uris else uris.take(cap)
        shown.forEach { uri ->
            AndroidUriPreview(
                context = context,
                uri = uri,
                progress = media.stagedProgress[uri],
                failed = uri in media.stagedFailed,
                onRetry = { startEagerUpload(repository, context, media, uri, scope) },
                downloadable = true,
                onTile = true,
                onRemove = { onRemove(uri) }
            )
        }
        if (uris.size > cap) {
            val tileInk = MaterialTheme.field.onBrandTile
            ActionRow {
                TextButton(
                    onClick = { expanded = !expanded },
                    colors = ButtonDefaults.textButtonColors(contentColor = tileInk)
                ) {
                    Text(if (expanded) "Show fewer" else "+${uris.size - cap} more", color = tileInk)
                }
            }
        }
    }
}

@Composable
private fun LoadingCard(mode: EntryMode, error: String? = null, onRetry: () -> Unit = {}) {
    if (error != null) {
        LoadFailedCard(message = error, onRetry = onRetry)
        return
    }
    RecordCard(title = "Loading ${mode.label.lowercase()}") {
        Text("Loading…", color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RecordPickerScreen(
    repository: FieldRepository,
    mode: EntryMode,
    onPick: (String) -> Unit,
    onView: (String) -> Unit,
    onError: (String) -> Unit,
    chrome: LazyPageChrome
) {
    var selected by rememberKept("picker.${mode.name}.selected") { "" }

    val workshopScope = rememberWorkshopScope(repository = repository, onError = onError)
    val isInterviews = mode == EntryMode.QUESTIONNAIRE
    val scopeSettled = !isInterviews || workshopScope.settled
    val scopeKey = if (isInterviews) workshopScope.requestKey else ""

    val context = LocalContext.current
    RefreshCompletenessRules(repository)
    val completenessKind = CompletenessKind.forMode(mode.name)
    var completenessFilter by remember(mode) {
        mutableStateOf(if (completenessKind != null) CompletenessFilterMemory.read(context, mode.name) else CompletenessFilter.ALL)
    }
    val mediaV2 = remember { com.fieldrepository.app.questionnaire.data.QuestionnaireV2.get(context) }
    val (mediaFilters, setMediaFilters) = com.fieldrepository.app.questionnaire.ui.rememberKeptFilters(mediaV2, com.fieldrepository.app.questionnaire.data.FilterStore.MEDIA)
    val mediaParams = if (mode == EntryMode.MEDIA) mediaFilters.mediaParams() else emptyMap()
    val rows = rememberRecordRows(
        repository,
        mode,
        workshopIds = if (isInterviews) workshopScope.workshopIds else EVERY_WORKSHOP,
        key = listOf(mode, scopeKey, completenessFilter, mediaParams),
        enabled = scopeSettled,
        completeness = completenessFilter.wire,
        mediaFilters = mediaParams,
        keepAs = "picker.${mode.name}.rows"
    )
    val completenessById = remember(rows.browse) { rows.browse.associate { it.id to it.completeness } }
    val chipsById = remember(rows.browse) { rows.browse.associate { it.id to it.chips } }
    val previewById = remember(rows.browse) { rows.browse.associate { it.id to it.preview } }
    val loading = !rows.browseLoaded && rows.note == null
    val failed = !rows.browseLoaded && rows.note != null && !rows.loading
    val options = recordRowOptions(mode, rows.known).map { it.value to it.label }
    val cards = recordRowOptions(mode, rows.browse)
    val browseHasMore = rows.browseLoaded && rows.browse.size < rows.browseTotal

    val switchKind = recordSwitchKindFor(mode)
    val switch = if (switchKind != null) rememberUpdatePickerSwitch(repository, switchKind) else null

    PageLazyColumn(chrome) {
        if (switchKind != null && switch != null) {
            item(key = "switcher", contentType = "card") {
                RecordCard(title = recordSwitcherTitle(switchKind)) {
                    RecordSwitcher(
                        kind = switchKind,
                        workshop = switch.first,
                        switcher = switch.second,
                        currentId = "",
                        onPick = onPick,
                        currentWorkshopId = null
                    )
                }
            }
        }

        item(key = "picker", contentType = "card") {
            RecordCard(title = "Update existing ${mode.label.lowercase()}") {
                Text("Pick a record to update, or tap a recent one to read it.", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (isInterviews) {
                    WorkshopScopeSelect(scope = workshopScope, label = "Workshops in this list")
                }
                if (completenessKind != null) {
                    CompletenessFilterChips(value = completenessFilter) { next ->
                        completenessFilter = next
                        CompletenessFilterMemory.write(context, mode.name, next)
                    }
                }
                if (mode == EntryMode.MEDIA) com.fieldrepository.app.questionnaire.ui.FilterBar(
                    mediaFilters, setMediaFilters, "",
                    options = { facet, q, ids -> mediaV2.filterOptions(facet, q, ids) },
                    fields = com.fieldrepository.app.questionnaire.ui.FilterField.MEDIA, showSearch = false
                )
                when {
                    loading -> Text("Loading ${mode.label.lowercase()} records…", color = Muted)
                    failed -> LoadFailedCard(
                        message = "Couldn't load the ${mode.label.lowercase()} records.",
                        onRetry = { rows.retry() }
                    )
                    rows.browseTotal == 0 && isInterviews && !workshopScope.isAllRecords ->
                        Text("No interviews in these workshops yet.", color = Muted)
                    rows.browseTotal == 0 && rows.browseLoaded ->
                        Text("No ${mode.label.lowercase()} records found yet.", color = Muted)
                    else -> {
                        DropdownField(
                            label = "Select ${mode.label.lowercase()}",
                            options = options,
                            selectedValue = selected,
                            placeholder = "Select a record",
                            includeNone = false,
                            server = rows.asServerSearchRows(countsRows = !isInterviews) { recordRowOptions(mode, it) },
                            onSelect = { selected = it }
                        )
                        rows.note?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                        Button(
                            onClick = { if (selected.isNotBlank()) onPick(selected) },
                            enabled = selected.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(RECORD_UPDATE_LABEL) }
                    }
                }
            }
        }

        if (cards.isNotEmpty()) {
            item(key = "cards-label", contentType = "note") {
                Text("Or tap a recent record to read it", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        items(cards, key = { "record:${it.value}" }, contentType = { "recordCard" }) { option ->
            val id = option.value
            val label = option.label
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Open") { onView(id) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label, color = Body, style = MaterialTheme.typography.bodyMedium)
                        previewById[id]?.let { com.fieldrepository.app.transcript.ui.TranscriptPreviewText(it, maxLines = 2) }
                        com.fieldrepository.app.questionnaire.ui.RecordingChips(chipsById[id], filters = mediaFilters, onChip = { f, l ->
                            if (mediaFilters.appliesToMedia(f)) setMediaFilters(mediaFilters.applyChip(f, l))
                        })
                    }
                    completenessById[id]?.let { block ->
                        CompletenessRing(
                            percent = block.percent,
                            size = 18.dp,
                            stroke = FieldProgressDefaults.MinThickness,
                            showLabel = false,
                            description = completenessSentence(block),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = { onPick(id) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.semantics { contentDescription = "$RECORD_UPDATE_LABEL $label" }
                    ) { Text(RECORD_UPDATE_LABEL, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (browseHasMore) {
            item(key = "more", contentType = "more") {
                LoadMoreRow(loading = rows.loading, onLoadMore = { rows.loadMore() })
            }
        }
    }
}

@Composable
private fun rememberUpdatePickerSwitch(
    repository: FieldRepository,
    kind: RecordSwitchKind
): Pair<WorkshopPickerState, RecordSwitcherState> {
    val switchWorkshop = rememberWorkshopPicker(repository, isEdit = true, initialId = null)
    return switchWorkshop to rememberRecordSwitcher(repository, kind, switchWorkshop)
}

@Composable
private fun LoadMoreRow(loading: Boolean, onLoadMore: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        OutlinedButton(onClick = onLoadMore, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (loading) "Loading…" else "Load more")
        }
    }
}


private suspend fun deleteByMode(repository: FieldRepository, mode: EntryMode, id: String) {
    when (mode) {
        EntryMode.ARTISAN -> repository.deleteArtisan(id)
        EntryMode.PRODUCT -> repository.deleteProduct(id)
        EntryMode.PROCESS -> repository.deleteProcess(id)
        EntryMode.TOOL -> repository.deleteTool(id)
        EntryMode.WORKSHOP -> repository.deleteWorkshop(id)
        EntryMode.CRAFT -> repository.deleteCraft(id)
        EntryMode.QUESTIONNAIRE -> repository.deleteInterview(id)
        EntryMode.MEDIA -> repository.deleteMedia(id)
        else -> throw IllegalArgumentException("This record type cannot be deleted")
    }
}

private val DELETABLE_MODES = setOf(
    EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.TOOL, EntryMode.WORKSHOP,
    EntryMode.CRAFT, EntryMode.QUESTIONNAIRE, EntryMode.MEDIA,
)

private fun deleteNoun(mode: EntryMode): String =
    if (mode == EntryMode.MEDIA) "media file" else mode.label.lowercase()

@Composable
private fun DeleteRecordSection(
    repository: FieldRepository,
    mode: EntryMode,
    recordId: String,
    onDeleted: () -> Unit,
    onError: (String) -> Unit
) {
    RecordDangerZoneCard(
        noun = deleteNoun(mode),
        delete = { deleteByMode(repository, mode, recordId) },
        onDeleted = onDeleted,
        onError = { onError(it.apiErrorMessage(RecordDangerZone.FAILED)) },
    )
}

private fun recordSwitchKindFor(mode: EntryMode): RecordSwitchKind? = when (mode) {
    EntryMode.ARTISAN -> RecordSwitchKind.ARTISAN
    EntryMode.CRAFT -> RecordSwitchKind.CRAFT
    EntryMode.PROCESS -> RecordSwitchKind.PROCESS
    EntryMode.PRODUCT -> RecordSwitchKind.PRODUCT
    EntryMode.TOOL -> RecordSwitchKind.TOOL
    else -> null
}

@Composable
private fun EditScreen(
    repository: FieldRepository,
    mode: EntryMode,
    recordId: String,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    craftSeed: PageResponse<CraftDto>? = null,
    artisanSeed: PageResponse<ArtisanDto>? = null,
    adminView: Boolean,
    canDelete: Boolean,
    onDone: () -> Unit,
    onError: (String) -> Unit,
    onOpenRecord: (String) -> Unit = {}
) {
    var currentWorkshopId by remember(recordId) { mutableStateOf<String?>(null) }
    var currentStatus by remember(recordId) { mutableStateOf<String?>(null) }
    var loadError by remember(recordId) { mutableStateOf<String?>(null) }
    var loadAttempt by remember(recordId) { mutableIntStateOf(0) }
    val retryLoad = { loadError = null; loadAttempt += 1 }

    val switchKind = recordSwitchKindFor(mode)
    if (switchKind != null) {
        val switchWorkshop = rememberWorkshopPicker(repository, isEdit = true, initialId = null)
        val switcher = rememberRecordSwitcher(repository, switchKind, switchWorkshop)
        RecordCard(title = recordSwitcherTitle(switchKind)) {
            RecordSwitcher(
                kind = switchKind,
                workshop = switchWorkshop,
                switcher = switcher,
                currentId = recordId,
                onPick = onOpenRecord,
                currentWorkshopId = currentWorkshopId
            )
        }
    }

    ReviewFeedbackCopy.recordTypeFor(mode.name)?.let { type ->
        ReviewNoteBanner(repository = repository, recordType = type, recordId = recordId, knownStatus = currentStatus)
    }

    when (mode) {
        EntryMode.ARTISAN -> {
            var detail by remember(recordId) { mutableStateOf<ArtisanDetailDto?>(null) }
            var answers by remember(recordId) { mutableStateOf<List<ArtisanAnswerDto>>(emptyList()) }
            var answersLoading by remember(recordId) { mutableStateOf(true) }
            LaunchedEffect(recordId, loadAttempt) {
                runCatching { repository.artisan(recordId) }
                    .onSuccess { detail = it; currentWorkshopId = it.workshopId; currentStatus = it.status }
                    .onFailure { loadError = it.apiErrorMessage("Couldn\'t load this artisan.") }
                runCatching { repository.artisanQuestionnaire(recordId) }
                    .onSuccess { answers = it.answered }
                answersLoading = false
            }
            val d = detail
            if (d == null) {
                LoadingCard(mode, loadError, retryLoad)
            } else {
                ArtisanForm(
                    repository = repository,
                    crafts = crafts,
                    craftSeed = craftSeed,
                    editing = d,
                    adminView = adminView,
                    onDone = onDone,
                    onError = onError
                )
                ArtisanQuestionnairePanel(answers = answers, loading = answersLoading)
            }
        }
        EntryMode.PRODUCT -> {
            var detail by remember(recordId) { mutableStateOf<ProductDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) {
                runCatching { repository.product(recordId) }
                    .onSuccess { detail = it; currentWorkshopId = it.workshopId; currentStatus = it.status }
                    .onFailure { loadError = it.apiErrorMessage("Couldn\'t load this product.") }
            }
            val d = detail
            if (d == null) LoadingCard(mode, loadError, retryLoad) else ProductForm(
                repository = repository,
                crafts = crafts,
                artisans = artisans,
                craftSeed = craftSeed,
                artisanSeed = artisanSeed,
                editing = d,
                adminView = adminView,
                onDone = onDone,
                onError = onError
            )
        }
        EntryMode.PROCESS -> {
            var detail by remember(recordId) { mutableStateOf<ProcessDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) {
                runCatching { repository.process(recordId) }
                    .onSuccess { detail = it; currentWorkshopId = it.workshopId; currentStatus = it.status }
                    .onFailure { loadError = it.apiErrorMessage("Couldn\'t load this process.") }
            }
            val d = detail
            if (d == null) LoadingCard(mode, loadError, retryLoad) else ProcessForm(
                repository = repository,
                editing = d,
                adminView = adminView,
                onDone = onDone,
                onError = onError
            )
        }
        EntryMode.TOOL -> {
            var detail by remember(recordId) { mutableStateOf<ToolDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) {
                runCatching { repository.tool(recordId) }
                    .onSuccess { detail = it; currentWorkshopId = it.workshopId; currentStatus = it.status }
                    .onFailure { loadError = it.apiErrorMessage("Couldn\'t load this tool.") }
            }
            val d = detail
            if (d == null) LoadingCard(mode, loadError, retryLoad) else ToolForm(
                repository = repository,
                crafts = crafts,
                artisans = artisans,
                craftSeed = craftSeed,
                artisanSeed = artisanSeed,
                editing = d,
                adminView = adminView,
                onDone = onDone,
                onError = onError
            )
        }
        EntryMode.WORKSHOP -> {
            var detail by remember(recordId) { mutableStateOf<WorkshopDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) {
                runCatching { repository.workshop(recordId) }
                    .onSuccess { detail = it; currentStatus = it.status }
                    .onFailure { loadError = it.apiErrorMessage("Couldn\'t load this workshop.") }
            }
            val d = detail
            if (d == null) LoadingCard(mode, loadError, retryLoad) else WorkshopForm(
                repository = repository,
                artisans = artisans,
                artisanSeed = artisanSeed,
                editing = d,
                adminView = adminView,
                onDone = onDone,
                onError = onError
            )
        }
        EntryMode.CRAFT -> {
            var detail by remember(recordId) { mutableStateOf<CraftDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) {
                runCatching { repository.craft(recordId) }
                    .onSuccess { detail = it; currentWorkshopId = it.workshopId }
                    .onFailure { loadError = it.apiErrorMessage("Couldn\'t load this craft.") }
            }
            val d = detail
            if (d == null) LoadingCard(mode, loadError, retryLoad) else CraftForm(
                repository = repository,
                editing = d,
                adminView = adminView,
                onDone = onDone,
                onError = onError
            )
        }
        EntryMode.MEDIA -> ViewDataDetail(
            repository = repository,
            mode = EntryMode.MEDIA,
            recordId = recordId,
            onError = onError
        )
        else -> Text("This record type cannot be edited here.", color = Muted)
    }
    if (canDelete && mode != EntryMode.MEDIA && mode != EntryMode.USERS && mode != EntryMode.VIEW_DATA) {
        DeleteRecordSection(repository, mode, recordId, onDeleted = onDone, onError = onError)
    }
}

@Composable
private fun InterviewEditLoader(
    repository: FieldRepository,
    recordId: String,
    sections: List<QuestionnaireSectionDto>,
    artisans: List<ArtisanDto>,
    canManageQuestionnaire: Boolean,
    adminView: Boolean,
    canDelete: Boolean,
    onRefreshSections: suspend (String?) -> Unit,
    onError: (String) -> Unit,
    onDone: () -> Unit
) {
    var detail by remember(recordId) { mutableStateOf<QuestionnaireInterviewDetailDto?>(null) }
    var sectionsReady by remember(recordId) { mutableStateOf(false) }
    var sectionsError by remember(recordId) { mutableStateOf<String?>(null) }
    var loadError by remember(recordId) { mutableStateOf<String?>(null) }
    var loadAttempt by remember(recordId) { mutableIntStateOf(0) }
    LaunchedEffect(recordId, loadAttempt) {
        runCatching { repository.interview(recordId) }
            .onSuccess { loaded ->
                detail = loaded
                runCatching { onRefreshSections(loaded.questionnaireId) }
                    .onSuccess { sectionsReady = true }
                    .onFailure {
                        sectionsError = it.apiErrorMessage("Couldn't load this interview's questionnaire.")
                    }
            }
            .onFailure { loadError = it.apiErrorMessage("Couldn't load this interview.") }
    }
    val d = detail
    val failure = sectionsError
    if (failure != null) {
        LoadFailedCard(
            message = "This interview's questions could not be loaded. $failure",
            onRetry = { sectionsError = null; sectionsReady = false; detail = null; loadAttempt += 1 }
        )
    } else if (d == null || !sectionsReady) {
        LoadingCard(EntryMode.QUESTIONNAIRE, loadError, { loadError = null; loadAttempt += 1 })
    } else {
        QuestionnaireForm(
            repository = repository,
            sections = sections,
            artisans = artisans,
            canManageQuestionnaire = canManageQuestionnaire,
            editing = d,
            adminView = adminView,
            onRefreshSections = onRefreshSections,
            onSubmit = { _, _ -> error("An open edit must PATCH the interview, never create a second one.") },
            onError = onError,
            onSaved = onDone
        )
        if (canDelete) {
            DeleteRecordSection(repository, EntryMode.QUESTIONNAIRE, recordId, onDeleted = onDone, onError = onError)
        }
    }
}

@Composable
private fun CraftForm(
    repository: FieldRepository,
    editing: CraftDto? = null,
    adminView: Boolean = false,
    onCreated: ((Prefill, String?, Boolean) -> Unit)? = null,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = rememberFormDraft(repository, "craft", editing?.id)
    val media = rememberMediaCaptureState()
    val isEdit = editing != null
    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }.drafted(draft, "name")
    var localName by remember(editing) { mutableStateOf(editing?.localName ?: "") }.drafted(draft, "localName")
    val craftCatalogue by CraftCatalogueCache.catalogue.collectAsState()
    val craftListContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CraftCatalogueCache.cached(craftListContext) } }
    var subCategoryId by remember(editing) { mutableStateOf(editing?.subCategoryId ?: "") }.drafted(draft, "subCategoryId")
    var categoryId by remember(editing) {
        mutableStateOf(editing?.categoryId ?: craftCatalogue?.subCategoryParent(editing?.subCategoryId) ?: "")
    }.drafted(draft, "categoryId")
    var subCategoryError by remember { mutableStateOf<String?>(null) }
    val subCategoryFocus = remember { FocusRequester() }
    var stateOfOrigin by remember(editing) { mutableStateOf(editing?.stateOfOrigin.orEmpty().toSet()) }.drafted(draft, "stateOfOrigin")
    var stateOfPractice by remember(editing) { mutableStateOf(editing?.stateOfPractice.orEmpty().toSet()) }.drafted(draft, "stateOfPractice")
    var giStatus by remember(editing) { mutableStateOf(editing?.giStatus ?: "NON_GI") }.drafted(draft, "giStatus")
    var description by remember(editing) { mutableStateOf(editing?.description ?: "") }.drafted(draft, "description")
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing, runningOnly = true)
    var saving by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }
    var another by remember { mutableStateOf(false) }
    fun finish(craftId: String?, queued: Boolean) {
        val created = onCreated
        if (isEdit || created == null || (queued && !another)) { onDone(); return }
        created(Prefill(craftId = craftId, craftName = name.trim()), name.trim(), another)
    }

    fun submit() {
        if (!validateRequired(listOf(
                RequiredCheck(name.isBlank(), { nameError = it }, nameFocus),
                RequiredCheck(subCategoryId.isBlank(), { subCategoryError = it }, subCategoryFocus)
            ))) { onError("Fill in the highlighted field."); return }
        scope.launch {
            if (!workshop.confirmSubmission()) return@launch
            saving = true
            val body = CraftCreateRequest(
                name = name.trim(),
                localName = localName.blankToNull(),
                subCategoryId = subCategoryId.blankToNull(),
                stateOfOrigin = CraftCatalogueCache.STATE_OPTIONS.filter { it in stateOfOrigin },
                stateOfPractice = CraftCatalogueCache.STATE_OPTIONS.filter { it in stateOfPractice },
                giStatus = giStatus,
                place = editing?.place,
                description = description.blankToNull(),
                workshopId = workshop.value(),
                recordedAt = if (isEdit) null else Instant.now().toString()
            )
            val queuedOffline = runCatching {
                trySaveOffline(repository, context, editing?.id, "craft", offlineFormJson.encodeToString(body),
                    name.trim(), media, name.trim(), "Field media for ${name.trim()}")
            }.getOrElse { false }
            if (queuedOffline) {
                media.reset()
                onError("Saved offline. Uploads when you're online.")
                draft.clear(); finish(null, queued = true)
                saving = false
                return@launch
            }
            runCatching {
                val craftId = if (isEdit) {
                    repository.updateCraft(editing!!.id, body).id
                } else {
                    createOrKeep(repository, context, "craft", offlineFormJson.encodeToString(body), name.trim(),
                        { media.offlineSpecs(name.trim(), "Field media for ${name.trim()}") }) { key -> repository.createCraft(body, key) }.id
                }
                uploadAttachments(repository, context, media, "craft", craftId, name, "Field media for ${name.trim()}")
                craftId
            }.onSuccess { craftId ->
                media.reset()
                draft.clear(); finish(craftId, queued = false)
            }.onFailure { if (it is KeptOnDevice) { media.reset(); draft.clear(); onError(it.message!!); finish(null, queued = true) } else onError(it.apiErrorMessage("Unable to save craft")) }
            saving = false
        }
    }
    val initialSig = remember(editing) { listOf(name, localName, subCategoryId, stateOfOrigin.sorted().joinToString(","), stateOfPractice.sorted().joinToString(","), giStatus, description).joinToString("") }
    val dirty = !saving && (
        listOf(name, localName, subCategoryId, stateOfOrigin.sorted().joinToString(","), stateOfPractice.sorted().joinToString(","), giStatus, description).joinToString("") != initialSig ||
            workshop.isDirty() || media.uris.isNotEmpty() || media.measurementUri != null
    )

    val dictates = recordDictationFor(RecordFormKind.CRAFT)
    RecordCard(title = if (isEdit) recordUpdateTitle("craft") else "Add craft") {
        DraftOfferCard(draft)
        RegisterUnsavedGuard(dirty = dirty) { another = false; submit() }
        media.draftInto(draft, "media")
        workshop.draftInto(draft)
        FormDraftAutosave(draft, dirty, label = name)
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        WorkshopField(state = workshop, saving = saving)
        RequiredInput("Craft name", name, nameError, nameFocus, titleCased = true, dictate = dictates("name")) { name = it }
        TextInput("Local name", localName, dictate = dictates("localName")) { localName = it }
        SearchableSelectField(
            label = "Category",
            options = craftCatalogue?.categories.orEmpty().map { SelectOption(it.id, it.name) },
            selectedValue = categoryId,
            placeholder = if (craftCatalogue == null) CraftCatalogueCache.OFFLINE_FIRST_RUN else "Choose a category",
            includeNone = false
        ) { picked ->
            if (picked != categoryId) subCategoryId = ""
            categoryId = picked
        }
        SearchableSelectField(
            label = "Sub-category *",
            options = craftCatalogue?.categories.orEmpty().firstOrNull { it.id == categoryId }?.subCategories.orEmpty()
                .map { SelectOption(it.id, it.name) },
            selectedValue = subCategoryId,
            placeholder = if (categoryId.isBlank()) "Choose a category first" else "Choose a sub-category",
            includeNone = false,
            enabled = categoryId.isNotBlank(),
            modifier = Modifier.focusRequester(subCategoryFocus)
        ) { picked ->
            subCategoryId = picked
            subCategoryError = null
        }
        subCategoryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        SearchableMultiSelectField(
            label = "State of origin",
            options = CraftCatalogueCache.STATE_OPTIONS.map { SelectOption(it, it) },
            selected = stateOfOrigin,
            placeholder = "Choose states"
        ) { stateOfOrigin = it }
        SearchableMultiSelectField(
            label = "State of practice",
            options = CraftCatalogueCache.STATE_OPTIONS.map { SelectOption(it, it) },
            selected = stateOfPractice,
            placeholder = "Choose states"
        ) { stateOfPractice = it }
        SegmentedChoice(
            label = "GI status",
            options = listOf(SelectOption("GI", "GI"), SelectOption("NON_GI", "Non GI")),
            selectedValue = giStatus
        ) { giStatus = it }
        TextInput("Description", description, minLines = 3, dictate = dictates("description"), rich = true) { description = it }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "craft", recordId = editing!!.id, onError = onError)
        }
        MediaCaptureSection(repository = repository, media = media, locationHeading = "Craft location", onMessage = onError, onError = onError)
        SaveButtons(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update craft" else "Save craft",
            onSave = { another = false; submit() },
            onSaveAnother = if (!isEdit && onCreated != null) ({ another = true; submit() }) else null
        )
    }
}

@Composable
private fun ArtisanForm(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    lookupState: CarryScopeState = CarryScopeState.PENDING,
    craftSeed: PageResponse<CraftDto>? = null,
    editing: ArtisanDetailDto? = null,
    prefill: Prefill? = null,
    adminView: Boolean = false,
    onArtisanCreated: ((Prefill, Boolean) -> Unit)? = null,
    onOpenExisting: ((String) -> Unit)? = null,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = rememberFormDraft(repository, "artisan", editing?.id, prefill.draftParentId(), prefill.draftParentLabel())
    val media = rememberMediaCaptureState()
    val isEdit = editing != null
    var name by remember(editing) { mutableStateOf(editing?.name ?: prefill?.artisanName ?: "") }.drafted(draft, "name")
    var localName by remember(editing) { mutableStateOf(editing?.localName ?: "") }.drafted(draft, "localName")
    var gender by remember(editing) { mutableStateOf(editing?.gender?.takeIf { it.isNotBlank() } ?: "") }.drafted(draft, "gender")
    var phone by remember(editing) {
        mutableStateOf(StandardValues.phoneForEditing(editing?.phoneCountryCode, editing?.phone))
    }.drafted(draft, "phone")
    var email by remember(editing) { mutableStateOf(editing?.email ?: "") }.drafted(draft, "email")
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }.drafted(draft, "place")
    var address by remember(editing) { mutableStateOf(editing?.address ?: "") }.drafted(draft, "address")
    var notes by remember(editing) { mutableStateOf(editing?.notes ?: "") }.drafted(draft, "notes")
    val storedAadhaarMask = editing?.aadhaarNumber?.takeIf { it.isNotBlank() }
    var aadhaar by remember(editing) {
        mutableStateOf(editing?.aadhaarNumber?.takeIf { aadhaarValidationError(it) == null }.orEmpty())
    }.drafted(draft, "aadhaar")
    var pehchanAvailable by remember(editing) { mutableStateOf<Boolean?>(editing?.pehchanCardAvailable) }.drafted(draft, "pehchanAvailable")
    var aadhaarNotShared by remember(editing) { mutableStateOf(editing?.aadhaarNotShared ?: false) }.drafted(draft, "aadhaarNotShared")
    val notSharedOffered = aadhaarNotSharedOffered(editing?.aadhaarNumber)
    val notSharedHonoured = aadhaarNotShared && notSharedOffered
    var lookupSettled by remember(editing) { mutableStateOf(editing != null) }.drafted(draft, "lookupSettled")
    var pehchanNumber by remember(editing) { mutableStateOf(editing?.pehchanCardNumber.orEmpty()) }.drafted(draft, "pehchanNumber")
    var dateOfBirth by remember(editing) {
        mutableStateOf<java.time.LocalDate?>(
            editing?.dateOfBirth?.take(10)?.let { parseFieldDate(it) }
        )
    }.drafted(draft, "dateOfBirth")
    var craftStartDate by remember(editing) {
        mutableStateOf<java.time.LocalDate?>(
            editing?.craftStartDate?.take(10)?.let { parseFieldDate(it) }
        )
    }.drafted(draft, "craftStartDate")
    var experienceYears by remember(editing) {
        mutableStateOf(editing?.experienceYears?.toString().orEmpty())
    }.drafted(draft, "experienceYears")
    var experienceMonths by remember(editing) {
        mutableStateOf(editing?.experienceMonths?.toString().orEmpty())
    }.drafted(draft, "experienceMonths")
    var dosItems by remember(editing) { mutableStateOf(splitNumbered(editing?.dos)) }.drafted(draft, "dosItems")
    var dontsItems by remember(editing) { mutableStateOf(splitNumbered(editing?.donts)) }.drafted(draft, "dontsItems")
    var craftId by remember(editing) { mutableStateOf(editing?.craftId ?: prefill?.craftId ?: "") }.drafted(draft, "craftId")
    val craftCatalogue by CraftCatalogueCache.catalogue.collectAsState()
    val craftOptions = remember(craftCatalogue) { craftCatalogue?.crafts?.map { CraftDto(id = it.id, name = it.name) }.orEmpty() }
    val craftListState = if (craftCatalogue != null) CarryScopeState.LOADED else CarryScopeState.UNAVAILABLE
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing, runningOnly = true)
    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.ARTISAN_FORM,
        scopes = listOf(carryScope(CarryNode.CRAFT, craftListState, craftOptions) { it.id }),
        handoff = prefill
    ) { carried ->
        carried.craftId?.let { craftId = it }
        carried.workshopId?.let { if (!workshop.isDirty()) workshop.applyDefault(it) }
    }
    fun clearCarriedContext() {
        carry.change()
        craftId = ""
    }
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }.drafted(draft, "status")
    var saving by remember { mutableStateOf(false) }
    val hasCraft = craftId.isNotBlank()
    var nameError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    var craftError by remember { mutableStateOf<String?>(null) }
    var dosError by remember { mutableStateOf<String?>(null) }
    var dontsError by remember { mutableStateOf<String?>(null) }
    var phoneError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var aadhaarError by remember { mutableStateOf<String?>(null) }
    var duplicateShown by remember(editing) { mutableStateOf<List<DuplicateCandidateDto>?>(null) }
    var duplicateAcked by remember(editing) { mutableStateOf<Set<String>>(emptySet()) }
    var pehchanError by remember { mutableStateOf<String?>(null) }
    var dateOfBirthError by remember { mutableStateOf<String?>(null) }
    var craftStartDateError by remember { mutableStateOf<String?>(null) }
    var aadhaarDuplicate by remember(editing) { mutableStateOf<String?>(null) }
    val keepsStoredAadhaar = isEdit && storedAadhaarMask != null && aadhaar.isBlank()
    val aadhaarRequired = (!isEdit || !editing?.aadhaarNumber.isNullOrBlank()) && !notSharedHonoured && !keepsStoredAadhaar
    val nameFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }
    val craftFocus = remember { FocusRequester() }
    val dosFocus = remember { FocusRequester() }
    val dontsFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val aadhaarFocus = remember { FocusRequester() }
    val pehchanFocus = remember { FocusRequester() }

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }

    LaunchedEffect(aadhaar, editing?.id, notSharedHonoured) {
        aadhaarDuplicate = null
        if (notSharedHonoured || aadhaar.isBlank() || aadhaarValidationError(aadhaar) != null) return@LaunchedEffect
        delay(400)
        val match = runCatching { repository.lookupArtisanByAadhaar(aadhaar) }.getOrNull()?.artisan
        if (match == null || match.id == editing?.id) return@LaunchedEffect
        val where = listOfNotNull(
            match.place?.takeIf { it.isNotBlank() },
            match.craft?.takeIf { it.isNotBlank() }
        ).joinToString(", ")
        aadhaarDuplicate = buildString {
            append(match.name.ifBlank { "Another artisan" })
            if (where.isNotEmpty()) append(" ($where)")
            append(" is already recorded with this Aadhaar number. Open that artisan instead of ")
            append("creating a duplicate.")
        }
    }

    var another by remember { mutableStateOf(false) }
    fun finishQueued() {
        val created = onArtisanCreated
        if (isEdit || created == null || !another) { onDone(); return }
        created(
            Prefill(craftId = craftId.ifBlank { null }, craftName = craftOptions.firstOrNull { it.id == craftId }?.name),
            true
        )
    }

    fun submit() {
        val dosText = joinNumbered(dosItems)
        val dontsText = joinNumbered(dontsItems)
        if (!validateRequired(listOf(
                RequiredCheck(name.isBlank(), { nameError = it }, nameFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus),
                RequiredCheck(!hasCraft, { craftError = it }, craftFocus),
                RequiredCheck(dosText.isBlank(), { dosError = it }, dosFocus),
                RequiredCheck(dontsText.isBlank(), { dontsError = it }, dontsFocus)
            ))) { onError("Fill in the highlighted field."); return }
        dateOfBirthError = if (dateOfBirth == null) "Enter the date of birth." else null
        craftStartDateError = if (craftStartDate == null) "Enter the craft start date." else null
        missingArtisanDatesSentence(dateOfBirth?.toString(), craftStartDate?.toString())?.let { sentence ->
            onError(sentence); return
        }
        artisanPhoneValidationError(phone)?.let { msg -> phoneError = msg; onError("Fix the phone number highlighted above."); return }
        phoneError = null
        if (email.isNotBlank() && !EMAIL_RE.matches(email.trim())) {
            emailError = "Enter a valid email."
            runCatching { emailFocus.requestFocus() }
            onError("Fix the email highlighted above."); return
        }
        emailError = null
        if (aadhaarRequired && aadhaar.isBlank()) {
            aadhaarError = "Enter the 12-digit Aadhaar number."
            runCatching { aadhaarFocus.requestFocus() }
            onError("Aadhaar number required."); return
        }
        if (!notSharedHonoured) aadhaarValidationError(aadhaar)?.let { msg ->
            aadhaarError = msg
            runCatching { aadhaarFocus.requestFocus() }
            onError("Fix the Aadhaar number highlighted above."); return
        }
        aadhaarError = null
        if (pehchanAvailable == null && !isEdit) {
            pehchanError = "Answer whether the artisan has a Pehchan card."
            runCatching { pehchanFocus.requestFocus() }
            onError("Answer the Pehchan card question."); return
        }
        if (pehchanAvailable == true && pehchanNumber.isBlank()) {
            pehchanError = "Enter the Pehchan Card number, or set the card to 'No'."
            runCatching { pehchanFocus.requestFocus() }
            onError("Fix the Pehchan Card details."); return
        }
        pehchanError = null
        newRecordLocationError(isEdit, media.location)?.let { onError(it); return }
        scope.launch {
            if (!isEdit && repository.isOnline(context)) {
                saving = true
                val found = repository.checkDuplicates(
                    DuplicateCheckRequest(
                        name = name.trim(),
                        localName = localName.blankToNull(),
                        gender = gender.blankToNull(),
                        dateOfBirth = dateOfBirth?.toString(),
                        phone = phone.blankToNull(),
                        place = place.trim(),
                        state = media.location?.state,
                        district = media.location?.district,
                        craftId = craftId.ifBlank { null },
                        craftName = null,
                        aadhaar = aadhaar.blankToNull(),
                        pehchanCardNumber = if (pehchanAvailable == true) pehchanNumber.blankToNull() else null
                    )
                )
                saving = false
                val show = PossibleDuplicates.toShow(found, duplicateAcked)
                if (show.isNotEmpty()) {
                    duplicateShown = show
                    return@launch
                }
            }
            if (!workshop.confirmSubmission()) return@launch
            saving = true
            val body = ArtisanCreateRequest(
                name = name.trim(),
                localName = localName.blankToNull(),
                gender = gender.blankToNull(),
                phone = StandardValues.normalizePhone(null, phone).digits ?: phone.blankToNull(),
                phoneCountryCode = StandardValues.normalizePhone(null, phone).code,
                email = email.blankToNull(),
                place = place.trim(),
                address = address.blankToNull(),
                notes = notes.blankToNull(),
                aadhaarNumber = if (keepsStoredAadhaar) null else aadhaarForBody(aadhaar, aadhaarNotShared, editing?.aadhaarNumber),
                aadhaarNotShared = if (notSharedHonoured || editing?.aadhaarNotShared == true) notSharedHonoured else null,
                pehchanCardAvailable = pehchanAvailable,
                pehchanCardNumber = if (pehchanAvailable == true) pehchanNumber.blankToNull() else null,
                dateOfBirth = dateOfBirth?.toString(),
                craftStartDate = craftStartDate?.toString(),
                experienceYears = experienceYears.toIntOrNull(),
                experienceMonths = experienceMonths.toIntOrNull(),
                dos = dosText,
                donts = dontsText,
                craftId = craftId.ifBlank { null },
                craftName = null,
                workshopId = workshop.value(),
                status = status,
                recordedAt = if (isEdit) null else Instant.now().toString(),
                location = locationForBody(isEdit, media.location, editing?.location),
                formContract = ARTISAN_FORM_CONTRACT,
                duplicateAcknowledged = if (isEdit) null else PossibleDuplicates.acknowledged(duplicateAcked)
            )
            val queuedOffline = runCatching {
                trySaveOffline(repository, context, editing?.id, "artisan", offlineFormJson.encodeToString(body),
                    name.trim(), media, name.trim(), "Field media for ${name.trim()}")
            }.getOrElse { false }
            if (queuedOffline) {
                media.reset()
                onError("Saved offline. Uploads when you're online.")
                draft.clear(); finishQueued()
                saving = false
                return@launch
            }
            runCatching {
                val artisanId = if (isEdit) {
                    repository.updateArtisan(editing!!.id, body).id
                } else {
                    createOrKeep(repository, context, "artisan", offlineFormJson.encodeToString(body), name.trim(),
                        { media.offlineSpecs(name.trim(), "Field media for ${name.trim()}") }) { key -> repository.createArtisan(body, key) }.id
                }
                uploadAttachments(repository, context, media, "artisan", artisanId, name, "Field media for ${name.trim()}")
                artisanId
            }.onSuccess { artisanId ->
                if (isEdit) {
                    media.reset()
                    draft.clear(); onDone()
                } else {
                    val resolvedCraftName = craftOptions.firstOrNull { it.id == craftId }?.name
                    val prefillOut = Prefill(
                        artisanId = artisanId,
                        artisanName = name.trim(),
                        place = place.trim(),
                        craftId = craftId.ifBlank { null },
                        craftName = resolvedCraftName
                    )
                    media.reset()
                    draft.clear()
                    onArtisanCreated?.invoke(prefillOut, another) ?: onDone()
                }
            }.onFailure {
                if (it is KeptOnDevice) { media.reset(); draft.clear(); onError(it.message!!); finishQueued() } else {
                    val failure = it.apiFailure("Unable to save artisan")
                    val held = if (isEdit) null else failure.possibleDuplicates()
                    if (held != null) {
                        duplicateShown = PossibleDuplicates.toShow(held, duplicateAcked).ifEmpty { held.take(PossibleDuplicates.SHOWN_MAX) }
                    } else {
                        onError(failure.message)
                    }
                }
            }
            saving = false
        }
    }
    duplicateShown?.let { shown ->
        PossibleDuplicateSheet(
            candidates = shown,
            saving = saving,
            onOpen = onOpenExisting?.let { open -> { id: String -> duplicateShown = null; open(id) } },
            onDifferentPerson = {
                duplicateAcked = duplicateAcked + shown.map { it.artisan.id }
                duplicateShown = null
                submit()
            },
            onCancel = { duplicateShown = null }
        )
    }
    fun formSignature(): String = listOf(
        name, localName, gender, phone, email, place, address, notes, aadhaar,
        pehchanAvailable.toString(), pehchanNumber, joinNumbered(dosItems), joinNumbered(dontsItems),
        craftId.exceptCarried(carry.offer?.context?.craftId ?: prefill?.craftId), status
    ).joinToString(" ")
    val initialSig = remember(editing) { formSignature() }
    val dirty = !saving && (
        formSignature() != initialSig ||
            workshop.isDirty() || media.uris.isNotEmpty() || media.measurementUri != null
    )

    val dictates = recordDictationFor(RecordFormKind.ARTISAN)
    val artisanPhotos = rememberImageCount(media.uris)
    RecordCard(title = if (isEdit) recordUpdateTitle("artisan") else "Add artisan") {
        DraftOfferCard(draft)
        if (!lookupSettled) {
            ArtisanLookupCard(
                repository = repository,
                onOpen = { id -> onOpenExisting?.invoke(id) },
                onStart = { start ->
                    if (start.name.isNotBlank() && name.isBlank()) name = start.name
                    if (start.aadhaar.isNotBlank() && aadhaar.isBlank()) aadhaar = start.aadhaar
                    lookupSettled = true
                },
                aadhaarProblem = { aadhaarValidationError(it) }
            )
            return@RecordCard
        }
        FormCompletenessLine(
            kind = CompletenessKind.ARTISAN,
            values = mapOf(
                "dateOfBirth" to dateOfBirth,
                "craftStartDate" to craftStartDate,
                "craftId" to craftId.ifBlank { null },
                "dos" to dosItems.joinToString("\n").ifBlank { null },
                "donts" to dontsItems.joinToString("\n").ifBlank { null },
                "locationId" to (media.location ?: editing?.location)
            ),
            stored = editing?.completeness,
            add = mapOf("photos" to artisanPhotos)
        )
        RegisterUnsavedGuard(dirty = dirty) { another = false; submit() }
        media.draftInto(draft, "media")
        workshop.draftInto(draft)
        FormDraftAutosave(draft, dirty, label = name)
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        WorkshopField(state = workshop, saving = saving)
        RequiredInput("Name", name, nameError, nameFocus, titleCased = true, dictate = dictates("name")) { name = it }
        TextInput("Local name", localName, dictate = dictates("localName")) { localName = it }
        CompletenessAnchor("craft", craftFocus) {
        val craftListContext = LocalContext.current.applicationContext
        CraftPickerField(
            label = "Craft",
            required = true,
            selectedIds = listOfNotNull(craftId.ifBlank { null }),
            fallbackNames = editing?.craft?.let { mapOf(it.id to it.name) }.orEmpty(),
            isError = craftError != null,
            supportingText = craftError,
            refresh = { repository.craftCatalogue(craftListContext) },
            modifier = Modifier.focusRequester(craftFocus),
            onChange = { ids ->
                craftId = ids.firstOrNull().orEmpty()
                craftError = null
                CraftCatalogueCache.catalogue.value?.craft(craftId)?.let {
                    carry.remember(CarryContext(craftId = it.id, craftName = it.name), explicit = true)
                }
            }
        )
        }
        RequiredInput("Place", place, placeError, placeFocus, titleCased = true, dictate = dictates("place")) { place = it }
        DropdownField(
            label = "Gender",
            options = genderOptions.map { it to it },
            selectedValue = gender,
            placeholder = "Select…",
            includeNone = true
        ) { gender = it }
        CompletenessAnchor("dateOfBirth") {
            CalendarField(
                label = "Date of birth *",
                value = dateOfBirth,
                onValueChange = { dateOfBirth = it; dateOfBirthError = null },
                maximum = java.time.LocalDate.now(),
                clearable = true,
                supportingText = dateOfBirthError ?: "Shown as an age."
            )
        }
        CompletenessAnchor("craftStartDate") {
            CalendarField(
                label = "Craft start date *",
                value = craftStartDate,
                onValueChange = { craftStartDate = it; craftStartDateError = null },
                maximum = java.time.LocalDate.now(),
                clearable = true,
                supportingText = craftStartDateError ?: "Shown as years of experience."
            )
        }
        OutlinedTextField(
            value = experienceYears,
            onValueChange = { typed -> experienceYears = typed.filter { it.isDigit() }.take(2) },
            label = { Text("Experience (years)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        DropdownField(
            label = "Experience (months)",
            options = EXPERIENCE_MONTH_OPTIONS.map { it.toString() to experienceMonthsLabel(it) },
            selectedValue = experienceMonths,
            placeholder = EXPERIENCE_NOT_STATED,
            includeNone = true
        ) { experienceMonths = it }
        ArtisanPhoneField(value = phone, error = phoneError) { phone = it; phoneError = null }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; emailError = null },
            label = { Text("Email") },
            isError = emailError != null,
            supportingText = emailError?.let { msg -> { Text(msg) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(emailFocus)
        )
        TextInput("Address", address, minLines = 2, dictate = dictates("address")) { address = it }
        MultiNoteInput(value = notes, dictate = dictates("notes")) { notes = it }
        Text("Identity", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Stored securely; masked when shared or exported.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (!notSharedHonoured) {
            ArtisanAadhaarField(
                value = aadhaar,
                error = aadhaarError,
                warning = aadhaarDuplicate,
                required = aadhaarRequired,
                focusRequester = aadhaarFocus,
                onRecord = storedAadhaarMask?.takeIf { aadhaar.isBlank() }
            ) { aadhaar = it; aadhaarError = null }
        }
        if (notSharedOffered) AppToggle(
            label = "Not shared by artisan",
            description = if (aadhaarNotShared) "Saved without an Aadhaar and flagged for follow-up." else "The artisan declined to give their Aadhaar.",
            checked = aadhaarNotShared,
            onCheckedChange = { on ->
                aadhaarNotShared = on
                aadhaarError = null
                if (on) aadhaar = ""
            }
        )
        DropdownField(
            label = "Artisan Pehchan Card available *",
            options = listOf("yes" to "Yes", "no" to "No"),
            selectedValue = when (pehchanAvailable) { true -> "yes"; false -> "no"; null -> "" },
            placeholder = "Has the artisan a Pehchan card?",
            includeNone = false
        ) { choice ->
            pehchanAvailable = choice == "yes"
            pehchanError = null
            if (pehchanAvailable == false) pehchanNumber = ""
        }
        OutlinedTextField(
            value = pehchanNumber,
            onValueChange = { pehchanNumber = it.uppercase(); pehchanError = null },
            label = { Text(if (pehchanAvailable == true) "Artisan Pehchan Card number *" else "Artisan Pehchan Card number") },
            placeholder = { Text(if (pehchanAvailable == true) "As printed on the card" else "No card on record") },
            enabled = pehchanAvailable == true,
            isError = pehchanError != null,
            supportingText = {
                val hint = pehchanError
                    ?: if (pehchanAvailable == true) {
                        "The PM Vishwakarma artisan ID printed on the card."
                    } else {
                        "Set \"available\" to Yes to enter a number."
                    }
                Text(hint, color = if (pehchanError != null) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.bodySmall)
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(pehchanFocus)
        )
        if (isEdit) RecordingConsentRow(artisanId = editing!!.id)
        if (isEdit) SharingConsentRow(artisanId = editing!!.id)
        CompletenessAnchor("dos", dosFocus) {
            NumberedListInput(
                label = "Do's",
                items = dosItems,
                error = dosError,
                focusRequester = dosFocus,
                helper = "What the artisan has learnt to do. One point per line."
            ) { dosItems = it; dosError = null }
        }
        CompletenessAnchor("donts", dontsFocus) {
            NumberedListInput(
                label = "Don'ts",
                items = dontsItems,
                error = dontsError,
                focusRequester = dontsFocus,
                helper = "What the artisan avoids. One point per line."
            ) { dontsItems = it; dontsError = null }
        }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "artisan", recordId = editing!!.id, onError = onError)
        }
        CompletenessAnchor("photo") {
            CompletenessAnchor("location") {
                MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
            }
        }
        SaveButtons(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update artisan" else "Save artisan",
            onSave = { another = false; submit() },
            onSaveAnother = if (!isEdit && onArtisanCreated != null) ({ another = true; submit() }) else null
        )
    }
}

@Composable
private fun WorkshopForm(
    repository: FieldRepository,
    artisans: List<ArtisanDto>,
    artisanSeed: PageResponse<ArtisanDto>? = null,
    editing: WorkshopDetailDto? = null,
    prefill: Prefill? = null,
    adminView: Boolean = false,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = rememberFormDraft(repository, "workshop", editing?.id, prefill.draftParentId(), prefill.draftParentLabel())
    val media = rememberMediaCaptureState()
    val isEdit = editing != null
    var title by remember(editing) { mutableStateOf(editing?.title ?: "") }.drafted(draft, "title")
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }.drafted(draft, "place")
    var description by remember(editing) { mutableStateOf(editing?.description ?: "") }.drafted(draft, "description")
    var notes by remember(editing) { mutableStateOf(editing?.notes ?: "") }.drafted(draft, "notes")
    var startDate by remember(editing) { mutableStateOf(parseIsoToLocalDate(editing?.startDate)) }.drafted(draft, "startDate")
    var endDate by remember(editing) { mutableStateOf(parseIsoToLocalDate(editing?.endDate)) }.drafted(draft, "endDate")
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }.drafted(draft, "status")
    var selectedArtisans by remember(editing) {
        mutableStateOf(
            editing?.artisans?.map { it.artisanId }?.toSet()
                ?: prefill?.artisanId?.let { setOf(it) }
                ?: emptySet()
        )
    }.drafted(draft, "selectedArtisans")
    val artisanSearch = rememberServerSearch(
        key = Unit, cached = artisans, seed = artisanSeed, idOf = { it.id }, textOf = ::artisanSearchText
    ) { term, page -> repository.artisansSearchPage(term, page) }
    var selectedCrafts by remember(editing) {
        mutableStateOf(editing?.crafts?.map { it.craftId }?.toSet() ?: emptySet())
    }.drafted(draft, "selectedCrafts")
    var saving by remember { mutableStateOf(false) }
    var titleError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    val titleFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }

    fun submit() {
        if (!validateRequired(listOf(
                RequiredCheck(title.isBlank(), { titleError = it }, titleFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus)
            ))) { onError("Fill in the highlighted field."); return }
        newRecordLocationError(isEdit, media.location, "Workshop location")?.let { onError(it); return }
        scope.launch {
            saving = true
            val start = (startDate ?: LocalDate.now()).toIsoInstant()
            val end = (endDate ?: startDate ?: LocalDate.now()).toIsoInstant()
            val originalArtisans = editing?.artisans?.map { it.artisanId }?.toSet() ?: emptySet()
            val originalCrafts = editing?.crafts?.map { it.craftId }?.toSet() ?: emptySet()
            val artisanIdsParam = if (!isEdit || selectedArtisans != originalArtisans) selectedArtisans.toList() else null
            val craftIdsParam = if (!isEdit || selectedCrafts != originalCrafts) selectedCrafts.toList() else null
            val body = WorkshopCreateRequest(
                title = title.trim(),
                date = start,
                startDate = start,
                endDate = end,
                place = place.trim(),
                description = description.blankToNull(),
                notes = notes.blankToNull(),
                artisanIds = artisanIdsParam,
                craftIds = craftIdsParam,
                status = status,
                recordedAt = if (isEdit) null else Instant.now().toString(),
                location = locationForBody(isEdit, media.location, editing?.location)
            )
            val queuedOffline = runCatching {
                trySaveOffline(repository, context, editing?.id, "workshop", offlineFormJson.encodeToString(body),
                    title.trim(), media, title.trim(), "Field media for ${title.trim()}")
            }.getOrElse { false }
            if (queuedOffline) {
                media.reset()
                onError("Saved offline. Uploads when you're online.")
                draft.clear(); onDone()
                saving = false
                return@launch
            }
            runCatching {
                val workshopId = if (isEdit) {
                    repository.updateWorkshop(editing!!.id, body).id
                } else {
                    createOrKeep(repository, context, "workshop", offlineFormJson.encodeToString(body), title.trim(),
                        { media.offlineSpecs(title.trim(), "Field media for ${title.trim()}") }) { key -> repository.createWorkshop(body, key) }.id
                }
                uploadAttachments(repository, context, media, "workshop", workshopId, title, "Field media for ${title.trim()}")
            }.onSuccess {
                media.reset()
                draft.clear(); onDone()
            }.onFailure { if (it is KeptOnDevice) { media.reset(); draft.clear(); onError(it.message!!); onDone() } else onError(it.apiErrorMessage("Unable to save workshop")) }
            saving = false
        }
    }
    val initialSig = remember(editing) {
        listOf(title, place, description, notes, status, startDate?.toString() ?: "", endDate?.toString() ?: "",
            selectedArtisans.sorted().joinToString(","), selectedCrafts.sorted().joinToString(",")).joinToString("")
    }
    val dirty = !saving && (
        listOf(title, place, description, notes, status, startDate?.toString() ?: "", endDate?.toString() ?: "",
            selectedArtisans.sorted().joinToString(","), selectedCrafts.sorted().joinToString(",")).joinToString("") != initialSig ||
            media.uris.isNotEmpty() || media.measurementUri != null
    )

    if (!isEdit && repository.cachedUser()?.isAdminUser() == true) {
        WorkshopMappingCard(repository = repository, onError = onError)
    }

    val dictates = recordDictationFor(RecordFormKind.WORKSHOP)
    val workshopPhotos = rememberImageCount(media.uris)
    RecordCard(title = if (isEdit) recordUpdateTitle("workshop") else "Add workshop") {
        DraftOfferCard(draft)
        FormCompletenessLine(
            kind = CompletenessKind.WORKSHOP,
            values = mapOf("startDate" to startDate, "endDate" to endDate),
            stored = editing?.completeness,
            add = mapOf("artisans" to selectedArtisans.size, "crafts" to selectedCrafts.size, "photos" to workshopPhotos)
        )
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        media.draftInto(draft, "media")
        FormDraftAutosave(draft, dirty, label = title)
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        RequiredInput("Workshop title", title, titleError, titleFocus, titleCased = true, dictate = dictates("title")) { title = it }
        RequiredInput("Place", place, placeError, placeFocus, titleCased = true, dictate = dictates("place")) { place = it }
        CompletenessAnchor("startDate") {
            CompletenessAnchor("endDate") {
                FieldDateRangeField(
                    label = "Workshop duration",
                    start = startDate,
                    end = endDate,
                    onRangeChange = { from, to ->
                        startDate = from
                        endDate = to
                    }
                )
            }
        }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        TextInput("Description", description, minLines = 3, dictate = dictates("description"), rich = true) { description = it }
        MultiNoteInput(value = notes, dictate = dictates("notes")) { notes = it }
        CompletenessAnchor("artisans") {
            CheckboxMultiSelectField(
                label = "Linked artisans",
                emptyMessage = "No artisans available yet. Create an artisan first.",
                options = artisanSearch.known.map { it.id to "${it.name} · ${it.place}" },
                selectedIds = selectedArtisans,
                server = artisanSearch.asServerSearch(::artisanSelectOption)
            ) { id ->
                selectedArtisans = if (selectedArtisans.contains(id)) selectedArtisans - id else selectedArtisans + id
            }
        }
        CompletenessAnchor("crafts") {
            val craftListContext = LocalContext.current.applicationContext
            CraftPickerField(
                label = "Crafts covered",
                multiple = true,
                selectedIds = selectedCrafts.toList(),
                fallbackNames = editing?.crafts?.mapNotNull { link -> link.craft?.let { it.id to it.name } }?.toMap().orEmpty(),
                refresh = { repository.craftCatalogue(craftListContext) },
            ) { next -> selectedCrafts = next.toSet() }
        }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "workshop", recordId = editing!!.id, onError = onError)
        }
        CompletenessAnchor("photo") {
            MediaCaptureSection(repository = repository, media = media, locationHeading = "Workshop location", onMessage = onError, onError = onError)
        }
        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update workshop" else "Save workshop"
        ) { submit() }
    }
}

@Composable
private fun ProductForm(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    lookupState: CarryScopeState = CarryScopeState.PENDING,
    craftSeed: PageResponse<CraftDto>? = null,
    artisanSeed: PageResponse<ArtisanDto>? = null,
    editing: ProductDetailDto? = null,
    prefill: Prefill? = null,
    adminView: Boolean = false,
    onCreated: ((Prefill, String?, Boolean) -> Unit)? = null,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = rememberFormDraft(repository, "product", editing?.id, prefill.draftParentId(), prefill.draftParentLabel())
    val media = rememberMediaCaptureState()
    val isEdit = editing != null
    var productName by remember(editing) { mutableStateOf(editing?.productName ?: "") }.drafted(draft, "productName")
    var localName by remember(editing) { mutableStateOf(editing?.localName ?: "") }.drafted(draft, "localName")
    var craftName by remember(editing) { mutableStateOf(editing?.craftName ?: prefill?.craftName ?: "") }.drafted(draft, "craftName")
    var artisanName by remember(editing) { mutableStateOf(editing?.artisanName ?: prefill?.artisanName ?: "") }.drafted(draft, "artisanName")
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }.drafted(draft, "place")
    var craftId by remember(editing) { mutableStateOf(editing?.craftId ?: prefill?.craftId ?: "") }.drafted(draft, "craftId")
    var artisanId by remember(editing) { mutableStateOf(editing?.artisanId ?: prefill?.artisanId ?: "") }.drafted(draft, "artisanId")
    val craftCatalogue by CraftCatalogueCache.catalogue.collectAsState()
    val craftOptions = remember(craftCatalogue) { craftCatalogue?.crafts?.map { CraftDto(id = it.id, name = it.name) }.orEmpty() }
    val craftListState = if (craftCatalogue != null) CarryScopeState.LOADED else CarryScopeState.UNAVAILABLE
    var notOnRecord by remember(editing) {
        mutableStateOf(editing != null && editing.artisanId.isNullOrBlank() && editing.artisanName.isNotBlank())
    }.drafted(draft, "notOnRecord")
    val picker = rememberArtisanPicker(repository, artisans, if (notOnRecord) craftId else "", artisanId, seed = artisanSeed)

    var productType by remember(editing) { mutableStateOf(editing?.productType ?: "OTHER") }.drafted(draft, "productType")
    var marketDemand by remember(editing) { mutableStateOf(editing?.marketDemand ?: "UNKNOWN") }.drafted(draft, "marketDemand")
    var timeTaken by remember(editing) { mutableStateOf(editing?.timeTakenToCompleteProduct ?: "") }.drafted(draft, "timeTaken")
    var size by remember(editing) { mutableStateOf(editing?.size ?: "") }.drafted(draft, "size")
    var length by remember(editing) { mutableStateOf(numToText(editing?.lengthInches)) }.drafted(draft, "length")
    var breadth by remember(editing) { mutableStateOf(numToText(editing?.breadthInches)) }.drafted(draft, "breadth")
    var height by remember(editing) { mutableStateOf(numToText(editing?.heightInches)) }.drafted(draft, "height")
    val markers = remember(editing) { MeasurementMarkers() }
    var costOfMaking by remember(editing) { mutableStateOf(numToText(editing?.costOfMaking)) }.drafted(draft, "costOfMaking")
    var sellingPrice by remember(editing) { mutableStateOf(numToText(editing?.sellingPrice)) }.drafted(draft, "sellingPrice")
    var rawMaterials by remember(editing) { mutableStateOf(editing?.rawMaterialsUsed ?: "") }.drafted(draft, "rawMaterials")
    var mainTools by remember(editing) { mutableStateOf(editing?.mainToolsUsed ?: "") }.drafted(draft, "mainTools")
    var functionUse by remember(editing) { mutableStateOf(editing?.productFunctionUse ?: "") }.drafted(draft, "functionUse")
    var remarks by remember(editing) { mutableStateOf(editing?.remarks ?: "") }.drafted(draft, "remarks")
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }.drafted(draft, "status")
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing, runningOnly = true)
    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.PRODUCT_FORM,
        scopes = listOf(
            carryScope(CarryNode.ARTISAN, lookupState, picker.options) { it.id },
            carryScope(CarryNode.CRAFT, craftListState, craftOptions) { it.id }
        ),
        handoff = prefill
    ) { carried ->
        carried.craftId?.let { craftId = it }
        carried.craftName?.let { craftName = it }
        carried.artisanId?.let { artisanId = it }
        carried.artisanName?.let { artisanName = it }
        carried.place?.let { place = it }
        carried.workshopId?.let { if (!workshop.isDirty()) workshop.applyDefault(it) }
    }
    fun clearCarriedContext() {
        carry.change()
        craftId = ""
        craftName = ""
        artisanId = ""
        artisanName = ""
        place = ""
    }
    var saving by remember { mutableStateOf(false) }
    var productNameError by remember { mutableStateOf<String?>(null) }
    var craftNameError by remember { mutableStateOf<String?>(null) }
    var artisanNameError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    var artisanPickError by remember { mutableStateOf<String?>(null) }
    val productNameFocus = remember { FocusRequester() }
    val craftNameFocus = remember { FocusRequester() }
    val artisanNameFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }
    var another by remember { mutableStateOf(false) }
    fun finish(productId: String?, queued: Boolean) {
        val created = onCreated
        if (isEdit || created == null || (queued && !another)) { onDone(); return }
        created(
            Prefill(
                artisanId = artisanId.ifBlank { null },
                artisanName = artisanName.blankToNull(),
                place = place.blankToNull(),
                craftId = craftId.ifBlank { null },
                craftName = craftName.blankToNull(),
                productId = productId,
                productName = productName.trim()
            ),
            productName.trim(),
            another
        )
    }

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }

    fun submit() {
        artisanPickError = if (!notOnRecord && artisanId.isBlank()) ARTISAN_PICK_SENTENCE else null
        val typedOk = validateRequired(listOf(
                RequiredCheck(productName.isBlank(), { productNameError = it }, productNameFocus),
                RequiredCheck(craftId.isBlank(), { craftNameError = it }, craftNameFocus),
                RequiredCheck(artisanName.isBlank() && notOnRecord, { artisanNameError = it }, artisanNameFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus)
            ))
        if (!typedOk || artisanPickError != null) { onError("Fill in the highlighted field."); return }
        newRecordLocationError(isEdit, media.location)?.let { onError(it); return }
        scope.launch {
            if (!workshop.confirmSubmission()) return@launch
            saving = true
            val body = ProductCreateRequest(
                productName = productName.trim(),
                localName = localName.blankToNull(),
                craftName = craftName.trim(),
                artisanName = artisanName.trim(),
                place = place.trim(),
                productType = productType,
                timeTakenToCompleteProduct = timeTaken.blankToNull(),
                size = size.blankToNull(),
                lengthInches = length.toDoubleOrNull(),
                breadthInches = breadth.toDoubleOrNull(),
                heightInches = height.toDoubleOrNull(),
                measurementMethods = markers.body(
                    mapOf("lengthInches" to length, "breadthInches" to breadth, "heightInches" to height)
                ),
                costOfMaking = costOfMaking.toDoubleOrNull(),
                sellingPrice = sellingPrice.toDoubleOrNull(),
                marketDemand = marketDemand,
                rawMaterialsUsed = rawMaterials.blankToNull(),
                mainToolsUsed = mainTools.blankToNull(),
                productFunctionUse = functionUse.blankToNull(),
                remarks = remarks.blankToNull(),
                artisanId = artisanId.ifBlank { null },
                craftId = craftId.ifBlank { null },
                workshopId = workshop.value(),
                status = status,
                recordedAt = if (isEdit) null else Instant.now().toString(),
                location = locationForBody(isEdit, media.location, editing?.location)
            )
            val sitting = CarryContext(
                artisanId = artisanId.ifBlank { null },
                artisanName = artisanName.trim(),
                place = place.trim(),
                craftId = craftId.ifBlank { null },
                craftName = craftName.trim(),
                workshopId = workshop.value(),
                workshopName = workshop.workshops.firstOrNull { it.id == workshop.value() }?.title
            )
            val queuedOffline = runCatching {
                trySaveOffline(repository, context, editing?.id, "product", offlineFormJson.encodeToString(body),
                    productName.trim(), media, productName.trim(), "Field media for ${productName.trim()}")
            }.getOrElse { false }
            if (queuedOffline) {
                carry.prune(CarryNode.PRODUCT)
                carry.remember(sitting)
                media.reset()
                onError("Saved offline. Uploads when you're online.")
                draft.clear(); finish(null, queued = true)
                saving = false
                return@launch
            }
            runCatching {
                val productId = if (isEdit) {
                    repository.updateProduct(editing!!.id, body).id
                } else {
                    createOrKeep(repository, context, "product", offlineFormJson.encodeToString(body), productName.trim(),
                        { media.offlineSpecs(productName.trim(), "Field media for ${productName.trim()}") }) { key -> repository.createProduct(body, key) }.id
                }
                uploadAttachments(repository, context, media, "product", productId, productName, "Field media for ${productName.trim()}")
                productId
            }.onSuccess { productId ->
                carry.remember(sitting.copy(productId = productId, productName = productName.trim()))
                media.reset()
                draft.clear(); finish(productId, queued = false)
            }.onFailure { if (it is KeptOnDevice) { carry.prune(CarryNode.PRODUCT); carry.remember(sitting); media.reset(); draft.clear(); onError(it.message!!); finish(null, queued = true) } else onError(it.apiErrorMessage("Unable to save product")) }
            saving = false
        }
    }
    val productSig: () -> String = {
        val carried = carry.offer?.context ?: prefill?.toCarryContext()
        listOf(productName, localName, craftName.exceptCarried(carried?.craftName),
            artisanName.exceptCarried(carried?.artisanName), place.exceptCarried(carried?.place),
            productType, marketDemand, timeTaken, size,
            length, breadth, height, costOfMaking, sellingPrice, rawMaterials, mainTools, functionUse, remarks,
            status, craftId.exceptCarried(carried?.craftId), artisanId.exceptCarried(carried?.artisanId)).joinToString("")
    }
    val initialSig = remember(editing) { productSig() }
    val dirty = !saving && (
        productSig() != initialSig || workshop.isDirty() || media.uris.isNotEmpty() || media.measurementUri != null
    )

    val dictates = recordDictationFor(RecordFormKind.PRODUCT)
    val productPhotos = rememberImageCount(media.uris)
    RecordCard(title = if (isEdit) recordUpdateTitle("product") else "Add product") {
        DraftOfferCard(draft)
        FormCompletenessLine(
            kind = CompletenessKind.PRODUCT,
            values = mapOf(
                "artisanId" to artisanId.ifBlank { null },
                "sellingPrice" to sellingPrice.toDoubleOrNull(),
                "costOfMaking" to costOfMaking.toDoubleOrNull(),
                "timeTakenToCompleteProduct" to timeTaken,
                "lengthInches" to length.toDoubleOrNull(),
                "breadthInches" to breadth.toDoubleOrNull(),
                "heightInches" to height.toDoubleOrNull(),
                "marketDemand" to marketDemand
            ),
            stored = editing?.completeness,
            add = mapOf("photos" to productPhotos)
        )
        RegisterUnsavedGuard(dirty = dirty) { another = false; submit() }
        media.draftInto(draft, "media")
        workshop.draftInto(draft)
        FormDraftAutosave(draft, dirty, label = productName)
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        WorkshopField(state = workshop, saving = saving)
        RequiredInput("Product name", productName, productNameError, productNameFocus, titleCased = true, dictate = dictates("productName")) { productName = it }
        TextInput("Local name", localName, dictate = dictates("localName")) { localName = it }
        DropdownField("Product type", productTypeOptions.map { it to it }, productType, includeNone = false) { productType = it }
        CompletenessAnchor("artisan") {
        DropdownField(
            label = "Artisan",
            options = picker.options.map { it.id to artisanFirstOption(it).label },
            selectedValue = artisanId,
            placeholder = "Search artisans",
            enabled = !notOnRecord,
            server = picker.search.asServerSearch(::artisanFirstOption)
        ) { id ->
            artisanId = id
            artisanPickError = null
            picker.options.firstOrNull { it.id == id }?.let {
                artisanName = it.name
                place = it.place
                craftId = it.craftId.orEmpty()
                craftName = it.craft?.name ?: craftOptions.firstOrNull { c -> c.id == it.craftId }?.name.orEmpty()
                carry.remember(
                    CarryContext(
                        artisanId = it.id,
                        artisanName = it.name,
                        place = it.place,
                        craftId = it.craftId,
                        craftName = craftName.blankToNull()
                    ),
                    explicit = true
                )
            }
        }
        }
        artisanPickError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!notOnRecord) {
            ArtisanCardPickerScan(
                repository = repository,
                selected = listOf(artisanId),
                onPicked = { id, name, cardPlace ->
                    artisanId = id
                    artisanPickError = null
                    name?.let { artisanName = it }
                    cardPlace?.let { place = it }
                    if (!ArtisanCardCode.isPending(id)) {
                        scope.launch {
                            runCatching { repository.artisan(id) }.getOrNull()?.let { found ->
                                found.craftId?.let { craftId = it }
                                found.craft?.name?.let { craftName = it }
                            }
                        }
                    }
                },
                onError = onError
            )
        }
        AppToggle(
            label = "Artisan not on record",
            description = "Type the craft, artisan and place yourself.",
            checked = notOnRecord,
            onCheckedChange = { on ->
                notOnRecord = on
                artisanPickError = null
                if (on) artisanId = ""
            }
        )
        val pickedArtisan = picker.options.firstOrNull { it.id == artisanId }
        val needsCraft = notOnRecord || (pickedArtisan != null && pickedArtisan.craftId.isNullOrBlank()) || craftNameError != null
        if (needsCraft) {
            val craftListContext = LocalContext.current.applicationContext
            CraftPickerField(
                label = "Craft",
                required = true,
                selectedIds = listOfNotNull(craftId.ifBlank { null }),
                fallbackNames = if (craftId.isNotBlank() && craftName.isNotBlank()) mapOf(craftId to craftName) else emptyMap(),
                isError = craftNameError != null,
                supportingText = craftNameError,
                refresh = { repository.craftCatalogue(craftListContext) },
                modifier = Modifier.focusRequester(craftNameFocus),
                onChange = { ids ->
                    val id = ids.firstOrNull().orEmpty()
                    craftId = id
                    craftName = CraftCatalogueCache.catalogue.value?.craft(id)?.name.orEmpty()
                    craftNameError = null
                    if (notOnRecord && craftChangeClearsArtisan(id, artisanId, picker.options)) {
                        artisanId = ""
                    }
                }
            )
        }
        if (notOnRecord) {
            RequiredInput("Artisan name", artisanName, artisanNameError, artisanNameFocus, titleCased = true, dictate = dictates("artisanName")) { artisanName = it }
        }
        if (notOnRecord || (pickedArtisan != null && pickedArtisan.place.isBlank()) || placeError != null) {
            RequiredInput("Place", place, placeError, placeFocus, titleCased = true, dictate = dictates("place")) { place = it }
        }
        CompletenessAnchor("timeTaken") {
            TextInput("Time taken to complete", timeTaken, dictate = dictates("timeTakenToCompleteProduct")) { timeTaken = it }
        }
        TextInput("Size", size, dictate = dictates("size")) { size = it }
        CompletenessAnchor("dimensions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) { TextInput("Length (inches)", length, keyboardType = KeyboardType.Decimal, dictate = dictates("lengthInches")) { length = it; markers.forget("lengthInches") } }
                Box(modifier = Modifier.weight(1f)) { TextInput("Breadth (inches)", breadth, keyboardType = KeyboardType.Decimal, dictate = dictates("breadthInches")) { breadth = it; markers.forget("breadthInches") } }
            }
            TextInput("Height (inches)", height, keyboardType = KeyboardType.Decimal, dictate = dictates("heightInches")) { height = it; markers.forget("heightInches") }
        }
        RecordMeasureField(
            dimensions = PRODUCT_MEASURE_DIMENSIONS,
            current = mapOf("lengthInches" to length, "breadthInches" to breadth, "heightInches" to height),
            photos = media.uris,
            enabled = !saving,
            onPropose = { column, text, technique ->
                when (column) {
                    "lengthInches" -> length = text
                    "breadthInches" -> breadth = text
                    "heightInches" -> height = text
                }
                markers.accept(column, text, geometryMarker(technique))
            },
        )
        GridMeasurementSection(
            repository = repository,
            media = media,
            includeHeight = true,
            onLengthBreadth = { l, b, marker ->
                if (l != null && l > 0) { length = numToText(l); markers.accept("lengthInches", length, visionMarker(marker)) }
                if (b != null && b > 0) { breadth = numToText(b); markers.accept("breadthInches", breadth, visionMarker(marker)) }
            },
            onHeight = { value, marker -> height = numToText(value); markers.accept("heightInches", height, visionMarker(marker)) }
        )
        CompletenessAnchor("costOfMaking") {
            CompletenessAnchor("sellingPrice") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) { TextInput("Cost of making", costOfMaking, keyboardType = KeyboardType.Decimal, dictate = dictates("costOfMaking")) { costOfMaking = it } }
                    Box(modifier = Modifier.weight(1f)) { TextInput("Selling price", sellingPrice, keyboardType = KeyboardType.Decimal, dictate = dictates("sellingPrice")) { sellingPrice = it } }
                }
            }
        }
        CompletenessAnchor("marketDemand") {
            DropdownField("Market demand", marketDemandOptions.map { it to it }, marketDemand, includeNone = false) { marketDemand = it }
        }
        TextInput("Raw materials used", rawMaterials, minLines = 2, dictate = dictates("rawMaterialsUsed"), rich = true) { rawMaterials = it }
        TextInput("Main tools used", mainTools, minLines = 2, dictate = dictates("mainToolsUsed"), rich = true) { mainTools = it }
        TextInput("Function or use", functionUse, minLines = 2, dictate = dictates("productFunctionUse"), rich = true) { functionUse = it }
        TextInput("Remarks", remarks, minLines = 3, dictate = dictates("remarks"), rich = true) { remarks = it }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "product", recordId = editing!!.id, onError = onError)
        }
        CompletenessAnchor("photo") {
            MediaCaptureSection(repository = repository, media = media, inheritPlace = picker.options.firstOrNull { it.id == artisanId }.inheritedPlace(isEdit), onMessage = onError, onError = onError)
        }
        SaveButtons(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update product" else "Save product",
            onSave = { another = false; submit() },
            onSaveAnother = if (!isEdit && onCreated != null) ({ another = true; submit() }) else null
        )
    }
}

@Composable
private fun ToolForm(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    lookupState: CarryScopeState = CarryScopeState.PENDING,
    craftSeed: PageResponse<CraftDto>? = null,
    artisanSeed: PageResponse<ArtisanDto>? = null,
    editing: ToolDetailDto? = null,
    prefill: Prefill? = null,
    adminView: Boolean = false,
    onCreated: ((Prefill, String?, Boolean) -> Unit)? = null,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = rememberFormDraft(repository, "tool", editing?.id, prefill.draftParentId(), prefill.draftParentLabel())
    val media = rememberMediaCaptureState()
    val stages = rememberMediaCaptureState()
    val isEdit = editing != null
    var toolkitName by remember(editing) { mutableStateOf(editing?.toolkitName ?: "") }.drafted(draft, "toolkitName")
    var localName by remember(editing) { mutableStateOf(editing?.localName ?: "") }.drafted(draft, "localName")
    var englishName by remember(editing) { mutableStateOf(editing?.englishName ?: "") }.drafted(draft, "englishName")
    var mirrorArmed by remember(editing) {
        mutableStateOf((editing?.englishName ?: "").isBlank() || editing?.englishName == editing?.toolkitName)
    }.drafted(draft, "mirrorArmed")
    var craftName by remember(editing) { mutableStateOf(editing?.craftName ?: prefill?.craftName ?: "") }.drafted(draft, "craftName")
    var artisanName by remember(editing) { mutableStateOf(editing?.artisanName ?: prefill?.artisanName ?: "") }.drafted(draft, "artisanName")
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }.drafted(draft, "place")
    val seededCraftIds = remember(editing) {
        val own = (editing?.craftId ?: prefill?.craftId)?.ifBlank { null }
        (listOfNotNull(own) + editing?.craftLinks?.map { it.craftId }.orEmpty()).distinct()
    }
    val seededArtisanIds = remember(editing) {
        val own = (editing?.artisanId ?: prefill?.artisanId)?.ifBlank { null }
        (listOfNotNull(own) + editing?.artisanLinks?.map { it.artisanId }.orEmpty()).distinct()
    }
    var craftIds by remember(editing) { mutableStateOf(seededCraftIds) }.drafted(draft, "craftIds")
    var artisanIds by remember(editing) { mutableStateOf(seededArtisanIds) }.drafted(draft, "artisanIds")
    val craftId = craftIds.firstOrNull().orEmpty()
    val artisanId = artisanIds.firstOrNull().orEmpty()
    val craftCatalogue by CraftCatalogueCache.catalogue.collectAsState()
    val craftOptions = remember(craftCatalogue, editing) {
        val listed = craftCatalogue?.crafts?.map { CraftDto(id = it.id, name = it.name) }.orEmpty()
        val linked = editing?.craftLinks?.mapNotNull { it.craft }.orEmpty().filter { own -> listed.none { it.id == own.id } }
        listed + linked
    }
    val craftListState = if (craftCatalogue != null) CarryScopeState.LOADED else CarryScopeState.UNAVAILABLE
    var notOnRecord by remember(editing) {
        mutableStateOf(editing != null && seededArtisanIds.isEmpty() && editing.artisanName.isNotBlank())
    }.drafted(draft, "notOnRecord")
    val picker = rememberArtisanPicker(
        repository,
        artisans,
        if (notOnRecord) craftIds else emptyList(),
        artisanIds,
        hydrated = editing?.artisanLinks?.mapNotNull { it.artisan }.orEmpty(),
        seed = artisanSeed
    )

    var processUsedIn by remember(editing) { mutableStateOf(editing?.processUsedIn ?: "") }.drafted(draft, "processUsedIn")
    var material by remember(editing) { mutableStateOf(editing?.material ?: "") }.drafted(draft, "material")
    var materialFamily by remember(editing) { mutableStateOf(editing?.materialFamily.orEmpty()) }.drafted(draft, "materialFamily")
    var yearsInUse by remember(editing) { mutableStateOf(editing?.yearsInUse?.toString() ?: "") }.drafted(draft, "yearsInUse")
    var height by remember(editing) { mutableStateOf(numToText(editing?.height)) }.drafted(draft, "height")
    var heightInches by remember(editing) { mutableStateOf(numToText(editing?.heightInches)) }.drafted(draft, "heightInches")
    var width by remember(editing) { mutableStateOf(numToText(editing?.width)) }.drafted(draft, "width")
    var length by remember(editing) { mutableStateOf(numToText(editing?.lengthInches)) }.drafted(draft, "length")
    var breadth by remember(editing) { mutableStateOf(numToText(editing?.breadthInches)) }.drafted(draft, "breadth")
    var thickness by remember(editing) { mutableStateOf(numToText(editing?.thickness)) }.drafted(draft, "thickness")
    var weight by remember(editing) { mutableStateOf(numToText(editing?.weight)) }.drafted(draft, "weight")
    var radius by remember(editing) { mutableStateOf(numToText(editing?.radius)) }.drafted(draft, "radius")
    val markers = remember(editing) { MeasurementMarkers() }
    var maker by remember(editing) { mutableStateOf(editing?.maker ?: "UNKNOWN") }.drafted(draft, "maker")
    var traditionType by remember(editing) { mutableStateOf(StandardValues.traditionChoice(editing?.traditionType)) }.drafted(draft, "traditionType")
    var replacementCost by remember(editing) { mutableStateOf(numToText(editing?.replacementCost)) }.drafted(draft, "replacementCost")
    var suggestions by remember(editing) { mutableStateOf(editing?.suggestionsForToolImprovement ?: "") }.drafted(draft, "suggestions")
    var remarks by remember(editing) { mutableStateOf(editing?.remarks ?: "") }.drafted(draft, "remarks")
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing, runningOnly = true)
    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.TOOL_FORM,
        scopes = listOf(
            carryScope(CarryNode.ARTISAN, lookupState, picker.options) { it.id },
            carryScope(CarryNode.CRAFT, craftListState, craftOptions) { it.id }
        ),
        handoff = prefill
    ) { carried ->
        carried.craftId?.let { id -> if (id.isNotBlank()) craftIds = listOf(id) }
        carried.craftName?.let { craftName = it }
        carried.artisanId?.let { id -> if (id.isNotBlank()) artisanIds = listOf(id) }
        carried.artisanName?.let { artisanName = it }
        carried.place?.let { place = it }
        carried.workshopId?.let { if (!workshop.isDirty()) workshop.applyDefault(it) }
    }
    fun clearCarriedContext() {
        carry.change()
        craftIds = emptyList()
        craftName = ""
        artisanIds = emptyList()
        artisanName = ""
        place = ""
    }

    fun applyToolkitName(next: String) {
        toolkitName = next
        if (mirrorArmed) englishName = next
    }

    fun carriedCraftName(): String? =
        craftIds.firstOrNull()?.let { cid -> craftOptions.firstOrNull { it.id == cid }?.name }
            ?: craftName.blankToNull()

    fun reorderSelection(current: List<String>, next: Set<String>): List<String> =
        current.filter { it in next } + next.filterNot { it in current }

    fun syncArtisanHead(previousHead: String?, next: List<String>) {
        val head = next.firstOrNull() ?: return
        if (head == previousHead) return
        val row = picker.options.firstOrNull { it.id == head }
            ?: editing?.artisanLinks?.firstOrNull { it.artisanId == head }?.artisan
            ?: return
        artisanName = row.name
        place = row.place
        carry.remember(
            CarryContext(
                artisanId = row.id,
                artisanName = row.name,
                place = row.place,
                craftId = craftIds.firstOrNull(),
                craftName = carriedCraftName()
            ),
            explicit = true
        )
    }

    fun onCraftsChanged(selection: Set<String>) {
        val next = reorderSelection(craftIds, selection)
        val removed = craftIds.filterNot { it in next }
        val previousArtisanHead = artisanIds.firstOrNull()
        val dropped = craftsChangeClearsArtisans(next, removed, artisanIds, picker.options)
        if (dropped.isNotEmpty()) artisanIds = artisanIds.filterNot { it in dropped }
        craftIds = next
        val joined = next.mapNotNull { cid -> craftOptions.firstOrNull { it.id == cid }?.name }.joinToString(", ")
        if (joined.isNotEmpty()) craftName = joined
        syncArtisanHead(previousArtisanHead, artisanIds)
    }

    fun onArtisansChanged(selection: Set<String>) {
        val previousHead = artisanIds.firstOrNull()
        val next = reorderSelection(artisanIds, selection)
        artisanIds = next
        val theirCrafts = next.mapNotNull { id -> picker.options.firstOrNull { it.id == id }?.craftId?.ifBlank { null } }
        val added = theirCrafts.filterNot { it in craftIds }.distinct()
        if (added.isNotEmpty()) {
            craftIds = craftIds + added
            val joined = craftIds.mapNotNull { cid -> craftOptions.firstOrNull { it.id == cid }?.name ?: picker.options.firstOrNull { it.craftId == cid }?.craft?.name }
                .joinToString(", ")
            if (joined.isNotEmpty()) craftName = joined
        }
        syncArtisanHead(previousHead, next)
    }
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }.drafted(draft, "status")
    var saving by remember { mutableStateOf(false) }
    var toolkitNameError by remember { mutableStateOf<String?>(null) }
    var craftNameError by remember { mutableStateOf<String?>(null) }
    var artisanNameError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    var artisanPickError by remember { mutableStateOf<String?>(null) }
    val toolkitNameFocus = remember { FocusRequester() }
    val craftNameFocus = remember { FocusRequester() }
    val artisanNameFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }

    var another by remember { mutableStateOf(false) }
    fun finish(queued: Boolean) {
        val created = onCreated
        if (isEdit || created == null || (queued && !another)) { onDone(); return }
        created(
            Prefill(
                artisanId = artisanId.ifBlank { null },
                artisanName = artisanName.blankToNull(),
                place = place.blankToNull(),
                craftId = craftId.ifBlank { null },
                craftName = carriedCraftName()
            ),
            toolkitName.trim(),
            another
        )
    }

    fun submit() {
        artisanPickError = if (!notOnRecord && artisanIds.isEmpty()) ARTISAN_PICK_SENTENCE else null
        val typedOk = validateRequired(listOf(
                RequiredCheck(toolkitName.isBlank(), { toolkitNameError = it }, toolkitNameFocus),
                RequiredCheck(craftIds.isEmpty(), { craftNameError = it }, craftNameFocus),
                RequiredCheck(artisanName.isBlank() && notOnRecord, { artisanNameError = it }, artisanNameFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus)
            ))
        if (!typedOk || artisanPickError != null) { onError("Fill in the highlighted field."); return }
        newRecordLocationError(isEdit, media.location)?.let { onError(it); return }
        scope.launch {
            if (!workshop.confirmSubmission()) return@launch
            saving = true
            val body = ToolCreateRequest(
                toolkitName = toolkitName.trim(),
                localName = localName.blankToNull(),
                englishName = englishName.blankToNull(),
                craftName = craftName.trim(),
                artisanName = artisanName.trim(),
                place = place.trim(),
                processUsedIn = processUsedIn.blankToNull(),
                material = material.blankToNull(),
                materialFamily = materialFamily.blankToNull(),
                yearsInUse = yearsInUse.toIntOrNull(),
                height = height.toDoubleOrNull(),
                width = width.toDoubleOrNull(),
                heightInches = heightInches.toDoubleOrNull(),
                lengthInches = length.toDoubleOrNull(),
                breadthInches = breadth.toDoubleOrNull(),
                measurementMethods = markers.body(
                    mapOf(
                        "lengthInches" to length,
                        "breadthInches" to breadth,
                        "heightInches" to heightInches,
                    )
                ),
                thickness = thickness.toDoubleOrNull(),
                weight = weight.toDoubleOrNull(),
                radius = radius.toDoubleOrNull(),
                maker = maker,
                traditionType = traditionType.ifBlank { StandardValues.TRADITION_UNANSWERED },
                replacementCost = replacementCost.toDoubleOrNull(),
                suggestionsForToolImprovement = suggestions.blankToNull(),
                remarks = remarks.blankToNull(),
                artisanId = artisanId.ifBlank { null },
                craftId = craftId.ifBlank { null },
                craftIds = if (!isEdit || craftIds != seededCraftIds) craftIds else null,
                artisanIds = if (!isEdit || artisanIds != seededArtisanIds) artisanIds else null,
                workshopId = workshop.value(),
                status = status,
                recordedAt = if (isEdit) null else Instant.now().toString(),
                location = locationForBody(isEdit, media.location, editing?.location)
            )
            val sitting = CarryContext(
                artisanId = artisanId.ifBlank { null },
                artisanName = artisanName.trim(),
                place = place.trim(),
                craftId = craftId.ifBlank { null },
                craftName = carriedCraftName().orEmpty(),
                workshopId = workshop.value(),
                workshopName = workshop.workshops.firstOrNull { it.id == workshop.value() }?.title
            )
            fun toolOfflineItems(): List<com.fieldrepository.app.data.OfflineMediaSpec> =
                media.uris.mapIndexed { i, uri ->
                    com.fieldrepository.app.data.OfflineMediaSpec(uri = uri, caption = "Field media for ${toolkitName.trim()}", recordName = toolkitName.trim(), batchIndex = i + 1, location = media.location)
                } + stages.uris.mapIndexed { i, uri ->
                    com.fieldrepository.app.data.OfflineMediaSpec(uri = uri, caption = "Process stage step ${i + 1} for ${toolkitName.trim()}", recordName = toolkitName.trim(), stageStep = i + 1, location = stages.location)
                }
            if (!repository.isOnline(context)) {
                val ok = runCatching {
                    val items = toolOfflineItems()
                    repository.queueOfflineEntry(
                        context, "tool", offlineFormJson.encodeToString(body), toolkitName.trim(), items,
                        kind = if (isEdit) OutboxKind.UPDATE else OutboxKind.CREATE,
                        targetId = editing?.id
                    )
                }.isSuccess
                if (ok) {
                    if (isEdit) {
                        carry.remember(sitting.copy(toolId = editing!!.id, toolName = toolkitName.trim()))
                    } else {
                        carry.prune(CarryNode.TOOL)
                        carry.remember(sitting)
                    }
                    media.reset(); stages.reset()
                    onError("Saved offline. Uploads when you're online.")
                    draft.clear(); finish(queued = true); saving = false; return@launch
                } else onError("Couldn't save offline")
                saving = false; return@launch
            }
            runCatching {
                val toolId = if (isEdit) {
                    repository.updateTool(editing!!.id, body).id
                } else {
                    createOrKeep(repository, context, "tool", offlineFormJson.encodeToString(body), toolkitName.trim(),
                        { toolOfflineItems() }) { key -> repository.createTool(body, key) }.id
                }
                uploadAttachments(repository, context, media, "tool", toolId, toolkitName, "Field media for ${toolkitName.trim()}")
                val stagesLeft = mutableListOf<OfflineMediaSpec>()
                stages.uris.forEachIndexed { index, uri ->
                    runCatching {
                        repository.uploadMedia(
                            context = context,
                            uri = uri,
                            linkedRecordType = "tool",
                            linkedRecordId = toolId,
                            caption = "Process stage step ${index + 1} for ${toolkitName.trim()}",
                            location = stages.location,
                            titleHint = toolkitName,
                            batchIndex = 1,
                            stageStep = index + 1
                        )
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        stagesLeft.add(
                            OfflineMediaSpec(
                                uri = uri, caption = "Process stage step ${index + 1} for ${toolkitName.trim()}",
                                recordName = toolkitName.trim(), stageStep = index + 1, location = stages.location
                            )
                        )
                    }
                }
                if (!queueFailedAttachments(repository, context, "tool", toolId, toolkitName, stagesLeft)) {
                    throw IllegalStateException(
                        "Tool saved, but ${countNoun(stagesLeft.size, "capture")} didn't upload. Re-attach from \"Update existing\"."
                    )
                }
                toolId
            }.onSuccess { toolId ->
                carry.remember(sitting.copy(toolId = toolId, toolName = toolkitName.trim()))
                media.reset()
                stages.reset()
                draft.clear(); finish(queued = false)
            }.onFailure { if (it is KeptOnDevice) { carry.prune(CarryNode.TOOL); carry.remember(sitting); media.reset(); stages.reset(); draft.clear(); onError(it.message!!); finish(queued = true) } else onError(it.apiErrorMessage("Unable to save tool")) }
            saving = false
        }
    }
    val toolSig: () -> String = {
        val carried = carry.offer?.context ?: prefill?.toCarryContext()
        listOf(toolkitName, localName, englishName, craftName.exceptCarried(carried?.craftName),
            artisanName.exceptCarried(carried?.artisanName), place.exceptCarried(carried?.place),
            processUsedIn, material,
            yearsInUse, height, width, length, breadth, thickness, weight, radius, maker, traditionType,
            replacementCost, suggestions, remarks, status,
            craftIds.joinToString(",").exceptCarried(carried?.craftId),
            artisanIds.joinToString(",").exceptCarried(carried?.artisanId)).joinToString("")
    }
    val initialSig = remember(editing) { toolSig() }
    val dirty = !saving && (
        toolSig() != initialSig || workshop.isDirty() ||
            media.uris.isNotEmpty() || media.measurementUri != null || stages.uris.isNotEmpty()
    )

    val dictates = recordDictationFor(RecordFormKind.TOOL)
    val toolPhotos = rememberImageCount(media.uris) + rememberImageCount(stages.uris)
    RecordCard(title = if (isEdit) recordUpdateTitle("tool") else "Add tool") {
        DraftOfferCard(draft)
        FormCompletenessLine(
            kind = CompletenessKind.TOOL,
            values = mapOf(
                "artisanId" to artisanId.ifBlank { null },
                "localName" to localName,
                "material" to material,
                "lengthInches" to length.toDoubleOrNull(),
                "breadthInches" to breadth.toDoubleOrNull(),
                "heightInches" to heightInches.toDoubleOrNull(),
                "maker" to maker,
                "traditionType" to traditionType,
                "replacementCost" to replacementCost.toDoubleOrNull()
            ),
            stored = editing?.completeness,
            add = mapOf("photos" to toolPhotos)
        )
        RegisterUnsavedGuard(dirty = dirty) { another = false; submit() }
        media.draftInto(draft, "media")
        stages.draftInto(draft, "stages")
        workshop.draftInto(draft)
        FormDraftAutosave(draft, dirty, label = toolkitName)
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        WorkshopField(state = workshop, saving = saving)
        RequiredInput("Toolkit name", toolkitName, toolkitNameError, toolkitNameFocus, titleCased = true, dictate = dictates("toolkitName")) { applyToolkitName(it) }
        CompletenessAnchor("localName") {
            TextInput("Local name", localName, dictate = dictates("localName")) { localName = it }
        }
        TextInput("English name", englishName, titleCased = true, dictate = dictates("englishName")) { englishName = it; mirrorArmed = false }
        val artisanOptionsForCraft = if (notOnRecord && craftIds.isNotEmpty()) {
            picker.options.filter { it.craftId in craftIds || it.id in artisanIds }
        } else {
            picker.options
        }
        val selectedCrafts = craftOptions.filter { it.id in craftIds }
        fun toolArtisanRows(list: List<ArtisanDto>): List<SelectOption> =
            artisansByCraftThenName(list, selectedCrafts).map { artisan ->
                SelectOption(
                    value = artisan.id,
                    label = artisan.name,
                    hint = listOf(craftNameFor(artisan, selectedCrafts), artisan.place)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { null }
                )
            }
        val artisanRows = toolArtisanRows(artisanOptionsForCraft)
        CompletenessAnchor("artisan") {
            CheckboxMultiSelectField(
                label = "Artisans",
                options = artisanRows,
                selectedIds = artisanIds.toSet(),
                placeholder = "Search artisans",
                emptyMessage = "No artisans recorded yet.",
                enabled = !notOnRecord,
                server = picker.search.asServerSearchRows(countsRows = true) { toolArtisanRows(it) },
                onSelectedChange = { selection -> onArtisansChanged(selection) }
            )
        }
        if (notOnRecord && craftIds.isNotEmpty() && picker.isLoadedFor(craftIds) && artisanOptionsForCraft.isEmpty()) {
            Text(
                if (craftIds.size == 1) "No artisans are linked to this craft yet."
                else "No artisans are linked to these crafts yet.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        artisanPickError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!notOnRecord) {
            ArtisanCardPickerScan(
                repository = repository,
                selected = artisanIds,
                onPicked = { id, _, _ -> if (id !in artisanIds) onArtisansChanged((artisanIds + id).toSet()) },
                onError = onError
            )
        }
        AppToggle(
            label = "Artisan not on record",
            description = "Type the craft, artisan and place yourself.",
            checked = notOnRecord,
            onCheckedChange = { on ->
                notOnRecord = on
                artisanPickError = null
                if (on) artisanIds = emptyList()
            }
        )
        val craftListContext = LocalContext.current.applicationContext
        CraftPickerField(
            label = "Linked crafts",
            required = true,
            multiple = true,
            selectedIds = craftIds,
            fallbackNames = craftOptions.associate { it.id to it.name },
            isError = craftNameError != null,
            supportingText = craftNameError,
            refresh = { repository.craftCatalogue(craftListContext) },
            modifier = Modifier.focusRequester(craftNameFocus),
        ) { next ->
            craftNameError = null
            onCraftsChanged(next.toSet())
        }
        val headArtisan = picker.options.firstOrNull { it.id == artisanIds.firstOrNull() }
        if (notOnRecord || artisanNameError != null) {
            RequiredInput("Artisan name", artisanName, artisanNameError, artisanNameFocus, titleCased = true, dictate = dictates("artisanName")) { artisanName = it }
        }
        if (notOnRecord || (headArtisan != null && headArtisan.place.isBlank()) || placeError != null) {
            RequiredInput("Place", place, placeError, placeFocus, titleCased = true, dictate = dictates("place")) { place = it }
        }
        TextInput("Process used in", processUsedIn, dictate = dictates("processUsedIn")) { processUsedIn = it }
        CompletenessAnchor("material") {
            TextInput("Material", material, dictate = dictates("material")) { material = it }
        }
        DropdownField("Material type", StandardValues.MATERIAL_FAMILY_OPTIONS, materialFamily, placeholder = "Select material type") { materialFamily = it }
        TextInput("Years in use", yearsInUse, keyboardType = KeyboardType.Number, dictate = dictates("yearsInUse")) { yearsInUse = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Height (cm)", height, keyboardType = KeyboardType.Decimal, dictate = dictates("height")) { height = it; propagateDimension(it, ::inchesTextFromCm) { partner -> heightInches = partner } } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Width (cm)", width, keyboardType = KeyboardType.Decimal, dictate = dictates("width")) { width = it; propagateDimension(it, ::inchesTextFromCm) { partner -> breadth = partner } } }
        }
        CompletenessAnchor("dimensions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) { TextInput("Length (inches)", length, keyboardType = KeyboardType.Decimal, dictate = dictates("lengthInches")) { length = it; markers.forget("lengthInches") } }
                Box(modifier = Modifier.weight(1f)) { TextInput("Breadth (inches)", breadth, keyboardType = KeyboardType.Decimal, dictate = dictates("breadthInches")) { breadth = it; markers.forget("breadthInches"); propagateDimension(it, ::cmTextFromInches) { partner -> width = partner } } }
            }
        }
        TextInput("Height (inches)", heightInches, keyboardType = KeyboardType.Decimal, dictate = dictates("heightInches")) { heightInches = it; markers.forget("heightInches"); propagateDimension(it, ::cmTextFromInches) { partner -> height = partner } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Thickness", thickness, keyboardType = KeyboardType.Decimal, dictate = dictates("thickness")) { thickness = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Weight", weight, keyboardType = KeyboardType.Decimal, dictate = dictates("weight")) { weight = it } }
        }
        TextInput("Radius", radius, keyboardType = KeyboardType.Decimal, dictate = dictates("radius")) { radius = it }
        RecordMeasureField(
            dimensions = TOOL_MEASURE_DIMENSIONS,
            current = mapOf(
                "lengthInches" to length,
                "breadthInches" to breadth,
                "heightInches" to heightInches,
            ),
            photos = media.uris,
            enabled = !saving,
            onPropose = { column, text, technique ->
                when (column) {
                    "lengthInches" -> length = text
                    "breadthInches" -> { breadth = text; cmTextFromInches(text)?.let { width = it } }
                    "heightInches" -> { heightInches = text; cmTextFromInches(text)?.let { height = it } }
                }
                markers.accept(column, text, geometryMarker(technique))
            },
        )
        GridMeasurementSection(
            repository = repository,
            media = media,
            includeHeight = true,
            onLengthBreadth = { l, b, marker ->
                if (l != null && l > 0) { length = numToText(l); markers.accept("lengthInches", length, visionMarker(marker)) }
                if (b != null && b > 0) { breadth = numToText(b); markers.accept("breadthInches", breadth, visionMarker(marker)); cmTextFromInches(breadth)?.let { width = it } }
            },
            onHeight = { value, marker -> heightInches = numToText(value); markers.accept("heightInches", heightInches, visionMarker(marker)); cmTextFromInches(heightInches)?.let { height = it } }
        )
        CompletenessAnchor("maker") {
            DropdownField("Maker", makerOptions.map { it to it }, maker, includeNone = false) { maker = it }
        }
        CompletenessAnchor("traditionType") {
            DropdownField("Tradition type", StandardValues.TRADITION_OPTIONS, traditionType, placeholder = "Select tradition type") { traditionType = it }
        }
        CompletenessAnchor("replacementCost") {
            TextInput("Replacement cost", replacementCost, keyboardType = KeyboardType.Decimal, dictate = dictates("replacementCost")) { replacementCost = it }
        }
        TextInput("Suggestions for improvement", suggestions, minLines = 2, dictate = dictates("suggestionsForToolImprovement"), rich = true) { suggestions = it }
        TextInput("Remarks", remarks, minLines = 3, dictate = dictates("remarks"), rich = true) { remarks = it }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        ToolStagesSection(stages = stages, onMessage = onError, onError = onError)
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "tool", recordId = editing!!.id, onError = onError)
        }
        CompletenessAnchor("photo") {
            MediaCaptureSection(repository = repository, media = media, inheritPlace = picker.options.firstOrNull { it.id == artisanIds.firstOrNull() }.inheritedPlace(isEdit), onMessage = onError, onError = onError)
        }
        SaveButtons(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update tool" else "Save tool",
            onSave = { another = false; submit() },
            onSaveAnother = if (!isEdit && onCreated != null) ({ another = true; submit() }) else null
        )
    }
}

@Composable
private fun ToolStagesSection(
    stages: MediaCaptureState,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    val permissionGate = rememberPermissionGate(onRefused = onError)
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) stages.uris = stages.uris + uris
    }
    val camera = rememberKeepShootingCamera(
        newUri = { createAppFileUri(context, "stage-photo-", ".jpg") },
        onShot = { uri -> stages.uris = stages.uris + uri },
        onRunEnded = onMessage
    )
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) stages.uris = stages.uris + uri
        pendingCaptureUri = null
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Process stages", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Capture each step in order.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        ActionRow {
            OutlinedButton(onClick = { pickMedia.launch("*/*") }) { Text("Add files") }
            OutlinedButton(
                onClick = { permissionGate.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) { camera.shoot() } },
            ) { Text("Photo step") }
        }
        KeepShootingToggle()
        ActionRow {
            OutlinedButton(
                onClick = {
                    permissionGate.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                        val uri = createAppFileUri(context, "stage-video-", ".mp4")
                        pendingCaptureUri = uri
                        takeVideo.launch(uri)
                    }
                },
            ) { Text("Video step") }
            OutlinedButton(
                onClick = {
                    if (!recording && !permissionGate.holds(MICROPHONE_PERMISSION)) {
                        permissionGate.ask(MICROPHONE_PERMISSION, MICROPHONE_REFUSED_SENTENCE)
                    } else if (!recording) {
                        runCatching {
                            val file = createAppFile(context, "stage-audio-", ".m4a")
                            recorder = startHeldTake(context, file)
                            recordingFile = file
                            recording = true
                            onMessage("Recording stage audio…")
                        }.onFailure { onError(it.apiErrorMessage("Unable to start stage audio")) }
                    } else {
                        runCatching {
                            stopHeldTake(context)?.let { file -> stages.uris = stages.uris + uriForFile(context, file) }
                        }.onFailure { onError(it.apiErrorMessage("Unable to stop stage audio")) }
                        recorder = null; recordingFile = null; recording = false
                        onMessage("Stage audio step added")
                    }
                },
            ) { if (recording) StopSquareLabel("Stop") else Text("Audio step ●") }
        }
        if (recording) {
            RecordingIndicator(getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) })
        }
        if (stages.uris.isNotEmpty()) {
            Text("${countNoun(stages.uris.size, "stage step")} captured", color = Muted, style = MaterialTheme.typography.bodySmall)
            stages.uris.forEachIndexed { index, uri ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Step ${index + 1}", color = Body, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    AndroidUriPreview(context = context, uri = uri, downloadable = true)
                }
            }
            ActionRow {
                TextButton(onClick = { stages.uris = emptyList() }) { Text("Clear stages") }
            }
        }
    }
}

private class ProcessStepUi(
    val key: String,
    serverId: String?,
    name: String,
    val stepType: String,
    existingMedia: List<MediaFileDto> = emptyList(),
    notes: String? = null
) {
    var serverId by mutableStateOf(serverId)
    var name by mutableStateOf(name)
    var existingMedia by mutableStateOf(existingMedia)
    var nameError by mutableStateOf<String?>(null)
    val nameFocus = FocusRequester()
    val media = MediaCaptureState()
    var recordAdditional by mutableStateOf(!notes.isNullOrBlank())
    var notes by mutableStateOf(notes ?: "")
    var templateName by mutableStateOf<String?>(null)
}

private fun ProcessStepUi.stepTypeLabel(): String =
    if (stepType == "SEQUENTIAL") "Sequential" else "Group of activities"

private fun processStepSegment(stepNumber: Int, stepType: String, fileIndex: Int): String =
    if (stepType == "SEQUENTIAL") "STEP_${stepNumber}${'A' + (fileIndex % 26)}"
    else "STEP_${stepNumber}_G${fileIndex + 1}"

@Composable
private fun ProcessForm(
    repository: FieldRepository,
    editing: ProcessDetailDto? = null,
    adminView: Boolean = false,
    prefill: Prefill? = null,
    onCreated: ((Prefill, String?, Boolean) -> Unit)? = null,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = rememberFormDraft(repository, "process", editing?.id)
    val isEdit = editing != null
    val preMedia = rememberMediaCaptureState()

    var products by remember { mutableStateOf<List<ProductDetailDto>>(emptyList()) }
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var artisanListState by remember { mutableStateOf(CarryScopeState.PENDING) }
    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }.drafted(draft, "name")
    var artisanId by remember(editing) { mutableStateOf(editing?.product?.artisanId ?: "") }.drafted(draft, "artisanId")
    var productId by remember(editing) { mutableStateOf(editing?.productId ?: "") }.drafted(draft, "productId")
    var artisanError by remember { mutableStateOf<String?>(null) }
    var preProcessAvailable by remember(editing) { mutableStateOf(editing?.preProcessAvailable ?: false) }.drafted(draft, "preProcessAvailable")
    var notes by remember(editing) { mutableStateOf(editing?.notes ?: "") }.drafted(draft, "notes")
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing, runningOnly = true)
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }.drafted(draft, "status")
    var saving by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var productError by remember { mutableStateOf<String?>(null) }
    var stepsError by remember { mutableStateOf<String?>(null) }
    var preMediaError by remember { mutableStateOf<String?>(null) }
    var existingPreMedia by remember(editing) { mutableStateOf(editing?.media ?: emptyList()) }
    val nameFocus = remember { FocusRequester() }
    var addMenu by remember { mutableStateOf(false) }
    var another by remember { mutableStateOf(false) }

    val steps = remember(editing) {
        mutableStateListOf<ProcessStepUi>().apply {
            editing?.steps?.forEach { add(ProcessStepUi(java.util.UUID.randomUUID().toString(), it.id, it.name, it.stepType, it.media, it.notes)) }
        }
    }

    var artisanSeed by remember { mutableStateOf<PageResponse<ArtisanDto>?>(null) }
    LaunchedEffect(Unit) {
        runCatching { repository.products() }.onSuccess { products = it }
        val gotArtisans = runCatching { repository.artisansPage() }
            .onSuccess {
                artisans = it.items
                artisanSeed = it
            }
            .isSuccess
        artisanListState = if (gotArtisans) carryScopeStateFor(artisanSeed) else CarryScopeState.UNAVAILABLE
    }

    val artisanPicker = rememberArtisanPicker(repository, artisans, "", artisanId, seed = artisanSeed)
    val artisanOptions = artisanPicker.options

    val carriedProduct = remember { mutableStateOf<String?>(null) }

    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.PROCESS_FORM,
        scopes = listOf(carryScope(CarryNode.ARTISAN, artisanListState, artisanOptions) { it.id }),
        handoff = prefill
    ) { carried ->
        carried.artisanId?.let { artisanId = it }
        carried.productId?.let { carriedProduct.value = it }
        carried.workshopId?.let { if (!workshop.isDirty()) workshop.applyDefault(it) }
    }
    fun clearCarriedContext() {
        carry.change()
        carriedProduct.value = null
        artisanId = ""
        productId = ""
    }

    var artisanProducts by remember { mutableStateOf<List<ProductDetailDto>>(emptyList()) }
    var productsLoading by remember { mutableStateOf(false) }
    var productLoadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(artisanId, artisanOptions, products) {
        productLoadError = null
        if (artisanId.isBlank()) {
            artisanProducts = emptyList()
            productsLoading = false
            return@LaunchedEffect
        }
        productsLoading = true
        val selectedArtisanName = artisanOptions.firstOrNull { it.id == artisanId }?.name?.trim()
        val result = runCatching {
            val linked = repository.productsForArtisan(artisanId, selectedArtisanName)
            val broad = if (products.isNotEmpty()) products else runCatching { repository.products() }.getOrDefault(emptyList())
            val byName = broad.filter { p ->
                (p.artisanId != null && p.artisanId == artisanId) ||
                    (!selectedArtisanName.isNullOrBlank() && p.artisanName.trim().equals(selectedArtisanName, ignoreCase = true))
            }
            (linked + byName).distinctBy { it.id }
        }
        productsLoading = false
        result.onSuccess { fetched ->
            artisanProducts = fetched
            carriedProduct.value?.let { carried ->
                carriedProduct.value = null
                if (fetched.any { it.id == carried }) productId = carried else carry.prune(CarryNode.PRODUCT)
            }
            if (productId.isNotBlank() && fetched.none { it.id == productId }) productId = ""
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            artisanProducts = emptyList()
            productLoadError = "${it.apiErrorMessage("Couldn't load this artisan's products.")} Tap the artisan again to retry."
        }
    }

    fun finishProcess(sitting: CarryContext, queued: Boolean) {
        val created = onCreated
        if (isEdit || created == null || (queued && !another)) { onDone(); return }
        created(
            Prefill(
                artisanId = sitting.artisanId,
                artisanName = sitting.artisanName,
                place = sitting.place,
                craftId = sitting.craftId,
                craftName = sitting.craftName,
                productId = sitting.productId,
                productName = sitting.productName
            ),
            name.trim(),
            another
        )
    }

    fun submit() {
        nameError = null; productError = null; stepsError = null; preMediaError = null; artisanError = null
        var firstInvalid = false
        if (name.isBlank()) { nameError = REQUIRED_FIELD_SENTENCE; if (!firstInvalid) { firstInvalid = true; runCatching { nameFocus.requestFocus() } } }
        if (artisanId.isBlank()) artisanError = "Please select an artisan"
        if (productId.isBlank()) productError = "Please select a product"
        if (preProcessAvailable && preMedia.uris.isEmpty() && (editing?.media?.isEmpty() != false)) {
            preMediaError = "Attach the pre-process media or uncheck the box"
        }
        if (steps.isEmpty()) stepsError = "Add at least one step"
        steps.forEach { step -> step.nameError = null }
        val blocked = nameError != null || productError != null || stepsError != null ||
            preMediaError != null || artisanError != null
        if (blocked) { onError("Fill in the highlighted fields."); return }

        scope.launch {
            if (!workshop.confirmSubmission()) return@launch
            saving = true
            val stepRequests = steps.mapIndexed { i, s ->
                ProcessStepRequest(
                    id = s.serverId,
                    name = s.name.trim(),
                    stepType = s.stepType,
                    sortOrder = i + 1,
                    notes = if (s.recordAdditional) s.notes.trim().ifBlank { null } else null
                )
            }
            val body = ProcessCreateRequest(
                name = name.trim(),
                productId = productId,
                preProcessAvailable = preProcessAvailable,
                notes = notes.blankToNull(),
                status = status,
                steps = stepRequests,
                workshopId = workshop.value(),
                recordedAt = if (isEdit) null else Instant.now().toString()
            )
            val savedArtisan = artisanOptions.firstOrNull { it.id == artisanId }
            val sitting = CarryContext(
                artisanId = artisanId.ifBlank { null },
                artisanName = savedArtisan?.name,
                place = savedArtisan?.place,
                craftId = savedArtisan?.craftId,
                craftName = savedArtisan?.craft?.name,
                productId = productId.ifBlank { null },
                productName = artisanProducts.firstOrNull { it.id == productId }?.productName,
                workshopId = workshop.value(),
                workshopName = workshop.workshops.firstOrNull { it.id == workshop.value() }?.title
            )
            fun processOfflineItems(): List<com.fieldrepository.app.data.OfflineMediaSpec> {
                val items = mutableListOf<com.fieldrepository.app.data.OfflineMediaSpec>()
                if (preProcessAvailable) {
                    preMedia.uris.forEachIndexed { i, uri ->
                        items.add(com.fieldrepository.app.data.OfflineMediaSpec(
                            uri = uri, caption = "Pre-process media for ${name.trim()}", recordName = name.trim(),
                            customSegment = "PRE", batchIndex = i + 1, linkedType = "process", location = preMedia.location))
                    }
                }
                steps.forEachIndexed { index, local ->
                    local.media.uris.forEachIndexed { fileIndex, uri ->
                        items.add(com.fieldrepository.app.data.OfflineMediaSpec(
                            uri = uri, caption = "Process step ${local.name.trim().ifBlank { "${index + 1}" }}", recordName = name.trim(),
                            customSegment = processStepSegment(index + 1, local.stepType, fileIndex),
                            batchIndex = fileIndex + 1, linkedType = "processstep", stepIndex = index,
                            location = local.media.location))
                    }
                }
                return items
            }
            if (!repository.isOnline(context)) {
                val ok = runCatching {
                    val items = processOfflineItems()
                    repository.queueOfflineEntry(
                        context, "process", offlineFormJson.encodeToString(body), name.trim(), items,
                        kind = if (isEdit) OutboxKind.UPDATE else OutboxKind.CREATE,
                        targetId = editing?.id
                    )
                }.isSuccess
                if (ok) {
                    carry.remember(sitting)
                    preMedia.reset(); steps.forEach { it.media.reset() }
                    onError("Saved offline. Uploads when you're online.")
                    draft.clear(); finishProcess(sitting, queued = true)
                } else onError("Couldn't save offline")
                saving = false
                return@launch
            }
            runCatching {
                val detail = if (isEdit) repository.updateProcess(editing!!.id, body) else createOrKeep(repository, context, "process", offlineFormJson.encodeToString(body), name.trim(),
                    { processOfflineItems() }) { key -> repository.createProcess(body, key) }
                if (preProcessAvailable) {
                    uploadAttachments(repository, context, preMedia, "process", detail.id, name, "Pre-process media for ${name.trim()}", customSegment = "PRE")
                }
                detail.steps.forEachIndexed { index, serverStep ->
                    val local = steps.getOrNull(index) ?: return@forEachIndexed
                    val stepLeft = mutableListOf<OfflineMediaSpec>()
                    local.media.uris.forEachIndexed { fileIndex, uri ->
                        val segment = processStepSegment(index + 1, serverStep.stepType, fileIndex)
                        val staged = local.media.stagedDeferred[uri]?.let { runCatching { it.await() }.getOrNull() } ?: local.media.staged[uri]
                        runCatching { if (staged != null) {
                            repository.completeStaged(
                                staged = staged,
                                linkedRecordType = "processstep",
                                linkedRecordId = serverStep.id,
                                recordName = name,
                                caption = "Process step ${serverStep.name.ifBlank { "${index + 1}" }}",
                                location = local.media.location,
                                batchIndex = fileIndex + 1,
                                customSegment = segment,
                                processingRequests = TranscribeChoices.processingFor(uri.toString(), staged.mediaType)
                            )
                        } else {
                            repository.uploadMedia(
                                context = context,
                                uri = uri,
                                linkedRecordType = "processstep",
                                linkedRecordId = serverStep.id,
                                caption = "Process step ${serverStep.name.ifBlank { "${index + 1}" }}",
                                location = local.media.location,
                                titleHint = name,
                                batchIndex = fileIndex + 1,
                                customSegment = segment
                            )
                        } }.onFailure { error ->
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            stepLeft.add(
                                OfflineMediaSpec(
                                    uri = uri, caption = "Process step ${serverStep.name.ifBlank { "${index + 1}" }}", recordName = name.trim(),
                                    customSegment = segment, batchIndex = fileIndex + 1, location = local.media.location
                                )
                            )
                        }
                    }
                    if (!queueFailedAttachments(repository, context, "processstep", serverStep.id, name, stepLeft)) {
                        throw IllegalStateException(
                            "Process saved, but ${countNoun(stepLeft.size, "file")} didn't upload. Re-attach from \"Update existing\"."
                        )
                    }
                }
            }.onSuccess {
                carry.remember(sitting)
                preMedia.reset()
                steps.forEach { it.media.reset() }
                draft.clear(); finishProcess(sitting, queued = false)
            }.onFailure { if (it is KeptOnDevice) { carry.remember(sitting); preMedia.reset(); steps.forEach { s -> s.media.reset() }; draft.clear(); onError(it.message!!); finishProcess(sitting, queued = true) } else onError(it.apiErrorMessage("Unable to save process")) }
            saving = false
        }
    }
    val procSig: () -> String = {
        val carried = carry.offer?.context
        listOf(name, artisanId.exceptCarried(carried?.artisanId), productId.exceptCarried(carried?.productId),
            notes, status, preProcessAvailable.toString(),
            steps.joinToString("|") { "${it.serverId}~${it.name}~${it.stepType}~${it.notes}~${it.media.uris.size}" }).joinToString("")
    }
    val initialSig = remember(editing) { procSig() }
    val dirty = !saving && (
        procSig() != initialSig || workshop.isDirty() ||
            preMedia.uris.isNotEmpty() || steps.any { it.media.uris.isNotEmpty() }
    )

    val dictates = recordDictationFor(RecordFormKind.PROCESS)
    RecordCard(title = if (isEdit) recordUpdateTitle("process") else "Document process") {
        DraftOfferCard(draft)
        RegisterUnsavedGuard(dirty = dirty) { another = false; submit() }
        preMedia.draftInto(draft, "preMedia")
        workshop.draftInto(draft)
        bindProcessSteps(draft, steps) { id -> editing?.steps?.firstOrNull { it.id == id }?.media.orEmpty() }
        FormDraftAutosave(draft, dirty, label = name)
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        Text(
            "How a product is made, step by step.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        WorkshopField(state = workshop, saving = saving)
        RequiredInput("Name of the process", name, nameError, nameFocus, titleCased = true, dictate = dictates("name")) { name = it }
        DropdownField(
            label = "Artisan *",
            options = artisanOptions.map { it.id to "${it.name} · ${it.place}" },
            selectedValue = artisanId,
            placeholder = "Select the artisan",
            includeNone = false,
            server = artisanPicker.search.asServerSearch(::artisanSelectOption)
        ) { picked ->
            if (picked != artisanId) {
                artisanId = picked
                productId = ""
                carriedProduct.value = null
                artisanOptions.firstOrNull { it.id == picked }?.let {
                    carry.remember(
                        CarryContext(artisanId = it.id, artisanName = it.name, place = it.place),
                        explicit = true
                    )
                }
            }
        }
        if (artisanError != null) Text(artisanError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        DropdownField(
            label = "Product *",
            options = artisanProducts.map { it.id to it.productName },
            selectedValue = productId,
            placeholder = when {
                artisanId.isBlank() -> "Select an artisan first"
                productsLoading -> "Loading products…"
                artisanProducts.isEmpty() -> "No products for this artisan"
                else -> "Select the product this process makes"
            },
            includeNone = false,
            enabled = artisanId.isNotBlank() && !productsLoading && artisanProducts.isNotEmpty()
        ) { picked ->
            productId = picked
            artisanProducts.firstOrNull { it.id == picked }?.let { product ->
                carry.prune(CarryNode.PRODUCT)
                val artisan = artisanOptions.firstOrNull { it.id == artisanId }
                carry.remember(
                    CarryContext(
                        artisanId = artisanId.ifBlank { null },
                        artisanName = artisan?.name,
                        place = artisan?.place,
                        craftId = artisan?.craftId,
                        productId = product.id,
                        productName = product.productName
                    )
                )
            }
        }
        when {
            productsLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Loading this artisan's products…", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            productLoadError != null -> Text(productLoadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            artisanId.isNotBlank() && artisanProducts.isEmpty() ->
                Text("No products for this artisan yet. Create one first.", color = Muted, style = MaterialTheme.typography.bodySmall)
            artisanId.isNotBlank() && artisanProducts.isNotEmpty() ->
                Text("${countNoun(artisanProducts.size, "product")}.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (productError != null) Text(productError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        AppToggle(
            label = "Pre-processes available",
            checked = preProcessAvailable,
            onCheckedChange = { preProcessAvailable = it }
        )
        if (preProcessAvailable) {
            Text("Attach the pre-process media (required).", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (existingPreMedia.isNotEmpty()) {
                Text("Already attached:", color = Muted, style = MaterialTheme.typography.bodySmall)
                existingPreMedia.forEach { saved ->
                    AndroidSavedMediaPreview(
                        context = context,
                        media = saved,
                        onDelete = {
                            scope.launch {
                                runCatching { repository.deleteMedia(saved.id) }
                                    .onSuccess { existingPreMedia = existingPreMedia.filterNot { it.id == saved.id } }
                                    .onFailure { error -> onError(error.apiErrorMessage("Unable to remove media")) }
                            }
                        }
                    )
                }
            }
            MediaCaptureSection(repository = repository, media = preMedia, emphasizeVideo = true, onMessage = onError, onError = onError)
            if (preMediaError != null) Text(preMediaError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("Steps", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        if (stepsError != null) Text(stepsError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        steps.forEachIndexed { index, step ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Step ${index + 1} · ${step.stepTypeLabel()}", color = Body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { steps.removeAt(index) }) { Text("Remove") }
                    }
                    TextInput("Name of the step", step.name, dictate = dictates("stepName")) { step.name = it }
                    when {
                        step.name.isBlank() -> Text("Unnamed step. Name it now or later.", color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall)
                        step.templateName != null && step.name == step.templateName ->
                            Text("Copied from the last process. Check it matches.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (step.existingMedia.isNotEmpty()) {
                        Text("Already attached:", color = Muted, style = MaterialTheme.typography.bodySmall)
                        step.existingMedia.forEach { saved ->
                            AndroidSavedMediaPreview(
                                context = context,
                                media = saved,
                                onDelete = {
                                    scope.launch {
                                        runCatching { repository.deleteMedia(saved.id) }
                                            .onSuccess { step.existingMedia = step.existingMedia.filterNot { it.id == saved.id } }
                                            .onFailure { error -> onError(error.apiErrorMessage("Unable to remove media")) }
                                    }
                                }
                            )
                        }
                    }
                    MediaCaptureSection(
                        repository = repository,
                        media = step.media,
                        emphasizeVideo = true,
                        showKeepShootingToggle = false,
                        beforeLocation = {
                            AppToggle(
                                label = "Record additional information",
                                checked = step.recordAdditional,
                                onCheckedChange = { step.recordAdditional = it; if (!it) step.notes = "" }
                            )
                            if (step.recordAdditional) {
                                MultiNoteInput(label = "Additional context for this step", value = step.notes, resetKey = step.key, dictate = dictates("stepNotes")) { step.notes = it }
                            }
                        },
                        onMessage = onError,
                        onError = onError
                    )
                }
            }
        }

        val stepsFromPhotos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            orderByCaptureTime(context, uris).forEach { uri ->
                steps.add(ProcessStepUi(java.util.UUID.randomUUID().toString(), null, "", "SEQUENTIAL").also { it.media.uris = listOf(uri) })
            }
            if (uris.isNotEmpty()) onError("${plural(uris.size, "step")} added from photos. Name them when you can.")
        }
        val stepCamera = rememberKeepShootingCamera(
            newUri = { createAppFileUri(context, "step-photo-", ".jpg") },
            onShot = { uri -> steps.add(ProcessStepUi(java.util.UUID.randomUUID().toString(), null, "", "SEQUENTIAL").also { it.media.uris = listOf(uri) }) },
            onRunEnded = onError
        )
        val stepGate = rememberPermissionGate(onRefused = onError)
        ActionRow {
            OutlinedButton(onClick = { stepsFromPhotos.launch("image/*") }) { Text("Steps from photos") }
            OutlinedButton(onClick = { stepGate.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) { stepCamera.shoot() } }) { Text("Photograph steps") }
        }
        val processCraftId = artisanProducts.firstOrNull { it.id == productId }?.craftId
            ?: artisanOptions.firstOrNull { it.id == artisanId }?.craftId
        var templateLoading by remember { mutableStateOf(false) }
        if (!isEdit && processCraftId != null && steps.none { it.name.isNotBlank() }) {
            ActionRow {
                OutlinedButton(
                    enabled = !templateLoading,
                    onClick = {
                        scope.launch {
                            templateLoading = true
                            runCatching { repository.latestProcessForCraft(processCraftId, editing?.id) }
                                .onSuccess { last ->
                                    if (last == null) {
                                        onError("No earlier process for this craft yet.")
                                    } else {
                                        val unnamed = steps.filter { it.name.isBlank() && it.media.uris.isEmpty() && it.existingMedia.isEmpty() }
                                        steps.removeAll(unnamed)
                                        last.steps.sortedBy { it.sortOrder }.forEach { source ->
                                            steps.add(
                                                ProcessStepUi(java.util.UUID.randomUUID().toString(), null, source.name, source.stepType)
                                                    .also { it.templateName = source.name }
                                            )
                                        }
                                        onError("${plural(last.steps.size, "step name")} copied from “${last.name}”. Check each one.")
                                    }
                                }
                                .onFailure { onError(it.apiErrorMessage("Couldn't load the craft's last process.")) }
                            templateLoading = false
                        }
                    }
                ) { Text(if (templateLoading) "Loading…" else "Start from this craft's last process") }
            }
        }
        Box {
            OutlinedButton(onClick = { addMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Another Step")
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Sequential") },
                    onClick = { steps.add(ProcessStepUi(java.util.UUID.randomUUID().toString(), null, "", "SEQUENTIAL")); addMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Group of activities") },
                    onClick = { steps.add(ProcessStepUi(java.util.UUID.randomUUID().toString(), null, "", "GROUP")); addMenu = false }
                )
            }
        }

        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                "Each researcher's account is kept separately.",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        SaveButtons(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update process" else "Save process",
            onSave = { another = false; submit() },
            onSaveAnother = if (!isEdit && onCreated != null) ({ another = true; submit() }) else null
        )
    }
}

private val viewDataModes = listOf(
    EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.TOOL,
    EntryMode.QUESTIONNAIRE, EntryMode.WORKSHOP, EntryMode.CRAFT, EntryMode.MEDIA
)

private fun EntryMode.linkedRecordType(): String = when (this) {
    EntryMode.ARTISAN -> "artisan"
    EntryMode.PRODUCT -> "product"
    EntryMode.TOOL -> "tool"
    EntryMode.WORKSHOP -> "workshop"
    EntryMode.CRAFT -> "craft"
    EntryMode.QUESTIONNAIRE -> "questionnaire"
    EntryMode.PROCESS -> "process"
    else -> name.lowercase()
}

private fun interviewGroupKey(iv: QuestionnaireInterviewDetailDto): String {
    val ids = iv.artisans.map { it.artisanId }.toSortedSet()
    return if (ids.isEmpty()) "iv:${iv.id}" else "set:${ids.joinToString(",")}"
}

private fun representativeInterview(group: List<QuestionnaireInterviewDetailDto>): QuestionnaireInterviewDetailDto =
    group.maxByOrNull { it.createdAt ?: "" } ?: group.first()

private suspend fun interviewGroup(
    repository: FieldRepository,
    of: QuestionnaireInterviewDetailDto
): List<QuestionnaireInterviewDetailDto> {
    val anchor = of.artisans.map { it.artisanId }.minOrNull() ?: return listOf(of)
    val key = interviewGroupKey(of)
    val siblings = runCatching { repository.interviewsForArtisan(anchor) }.getOrDefault(emptyList())
    val group = siblings.filter { interviewGroupKey(it) == key }
    return if (group.any { it.id == of.id }) group else group + of
}

private data class RecordRow(
    val id: String,
    val label: String,
    val text: String,
    val interview: QuestionnaireInterviewDetailDto? = null,
    val completeness: CompletenessDto? = null,
    val chips: com.fieldrepository.app.questionnaire.data.QChips? = null,
    val preview: com.fieldrepository.app.transcript.data.TranscriptPreviewDto? = null
)

private suspend fun recordRowsPage(
    repository: FieldRepository,
    mode: EntryMode,
    search: String?,
    page: Int,
    workshopIds: List<String>? = EVERY_WORKSHOP,
    completeness: String? = null,
    mediaFilters: Map<String, String> = emptyMap()
): PageResponse<RecordRow> {
    fun <T> PageResponse<T>.rows(row: (T) -> RecordRow) =
        PageResponse(items.map(row), total, this.page, pageSize, pages)
    return when (mode) {
        EntryMode.ARTISAN -> repository.artisansSearchPage(search, page, completeness = completeness).rows {
            RecordRow(it.id, recordOptionLabel(it), artisanSearchText(it), completeness = it.completeness)
        }
        EntryMode.CRAFT -> repository.craftsSearchPage(search, page).rows {
            RecordRow(it.id, recordOptionLabel(it), craftSearchText(it))
        }
        EntryMode.PRODUCT -> repository.productsSearchPage(search, page, completeness).rows {
            RecordRow(it.id, recordOptionLabel(it), "${it.productName} ${it.artisanName} ${it.craftName}", completeness = it.completeness)
        }
        EntryMode.PROCESS -> repository.processesSearchPage(search, page).rows {
            RecordRow(it.id, recordOptionLabel(it), "${it.name} ${it.product?.productName.orEmpty()}")
        }
        EntryMode.TOOL -> repository.toolsSearchPage(search, page, completeness).rows {
            RecordRow(it.id, recordOptionLabel(it), "${it.toolkitName} ${it.toolArtisanNames().orEmpty()} ${it.craftName}", completeness = it.completeness)
        }
        EntryMode.WORKSHOP -> repository.workshopsSearchPage(search, page, completeness).rows {
            val title = workshopDisplayTitle(it.title, date = it.occurrenceDate(), place = it.place)
            RecordRow(it.id, title, "$title ${it.place.orEmpty()}", completeness = it.completeness)
        }
        EntryMode.MEDIA -> repository.mediaSearchPage(search, page, mediaFilters).rows { m ->
            val label = m.caption?.takeIf { it.isNotBlank() } ?: m.originalFilename.ifBlank { "Media" }
            val words = com.fieldrepository.app.questionnaire.data.ChipRules.display(m.questionnaireChips).joinToString(" ") { it.label }
            RecordRow(m.id, label, "$label ${m.originalFilename} ${m.mediaType} $words", chips = m.questionnaireChips, preview = m.transcriptPreview)
        }
        EntryMode.QUESTIONNAIRE -> repository.interviewsSearchPage(workshopIds, search, page, completeness).rows { iv ->
            val names = iv.artisans.mapNotNull { it.artisan?.name }.distinct().joinToString(", ")
            RecordRow(iv.id, iv.title.ifBlank { "Untitled interview" }, "$names ${iv.title}", interview = iv, completeness = iv.completeness)
        }
        else -> PageResponse(emptyList(), 0, 1, SERVER_SEARCH_PAGE_SIZE, 0)
    }
}

private fun recordRowOptions(mode: EntryMode, rows: List<RecordRow>): List<SelectOption> =
    if (mode != EntryMode.QUESTIONNAIRE) {
        rows.map { SelectOption(it.id, it.label) }
    } else {
        rows.mapNotNull { it.interview }.groupBy { interviewGroupKey(it) }.values.map { group ->
            val rep = representativeInterview(group)
            val artisanNames = rep.artisans.mapNotNull { it.artisan?.name }.distinct().joinToString(", ")
            val parts = listOfNotNull(
                artisanNames.ifBlank { null },
                rep.title.takeIf { it.isNotBlank() },
                if (group.size > 1) "${group.size} sessions" else null
            )
            SelectOption(rep.id, parts.joinToString(" · ").ifBlank { "Untitled interview" })
        }.sortedBy { it.label.lowercase() }
    }

@Composable
private fun rememberRecordRows(
    repository: FieldRepository,
    mode: EntryMode?,
    workshopIds: List<String>? = EVERY_WORKSHOP,
    key: Any? = mode,
    enabled: Boolean = mode != null,
    completeness: String? = null,
    mediaFilters: Map<String, String> = emptyMap(),
    keepAs: String? = null
): ServerSearchState<RecordRow> = rememberServerSearch(
    key = key,
    cached = emptyList(),
    seed = null,
    idOf = { it.id },
    textOf = { it.text },
    enabled = enabled,
    keepAs = keepAs
) { term, page -> recordRowsPage(repository, mode ?: EntryMode.ARTISAN, term, page, workshopIds, completeness, mediaFilters) }

private data class ActivityItem(
    val mode: EntryMode,
    val id: String,
    val title: String,
    val subtitle: String,
    val createdAt: String?
)

private data class MyActivityResult(
    val items: List<ActivityItem>,
    val failedTypes: List<String>,
    val next: Map<String, Int> = emptyMap(),
) {
    val truncated: Boolean get() = next.isNotEmpty()
}

private suspend fun loadMyActivity(
    repository: FieldRepository,
    userId: String,
    previous: MyActivityResult? = null,
): MyActivityResult {
    val from = previous?.next
    val items = mutableListOf<ActivityItem>()
    val failed = mutableListOf<String>()
    val next = mutableMapOf<String, Int>()
    var firstFailure: Throwable? = null
    fun <T> kotlinx.coroutines.CoroutineScope.collect(label: String, load: suspend (Int) -> MyRecordsPage<T>, row: (T) -> ActivityItem) {
        val start = if (from == null) 1 else from[label] ?: return
        launch {
            runCatching { load(start) }
                .onSuccess { page -> page.nextPage?.let { next[label] = it }; page.items.forEach { items.add(row(it)) } }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    failed += label
                    if (firstFailure == null) firstFailure = it
                    if (from != null) next[label] = start
                }
        }
    }
    kotlinx.coroutines.coroutineScope {
    collect("artisans", { repository.myArtisans(userId, it) }) { ActivityItem(EntryMode.ARTISAN, it.id, it.name, "Artisan · ${it.place}", it.createdAt) }
    collect("products", { repository.myProducts(userId, it) }) { ActivityItem(EntryMode.PRODUCT, it.id, it.productName, "Product · ${it.craftName}", it.createdAt) }
    collect("tools", { repository.myTools(userId, it) }) { ActivityItem(EntryMode.TOOL, it.id, it.toolkitName, "Tool · ${it.craftName}", it.createdAt) }
    collect("processes", { repository.myProcesses(userId, it) }) {
        ActivityItem(EntryMode.PROCESS, it.id, it.name, "Process" + (it.product?.productName?.let { p -> " · $p" } ?: ""), it.createdAt)
    }
    collect("crafts", { repository.myCrafts(userId, it) }) { ActivityItem(EntryMode.CRAFT, it.id, it.name, "Craft", it.createdAt) }
    collect("workshops", { repository.myWorkshops(userId, it) }) {
        ActivityItem(EntryMode.WORKSHOP, it.id, workshopDisplayTitle(it.title, date = it.occurrenceDate(), place = it.place), "Workshop", it.createdAt)
    }
    collect("interviews", { repository.myInterviews(userId, it) }) {
        ActivityItem(EntryMode.QUESTIONNAIRE, it.id, it.title.ifBlank { "Untitled interview" }, "Interview", it.createdAt)
    }
    }
    if (previous == null && failed.size == 7) throw firstFailure ?: IllegalStateException("Couldn't load your activity.")
    if (previous == null) return MyActivityResult(items.sortedByDescending { it.createdAt ?: "" }, failed, next)
    val seen = previous.items.map { "${it.mode.name}:${it.id}" }.toHashSet()
    return MyActivityResult(
        items = (previous.items + items.filter { "${it.mode.name}:${it.id}" !in seen }).sortedByDescending { it.createdAt ?: "" },
        failedTypes = (previous.failedTypes + failed).distinct(),
        next = next,
    )
}

@Composable
private fun MyActivityScreen(
    repository: FieldRepository,
    userId: String,
    onOpen: (EntryMode, String) -> Unit,
    onError: (String) -> Unit,
    chrome: LazyPageChrome
) {
    var activity by rememberKept("myActivity.loaded") { null as MyActivityResult? }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadingOlder by remember { mutableStateOf(false) }
    var olderFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        runCatching { loadMyActivity(repository, userId) }
            .onSuccess { activity = it; loadError = null }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                loadError = it.apiErrorMessage("Couldn't load your activity.")
            }
    }
    val current = activity
    val failure = loadError
    PageLazyColumn(chrome, spacing = 12.dp) {
        item(key = "intro", contentType = "note") {
            Text("Your records, newest first.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (current != null && current.failedTypes.isNotEmpty()) {
            item(key = "partial", contentType = "card") {
                LoadFailedCard(
                    message = "Couldn't load your ${current.failedTypes.joinToString(", ")}. Everything else is below.",
                    onRetry = { attempt += 1 }
                )
            }
        }
        when {
            current == null && failure != null -> item(key = "failed", contentType = "card") {
                LoadFailedCard(message = failure, onRetry = { loadError = null; attempt += 1 })
            }
            current == null -> item(key = "loading", contentType = "note") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading your activity…", color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            current.items.isEmpty() && current.failedTypes.isEmpty() -> item(key = "empty", contentType = "note") {
                Text("Nothing recorded yet.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            else -> items(
                current.items.distinctBy { "${it.mode.name}:${it.id}" },
                key = { "${it.mode.name}:${it.id}" },
                contentType = { "activity" }
            ) { item -> ActivityCard(item = item, onOpen = onOpen) }
        }
        if (current != null && current.truncated && current.items.isNotEmpty()) {
            item(key = "older", contentType = "note") {
                MyActivityOlder(shown = current.items.size, loading = loadingOlder, failed = olderFailed) {
                    scope.launch {
                        loadingOlder = true
                        olderFailed = false
                        runCatching { loadMyActivity(repository, userId, previous = current) }
                            .onSuccess { activity = it }
                            .onFailure {
                                if (it is kotlinx.coroutines.CancellationException) throw it
                                olderFailed = true
                            }
                        loadingOlder = false
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(item: ActivityItem, onOpen: (EntryMode, String) -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = "Open ${item.title}") { onOpen(item.mode, item.id) }
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, display = true, color = Body, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(
                item.subtitle + (formatIsoDate(item.createdAt)?.let { " · $it" } ?: ""),
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}


@Composable
private fun ToolAssignScreen(
    repository: FieldRepository,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var toolId by remember { mutableStateOf("") }
    var craftIds by remember { mutableStateOf(setOf<String>()) }
    var artisanIds by remember { mutableStateOf(setOf<String>()) }
    var assigned by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    val toolSearch = rememberServerSearch(
        key = Unit, cached = emptyList<ToolDetailDto>(), seed = null, idOf = { it.id },
        textOf = { "${it.toolkitName} ${it.craftName}" }
    ) { term, page -> repository.toolsSearchPage(term, page) }
    val craftScope = craftIds.sorted()
    val artisanSearch = rememberServerSearch(
        key = craftScope, cached = emptyList<ArtisanDto>(), seed = null, idOf = { it.id },
        textOf = ::artisanSearchText, enabled = craftScope.isNotEmpty()
    ) { term, page -> repository.artisansForCraftsPage(craftScope, term, page) }
    val tools = toolSearch.known
    val artisans = artisanSearch.known
    LaunchedEffect(toolId) {
        if (toolId.isBlank()) { assigned = emptyList(); return@LaunchedEffect }
        runCatching { repository.toolArtisans(toolId) }.onSuccess { assigned = it }.onFailure { assigned = emptyList() }
    }

    val artisansForCrafts = remember(artisans, craftIds) {
        artisans.filter { it.craftId != null && craftIds.contains(it.craftId) }
    }
    LaunchedEffect(artisansForCrafts) {
        artisanIds = artisanIds.filter { id -> artisansForCrafts.any { it.id == id } }.toSet()
    }

    RecordCard(title = "Assign a tool to multiple artisans") {
        Text(
            "Link one tool to several artisans.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        DropdownField(
            label = "Tool *",
            options = tools.map { it.id to "${it.toolkitName} · ${it.craftName}" },
            selectedValue = toolId,
            placeholder = "Select a tool",
            includeNone = false,
            server = toolSearch.asServerSearch { SelectOption(it.id, "${it.toolkitName} · ${it.craftName}") }
        ) { toolId = it }
        val craftListContext = LocalContext.current.applicationContext
        CraftPickerField(
            label = "Crafts",
            multiple = true,
            selectedIds = craftIds.toList(),
            refresh = { repository.craftCatalogue(craftListContext) },
        ) { next -> craftIds = next.toSet() }
        CheckboxMultiSelectField(
            label = "Artisans of selected crafts",
            options = artisansForCrafts.map { it.id to "${it.name} · ${it.place}" },
            selectedIds = artisanIds,
            emptyMessage = if (craftIds.isEmpty()) "Select one or more crafts first." else "No artisans for the selected crafts.",
            server = if (craftIds.isEmpty()) null else artisanSearch.asServerSearch(::artisanSelectOption),
            onToggle = { id -> artisanIds = if (artisanIds.contains(id)) artisanIds - id else artisanIds + id }
        )
        info?.let { Text(it, color = Coral, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                info = null
                if (toolId.isBlank() || artisanIds.isEmpty()) { onError("Pick a tool and at least one artisan."); return@Button }
                scope.launch {
                    saving = true
                    runCatching { repository.assignToolArtisans(toolId, artisanIds.toList()) }
                        .onSuccess { result -> assigned = result; artisanIds = emptySet(); info = "Linked to ${countNoun(result.size, "artisan")}." }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; onError(it.apiErrorMessage("Assignment failed")) }
                    saving = false
                }
            },
            enabled = !saving && toolId.isNotBlank() && artisanIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saving) "Assigning…" else "Assign tool to ${countNoun(artisanIds.size, "artisan")}") }

        if (toolId.isNotBlank()) {
            HorizontalDivider()
            Text("Currently assigned to", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (assigned.isEmpty()) {
                Text("No other artisans yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                assigned.forEach { artisan ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${artisan.name}${artisan.craft?.name?.let { " · $it" } ?: ""}",
                            color = Body,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { repository.unassignToolArtisan(toolId, artisan.id) }
                                    .onSuccess { assigned = assigned.filter { it.id != artisan.id } }
                                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; onError(it.apiErrorMessage("Could not remove")) }
                            }
                        }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (!value.isNullOrBlank()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
            Text(value, color = Body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MediaWithTranscript(context: Context, media: MediaFileDto, repository: FieldRepository? = null) {
    AndroidSavedMediaPreview(context = context, media = media)
    val uploader = media.uploadedBy?.name
    val uploadedWhen = formatIsoDate(media.createdAt)
    if (uploader != null || uploadedWhen != null) {
        Text(
            "Uploaded by ${uploader ?: "Unknown"}" + (uploadedWhen?.let { " · $it" } ?: ""),
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
    com.fieldrepository.app.questionnaire.ui.RecordingChips(media.questionnaireChips, onChip = QuestionnaireV2Nav::openRecordings)
    MediaRejectAction(mediaId = media.id, label = media.caption?.takeIf { it.isNotBlank() } ?: media.originalFilename)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val isAudio = media.mediaType.equals("AUDIO", ignoreCase = true)
    val processing = setOf("QUEUED", "PROCESSING", "PENDING", "RUNNING")
    val done = setOf("COMPLETED", "EMPTY", "DONE")
    var transcriptText by remember(media.id) { mutableStateOf(media.transcriptText) }
    var liveStatus by remember(media.id) { mutableStateOf(media.transcriptStatus) }
    var transcribing by remember(media.id) { mutableStateOf(false) }
    val status = liveStatus?.uppercase()
    val isAdmin = repository?.cachedUser()?.role in setOf("ADMIN", "MASTER_ADMIN")

    fun transcribeNow() {
        val repo = repository ?: return
        transcribing = true
        liveStatus = "PROCESSING"
        scope.launch {
            runCatching { repo.transcribeNow(media.id) }
                .onSuccess { updated ->
                    transcriptText = updated.transcriptText
                    liveStatus = updated.transcriptStatus ?: "COMPLETED"
                }
                .onFailure { liveStatus = media.transcriptStatus }
            transcribing = false
        }
    }

    val isVideo = media.mediaType.equals("VIDEO", ignoreCase = true)
    val isTimed = isAudio || isVideo
    val legacyTranscript: @Composable () -> Unit = {
    when {
        !transcriptText.isNullOrBlank() -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Transcript", color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clipboard.setText(AnnotatedString(transcriptText!!)) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy transcript", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", style = MaterialTheme.typography.labelMedium)
                    }
                }
                MarkdownText(markdown = transcriptText!!, color = Body)
            }
            if (repository != null) {
                TranscriptInsightsCard(
                    mediaId = media.id,
                    load = { id -> repository.transcriptInsightBatched(id) },
                    loadFull = { id -> repository.transcriptInsight(id) },
                    formatDate = ::formatIsoDate,
                    actions = repository.transcriptInsightActions
                )
            }
            if (repository != null) {
                TranscriptRefineControls(
                    context = context,
                    media = media,
                    repository = repository,
                    onApplied = { newText -> transcriptText = newText }
                )
            }
            if (isAdmin && isTimed && repository != null) {
                TranscribeNowButton(transcribing = transcribing, hasExisting = true) { transcribeNow() }
            }
        }
        transcribing || (isTimed && (status in processing || (status == null && media.transcribeRequested == true))) -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(if (transcribing) "Transcribing now…" else "Transcribing audio…", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        isTimed && status in done -> {
            Text("Transcript complete — no speech detected.", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (isAdmin && repository != null) {
                TranscribeNowButton(transcribing = transcribing, hasExisting = false) { transcribeNow() }
            }
        }
        isTimed && status == null -> {
            if (isAdmin && repository != null) {
                TranscribeNowButton(transcribing = transcribing, hasExisting = false) { transcribeNow() }
            }
        }
        isTimed -> {
            Text(
                "Transcript: ${liveStatus ?: "—"}" + (media.transcriptError?.let { " — $it" } ?: ""),
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            if (isAdmin && repository != null) {
                TranscribeNowButton(transcribing = transcribing, hasExisting = false) { transcribeNow() }
            }
        }
    }
    Unit
    }
    if (isAudio || isVideo) TranscriptEntry(mediaId = media.id, audio = null, legacy = legacyTranscript) else legacyTranscript()
}

@Composable
private fun TranscribeNowButton(transcribing: Boolean, hasExisting: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !transcribing) {
        Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (transcribing) "Transcribing…" else if (hasExisting) "Re-transcribe now" else "Transcribe now", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ArtisanQuestionnaireData(repository: FieldRepository, artisanId: String, focusSection: String? = null) {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val v2Config by v2.config.collectAsState()
    if (v2Config?.v2Enabled == true) {
        var artisanName by remember(artisanId) { mutableStateOf("") }
        LaunchedEffect(artisanId) { runCatching { repository.artisan(artisanId) }.onSuccess { artisanName = it.name } }
        FieldArtisanQuestionnaireRecord(artisanId, artisanName, focusSection = focusSection) {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
        }
        return
    }
    var data by remember(artisanId) { mutableStateOf<ArtisanQuestionnaireDto?>(null) }
    var loading by remember(artisanId) { mutableStateOf(true) }
    LaunchedEffect(artisanId) {
        runCatching { repository.artisanQuestionnaire(artisanId) }.onSuccess { data = it }
        loading = false
    }
    val loaded = data
    ArtisanQuestionnairePanel(answers = loaded?.answered ?: emptyList(), loading = loading, focusSection = focusSection)
    val interviews = loaded?.interviews.orEmpty().filter { it.media.isNotEmpty() || !it.notes.isNullOrBlank() }
    if (interviews.isNotEmpty()) {
        RecordCard(title = "Questionnaire recordings", icon = Icons.Filled.Quiz) {
            Text(
                "From every interview with this artisan.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            interviews.forEach { interview ->
                HorizontalDivider()
                Text(interview.title.ifBlank { "Interview" }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                val meta = listOfNotNull(
                    formatIsoDate(interview.interviewDate),
                    if (interview.coArtisans.isNotEmpty()) "with ${interview.coArtisans.joinToString(", ")}" else null
                ).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = Muted, style = MaterialTheme.typography.bodySmall)
                interview.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Body, style = MaterialTheme.typography.bodySmall) }
                if (interview.media.isEmpty()) Text("No recordings.", color = Muted, style = MaterialTheme.typography.bodySmall)
                interview.media.forEach { MediaWithTranscript(context, it, repository) }
            }
        }
    }
}

private const val APP_PREFS_NAME = "fieldrepo_prefs"
private const val PREF_AI_COST_ACK = "ai_refine_cost_ack"

private fun aiCostReminderSuppressed(context: Context): Boolean =
    context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_AI_COST_ACK, false)

private fun suppressAiCostReminder(context: Context) {
    context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_AI_COST_ACK, true).apply()
}

@Composable
private fun TranscriptRefineControls(
    context: Context,
    media: MediaFileDto,
    repository: FieldRepository,
    onApplied: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var refining by remember(media.id) { mutableStateOf(false) }
    var refined by remember(media.id) { mutableStateOf<String?>(null) }
    var refinedTranslated by remember(media.id) { mutableStateOf(false) }
    var refineError by remember(media.id) { mutableStateOf<String?>(null) }
    var applying by remember(media.id) { mutableStateOf(false) }
    var appliedNote by remember(media.id) { mutableStateOf<String?>(null) }
    var pendingTranslate by remember { mutableStateOf<Boolean?>(null) }
    var dontRemind by remember { mutableStateOf(false) }
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)

    fun runRefine(translate: Boolean) {
        scope.launch {
            refining = true
            refineError = null
            runCatching { repository.refineTranscript(media.id, translate) }
                .onSuccess { resp ->
                    when {
                        !resp.refined.isNullOrBlank() -> { refined = resp.refined; refinedTranslated = translate }
                        resp.available == false -> refineError = resp.message ?: "AI refinement is not configured."
                        else -> refineError = resp.message ?: "No transcript content to refine."
                    }
                }
                .onFailure {
                    if (it !is kotlinx.coroutines.CancellationException) refineError = it.apiErrorMessage("Couldn't refine the transcript.")
                }
            refining = false
        }
    }

    fun onRefineClick(translate: Boolean) {
        if (aiCostReminderSuppressed(context)) runRefine(translate) else pendingTranslate = translate
    }

    ActionRow {
        OutlinedButton(onClick = { onRefineClick(false) }, enabled = !refining, contentPadding = pad) {
            Text("Refine transcript", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(onClick = { onRefineClick(true) }, enabled = !refining, contentPadding = pad) {
            Text("Refine & translate", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (refining) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("Refining with AI…", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    refineError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    refined?.let { md ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.brandTile, RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                if (refinedTranslated) "Refined conversation (English)" else "Refined conversation",
                color = MaterialTheme.field.accentOnBrandTile,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            MarkdownText(markdown = md, color = MaterialTheme.field.onBrandTile)
            Text(
                "Save this refined version in place of the current transcript?",
                color = MaterialTheme.field.onBrandTileMuted,
                style = MaterialTheme.typography.bodySmall
            )
            ActionRow {
                Button(
                    onClick = {
                        scope.launch {
                            applying = true
                            refineError = null
                            runCatching { repository.applyTranscript(media.id, md) }
                                .onSuccess {
                                    onApplied(md)
                                    appliedNote = "Saved as the transcript ✓"
                                    refined = null
                                }
                                .onFailure {
                                    if (it !is kotlinx.coroutines.CancellationException)
                                        refineError = it.apiErrorMessage("Couldn't save the refined transcript.")
                                }
                            applying = false
                        }
                    },
                    enabled = !applying,
                    contentPadding = pad
                ) { Text(if (applying) "Saving…" else "Approve", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = { refined = null },
                    enabled = !applying,
                    contentPadding = pad
                ) { Text("Reject", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
    appliedNote?.let { Text(it, color = SuccessGreen, style = MaterialTheme.typography.bodySmall) }

    val mode = pendingTranslate
    if (mode != null) {
        AlertDialog(
            onDismissRequest = { pendingTranslate = null },
            title = { Text("This uses AI and costs extra") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Refining" + (if (mode) " and translating" else "") + " uses AI and costs a little each time. Continue?"
                    )
                    AppToggle(
                        label = "Do not remind me again",
                        checked = dontRemind,
                        onCheckedChange = { dontRemind = it }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontRemind) suppressAiCostReminder(context)
                    pendingTranslate = null
                    runRefine(mode)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { pendingTranslate = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MarkdownText(markdown: String, color: Color = Body) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        markdown.replace("\r\n", "\n").split("\n").forEach { raw ->
            val line = raw.trim()
            val isRule = line.length >= 3 && (line.all { it == '-' } || line.all { it == '*' } || line.all { it == '_' })
            when {
                line.isEmpty() -> Spacer(Modifier.height(2.dp))
                isRule -> HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Muted)
                line.startsWith("### ") -> Text(parseInlineMarkdown(line.removePrefix("### ")), display = true, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                line.startsWith("## ") -> Text(parseInlineMarkdown(line.removePrefix("## ")), display = true, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                line.startsWith("# ") -> Text(parseInlineMarkdown(line.removePrefix("# ")), display = true, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                line.startsWith("- ") || line.startsWith("* ") -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text("•  ", color = color, style = MaterialTheme.typography.bodyMedium)
                    Text(parseInlineMarkdown(line.drop(2)), color = color, style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(parseInlineMarkdown(line), color = color, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun parseInlineMarkdown(text: String) = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

@Composable
private fun RecordMediaSection(
    repository: FieldRepository,
    context: Context,
    linkedType: String,
    recordId: String,
    onError: (String) -> Unit
) {
    var media by remember(linkedType, recordId) { mutableStateOf<List<MediaFileDto>>(emptyList()) }
    var loading by remember(linkedType, recordId) { mutableStateOf(true) }
    LaunchedEffect(linkedType, recordId) {
        loading = true
        runCatching { repository.mediaForRecord(linkedType, recordId) }
            .onSuccess { media = it }
            .onFailure { onError(it.apiErrorMessage("Unable to load media")) }
        loading = false
    }
    HorizontalDivider()
    Text("Media & transcripts", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    when {
        loading -> Text("Loading media…", color = Muted, style = MaterialTheme.typography.bodySmall)
        media.isEmpty() -> Text("No media attached.", color = Muted, style = MaterialTheme.typography.bodySmall)
        else -> media.forEach { MediaWithTranscript(context, it, repository) }
    }
}

private val StarGold = Color(0xFFF5B301)

@Composable
private fun StarRatingInput(rating: Int, max: Int = 5, label: String = "Rating", onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.selectableGroup()) {
        (1..max).forEach { i ->
            IconButton(
                onClick = { onChange(if (rating == i) 0 else i) },
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        selected = rating == i
                        stateDescription = if (rating == i) "Selected" else "Not selected"
                    }
            ) {
                Icon(
                    imageVector = if (i <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "$label: $i of $max",
                    tint = if (i <= rating) StarGold else Muted,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun StarRatingDisplay(rating: Int, max: Int = 5) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..max).forEach { i ->
            Icon(
                imageVector = if (i <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                tint = if (i <= rating) StarGold else Muted,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("$rating / $max", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LabeledStarRating(label: String, rating: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        StarRatingInput(rating = rating, label = label, onChange = onChange)
    }
}

@Composable
private fun LabeledRatingDisplay(label: String, rating: Int?) {
    if (rating == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        StarRatingDisplay(rating)
    }
}

@Composable
private fun QualitativeRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(value, color = Body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FeedbackScreen(repository: FieldRepository, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var rating by remember { mutableStateOf(0) }
    var easeOfUse by remember { mutableStateOf(0) }
    var reliability by remember { mutableStateOf(0) }
    var performance by remember { mutableStateOf(0) }
    var design by remember { mutableStateOf(0) }
    var features by remember { mutableStateOf(0) }
    var recommend by remember { mutableStateOf(0) }
    var role by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var likeMost by remember { mutableStateOf("") }
    var improve by remember { mutableStateOf("") }
    var bugs by remember { mutableStateOf("") }
    var featureRequests by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<FeedbackDto?>(null) }
    var status by remember { mutableStateOf(ActionStatus.IDLE) }
    AutoResetStatus(status) { status = ActionStatus.IDLE }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { repository.myFeedback() }
            .onSuccess { fb ->
                if (fb.id.isNotBlank()) {
                    existing = fb
                    rating = fb.rating ?: 0
                    easeOfUse = fb.easeOfUse ?: 0
                    reliability = fb.reliability ?: 0
                    performance = fb.performance ?: 0
                    design = fb.design ?: 0
                    features = fb.features ?: 0
                    recommend = fb.recommend ?: 0
                    role = fb.role.orEmpty()
                    comment = fb.comment.orEmpty()
                    likeMost = fb.likeMost.orEmpty()
                    improve = fb.improve.orEmpty()
                    bugs = fb.bugs.orEmpty()
                    featureRequests = fb.featureRequests.orEmpty()
                }
            }
            .onFailure { onError(it.apiErrorMessage("Unable to load your feedback")) }
        loading = false
    }

    RecordCard(title = "App feedback") {
        Text(
            "How is the app working for you? Everything is optional.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (loading) {
            Text("Loading your feedback…", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Ratings", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text("Overall rating", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            StarRatingInput(rating = rating) { rating = it }
            Text(
                when (rating) {
                    0 -> "Tap a star to rate"
                    1 -> "Poor"
                    2 -> "Fair"
                    3 -> "Good"
                    4 -> "Very good"
                    else -> "Excellent"
                },
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            LabeledStarRating("Ease of use", easeOfUse) { easeOfUse = it }
            LabeledStarRating("Reliability / stability", reliability) { reliability = it }
            LabeledStarRating("Speed / performance", performance) { performance = it }
            LabeledStarRating("Design / look & feel", design) { design = it }
            LabeledStarRating("Features / completeness", features) { features = it }
            LabeledStarRating("How likely you'd recommend it", recommend) { recommend = it }

            HorizontalDivider()
            Text("In your words", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            TextInput("Your role (e.g. researcher, field documenter)", role) { role = it }
            TextInput("What do you like most?", likeMost, minLines = 2) { likeMost = it }
            TextInput("What should we improve?", improve, minLines = 2) { improve = it }
            TextInput("Any bugs or issues you hit?", bugs, minLines = 2) { bugs = it }
            TextInput("Features you'd like to see", featureRequests, minLines = 2) { featureRequests = it }
            TextInput("Anything else (general comments)", comment, minLines = 3) { comment = it }

            val anyProvided = rating > 0 || easeOfUse > 0 || reliability > 0 || performance > 0 ||
                design > 0 || features > 0 || recommend > 0 ||
                listOf(role, comment, likeMost, improve, bugs, featureRequests).any { it.isNotBlank() }
            Button(
                onClick = {
                    if (!anyProvided) {
                        onError("Add at least one rating or a written answer first.")
                        return@Button
                    }
                    scope.launch {
                        saving = true
                        val request = FeedbackUpsertRequest(
                                    rating = rating.takeIf { it > 0 },
                                    easeOfUse = easeOfUse.takeIf { it > 0 },
                                    reliability = reliability.takeIf { it > 0 },
                                    performance = performance.takeIf { it > 0 },
                                    design = design.takeIf { it > 0 },
                                    features = features.takeIf { it > 0 },
                                    recommend = recommend.takeIf { it > 0 },
                                    role = role.blankToNull(),
                                    comment = comment.blankToNull(),
                                    likeMost = likeMost.blankToNull(),
                                    improve = improve.blankToNull(),
                                    bugs = bugs.blankToNull(),
                                    featureRequests = featureRequests.blankToNull()
                                )
                        val payload = offlineFormJson.encodeToString(request)
                        if (queueSmallWrite(repository, context, null, idempotent = true, type = "feedback", payloadJson = payload, label = "App feedback")) {
                            status = ActionStatus.SUCCESS
                            onError("Saved offline. Sends when you're online.")
                            saving = false
                            return@launch
                        }
                        runCatching { repository.upsertMyFeedback(request) }
                            .onSuccess { existing = it; status = ActionStatus.SUCCESS }
                            .onFailure {
                                if (it !is kotlinx.coroutines.CancellationException) {
                                    if (queueSmallWrite(repository, context, it, idempotent = true, type = "feedback", payloadJson = payload, label = "App feedback")) {
                                        status = ActionStatus.SUCCESS
                                        onError("Saved offline. Sends when you're online.")
                                    } else {
                                        status = ActionStatus.ERROR
                                        onError(it.apiErrorMessage("Unable to save your feedback"))
                                    }
                                }
                            }
                        saving = false
                    }
                },
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (status) {
                        ActionStatus.SUCCESS -> SuccessGreen
                        ActionStatus.ERROR -> FailureRed
                        ActionStatus.IDLE -> MaterialTheme.colorScheme.primary
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        saving -> "Saving…"
                        status == ActionStatus.SUCCESS -> "Saved ✓"
                        existing != null -> "Update feedback"
                        else -> "Submit feedback"
                    }
                )
            }
            existing?.updatedAt?.let { formatIsoDate(it)?.let { d -> Text("Last updated $d", color = Muted, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable
private fun MasterFeedbackCard(repository: FieldRepository, onError: (String) -> Unit) {
    var feedback by remember { mutableStateOf<List<FeedbackDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedUserId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { repository.allFeedback() }
            .onSuccess { feedback = it }
            .onFailure { onError(it.apiErrorMessage("Unable to load feedback")) }
        loading = false
    }

    RecordCard(title = "User feedback") {
        Text("Pick a user to read their feedback.", color = Muted, style = MaterialTheme.typography.bodySmall)
        when {
            loading -> Text("Loading feedback…", color = Muted, style = MaterialTheme.typography.bodySmall)
            feedback.isEmpty() -> Text("No feedback submitted yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
            else -> {
                val avg = feedback.mapNotNull { it.rating }.takeIf { it.isNotEmpty() }?.average()
                Text(
                    "${countNoun(feedback.size, "user")} gave feedback" + (avg?.let { " · average rating ${"%.1f".format(it)} / 5" } ?: ""),
                    color = Body,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                val options = feedback
                    .sortedBy { (it.user?.name ?: it.userId).lowercase() }
                    .map { fb ->
                        val name = fb.user?.name ?: "Unknown user"
                        fb.userId to (name + (fb.rating?.let { " · $it★" } ?: ""))
                    }
                DropdownField(
                    label = "Feedback by user",
                    options = options,
                    selectedValue = selectedUserId,
                    placeholder = "Select a user",
                    includeNone = false
                ) { selectedUserId = it }
                feedback.firstOrNull { it.userId == selectedUserId }?.let { fb ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceCard, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(fb.user?.name ?: "Unknown user", display = true, color = Body, fontWeight = FontWeight.SemiBold)
                        fb.user?.email?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                        if (!fb.role.isNullOrBlank()) Text("Role: ${fb.role}", color = Muted, style = MaterialTheme.typography.bodySmall)

                        if (fb.rating != null) {
                            Text("Overall", color = Muted, style = MaterialTheme.typography.bodySmall)
                            StarRatingDisplay(fb.rating!!)
                        }
                        LabeledRatingDisplay("Ease of use", fb.easeOfUse)
                        LabeledRatingDisplay("Reliability", fb.reliability)
                        LabeledRatingDisplay("Performance", fb.performance)
                        LabeledRatingDisplay("Design", fb.design)
                        LabeledRatingDisplay("Features", fb.features)
                        LabeledRatingDisplay("Would recommend", fb.recommend)

                        val hasText = listOf(fb.likeMost, fb.improve, fb.bugs, fb.featureRequests, fb.comment).any { !it.isNullOrBlank() }
                        if (hasText) HorizontalDivider()
                        QualitativeRow("Likes most", fb.likeMost)
                        QualitativeRow("To improve", fb.improve)
                        QualitativeRow("Bugs / issues", fb.bugs)
                        QualitativeRow("Feature requests", fb.featureRequests)
                        QualitativeRow("General comments", fb.comment)

                        val nothing = fb.rating == null && fb.easeOfUse == null && fb.reliability == null &&
                            fb.performance == null && fb.design == null && fb.features == null &&
                            fb.recommend == null && !hasText
                        if (nothing) Text("No details provided.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        formatIsoDate(fb.updatedAt)?.let { Text("Updated $it", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrphanRecordingsCard(repository: FieldRepository, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var orphans by remember { mutableStateOf<List<MediaFileDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var targetOptions by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    val selections = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<MediaFileDto?>(null) }

    suspend fun loadOptionsFor(types: Set<String>) {
        val map = targetOptions.toMutableMap()
        types.forEach { t ->
            if (map.containsKey(t)) return@forEach
            runCatching {
                when (t.lowercase()) {
                    "questionnaire", "questionnaireinterview" -> repository.interviewsPage(EVERY_WORKSHOP, EVERY_PAGE_BUDGET).items.map { it.id to it.title.ifBlank { "Untitled interview" } }
                    "product" -> repository.products().map { it.id to "${it.productName} · ${it.artisanName}" }
                    "tool" -> repository.tools().map { it.id to it.toolRowLabel() }
                    "artisan" -> repository.artisans().map { it.id to it.name }
                    "craft" -> (CraftCatalogueCache.catalogue.value ?: repository.craftCatalogue(context))?.crafts.orEmpty().map { it.id to it.name }
                    "workshop" -> repository.workshops().map { it.id to workshopDisplayTitle(it.title, date = it.occurrenceDate(), place = it.place) }
                    else -> emptyList()
                }
            }.onSuccess { map[t] = it }
        }
        targetOptions = map
    }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { repository.orphanedMedia() }
            .onSuccess {
                orphans = it
                loadOptionsFor(it.mapNotNull { m -> m.linkedRecordType }.toSet())
            }
            .onFailure { onError(it.apiErrorMessage("Unable to load recovered recordings")) }
        loading = false
    }

    RecordCard(title = "Recovered recordings") {
        Text(
            "Recordings whose record was deleted. Play or re-attach them.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        when {
            loading -> Text("Loading…", color = Muted, style = MaterialTheme.typography.bodySmall)
            orphans.isEmpty() -> Text("None.", color = Muted, style = MaterialTheme.typography.bodySmall)
            else -> {
                Text(countNoun(orphans.size, "recovered file"), color = Body, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                orphans.forEach { m ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceCard, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val typeLabel = m.linkedRecordType?.replaceFirstChar { it.uppercase() } ?: "Record"
                        Text("From a deleted $typeLabel", color = Muted, style = MaterialTheme.typography.bodySmall)
                        m.uploadedBy?.name?.let { Text("Recorded by $it", color = Muted, style = MaterialTheme.typography.bodySmall) }
                        MediaWithTranscript(context, m, repository)
                        val opts = targetOptions[m.linkedRecordType].orEmpty()
                        if (opts.isEmpty()) {
                            Text("No existing ${typeLabel.lowercase()} to re-link to.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        } else {
                            DropdownField(
                                label = "Re-link to a ${typeLabel.lowercase()}",
                                options = opts,
                                selectedValue = selections[m.id].orEmpty(),
                                placeholder = "Select a record",
                                includeNone = false
                            ) { selections[m.id] = it }
                            Button(
                                onClick = {
                                    val target = selections[m.id]
                                    if (target.isNullOrBlank()) { onError("Pick a record to re-link to first."); return@Button }
                                    scope.launch {
                                        busy = busy + m.id
                                        runCatching { repository.relinkMedia(m.id, m.linkedRecordType ?: "", target) }
                                            .onSuccess { orphans = orphans.filterNot { it.id == m.id }; selections.remove(m.id) }
                                            .onFailure { onError(it.apiErrorMessage("Unable to re-link this recording")) }
                                        busy = busy - m.id
                                    }
                                },
                                enabled = m.id !in busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (m.id in busy) "Re-linking…" else "Re-link this recording") }
                        }
                        OutlinedButton(
                            onClick = { pendingDelete = m },
                            enabled = m.id !in busy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (m.id in busy) "Working…" else "Permanently delete")
                        }
                    }
                }
            }
        }
    }
    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Permanently delete recording?") },
            text = {
                Text(
                    "Permanently delete “${toDelete.originalFilename}”?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            busy = busy + toDelete.id
                            runCatching { repository.deleteMedia(toDelete.id) }
                                .onSuccess { orphans = orphans.filterNot { it.id == toDelete.id }; selections.remove(toDelete.id) }
                                .onFailure { onError(it.apiErrorMessage("Unable to delete this recording")) }
                            busy = busy - toDelete.id
                        }
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

private const val COMPLETION_OPTION_ID = "__completion__"

private enum class AdminHubEntry(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val masterOnly: Boolean = false,
    val reviewGated: Boolean = false
) {
    REVIEWS("Reviews & approvals", "Approve or reject records the team submitted.", Icons.Filled.ManageAccounts, reviewGated = true),
    RECOVERED("Recovered recordings", "Play & re-link recordings whose record was deleted.", Icons.Filled.RecordVoiceOver),
    FEEDBACK("User feedback", "Everyone's app feedback, grouped by user.", Icons.Filled.RateReview, masterOnly = true),
    TOOLS("Assign tools to artisans", "Link tools to the artisans who use them.", Icons.Filled.Build),
    TASKS(
        "Task assignment",
        "Assign and track work.",
        Icons.AutoMirrored.Filled.Assignment
    ),
    WORKSHOPS("Workshop assignments", "Choose who may submit entries for each workshop.", Icons.Filled.Groups),
    ACCESS_REQUESTS("Workshop access requests", "Approve or deny requests to work in a workshop.", Icons.Filled.LockOpen),
    ACCESS_ROSTER(
        "Access roster",
        "Who may sign in, and pending requests.",
        Icons.Filled.HowToReg
    ),
    API_KEYS(
        "API keys",
        "Manage service keys.",
        Icons.Filled.VpnKey,
        masterOnly = true
    ),
    SETTINGS("Settings", "Transcription output and off-peak processing.", Icons.Filled.Tune, masterOnly = true)
}

@Composable
private fun AdminHubScreen(
    repository: FieldRepository,
    user: UserDto,
    isMasterAdmin: Boolean,
    canReview: Boolean,
    onOpenRecord: ((EntryMode, String) -> Unit)? = null,
    section: AdminHubEntry? = null,
    onSectionChange: (AdminHubEntry?) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    pendingAccessRequests: Int? = null,
    onPendingCountChanged: (Int) -> Unit = {}
) {
    var directory by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { repository.userDirectory() }.onSuccess { directory = it } }

    val entries = AdminHubEntry.entries.filter { (!it.masterOnly || isMasterAdmin) && (!it.reviewGated || canReview) }
    val current = section

    if (current == null) {
        RecordCard(title = "Admin tools", icon = Icons.Filled.Tune) {
            Text("Administrative tools and settings.", color = Muted, style = MaterialTheme.typography.bodySmall)
            entries.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSectionChange(entry) }
                        .background(SurfaceCard, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(color = MaterialTheme.field.brandTile, shape = RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(entry.icon, contentDescription = null, tint = MaterialTheme.field.onBrandTile, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.label, display = true, color = Body, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Text(entry.description, color = Muted, style = MaterialTheme.typography.bodySmall)
                        val waiting = pendingAccessRequests?.takeIf { it > 0 && entry == AdminHubEntry.ACCESS_ROSTER }
                        if (waiting != null) {
                            Text(
                                "$waiting waiting for a decision",
                                color = MaterialTheme.field.warning,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text("›", color = Muted, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    } else {
        when (current) {
            AdminHubEntry.REVIEWS -> ReviewApprovalCard(repository = repository, onOpenRecord = onOpenRecord, onError = onError)
            AdminHubEntry.RECOVERED -> OrphanRecordingsCard(repository = repository, onError = onError)
            AdminHubEntry.FEEDBACK -> MasterFeedbackCard(repository = repository, onError = onError)
            AdminHubEntry.TOOLS -> ToolAssignScreen(repository = repository, onError = onError)
            AdminHubEntry.TASKS -> TaskAdminScreen(
                repository = repository,
                onBack = null,
                onMessage = onMessage,
                onError = onError
            )
            AdminHubEntry.WORKSHOPS -> WorkshopAssignmentCard(repository = repository, directory = directory, onMessage = onMessage, onError = onError)
            AdminHubEntry.ACCESS_REQUESTS -> WorkshopAccessQueueCard(repository = repository, onMessage = onMessage, onError = onError)
            AdminHubEntry.ACCESS_ROSTER -> AccessRosterScreen(
                repository = repository,
                user = user,
                onBack = null,
                onMessage = onMessage,
                onError = onError,
                onPendingCountChanged = onPendingCountChanged
            )
            AdminHubEntry.API_KEYS -> ApiKeysScreen(
                repository = repository,
                onBack = null,
                onMessage = onMessage,
                onError = onError
            )
            AdminHubEntry.SETTINGS -> SettingsScreen(repository = repository, onMessage = onMessage, onError = onError)
        }
    }
}

@Composable
private fun ViewDataScreen(
    repository: FieldRepository,
    canReview: Boolean = false,
    masterAdmin: Boolean = false,
    isAdmin: Boolean = false,
    adminChrome: Boolean = true,
    showProvenance: Boolean = false,
    canDownloadDataset: Boolean = false,
    onOpenDataBrowser: (() -> Unit)? = null,
    onOpenRecord: ((EntryMode, String) -> Unit)? = null,
    onError: (String) -> Unit
) {
    var mode by remember { mutableStateOf(EntryMode.ARTISAN) }
    var selectedId by remember { mutableStateOf("") }
    var loadingList by remember { mutableStateOf(false) }

    val workshopScope = rememberWorkshopScope(repository = repository, onError = onError)
    var interviewsDetailed by remember { mutableStateOf<List<QuestionnaireInterviewDetailDto>>(emptyList()) }
    var selectedArtisanIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var questionnaireSections by remember { mutableStateOf<List<QuestionnaireSectionDto>>(emptyList()) }

    val scopeSettledForLists = mode != EntryMode.QUESTIONNAIRE || workshopScope.settled
    val entrySearch = rememberRecordRows(
        repository, mode, key = mode, enabled = mode != EntryMode.QUESTIONNAIRE
    )
    val artisanFilterSearch = rememberServerSearch(
        key = workshopScope.requestKey,
        cached = emptyList<ArtisanDto>(),
        seed = null,
        idOf = { it.id },
        textOf = ::artisanSearchText,
        enabled = mode == EntryMode.QUESTIONNAIRE && scopeSettledForLists
    ) { term, page -> repository.artisansSearchPage(term, page, workshopScope.workshopIds) }
    val artisanFilterList = artisanFilterSearch.known
    val questionnaireOptions = remember(interviewsDetailed, selectedArtisanIds, artisanFilterList, questionnaireSections) {
        if (selectedArtisanIds.isEmpty()) emptyList()
        else {
            val nameById = artisanFilterList.associate { it.id to it.name }
            val codeByQuestionId = questionnaireSections.flatMap { sec ->
                sec.questions.map { q -> q.id to "${sec.code}${q.sortOrder}" }
            }.toMap()
            interviewsDetailed
                .filter { iv -> iv.artisans.any { it.artisanId in selectedArtisanIds } }
                .groupBy { interviewGroupKey(it) }
                .map { (_, group) ->
                    val representative = representativeInterview(group)
                    val artisanNames = representative.artisans
                        .mapNotNull { link -> link.artisan?.name ?: nameById[link.artisanId] }
                        .distinct()
                        .joinToString(", ")
                        .ifBlank { "Unknown artisan" }
                    val answeredCodes = group
                        .flatMap { iv -> iv.responses }
                        .mapNotNull { codeByQuestionId[it.questionId] }
                        .distinct()
                        .sorted()
                    val qSpan = when {
                        answeredCodes.isEmpty() -> null
                        answeredCodes.size <= 4 -> answeredCodes.joinToString(" ")
                        else -> "${answeredCodes.first()}–${answeredCodes.last()} (${answeredCodes.size})"
                    }
                    val researchers = group.mapNotNull { it.createdBy?.name }.distinct().joinToString(", ")
                    val rest = listOfNotNull(
                        qSpan,
                        representative.title.takeIf { it.isNotBlank() },
                        representative.place?.takeIf { it.isNotBlank() },
                        researchers.ifBlank { null }?.let { "by $it" },
                        if (group.size > 1) "${group.size} sessions" else null
                    ).joinToString(" · ")
                    representative.id to (if (rest.isBlank()) artisanNames else "$artisanNames · $rest")
                }
                .sortedBy { it.second.lowercase() }
        }
    }

    val scopeSettled = mode != EntryMode.QUESTIONNAIRE || workshopScope.settled
    val scopeKey = if (mode == EntryMode.QUESTIONNAIRE) workshopScope.requestKey else ""
    LaunchedEffect(mode, scopeSettled, scopeKey) {
        if (!scopeSettled) return@LaunchedEffect
        selectedId = ""
        selectedArtisanIds = emptySet()
        interviewsDetailed = emptyList()
        if (mode == EntryMode.QUESTIONNAIRE) {
            loadingList = true
            runCatching {
                runCatching { repository.questionnaireSections() }.getOrDefault(emptyList())
            }.onSuccess { questionnaireSections = it }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                onError(it.apiErrorMessage("Unable to load questionnaires"))
            }
        }
        loadingList = false
    }
    LaunchedEffect(mode, selectedArtisanIds, scopeKey) {
        if (mode != EntryMode.QUESTIONNAIRE || selectedArtisanIds.isEmpty()) {
            interviewsDetailed = emptyList()
            return@LaunchedEffect
        }
        runCatching {
            selectedArtisanIds.flatMap { id -> repository.interviewsForArtisanInScope(id, workshopScope.workshopIds) }
                .distinctBy { it.id }
        }.onSuccess { interviewsDetailed = it }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            onError(it.apiErrorMessage("Unable to load questionnaires"))
        }
    }
    LaunchedEffect(questionnaireOptions, mode) {
        if (mode == EntryMode.QUESTIONNAIRE && selectedId.isNotBlank() && selectedId != COMPLETION_OPTION_ID && questionnaireOptions.none { it.first == selectedId }) {
            selectedId = ""
        }
    }

    RecordCard(title = "View data") {
        Text("Pick a record type, then an entry.", color = Muted, style = MaterialTheme.typography.bodySmall)
        DropdownField(
            label = "Record type",
            options = viewDataModes.map { it.name to it.label },
            selectedValue = mode.name,
            includeNone = false
        ) { picked -> viewDataModes.firstOrNull { it.name == picked }?.let { mode = it } }
        when {
            loadingList -> Text("Loading ${mode.label.lowercase()}…", color = Muted)
            mode == EntryMode.QUESTIONNAIRE -> {
                WorkshopScopeSelect(scope = workshopScope, label = "Workshops in this list")
                val artisanFilterOptions = remember(artisanFilterList) { artisanSelectOptions(artisanFilterList) }
                SearchableMultiSelectField(
                    label = "Involved artisans",
                    options = artisanFilterOptions,
                    selected = selectedArtisanIds,
                    placeholder = "Narrow the list to one or more artisans",
                    emptyMessage = "No artisans in the workshops above yet",
                    server = artisanFilterSearch.asServerSearchRows(countsRows = true) { artisanSelectOptions(it) }
                ) { next -> selectedArtisanIds = next }
                val hasArtisan = selectedArtisanIds.isNotEmpty()
                val secondaryOptions = listOf(COMPLETION_OPTION_ID to "▦ Check completion (all artisans)") +
                    (if (hasArtisan) questionnaireOptions else emptyList())
                DropdownField(
                    label = "Select questionnaire",
                    options = secondaryOptions,
                    selectedValue = selectedId,
                    placeholder = "Check completion, or pick an artisan then a questionnaire",
                    includeNone = false,
                    enabled = true
                ) { selectedId = it }
                if (hasArtisan && questionnaireOptions.isEmpty()) {
                    val why =
                        if (workshopScope.isAllRecords) "No questionnaires involve the selected ${if (selectedArtisanIds.size == 1) "artisan" else "artisans"} yet"
                        else "No questionnaires for these artisans in these workshops"
                    Text("$why.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            !entrySearch.browseLoaded && entrySearch.note == null ->
                Text("Loading ${mode.label.lowercase()}…", color = Muted)
            entrySearch.browseLoaded && entrySearch.browseTotal == 0 ->
                Text("No ${mode.label.lowercase()} records yet.", color = Muted)
            else -> DropdownField(
                label = "Select ${mode.label.lowercase()}",
                options = recordRowOptions(mode, entrySearch.known).map { it.value to it.label },
                selectedValue = selectedId,
                placeholder = "Select a record",
                includeNone = false,
                server = entrySearch.asServerSearchRows(countsRows = true) { recordRowOptions(mode, it) }
            ) { selectedId = it }
        }
    }
    val canOverrideCompletion = isAdmin && adminChrome
    if (selectedId == COMPLETION_OPTION_ID && mode == EntryMode.QUESTIONNAIRE) {
        CompletionMatrixCard(repository = repository, artisanId = null, canEdit = canOverrideCompletion, onError = onError)
    } else if (selectedId.isNotBlank()) {
        ViewDataDetail(
            repository = repository,
            mode = mode,
            recordId = selectedId,
            canOverrideCompletion = canOverrideCompletion,
            showProvenance = showProvenance,
            onError = onError
        )
    }
    if (canDownloadDataset) {
        onOpenDataBrowser?.let { DataBrowserEntryCard(onOpen = it) }
        DatasetDownloadCard(repository = repository, onError = onError)
    }
    if (canReview && !(isAdmin && adminChrome)) {
        ReviewApprovalCard(repository = repository, onOpenRecord = onOpenRecord, onError = onError)
    }
}

private data class ReviewField(val key: String, val label: String, val multiline: Boolean = false)

private fun reviewEditableFields(recordType: String): List<ReviewField> = when (recordType.lowercase()) {
    "artisan" -> listOf(
        ReviewField("name", "Name"),
        ReviewField("localName", "Local name"),
        ReviewField("place", "Place"),
        ReviewField("address", "Address", multiline = true),
        ReviewField("notes", "Notes", multiline = true),
        ReviewField("dos", "Do's", multiline = true),
        ReviewField("donts", "Don'ts", multiline = true)
    )
    "workshop" -> listOf(
        ReviewField("title", "Workshop title"),
        ReviewField("place", "Place"),
        ReviewField("description", "Description", multiline = true),
        ReviewField("notes", "Notes", multiline = true)
    )
    "product" -> listOf(
        ReviewField("productName", "Product name"),
        ReviewField("localName", "Local name"),
        ReviewField("artisanName", "Artisan name"),
        ReviewField("place", "Place"),
        ReviewField("rawMaterialsUsed", "Raw materials used", multiline = true),
        ReviewField("mainToolsUsed", "Main tools used", multiline = true),
        ReviewField("productFunctionUse", "Function / use", multiline = true),
        ReviewField("remarks", "Remarks", multiline = true)
    )
    "tool" -> listOf(
        ReviewField("toolkitName", "Toolkit name"),
        ReviewField("localName", "Local name"),
        ReviewField("englishName", "English name"),
        ReviewField("artisanName", "Artisan name"),
        ReviewField("place", "Place"),
        ReviewField("processUsedIn", "Process used in", multiline = true),
        ReviewField("material", "Material"),
        ReviewField("suggestionsForToolImprovement", "Suggestions for improvement", multiline = true),
        ReviewField("remarks", "Remarks", multiline = true)
    )
    "process" -> listOf(
        ReviewField("name", "Name of the process"),
        ReviewField("notes", "Notes", multiline = true)
    )
    "questionnaire" -> listOf(
        ReviewField("title", "Interview title"),
        ReviewField("place", "Place"),
        ReviewField("language", "Language"),
        ReviewField("notes", "Notes", multiline = true)
    )
    "media" -> listOf(
        ReviewField("caption", "Caption", multiline = true),
        ReviewField("transcriptText", "Transcript", multiline = true)
    )
    else -> emptyList()
}

private suspend fun loadReviewRecordValues(
    repository: FieldRepository,
    recordType: String,
    recordId: String
): Map<String, String> = when (recordType.lowercase()) {
    "artisan" -> repository.artisan(recordId).let {
        mapOf(
            "name" to it.name, "localName" to it.localName.orEmpty(), "place" to it.place,
            "address" to it.address.orEmpty(), "notes" to it.notes.orEmpty(),
            "dos" to it.dos.orEmpty(), "donts" to it.donts.orEmpty()
        )
    }
    "workshop" -> repository.workshop(recordId).let {
        mapOf(
            "title" to it.title, "place" to it.place,
            "description" to it.description.orEmpty(), "notes" to it.notes.orEmpty()
        )
    }
    "product" -> repository.product(recordId).let {
        mapOf(
            "productName" to it.productName, "localName" to it.localName.orEmpty(),
            "craftName" to it.craftName, "artisanName" to it.artisanName, "place" to it.place,
            "rawMaterialsUsed" to it.rawMaterialsUsed.orEmpty(),
            "mainToolsUsed" to it.mainToolsUsed.orEmpty(),
            "productFunctionUse" to it.productFunctionUse.orEmpty(),
            "remarks" to it.remarks.orEmpty()
        )
    }
    "tool" -> repository.tool(recordId).let {
        mapOf(
            "toolkitName" to it.toolkitName, "localName" to it.localName.orEmpty(),
            "englishName" to it.englishName.orEmpty(), "craftName" to it.craftName,
            "artisanName" to it.artisanName, "place" to it.place,
            "processUsedIn" to it.processUsedIn.orEmpty(), "material" to it.material.orEmpty(),
            "suggestionsForToolImprovement" to it.suggestionsForToolImprovement.orEmpty(),
            "remarks" to it.remarks.orEmpty()
        )
    }
    "process" -> repository.process(recordId).let {
        mapOf("name" to it.name, "notes" to it.notes.orEmpty())
    }
    "questionnaire" -> repository.interview(recordId).let {
        mapOf(
            "title" to it.title, "place" to it.place.orEmpty(),
            "language" to it.language.orEmpty(), "notes" to it.notes.orEmpty()
        )
    }
    "media" -> repository.mediaItem(recordId).let {
        mapOf("caption" to it.caption.orEmpty(), "transcriptText" to it.transcriptText.orEmpty())
    }
    else -> emptyMap()
}

@Composable
private fun ReviewApprovalCard(
    repository: FieldRepository,
    onOpenRecord: ((EntryMode, String) -> Unit)? = null,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<PendingReviewDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var total by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val pages = maxOf(1, (total + REVIEW_PAGE_SIZE - 1) / REVIEW_PAGE_SIZE)
    val selected = remember { mutableStateMapOf<String, PendingReviewDto>() }
    var bulkBusy by remember { mutableStateOf(false) }
    var confirmBulk by remember { mutableStateOf(false) }
    var workshopId by remember { mutableStateOf("") }
    val workshopSearch = rememberServerSearch(
        key = "review-workshops",
        cached = emptyList<WorkshopDetailDto>(),
        seed = null,
        idOf = { it.id },
        textOf = { it.title + " " + it.place }
    ) { term, at -> repository.workshopsSearchPage(term, at) }
    var reasons by remember { mutableStateOf<List<ReviewReasonDto>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { repository.reviewReasons() }.onSuccess { reasons = it } }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { repository.pendingReviewsPage(page, workshopId = workshopId) }
                .onSuccess { answer ->
                    pending = answer.items
                    total = answer.total
                    loadError = null
                    val last = maxOf(1, (answer.total + REVIEW_PAGE_SIZE - 1) / REVIEW_PAGE_SIZE)
                    if (answer.items.isEmpty() && page > last) {
                        page = last
                        refresh()
                    }
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    loadError = it.apiErrorMessage("Couldn't load the review queue.")
                }
            loading = false
        }
    }
    LaunchedEffect(page, workshopId) { refresh() }

    fun approveTicked() {
        scope.launch {
            bulkBusy = true
            val rows = selected.values.toList()
            runCatching { repository.bulkApprove(rows) }
                .onSuccess { answer ->
                    val done = answer.results.filter { it.outcome == "APPROVED" }
                        .map { "${it.recordType}:${it.id}" }.toSet()
                    done.forEach { selected.remove(it) }
                    pending = pending.filterNot { "${it.recordType}:${it.id}" in done }
                    total = (total - done.size).coerceAtLeast(0)
                    info = ReviewFeedbackCopy.bulkReceipt(answer.approved, answer.failed, answer.mediaApproved)
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    onError(it.apiErrorMessage("Couldn't approve these records."))
                }
            bulkBusy = false
        }
    }

    if (confirmBulk) {
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = { Text("Approve ${selected.size} record${if (selected.size == 1) "" else "s"}?") },
            text = { Text(ReviewFeedbackCopy.alsoApproves(selected.values.sumOf { it.pendingMediaCount }).orEmpty()) },
            confirmButton = { TextButton(onClick = { confirmBulk = false; approveTicked() }) { Text(ReviewFeedbackCopy.approveLabel(selected.size)) } },
            dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("Cancel") } }
        )
    }

    RecordCard(title = "Reviews & approvals") {
        Text(
            "Pending submissions. Tap one to review.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        SearchableSelectField(
            label = "Workshop",
            options = workshopSearch.known.map { SelectOption(it.id, it.title, it.place.ifBlank { null }) },
            selectedValue = workshopId,
            placeholder = "All workshops",
            includeNone = false,
            clearable = true,
            server = workshopSearch.asServerSearch { SelectOption(it.id, it.title, it.place.ifBlank { null }) }
        ) { picked ->
            if (picked != workshopId) {
                workshopId = picked
                page = 1
                selected.clear()
            }
        }
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Loading pending records…", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            loadError != null -> LoadFailedCard(message = loadError.orEmpty(), onRetry = { refresh() })
            pending.isEmpty() && total == 0 -> Text("Nothing pending.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text(
                    "$total record${if (total == 1) "" else "s"} awaiting review" +
                        if (pages > 1) " · page $page of $pages" else "",
                    color = Body,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                info?.let { Text(it, color = MaterialTheme.field.success, style = MaterialTheme.typography.bodyMedium) }
                if (selected.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${selected.size} selected", color = Body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { selected.clear() },
                            enabled = !bulkBusy,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Clear") }
                        Button(
                            onClick = {
                                if (selected.values.sumOf { it.pendingMediaCount } > 0) confirmBulk = true else approveTicked()
                            },
                            enabled = !bulkBusy,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(if (bulkBusy) "Approving…" else ReviewFeedbackCopy.approveLabel(selected.size)) }
                    }
                }
                if (pending.isEmpty()) {
                    LaunchedEffect(Unit) { refresh() }
                }
                pending.forEach { item ->
                    key(item.recordType, item.id) {
                        val selectionKey = "${item.recordType}:${item.id}"
                        PendingReviewRow(
                            repository = repository,
                            item = item,
                            reasons = reasons,
                            checked = selectionKey in selected,
                            onCheckedChange = { on ->
                                if (on) selected[selectionKey] = item else selected.remove(selectionKey)
                            },
                            onOpenRecord = onOpenRecord,
                            onResolved = { message ->
                                pending = pending.filterNot { it.recordType == item.recordType && it.id == item.id }
                                selected.remove(selectionKey)
                                total = (total - 1).coerceAtLeast(0)
                                info = message
                            },
                            onInfo = { info = it },
                            onError = onError
                        )
                    }
                }
                if (pages > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { page -= 1 }, enabled = page > 1 && !loading, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Previous")
                        }
                        Text("Page $page of $pages", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { page += 1 }, enabled = page < pages && !loading, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Next")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingReviewRow(
    repository: FieldRepository,
    item: PendingReviewDto,
    reasons: List<ReviewReasonDto> = emptyList(),
    checked: Boolean = false,
    onCheckedChange: (Boolean) -> Unit = {},
    onOpenRecord: ((EntryMode, String) -> Unit)? = null,
    onResolved: (String) -> Unit,
    onInfo: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<String?>(null) }
    var confirmReject by remember { mutableStateOf(false) }
    var reviseNote by remember { mutableStateOf("") }
    var rejectNote by remember { mutableStateOf("") }
    var reasonCode by remember { mutableStateOf("") }
    val reason = reasons.firstOrNull { it.code == reasonCode }
    var editNote by remember { mutableStateOf("") }
    var original by remember { mutableStateOf<Map<String, String>?>(null) }
    val edited = remember { mutableStateMapOf<String, String>() }
    val fields = remember(item.recordType) { reviewEditableFields(item.recordType) }

    fun act(ok: String, resolves: Boolean, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            runCatching { block() }
                .onSuccess { if (resolves) onResolved(ok) else onInfo(ok) }
                .onFailure { onError(it.apiErrorMessage("That review action didn't go through")) }
            busy = false
        }
    }

    val context = LocalContext.current
    var confirmApprove by remember { mutableStateOf(false) }
    fun approve() {
        scope.launch {
            busy = true
            runCatching { repository.approveRecord(context, item.recordType, item.id, item.label) }
                .onSuccess { onResolved(ReviewFeedbackCopy.approvalOutcome(item.label, it)) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    onError(it.apiErrorMessage("That review action didn't go through"))
                }
            busy = false
        }
    }

    LaunchedEffect(panel) {
        if (panel != "edit" || original != null || fields.isEmpty()) return@LaunchedEffect
        runCatching { loadReviewRecordValues(repository, item.recordType, item.id) }
            .onSuccess { values ->
                original = values
                edited.clear()
                edited.putAll(values)
            }
            .onFailure {
                panel = null
                onError(it.apiErrorMessage("Unable to open ${item.label} for editing"))
            }
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val openMode = recentSubmissionEntryMode(item.recordType)
            val open = onOpenRecord
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            FieldCheckbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Select ${item.label}" }
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (openMode == null || open == null) Modifier
                        else Modifier.clickable(role = Role.Button, onClickLabel = "Open ${item.label}") { open(openMode, item.id) }
                    )
            ) {
                Text(item.label, color = Body, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        item.recordType.replaceFirstChar { it.uppercase() },
                        item.place?.takeIf { it.isNotBlank() },
                        item.createdBy?.name?.takeIf { it.isNotBlank() }?.let { "by $it" },
                        formatIsoDate(item.submittedAt ?: item.createdAt)
                    ).joinToString(" · "),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            }
            if (item.resubmitted) {
                Text("Resubmitted", color = Coral, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                item.previousReview?.notes?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        "Last time" + (item.previousReview.reviewerName?.let { " ($it)" } ?: "") + ": " + note,
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (item.needsAdminApproval) {
                Text(
                    "Outside workshop dates — admin approval needed.",
                    color = Coral,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            ActionRow(
                destructive = {
                    OutlinedButton(
                        onClick = {
                            if (reasons.isEmpty()) confirmReject = true
                            else {
                                reasonCode = ""
                                panel = if (panel == "reject") null else "reject"
                            }
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) { Text("Reject", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                }
            ) {
                Button(
                    onClick = { if (item.pendingMediaCount > 0) confirmApprove = true else approve() },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text(if (busy) "Working…" else "Approve", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                OutlinedButton(
                    onClick = { panel = if (panel == "edit") null else "edit" },
                    enabled = !busy && fields.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (panel == "edit") "Close" else RECORD_UPDATE_LABEL, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = {
                        reasonCode = ""
                        panel = if (panel == "revise") null else "revise"
                    },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text(if (panel == "revise") "Close" else "Send for revision", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }

            when (panel) {
                "revise" -> {
                    Text(
                        "Comments required — the creator sees them.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    ReviewReasonPicker(panel = "revise", reasons = reasons, reasonCode = reasonCode) { reasonCode = it }
                    TextInput("What needs to change?", reviseNote, minLines = 2) { reviseNote = it }
                    Button(
                        enabled = !busy && reviseNote.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            act("Sent ${item.label} back for revision", resolves = true) {
                                repository.reviseRecord(
                                    item.recordType, item.id, reviseNote.trim(), reason?.takeIf { it.fixable }?.code
                                )
                            }
                        }
                    ) { Text(if (busy) "Sending…" else "Send back with comments") }
                }
                "reject" -> {
                    ReviewReasonPicker(panel = "reject", reasons = reasons, reasonCode = reasonCode) { reasonCode = it }
                    TextInput(
                        if (reason?.needsNote == true) "Note for the contributor (required)" else "Note for the contributor (optional)",
                        rejectNote,
                        minLines = 2
                    ) { rejectNote = it }
                    Button(
                        enabled = !busy && ReviewFeedbackCopy.refusalReady(reason, rejectNote),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (ReviewFeedbackCopy.refusalOutcome(reason) == "REJECTED") confirmReject = true
                            else act("Sent ${item.label} back for revision", resolves = true) {
                                repository.rejectRecord(item.recordType, item.id, reason?.code, rejectNote)
                            }
                        }
                    ) { Text(if (busy) "Sending…" else ReviewFeedbackCopy.refusalButtonLabel(reason)) }
                }
                "edit" -> {
                    val baseline = original
                    if (baseline == null) {
                        Text("Loading ${item.recordType}…", color = Muted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "Fix values directly. This doesn't approve it; the edit is logged under your name.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        fields.forEach { field ->
                            val value = edited[field.key].orEmpty()
                            TextInput(
                                field.label,
                                value,
                                minLines = if (field.multiline) 2 else 1,
                                dictate = field.multiline,
                            ) {
                                edited[field.key] = it
                            }
                            if (field.key in com.fieldrepository.app.data.TITLE_CASE_FIELDS) {
                                titleCasePreview(value)?.let { normalised ->
                                    Text("Will be saved as “$normalised”", color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        TextInput("Why? (optional, logged with the edit)", editNote) { editNote = it }
                        val changed = fields
                            .map { it.key }
                            .filter { key -> edited[key].orEmpty() != baseline[key].orEmpty() }
                            .associateWith { key -> edited[key].orEmpty() }
                        Button(
                            enabled = !busy && changed.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                act("Edited ${item.label} (${countNoun(changed.size, "field")})", resolves = false) {
                                    repository.editReviewedRecord(item.recordType, item.id, changed, editNote)
                                    val fresh = loadReviewRecordValues(repository, item.recordType, item.id)
                                    original = fresh
                                    edited.clear()
                                    edited.putAll(fresh)
                                    editNote = ""
                                }
                            }
                        ) {
                            Text(
                                when {
                                    busy -> "Saving…"
                                    changed.isEmpty() -> "No changes yet"
                                    else -> "Save ${countNoun(changed.size, "change")}"
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmApprove) {
        AlertDialog(
            onDismissRequest = { confirmApprove = false },
            title = { Text("Approve ${item.label}?") },
            text = { Text(ReviewFeedbackCopy.alsoApproves(item.pendingMediaCount).orEmpty()) },
            confirmButton = { TextButton(onClick = { confirmApprove = false; approve() }) { Text("Approve") } },
            dismissButton = { TextButton(onClick = { confirmApprove = false }) { Text("Cancel") } }
        )
    }

    if (confirmReject) {
        AlertDialog(
            onDismissRequest = { confirmReject = false },
            title = { Text("Reject this record?") },
            text = {
                Text(
                    "${item.label} will be rejected. To ask for fixes, use Send for revision."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReject = false
                    act("Rejected ${item.label}", resolves = true) {
                        repository.rejectRecord(item.recordType, item.id, reason?.code, rejectNote)
                    }
                }) { Text("Reject record", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReject = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DatasetDownloadCard(repository: FieldRepository, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var reportBusy by remember { mutableStateOf(false) }
    var reportMessage by remember { mutableStateOf<String?>(null) }

    RecordCard(title = "Download entire dataset") {
        Text(
            "Every record and file in one zip, organised by workshop. This can take a while.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (downloading) {
            val fraction = if (total > 0) done.toFloat() / total.toFloat() else 0f
            FieldLinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text("Compiling $done / $total files (${(fraction * 100).toInt()}%)", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        resultMessage?.let { Text(it, color = Body, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                if (downloading) return@Button
                resultMessage = null
                downloading = true
                done = 0
                total = 0
                scope.launch {
                    runCatching {
                        repository.downloadDataset(context) { d, t -> done = d; total = t }
                    }.onSuccess { res ->
                        resultMessage = "Saved to ${res.displayLocation} — ${res.saved}/${res.total} files" +
                            (if (res.failed > 0) " (${res.failed} could not be fetched)" else "") +
                            (if (res.truncated) " — not every record included" else "")
                    }.onFailure { onError(it.apiErrorMessage("Unable to download the dataset")) }
                    downloading = false
                }
            },
            enabled = !downloading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (downloading) "Downloading…" else "Download all data (.zip)")
        }
        HorizontalDivider()
        Text(
            "Or download an .xlsx report, one sheet per record type.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        reportMessage?.let { Text(it, color = Body, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            onClick = {
                if (reportBusy) return@OutlinedButton
                reportMessage = null
                reportBusy = true
                scope.launch {
                    runCatching { repository.downloadReport(context) }
                        .onSuccess { location -> reportMessage = "Report saved to $location" }
                        .onFailure { onError(it.apiErrorMessage("Unable to download the report")) }
                    reportBusy = false
                }
            },
            enabled = !reportBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (reportBusy) "Preparing report…" else "Download report (.xlsx)")
        }
    }
}

private val CompletionGreen = Color(0xFF2E9E5B)
private val CompletionAmber = Color(0xFFE2A400)
private val CompletionRed = Color(0xFFD25141)
private val CompletionEmpty: Color get() = com.fieldrepository.app.ui.Hairline

private fun completionColor(status: String?, derived: Boolean): Color = when (status) {
    "COMPLETED" -> CompletionGreen
    "NEEDS_REVIEW" -> CompletionAmber
    "NEEDS_REDO" -> CompletionRed
    else -> if (derived) CompletionGreen else CompletionEmpty
}

@Composable
private fun CompletionMatrixCard(
    repository: FieldRepository,
    artisanId: String? = null,
    canEdit: Boolean = false,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val workshopScope = rememberWorkshopScope(repository = repository, onError = onError)
    var matrix by remember(artisanId) { mutableStateOf<CompletionMatrixDto?>(null) }
    var loading by remember(artisanId) { mutableStateOf(true) }
    var cellOverrides by remember(artisanId) { mutableStateOf<Map<Pair<String, String>, String?>>(emptyMap()) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(artisanId, workshopScope.settled, workshopScope.requestKey) {
        if (!workshopScope.settled) return@LaunchedEffect
        loading = true
        runCatching { repository.completionMatrix(artisanId, workshopScope.workshopIds) }
            .onSuccess {
                matrix = it
                cellOverrides = emptyMap()
                loading = false
            }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                onError(it.apiErrorMessage("Unable to load completion"))
                loading = false
            }
    }

    RecordCard(title = "Check completion", icon = Icons.Filled.GridView) {
        Text(
            "Green means recorded. Pinch to zoom." +
                if (canEdit) " Tap a cell to set its status." else "",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        WorkshopScopeSelect(scope = workshopScope, label = "Workshops in this matrix")

        val hidden = matrix?.unassignedInterviews ?: 0
        if (hidden > 0) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, MaterialTheme.shapes.small)
                    .padding(10.dp)
            ) {
                Text(
                    "$hidden interview${if (hidden == 1) " has" else "s have"} no workshop",
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Choose All records to include ${if (hidden == 1) "it" else "them"}" +
                        if (canEdit) {
                            ", or file ${if (hidden == 1) "it" else "them"} under a workshop from the " +
                                "Workshop screen."
                        } else ".",
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            CompletionLegendChip(CompletionGreen, "Completed")
            CompletionLegendChip(CompletionAmber, "Requires review")
            CompletionLegendChip(CompletionRed, "Needs redo")
            CompletionLegendChip(CompletionEmpty, "Not started")
        }
        if (matrix?.overridesAreRepositoryWide == true) {
            Text(
                "Admin-marked cells keep their colour in every scope.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        val data = matrix
        when {
            loading -> Text("Loading completion…", color = Muted, style = MaterialTheme.typography.bodySmall)
            data == null || data.sections.isEmpty() -> Text("No questionnaire sections defined yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
            data.artisans.isEmpty() -> Text(
                if (!workshopScope.isAllRecords) {
                    "No artisans in these workshops."
                } else {
                    "No artisans yet."
                },
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            else -> {
                val grid = data
                val cellByKey: Map<Pair<String, String>, CompletionCellDto> =
                    remember(grid) { grid.cells.associateBy { c -> c.artisanId to c.sectionId } }
                val nameWidth = 132.dp
                val colWidth = 52.dp
                val rowHeight = 40.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(440.dp)
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.5f, 3f)
                                offset += pan
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Row {
                            CompletionHeaderCell("Artisan", nameWidth, rowHeight, alignStart = true)
                            data.sections.forEach { section ->
                                CompletionHeaderCell(section.code, colWidth, rowHeight)
                            }
                        }
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            data.artisans.forEach { artisan ->
                                Row {
                                    Box(
                                        modifier = Modifier
                                            .width(nameWidth)
                                            .height(rowHeight)
                                            .background(Canvas)
                                            .padding(horizontal = 6.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text(artisan.name, color = Body, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    data.sections.forEach { section ->
                                        val key = artisan.id to section.id
                                        val cell = cellByKey[key]
                                        val status = if (cellOverrides.containsKey(key)) cellOverrides[key] else cell?.status
                                        val derived = cell?.derived == true
                                        val cellWords = "${artisan.name}, ${section.code}: " + when {
                                            status == "COMPLETED" || (status == null && derived) -> "complete"
                                            status == "NEEDS_REVIEW" -> "needs review"
                                            status == "NEEDS_REDO" -> "needs redo"
                                            else -> "not started"
                                        }
                                        Box(
                                            modifier = Modifier
                                                .width(colWidth)
                                                .height(rowHeight)
                                                .padding(1.dp)
                                                .background(completionColor(status, derived), RoundedCornerShape(3.dp))
                                                .then(
                                                    if (canEdit) {
                                                        Modifier.clickable(onClickLabel = "Change status", role = Role.Button) { editing = key }
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                                .semantics(mergeDescendants = true) { contentDescription = cellWords },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (status == "COMPLETED" || (status == null && derived)) {
                                                Text("✓", color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                            } else if (status == "NEEDS_REVIEW") {
                                                Text("!", color = Color(0xFF3B2300), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                            } else if (status == "NEEDS_REDO") {
                                                Text("✗", color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val cellToEdit = editing
    val loadedMatrix = matrix
    if (cellToEdit != null && canEdit && loadedMatrix != null) {
        val section = loadedMatrix.sections.firstOrNull { s -> s.id == cellToEdit.second }
        val sectionName = if (section != null) "${section.code} · ${section.title}" else "section"
        val artisan = loadedMatrix.artisans.firstOrNull { a -> a.id == cellToEdit.first }
        val artisanName = artisan?.name ?: "artisan"
        fun apply(status: String?) {
            editing = null
            cellOverrides = cellOverrides + (cellToEdit to status)
            scope.launch {
                runCatching { repository.setCompletionCell(cellToEdit.first, cellToEdit.second, status) }
                    .onFailure {
                        onError(it.apiErrorMessage("Unable to update status"))
                        cellOverrides = cellOverrides - cellToEdit
                    }
            }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            title = { Text("Mark $sectionName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("for $artisanName", color = Muted, style = MaterialTheme.typography.bodySmall)
                    CompletionStatusButton(CompletionGreen, "Completed") { apply("COMPLETED") }
                    CompletionStatusButton(CompletionAmber, "Requires review") { apply("NEEDS_REVIEW") }
                    CompletionStatusButton(CompletionRed, "Needs to be redone") { apply("NEEDS_REDO") }
                    TextButton(onClick = { apply(null) }) { Text("Clear (use auto-detected)") }
                }
            }
        )
    }
}

@Composable
private fun CompletionLegendChip(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CompletionHeaderCell(text: String, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, alignStart: Boolean = false) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(SurfaceCard)
            .padding(horizontal = 4.dp),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center
    ) {
        Text(text, color = Body, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompletionStatusButton(color: Color, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.size(18.dp).background(color, RoundedCornerShape(4.dp)))
        Text(label, color = Body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RecordViewScreen(
    repository: FieldRepository,
    mode: EntryMode,
    recordId: String,
    section: String? = null,
    canUpdate: Boolean,
    onUpdate: (String?) -> Unit,
    canOverrideCompletion: Boolean,
    showProvenance: Boolean,
    mayReview: Boolean = false,
    canDelete: Boolean = false,
    onDeleted: () -> Unit = {},
    onMessage: (String) -> Unit = {},
    onError: (String) -> Unit
) {
    var status by remember(mode, recordId) { mutableStateOf<String?>(null) }
    var refresh by remember(mode, recordId) { mutableIntStateOf(0) }
    val reviewType = ReviewFeedbackCopy.recordTypeFor(mode.name)
    val mediaReviewAnswer = remember(reviewType, recordId, mayReview, refresh) { CompletableDeferred<RecordMediaReviewDto?>() }
    reviewType?.let { type ->
        ReviewNoteBanner(repository = repository, recordType = type, recordId = recordId, knownStatus = status)
        RecordReviewBar(
            repository = repository,
            recordType = type,
            recordId = recordId,
            knownStatus = status,
            mayReview = mayReview,
            mediaReview = mediaReviewAnswer,
            onDecided = { message -> onMessage(message); refresh += 1 }
        )
    }
    var completeness by remember(mode, recordId) { mutableStateOf<CompletenessDto?>(null) }
    RefreshCompletenessRules(repository)
    if (canUpdate) {
        Button(onClick = { onUpdate(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(RECORD_UPDATE_LABEL)
        }
    }
    var mediaReview by remember(mode, recordId) { mutableStateOf<MediaReviewScope?>(null) }
    LaunchedEffect(reviewType, recordId, mayReview, refresh) {
        try {
            if (!mayReview || reviewType == null) {
                mediaReview = null
                return@LaunchedEffect
            }
            val reasons = runCatching { repository.reviewReasons() }.getOrDefault(emptyList())
            runCatching { repository.recordMediaReview(reviewType, recordId) }
                .onSuccess { answer ->
                    mediaReviewAnswer.complete(answer)
                    mediaReview = MediaReviewScope(repository, answer.rejectable.toSet(), reasons) { message ->
                        onMessage(message)
                        refresh += 1
                    }
                }
                .onFailure { mediaReview = null }
        } finally {
            mediaReviewAnswer.complete(null)
        }
    }
    completeness?.let { block ->
        RecordCard(title = "Completeness") {
            CompletenessMeter(block = block, canUpdate = canUpdate, onUpdate = { key -> onUpdate(key) })
        }
    }
    key(refresh) {
        CompositionLocalProvider(LocalMediaReview provides mediaReview) {
            ViewDataDetail(
                repository = repository,
                mode = mode,
                recordId = recordId,
                onStatus = { status = it },
                canOverrideCompletion = canOverrideCompletion,
                showProvenance = showProvenance,
                onError = onError,
                onCompleteness = { completeness = it },
                focusSection = section
            )
        }
    }
    if (canDelete && mode in DELETABLE_MODES) {
        DeleteRecordSection(repository, mode, recordId, onDeleted = onDeleted, onError = onError)
    }
}

private val completenessRulesRefreshed = java.util.concurrent.atomic.AtomicBoolean(false)

@Composable
private fun RefreshCompletenessRules(repository: FieldRepository) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        if (completenessRulesRefreshed.compareAndSet(false, true)) {
            runCatching { RecordCompletenessStore.refresh(context) { repository.completenessRulesText() } }
                .onFailure { completenessRulesRefreshed.set(false) }
        }
    }
}

@Composable
private fun FormCompletenessLine(kind: String, values: Map<String, Any?>, stored: CompletenessDto?, add: Map<String, Int>) {
    val rules = rememberCompletenessRules() ?: return
    LiveCompletenessLine(evaluateCompleteness(rules, kind, values, liveCompletenessCounts(rules, kind, stored, add)))
}

@Composable
private fun ViewDataDetail(
    repository: FieldRepository,
    mode: EntryMode,
    recordId: String,
    canOverrideCompletion: Boolean = false,
    showProvenance: Boolean = false,
    onStatus: (String?) -> Unit = {},
    onError: (String) -> Unit,
    onCompleteness: (CompletenessDto?) -> Unit = {},
    focusSection: String? = null
) {
    val context = LocalContext.current
    var loadError by remember(recordId) { mutableStateOf<String?>(null) }
    var loadAttempt by remember(recordId) { mutableIntStateOf(0) }
    val retryLoad = { loadError = null; loadAttempt += 1 }
    when (mode) {
        EntryMode.PROCESS -> {
            var detail by remember(recordId) { mutableStateOf<ProcessDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) {
                runCatching { repository.process(recordId) }.onSuccess { detail = it; onStatus(it.status) }.onFailure { loadError = it.apiErrorMessage("Couldn't load this process.") }
            }
            val d = detail ?: return run { LoadingCard(mode, loadError, retryLoad) }
            RecordCard(title = d.name.ifBlank { "Process" }) {
                if (showProvenance) ProvenanceSection(meta = d.extraMetadata, createdByName = d.createdBy?.name)
                DetailRow("Product", d.product?.productName)
                DetailRow("Pre-processes", if (d.preProcessAvailable) "Yes" else "No")
                DetailRow("Notes", d.notes)
                DetailRow("Status", d.status)
                if (d.media.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Pre-process media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    d.media.forEach { MediaWithTranscript(context, it, repository) }
                }
                d.steps.forEach { step ->
                    HorizontalDivider()
                    Text("Step ${step.sortOrder} · ${if (step.stepType == "SEQUENTIAL") "Sequential" else "Group"} — ${step.name}", color = Body, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    step.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                    if (step.media.isEmpty()) Text("No media.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    step.media.forEach { MediaWithTranscript(context, it, repository) }
                }
            }
        }
        EntryMode.ARTISAN -> {
            var d by remember(recordId) { mutableStateOf<ArtisanDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) { runCatching { repository.artisan(recordId) }.onSuccess { d = it; onStatus(it.status); onCompleteness(it.completeness) }.onFailure { loadError = it.apiErrorMessage("Couldn't load this artisan.") } }
            val v = d ?: return run { LoadingCard(mode, loadError, retryLoad) }
            RecordCard(title = v.name.ifBlank { "Artisan" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Craft", v.craft?.name)
                DetailRow("Place", v.place)
                DetailRow("Gender", v.gender)
                DetailRow("Phone", StandardValues.formatPhone(v.phoneCountryCode, v.phone))
                DetailRow("Email", v.email)
                DetailRow("Address", v.address)
                DetailRow("Notes", v.notes)
                DetailRow(
                    "Aadhaar number",
                    if (v.aadhaarNotShared && v.aadhaarNumber.isNullOrBlank()) "Not shared by artisan — follow up" else maskAadhaar(v.aadhaarNumber)
                )
                DetailRow("Pehchan card", if (v.pehchanCardAvailable) "Yes" else "No")
                DetailRow("Pehchan card number", v.pehchanCardNumber)
                NumberedListDisplay("Do's", v.dos)
                NumberedListDisplay("Don'ts", v.donts)
                DetailRow("Status", v.status)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
            }
            CompletionMatrixCard(repository = repository, artisanId = recordId, canEdit = canOverrideCompletion, onError = onError)
            ArtisanQuestionnaireData(repository = repository, artisanId = recordId, focusSection = focusSection)
        }
        EntryMode.PRODUCT -> {
            var d by remember(recordId) { mutableStateOf<ProductDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) { runCatching { repository.product(recordId) }.onSuccess { d = it; onStatus(it.status); onCompleteness(it.completeness) }.onFailure { loadError = it.apiErrorMessage("Couldn't load this product.") } }
            val v = d ?: return run { LoadingCard(mode, loadError, retryLoad) }
            RecordCard(title = v.productName.ifBlank { "Product" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Craft", v.craftName)
                DetailRow("Artisan", v.artisanName)
                DetailRow("Place", v.place)
                DetailRow("Type", v.productType)
                DetailRow("Market", v.marketDemand)
                DetailRow("Materials", v.rawMaterialsUsed)
                DetailRow("Tools", v.mainToolsUsed)
                DetailRow("Function", v.productFunctionUse)
                DetailRow("Remarks", v.remarks)
                DetailRow("Status", v.status)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
            }
        }
        EntryMode.TOOL -> {
            var d by remember(recordId) { mutableStateOf<ToolDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) { runCatching { repository.tool(recordId) }.onSuccess { d = it; onStatus(it.status); onCompleteness(it.completeness) }.onFailure { loadError = it.apiErrorMessage("Couldn't load this tool.") } }
            val v = d ?: return run { LoadingCard(mode, loadError, retryLoad) }
            RecordCard(title = v.toolkitName.ifBlank { "Tool" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Craft", v.craftName)
                DetailRow("Artisan", v.artisanName)
                DetailRow("Place", v.place)
                DetailRow("Material", v.material)
                DetailRow("Material type", StandardValues.materialFamilyLabel(v.materialFamily))
                DetailRow("Maker", v.maker)
                DetailRow("Tradition", StandardValues.traditionLabel(v.traditionType))
                DetailRow("Used in", v.processUsedIn)
                DetailRow("Remarks", v.remarks)
                DetailRow("Status", v.status)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
            }
        }
        EntryMode.WORKSHOP -> {
            var d by remember(recordId) { mutableStateOf<WorkshopDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) { runCatching { repository.workshop(recordId) }.onSuccess { d = it; onStatus(it.status); onCompleteness(it.completeness) }.onFailure { loadError = it.apiErrorMessage("Couldn't load this workshop.") } }
            val v = d ?: return run { LoadingCard(mode, loadError, retryLoad) }
            RecordCard(title = v.title.ifBlank { "Workshop" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Place", v.place)
                DetailRow("Description", v.description)
                DetailRow("Notes", v.notes)
                DetailRow("Status", v.status)
                DetailRow("Artisans", v.artisans.mapNotNull { it.artisan?.name }.joinToString(", ").ifBlank { null })
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
                HorizontalDivider()
                com.fieldrepository.app.questionnaire.ui.RecordingsList(
                    remember { com.fieldrepository.app.questionnaire.data.QuestionnaireV2.get(context) },
                    fixed = remember(recordId) { com.fieldrepository.app.questionnaire.data.QuestionnaireFilters().with("workshopId", listOf(recordId)) },
                    screen = "workshop-recordings-$recordId",
                    onOpenSession = { sessionId -> QuestionnaireV2Nav.openServerSession.value = sessionId }
                )
            }
        }
        EntryMode.CRAFT -> {
            var d by remember(recordId) { mutableStateOf<CraftDto?>(null) }
            val craftList by CraftCatalogueCache.catalogue.collectAsState()
            LaunchedEffect(recordId, loadAttempt) { runCatching { repository.craft(recordId) }.onSuccess { d = it }.onFailure { loadError = it.apiErrorMessage("Couldn't load this craft.") } }
            val v = d ?: return run { LoadingCard(mode, loadError, retryLoad) }
            RecordCard(title = v.name.ifBlank { "Craft" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Local name", v.localName)
                DetailRow("Category", v.category)
                DetailRow("Sub-category", craftList?.let { list ->
                    list.categories.flatMap { it.subCategories }.firstOrNull { it.id == v.subCategoryId }?.name
                })
                DetailRow("Serial", v.sourceSerial?.toString())
                DetailRow("GI status", when (v.giStatus) { "GI" -> "GI"; "NON_GI" -> "Non GI"; else -> null })
                DetailRow("State of origin", v.stateOfOrigin.joinToString(", ").ifBlank { null })
                DetailRow("State of practice", v.stateOfPractice.joinToString(", ").ifBlank { null })
                DetailRow("Place", v.place)
                DetailRow("Description", v.description)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
            }
        }
        EntryMode.QUESTIONNAIRE -> {
            var members by remember(recordId) { mutableStateOf<List<QuestionnaireInterviewDetailDto>?>(null) }
            var groupMedia by remember(recordId) { mutableStateOf<List<MediaFileDto>>(emptyList()) }
            LaunchedEffect(recordId, loadAttempt) {
                runCatching {
                    val selected = repository.interview(recordId)
                    onCompleteness(selected.completeness)
                    val group = interviewGroup(repository, selected)
                    val mediaById = LinkedHashMap<String, MediaFileDto>()
                    group.forEach { m ->
                        runCatching { repository.mediaForRecord("questionnaire", m.id) }.getOrDefault(emptyList())
                            .forEach { mediaById[it.id] = it }
                    }
                    group to mediaById.values.toList()
                }.onSuccess { (g, media) ->
                    members = g
                    groupMedia = media
                    onStatus(g.firstOrNull { it.id == recordId }?.status)
                }
                    .onFailure { loadError = it.apiErrorMessage("Couldn't load this interview.") }
            }
            val g = members ?: return run { LoadingCard(mode, loadError, retryLoad) }
            val rep = representativeInterview(g)
            RecordCard(title = rep.title.ifBlank { "Interview" }) {
                if (showProvenance) ProvenanceSection(meta = rep.extraMetadata, createdByName = rep.createdBy?.name)
                DetailRow("Artisans", rep.artisans.mapNotNull { it.artisan?.name }.joinToString(", ").ifBlank { null })
                DetailRow("Place", rep.place)
                DetailRow("Language", StandardValues.languageName(rep.language))
                DetailRow("Notes", rep.notes)
                DetailRow("Status", rep.status)
                val researchers = g.mapNotNull { it.createdBy?.name }.distinct().joinToString(", ")
                if (researchers.isNotBlank()) DetailRow(if (researchers.contains(", ")) "Interviewers" else "Interviewer", researchers)
                if (g.size > 1) DetailRow("Merged from", "${g.size} interview records with the same people")
                val allResponses = g.flatMap { it.responses }
                    .filter { !it.answerText.isNullOrBlank() }
                    .distinctBy { it.questionId to it.answerText }
                if (allResponses.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Answers", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    allResponses.forEach { r -> DetailRow(r.answeredBy?.name ?: "Answer", r.answerText) }
                }
                HorizontalDivider()
                Text("Recordings & media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                if (groupMedia.isEmpty()) Text("No media attached.", color = Muted, style = MaterialTheme.typography.bodySmall)
                else groupMedia.forEach { MediaWithTranscript(context, it, repository) }
            }
        }
        EntryMode.MEDIA -> {
            var d by remember(recordId) { mutableStateOf<MediaFileDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) { runCatching { repository.mediaItem(recordId) }.onSuccess { d = it }.onFailure { loadError = it.apiErrorMessage("Couldn't load this file.") } }
            val v = d ?: return run { LoadingCard(mode, loadError, retryLoad) }
            RecordCard(title = v.originalFilename.ifBlank { "Media" }) {
                DetailRow("Type", v.mediaType)
                DetailRow("Caption", v.caption)
                v.linkedRecordType?.takeIf { it.isNotBlank() }?.let { DetailRow("Linked to", it.replaceFirstChar { c -> c.uppercase() }) }
                MediaWithTranscript(context, v, repository)
            }
        }
        else -> Text("This record type cannot be viewed here.", color = Muted)
    }
    if (mode in setOf(
            EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.TOOL, EntryMode.WORKSHOP,
            EntryMode.PROCESS, EntryMode.QUESTIONNAIRE, EntryMode.CRAFT, EntryMode.MEDIA
        )
    ) {
        RecordCollabSection(repository, mode.linkedRecordType(), recordId, onError)
    }
}

@Composable
private fun RecordCollabSection(
    repository: FieldRepository,
    recordType: String,
    recordId: String,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var comments by remember(recordId) { mutableStateOf<List<EntryCommentDto>>(emptyList()) }
    var revisions by remember(recordId) { mutableStateOf<List<RecordRevisionDto>?>(null) }
    var draft by remember(recordId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val me = remember { repository.cachedUser() }
    var editingId by remember(recordId) { mutableStateOf<String?>(null) }
    var editDraft by remember(recordId) { mutableStateOf("") }

    fun reload() {
        scope.launch {
            runCatching { comments = repository.entryComments(recordType, recordId) }
                .onFailure { onError(it.apiErrorMessage("Unable to load comments")) }
            revisions = runCatching { repository.recordRevisions(recordType, recordId) }.getOrNull()
        }
    }

    LaunchedEffect(recordType, recordId) { reload() }

    RecordCard(title = "Comments & edit history") {
        Text("Comments", display = true, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        if (comments.isEmpty()) Text("No comments yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
        comments.forEach { c ->
            if (editingId == c.id) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextInput("Edit comment", editDraft, minLines = 2) { editDraft = it }
                    ActionRow {
                        OutlinedButton(enabled = !busy, onClick = { editingId = null }) { Text("Cancel") }
                        Button(
                            enabled = !busy && editDraft.isNotBlank(),
                            onClick = {
                                val next = editDraft.trim()
                                if (next == c.body) {
                                    editingId = null
                                    return@Button
                                }
                                scope.launch {
                                    busy = true
                                    runCatching { repository.updateEntryComment(c.id, next) }
                                        .onSuccess { editingId = null; reload() }
                                        .onFailure { onError(it.apiErrorMessage("Unable to save comment")) }
                                    busy = false
                                }
                            }
                        ) { Text(if (busy) "Saving…" else "Save") }
                    }
                }
                return@forEach
            }
            com.fieldrepository.app.ui.EntryCommentRow(
                comment = c,
                viewerId = me?.id,
                busy = busy,
                onEdit = { editingId = c.id; editDraft = c.body },
                onRemove = {
                    scope.launch {
                        busy = true
                        runCatching { repository.deleteEntryComment(c.id) }
                            .onSuccess { reload() }
                            .onFailure { onError(it.apiErrorMessage("Unable to remove the comment")) }
                        busy = false
                    }
                }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Add a comment (needs comment access)", draft) { draft = it } }
            Button(
                enabled = !busy && draft.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        val body = EntryCommentBody(recordType = recordType, recordId = recordId, body = draft.trim())
                        if (queueSmallWrite(repository, context, null, idempotent = false, type = "comment",
                                payloadJson = offlineFormJson.encodeToString(body), label = "Comment")) {
                            draft = ""
                            onError("Saved offline. Posts when you're online.")
                            busy = false
                            return@launch
                        }
                        runCatching { repository.addEntryComment(recordType, recordId, draft.trim()); draft = "" }
                            .onSuccess { reload() }
                            .onFailure { onError(it.apiErrorMessage("Unable to post comment")) }
                        busy = false
                    }
                }
            ) { Text("Post") }
        }
        revisions?.let { revs ->
            HorizontalDivider()
            Text("Edit history", display = true, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (revs.isEmpty()) Text("No edits recorded.", color = Muted, style = MaterialTheme.typography.bodySmall)
            revs.forEach { r ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("${r.editedBy?.name ?: "Unknown"} · ${r.createdAt.take(10)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    r.changes.forEach { (field, change) ->
                        Text(
                            "$field: ${jsonText(change.old)} → ${jsonText(change.new)}",
                            color = Body, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

private fun jsonText(value: kotlinx.serialization.json.JsonElement?): String {
    if (value == null || value is kotlinx.serialization.json.JsonNull) return "—"
    val prim = value as? kotlinx.serialization.json.JsonPrimitive ?: return value.toString()
    return prim.content
}

private val mediaLinkModes = listOf(
    EntryMode.ARTISAN, EntryMode.WORKSHOP, EntryMode.CRAFT, EntryMode.TOOL,
    EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.QUESTIONNAIRE, EntryMode.MEDIA
)

@Composable
private fun AndroidMediaForm(
    repository: FieldRepository,
    onUploaded: (Int) -> Unit,
    onQueued: (queued: Int, uploaded: Int) -> Unit = { _, _ -> },
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = rememberFormDraft(repository, "media", null)
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var mediaTitle by remember { mutableStateOf("") }.drafted(draft, "mediaTitle")
    var linkedMode by remember { mutableStateOf<EntryMode?>(null) }
    var linkedEntryId by remember { mutableStateOf("") }
    var restoredEntryId by remember { mutableStateOf<String?>(null) }
    var caption by remember { mutableStateOf("") }.drafted(draft, "caption")
    var location by remember { mutableStateOf<LocationRequest?>(null) }.drafted(draft, "location")
    var uploading by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf(ActionStatus.IDLE) }
    var uploadFraction by remember { mutableStateOf(0f) }
    var uploadProgressText by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var savedMedia by remember { mutableStateOf<List<com.fieldrepository.app.data.MediaFileDto>>(emptyList()) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var batchQueued by remember { mutableStateOf(false) }
    AutoResetStatus(uploadStatus) { uploadStatus = ActionStatus.IDLE; batchQueued = false }

    fun refreshMedia() {
        scope.launch {
            runCatching { repository.media() }
                .onSuccess { savedMedia = it }
                .onFailure { error -> onError(error.apiErrorMessage("Unable to load saved media")) }
        }
    }

    val permissionGate = rememberPermissionGate(onRefused = onError)
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) selectedUris = selectedUris + uris
    }
    val camera = rememberKeepShootingCamera(
        newUri = { createAppFileUri(context, "media-photo-", ".jpg") },
        onShot = { uri -> selectedUris = selectedUris + uri },
        onRunEnded = { localMessage = it }
    )
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) selectedUris = selectedUris + uri
        pendingCaptureUri = null
    }

    LaunchedEffect(Unit) {
        refreshMedia()
    }
    LaunchedEffect(linkedMode) {
        linkedEntryId = restoredEntryId ?: ""
        restoredEntryId = null
    }
    val entrySearch = rememberRecordRows(repository, linkedMode)
    val entryOptions = linkedMode?.let { recordRowOptions(it, entrySearch.known).map { o -> o.value to o.label } }.orEmpty()
    val loadingEntries = linkedMode != null && !entrySearch.browseLoaded && entrySearch.note == null

    val dictates = recordDictationFor(RecordFormKind.MEDIA)
    RecordCard(title = "Capture media") {
        DraftOfferCard(draft)
        draft.bindCustom("selectedUris", { FormDraft.encodeUris(selectedUris) }, { el -> FormDraft.decodeUris(el)?.let { selectedUris = it } })
        draft.bindCustom("linked", {
            linkedMode?.let { mode ->
                JsonObject(
                    mapOf(
                        "mode" to kotlinx.serialization.json.JsonPrimitive(mode.name),
                        "entryId" to kotlinx.serialization.json.JsonPrimitive(linkedEntryId)
                    )
                )
            }
        }, { el ->
            val o = el as JsonObject
            val mode = runCatching { EntryMode.valueOf((o["mode"] as kotlinx.serialization.json.JsonPrimitive).content) }.getOrNull()
            if (mode != null) {
                restoredEntryId = (o["entryId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                if (mode == linkedMode) { linkedEntryId = restoredEntryId ?: linkedEntryId; restoredEntryId = null } else linkedMode = mode
            }
        })
        FormDraftAutosave(
            draft,
            dirty = !uploading && (selectedUris.isNotEmpty() || mediaTitle.isNotBlank() || caption.isNotBlank()),
            label = mediaTitle.ifBlank { caption }
        )
        Text(
            "Tick Transcribe and translate on a recording or video to get its text.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = { pickMedia.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
            Text("Pick multiple files")
        }
        OutlinedButton(
            onClick = { permissionGate.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) { camera.shoot() } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Take image")
        }
        KeepShootingToggle()
        OutlinedButton(
            onClick = {
                permissionGate.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                    val uri = createAppFileUri(context, "field-video-", ".mp4")
                    pendingCaptureUri = uri
                    takeVideo.launch(uri)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Record video")
        }
        OutlinedButton(
            onClick = {
                if (!recording && !permissionGate.holds(MICROPHONE_PERMISSION)) {
                    permissionGate.ask(MICROPHONE_PERMISSION, MICROPHONE_REFUSED_SENTENCE)
                } else if (!recording) {
                    runCatching {
                        val file = createAppFile(context, "field-audio-", ".m4a")
                        recorder = startHeldTake(context, file)
                        recordingFile = file
                        recording = true
                        localMessage = "Recording audio..."
                    }.onFailure { error ->
                        onError(error.apiErrorMessage("Unable to start audio recording"))
                    }
                } else {
                    runCatching {
                        stopHeldTake(context)?.let { file -> selectedUris = selectedUris + uriForFile(context, file) }
                    }.onFailure { error ->
                        onError(error.apiErrorMessage("Unable to stop audio recording"))
                    }
                    recorder = null
                    recordingFile = null
                    recording = false
                    localMessage = "Audio recording added to batch"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (recording) StopSquareLabel("Stop audio recording") else Text("Record audio ●")
        }
        if (recording) {
            RecordingIndicator(getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) })
        }
        TextInput("Media title / object name", mediaTitle, dictate = dictates("mediaTitle")) { mediaTitle = it }
        DropdownField(
            label = "Linked record type *",
            options = mediaLinkModes.map { it.name to it.label },
            selectedValue = linkedMode?.name ?: "",
            placeholder = "Choose the type of record",
            includeNone = false
        ) { picked -> linkedMode = mediaLinkModes.firstOrNull { it.name == picked } }
        DropdownField(
            label = "Linked entry (optional)",
            options = entryOptions,
            selectedValue = linkedEntryId,
            placeholder = when {
                linkedMode == null -> "Select a record type first"
                loadingEntries -> "Loading…"
                entryOptions.isEmpty() -> "No entries for this type"
                else -> "Select an entry"
            },
            includeNone = true,
            enabled = linkedMode != null && !loadingEntries,
            server = linkedMode?.let { m -> entrySearch.asServerSearchRows(countsRows = true) { recordRowOptions(m, it) } }
        ) { linkedEntryId = it }
        TextInput("Caption", caption, minLines = 2, dictate = dictates("caption")) { caption = it }
        LocationAddressEditor(
            repository = repository,
            value = location,
            onUseGps = {
                if (!holdsLocation(context)) locationLauncher.launch(LOCATION_PERMISSIONS)
                readLastKnownLocation(context)
            },
            onChange = { location = it },
            onMessage = { localMessage = it }
        )
        if (selectedUris.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = MaterialTheme.field.brandTile, shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("${countNoun(selectedUris.size, "file")} ready", color = MaterialTheme.field.onBrandTile, fontWeight = FontWeight.SemiBold)
                var batchExpanded by remember { mutableStateOf(false) }
                val batchCap = 8
                val shownBatch = if (batchExpanded) selectedUris else selectedUris.take(batchCap)
                shownBatch.forEach { uri ->
                    AndroidUriPreview(
                        context = context,
                        uri = uri,
                        downloadable = true,
                        onTile = true,
                        onRemove = { selectedUris = selectedUris.filterNot { it == uri } }
                    )
                }
                val tileInk = MaterialTheme.field.onBrandTile
                if (selectedUris.size > batchCap) {
                    ActionRow {
                        TextButton(
                            onClick = { batchExpanded = !batchExpanded },
                            colors = ButtonDefaults.textButtonColors(contentColor = tileInk)
                        ) {
                            Text(if (batchExpanded) "Show fewer" else "+${selectedUris.size - batchCap} more", color = tileInk)
                        }
                    }
                }
                ActionRow {
                    OutlinedButton(
                        onClick = { selectedUris = emptyList() },
                        border = BorderStroke(1.dp, tileInk.copy(alpha = TILE_OUTLINE_ALPHA)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = tileInk)
                    ) {
                        Text("Clear batch", color = tileInk, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            UriTranscribeChoices(selectedUris)
        }
        localMessage?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        if (uploading) {
            FieldLinearProgressIndicator(progress = { uploadFraction }, modifier = Modifier.fillMaxWidth())
            uploadProgressText?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
        Button(
            onClick = {
                scope.launch {
                    uploading = true
                    uploadFraction = 0f
                    uploadProgressText = null
                    val uris = selectedUris
                    suspend fun queueBatch(files: List<Uri>, uploaded: Int = 0): Boolean {
                        val mode = linkedMode ?: return false
                        val title = mediaTitle.ifBlank { caption }
                        val queued = runCatching {
                            repository.queueAttachments(
                                context, mode.linkedRecordType(), linkedEntryId,
                                title.trim().ifBlank { "Media" },
                                files.map { uri ->
                                    OfflineMediaSpec(
                                        uri = uri, caption = caption.ifBlank { null }, recordName = title,
                                        batchIndex = uris.indexOf(uri) + 1, location = location
                                    )
                                }
                            )
                        }.isSuccess
                        if (queued) {
                            draft.clear()
                            selectedUris = emptyList()
                            mediaTitle = ""
                            caption = ""
                            localMessage = null
                            uploadStatus = ActionStatus.SUCCESS
                            batchQueued = true
                            onQueued(files.size, uploaded)
                        }
                        return queued
                    }
                    if (!repository.isOnline(context) && queueBatch(uris)) {
                        uploading = false
                        return@launch
                    }
                    val sizes = uris.map { queryMediaSize(context, it) }
                    val totalBytes = sizes.sum().coerceAtLeast(1L)
                    val startMs = System.currentTimeMillis()
                    var completedBytes = 0L
                    var success = 0
                    val failedUris = mutableListOf<Uri>()
                    var cancelled = false
                    uris.forEachIndexed { index, uri ->
                        if (cancelled) return@forEachIndexed
                        var lastPct = -1
                        val result = runCatching {
                            repository.uploadMedia(
                                context = context,
                                uri = uri,
                                linkedRecordType = linkedMode?.linkedRecordType() ?: "",
                                linkedRecordId = linkedEntryId,
                                caption = caption,
                                location = location,
                                titleHint = mediaTitle.ifBlank { caption },
                                batchIndex = index + 1,
                                onProgress = { sent, _ ->
                                    val done = completedBytes + sent
                                    val pct = ((done.toFloat() / totalBytes) * 100f).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        uploadFraction = pct / 100f
                                        val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
                                        val rate = if (elapsed > 0) done / elapsed else 0.0
                                        val eta = if (rate > 0) ((totalBytes - done) / rate).toLong() else -1L
                                        uploadProgressText = "File ${index + 1}/${uris.size} · $pct%" +
                                            (if (eta >= 0) " · ${formatEta(eta)}" else "")
                                    }
                                }
                            )
                        }
                        when {
                            result.isSuccess -> success++
                            result.exceptionOrNull() is kotlinx.coroutines.CancellationException -> cancelled = true
                            else -> failedUris.add(uri)
                        }
                        completedBytes += sizes[index]
                        uploadFraction = (completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                    }
                    uploading = false
                    uploadProgressText = null
                    uploadFraction = 0f
                    when {
                        cancelled -> Unit
                        failedUris.isEmpty() -> {
                            selectedUris = emptyList()
                            mediaTitle = ""
                            caption = ""
                            localMessage = null
                            uploadStatus = ActionStatus.SUCCESS
                            refreshMedia()
                            draft.clear(); onUploaded(success)
                        }
                        linkedEntryId.isNotBlank() && linkedMode != null && queueFailedAttachments(
                            repository, context, linkedMode!!.linkedRecordType(), linkedEntryId,
                            mediaTitle.ifBlank { caption },
                            failedUris.map { uri ->
                                OfflineMediaSpec(
                                    uri = uri, caption = caption.ifBlank { null }, recordName = mediaTitle.ifBlank { caption },
                                    batchIndex = uris.indexOf(uri) + 1, location = location
                                )
                            }
                        ) -> {
                            draft.clear()
                            selectedUris = emptyList()
                            mediaTitle = ""
                            caption = ""
                            localMessage = null
                            uploadStatus = ActionStatus.SUCCESS
                            batchQueued = true
                            if (success > 0) refreshMedia()
                        }
                        linkedEntryId.isBlank() && !repository.isOnline(context) &&
                            queueBatch(failedUris.toList(), uploaded = success) -> {
                            if (success > 0) refreshMedia()
                        }
                        else -> {
                            selectedUris = failedUris.toList()
                            uploadStatus = ActionStatus.ERROR
                            val msg = if (success == 0) {
                                "All ${countNoun(failedUris.size, "file")} failed. Check your connection and tap Upload to retry."
                            } else {
                                "$success uploaded, ${failedUris.size} failed. Tap Upload to retry."
                            }
                            localMessage = msg
                            if (success > 0) refreshMedia()
                            onError(msg)
                        }
                    }
                }
            },
            enabled = selectedUris.isNotEmpty() && linkedMode != null && !uploading && !recording,
            colors = if (uploadStatus == ActionStatus.IDLE) ButtonDefaults.buttonColors() else {
                val c = if (uploadStatus == ActionStatus.SUCCESS) SuccessGreen else FailureRed
                ButtonDefaults.buttonColors(
                    containerColor = c, contentColor = Color.White,
                    disabledContainerColor = c, disabledContentColor = Color.White
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                mediaUploadButtonLabel(
                    uploading = uploading,
                    succeeded = uploadStatus == ActionStatus.SUCCESS,
                    failed = uploadStatus == ActionStatus.ERROR,
                    queued = batchQueued,
                    hasRecordType = linkedMode != null
                )
            )
        }
        if (savedMedia.isNotEmpty()) {
            Text("Recent saved media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            savedMedia.take(10).forEach { item ->
                AndroidSavedMediaPreview(
                    context = context,
                    media = item,
                    onDelete = {
                        scope.launch {
                            runCatching { repository.deleteMedia(item.id) }
                                .onSuccess {
                                    savedMedia = savedMedia.filterNot { it.id == item.id }
                                    localMessage = "Media removed"
                                }
                                .onFailure { error -> onError(error.apiErrorMessage("Unable to remove media")) }
                        }
                    }
                )
            }
        }
    }
}

private fun queryMediaSize(context: Context, uri: Uri): Long {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)
}

private fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "almost done"
    if (seconds < 60) return "~${seconds}s left"
    return "~${seconds / 60}m ${seconds % 60}s left"
}

private fun mediaTypeFromMime(mime: String?): String = when {
    mime == null -> "DOCUMENT"
    mime.startsWith("image/") -> "IMAGE"
    mime.startsWith("video/") -> "VIDEO"
    mime.startsWith("audio/") -> "AUDIO"
    mime == "application/pdf" -> "PDF"
    else -> "DOCUMENT"
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AndroidUriPreview(
    context: Context,
    uri: Uri,
    onRemove: (() -> Unit)? = null,
    progress: Float? = null,
    failed: Boolean = false,
    onRetry: (() -> Unit)? = null,
    downloadable: Boolean = false,
    onTile: Boolean = false
) {
    val mimeType = remember(uri) { context.contentResolver.getType(uri) }
    val mediaType = remember(mimeType) { mediaTypeFromMime(mimeType) }
    var showViewer by remember { mutableStateOf(false) }
    MediaThumb(
        uri = uri,
        mediaType = mediaType,
        title = uri.lastPathSegment.orEmpty(),
        subtitle = mimeType ?: "Unknown file type",
        onOpen = {
            if (mediaType in IN_APP_PLAYABLE) showViewer = true else openUri(context, uri, mimeType)
        },
        trailing = onRemove?.let { remove ->
            {
                DiscardBadge(
                    contentDescription = "Discard ${uri.lastPathSegment.orEmpty()}",
                    onClick = remove
                )
            }
        }
    )
    when {
        failed -> Text(
            "Upload failed — tap Retry, or Download to keep a copy on this device.",
            color = if (onTile) MaterialTheme.field.errorOnBrandTile else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
        progress != null && progress < 1f -> {
            FieldLinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Coral
            )
            Text("Uploading ${(progress * 100).toInt()}%", color = MaterialTheme.field.onBrandTileMuted, style = MaterialTheme.typography.bodySmall)
        }
        progress != null && progress >= 1f -> Text("Uploaded ✓", color = SuccessGreen, style = MaterialTheme.typography.bodySmall)
    }
    if ((failed && onRetry != null) || downloadable) {
        ActionRow(modifier = Modifier.fillMaxWidth()) {
            if (failed && onRetry != null) {
                MediaActionButton(
                    action = MediaAction.RETRY,
                    onClick = onRetry,
                    modifier = Modifier.semantics { contentDescription = "Retry uploading ${uri.lastPathSegment.orEmpty()}" }
                )
            }
            if (downloadable) {
                MediaDownloadButton(
                    source = uri,
                    mimeType = mimeType,
                    name = uri.lastPathSegment,
                    onTile = if (onTile) MaterialTheme.field.onBrandTile else null
                )
            }
        }
    }
    if (showViewer) {
        MediaViewerDialog(uri = uri, mediaType = mediaType, onDismiss = { showViewer = false })
    }
}

@Composable
private fun DiscardBadge(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp)
    ) {
        Box(
            modifier = Modifier.size(28.dp).background(Color(0xCC1A1A1A), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AndroidSavedMediaPreview(
    context: Context,
    media: com.fieldrepository.app.data.MediaFileDto,
    onDelete: (() -> Unit)? = null
) {
    val onDelete: (() -> Unit)? = onDelete.takeIf { com.fieldrepository.app.ui.LocalMayDelete.current }
    val uri = remember(media.url) { media.url?.let(Uri::parse) }
    var showViewer by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val offlinePreview = com.fieldrepository.app.ui.rememberOfflinePreview(media.id)

    if (uri == null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(media.originalFilename, color = Body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (onDelete != null) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    } else {
        MediaThumb(
            uri = uri,
            mediaType = media.mediaType,
            title = media.originalFilename,
            subtitle = listOfNotNull(media.mimeType ?: media.mediaType, media.transcriptStatus?.let { "Transcript: $it" }).joinToString(" · "),
            onOpen = {
                if (media.mediaType in IN_APP_PLAYABLE) showViewer = true else openUri(context, uri, media.mimeType)
            },
            offlinePreview = offlinePreview.file,
            onPreviewLoaded = offlinePreview.keep,
            trailing = if (onDelete != null) {
                {
                    DiscardBadge(
                        contentDescription = "Remove ${media.originalFilename}",
                        onClick = { confirmDelete = true }
                    )
                }
            } else {
                null
            }
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            MediaActionButton(
                action = MediaAction.DOWNLOAD,
                onClick = { saveMediaToDevice(context, media.url, media.originalFilename, media.mimeType) },
                modifier = Modifier.semantics { contentDescription = "Download ${media.originalFilename}" }
            )
            if (onDelete != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (showViewer) {
            MediaViewerDialog(
                uri = uri,
                mediaType = media.mediaType,
                onSave = { saveMediaToDevice(context, media.url, media.originalFilename, media.mimeType) },
                offlinePreview = offlinePreview.file,
                onDismiss = { showViewer = false },
                expand = com.fieldrepository.app.ui.AudioExpand(media.id, media.originalFilename, sizeBytes = media.byteSize, onSave = { saveMediaToDevice(context, media.url, media.originalFilename, media.mimeType) }).takeIf { media.mediaType.equals("AUDIO", ignoreCase = true) }
            )
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            title = { Text("Remove this media?") },
            text = { Text("\"${media.originalFilename}\" will be permanently deleted from this record and from storage.") }
        )
    }
}

private val IN_APP_PLAYABLE = setOf("IMAGE", "VIDEO", "AUDIO")

internal fun artisanSelectOptions(artisans: List<ArtisanDto>): List<SelectOption> =
    artisans.map { artisan ->
        SelectOption(
            value = artisan.id,
            label = "${artisan.name} \u00b7 ${artisan.place}",
            hint = artisan.craft?.name ?: "No craft"
        )
    }

private data class QuestionnaireArtisanOptions(
    val options: List<ArtisanDto>,
    val cut: String?,
    val loading: Boolean,
    val failed: Boolean,
    val offeredIds: List<String>,
    val loadedForWorkshop: String?,
    val search: ServerSearchState<ArtisanDto>
)

@Composable
private fun rememberQuestionnaireArtisanOptions(
    repository: FieldRepository,
    fallback: List<ArtisanDto>,
    hydrated: List<ArtisanDto>,
    workshopId: String,
    workshopSettled: Boolean,
    selectedIds: Set<String>
): QuestionnaireArtisanOptions {
    val roster = rememberServerSearch(
        key = workshopId,
        cached = emptyList<ArtisanDto>(),
        seed = null,
        idOf = { it.id },
        textOf = ::artisanSearchText,
        enabled = workshopSettled
    ) { term, page -> repository.artisansSearchPage(term, page, listOfNotNull(workshopId.ifBlank { null })) }
    val answered = workshopSettled && roster.browseLoaded && roster.browseKey == workshopId
    val failed = workshopSettled && !roster.browseLoaded && roster.note != null && !roster.loading
    val base = if (answered) roster.browse else emptyList()
    val pool = mergeArtisansById(mergeArtisansById(fallback, hydrated), roster.seen)
    val keep = selectedIds.mapNotNull { id -> pool.firstOrNull { it.id == id } }
    val options = remember(base, keep) { mergeArtisansById(base, keep) }
    return QuestionnaireArtisanOptions(
        options = options,
        cut = if (answered && roster.browse.size < roster.browseTotal) "partial" else null,
        loading = workshopSettled && !answered && !failed,
        failed = failed,
        offeredIds = base.map { it.id },
        loadedForWorkshop = if (answered) workshopId else null,
        search = roster
    )
}

@Composable
private fun QuestionnaireForm(
    repository: FieldRepository,
    sections: List<QuestionnaireSectionDto>,
    artisans: List<ArtisanDto>,
    lookupState: CarryScopeState = CarryScopeState.PENDING,
    canManageQuestionnaire: Boolean,
    prefill: Prefill? = null,
    editing: QuestionnaireInterviewDetailDto? = null,
    adminView: Boolean = false,
    onRefreshSections: suspend (String?) -> Unit,
    onSync: suspend (String?) -> Unit = onRefreshSections,
    onSubmit: suspend (QuestionnaireInterviewCreateRequest, String?) -> String,
    onError: (String) -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = rememberFormDraft(repository, "questionnaire", editing?.id, prefill.draftParentId(), prefill.draftParentLabel())
    val isEdit = editing != null
    var syncing by remember { mutableStateOf(false) }
    var saveState by remember { mutableStateOf(SaveState.IDLE) }
    var title by remember(editing) { mutableStateOf(editing?.title ?: "") }.drafted(draft, "title")
    var selectedArtisans by remember(editing) {
        mutableStateOf(
            editing?.artisans?.map { it.artisanId }?.toSet()
                ?: prefill?.artisanId?.let { setOf(it) }
                ?: emptySet()
        )
    }.drafted(draft, "selectedArtisans")
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }.drafted(draft, "place")
    var language by remember(editing) { mutableStateOf(editing?.language.orEmpty()) }.drafted(draft, "language")
    var notes by remember(editing) { mutableStateOf(editing?.notes ?: "") }.drafted(draft, "notes")
    var capturedLocation by remember(editing) { mutableStateOf(editing?.location?.toRequest()) }.drafted(draft, "capturedLocation")
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing, runningOnly = true)
    var instruments by remember { mutableStateOf<List<QuestionnaireDto>>(emptyList()) }
    var questionnaireId by remember(editing) { mutableStateOf(editing?.questionnaireId) }.drafted(draft, "questionnaireId")
    var instrumentTouched by remember(editing) { mutableStateOf(false) }.drafted(draft, "instrumentTouched")
    var instrumentsLoaded by remember { mutableStateOf(false) }
    var instrumentsFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { repository.questionnaires() }
            .onSuccess { instruments = it; instrumentsLoaded = true; instrumentsFailed = false }
            .onFailure { instrumentsFailed = true }
    }
    LaunchedEffect(sections, instruments) {
        if (instrumentTouched || questionnaireId != null) return@LaunchedEffect
        questionnaireId = sections.firstOrNull()?.questionnaireId
            ?: instruments.firstOrNull { it.isDefault }?.id
            ?: instruments.firstOrNull()?.id
    }
    val boundInstrumentId = workshop.workshops.firstOrNull { it.id == workshop.selectedId }?.questionnaireId
    LaunchedEffect(boundInstrumentId, questionnaireId, isEdit) {
        if (isEdit || instrumentTouched) return@LaunchedEffect
        val bound = boundInstrumentId ?: return@LaunchedEffect
        if (bound == questionnaireId) return@LaunchedEffect
        questionnaireId = bound
        runCatching { onRefreshSections(bound) }
            .onFailure { onError(it.apiErrorMessage("Couldn't load this workshop's questionnaire")) }
    }
    val instrumentOptions = remember(instruments, editing, questionnaireId, boundInstrumentId) {
        val rows = instruments.toMutableList()
        val chosen = questionnaireId
        if (chosen != null && rows.none { it.id == chosen }) {
            editing?.questionnaire?.takeIf { it.id == chosen }?.let { rows.add(0, it) }
        }
        rows.map { instrument ->
            instrument.id to instrument.title + if (instrument.id == boundInstrumentId) " (this workshop)" else ""
        }
    }
    val artisanOptions = rememberQuestionnaireArtisanOptions(
        repository = repository,
        fallback = artisans,
        hydrated = editing?.artisans?.mapNotNull { it.artisan }.orEmpty(),
        workshopId = workshop.selectedId,
        workshopSettled = workshop.settled,
        selectedIds = selectedArtisans
    )
    val outOfWorkshopArtisans = artisansNotAtWorkshop(
        selectedIds = selectedArtisans.toList(),
        offeredIds = artisanOptions.offeredIds,
        loadedForWorkshop = artisanOptions.loadedForWorkshop,
        workshopId = workshop.selectedId,
        cut = artisanOptions.cut
    )
    val outOfWorkshopMessage = outOfWorkshopNotice(
        outOfWorkshopArtisans.mapNotNull { id -> artisanOptions.options.firstOrNull { it.id == id }?.name }
    )
    val artisanPickerOptions = remember(artisanOptions.options) { artisanSelectOptions(artisanOptions.options) }
    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.QUESTIONNAIRE_FORM,
        scopes = listOf(carryScope(CarryNode.ARTISAN, lookupState, artisans) { it.id }),
        handoff = prefill
    ) { carried ->
        carried.artisanId?.let { selectedArtisans = setOf(it) }
        carried.workshopId?.let { if (!workshop.isDirty()) workshop.applyDefault(it) }
    }
    fun clearCarriedContext() {
        carry.change()
        selectedArtisans = emptySet()
    }
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }.drafted(draft, "status")
    var titleError by remember { mutableStateOf<String?>(null) }
    val titleFocus = remember { FocusRequester() }
    val questions = remember(sections) { sections.flatMap { it.questions }.filter { it.isActive } }
    val acceptedMachine = remember(editing) { mutableStateListOf<String>() }
    val answers = remember(questions, editing) {
        questions.associate { q ->
            q.id to mutableStateOf(editing?.responses?.firstOrNull { it.questionId == q.id }?.answerText ?: "")
        }
    }
    val media = rememberMediaCaptureState()
    var artisanFilled by remember(editing?.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var artisanFilledFrom by remember(editing?.id) { mutableStateOf<String?>(null) }
    var transcriptFilled by remember(editing?.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var autoPlace by remember(editing?.id) { mutableStateOf<String?>(null) }
    val headArtisanId = selectedArtisans.firstOrNull()
    LaunchedEffect(headArtisanId, questions) {
        if (isEdit) return@LaunchedEffect
        artisanFilled.forEach { (qid, value) -> answers[qid]?.let { if (it.value == value) it.value = "" } }
        val previousPlace = autoPlace
        if (headArtisanId == null) {
            artisanFilled = emptyMap(); artisanFilledFrom = null
            if (previousPlace != null && place == previousPlace) place = ""
            autoPlace = null
            return@LaunchedEffect
        }
        val listed = artisanOptions.options.firstOrNull { it.id == headArtisanId }
        val detail = runCatching { repository.artisan(headArtisanId) }.getOrNull()
            ?: listed?.let { artisanDetailFromListed(it) }
        val theirPlace = (detail?.place ?: listed?.place).orEmpty().trim()
        if (theirPlace.isNotEmpty() && (place.isBlank() || place == previousPlace)) {
            place = theirPlace
            autoPlace = theirPlace
        }
        if (detail == null) {
            artisanFilled = emptyMap(); artisanFilledFrom = null
            return@LaunchedEffect
        }
        val me = repository.cachedUser()
        val fills = artisanPrefill(questions, detail, me?.name?.takeIf { it.isNotBlank() } ?: me?.email)
            .filterKeys { answers[it]?.value.isNullOrBlank() }
        fills.forEach { (qid, value) -> answers[qid]?.value = value }
        artisanFilled = fills
        artisanFilledFrom = detail.name.takeIf { fills.isNotEmpty() }
    }
    val qMedia = rememberMediaCaptureState()
    MediaStagingEffect(repository = repository, media = qMedia)
    var savedMedia by remember(editing?.id) { mutableStateOf<List<MediaFileDto>>(emptyList()) }
    LaunchedEffect(editing?.id) {
        val ed = editing ?: return@LaunchedEffect
        runCatching {
            val groupIds = interviewGroup(repository, ed).map { it.id }
            val byId = LinkedHashMap<String, MediaFileDto>()
            groupIds.forEach { gid ->
                runCatching { repository.mediaForRecord("questionnaire", gid) }.getOrDefault(emptyList())
                    .forEach { byId[it.id] = it }
            }
            byId.values.toList()
        }.onSuccess { savedMedia = it }
    }
    var lastEditedSectionId by remember { mutableStateOf<String?>(null) }
    val clipTranscripts = remember(savedMedia, questions) { questionTranscripts(savedMedia, questions) }
    fun effectiveTitle(): String = title.trim().ifBlank {
        automaticInterviewTitle(
            artisanName = artisanOptions.options.firstOrNull { it.id == selectedArtisans.firstOrNull() }?.name
                ?: editing?.artisans?.firstOrNull()?.artisan?.name,
            artisanCount = selectedArtisans.size,
            questionnaireTitle = instruments.firstOrNull { it.id == questionnaireId }?.title ?: editing?.questionnaire?.title
        )
    }
    var questionAudio by remember { mutableStateOf<Map<String, List<Uri>>>(emptyMap()) }
    var createdId by remember(editing?.id) { mutableStateOf<String?>(null) }.drafted(draft, "createdId")
    fun sectionIdForKey(key: String): String? =
        if (key.startsWith("section:")) key.removePrefix("section:")
        else sections.firstOrNull { sec -> sec.questions.any { it.id == key } }?.id
    fun dropStaged(uri: Uri) {
        val deferred = qMedia.stagedDeferred[uri]
        qMedia.forget(uri)
        qMedia.uris = qMedia.uris.filterNot { it == uri }
        AppScope.io.launch { runCatching { deferred?.await()?.let { repository.deleteStaged(it.objectKey) } } }
    }
    fun addClip(key: String, uri: Uri) {
        questionAudio = questionAudio + (key to ((questionAudio[key] ?: emptyList()) + uri))
        qMedia.uris = qMedia.uris + uri
        lastEditedSectionId = sectionIdForKey(key)
    }
    fun removeLastClip(key: String) {
        val list = questionAudio[key] ?: return
        val removed = list.lastOrNull()
        questionAudio = questionAudio + (key to list.dropLast(1))
        if (removed != null) dropStaged(removed)
        lastEditedSectionId = sectionIdForKey(key)
    }
    fun removeClipUri(uri: Uri) {
        val entry = questionAudio.entries.firstOrNull { uri in it.value } ?: return
        questionAudio = questionAudio + (entry.key to entry.value.filterNot { it == uri })
        dropStaged(uri)
        lastEditedSectionId = sectionIdForKey(entry.key)
    }
    fun captionBelongsToSection(caption: String?, section: QuestionnaireSectionDto): Boolean {
        val cap = caption?.trim().orEmpty()
        if (cap.isEmpty()) return false
        val expected = buildSet {
            add("Section audio: ${section.code} ${section.title}".trim())
            section.questions.forEach { q -> add("Question audio: ${q.sectionCode}${q.sortOrder} ${q.prompt}".trim()) }
        }
        if (cap in expected) return true
        if (cap.startsWith("Section audio:")) {
            val rest = cap.removePrefix("Section audio:").trim()
            return rest == section.code || rest.startsWith("${section.code} ")
        }
        if (cap.startsWith("Question audio:")) {
            val rest = cap.removePrefix("Question audio:").trim()
            return rest.startsWith(section.code) && rest.length > section.code.length && rest[section.code.length].isDigit()
        }
        return false
    }
    var recordMode by remember { mutableStateOf("SECTION") }
    var expandedSections by remember { mutableStateOf<Set<String>>(emptySet()) }
    var revealedSections by remember(editing?.id) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(editing?.id, sections, savedMedia) {
        if (!isEdit) return@LaunchedEffect
        val sectionsWithSavedMedia = sections
            .filter { section -> savedMedia.any { captionBelongsToSection(it.caption, section) } }
            .map { it.id }
            .toSet()
        val reveal = sectionsToRevealOnEdit(
            sections = sections,
            responses = editing?.responses.orEmpty(),
            sectionsWithSavedMedia = sectionsWithSavedMedia,
            alreadyRevealed = revealedSections
        )
        if (reveal.isNotEmpty()) {
            expandedSections = expandedSections + reveal
            revealedSections = revealedSections + reveal
        }
    }
    var showBuilder by remember { mutableStateOf(false) }

    if (canManageQuestionnaire && !isEdit) {
        OutlinedButton(onClick = { showBuilder = !showBuilder }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showBuilder) "Hide questionnaire builder" else "Open questionnaire builder")
        }
        if (showBuilder) {
            QuestionnaireBuilder(
                repository, sections, { onRefreshSections(questionnaireId) }, onError, questionnaireId,
                canEdit = com.fieldrepository.app.ui.mayEditQuestionnaire(repository.cachedUser(), instruments.firstOrNull { it.id == questionnaireId }?.createdById)
            )
        }
    }
    var syncStatus by remember { mutableStateOf(ActionStatus.IDLE) }
    AutoResetStatus(syncStatus) { syncStatus = ActionStatus.IDLE }
    val syncContainer = when (syncStatus) {
        ActionStatus.SUCCESS -> SuccessGreen
        ActionStatus.ERROR -> FailureRed
        ActionStatus.IDLE -> MaterialTheme.colorScheme.primary
    }
    Button(
        onClick = {
            if (syncing) return@Button
            scope.launch {
                syncing = true
                runCatching { onSync(questionnaireId) }
                    .onSuccess { syncStatus = ActionStatus.SUCCESS }
                    .onFailure {
                        if (it !is kotlinx.coroutines.CancellationException) {
                            syncStatus = ActionStatus.ERROR
                            onError(it.apiErrorMessage("Unable to synchronize"))
                        }
                    }
                syncing = false
            }
        },
        enabled = !syncing,
        colors = ButtonDefaults.buttonColors(containerColor = syncContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                syncing -> "Synchronizing…"
                syncStatus == ActionStatus.SUCCESS -> "Synchronised ✓"
                syncStatus == ActionStatus.ERROR -> "Sync failed — tap to retry"
                else -> "Sync now"
            }
        )
    }

    val questionnaireIsAdmin = remember { repository.cachedUser()?.role in setOf("ADMIN", "MASTER_ADMIN") }
    var showCompletion by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showCompletion = !showCompletion }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (showCompletion) "Hide completion overview" else "Check completion")
    }
    if (showCompletion) {
        CompletionMatrixCard(
            repository = repository,
            artisanId = null,
            canEdit = questionnaireIsAdmin && adminView,
            onError = onError
        )
    }

    val interviewRecordings = media.uris.size + qMedia.uris.size + questionAudio.values.sumOf { it.size }
    RecordCard(title = if (isEdit) recordUpdateTitle("interview") else "Add questionnaire interview") {
        DraftOfferCard(draft)
        FormCompletenessLine(
            kind = CompletenessKind.INTERVIEW,
            values = mapOf(
                "interviewDate" to (if (isEdit) editing?.interviewDate else "on-save"),
                "place" to place,
                "language" to language
            ),
            stored = editing?.completeness,
            add = mapOf(
                "artisans" to selectedArtisans.size,
                "answers" to answers.values.count { it.value.isNotBlank() },
                "recordings" to interviewRecordings
            )
        )
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        if (isEdit) {
            Text("Others' answers stay unless you change them.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        TextInput("Interview title", title, titleCased = true) { title = it }
        if (title.isBlank()) {
            Text("Leave empty to save as “${effectiveTitle()}”.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        CompletenessAnchor("place") {
            TextInput("Place", place, titleCased = true) { place = it }
        }
        if (autoPlace != null && place == autoPlace) {
            Text("From the artisan's record. Change it to override.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        CompletenessAnchor("language") {
            DropdownField(label = "Language", options = StandardValues.languageOptions(language), selectedValue = language, placeholder = "Select language", includeNone = false) { language = it }
        }
        WorkshopField(state = workshop, saving = saveState == SaveState.SAVING)
        DropdownField(
            label = "Questionnaire",
            options = instrumentOptions,
            selectedValue = questionnaireId.orEmpty(),
            placeholder = when {
                instrumentsFailed -> "Questionnaire list unavailable"
                !instrumentsLoaded -> "Loading questionnaires…"
                instrumentOptions.isEmpty() -> "No questionnaires published yet"
                else -> "Default questionnaire"
            },
            includeNone = false,
            enabled = !isEdit && saveState != SaveState.SAVING && instrumentOptions.isNotEmpty()
        ) { picked ->
            instrumentTouched = true
            questionnaireId = picked
            scope.launch {
                runCatching { onRefreshSections(picked) }
                    .onFailure { onError(it.apiErrorMessage("Couldn't load that questionnaire's sections")) }
            }
        }
        if (isEdit) {
            Text(
                "An interview stays on the questionnaire it was first saved against.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (instrumentsFailed) {
            Text(
                "Couldn't load questionnaires. The workshop's default will be used.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        CompletenessAnchor("artisans") {
        SearchableMultiSelectField(
            label = "Artisans interviewed",
            options = artisanPickerOptions,
            selected = selectedArtisans,
            placeholder = "Select the artisans this interview is with",
            server = artisanOptions.search.asServerSearchRows(countsRows = true) { artisanSelectOptions(it) },
            emptyMessage = when {
                artisanOptions.failed -> "This workshop's artisan list could not be loaded"
                artisanOptions.loading -> "Loading artisans…"
                workshop.selectedId.isNotBlank() -> "No artisans are recorded at this workshop yet"
                else -> "No artisans recorded yet"
            }
        ) { next ->
            val added = next - selectedArtisans
            selectedArtisans = next
            added.firstOrNull()?.let { id ->
                (artisanOptions.options.firstOrNull { it.id == id } ?: artisanOptions.search.seen.firstOrNull { it.id == id })?.let {
                    carry.remember(
                        CarryContext(
                            artisanId = it.id,
                            artisanName = it.name,
                            place = it.place,
                            craftId = it.craftId,
                            craftName = it.craft?.name
                        ),
                        explicit = true
                    )
                }
            }
        }
        }
        if (artisanOptions.failed) {
            Text(
                "Couldn't load artisans. Pick the workshop again to retry.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        outOfWorkshopMessage?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        DropdownField(
            label = "Recording mode",
            options = listOf(
                "INDIVIDUAL" to "Record individual questions",
                "SECTION" to "Record the entire section at once"
            ),
            selectedValue = recordMode,
            includeNone = false
        ) { recordMode = it }
        var hideAnswers by remember { mutableStateOf(true) }
        AppToggle(
            label = "Show answer text boxes",
            description = "Turn on to type answers.",
            checked = !hideAnswers,
            onCheckedChange = { hideAnswers = !it }
        )
        LocationAddressEditor(
            repository = repository,
            value = capturedLocation,
            onUseGps = { readLastKnownLocation(context) },
            onChange = { capturedLocation = it },
            onMessage = onError,
            inherit = artisans.firstOrNull { it.id in selectedArtisans }.inheritedPlace(isEdit)
        )
        CompletenessAnchor("answers") {
            Text("Tap a section to answer it.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        val standingFills = artisanFilled.filter { (qid, value) -> answers[qid]?.value == value }
        if (standingFills.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "${plural(standingFills.size, "answer")} filled from ${artisanFilledFrom ?: "the artisan"}'s record. Change any to override.",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall
                )
                ActionRow {
                    OutlinedButton(onClick = {
                        standingFills.forEach { (qid, _) -> answers[qid]?.value = "" }
                        artisanFilled = emptyMap()
                    }) { Text("Undo") }
                }
            }
        }
        val transcriptOffers = questions.mapNotNull { q -> transcriptOffer(answers[q.id]?.value, clipTranscripts[q.id])?.let { q.id to it } }
        if (transcriptOffers.size > 1) {
            ActionRow {
                OutlinedButton(onClick = {
                    transcriptOffers.forEach { (qid, text) -> answers[qid]?.value = text }
                    transcriptFilled = transcriptFilled + transcriptOffers
                    onError("${plural(transcriptOffers.size, "answer")} taken from the recordings. Check them before saving.")
                }) { Text("Use ${transcriptOffers.size} transcripts as answers") }
            }
        }
        sections.forEach { section ->
            val activeQuestions = section.questions.filter { it.isActive }
            if (activeQuestions.isNotEmpty()) {
                val expanded = expandedSections.contains(section.id)
                val sectionSavedMedia = if (isEdit) savedMedia.filter { captionBelongsToSection(it.caption, section) } else emptyList()
                val sectionRecorded = (questionAudio["section:${section.id}"]?.isNotEmpty() == true) ||
                    sectionSavedMedia.any { it.caption?.trim()?.startsWith("Section audio:") == true }
                val answeredCount = if (sectionRecorded) activeQuestions.size else activeQuestions.count { q ->
                    val hasText = (answers[q.id]?.value?.trim().orEmpty()).isNotEmpty()
                    val hasLiveClip = questionAudio[q.id]?.isNotEmpty() == true
                    val hasSavedClip = sectionSavedMedia.any { m ->
                        val cap = m.caption?.trim().orEmpty()
                        cap.startsWith("Question audio:") && run {
                            val rest = cap.removePrefix("Question audio:").trim()
                            rest == "${q.sectionCode}${q.sortOrder}" || rest.startsWith("${q.sectionCode}${q.sortOrder} ")
                        }
                    }
                    hasText || hasLiveClip || hasSavedClip
                }
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedSections = if (expanded) expandedSections - section.id else expandedSections + section.id
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${section.code}. ${section.title}", display = true, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "${activeQuestions.size} questions · $answeredCount answered" +
                                        (if (sectionSavedMedia.isNotEmpty()) " · ${countNoun(sectionSavedMedia.size, "saved recording")}" else ""),
                                    color = Muted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(if (expanded) "Hide ▲" else "Open ▼", color = Body, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        if (expanded) {
                            if (recordMode == "SECTION") {
                                val sectionKey = "section:${section.id}"
                                Text("Record this entire section in one take.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                AudioClipRecorder(
                                    clips = questionAudio[sectionKey] ?: emptyList(),
                                    onAddClip = { uri -> addClip(sectionKey, uri) },
                                    onRemoveLast = { removeLastClip(sectionKey) },
                                    onError = onError,
                                    idleLabel = "Record section ●"
                                )
                                HorizontalDivider()
                            }
                            editing?.let { interview ->
                                AnswerDraftsCard(
                                    interviewId = interview.id,
                                    sectionId = section.id,
                                    questions = activeQuestions.map { it.id to "${it.sortOrder}. ${it.prompt}" },
                                ) { kept ->
                                    kept.forEach { (id, text) -> answers[id]?.let { state -> state.value = text } }
                                    acceptedMachine.addAll(kept.keys)
                                }
                            }
                            activeQuestions.forEach { question ->
                                Text("${question.sortOrder}. ${question.prompt}", color = Muted, style = MaterialTheme.typography.bodySmall)
                                val origin = editing?.responses?.firstOrNull { it.questionId == question.id }?.answerOrigin
                                    ?: if (question.id in acceptedMachine) AnswerDrafts.ORIGIN_MACHINE else null
                                AnswerDrafts.badge(origin)?.let { badge ->
                                    Text(badge, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                }
                                if (recordMode == "INDIVIDUAL") {
                                    AudioClipRecorder(
                                        clips = questionAudio[question.id] ?: emptyList(),
                                        onAddClip = { uri -> addClip(question.id, uri) },
                                        onRemoveLast = { removeLastClip(question.id) },
                                        onError = onError
                                    )
                                }
                                val recordedAnswer = editing?.responses
                                    ?.firstOrNull { it.questionId == question.id }?.answerText
                                val currentAnswer = answers[question.id]?.value.orEmpty()
                                val fromTranscript = transcriptFilled[question.id]?.let { it == currentAnswer } == true
                                val fromArtisan = artisanFilled[question.id]?.let { it == currentAnswer } == true
                                if (answerBoxVisible(hideAnswers, recordedAnswer) || fromTranscript || fromArtisan) {
                                    TextInput("Answer", currentAnswer, minLines = 3) { value ->
                                        answers[question.id]?.let { state -> state.value = value }
                                        lastEditedSectionId = section.id
                                    }
                                    when {
                                        fromTranscript -> Text("From the recording.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                        fromArtisan -> Text("From the artisan's record.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                transcriptOffer(currentAnswer, clipTranscripts[question.id])?.let { offered ->
                                    ActionRow {
                                        OutlinedButton(onClick = {
                                            answers[question.id]?.value = offered
                                            transcriptFilled = transcriptFilled + (question.id to offered)
                                            lastEditedSectionId = section.id
                                        }) { Text("Use as answer") }
                                    }
                                }
                            }
                            val sectionUploadUris = questionAudio.entries
                                .filter { sectionIdForKey(it.key) == section.id }
                                .flatMap { it.value }
                            if (sectionUploadUris.isNotEmpty()) {
                                HorizontalDivider()
                                Text("This section's recordings (uploading)", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                AttachedUploadsCard(
                                    context = context,
                                    media = qMedia,
                                    label = "recording",
                                    repository = repository,
                                    uris = sectionUploadUris
                                ) { uri -> removeClipUri(uri) }
                            }
                            if (isEdit && sectionSavedMedia.isNotEmpty()) {
                                HorizontalDivider()
                                Text("Saved recordings & media for this section", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                sectionSavedMedia.forEach { MediaWithTranscript(context, it, repository) }
                            }
                        }
                    }
                }
                if (lastEditedSectionId == section.id) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp)
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                        Text("Most recent changes by you were made over here", color = SuccessGreen, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (qMedia.uris.isNotEmpty()) {
            HorizontalDivider()
            Text("All recordings (every section)", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text("Every clip in this interview.", color = Muted, style = MaterialTheme.typography.bodySmall)
            AttachedUploadsCard(context = context, media = qMedia, label = "recording", repository = repository) { uri -> removeClipUri(uri) }
        }
        if (isEdit) {
            val otherSavedMedia = savedMedia.filterNot { m -> sections.any { captionBelongsToSection(m.caption, it) } }
            if (otherSavedMedia.isNotEmpty()) {
                HorizontalDivider()
                Text("Other saved recordings & media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                otherSavedMedia.forEach { MediaWithTranscript(context, it, repository) }
            }
        }
        CompletenessAnchor("recording") {
            MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
        }
        MultiNoteInput(label = "Interview notes", value = notes) { notes = it }
        var mergeOffer by remember(editing?.id) { mutableStateOf<InterviewSetHolder?>(null) }
        var mergeUnsaved by remember(editing?.id) { mutableStateOf<String?>(null) }
        var mergeConflicts by remember(editing?.id) { mutableStateOf<List<MergeAnswerConflict>>(emptyList()) }
        var mergeConflictMessage by remember(editing?.id) { mutableStateOf<String?>(null) }
        var merging by remember(editing?.id) { mutableStateOf(false) }
        fun dismissMergeOffer() {
            if (merging) return
            mergeOffer = null
            mergeUnsaved = null
            mergeConflicts = emptyList()
            mergeConflictMessage = null
        }
        fun confirmMerge(holder: InterviewSetHolder) {
            val original = editing ?: return
            scope.launch {
                merging = true
                runCatching { repository.mergeQuestionnaireInterviewInto(original.id, holder.id) }
                    .onSuccess {
                        merging = false
                        mergeOffer = null
                        draft.clear(); onSaved()
                    }
                    .onFailure { error ->
                        merging = false
                        val failure = error.apiFailure("Unable to move this interview")
                        val conflicts = failure.mergeAnswerConflicts()
                        if (conflicts.isEmpty()) {
                            dismissMergeOffer()
                            onError(failure.message)
                        } else {
                            mergeConflicts = conflicts
                            mergeConflictMessage = failure.message
                        }
                    }
            }
        }
        mergeOffer?.let { holder ->
            val holderName = holder.title?.trim().orEmpty()
            AlertDialog(
                onDismissRequest = { dismissMergeOffer() },
                title = {
                    Text(
                        if (mergeConflicts.isEmpty()) "This artisan set already has an interview"
                        else "Nothing was moved — these answers disagree"
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (mergeConflicts.isEmpty()) {
                            Text(mergeOfferQuestion(holder.title), color = Body, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Answers and recordings move there; this one is removed. Nothing is overwritten.",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                            mergeUnsaved?.let {
                                Text(it, color = MaterialTheme.field.onWarningContainer, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            mergeConflictMessage?.let { Text(it, color = Body, style = MaterialTheme.typography.bodyMedium) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "Not moved — reconcile these first",
                                    color = MaterialTheme.field.onWarningContainer,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                mergeConflictLines(mergeConflicts).forEach { line ->
                                    Text(
                                        "• $line",
                                        color = MaterialTheme.field.onWarningContainer,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            Text(
                                "Reconcile the answers above, then try again.",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                confirmButton = {
                    if (mergeConflicts.isEmpty()) {
                        Button(onClick = { confirmMerge(holder) }, enabled = !merging) {
                            Text(
                                when {
                                    merging -> "Moving…"
                                    holderName.isEmpty() -> "Move into the other interview"
                                    else -> "Move into “$holderName”"
                                }
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dismissMergeOffer() }, enabled = !merging) {
                        Text(if (mergeConflicts.isEmpty()) "Keep both, cancel" else "Close")
                    }
                }
            )
        }
        fun submit() {
                newRecordLocationError(isEdit, capturedLocation)?.let { onError(it); return }
                scope.launch {
                    if (!workshop.confirmSubmission()) return@launch
                    saveState = SaveState.SAVING
                    val now = Instant.now().toString()
                    val responsesToSend = questions.mapNotNull { question ->
                        val current = answers[question.id]?.value?.trim().orEmpty()
                        val initial = editing?.responses?.firstOrNull { it.questionId == question.id }?.answerText?.trim().orEmpty()
                        if (current.isNotBlank() && current != initial) {
                            QuestionnaireResponseRequest(
                                questionId = question.id,
                                answerText = current,
                                answerSource = if (transcriptFilled[question.id]?.trim() == current) TRANSCRIPT_SOURCE else null
                            )
                        } else null
                    }
                    fun questionnaireOfflineItems(): List<com.fieldrepository.app.data.OfflineMediaSpec> {
                        val questionsById = questions.associateBy { it.id }
                        val sectionsById = sections.associateBy { it.id }
                        val items = mutableListOf<com.fieldrepository.app.data.OfflineMediaSpec>()
                        questionAudio.forEach { (key, uris) ->
                            val caption: String
                            val hint: String
                            val sectionCodePart: String?
                            val questionNumberPart: String?
                            if (key.startsWith("section:")) {
                                val section = sectionsById[key.removePrefix("section:")]
                                caption = "Section audio: ${section?.code ?: ""} ${section?.title ?: ""}".trim()
                                hint = title.ifBlank { section?.title ?: "Section recording" }
                                sectionCodePart = section?.code
                                questionNumberPart = "SEC"
                            } else {
                                val question = questionsById[key]
                                caption = "Question audio: ${question?.sectionCode ?: ""}${question?.sortOrder ?: ""} ${question?.prompt ?: ""}".trim()
                                hint = title.ifBlank { question?.prompt ?: "Question recording" }
                                sectionCodePart = question?.sectionCode
                                questionNumberPart = question?.sortOrder?.toString()
                            }
                            uris.forEachIndexed { index, uri ->
                                val baseName = questionnaireClipBaseName(context, sectionCodePart, questionNumberPart, title, uri)
                                    .let { if (uris.size > 1) "${it}_${index + 1}" else it }
                                items.add(com.fieldrepository.app.data.OfflineMediaSpec(
                                    uri = uri, caption = caption, recordName = hint,
                                    overrideBaseName = baseName, batchIndex = index + 1,
                                    processing = listOf(UploadTranscribe.TRANSCRIPTION)))
                            }
                        }
                        media.uris.forEachIndexed { i, uri ->
                            items.add(com.fieldrepository.app.data.OfflineMediaSpec(
                                uri = uri,
                                caption = "Field media for ${title.trim().ifBlank { "interview" }}",
                                recordName = title.ifBlank { "Interview" },
                                batchIndex = i + 1))
                        }
                        return items
                    }
                    fun interviewUpdateRequest(): QuestionnaireInterviewUpdateRequest {
                        val original = editing!!
                        val originalArtisans = original.artisans.map { it.artisanId }.toSet()
                        return QuestionnaireInterviewUpdateRequest(
                            title = effectiveTitle(),
                            place = place.blankToNull(),
                            language = language.blankToNull(),
                            notes = notes.blankToNull(),
                            status = status,
                            artisanIds = if (selectedArtisans != originalArtisans) selectedArtisans.toList() else null,
                            responses = responsesToSend.ifEmpty { null },
                            workshopId = workshop.value(),
                            location = locationForBody(true, capturedLocation, original.location)
                        )
                    }
                    fun updateRequestFor(): QuestionnaireInterviewUpdateRequest =
                        if (editing != null) interviewUpdateRequest() else QuestionnaireInterviewUpdateRequest(
                            title = effectiveTitle(),
                            place = place.blankToNull(),
                            language = language.blankToNull(),
                            notes = notes.blankToNull(),
                            status = status,
                            artisanIds = selectedArtisans.toList(),
                            responses = responsesToSend.ifEmpty { null },
                            workshopId = workshop.value(),
                            location = capturedLocation
                        )
                    val saveTarget = com.fieldrepository.app.data.LegacyQuestionnaireSave.target(editing?.id, createdId)
                    val updateId = (saveTarget as? com.fieldrepository.app.data.LegacyQuestionnaireSave.Target.Update)?.interviewId
                    if (!repository.isOnline(context)) {
                        val ok = runCatching {
                            if (updateId != null) {
                                repository.queueSequence(
                                    context, "questionnaire", updateId, effectiveTitle(),
                                    listOf(OutboxStep("questionnaire.update", updateId, offlineFormJson.encodeToString(updateRequestFor()))),
                                    questionnaireOfflineItems()
                                )
                                return@runCatching
                            }
                            val request = QuestionnaireInterviewCreateRequest(
                                title = effectiveTitle(),
                                place = place.blankToNull(),
                                language = language.blankToNull(),
                                notes = notes.blankToNull(),
                                status = status,
                                artisanIds = selectedArtisans.toList(),
                                workshopId = workshop.value(),
                                questionnaireId = questionnaireId?.blankToNull(),
                                location = capturedLocation,
                                responses = responsesToSend,
                                recordedAt = now
                            )
                            val items = questionnaireOfflineItems()
                            repository.queueOfflineEntry(context, "questionnaire", offlineFormJson.encodeToString(request),
                                effectiveTitle(), items)
                        }.isSuccess
                        if (ok && updateId != null) {
                            media.reset(); qMedia.reset(); questionAudio = emptyMap(); createdId = null
                            draft.clear()
                            saveState = SaveState.SAVED
                            onError("Saved offline. Uploads when you're online.")
                            delay(SAVED_CONFIRM_MS)
                            onSaved()
                            return@launch
                        }
                        if (ok) {
                            media.reset(); qMedia.reset(); questionAudio = emptyMap()
                            title = ""; selectedArtisans = emptySet(); place = ""; language = ""; notes = ""
                            status = defaultCreateStatus(repository.cachedUser()?.role)
                            capturedLocation = null; answers.values.forEach { it.value = "" }
                            workshop.markSaved()
                            saveState = SaveState.SAVED
                            delay(SAVED_CONFIRM_MS)
                            draft.clear(); onSaved()
                        } else {
                            saveState = SaveState.IDLE
                            onError("Couldn't save offline")
                        }
                        return@launch
                    }
                    runCatching {
                        val interviewId = if (isEdit) {
                            val original = editing!!
                            val originalArtisans = original.artisans.map { it.artisanId }.toSet()
                            repository.updateQuestionnaireInterview(
                                original.id,
                                interviewUpdateRequest()
                            )
                            original.id
                        } else if (updateId != null) {
                            repository.updateQuestionnaireInterview(updateId, updateRequestFor())
                            updateId
                        } else {
                            val request = QuestionnaireInterviewCreateRequest(
                                    title = effectiveTitle(),
                                    place = place.blankToNull(),
                                    language = language.blankToNull(),
                                    notes = notes.blankToNull(),
                                    status = status,
                                    artisanIds = selectedArtisans.toList(),
                                    workshopId = workshop.value(),
                                    questionnaireId = questionnaireId?.blankToNull(),
                                    location = capturedLocation,
                                    responses = responsesToSend,
                                    recordedAt = now
                                )
                            createOrKeep(
                                repository, context, "questionnaire", offlineFormJson.encodeToString(request),
                                effectiveTitle(), { questionnaireOfflineItems() }
                            ) { key -> onSubmit(request, key) }.also { createdId = it }
                        }
                        val questionsById = questions.associateBy { it.id }
                        val sectionsById = sections.associateBy { it.id }
                        questionAudio.forEach { (key, uris) ->
                            val caption: String
                            val hint: String
                            val sectionCodePart: String?
                            val questionNumberPart: String?
                            if (key.startsWith("section:")) {
                                val section = sectionsById[key.removePrefix("section:")]
                                caption = "Section audio: ${section?.code ?: ""} ${section?.title ?: ""}".trim()
                                hint = title.ifBlank { section?.title ?: "Section recording" }
                                sectionCodePart = section?.code
                                questionNumberPart = "SEC"
                            } else {
                                val question = questionsById[key]
                                caption = "Question audio: ${question?.sectionCode ?: ""}${question?.sortOrder ?: ""} ${question?.prompt ?: ""}".trim()
                                hint = title.ifBlank { question?.prompt ?: "Question recording" }
                                sectionCodePart = question?.sectionCode
                                questionNumberPart = question?.sortOrder?.toString()
                            }
                            uris.forEachIndexed { index, uri ->
                                val baseName = questionnaireClipBaseName(
                                    context = context,
                                    sectionCode = sectionCodePart,
                                    questionNumber = questionNumberPart,
                                    interviewName = title,
                                    uri = uri
                                ).let { if (uris.size > 1) "${it}_${index + 1}" else it }
                                val staged = qMedia.stagedDeferred[uri]?.let { runCatching { it.await() }.getOrNull() }
                                    ?: qMedia.staged[uri]
                                if (staged != null) {
                                    repository.completeStaged(
                                        staged = staged,
                                        linkedRecordType = "questionnaire",
                                        linkedRecordId = interviewId,
                                        recordName = hint,
                                        caption = caption,
                                        location = null,
                                        batchIndex = index + 1,
                                        processingRequests = listOf(UploadTranscribe.TRANSCRIPTION),
                                        overrideBaseName = baseName
                                    )
                                } else {
                                    repository.uploadMedia(
                                        context = context,
                                        uri = uri,
                                        linkedRecordType = "questionnaire",
                                        linkedRecordId = interviewId,
                                        caption = caption,
                                        location = null,
                                        titleHint = hint,
                                        batchIndex = index + 1,
                                        processingRequests = listOf(UploadTranscribe.TRANSCRIPTION),
                                        overrideBaseName = baseName
                                    )
                                }
                                questionAudio = com.fieldrepository.app.data.LegacyQuestionnaireSave.withoutClip(questionAudio, key, uri)
                                qMedia.forget(uri)
                                qMedia.uris = qMedia.uris.filterNot { it == uri }
                            }
                        }
                        media.location = capturedLocation
                        uploadAttachments(
                            repository, context, media, "questionnaire", interviewId,
                            title.ifBlank { "Interview" },
                            "Field media for ${title.trim().ifBlank { "interview" }}"
                        )
                    }.onFailure { error ->
                        if (error is KeptOnDevice) {
                            media.reset(); qMedia.reset(); questionAudio = emptyMap()
                            draft.clear()
                            saveState = SaveState.SAVED
                            onError(error.message!!)
                            delay(SAVED_CONFIRM_MS)
                            onSaved()
                            return@launch
                        }
                        saveState = SaveState.IDLE
                        val failure = error.apiFailure("Unable to save questionnaire")
                        val holder = if (isEdit) failure.artisanSetHolder() else null
                        if (holder != null) {
                            mergeUnsaved = unsavedBeforeMergeNotice(
                                typedAnswers = responsesToSend.size,
                                recordings = qMedia.uris.size,
                                attachments = media.uris.size
                            )
                            mergeConflicts = emptyList()
                            mergeConflictMessage = null
                            mergeOffer = holder
                        } else {
                            onError(failure.message)
                        }
                        return@launch
                    }
                    media.reset()
                    qMedia.reset()
                    questionAudio = emptyMap()
                    artisans.firstOrNull { it.id in selectedArtisans }?.let { interviewed ->
                        carry.remember(
                            CarryContext(
                                artisanId = interviewed.id,
                                artisanName = interviewed.name,
                                place = interviewed.place,
                                craftId = interviewed.craftId,
                                craftName = interviewed.craft?.name,
                                workshopId = workshop.value(),
                                workshopName = workshop.workshops.firstOrNull { it.id == workshop.value() }?.title
                            )
                        )
                    }
                    if (!isEdit) {
                        createdId = null
                        title = ""
                        selectedArtisans = emptySet()
                        place = ""
                        language = ""
                        notes = ""
                        status = defaultCreateStatus(repository.cachedUser()?.role)
                        capturedLocation = null
                        answers.values.forEach { it.value = "" }
                        workshop.markSaved()
                    }
                    saveState = SaveState.SAVED
                    delay(SAVED_CONFIRM_MS)
                    draft.clear(); onSaved()
                }
        }
        val qSig: () -> String = {
            val carriedArtisan = carry.offer?.context?.artisanId ?: prefill?.artisanId
            listOf(title, place, language, notes, status,
                selectedArtisans.filterNot { it == carriedArtisan }.sorted().joinToString(","),
                answers.entries.joinToString("|") { "${it.key}=${it.value.value}" }).joinToString("")
        }
        val initialSig = remember(editing) { qSig() }
        val dirty = qSig() != initialSig || workshop.isDirty() || qMedia.uris.isNotEmpty() || media.uris.isNotEmpty()
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        media.draftInto(draft, "media")
        qMedia.draftInto(draft, "qMedia")
        workshop.draftInto(draft)
        bindQuestionnaireAnswers(draft, answers)
        draft.bindCustom("questionAudio", { FormDraft.encodeUriMap(questionAudio) }, { el -> FormDraft.decodeUriMap(el)?.let { questionAudio = it } })
        FormDraftAutosave(draft, dirty, label = title)
        SaveButton(
            state = saveState,
            idleLabel = if (isEdit || createdId != null) "Update interview" else "Save questionnaire"
        ) { submit() }
        Text("Media uploads as you go.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun QuestionnaireV2Builder(repository: FieldRepository, user: UserDto?, onBack: () -> Unit, onError: (String) -> Unit) {
    var instruments by remember { mutableStateOf<List<QuestionnaireDto>>(emptyList()) }
    var questionnaireId by remember { mutableStateOf<String?>(null) }
    var sections by remember { mutableStateOf<List<QuestionnaireSectionDto>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { repository.questionnaires() }.onSuccess { list ->
            instruments = list
            if (questionnaireId == null) questionnaireId = (list.firstOrNull { it.isDefault } ?: list.firstOrNull())?.id
        }.onFailure { onError(it.apiErrorMessage("Couldn't load the questionnaires.")) }
    }
    LaunchedEffect(questionnaireId) {
        val id = questionnaireId ?: return@LaunchedEffect
        runCatching { repository.questionnaireSections(id) }.onSuccess { sections = it }
            .onFailure { onError(it.apiErrorMessage("Couldn't load the questionnaire sections.")) }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("‹ Sessions") }
        com.fieldrepository.app.ui.SearchableSelectField(
            label = "Questionnaire",
            options = instruments.map { com.fieldrepository.app.ui.SelectOption(it.id, it.title) },
            selectedValue = questionnaireId.orEmpty(),
            includeNone = false
        ) { questionnaireId = it }
        val chosen = questionnaireId
        if (chosen != null) QuestionnaireBuilder(
            repository, sections,
            { runCatching { repository.questionnaireSections(chosen) }.onSuccess { sections = it } },
            onError, chosen,
            canEdit = com.fieldrepository.app.ui.mayEditQuestionnaire(user, instruments.firstOrNull { it.id == chosen }?.createdById)
        )
    }
}

@Composable
private fun QuestionnaireBuilder(
    repository: FieldRepository,
    sections: List<QuestionnaireSectionDto>,
    onRefresh: suspend () -> Unit,
    onError: (String) -> Unit,
    questionnaireId: String? = null,
    canEdit: Boolean
) {
    val scope = rememberCoroutineScope()
    var newCode by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }
    var expandedSections by remember { mutableStateOf(setOf<String>()) }
    var updatingSections by remember { mutableStateOf(setOf<String>()) }

    RecordCard(title = "Questionnaire builder") {
        Text(
            if (canEdit) "Tap a section to edit it." else com.fieldrepository.app.ui.QUESTIONNAIRE_READ_ONLY_NOTE,
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (canEdit) {
        TextInput("New section code", newCode, dictate = false) { newCode = it }
        TextInput("New section title", newTitle) { newTitle = it }
        Button(
            onClick = {
                scope.launch {
                    runCatching {
                        repository.createQuestionnaireSection(QuestionnaireSectionCreateRequest(code = newCode.trim(), title = newTitle.trim(), questionnaireId = questionnaireId))
                        newCode = ""
                        newTitle = ""
                        onRefresh()
                    }.onFailure { onError(it.apiErrorMessage("Unable to add section")) }
                }
            },
            enabled = newCode.isNotBlank() && newTitle.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add section") }
        }

        sections.forEachIndexed { sectionIndex, section ->
            val expanded = expandedSections.contains(section.id)
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide ${section.title}" else "Open ${section.title}") {
                                expandedSections = if (expanded) expandedSections - section.id else expandedSections + section.id
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${section.sortOrder}. ${section.code} - ${section.title}", display = true, fontWeight = FontWeight.SemiBold)
                            Text(countNoun(section.questions.size, "question"), color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (expanded) "Hide ▲" else "Show ▼", color = Body, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    val updating = section.id in updatingSections && canEdit
                    if (expanded && !updating) {
                        if (section.questions.isEmpty()) {
                            Text("No questions in this section yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        section.questions.forEach { question ->
                            Text("${question.sortOrder}. ${question.prompt}", color = Body, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (canEdit) OutlinedButton(
                            onClick = { updatingSections = updatingSections + section.id },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(RECORD_UPDATE_LABEL)
                        }
                    }
                    if (expanded && updating) {
                        OutlinedButton(
                            onClick = { updatingSections = updatingSections - section.id },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("Done") }
                    }
                    if (expanded && updating) {
                        var code by remember(section.id, section.code) { mutableStateOf(section.code) }
                        var sectionTitle by remember(section.id, section.title) { mutableStateOf(section.title) }
                        var newPrompt by remember(section.id) { mutableStateOf("") }
                        TextInput("Code", code, dictate = false) { code = it }
                        TextInput("Title", sectionTitle) { sectionTitle = it }
                        ActionRow {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            repository.updateQuestionnaireSection(section.id, QuestionnaireSectionUpdateRequest(code = code.trim(), title = sectionTitle.trim(), isActive = true))
                                            onRefresh()
                                        }.onFailure { onError(it.apiErrorMessage("Unable to update section")) }
                                    }
                                },
                            ) { Text("Save") }
                            OutlinedButton(
                                enabled = sectionIndex > 0,
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            repository.reorderQuestionnaireSections(moveIds(sections.map { it.id }, sectionIndex, -1))
                                            onRefresh()
                                        }.onFailure { onError(it.apiErrorMessage("Unable to move section")) }
                                    }
                                }
                            ) { Text("Up") }
                            OutlinedButton(
                                enabled = sectionIndex < sections.lastIndex,
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            repository.reorderQuestionnaireSections(moveIds(sections.map { it.id }, sectionIndex, 1))
                                            onRefresh()
                                        }.onFailure { onError(it.apiErrorMessage("Unable to move section")) }
                                    }
                                }
                            ) { Text("Down") }
                        }
                        if (canEdit) OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        repository.deleteQuestionnaireSection(section.id)
                                        onRefresh()
                                    }.onFailure { onError(it.apiErrorMessage("Unable to remove section")) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Remove section") }

                        section.questions.forEachIndexed { questionIndex, question ->
                            var prompt by remember(question.id, question.prompt) { mutableStateOf(question.prompt) }
                            var mapping by remember(question.id, question.artisanField) { mutableStateOf(question.artisanField.orEmpty()) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(color = Canvas, shape = RoundedCornerShape(12.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("${question.sortOrder}. ${question.sectionCode} ${question.prompt}", color = Muted, style = MaterialTheme.typography.bodySmall)
                                TextInput("Prompt", prompt, minLines = 2) { prompt = it }
                                DropdownField(
                                    label = "Answered from the artisan's",
                                    options = ARTISAN_FIELD_OPTIONS,
                                    selectedValue = mapping,
                                    placeholder = "Not filled in for them"
                                ) { mapping = it }
                                ActionRow {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    repository.updateQuestionnaireQuestion(question.id, QuestionnaireQuestionUpdateRequest(prompt = prompt.trim(), isActive = true, artisanField = artisanFieldUpdate(question.artisanField, mapping)))
                                                    onRefresh()
                                                }.onFailure { onError(it.apiErrorMessage("Unable to update question")) }
                                            }
                                        },
                                    ) { Text("Save") }
                                    OutlinedButton(
                                        enabled = questionIndex > 0,
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    repository.reorderQuestionnaireQuestions(section.id, moveIds(section.questions.map { it.id }, questionIndex, -1))
                                                    onRefresh()
                                                }.onFailure { onError(it.apiErrorMessage("Unable to move question")) }
                                            }
                                        }
                                    ) { Text("Up") }
                                    OutlinedButton(
                                        enabled = questionIndex < section.questions.lastIndex,
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    repository.reorderQuestionnaireQuestions(section.id, moveIds(section.questions.map { it.id }, questionIndex, 1))
                                                    onRefresh()
                                                }.onFailure { onError(it.apiErrorMessage("Unable to move question")) }
                                            }
                                        }
                                    ) { Text("Down") }
                                }
                                ActionRow {
                                    OutlinedButton(
                                        enabled = sectionIndex > 0,
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    repository.updateQuestionnaireQuestion(question.id, QuestionnaireQuestionUpdateRequest(sectionId = sections[sectionIndex - 1].id))
                                                    onRefresh()
                                                }.onFailure { onError(it.apiErrorMessage("Unable to move question")) }
                                            }
                                        },
                                    ) { Text("Prev section") }
                                    OutlinedButton(
                                        enabled = sectionIndex < sections.lastIndex,
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    repository.updateQuestionnaireQuestion(question.id, QuestionnaireQuestionUpdateRequest(sectionId = sections[sectionIndex + 1].id))
                                                    onRefresh()
                                                }.onFailure { onError(it.apiErrorMessage("Unable to move question")) }
                                            }
                                        },
                                    ) { Text("Next section") }
                                }
                                if (canEdit) OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            runCatching {
                                                repository.deleteQuestionnaireQuestion(question.id)
                                                onRefresh()
                                            }.onFailure { onError(it.apiErrorMessage("Unable to remove question")) }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Remove question") }
                            }
                        }

                        TextInput("New question", newPrompt, minLines = 2) { newPrompt = it }
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        repository.createQuestionnaireQuestion(QuestionnaireQuestionCreateRequest(sectionId = section.id, prompt = newPrompt.trim()))
                                        newPrompt = ""
                                        onRefresh()
                                    }.onFailure { onError(it.apiErrorMessage("Unable to add question")) }
                                }
                            },
                            enabled = newPrompt.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add question") }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserManagementForm(
    repository: FieldRepository,
    isMasterAdmin: Boolean,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    fun refreshUsers() {
        scope.launch {
            loading = true
            runCatching { repository.users() }
                .onSuccess { users = it }
                .onFailure { onError(it.apiErrorMessage("Unable to load users")) }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshUsers()
    }
    var expandedUsers by remember { mutableStateOf<Set<String>>(emptySet()) }
    var updatingUsers by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmTwoStepOff by remember { mutableStateOf<UserDto?>(null) }
    var issuedLink by remember { mutableStateOf<Pair<String, IssuedPasswordLinkDto>?>(null) }
    var linkBusy by remember { mutableStateOf(false) }
    val actor = remember { repository.cachedUser() }
    val actorRank = remember(actor) { roleRank(actor?.role) }
    val actorIsMaster = isMasterAdmin
    val actorIsAdmin = actorRank >= RANK_ADMIN
    val assignableRoles = remember(actorRank, actorIsMaster) {
        ROLE_RANK.entries
            .filter { it.value <= actorRank && (it.key != "MASTER_ADMIN" || actorIsMaster) }
            .sortedByDescending { it.value }
            .map { it.key to roleLabel(it.key) }
    }

    confirmTwoStepOff?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmTwoStepOff = null },
            title = { Text("Turn off two-step verification?") },
            text = {
                Text(
                    "${target.name.ifBlank { target.email }} will sign in with only their password or Google, " +
                        "Microsoft or Yahoo until they turn it on again. Their recovery codes stop working."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTwoStepOff = null
                    scope.launch {
                        runCatching { repository.resetTwoFactorForUser(target.id); refreshUsers() }
                            .onFailure { onError(it.apiErrorMessage("Unable to turn off two-step verification")) }
                    }
                }) { Text("Turn off", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmTwoStepOff = null }) { Text("Cancel") } }
        )
    }

    RecordCard(title = "Users and access") {
        Text(
            "Tap a user to manage their role and access.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        issuedLink?.let { (subject, link) ->
            IssuedPasswordLinkPanel(
                link = link,
                subject = subject,
                expires = formatIsoDate(link.expiresAt) ?: link.expiresAt,
                onWithdraw = {
                    scope.launch {
                        runCatching { repository.revokePasswordLink(link.id) }
                            .onSuccess { issuedLink = null }
                            .onFailure { onError(it.apiErrorMessage("Unable to withdraw the link")) }
                    }
                },
                onDismiss = { issuedLink = null }
            )
        }
        if (actorIsAdmin) {
            AddPersonCard(
                repository = repository,
                actorRole = actor?.role,
                actorIsMaster = actorIsMaster,
                onCreated = { refreshUsers() },
                onOpenExisting = { id ->
                    expandedUsers = expandedUsers + id
                    refreshUsers()
                }
            )
        }
        if (loading) {
            Text("Loading users...", color = Muted)
        }
        users.forEach { appUser ->
            val isMaster = appUser.role == "MASTER_ADMIN"
            val targetIsProfessorPlus = roleRank(appUser.role) >= RANK_PROFESSOR
            val canManageTarget = if (actorIsMaster) !isMaster else roleRank(appUser.role) < actorRank
            val canEditGrants = actorIsAdmin && canManageTarget && !targetIsProfessorPlus
            val expanded = expandedUsers.contains(appUser.id)
            val grantedCount = listOf(
                targetIsProfessorPlus || appUser.canManageQuestionnaire,
                roleRank(appUser.role) >= RANK_FIELD_CONTRIBUTOR || appUser.canReview,
                roleRank(appUser.role) >= RANK_ADMIN || appUser.canViewProvenance,
                targetIsProfessorPlus || appUser.canDownloadDataset
            ).count { it }
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide ${appUser.name}" else "Open ${appUser.name}") {
                                expandedUsers = if (expanded) expandedUsers - appUser.id else expandedUsers + appUser.id
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(appUser.name, display = true, fontWeight = FontWeight.SemiBold)
                            Text("${appUser.email} · ${roleLabel(appUser.role)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                            if (appUser.twoFactorEnabled) {
                                Text("Two-step on", color = Body, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                            Text(
                                if (isMaster) "All privileges (master admin)"
                                else "$grantedCount of 4 privileges granted",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(if (expanded) "Hide ▲" else "Show ▼", color = Body, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    val mayUpdate = canManageTarget && !isMaster && (actorIsAdmin || assignableRoles.isNotEmpty())
                    val updating = mayUpdate && updatingUsers.contains(appUser.id)
                    if (expanded && !updating) {
                        HorizontalDivider()
                        UserSummaryLine("Role", roleLabel(appUser.role))
                        UserSummaryLine("Questionnaire builder", if (targetIsProfessorPlus || appUser.canManageQuestionnaire) "Granted" else "Not granted")
                        UserSummaryLine(
                            "Record review & approval",
                            if (roleRank(appUser.role) >= RANK_FIELD_CONTRIBUTOR || appUser.canReview) "Granted" else "Not granted"
                        )
                        UserSummaryLine("View provenance", if (roleRank(appUser.role) >= RANK_ADMIN || appUser.canViewProvenance) "Granted" else "Not granted")
                        UserSummaryLine("Download entire dataset", if (targetIsProfessorPlus || appUser.canDownloadDataset) "Granted" else "Not granted")
                        UserSummaryLine("Password", if (accountHasPassword(appUser)) "Set" else "No password yet")
                        AppToggle(
                            label = "Two-step verification",
                            description = if (appUser.twoFactorEnabled) "Turned on by them." else "Not turned on. Only they can turn it on.",
                            checked = appUser.twoFactorEnabled,
                            enabled = false,
                            onCheckedChange = {}
                        )
                        com.fieldrepository.app.ui.signInStatusLabel(appUser.lastLoginAt, appUser.firstLoginAt, appUser.createdAt)
                            ?.let { UserSummaryLine("Signed in", it) }
                        if (mayUpdate) {
                            OutlinedButton(
                                onClick = { updatingUsers = updatingUsers + appUser.id },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(RECORD_UPDATE_LABEL)
                            }
                        }
                    }
                    if (expanded && updating) {
                        HorizontalDivider()
                        OutlinedButton(
                            onClick = { updatingUsers = updatingUsers - appUser.id },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("Done") }
                        if (canManageTarget && !isMaster && assignableRoles.isNotEmpty()) {
                            DropdownField(
                                label = "Role",
                                options = assignableRoles,
                                selectedValue = appUser.role,
                                includeNone = false
                            ) { nextRole ->
                                if (nextRole != appUser.role) {
                                    scope.launch {
                                        runCatching { repository.updateUserRole(appUser.id, nextRole); refreshUsers() }
                                            .onFailure { onError(it.apiErrorMessage("Unable to update role")) }
                                    }
                                }
                            }
                            Text(
                                "Professors and above already have these.",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                            HorizontalDivider()
                        }
                        GrantToggleRow(
                            label = "Questionnaire builder",
                            granted = targetIsProfessorPlus || appUser.canManageQuestionnaire,
                            enabled = canEditGrants,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserQuestionnaireAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.apiErrorMessage("Unable to update questionnaire access")) }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "Record review & approval",
                            granted = roleRank(appUser.role) >= RANK_FIELD_CONTRIBUTOR || appUser.canReview,
                            enabled = actorIsAdmin && canManageTarget && roleRank(appUser.role) < RANK_FIELD_CONTRIBUTOR,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserReviewAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.apiErrorMessage("Unable to update review access")) }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "View provenance",
                            granted = roleRank(appUser.role) >= RANK_ADMIN || appUser.canViewProvenance,
                            enabled = actorIsAdmin && canManageTarget && roleRank(appUser.role) < RANK_ADMIN,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserProvenanceAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.apiErrorMessage("Unable to update provenance access")) }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "Download entire dataset",
                            granted = targetIsProfessorPlus || appUser.canDownloadDataset,
                            enabled = canEditGrants,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserDatasetAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.apiErrorMessage("Unable to update dataset access")) }
                                }
                            }
                        )
                        if (actorIsAdmin && canManageTarget && appUser.id != actor?.id && passwordLinkOffered(appUser)) {
                            HorizontalDivider()
                            OutlinedButton(
                                enabled = !linkBusy,
                                onClick = {
                                    scope.launch {
                                        linkBusy = true
                                        runCatching { repository.issuePasswordLink(appUser.id) }
                                            .onSuccess { issuedLink = appUser.name to it }
                                            .onFailure { onError(it.apiErrorMessage("Unable to issue a password link")) }
                                        linkBusy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Issue a set-password link")
                            }
                            Text("Shown once. Copy it and send it yourself.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (actorIsAdmin && canManageTarget && appUser.twoFactorEnabled && appUser.id != actor?.id) {
                            HorizontalDivider()
                            OutlinedButton(onClick = { confirmTwoStepOff = appUser }) {
                                Text("Turn off two-step verification", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (actorIsAdmin && canManageTarget && appUser.id != actor?.id) {
                            HelpThemBackInActions(
                                repository = repository,
                                target = appUser,
                                onChanged = { refreshUsers() },
                                onError = onError
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharingForm(
    repository: FieldRepository,
    isAdmin: Boolean,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val myId = repository.cachedUser()?.id
    var tiers by remember { mutableStateOf<List<DataAccessTierInfo>>(emptyList()) }
    var grants by remember { mutableStateOf(MyGrantsDto()) }
    var directory by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            runCatching {
                tiers = repository.dataAccessTiers()
                grants = repository.dataAccessGrants()
                directory = repository.userDirectory().filter { it.id != myId }
            }.onSuccess { loadError = null }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    loadError = it.apiErrorMessage("Couldn't load sharing.")
                }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun run(block: suspend () -> Unit, ok: String) {
        scope.launch {
            busy = true
            runCatching { block() }
                .onSuccess { onMessage(ok); reload() }
                .onFailure { onError(it.apiErrorMessage("That didn't go through. Try again.")) }
            busy = false
        }
    }

    loadError?.let { failure ->
        LoadFailedCard(message = failure, onRetry = { reload() })
    }
    RecordCard(title = "Access tiers", icon = Icons.Filled.Share) {
        if (tiers.isEmpty() && loadError == null) Text("Loading…", color = Muted)
        if (tiers.isEmpty() && loadError != null) Text("Not loaded.", color = Muted)
        tiers.forEach { t ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when (t.tier) {
                        "DOWNLOAD" -> "Download (minimum)"
                        "COMMENT" -> "Comment (medium)"
                        else -> "Edit (maximum)"
                    },
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium
                )
                Text(t.description, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    RecordCard(title = "Request access") {
        RequestAccessFields(
            repository = repository,
            directory = directory,
            outgoing = grants.outgoing,
            onChanged = { reload() }
        )
    }

    RecordCard(title = "Grant access to your data") {
        GrantAccessFields(
            repository = repository,
            directory = directory,
            incoming = grants.incoming,
            onChanged = { reload() },
            onError = onError
        )
    }

    RecordCard(title = "Access to your data") {
        val incoming = grants.incoming
        if (incoming.isEmpty()) Text("No requests yet.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        incoming.forEach { g ->
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(g.grantee?.name ?: g.granteeId, display = true, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${g.grantee?.email ?: ""} · ${tierLabel(g.tier)} · ${g.status}" +
                            (if (g.allData) " · all data" else " · ${g.scopeItems.size} records"),
                        color = Muted, style = MaterialTheme.typography.bodySmall
                    )
                    if (!g.requestNote.isNullOrBlank()) Text("“${g.requestNote}”", color = Muted, style = MaterialTheme.typography.bodySmall)
                    ActionRow {
                        when (g.status) {
                            "PENDING" -> {
                                Button(enabled = !busy, onClick = { run({ repository.decideDataAccess(g.id, "GRANTED", g.tier) }, "Access granted.") }) { Text("Approve") }
                                OutlinedButton(enabled = !busy, onClick = { run({ repository.decideDataAccess(g.id, "DENIED", null) }, "Request denied.") }) { Text("Deny") }
                            }
                            "GRANTED" -> OutlinedButton(enabled = !busy, onClick = { run({ repository.revokeDataAccess(g.id) }, "Access revoked.") }) { Text("Revoke") }
                            else -> OutlinedButton(enabled = !busy, onClick = { run({ repository.deleteDataAccess(g.id) }, "Request removed.") }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }

    RecordCard(title = "Your access to others' data") {
        val outgoing = grants.outgoing
        if (outgoing.isEmpty()) Text("No access yet.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        outgoing.forEach { g ->
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(g.owner?.name ?: g.ownerId, display = true, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${g.owner?.email ?: ""} · ${tierLabel(g.tier)} · ${g.status}",
                        color = Muted, style = MaterialTheme.typography.bodySmall
                    )
                    ActionRow {
                        OutlinedButton(enabled = !busy, onClick = { run({ repository.deleteDataAccess(g.id) }, "Request removed.") }) {
                            Text(if (g.status == "PENDING") "Withdraw" else "Remove")
                        }
                    }
                }
            }
        }
    }
}

private val workshopAccessLevels = listOf("VIEW", "CONTRIBUTE", "EDIT")

private const val DEFAULT_WORKSHOP_LEVEL = "CONTRIBUTE"

private fun workshopLevelLabel(level: String): String = when (level) {
    "VIEW" -> "View"
    "CONTRIBUTE" -> "Contribute"
    "EDIT" -> "Edit"
    else -> level
}

private fun workshopAccessStatusLabel(status: String): String = when (status) {
    "PENDING" -> "Waiting for approval"
    "GRANTED" -> "Granted"
    "DENIED" -> "Denied"
    "REVOKED" -> "Revoked"
    else -> status
}

@Composable
@ReadOnlyComposable
private fun workshopAccessStatusColor(status: String): Color = when (status) {
    "GRANTED" -> SuccessGreen
    "PENDING" -> Coral
    else -> FailureRed
}

private fun workshopLevelOptions(levels: List<WorkshopAccessLevelDto>): List<Pair<String, String>> =
    if (levels.isEmpty()) workshopAccessLevels.map { it to workshopLevelLabel(it) }
    else levels.map { it.level to workshopLevelLabel(it.level) }

private fun assignmentWorkshopLabel(row: WorkshopAssignmentDto): String =
    row.workshop?.let { workshopOptionLabel(it) } ?: "Unnamed workshop"

@Composable
private fun WorkshopAssignmentCard(
    repository: FieldRepository,
    directory: List<UserDto>,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var workshops by remember { mutableStateOf<List<WorkshopDetailDto>>(emptyList()) }
    var levels by remember { mutableStateOf<List<WorkshopAccessLevelDto>>(emptyList()) }
    var selectedWorkshop by remember { mutableStateOf("") }
    var roster by remember { mutableStateOf<List<WorkshopAssignmentDto>>(emptyList()) }
    var loadingRoster by remember { mutableStateOf(false) }
    var busyUserId by remember { mutableStateOf<String?>(null) }
    var addUserId by remember { mutableStateOf("") }
    var addLevel by remember { mutableStateOf(DEFAULT_WORKSHOP_LEVEL) }

    LaunchedEffect(Unit) {
        runCatching { workshops = repository.workshopsByOccurrence() }
            .onFailure { onError(it.apiErrorMessage("Unable to load workshops")) }
        runCatching { levels = repository.workshopAccessLevels() }
    }

    fun loadRoster(workshopId: String) {
        if (workshopId.isBlank()) { roster = emptyList(); return }
        scope.launch {
            loadingRoster = true
            runCatching { repository.workshopAssignments(workshopId) }
                .onSuccess { roster = it }
                .onFailure { onError(it.apiErrorMessage("Unable to load the roster")) }
            loadingRoster = false
        }
    }

    fun act(userId: String, ok: String, block: suspend () -> Unit) {
        scope.launch {
            busyUserId = userId
            runCatching { block() }
                .onSuccess { onMessage(ok); loadRoster(selectedWorkshop) }
                .onFailure { onError(it.apiErrorMessage("That change didn't go through")) }
            busyUserId = null
        }
    }

    RecordCard(title = "Workshop assignments", icon = Icons.Filled.Groups) {
        Text(
            "Pick a workshop and who may work in it. With nobody assigned, it's open to all.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        DropdownField(
            label = "Workshop",
            options = workshops.map { it.id to workshopOptionLabel(it) },
            selectedValue = selectedWorkshop,
            placeholder = "Select workshop",
            onSelect = { selectedWorkshop = it; addUserId = ""; loadRoster(it) }
        )
        if (selectedWorkshop.isBlank()) return@RecordCard

        if (loadingRoster) {
            Text("Loading the roster…", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else if (roster.isEmpty()) {
            Text("Open to everyone.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        val ordered = roster.sortedBy { row ->
            when (row.status) { "GRANTED" -> 0; "PENDING" -> 1; else -> 2 }
        }
        ordered.forEach { row ->
            val busy = busyUserId == row.userId
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.user?.name ?: row.userId, display = true, color = Body, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            row.user?.email?.takeIf { it.isNotBlank() },
                            workshopLevelLabel(row.accessLevel)
                        ).joinToString(" · "),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(workshopAccessStatusLabel(row.status), color = workshopAccessStatusColor(row.status), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    row.requestNote?.takeIf { it.isNotBlank() }?.let { Text("Asked: “$it”", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    row.decisionNote?.takeIf { it.isNotBlank() }?.let { Text("Decision: “$it”", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    DropdownField(
                        label = "Access level",
                        options = workshopLevelOptions(levels),
                        selectedValue = row.accessLevel,
                        includeNone = false,
                        enabled = !busy,
                        onSelect = { level ->
                            if (level != row.accessLevel) {
                                act(row.userId, "${row.user?.name ?: "Access"} set to ${workshopLevelLabel(level)}") {
                                    repository.updateWorkshopAccess(selectedWorkshop, row.userId, accessLevel = level)
                                }
                            }
                        }
                    )
                    ActionRow {
                        if (row.status == "GRANTED") {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    act(row.userId, "Access revoked") {
                                        repository.revokeWorkshopAccess(selectedWorkshop, row.userId)
                                    }
                                }
                            ) { Text(if (busy) "Working…" else "Revoke", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                        } else {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    act(row.userId, "Access granted") {
                                        repository.grantWorkshopAccess(selectedWorkshop, row.userId, row.accessLevel)
                                    }
                                }
                            ) { Text(if (busy) "Working…" else "Grant", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                            if (row.status == "PENDING") {
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = {
                                        act(row.userId, "Request denied") {
                                            repository.updateWorkshopAccess(selectedWorkshop, row.userId, status = "DENIED")
                                        }
                                    }
                                ) { Text("Deny", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Add a researcher", display = true, color = Body, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        val onRoster = roster.map { it.userId }.toSet()
        val addable = directory.filterNot { it.id in onRoster }
        if (addable.isEmpty()) {
            Text("Everyone already has access to this workshop.", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            DropdownField(
                label = "Researcher",
                options = addable.map { it.id to "${it.name} · ${it.email}" },
                selectedValue = addUserId,
                placeholder = "Select researcher",
                onSelect = { addUserId = it }
            )
            DropdownField(
                label = "Access level",
                options = workshopLevelOptions(levels),
                selectedValue = addLevel,
                includeNone = false,
                onSelect = { addLevel = it }
            )
            levels.firstOrNull { it.level == addLevel }?.description?.takeIf { it.isNotBlank() }
                ?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            Button(
                enabled = busyUserId == null && addUserId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val target = addUserId
                    act(target, "Access granted") {
                        repository.grantWorkshopAccess(selectedWorkshop, target, addLevel)
                        addUserId = ""
                    }
                }
            ) { Text("Grant access") }
        }
    }
}

@Composable
private fun WorkshopAccessScreen(
    repository: FieldRepository,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var workshops by remember { mutableStateOf<List<WorkshopDetailDto>>(emptyList()) }
    var levels by remember { mutableStateOf<List<WorkshopAccessLevelDto>>(emptyList()) }
    var mine by remember { mutableStateOf<List<WorkshopAssignmentDto>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var level by remember { mutableStateOf(DEFAULT_WORKSHOP_LEVEL) }
    var note by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    suspend fun loadMine() {
        runCatching { repository.myWorkshopAccess() }
            .onSuccess { mine = it }
            .onFailure { onError(it.apiErrorMessage("Unable to load your access")) }
    }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { workshops = repository.workshopsByOccurrence() }
            .onFailure { onError(it.apiErrorMessage("Unable to load workshops")) }
        runCatching { levels = repository.workshopAccessLevels() }
        loadMine()
        loading = false
    }

    RecordCard(title = "Request workshop access", icon = Icons.Filled.LockOpen) {
        Text(
            "Pick workshops and send one request.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (loading) {
            Text("Loading workshops…", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            CheckboxMultiSelectField(
                label = "Workshops",
                options = workshops.map { it.id to workshopOptionLabel(it) },
                selectedIds = selected,
                emptyMessage = "No workshops to request yet.",
                onToggle = { id -> selected = if (id in selected) selected - id else selected + id }
            )
            DropdownField(
                label = "Access level you need",
                options = workshopLevelOptions(levels),
                selectedValue = level,
                includeNone = false,
                onSelect = { level = it }
            )
            levels.firstOrNull { it.level == level }?.description?.takeIf { it.isNotBlank() }
                ?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            TextInput("Why do you need access? (optional)", note, minLines = 2) { note = it }
            Button(
                enabled = !busy && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { repository.requestWorkshopAccess(selected.toList(), level, note) }
                            .onSuccess { result ->
                                val counts = result.outcomes.groupingBy { it.outcome }.eachCount()
                                val parts = listOfNotNull(
                                    counts["CREATED"]?.let { "$it sent" },
                                    counts["RE_REQUESTED"]?.let { "$it asked again" },
                                    counts["ALREADY_PENDING"]?.let { "$it already waiting" },
                                    counts["ALREADY_GRANTED"]?.let { "$it already granted" }
                                )
                                onMessage("Workshop access: ${parts.joinToString(", ").ifBlank { "request sent" }}")
                                selected = emptySet()
                                note = ""
                                loadMine()
                            }
                            .onFailure { onError(it.apiErrorMessage("Unable to send the request")) }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Sending…" else "Request access (${selected.size})") }
        }
    }

    RecordCard(title = "My workshop access") {
        Text(
            "Granted, pending and refused.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (mine.isEmpty()) {
            Text("No workshop access yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        mine.forEach { row ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(assignmentWorkshopLabel(row), display = true, color = Body, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            workshopAccessStatusLabel(row.status),
                            color = workshopAccessStatusColor(row.status),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("· ${workshopLevelLabel(row.accessLevel)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    row.decisionNote?.takeIf { it.isNotBlank() }?.let { Text("“$it”", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    row.decidedBy?.name?.let { Text("Decided by $it${formatIsoDate(row.decidedAt)?.let { d -> " · $d" } ?: ""}", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    if (row.status == "DENIED" || row.status == "REVOKED") {
                        Text("You can ask again above.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkshopAccessQueueCard(
    repository: FieldRepository,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<WorkshopAssignmentDto>>(emptyList()) }
    var levels by remember { mutableStateOf<List<WorkshopAccessLevelDto>>(emptyList()) }
    var showAll by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }
    val chosenLevel = remember { mutableStateMapOf<String, String>() }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { repository.workshopAccessQueue(if (showAll) "ALL" else "PENDING") }
                .onSuccess { rows = it }
                .onFailure { onError(it.apiErrorMessage("Unable to load the access queue")) }
            loading = false
        }
    }
    LaunchedEffect(showAll) { refresh() }
    LaunchedEffect(Unit) { runCatching { levels = repository.workshopAccessLevels() } }

    fun decide(row: WorkshopAssignmentDto, status: String) {
        scope.launch {
            busyId = row.id
            runCatching {
                repository.decideWorkshopAccess(row.id, status, chosenLevel[row.id] ?: row.accessLevel)
            }
                .onSuccess {
                    onMessage(if (status == "GRANTED") "Access granted to ${row.user?.name ?: "the researcher"}" else "Request denied")
                    refresh()
                }
                .onFailure { onError(it.apiErrorMessage("That decision didn't go through")) }
            busyId = null
        }
    }

    RecordCard(title = "Workshop access requests", icon = Icons.Filled.LockOpen) {
        Text(
            "Requests to work in a workshop, oldest first.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showAll, onClick = { showAll = false }, label = { Text("Pending") })
            FilterChip(selected = showAll, onClick = { showAll = true }, label = { Text("Full history") })
        }
        when {
            loading -> Text("Loading requests…", color = Muted, style = MaterialTheme.typography.bodySmall)
            rows.isEmpty() -> Text(
                if (showAll) "No workshop access yet." else "Nothing waiting.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            else -> rows.forEach { row ->
                val busy = busyId == row.id
                val pending = row.status == "PENDING"
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.user?.name ?: row.userId, display = true, color = Body, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                row.user?.email?.takeIf { it.isNotBlank() },
                                assignmentWorkshopLabel(row),
                                formatIsoDate(row.createdAt)
                            ).joinToString(" · "),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "${workshopAccessStatusLabel(row.status)} · asked for ${workshopLevelLabel(row.accessLevel)}",
                            color = workshopAccessStatusColor(row.status),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        row.requestNote?.takeIf { it.isNotBlank() }?.let { Text("“$it”", color = Muted, style = MaterialTheme.typography.bodySmall) }
                        if (pending) {
                            DropdownField(
                                label = "Grant at level",
                                options = workshopLevelOptions(levels),
                                selectedValue = chosenLevel[row.id] ?: row.accessLevel,
                                includeNone = false,
                                enabled = !busy,
                                onSelect = { chosenLevel[row.id] = it }
                            )
                            ActionRow {
                                Button(
                                    enabled = !busy,
                                    onClick = { decide(row, "GRANTED") }
                                ) { Text(if (busy) "Working…" else "Approve", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = { decide(row, "DENIED") }
                                ) { Text("Deny", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                            }
                        } else {
                            row.decisionNote?.takeIf { it.isNotBlank() }?.let { Text("Decision: “$it”", color = Muted, style = MaterialTheme.typography.bodySmall) }
                            Text(
                                "Decided. Change it in Workshop assignments.",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun taskStatusLabel(status: String): String = when (status) {
    "OPEN" -> "Open"
    "IN_PROGRESS" -> "In progress"
    "DONE" -> "Done"
    "CANCELLED" -> "Cancelled"
    else -> status
}

@Composable
@ReadOnlyComposable
private fun taskStatusColor(status: String): Color = when (status) {
    "DONE" -> SuccessGreen
    "IN_PROGRESS" -> Coral
    "CANCELLED" -> Muted
    else -> Body
}

private val assigneeTaskStatuses = listOf("OPEN", "IN_PROGRESS", "DONE")

private fun taskScopeSummary(task: TaskDto): String {
    val parts = mutableListOf<String>()
    if (task.recordTypeLabels.isNotEmpty()) {
        parts += task.targetCount?.let { "${task.recordTypeLabels.joinToString(", ")} (target $it)" }
            ?: task.recordTypeLabels.joinToString(", ")
    }
    if (task.sections.isNotEmpty()) {
        val codes = task.sections.joinToString(", ") { it.code }
        parts += if (task.sections.size == 1) "questionnaire section $codes" else "questionnaire sections $codes"
    }
    if (task.artisans.isNotEmpty()) {
        parts += if (task.artisans.size <= 3) {
            "for ${task.artisans.joinToString(", ") { it.name }}"
        } else {
            "for ${task.artisans.size} artisans"
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun MyTasksScreen(
    repository: FieldRepository,
    canAssign: Boolean,
    onOpenAssignmentBoard: (() -> Unit)? = null,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val myId = remember { repository.cachedUser()?.id }
    var view by remember { mutableStateOf("assigned") }
    LaunchedEffect(canAssign) { if (!canAssign) view = "assigned" }
    var statusFilter by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf<List<TaskDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { repository.tasks(view = view, status = statusFilter.ifBlank { null }) }
                .onSuccess { tasks = it }
                .onFailure { onError(it.apiErrorMessage("Unable to load your tasks")) }
            loading = false
        }
    }
    LaunchedEffect(view, statusFilter) { refresh() }

    fun update(task: TaskDto, status: String? = null, progressCount: Int? = null, ok: String) {
        scope.launch {
            busyId = task.id
            val payload = offlineFormJson.encodeToString(TaskUpdateBody(status = status, progressCount = progressCount))
            suspend fun keepOnDevice(failure: Throwable?): Boolean {
                if (!queueSmallWrite(repository, context, failure, idempotent = true, type = "task-progress",
                        payloadJson = payload, label = task.title.ifBlank { "Task" }, targetId = task.id)) return false
                tasks = tasks.map {
                    if (it.id == task.id) it.copy(status = status ?: it.status, progressCount = progressCount ?: it.progressCount) else it
                }
                onMessage("Saved offline. Updates when you're online.")
                return true
            }
            if (keepOnDevice(null)) { busyId = null; return@launch }
            runCatching { repository.updateTaskProgress(task.id, status = status, progressCount = progressCount) }
                .onSuccess { updated ->
                    tasks = tasks.map { if (it.id == updated.id) updated else it }
                    onMessage(ok)
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    if (!keepOnDevice(it)) onError(it.apiErrorMessage("Unable to update the task"))
                }
            busyId = null
        }
    }

    RecordCard(title = "Tasks", icon = Icons.AutoMirrored.Filled.Assignment) {
        Text(
            "Your assigned work. Update the status as you go.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (canAssign) {
            Text(
                "Assign work from the assignment board.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            onOpenAssignmentBoard?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Assignment board")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = view == "assigned", onClick = { view = "assigned" }, label = { Text("Assigned to me") })
                FilterChip(selected = view == "created", onClick = { view = "created" }, label = { Text("Assigned by me") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(selected = statusFilter.isBlank(), onClick = { statusFilter = "" }, label = { Text("All") })
            listOf("OPEN", "IN_PROGRESS", "DONE").forEach { value ->
                FilterChip(
                    selected = statusFilter == value,
                    onClick = { statusFilter = if (statusFilter == value) "" else value },
                    label = { Text(taskStatusLabel(value)) }
                )
            }
        }
        OutlinedButton(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (loading) "Loading tasks…" else "Refresh")
        }
        when {
            loading -> Text("Loading tasks…", color = Muted, style = MaterialTheme.typography.bodySmall)
            tasks.isEmpty() -> Text(
                if (view == "created") "You have not assigned any work yet."
                else "Nothing is assigned to you right now.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            else -> tasks.forEach { task ->
                TaskCard(
                    task = task,
                    editable = task.assigneeId != null && task.assigneeId == myId,
                    busy = busyId == task.id,
                    onStatus = { status -> update(task, status = status, ok = "Marked ${taskStatusLabel(status).lowercase()}") },
                    onProgress = { count -> update(task, progressCount = count, ok = "Progress reported") }
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskDto,
    editable: Boolean,
    busy: Boolean,
    onStatus: (String) -> Unit,
    onProgress: (Int) -> Unit
) {
    var reported by remember(task.id, task.progressCount) { mutableStateOf(task.progressCount.toString()) }
    val scopeLine = taskScopeSummary(task)

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(task.title.ifBlank { "Field task" }, display = true, color = Body, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(taskStatusLabel(task.status), color = taskStatusColor(task.status), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                if (task.isOverdue) Text("· Overdue", color = FailureRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                listOfNotNull(
                    task.workshopTitle?.takeIf { it.isNotBlank() },
                    formatIsoDate(task.dueAt)?.let { "due $it" },
                    if (!editable) task.assignee?.name?.takeIf { it.isNotBlank() }?.let { "for $it" } else null
                ).joinToString(" · ").ifBlank { "No workshop or due date" },
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            if (scopeLine.isNotBlank()) Text(scopeLine, color = Body, style = MaterialTheme.typography.bodySmall)
            task.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }

            val target = task.targetCount
            val derived = task.derivedCount
            Text(
                buildString {
                    append("Reported ${task.progressCount}")
                    if (target != null) append(" of $target")
                    if (derived != null) {
                        append(" · recorded $derived")
                        task.derivedTarget?.let { append(" of $it") }
                    }
                },
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            task.percentComplete?.let { percent ->
                FieldLinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            }

            if (!editable) return@Column
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                assigneeTaskStatuses.forEach { value ->
                    val current = task.status == value
                    val segment = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { selected = current }
                    if (current) {
                        Button(
                            onClick = {},
                            enabled = !busy,
                            modifier = segment,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) { Text(taskStatusLabel(value), maxLines = 2, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
                    } else {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { onStatus(value) },
                            modifier = segment,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) { Text(taskStatusLabel(value), maxLines = 2, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = reported,
                    onValueChange = { raw -> reported = raw.filter { it.isDigit() }.take(6) },
                    label = { Text(if (target != null) "Done so far (of $target)" else "Done so far") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = !busy && reported.toIntOrNull() != null && reported.toIntOrNull() != task.progressCount,
                    onClick = { reported.toIntOrNull()?.let(onProgress) }
                ) { Text(if (busy) "Saving…" else "Report") }
            }
        }
    }
}

private fun tierLabel(tier: String): String = when (tier) {
    "DOWNLOAD" -> "Download"
    "COMMENT" -> "Comment"
    "EDIT" -> "Edit"
    else -> tier
}

@Composable
private fun UserSummaryLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, color = Body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GrantToggleRow(label: String, granted: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Body, style = MaterialTheme.typography.bodyMedium)
            Text(if (granted) "Granted" else "Not granted", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(enabled = enabled, onClick = { onToggle(!granted) }) {
            Text(if (granted) "Revoke" else "Grant")
        }
    }
}

@Composable
private fun RecordCard(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Canvas),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                    Text(title, display = true, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                Text(title, display = true, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            }
            content()
        }
    }
}

@Composable
private fun WorkshopMappingCard(
    repository: FieldRepository,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<WorkshopMappingPlanDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var appliedTotal by remember { mutableStateOf<Int?>(null) }
    var savingAuto by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        runCatching { repository.unmappedRecords() }
            .onSuccess { plan = it; loading = false }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                onError(it.apiErrorMessage("The unmapped records could not be read."))
                loading = false
            }
    }

    RecordCard(title = "Records not filed under a workshop", icon = Icons.Filled.Link) {
        Text(
            "Hidden from workshop views. Records with a filed parent are filed each night; the rest need a look.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        plan?.autoMap?.let { status ->
            AppToggle(
                label = "File clear-cut records each night",
                checked = status.enabled,
                busy = savingAuto,
                description = WorkshopMappingCounts.autoMapLine(status, plan?.let { WorkshopMappingCounts.totalClearCut(it) } ?: 0),
                onCheckedChange = { next ->
                    scope.launch {
                        savingAuto = true
                        runCatching { repository.setUnmappedAuto(next) }
                            .onSuccess { saved -> plan = plan?.copy(autoMap = saved) }
                            .onFailure { failure ->
                                if (failure is kotlinx.coroutines.CancellationException) throw failure
                                onError(failure.apiErrorMessage("The setting could not be saved."))
                            }
                        savingAuto = false
                    }
                }
            )
        }

        val current = plan
        when {
            loading && current == null ->
                Text("Checking records…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)

            current == null ->
                Text(
                    "Couldn't check. Tap Re-check.",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall
                )

            current.totals.unassigned == 0 ->
                Text(
                    "Every record has a workshop.",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall
                )

            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MappingStatTile("Need a look", WorkshopMappingCounts.totalNeedsReview(current), Modifier.weight(1f))
                    MappingStatTile(
                        "Suggested",
                        maxOf(0, current.totals.resolved - WorkshopMappingCounts.totalClearCut(current)),
                        Modifier.weight(1f)
                    )
                    MappingStatTile("Need a person", current.totals.unresolved, Modifier.weight(1f))
                }

                current.buckets.filter { WorkshopMappingCounts.needsReview(it) > 0 }.forEach { bucket ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
                            .padding(10.dp)
                    ) {
                        val needsReview = WorkshopMappingCounts.needsReview(bucket)
                        val noun = if (needsReview == 1) bucket.singular else bucket.plural
                        Text(
                            "$needsReview $noun" +
                                if (bucket.applied != null) {
                                    " — ${bucket.applied} filed just now"
                                } else {
                                    " — ${WorkshopMappingCounts.suggested(bucket)} suggested" +
                                        if (bucket.unresolved > 0) ", ${bucket.unresolved} need a person" else ""
                                },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (bucket.byWorkshop.isNotEmpty()) {
                            Text(
                                "Filed under " + bucket.byWorkshop.joinToString(", ") { "${it.title} (${it.count})" } + ".",
                                color = MaterialTheme.field.muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        WorkshopMappingCounts.reviewRungs(bucket).forEach { entry ->
                            Text(
                                "${entry.count} because ${entry.copy}.",
                                color = MaterialTheme.field.body,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        val stuck = bucket.rows.filter { it.workshopId == null }
                        if (stuck.isNotEmpty()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.field.warningContainer, MaterialTheme.shapes.small)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    "Choose its workshop by hand",
                                    color = MaterialTheme.field.onWarningContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                stuck.forEach { row ->
                                    Text(
                                        row.title +
                                            (row.reasonCopy?.let { " — $it" } ?: "") +
                                            (if (row.candidateTitles.isNotEmpty()) {
                                                " (" + row.candidateTitles.joinToString(" or ") + ")"
                                            } else ""),
                                        color = MaterialTheme.field.onWarningContainer,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (stuck.size < bucket.unresolved) {
                                    Text(
                                        "${bucket.unresolved - stuck.size} more. Re-check after filing these.",
                                        color = MaterialTheme.field.onWarningContainer,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }

                if (current.totals.resolved > 0) {
                    Button(
                        onClick = {
                            scope.launch {
                                applying = true
                                runCatching { repository.mapUnmappedRecords() }
                                    .onSuccess {
                                        appliedTotal = it.totals.applied ?: 0
                                        reloadKey += 1
                                    }
                                    .onFailure { failure ->
                                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                                        onError(failure.apiErrorMessage("The records could not be mapped."))
                                    }
                                applying = false
                            }
                        },
                        enabled = !applying,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (applying) {
                                "Filing…"
                            } else {
                                "File ${current.totals.resolved} record" +
                                    (if (current.totals.resolved == 1) "" else "s") + " under its workshop"
                            }
                        )
                    }
                    Text(
                        "Only fills empty workshops; nothing else changes.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        appliedTotal?.let { total ->
            Text(
                if (total == 0) {
                    "Nothing left to file."
                } else {
                    "$total record${if (total == 1) "" else "s"} filed."
                },
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall
            )
        }

        plan?.let { current ->
            if (current.workshops.isEmpty()) {
                Text(
                    "No workshop has dates. Add dates to file by date.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                current.workshops.forEach { window ->
                    Text(
                        "${window.title} — read as ${formatIsoDay(window.start)} to " +
                            formatIsoDay(window.end, daysBefore = 1),
                        color = MaterialTheme.field.placeholder,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        OutlinedButton(onClick = { reloadKey += 1 }, enabled = !loading && !applying) {
            Text("Re-check")
        }
    }
}

@Composable
private fun MappingStatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label.uppercase(Locale.ROOT), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        Text(
            value.toString(),
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

private val offlineFormJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private suspend fun trySaveOffline(
    repository: FieldRepository,
    context: Context,
    editId: String?,
    type: String,
    payloadJson: String,
    label: String,
    media: MediaCaptureState,
    recordName: String?,
    caption: String?
): Boolean {
    if (repository.isOnline(context)) return false
    repository.queueOffline(context, type, payloadJson, label, media.uris, recordName, caption, media.location, editOf = editId)
    return true
}

@Composable
private fun OfflineNotice(
    modifier: Modifier = Modifier,
    text: String = "Offline — showing saved data. Changes upload when you're back."
) {
    val ink = MaterialTheme.field.onBrandTile
    Row(
        modifier = modifier
            .background(MaterialTheme.field.brandTile, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.CloudOff, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Text(
            text,
            color = ink,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private class KeptOnDevice : Exception("Saved offline. Uploads when you're online.")

private suspend fun <T> createOrKeep(
    repository: FieldRepository,
    context: Context,
    type: String,
    payloadJson: String,
    label: String,
    items: () -> List<OfflineMediaSpec>,
    create: suspend (idempotencyKey: String) -> T
): T {
    val key = java.util.UUID.randomUUID().toString()
    try {
        return create(key)
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException || !repository.isTransientFailure(e)) throw e
        val queued = runCatching {
            repository.queueOfflineEntry(context, type, payloadJson, label, items(), idempotencyKey = key)
        }.isSuccess
        if (!queued) throw e
        throw KeptOnDevice()
    }
}

private fun MediaCaptureState.offlineSpecs(recordName: String?, caption: String?): List<OfflineMediaSpec> =
    uris.mapIndexed { index, uri ->
        OfflineMediaSpec(uri = uri, caption = caption, recordName = recordName, batchIndex = index + 1, location = location)
    }

private fun Prefill?.draftParentId(): String? = this?.artisanId ?: this?.craftId

private fun Prefill?.draftParentLabel(): String? {
    val handed = this ?: return null
    return if (handed.artisanId != null) handed.artisanName?.takeIf { it.isNotBlank() }
    else handed.craftName?.takeIf { it.isNotBlank() }
}

private fun <T> MutableState<T>.drafted(draft: FormDraft, key: String): MutableState<T> = draft.bind(key, this)

private fun MediaCaptureState.draftInto(draft: FormDraft, key: String) {
    draft.bindCustom("$key.uris", { FormDraft.encodeUris(uris) }, { el -> FormDraft.decodeUris(el)?.let { uris = it } })
    draft.bindCustom("$key.location", { DraftValues.encode(location) }, { el ->
        val decoded = DraftValues.decode(el)
        if (decoded == null || decoded is LocationRequest) location = decoded as LocationRequest?
    })
}

private fun WorkshopPickerState.draftInto(draft: FormDraft) {
    draft.bindCustom("workshop", { DraftValues.encode(selectedId) }, { el ->
        (DraftValues.decode(el) as? String)?.let { selectedId = it }
    })
}

private fun bindQuestionnaireAnswers(draft: FormDraft, answers: Map<String, MutableState<String>>) {
    draft.bindCustom("answers", {
        DraftValues.encode(answers.mapValues { it.value.value }.filterValues { it.isNotEmpty() })
    }, { el ->
        (DraftValues.decode(el) as? Map<*, *>)?.forEach { (id, text) ->
            if (id is String && text is String) answers[id]?.value = text
        }
    })
}

private fun bindProcessSteps(
    draft: FormDraft,
    steps: androidx.compose.runtime.snapshots.SnapshotStateList<ProcessStepUi>,
    existingMediaFor: (String) -> List<MediaFileDto>
) {
    draft.bindCustom("steps", {
        kotlinx.serialization.json.JsonArray(steps.map { step ->
            JsonObject(
                mapOf(
                    "key" to kotlinx.serialization.json.JsonPrimitive(step.key),
                    "serverId" to (step.serverId?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull),
                    "name" to kotlinx.serialization.json.JsonPrimitive(step.name),
                    "stepType" to kotlinx.serialization.json.JsonPrimitive(step.stepType),
                    "notes" to kotlinx.serialization.json.JsonPrimitive(step.notes),
                    "recordAdditional" to kotlinx.serialization.json.JsonPrimitive(step.recordAdditional),
                    "uris" to FormDraft.encodeUris(step.media.uris),
                    "location" to (DraftValues.encode(step.media.location) ?: kotlinx.serialization.json.JsonNull)
                )
            )
        })
    }, { el ->
        val restored = (el as kotlinx.serialization.json.JsonArray).map { item ->
            val o = item as JsonObject
            fun text(name: String): String? = (o[name] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content
            val serverId = text("serverId")
            ProcessStepUi(
                key = text("key") ?: java.util.UUID.randomUUID().toString(),
                serverId = serverId,
                name = text("name").orEmpty(),
                stepType = text("stepType") ?: "SEQUENTIAL",
                existingMedia = serverId?.let(existingMediaFor).orEmpty(),
                notes = text("notes")
            ).apply {
                recordAdditional = text("recordAdditional") == "true"
                o["uris"]?.let { FormDraft.decodeUris(it) }?.let { media.uris = it }
                o["location"]?.let { DraftValues.decode(it) }?.let { if (it is LocationRequest) media.location = it }
            }
        }
        steps.clear()
        steps.addAll(restored)
    })
}

private enum class SaveState { IDLE, SAVING, SAVED }

@Composable
private fun SaveButton(
    state: SaveState,
    idleLabel: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(onClick = onClick, enabled = enabled && state == SaveState.IDLE, modifier = modifier) {
        when (state) {
            SaveState.SAVING -> {
                FieldCircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current
                )
                Spacer(Modifier.width(8.dp))
                Text("Saving…")
            }
            SaveState.SAVED -> {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Saved ✓")
            }
            SaveState.IDLE -> Text(idleLabel)
        }
    }
}

@Composable
private fun SaveButtons(
    state: SaveState,
    idleLabel: String,
    onSave: () -> Unit,
    onSaveAnother: (() -> Unit)?
) {
    if (onSaveAnother == null) {
        SaveButton(state = state, idleLabel = idleLabel, onClick = onSave)
        return
    }
    ActionRow {
        SaveButton(state = state, idleLabel = idleLabel, modifier = Modifier, onClick = onSave)
        OutlinedButton(onClick = onSaveAnother, enabled = state == SaveState.IDLE) { Text("Save and add another") }
    }
}

private const val SAVED_CONFIRM_MS = 750L

@Composable
private fun TitleCaseHint(value: String) {
    val normalised = titleCasePreview(value) ?: return
    Text("Will be saved as “$normalised”", color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TextInput(
    label: String,
    value: String,
    minLines: Int = 1,
    titleCased: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    dictate: Boolean = true,
    rich: Boolean = false,
    resetKey: Any? = null,
    onValueChange: (String) -> Unit
) {
    RecordProseField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        minLines = minLines,
        keyboardType = keyboardType,
        dictate = dictate,
        rich = rich,
        resetKey = resetKey,
        capitalization = if (titleCased) KeyboardCapitalization.Words else null,
        below = { if (titleCased) TitleCaseHint(value) },
    )
}

private fun splitNumbered(value: String?): List<String> =
    value?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: listOf("")

private fun joinNumbered(items: List<String>): String =
    items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

@Composable
private fun NumberedListInput(
    label: String,
    items: List<String>,
    error: String?,
    focusRequester: FocusRequester? = null,
    helper: String? = null,
    onChange: (List<String>) -> Unit
) {
    val rows = items.ifEmpty { listOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text("$label *", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        helper?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        rows.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${index + 1}.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = item,
                    onValueChange = { raw ->
                        if (raw.contains('\n')) {
                            val segments = raw.split('\n')
                            val updated = rows.toMutableList()
                            updated[index] = segments.first().trim()
                            updated.addAll(index + 1, segments.drop(1).map { it.trim() })
                            onChange(updated)
                        } else {
                            val updated = rows.toMutableList()
                            updated[index] = raw
                            onChange(updated)
                        }
                    },
                    isError = error != null && index == 0,
                    minLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .let { if (index == 0 && focusRequester != null) it.focusRequester(focusRequester) else it }
                )
                if (rows.size > 1) {
                    IconButton(onClick = {
                        val updated = rows.toMutableList().also { it.removeAt(index) }
                        onChange(updated.ifEmpty { listOf("") })
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove point", tint = Muted)
                    }
                }
            }
        }
        ActionRow {
            TextButton(onClick = { onChange(rows + "") }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add point")
            }
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private const val NOTE_SEPARATOR = "\n\n"

private fun splitNotes(value: String?): List<String> =
    value?.split(Regex("\\n\\s*\\n"))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

private fun joinNotes(items: List<String>): String =
    items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(NOTE_SEPARATOR)

@Composable
private fun MultiNoteInput(
    label: String = "Notes",
    value: String,
    resetKey: Any? = null,
    dictate: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    var rows by remember(resetKey) { mutableStateOf(splitNotes(value).ifEmpty { listOf("") }) }
    fun emit(updated: List<String>) {
        val next = updated.ifEmpty { listOf("") }
        rows = next
        onValueChange(joinNotes(next))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        rows.forEachIndexed { index, note ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RecordProseField(
                    label = if (rows.size > 1) "Note ${index + 1}" else "Note",
                    value = note,
                    onValueChange = { v -> emit(rows.toMutableList().also { it[index] = v }) },
                    minLines = 2,
                    dictate = dictate,
                    resetKey = resetKey to index,
                    modifier = Modifier.weight(1f),
                )
                if (rows.size > 1) {
                    IconButton(onClick = { emit(rows.toMutableList().also { it.removeAt(index) }) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove note", tint = Muted)
                    }
                }
            }
        }
        ActionRow {
            TextButton(onClick = { emit(rows + "") }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add note")
            }
        }
    }
}

@Composable
private fun NumberedListDisplay(label: String, value: String?) {
    val items = value?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        items.forEachIndexed { index, item ->
            Text("${index + 1}. $item", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RequiredInput(
    label: String,
    value: String,
    error: String?,
    focusRequester: FocusRequester,
    minLines: Int = 1,
    titleCased: Boolean = false,
    dictate: Boolean = true,
    onValueChange: (String) -> Unit
) {
    RecordProseField(
        label = "$label *",
        value = value,
        onValueChange = onValueChange,
        minLines = minLines,
        dictate = dictate,
        errorText = error,
        focusRequester = focusRequester,
        capitalization = if (titleCased) KeyboardCapitalization.Words else null,
        below = { if (titleCased) TitleCaseHint(value) },
    )
}

private class RequiredCheck(
    val isBlank: Boolean,
    val setError: (String?) -> Unit,
    val focus: FocusRequester
)

private fun validateRequired(checks: List<RequiredCheck>): Boolean {
    checks.forEach { it.setError(null) }
    val missing = checks.filter { it.isBlank }
    if (missing.isEmpty()) return true
    missing.forEach { it.setError(REQUIRED_FIELD_SENTENCE) }
    runCatching { missing.first().focus.requestFocus() }
    return false
}

private const val REQUIRED_FIELD_SENTENCE = "Required — fill this in to save."

private fun createAppFile(context: Context, prefix: String, suffix: String): File {
    val directory = DraftStore.capturesDir(context)
    return File.createTempFile(prefix, suffix, directory)
}

private fun createAppFileUri(context: Context, prefix: String, suffix: String): Uri {
    return uriForFile(context, createAppFile(context, prefix, suffix))
}

private fun uriForFile(context: Context, file: File): Uri {
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun saveMediaToDevice(context: Context, url: String?, filename: String, mimeType: String?) {
    if (url.isNullOrBlank()) {
        Toast.makeText(context, "This file has no downloadable URL.", Toast.LENGTH_LONG).show()
        return
    }
    val safeName = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "media" }
    runCatching {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(safeName)
            .setDescription("Saving to Downloads")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(context, "Saving \"$safeName\" to Downloads…", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "Couldn't save: ${it.apiErrorMessage("download failed")}", Toast.LENGTH_LONG).show()
    }
}

private fun openUri(context: Context, uri: Uri, mimeType: String?) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (mimeType.isNullOrBlank()) {
            data = uri
        } else {
            setDataAndType(uri, mimeType)
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open media"))
    }
}

private fun mediaDurationHHMMSS(context: Context, uri: Uri): String {
    val ms = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(0L)
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d%02d%02d".format(h, m, s)
}

private fun questionnaireClipBaseName(
    context: Context,
    sectionCode: String?,
    questionNumber: String?,
    interviewName: String?,
    uri: Uri
): String {
    fun token(value: String?, fallback: String): String =
        value?.trim()?.replace(Regex("[^A-Za-z0-9]+"), "")?.uppercase()?.take(40)?.ifBlank { fallback } ?: fallback
    val section = token(sectionCode, "SEC")
    val question = token(questionNumber, "SEC")
    val name = token(interviewName, "INTERVIEW")
    val duration = mediaDurationHHMMSS(context, uri)
    val stamp = java.text.SimpleDateFormat("ddMMyyyyHHmm", java.util.Locale.US).format(java.util.Date())
    return listOf(section, question, name, duration, stamp).joinToString("_")
}

private fun createAudioRecorder(context: Context, file: File): MediaRecorder {
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }
    return recorder.apply {
        runCatching { setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION) }
            .onFailure { setAudioSource(MediaRecorder.AudioSource.MIC) }
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioChannels(1)
        setAudioSamplingRate(44_100)
        setAudioEncodingBitRate(96_000)
        setOutputFile(file.absolutePath)
        prepare()
    }
}

private fun readLastKnownLocation(context: Context): LocationRequest? {
    val hasFine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return null

    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    val location = providers.mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.time }
    return location?.let {
        LocationRequest(
            latitude = it.latitude,
            longitude = it.longitude,
            altitude = it.altitude.takeIf { _ -> it.hasAltitude() },
            accuracy = it.accuracy.toDouble().takeIf { _ -> it.hasAccuracy() }
        )
    }
}

private fun moveIds(ids: List<String>, index: Int, direction: Int): List<String> {
    val nextIndex = index + direction
    if (nextIndex !in ids.indices) return ids
    return ids.toMutableList().also {
        val item = it[index]
        it[index] = it[nextIndex]
        it[nextIndex] = item
    }
}

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }


internal fun queuedMediaSentence(count: Int, uploaded: Int = 0): String {
    val rest = if (count == 1) "It uploads when you're back online." else "They upload when you're back online."
    return if (uploaded > 0) "$uploaded uploaded, $count saved on this device. $rest"
    else if (count == 1) "1 file saved on this device. $rest"
    else "$count files saved on this device. $rest"
}

internal fun mediaUploadButtonLabel(
    uploading: Boolean,
    succeeded: Boolean,
    failed: Boolean,
    queued: Boolean,
    hasRecordType: Boolean
): String = when {
    uploading -> "Uploading..."
    succeeded && queued -> "Saved on device ✓"
    succeeded -> "Uploaded ✓"
    failed -> "Upload failed — tap to retry"
    !hasRecordType -> "Choose a record type"
    else -> "Upload batch"
}

private fun uploadStalledSentence(failed: Int): String =
    "$failed file${if (failed == 1) "" else "s"} didn't upload. Tap Retry or save again."

private val LocalIslandClearance = staticCompositionLocalOf<MutableState<androidx.compose.ui.unit.Dp>?> { null }

private fun richMediaUrlResolver(repository: FieldRepository): suspend (String) -> String? =
    { id -> repository.mediaItem(id).url }

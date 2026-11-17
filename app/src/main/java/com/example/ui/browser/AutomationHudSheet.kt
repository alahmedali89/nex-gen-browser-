package com.example.ui.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.automation.ActiveRecordingSession
import com.example.automation.AutomationActionType
import com.example.automation.AutomationEngine
import com.example.automation.AutomationErrorCode
import com.example.automation.AutomationHudMode
import com.example.automation.AutomationStep
import com.example.automation.ControlKind
import com.example.automation.CredentialFieldRole
import com.example.automation.DetectedPageElement
import com.example.automation.DomScanStatus
import com.example.automation.DropdownKind
import com.example.automation.InterruptedExecutionCandidate
import com.example.automation.LocatorResolutionDiagnostics
import com.example.automation.PageInspectionState
import com.example.automation.PendingCredentialReplayAuthorization
import com.example.automation.RecordingStatus
import com.example.automation.WorkflowExecutionState
import com.example.data.local.AutomationExecutionEntity
import com.example.data.local.AutomationWorkflowEntity
import com.example.data.local.CredentialVaultEntity
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material3.FilterChip
import com.example.automation.ExtractedRecord
import com.example.automation.NanoChatMessage
import com.example.automation.StepStatus
import com.example.ui.theme.EmphasizedTypography
import com.example.ui.theme.LocalReducedMotion
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationHudSheet(
    hudMode: AutomationHudMode,
    pageInspection: PageInspectionState,
    recordingSession: ActiveRecordingSession,
    instructionDraftText: String,
    instructionDraftSteps: List<AutomationStep>,
    executionState: WorkflowExecutionState?,
    workflows: List<AutomationWorkflowEntity>,
    executions: List<AutomationExecutionEntity>,
    onSelectMode: (AutomationHudMode) -> Unit,
    onScanPage: () -> Unit,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onMinimizeRecording: () -> Unit,
    onRenameRecordingDraft: (String) -> Unit,
    onEditRecordingStep: (String, String, String) -> Unit,
    onDeleteRecordingStep: (String) -> Unit,
    onSaveRecording: (String?) -> Unit,
    onDiscardRecording: () -> Unit,
    onInstructionTextChange: (String) -> Unit,
    onAddInstructions: () -> Unit,
    onClearInstructions: () -> Unit,
    onReviewInstructionSteps: () -> Unit,
    onEditInstructionStep: (String, String, AutomationActionType, String) -> Unit,
    onDeleteInstructionStep: (String) -> Unit,
    onMoveInstructionStepUp: (String) -> Unit,
    onMoveInstructionStepDown: (String) -> Unit,
    onSaveInstructionsAsWorkflow: (String) -> Unit,
    onExecuteInstructions: () -> Unit,
    onRunWorkflow: (Long) -> Unit,
    onOpenWorkflowDetails: (Long) -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenNanoRetrieverPanel: () -> Unit,
    onConfirmDestructiveExecution: () -> Unit,
    onCancelDestructiveExecution: () -> Unit,
    onClearExecutionBanner: () -> Unit,
    onDismiss: () -> Unit,
    onCancelExecution: () -> Unit = {},
    onPauseExecution: () -> Unit = {},
    onResumeExecution: () -> Unit = {},
    onEditRecordingStepDetailed: ((String, String, String, String, String) -> Unit)? = null,
    onEditInstructionStepDetailed: ((String, String, AutomationActionType, String, String, String) -> Unit)? = null,
    activeTabId: String = "",
    credentialVaultEntries: List<CredentialVaultEntity> = emptyList(),
    pendingCredentialReplayAuth: PendingCredentialReplayAuthorization? = null,
    onConfirmCredentialReplay: () -> Unit = {},
    onCancelCredentialReplay: () -> Unit = {},
    onAssignCredentialToRecordingStep: (String, String?, CredentialFieldRole) -> Unit = { _, _, _ -> },
    onOpenCredentialVault: () -> Unit = {},
    onExtendedScan: () -> Unit = {},
    onCancelScan: () -> Unit = {},
    interruptedCandidate: InterruptedExecutionCandidate? = null,
    onResumeInterrupted: () -> Unit = {},
    onRestartInterrupted: () -> Unit = {},
    onDismissInterrupted: () -> Unit = {},
    onOpenDisambiguation: (() -> Unit)? = null,
    lastLocatorDiagnostics: LocatorResolutionDiagnostics? = null,
    isScannerOutlineOverlayEnabled: Boolean = true,
    onToggleScannerOutlines: () -> Unit = {},
    onHighlightElement: (DetectedPageElement) -> Unit = {},
    nanoMessages: List<NanoChatMessage> = emptyList(),
    isNanoRunning: Boolean = false,
    extractedRecords: List<ExtractedRecord> = emptyList(),
    isRetrieverRunning: Boolean = false,
    openTabsCount: Int = 1,
    onRunScannerAgent: (String) -> Unit = {},
    onExtractCurrentPage: () -> Unit = {},
    onExtractAllTabs: () -> Unit = {},
    isHomePage: Boolean = pageInspection.domain.isBlank() || pageInspection.domain == "Start Page"
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val coroutineScope = rememberCoroutineScope()
    var isSheetCollapsed by remember { mutableStateOf(false) }

    LaunchedEffect(hudMode) {
        isSheetCollapsed = false
        if (sheetState.currentValue != SheetValue.Expanded) {
            sheetState.expand()
        }
    }

    val isCompactHomeOverview = isHomePage && hudMode == AutomationHudMode.OVERVIEW
    val usePartialExpand = !isCompactHomeOverview && sheetState.hasPartiallyExpandedState
    val isExpanded = if (usePartialExpand) {
        !isSheetCollapsed && sheetState.currentValue == SheetValue.Expanded
    } else {
        !isSheetCollapsed
    }

    if (pendingCredentialReplayAuth != null) {
        CredentialReplayAuthorizationDialog(
            authorization = pendingCredentialReplayAuth,
            onConfirm = onConfirmCredentialReplay,
            onDismiss = onCancelCredentialReplay
        )
    } else if (executionState?.isAwaitingConfirmation == true) {
        DestructiveAutomationConfirmDialog(
            workflowName = executionState.workflowName,
            destructiveSteps = executionState.pendingDestructiveSteps,
            onConfirm = onConfirmDestructiveExecution,
            onDismiss = onCancelDestructiveExecution
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("automation_hud_sheet")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            // HUD Header
            AutomationHudHeader(
                hudMode = hudMode,
                domain = pageInspection.domain,
                detectedCount = pageInspection.elements.size,
                isExpanded = isExpanded,
                onBackToOverview = { onSelectMode(AutomationHudMode.OVERVIEW) },
                onToggleExpand = {
                    coroutineScope.launch {
                        if (isExpanded) {
                            if (usePartialExpand) {
                                sheetState.partialExpand()
                            } else {
                                isSheetCollapsed = true
                            }
                        } else {
                            isSheetCollapsed = false
                            if (sheetState.hasPartiallyExpandedState &&
                                sheetState.currentValue != SheetValue.Expanded
                            ) {
                                sheetState.expand()
                            }
                        }
                    }
                },
                onClose = onDismiss
            )

            AnimatedVisibility(
                visible = !isSheetCollapsed,
                enter = expandVertically(tween(180)) + fadeIn(tween(150)),
                exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {

            // Live Execution Feedback Card (when running, paused, or finished inside HUD)
            val isExecutionRunning = executionState?.executionStatus?.isActive == true
            if (interruptedCandidate != null && !isExecutionRunning) {
                InterruptedExecutionResumeBanner(
                    candidate = interruptedCandidate,
                    onResume = onResumeInterrupted,
                    onRestart = onRestartInterrupted,
                    onDismiss = onDismissInterrupted
                )
            }
            if (executionState != null && !executionState.isAwaitingConfirmation && !executionState.isInterrupted) {
                AutomationExecutionStatusBanner(
                    executionState = executionState,
                    onCancel = onCancelExecution,
                    onPause = onPauseExecution,
                    onResume = onResumeExecution,
                    onDismiss = onClearExecutionBanner,
                    onOpenDisambiguation = onOpenDisambiguation,
                    lastLocatorDiagnostics = lastLocatorDiagnostics
                )
            }

            // Active Mode Content
            AnimatedContent(
                targetState = hudMode,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
                label = "AutomationHudModeContent"
            ) { mode ->
                when (mode) {
                    AutomationHudMode.OVERVIEW -> {
                        AutomationHudOverviewContent(
                            pageInspection = pageInspection,
                            isHomePage = isHomePage,
                            recordingStatus = recordingSession.status,
                            savedCount = workflows.size,
                            recentCount = executions.size,
                            onSelectRecord = {
                                if (recordingSession.status == RecordingStatus.IDLE ||
                                    recordingSession.status == RecordingStatus.CANCELLED ||
                                    recordingSession.status == RecordingStatus.ERROR ||
                                    (recordingSession.status == RecordingStatus.STOPPED && recordingSession.steps.isEmpty())
                                ) {
                                    onStartRecording()
                                } else {
                                    onSelectMode(AutomationHudMode.RECORDING)
                                }
                            },
                            onSelectInstructions = { onSelectMode(AutomationHudMode.INSTRUCTIONS) },
                            onScanPage = onScanPage,
                            onExtendedScan = onExtendedScan,
                            onCancelScan = onCancelScan,
                            onViewElements = { onSelectMode(AutomationHudMode.ELEMENTS) },
                            onOpenSaved = { onSelectMode(AutomationHudMode.SAVED) },
                            onCreateAutomation = { onSelectMode(AutomationHudMode.INSTRUCTIONS) },
                            onOpenRecent = { onSelectMode(AutomationHudMode.RECENT) },
                            onOpenWorkspace = onOpenWorkspace,
                            onOpenNanoRetriever = onOpenNanoRetrieverPanel
                        )
                    }

                    AutomationHudMode.RECORDING -> {
                        AutomationRecordingContent(
                            session = recordingSession,
                            activeTabId = activeTabId,
                            credentialVaultEntries = credentialVaultEntries,
                            onStartRecording = onStartRecording,
                            onPauseRecording = onPauseRecording,
                            onResumeRecording = onResumeRecording,
                            onStopRecording = onStopRecording,
                            onMinimizeHud = onMinimizeRecording,
                            onRenameDraft = onRenameRecordingDraft,
                            onEditStep = onEditRecordingStep,
                            onEditStepDetailed = onEditRecordingStepDetailed,
                            onAssignCredentialToStep = onAssignCredentialToRecordingStep,
                            onOpenCredentialVault = onOpenCredentialVault,
                            onDeleteStep = onDeleteRecordingStep,
                            onSaveRecording = onSaveRecording,
                            onDiscardRecording = onDiscardRecording
                        )
                    }

                    AutomationHudMode.INSTRUCTIONS -> {
                        AutomationInstructionsContent(
                            draftText = instructionDraftText,
                            steps = instructionDraftSteps,
                            currentDomain = pageInspection.domain,
                            isExecutionRunning = isExecutionRunning,
                            onTextChange = onInstructionTextChange,
                            onAddInstructions = onAddInstructions,
                            onClear = onClearInstructions,
                            onReviewSteps = onReviewInstructionSteps,
                            onEditStep = onEditInstructionStep,
                            onEditStepDetailed = onEditInstructionStepDetailed,
                            onDeleteStep = onDeleteInstructionStep,
                            onMoveStepUp = onMoveInstructionStepUp,
                            onMoveStepDown = onMoveInstructionStepDown,
                            onSaveWorkflow = onSaveInstructionsAsWorkflow,
                            onExecute = onExecuteInstructions
                        )
                    }

                    AutomationHudMode.ELEMENTS -> {
                        AutomationElementsContent(
                            inspection = pageInspection,
                            isOutlineEnabled = isScannerOutlineOverlayEnabled,
                            onToggleOutlines = onToggleScannerOutlines,
                            onHighlightElement = onHighlightElement,
                            onRescan = onScanPage,
                            onExtendedScan = onExtendedScan,
                            onCancelScan = onCancelScan,
                            nanoMessages = nanoMessages,
                            isNanoRunning = isNanoRunning,
                            extractedRecords = extractedRecords,
                            isRetrieverRunning = isRetrieverRunning,
                            openTabsCount = openTabsCount,
                            onRunScannerAgent = onRunScannerAgent,
                            onExtractCurrentPage = onExtractCurrentPage,
                            onExtractAllTabs = onExtractAllTabs,
                            onOpenFullAgentsPanel = onOpenNanoRetrieverPanel,
                            onUseElementInInstructions = { element ->
                                val targetName = element.accessibleName.ifBlank { element.label }
                                val isEditableDateInput = element.controlKind == ControlKind.DATE_TIME &&
                                    element.tagName == "input" &&
                                    element.editable &&
                                    !element.readonly
                                val isTextInput = element.inputType in listOf(
                                    "text", "email", "password", "search", "tel", "url",
                                    "number", "date", "datetime-local", "month", "week", "time"
                                ) ||
                                    element.tagName == "textarea" ||
                                    element.contentEditable.isNotBlank() ||
                                    element.controlKind == ControlKind.STANDARD_INPUT ||
                                    isEditableDateInput
                                val isDropdownOrSelect = element.controlKind == ControlKind.SELECT ||
                                    element.controlKind == ControlKind.CUSTOM_DROPDOWN ||
                                    element.dropdownKind != DropdownKind.NONE
                                val cmd = when {
                                    isDropdownOrSelect -> "Select \"\" in $targetName"
                                    isTextInput -> "Enter \"\" in $targetName"
                                    else -> "Click $targetName"
                                }
                                onInstructionTextChange(
                                    if (instructionDraftText.isBlank()) cmd else "$instructionDraftText\n$cmd"
                                )
                                onSelectMode(AutomationHudMode.INSTRUCTIONS)
                            }
                        )
                    }

                    AutomationHudMode.SAVED -> {
                        AutomationSavedQuickListContent(
                            workflows = workflows,
                            isExecutionRunning = isExecutionRunning,
                            onRunWorkflow = onRunWorkflow,
                            onOpenDetails = onOpenWorkflowDetails,
                            onOpenWorkspace = onOpenWorkspace
                        )
                    }

                    AutomationHudMode.RECENT -> {
                        AutomationRecentExecutionsContent(
                            executions = executions,
                            onOpenDetails = onOpenWorkflowDetails,
                            onOpenWorkspace = onOpenWorkspace
                        )
                    }
                }
            }
                }
            }
        }
    }
}

@Composable
private fun AutomationHudHeader(
    hudMode: AutomationHudMode,
    domain: String,
    detectedCount: Int,
    isExpanded: Boolean,
    onBackToOverview: () -> Unit,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("automation_hud_header")
    ) {
        if (hudMode != AutomationHudMode.OVERVIEW) {
            IconButton(
                onClick = onBackToOverview,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("automation_hud_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_navigate_back)
                )
            }
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = ApexIcons.MotionSensorActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.automation_hud_title),
                style = EmphasizedTypography.activeTabTitle,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = domain.ifBlank { "Start Page" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (detectedCount > 0) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {
                    Text(
                        text = stringResource(R.string.automation_detected_count, detectedCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (detectedCount > 0) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        IconButton(
            onClick = onToggleExpand,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("automation_hud_expand_button")
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = stringResource(
                    if (isExpanded) R.string.automation_hud_collapse else R.string.automation_hud_expand
                )
            )
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("automation_hud_close_button")
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.automation_hud_close)
            )
        }
    }
}

@Composable
private fun AutomationHudOverviewContent(
    pageInspection: PageInspectionState,
    isHomePage: Boolean = false,
    recordingStatus: RecordingStatus,
    savedCount: Int,
    recentCount: Int,
    onSelectRecord: () -> Unit,
    onSelectInstructions: () -> Unit,
    onScanPage: () -> Unit,
    onExtendedScan: () -> Unit = {},
    onCancelScan: () -> Unit = {},
    onViewElements: () -> Unit,
    onOpenSaved: () -> Unit,
    onCreateAutomation: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenNanoRetriever: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        if (isHomePage) {
            MaterialGroupListItem(
                index = 0,
                totalCount = 1,
                title = stringResource(R.string.automation_quick_saved),
                subtitle = "$savedCount workflow${if (savedCount == 1) "" else "s"} in library",
                role = Role.Button,
                onClick = onOpenSaved,
                leadingContent = {
                    SettingsIconBadge(icon = Icons.Outlined.Bookmarks, highlighted = savedCount > 0)
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.testTag("hud_quick_saved")
            )

            Button(
                onClick = onOpenWorkspace,
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hud_open_workspace_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.OpenInFull,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.automation_open_workspace))
            }
            return@Column
        }

        // A. Automation Modes
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MaterialGroupSectionHeader(title = "Automation Modes")
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val isRecActive = recordingStatus == RecordingStatus.RECORDING ||
                    recordingStatus == RecordingStatus.PAUSED
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = if (isRecActive) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.large)
                        .clickable(role = Role.Button, onClick = onSelectRecord)
                        .testTag("hud_mode_record_button")
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.RadioButtonChecked,
                            contentDescription = null,
                            tint = if (isRecActive) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                        Text(
                            text = if (isRecActive) "Recording…" else stringResource(R.string.automation_mode_record),
                            style = EmphasizedTypography.activeTabTitle,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isRecActive) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                        Text(
                            text = "Capture clicks & inputs",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isRecActive) {
                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            }
                        )
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.large)
                        .clickable(role = Role.Button, onClick = onSelectInstructions)
                        .testTag("hud_mode_instructions_button")
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = stringResource(R.string.automation_mode_instructions),
                            style = EmphasizedTypography.activeTabTitle,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Write steps in plain text",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        // B. Page Inspection
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MaterialGroupSectionHeader(title = "Page Inspection")
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DocumentScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    pageInspection.isScanning -> stringResource(R.string.automation_scanning)
                                    pageInspection.elements.isNotEmpty() ->
                                        "${pageInspection.elements.size} interactive elements ready"
                                    pageInspection.domain == "Start Page" ->
                                        "Open a webpage to detect inputs and buttons"
                                    else -> "No interactive elements scanned yet"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (pageInspection.errorMessage != null) {
                                Text(
                                    text = pageInspection.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("hud_scan_error_text")
                                )
                            } else if (pageInspection.summary.partialReason != null) {
                                Text(
                                    text = pageInspection.summary.partialReason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.testTag("hud_scan_partial_text")
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when (pageInspection.scanStatus) {
                                DomScanStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
                                DomScanStatus.PARTIAL -> MaterialTheme.colorScheme.tertiaryContainer
                                DomScanStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            modifier = Modifier.testTag("hud_scan_status_badge")
                        ) {
                            Text(
                                text = pageInspection.scanStatus.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        if (pageInspection.isScanning) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (pageInspection.elements.isNotEmpty() ||
                        pageInspection.extractedRecords.isNotEmpty() ||
                        pageInspection.summary.inaccessibleCrossOriginFrames > 0 ||
                        pageInspection.summary.inaccessibleShadowRootsFound > 0
                    ) {
                        if (pageInspection.pageIntentSummary.isNotBlank()) {
                            Text(
                                text = pageInspection.pageIntentSummary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("hud_scan_intent_summary")
                            )
                        }
                        val s = pageInspection.summary
                        val summaryParts = buildList {
                            if (s.inputFieldsFound > 0) add("Inputs: ${s.inputFieldsFound}")
                            if (s.buttonsFound > 0) add("Buttons: ${s.buttonsFound}")
                            if (s.dropdownsFound > 0) add("Dropdowns: ${s.dropdownsFound}")
                            if (s.checkboxesFound + s.radioButtonsFound > 0) add("Toggles: ${s.checkboxesFound + s.radioButtonsFound}")
                            if (s.dateTimeControlsFound > 0) add("Date/Time: ${s.dateTimeControlsFound}")
                            if (s.contentEditableFound > 0) add("Editable: ${s.contentEditableFound}")
                            if (s.ariaControlsFound > 0) add("ARIA: ${s.ariaControlsFound}")
                            if (s.shadowDomElementsFound > 0) add("Shadow DOM: ${s.shadowDomElementsFound}")
                            if (s.sameOriginIframeElementsFound > 0) add("Same-Origin Iframes: ${s.sameOriginIframeElementsFound}")
                            if (s.structuredRecordsFound > 0) add("Extracted Rows: ${s.structuredRecordsFound}")
                            if (s.inaccessibleCrossOriginFrames > 0) add("Cross-Origin Frames (Blocked): ${s.inaccessibleCrossOriginFrames}")
                            if (s.inaccessibleShadowRootsFound > 0) add("Closed Shadow Roots: ${s.inaccessibleShadowRootsFound}")
                        }
                        if (summaryParts.isNotEmpty()) {
                            Text(
                                text = summaryParts.joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("hud_scan_summary_text")
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilledTonalButton(
                            onClick = onScanPage,
                            enabled = !pageInspection.isScanning,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hud_scan_page_button")
                        ) {
                            Text(stringResource(R.string.automation_scan_page), maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onViewElements,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hud_view_elements_button")
                        ) {
                            Text(
                                text = stringResource(R.string.automation_view_elements),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onExtendedScan,
                            enabled = !pageInspection.isScanning && pageInspection.domain != "Start Page",
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hud_extended_scan_button")
                        ) {
                            Text("Extended Scan", maxLines = 1)
                        }
                        if (pageInspection.isScanning) {
                            OutlinedButton(
                                onClick = onCancelScan,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("hud_cancel_scan_button")
                            ) {
                                Text("Cancel Scan", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        // C. Quick Actions
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MaterialGroupSectionHeader(title = "Quick Actions")
            Column(verticalArrangement = Arrangement.spacedBy(GroupedListSpacing)) {
                MaterialGroupListItem(
                    index = 0,
                    totalCount = 4,
                    title = stringResource(R.string.automation_quick_saved),
                    subtitle = "$savedCount workflow${if (savedCount == 1) "" else "s"} in library",
                    role = Role.Button,
                    onClick = onOpenSaved,
                    leadingContent = {
                        SettingsIconBadge(icon = Icons.Outlined.Bookmarks, highlighted = savedCount > 0)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.testTag("hud_quick_saved")
                )
                MaterialGroupListItem(
                    index = 1,
                    totalCount = 4,
                    title = stringResource(R.string.automation_quick_create),
                    subtitle = "Compose step-by-step instructions",
                    role = Role.Button,
                    onClick = onCreateAutomation,
                    leadingContent = {
                        SettingsIconBadge(icon = Icons.Filled.Add)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.testTag("hud_quick_create")
                )
                MaterialGroupListItem(
                    index = 2,
                    totalCount = 4,
                    title = stringResource(R.string.automation_quick_recent),
                    subtitle = "$recentCount recent run${if (recentCount == 1) "" else "s"}",
                    role = Role.Button,
                    onClick = onOpenRecent,
                    leadingContent = {
                        SettingsIconBadge(icon = Icons.Outlined.History)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.testTag("hud_quick_recent")
                )
                MaterialGroupListItem(
                    index = 3,
                    totalCount = 4,
                    title = "Nano & Retriever Agents",
                    subtitle = "Merged DOM Scanner agent & multi-tab table extractor",
                    role = Role.Button,
                    onClick = onOpenNanoRetriever,
                    leadingContent = {
                        SettingsIconBadge(icon = Icons.Outlined.AutoAwesome)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.testTag("hud_quick_nano_retriever")
                )
            }
        }

        // D. Main Action
        Button(
            onClick = onOpenWorkspace,
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("hud_open_workspace_button")
        ) {
            Icon(
                imageVector = Icons.Outlined.OpenInFull,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.automation_open_workspace))
        }
    }
}

@Composable
private fun AutomationRecordingContent(
    session: ActiveRecordingSession,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onMinimizeHud: () -> Unit,
    onRenameDraft: (String) -> Unit,
    onEditStep: (String, String, String) -> Unit,
    onEditStepDetailed: ((String, String, String, String, String) -> Unit)? = null,
    onDeleteStep: (String) -> Unit,
    onSaveRecording: (String?) -> Unit,
    onDiscardRecording: () -> Unit,
    activeTabId: String = "",
    credentialVaultEntries: List<CredentialVaultEntity> = emptyList(),
    onAssignCredentialToStep: (String, String?, CredentialFieldRole) -> Unit = { _, _, _ -> },
    onOpenCredentialVault: () -> Unit = {}
) {
    val reducedMotion = LocalReducedMotion.current
    val pulseTransition = rememberInfiniteTransition(label = "RecordingDotPulse")
    val dotAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (!reducedMotion && session.status == RecordingStatus.RECORDING) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "RecordingDotAlpha"
    )
    var editingStep by remember { mutableStateOf<AutomationStep?>(null) }

    editingStep?.let { target ->
        EditAutomationStepDialog(
            step = target,
            allowActionTypeChange = false,
            credentialVaultEntries = credentialVaultEntries,
            expectedOrigin = target.expectedOrigin.ifBlank { session.currentOrigin.ifBlank { session.targetUrl } },
            onAssignCredential = { credId, role ->
                onAssignCredentialToStep(target.id, credId, role)
            },
            onOpenCredentialVault = onOpenCredentialVault,
            onSave = { desc, _, valText ->
                onEditStep(target.id, desc, valText)
                editingStep = null
            },
            onSaveDetailed = { desc, _, valText, targetLabel, selector ->
                if (onEditStepDetailed != null) {
                    onEditStepDetailed(target.id, desc, valText, targetLabel, selector)
                } else {
                    onEditStep(target.id, desc, valText)
                }
                editingStep = null
            },
            onDismiss = { editingStep = null }
        )
    }

    val statusCardColor by animateColorAsState(
        targetValue = when (session.status) {
            RecordingStatus.RECORDING, RecordingStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
            RecordingStatus.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer
            RecordingStatus.STOPPED -> MaterialTheme.colorScheme.primaryContainer
            RecordingStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
            RecordingStatus.IDLE -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "RecordingStatusCardColor"
    )
    val statusContentColor = when (session.status) {
        RecordingStatus.RECORDING, RecordingStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        RecordingStatus.PAUSED -> MaterialTheme.colorScheme.onTertiaryContainer
        RecordingStatus.STOPPED -> MaterialTheme.colorScheme.onPrimaryContainer
        RecordingStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        RecordingStatus.IDLE -> MaterialTheme.colorScheme.onSurface
    }
    val isInAnotherTab = (session.status == RecordingStatus.RECORDING || session.status == RecordingStatus.PAUSED) &&
        activeTabId.isNotBlank() &&
        session.initiatingTabId.isNotBlank() &&
        activeTabId != session.initiatingTabId

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        // Distinct Recording / Paused / Stopped / Cancelled / Error Status Banner
        Surface(
            shape = MaterialTheme.shapes.large,
            color = statusCardColor,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("recording_status_card")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .alpha(dotAlpha)
                        .background(
                            when (session.status) {
                                RecordingStatus.RECORDING, RecordingStatus.ERROR -> MaterialTheme.colorScheme.error
                                RecordingStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
                                RecordingStatus.STOPPED -> MaterialTheme.colorScheme.primary
                                RecordingStatus.CANCELLED, RecordingStatus.IDLE -> MaterialTheme.colorScheme.outline
                            }
                        )
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (session.status) {
                            RecordingStatus.RECORDING -> "Recording actions…"
                            RecordingStatus.PAUSED -> "Recording paused"
                            RecordingStatus.STOPPED -> "Recording stopped — review & save"
                            RecordingStatus.CANCELLED -> "Recording cancelled"
                            RecordingStatus.ERROR -> "Recording unavailable"
                            RecordingStatus.IDLE -> "Ready to record"
                        },
                        style = EmphasizedTypography.activeTabTitle,
                        fontWeight = FontWeight.SemiBold,
                        color = statusContentColor
                    )
                    val subtitleText = when {
                        session.recordingError != null -> session.recordingError.message
                        isInAnotherTab -> "Recording continues in ${session.targetDomain.ifBlank { "another" }} tab (${session.steps.size} captured)"
                        else -> "${session.steps.size} action${if (session.steps.size == 1) "" else "s"} captured" +
                            if (session.targetDomain.isNotBlank()) " on ${session.targetDomain}" else ""
                    }
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusContentColor.copy(alpha = 0.85f)
                    )
                }
            }
        }

        // Control Buttons based on State
        when (session.status) {
            RecordingStatus.IDLE, RecordingStatus.CANCELLED, RecordingStatus.ERROR -> {
                if (session.steps.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onDiscardRecording,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("recording_discard_button")
                        ) {
                            Text(stringResource(R.string.automation_discard))
                        }
                        Button(
                            onClick = onStartRecording,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("recording_start_button")
                        ) {
                            Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Start Recording")
                        }
                    }
                } else {
                    Button(
                        onClick = onStartRecording,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recording_start_button")
                    ) {
                        Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Recording")
                    }
                }
            }
            RecordingStatus.RECORDING -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilledTonalButton(
                        onClick = onPauseRecording,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("recording_pause_button")
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.automation_pause))
                    }
                    Button(
                        onClick = onStopRecording,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("recording_stop_button")
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.automation_stop))
                    }
                }
                OutlinedButton(
                    onClick = onMinimizeHud,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recording_minimize_button")
                ) {
                    Text(stringResource(R.string.automation_hud_minimize) + " to interact with website")
                }
            }
            RecordingStatus.PAUSED -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onResumeRecording,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("recording_resume_button")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.automation_resume))
                    }
                    FilledTonalButton(
                        onClick = onStopRecording,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("recording_stop_button")
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.automation_stop))
                    }
                }
                OutlinedButton(
                    onClick = onMinimizeHud,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recording_minimize_button")
                ) {
                    Text(stringResource(R.string.automation_hud_minimize))
                }
            }
            RecordingStatus.STOPPED -> {
                OutlinedTextField(
                    value = session.suggestedName,
                    onValueChange = onRenameDraft,
                    label = { Text("Automation name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recording_name_input")
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onDiscardRecording,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("recording_discard_button")
                    ) {
                        Text(stringResource(R.string.automation_discard))
                    }
                    Button(
                        onClick = { onSaveRecording(session.suggestedName) },
                        enabled = session.steps.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("recording_save_button")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.automation_save))
                    }
                }
            }
        }

        // Captured Steps List
        MaterialGroupSectionHeader(title = "Captured Actions (${session.steps.size})")
        if (session.steps.isEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Tap Minimize or interact with fields, checkboxes, links, and buttons on the webpage to capture steps live.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(GroupedListSpacing)) {
                session.steps.forEachIndexed { idx, step ->
                    val shape = groupedListItemShape(idx, session.steps.size)
                    Surface(
                        shape = shape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recorded_step_row_${idx + 1}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${idx + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = step.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (step.isCredentialStep) {
                                    val boundCred = credentialVaultEntries.firstOrNull {
                                        it.credentialId == step.credentialReferenceId
                                    }
                                    val roleLabel = (step.credentialFieldRole ?: CredentialFieldRole.PASSWORD).label
                                    val badgeText = when {
                                        step.credentialReferenceId.isNullOrBlank() ->
                                            "Requires saved credential ($roleLabel) • Tap Edit to bind"
                                        boundCred == null ->
                                            "Missing credential (deleted from Vault)"
                                        else ->
                                            "Bound to ${boundCred.label} (${boundCred.usernameIdentifier}) • $roleLabel"
                                    }
                                    val isWarning = step.credentialReferenceId.isNullOrBlank() || boundCred == null
                                    Text(
                                        text = badgeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isWarning) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        modifier = Modifier.testTag("recorded_step_credential_badge_${idx + 1}")
                                    )
                                } else if (step.value.isNotBlank()) {
                                    Text(
                                        text = "Value: ${step.value}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = { editingStep = step },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("recorded_step_edit_${idx + 1}")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.automation_edit),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { onDeleteStep(step.id) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("recorded_step_delete_${idx + 1}")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteOutline,
                                    contentDescription = stringResource(R.string.automation_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationInstructionsContent(
    draftText: String,
    steps: List<AutomationStep>,
    currentDomain: String,
    isExecutionRunning: Boolean = false,
    onTextChange: (String) -> Unit,
    onAddInstructions: () -> Unit,
    onClear: () -> Unit,
    onReviewSteps: () -> Unit,
    onEditStep: (String, String, AutomationActionType, String) -> Unit,
    onEditStepDetailed: ((String, String, AutomationActionType, String, String, String) -> Unit)? = null,
    onDeleteStep: (String) -> Unit,
    onMoveStepUp: (String) -> Unit,
    onMoveStepDown: (String) -> Unit,
    onSaveWorkflow: (String) -> Unit,
    onExecute: () -> Unit
) {
    var editingStep by remember { mutableStateOf<AutomationStep?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveWorkflowName by remember(currentDomain) {
        mutableStateOf(if (currentDomain == "Start Page") "Manual Automation" else "$currentDomain Automation")
    }

    editingStep?.let { target ->
        EditAutomationStepDialog(
            step = target,
            allowActionTypeChange = true,
            onSave = { desc, actionType, valText ->
                onEditStep(target.id, desc, actionType, valText)
                editingStep = null
            },
            onSaveDetailed = { desc, actionType, valText, targetLabel, selector ->
                if (onEditStepDetailed != null) {
                    onEditStepDetailed(target.id, desc, actionType, valText, targetLabel, selector)
                } else {
                    onEditStep(target.id, desc, actionType, valText)
                }
                editingStep = null
            },
            onDismiss = { editingStep = null }
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.automation_save)) },
            text = {
                OutlinedTextField(
                    value = saveWorkflowName,
                    onValueChange = { saveWorkflowName = it },
                    label = { Text("Workflow name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("instructions_save_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveWorkflow(saveWorkflowName)
                        showSaveDialog = false
                    },
                    modifier = Modifier.testTag("instructions_save_confirm_button")
                ) {
                    Text(stringResource(R.string.automation_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draftText,
            onValueChange = onTextChange,
            label = { Text(stringResource(R.string.automation_mode_instructions)) },
            placeholder = { Text(stringResource(R.string.automation_instructions_placeholder)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("instructions_input_field")
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilledTonalButton(
                onClick = onAddInstructions,
                enabled = draftText.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("instructions_add_button")
            ) {
                Text(stringResource(R.string.automation_add_instructions), maxLines = 1)
            }
            OutlinedButton(
                onClick = onClear,
                enabled = draftText.isNotBlank() || steps.isNotEmpty(),
                modifier = Modifier.testTag("instructions_clear_button")
            ) {
                Text(stringResource(R.string.automation_clear))
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = onReviewSteps,
                enabled = draftText.isNotBlank() || steps.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("instructions_review_button")
            ) {
                Text(stringResource(R.string.automation_review_steps), maxLines = 1)
            }
            Button(
                onClick = onExecute,
                enabled = !isExecutionRunning && (draftText.isNotBlank() || steps.isNotEmpty()),
                modifier = Modifier
                    .weight(1f)
                    .testTag("instructions_execute_button")
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.automation_execute))
            }
        }

        if (steps.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                MaterialGroupSectionHeader(
                    title = "Workflow Steps (${steps.size})",
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier.testTag("instructions_save_button")
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.automation_save))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(GroupedListSpacing)) {
                steps.forEachIndexed { idx, step ->
                    val shape = groupedListItemShape(idx, steps.size)
                    Surface(
                        shape = shape,
                        color = if (step.isDestructive) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("instruction_step_row_${idx + 1}")
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "${idx + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = step.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = buildString {
                                            append(step.actionType.label)
                                            if (step.value.isNotBlank()) append(" • \"${step.value}\"")
                                            if (step.isDestructive) append(" • Confirmation required")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (step.isDestructive) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                IconButton(
                                    onClick = { onMoveStepUp(step.id) },
                                    enabled = idx > 0,
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { onMoveStepDown(step.id) },
                                    enabled = idx < steps.lastIndex,
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { editingStep = step },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.automation_edit), modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { onDeleteStep(step.id) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = stringResource(R.string.automation_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationElementsContent(
    inspection: PageInspectionState,
    isOutlineEnabled: Boolean = true,
    onToggleOutlines: () -> Unit = {},
    onHighlightElement: (DetectedPageElement) -> Unit = {},
    onRescan: () -> Unit,
    onExtendedScan: () -> Unit = {},
    onCancelScan: () -> Unit = {},
    nanoMessages: List<NanoChatMessage> = emptyList(),
    isNanoRunning: Boolean = false,
    extractedRecords: List<ExtractedRecord> = emptyList(),
    isRetrieverRunning: Boolean = false,
    openTabsCount: Int = 1,
    onRunScannerAgent: (String) -> Unit = {},
    onExtractCurrentPage: () -> Unit = {},
    onExtractAllTabs: () -> Unit = {},
    onOpenFullAgentsPanel: () -> Unit = {},
    onUseElementInInstructions: (DetectedPageElement) -> Unit
) {
    val displayRecords = remember(extractedRecords, inspection.extractedRecords) {
        extractedRecords.ifEmpty { inspection.extractedRecords }
    }
    var scannerQuery by remember { mutableStateOf("") }
    var showExtractedTab by remember { mutableStateOf(false) }
    val latestAgentReply = remember(nanoMessages) {
        nanoMessages.lastOrNull { !it.isUser && it.id != "welcome_nano" }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Detected Elements (${inspection.elements.size})",
                    style = EmphasizedTypography.activeTabTitle,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Status: ${inspection.scanStatus.name}" +
                        (if (displayRecords.isNotEmpty()) " • ${displayRecords.size} extracted" else "") +
                        (if (inspection.summary.dynamicUpdatesProcessed > 0) " • Live updates: ${inspection.summary.dynamicUpdatesProcessed}" else "") +
                        (if (inspection.isDynamicObserverActive) " • Observer active" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("elements_scan_status_subtitle")
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = onToggleOutlines,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("elements_toggle_outlines_button")
                ) {
                    Icon(
                        imageVector = if (isOutlineEnabled) {
                            Icons.Outlined.Visibility
                        } else {
                            Icons.Outlined.VisibilityOff
                        },
                        contentDescription = if (isOutlineEnabled) {
                            "Hide DOM element outlines"
                        } else {
                            "Show DOM element outlines"
                        },
                        tint = if (isOutlineEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (inspection.isScanning) {
                    OutlinedButton(
                        onClick = onCancelScan,
                        modifier = Modifier.testTag("elements_cancel_scan_button")
                    ) {
                        Text("Cancel")
                    }
                } else {
                    OutlinedButton(
                        onClick = onExtendedScan,
                        enabled = inspection.domain != "Start Page",
                        modifier = Modifier.testTag("elements_extended_scan_button")
                    ) {
                        Text("Extended")
                    }
                }
                FilledTonalButton(
                    onClick = onRescan,
                    enabled = !inspection.isScanning,
                    modifier = Modifier.testTag("elements_rescan_button")
                ) {
                    Text(if (inspection.isScanning) "Scanning…" else stringResource(R.string.automation_scan_page))
                }
            }
        }

        // Merged Nano Multi-Agent + Retriever Extraction Controls inside DOM Scanner
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scanner_unified_intelligence_card")
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "DOM Agent & Structured Extractor",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = onOpenFullAgentsPanel,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("scanner_open_agents_panel_button")
                    ) {
                        Text("Full Panel", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (inspection.pageIntentSummary.isNotBlank()) {
                    Text(
                        text = inspection.pageIntentSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("scanner_page_intent_chip")
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = scannerQuery,
                        onValueChange = { scannerQuery = it },
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = "Ask DOM agent or click/extract target…",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scanner_agent_query_input")
                    )
                    FilledTonalIconButton(
                        onClick = {
                            val q = scannerQuery.trim()
                            if (q.isNotEmpty() && !isNanoRunning) {
                                onRunScannerAgent(q)
                                scannerQuery = ""
                            }
                        },
                        enabled = scannerQuery.isNotBlank() && !isNanoRunning,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("scanner_run_agent_button")
                    ) {
                        if (isNanoRunning) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Run DOM agent task"
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilledTonalButton(
                        onClick = {
                            onExtractCurrentPage()
                            showExtractedTab = true
                        },
                        enabled = !isRetrieverRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scanner_extract_page_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.TableRows,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isRetrieverRunning) "Extracting…" else "Extract Page", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            onExtractAllTabs()
                            showExtractedTab = true
                        },
                        enabled = !isRetrieverRunning && openTabsCount >= 1,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scanner_extract_all_tabs_button")
                    ) {
                        Text("All Tabs ($openTabsCount)", maxLines = 1)
                    }
                }

                if (latestAgentReply != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("scanner_agent_result_card")
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(10.dp)
                        ) {
                            if (latestAgentReply.steps.isNotEmpty()) {
                                val stepSummary = latestAgentReply.steps.joinToString(" → ") { st ->
                                    val mark = if (st.status == StepStatus.COMPLETED) "✓" else "…"
                                    "${st.role.badge} $mark"
                                }
                                Text(
                                    text = stepSummary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                text = latestAgentReply.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }

        // Toggle between Interactive DOM Controls & Extracted Structured Content
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = !showExtractedTab,
                onClick = { showExtractedTab = false },
                label = { Text("Elements (${inspection.elements.size})") },
                modifier = Modifier.testTag("scanner_tab_elements")
            )
            FilterChip(
                selected = showExtractedTab,
                onClick = { showExtractedTab = true },
                label = { Text("Extracted (${displayRecords.size})") },
                modifier = Modifier.testTag("scanner_tab_extracted")
            )
        }

        if (inspection.summary.partialReason != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("elements_partial_banner")
            ) {
                Text(
                    text = inspection.summary.partialReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        if (inspection.inaccessibleFrames.isNotEmpty() || inspection.summary.inaccessibleShadowRootsFound > 0) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("elements_boundary_notice")
            ) {
                val msg = buildList {
                    if (inspection.inaccessibleFrames.isNotEmpty()) {
                        add("${inspection.inaccessibleFrames.size} cross-origin/inaccessible frame(s) skipped")
                    }
                    if (inspection.summary.inaccessibleShadowRootsFound > 0) {
                        add("${inspection.summary.inaccessibleShadowRootsFound} closed shadow root(s) skipped")
                    }
                }.joinToString(" • ")
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        if (showExtractedTab) {
            if (displayRecords.isEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No structured items extracted yet. Tap 'Extract Page' or 'All Tabs' above to capture structured content.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(GroupedListSpacing),
                    modifier = Modifier.testTag("scanner_extracted_records_list")
                ) {
                    displayRecords.forEachIndexed { idx, rec ->
                        MaterialGroupListItem(
                            index = idx,
                            totalCount = displayRecords.size,
                            title = rec.primaryField,
                            subtitle = buildString {
                                append(rec.secondaryValue)
                                append(" • ")
                                append(rec.tabTitle)
                                if (rec.matchedSelector.isNotBlank()) {
                                    append(" [${rec.matchedSelector}]")
                                }
                            },
                            leadingContent = {
                                SettingsIconBadge(icon = Icons.Outlined.TableRows)
                            },
                            modifier = Modifier.testTag("scanner_extracted_item_$idx")
                        )
                    }
                }
            }
        } else if (inspection.elements.isEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = inspection.errorMessage ?: "No interactive buttons, inputs, or links detected on this page yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(GroupedListSpacing)) {
                inspection.elements.forEachIndexed { idx, element ->
                    val displayName = element.accessibleName.ifBlank { element.label }
                    val confidencePct = (element.locatorConfidence * 100f).toInt().coerceIn(0, 100)
                    val isHighlighted = inspection.highlightedElementSelector.isNotBlank() &&
                        inspection.highlightedElementSelector == element.selector
                    val stateBadges = buildList {
                        add("${element.tagName}${if (element.inputType.isNotBlank()) "[${element.inputType}]" else ""}")
                        if (element.role.isNotBlank() && element.role != element.tagName) {
                            add("role=${element.role}")
                        }
                        add("${element.locatorStrategy.name} ($confidencePct%)")
                        if (element.controlKind == ControlKind.DATE_TIME || element.dateTimeMetadata != null) {
                            add("Date/Calendar")
                        }
                        if (element.dropdownKind != DropdownKind.NONE) {
                            add(element.dropdownKind.label)
                        }
                        if (element.radioGroupName.isNotBlank()) {
                            add("Group: ${element.radioGroupName}")
                        }
                        if (element.required) add("Required")
                        if (element.disabled) add("Disabled")
                        if (element.readonly) add("Read-Only")
                        if (element.isInShadowDom) add("Shadow DOM (${element.shadowHostPath.joinToString(" >>> ")})")
                        if (element.isInSameOriginIframe) add("Iframe (${element.framePath.joinToString(" >> ")})")
                        if (!element.inViewport && element.visible) add("Off-screen")
                        if (element.isLocatorAmbiguous) add("Ambiguous")
                        if (element.stale) add("Stale")
                        if (element.sensitiveCategory.isSensitive) add("Sensitive (${element.sensitiveCategory.name} excluded)")
                        if (element.formGroupId.isNotBlank()) add("Form: ${element.formGroupId}")
                    }
                    MaterialGroupListItem(
                        index = idx,
                        totalCount = inspection.elements.size,
                        title = displayName,
                        subtitle = "${stateBadges.joinToString(" • ")}\nSelector: ${element.selector}",
                        role = Role.Button,
                        onClick = { onUseElementInInstructions(element) },
                        leadingContent = {
                            SettingsIconBadge(icon = Icons.Outlined.ListAlt)
                        },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TextButton(
                                    onClick = { onHighlightElement(element) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("detected_element_highlight_btn_$idx")
                                ) {
                                    Text(
                                        text = if (isHighlighted) "Outlined" else "Outline",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isHighlighted) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.secondary
                                        }
                                    )
                                }
                                Text(
                                    text = if (element.stale) "Stale" else "Use",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (element.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        modifier = Modifier.testTag("detected_element_item_$idx")
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomationSavedQuickListContent(
    workflows: List<AutomationWorkflowEntity>,
    isExecutionRunning: Boolean = false,
    onRunWorkflow: (Long) -> Unit,
    onOpenDetails: (Long) -> Unit,
    onOpenWorkspace: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (workflows.isEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.automation_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.automation_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val previewList = workflows.take(6)
            Column(verticalArrangement = Arrangement.spacedBy(GroupedListSpacing)) {
                previewList.forEachIndexed { idx, wf ->
                    MaterialGroupListItem(
                        index = idx,
                        totalCount = previewList.size,
                        title = wf.name,
                        subtitle = "${wf.domain} • ${wf.stepCount} steps • ${wf.type}",
                        role = Role.Button,
                        onClick = { onOpenDetails(wf.id) },
                        leadingContent = {
                            SettingsIconBadge(icon = ApexIcons.MotionSensorActive)
                        },
                        trailingContent = {
                            FilledTonalIconButton(
                                onClick = { onRunWorkflow(wf.id) },
                                enabled = !isExecutionRunning,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.automation_run)
                                )
                            }
                        }
                    )
                }
            }
        }

        Button(onClick = onOpenWorkspace, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.automation_open_workspace))
        }
    }
}

@Composable
private fun AutomationRecentExecutionsContent(
    executions: List<AutomationExecutionEntity>,
    onOpenDetails: (Long) -> Unit,
    onOpenWorkspace: () -> Unit
) {
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (executions.isEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No recent automation executions yet. Run a saved automation or execute manual instructions to view results here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            val preview = executions.take(8)
            Column(verticalArrangement = Arrangement.spacedBy(GroupedListSpacing)) {
                preview.forEachIndexed { idx, exec ->
                    val succeeded = exec.status == "COMPLETED"
                    MaterialGroupListItem(
                        index = idx,
                        totalCount = preview.size,
                        title = exec.workflowName,
                        subtitle = "${exec.status} • ${exec.completedSteps}/${exec.totalSteps} steps • ${formatter.format(Date(exec.startedAt))}",
                        role = Role.Button,
                        onClick = { onOpenDetails(exec.workflowId) },
                        leadingContent = {
                            Icon(
                                imageVector = if (succeeded) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = if (succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    )
                }
            }
        }

        OutlinedButton(onClick = onOpenWorkspace, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.automation_open_workspace))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationExecutionStatusBanner(
    executionState: WorkflowExecutionState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onOpenDisambiguation: (() -> Unit)? = null,
    lastLocatorDiagnostics: LocatorResolutionDiagnostics? = null
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    val isRunning = executionState.status == "RUNNING"
    val isPaused = executionState.status == "PAUSED"
    val isInterrupted = executionState.status == "INTERRUPTED"
    val isActive = isRunning || isPaused
    val isFailed = executionState.status == "FAILED"
    val isCancelled = executionState.status == "CANCELLED"
    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .testTag("automation_execution_banner")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = when {
                        isFailed -> Icons.Filled.ErrorOutline
                        isCancelled -> Icons.Filled.Stop
                        isPaused || isInterrupted -> Icons.Filled.Pause
                        isRunning -> ApexIcons.MotionSensorActive
                        else -> Icons.Filled.CheckCircle
                    },
                    contentDescription = executionState.status,
                    tint = when {
                        isFailed -> MaterialTheme.colorScheme.error
                        isCancelled -> MaterialTheme.colorScheme.onSurfaceVariant
                        isPaused || isInterrupted -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${executionState.workflowName} • ${executionState.status}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("automation_execution_status_title")
                    )
                    if (isActive && executionState.totalSteps > 0) {
                        val displayStepNum = (executionState.currentStepIndex + 1)
                            .coerceIn(1, executionState.totalSteps)
                        val actionPrefix = executionState.currentActionLabel
                            .takeIf { it.isNotBlank() }
                            ?.let { "[$it] " }
                            .orEmpty()
                        val stepText = executionState.currentStepDescription.ifBlank {
                            executionState.verificationMessage
                        }
                        Text(
                            text = "Step $displayStepNum/${executionState.totalSteps}: $actionPrefix$stepText",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("automation_execution_current_step")
                        )
                    }
                    if (executionState.verificationMessage.isNotBlank()) {
                        Text(
                            text = executionState.verificationMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isFailed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.testTag("automation_execution_message")
                        )
                    }
                    if (executionState.recoveryNotice.isNotBlank()) {
                        Text(
                            text = executionState.recoveryNotice,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.testTag("automation_execution_recovery_notice")
                        )
                    }
                    val activeDomHighlight = executionState.activeElementHighlight
                    if (activeDomHighlight != null && activeDomHighlight.selector.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .fillMaxWidth()
                                .testTag("automation_execution_active_dom_target")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DocumentScanner,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = buildString {
                                        append("DOM Target: ")
                                        if (activeDomHighlight.tagName.isNotBlank()) {
                                            append("<${activeDomHighlight.tagName}> ")
                                        }
                                        append(activeDomHighlight.selector)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (executionState.activeBranchLabel.isNotBlank() ||
                        executionState.activeSubWorkflowName.isNotBlank() ||
                        executionState.recoveryAttemptsCount > 0 ||
                        executionState.variableSnapshots.isNotEmpty()
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag("automation_execution_phase6_telemetry")
                        ) {
                            if (executionState.activeSubWorkflowName.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = "Sub: ${executionState.activeSubWorkflowName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (executionState.activeBranchLabel.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = executionState.activeBranchLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (executionState.recoveryAttemptsCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(
                                        text = "Recovery x${executionState.recoveryAttemptsCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            executionState.variableSnapshots.take(3).forEach { v ->
                                val shown = if (v.isMasked) "••••" else v.previewValue
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                                ) {
                                    Text(
                                        text = "\${${v.name}}=$shown",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                if (!isActive) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("automation_dismiss_execution_button")
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }
            }
            if (isActive && (onPause != null || onResume != null || onCancel != null)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRunning && onPause != null) {
                        FilledTonalButton(
                            onClick = onPause,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("automation_pause_execution_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.automation_pause))
                        }
                    } else if (isPaused && onResume != null) {
                        Button(
                            onClick = onResume,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("automation_resume_execution_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.automation_resume))
                        }
                    }
                    if (onCancel != null) {
                        OutlinedButton(
                            onClick = onCancel,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("automation_cancel_execution_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
            if (isActive && executionState.totalSteps > 0) {
                LinearProgressIndicator(
                    progress = {
                        (maxOf(executionState.completedSteps, executionState.currentStepIndex).toFloat() /
                            executionState.totalSteps.toFloat())
                            .coerceIn(0.05f, 1f)
                    },
                    color = if (isPaused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("automation_execution_progress")
                )
            }
            if (executionState.errorCode == AutomationErrorCode.ELEMENT_AMBIGUOUS &&
                executionState.ambiguousCandidates.isNotEmpty() &&
                onOpenDisambiguation != null
            ) {
                Button(
                    onClick = onOpenDisambiguation,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("automation_choose_target_button")
                ) {
                    Text("Choose Target (${executionState.ambiguousCandidates.size})")
                }
            }
            val diagnostics = executionState.lastLocatorDiagnostics ?: lastLocatorDiagnostics
            if (diagnostics != null) {
                TextButton(
                    onClick = { showDiagnostics = !showDiagnostics },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("automation_diagnostics_toggle")
                ) {
                    Icon(
                        imageVector = if (showDiagnostics) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showDiagnostics) "Hide Resolution Diagnostics" else "Resolution Diagnostics",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (showDiagnostics) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("automation_diagnostics_panel")
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(10.dp)
                        ) {
                            Text(
                                text = "Locator: ${diagnostics.recordedLocator.ifBlank { diagnostics.targetLabel }}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Total ${diagnostics.totalMatches} • Visible ${diagnostics.visibleMatches} • Interactable ${diagnostics.interactableMatches} • Outcome: ${diagnostics.finalOutcome.ifBlank { "NONE" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (diagnostics.resolvedSelector.isNotBlank()) {
                                Text(
                                    text = "Resolved: ${diagnostics.resolvedSelector}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            diagnostics.attemptedCandidates.take(6).forEach { attempt ->
                                val statusLabel = if (attempt.selected) "selected ✓" else attempt.rejectedReason.ifBlank { "skipped" }
                                Text(
                                    text = "• [${attempt.strategy}] ${attempt.selector} → matched ${attempt.matchCount} (${attempt.visibleCount} visible, $statusLabel)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            if (executionState.stepLogs.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                executionState.stepLogs.takeLast(4).forEach { log ->
                    Text(
                        text = "• $log",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun DestructiveAutomationConfirmDialog(
    workflowName: String,
    destructiveSteps: List<AutomationStep>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("Confirm Sensitive Action") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "\"$workflowName\" contains actions that submit data or modify remote state:",
                    style = MaterialTheme.typography.bodyMedium
                )
                destructiveSteps.take(4).forEach { step ->
                    Text(
                        text = "• ${step.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.testTag("destructive_confirm_button")
            ) {
                Text(stringResource(R.string.automation_execute))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("destructive_cancel_button")
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun CredentialReplayAuthorizationDialog(
    authorization: PendingCredentialReplayAuthorization,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Authorize Saved Credential Replay",
                modifier = Modifier.testTag("credential_replay_auth_title")
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Workflow \"${authorization.workflowName}\" requests permission to fill saved credentials on:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = authorization.displayOrigin.ifBlank { authorization.websiteOrigin },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.testTag("credential_replay_origin_text")
                        )
                        Text(
                            text = "Canonical origin: ${authorization.websiteOrigin}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
                authorization.items.forEach { item ->
                    Text(
                        text = "• Step ${item.stepOrder}: Fill ${item.fieldRole.label} using '${item.credentialLabel}' (${item.usernameIdentifier})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (authorization.hasSubmissionStep) {
                    Text(
                        text = "Note: This workflow also includes a form submission or confirmation step.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("credential_replay_confirm_button")
            ) {
                Text("Authorize & Authenticate")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("credential_replay_cancel_button")
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun EditAutomationStepDialog(
    step: AutomationStep?,
    allowActionTypeChange: Boolean,
    onSave: (description: String, actionType: AutomationActionType, value: String) -> Unit,
    onDismiss: () -> Unit,
    onSaveDetailed: ((description: String, actionType: AutomationActionType, value: String, targetLabel: String, selector: String) -> Unit)? = null,
    credentialVaultEntries: List<CredentialVaultEntity> = emptyList(),
    expectedOrigin: String = "",
    onAssignCredential: ((credentialId: String?, role: CredentialFieldRole) -> Unit)? = null,
    onOpenCredentialVault: (() -> Unit)? = null
) {
    var description by remember(step?.id) { mutableStateOf(step?.description.orEmpty()) }
    var targetLabel by remember(step?.id) { mutableStateOf(step?.targetLabel.orEmpty()) }
    var selector by remember(step?.id) { mutableStateOf(step?.selector.orEmpty()) }
    var value by remember(step?.id) { mutableStateOf(if (step?.isCredentialStep == true) "" else step?.value.orEmpty()) }
    var selectedType by remember(step?.id) { mutableStateOf(step?.actionType ?: AutomationActionType.CLICK) }
    var selectedCredentialId by remember(step?.id) { mutableStateOf(step?.credentialReferenceId) }
    var selectedFieldRole by remember(step?.id) {
        mutableStateOf(step?.credentialFieldRole ?: CredentialFieldRole.PASSWORD)
    }
    var useSavedCredential by remember(step?.id) {
        mutableStateOf(step?.isCredentialStep == true)
    }

    val canonicalExpectedOrigin = remember(expectedOrigin) {
        if (expectedOrigin.isBlank()) "" else AutomationEngine().parseOrigin(expectedOrigin).canonicalOrigin
    }
    val matchingOriginCredentials = remember(credentialVaultEntries, canonicalExpectedOrigin) {
        if (canonicalExpectedOrigin.isEmpty()) {
            credentialVaultEntries
        } else {
            credentialVaultEntries.filter { it.websiteOrigin == canonicalExpectedOrigin }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step == null) stringResource(R.string.automation_add_step) else "Edit Step") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Step description") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_step_description_input")
                )
                OutlinedTextField(
                    value = targetLabel,
                    onValueChange = { targetLabel = it },
                    label = { Text("Target element label") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_step_label_input")
                )
                OutlinedTextField(
                    value = selector,
                    onValueChange = { selector = it },
                    label = { Text("CSS selector (optional)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_step_selector_input")
                )
                if (!useSavedCredential) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Input value / URL (supports \${var})") },
                        supportingText = {
                            Text("Tip: reference workflow variables like \${username} across pages")
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_step_value_input")
                    )
                }

                if (step?.isCredentialStep == true ||
                    selectedType == AutomationActionType.INPUT ||
                    selectedType == AutomationActionType.FILL_CREDENTIAL
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_step_credential_section")
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Saved Credential Binding",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (step?.isCredentialStep != true) {
                                    TextButton(
                                        onClick = {
                                            useSavedCredential = !useSavedCredential
                                            if (useSavedCredential) value = ""
                                        }
                                    ) {
                                        Text(if (useSavedCredential) "Use Plain Input" else "Bind Vault Credential")
                                    }
                                }
                            }

                            if (useSavedCredential) {
                                Text(
                                    text = "Passwords are never stored in workflow steps. Select a credential for ${canonicalExpectedOrigin.ifBlank { "this website origin" }}:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CredentialFieldRole.entries.forEach { role ->
                                        val isRoleSelected = selectedFieldRole == role
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isRoleSelected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHighest
                                            },
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { selectedFieldRole = role }
                                                .testTag("edit_step_role_${role.id.lowercase()}")
                                        ) {
                                            Text(
                                                text = "Fill ${role.label}",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selectedCredentialId == null) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { selectedCredentialId = null }
                                        .testTag("edit_step_cred_unassigned")
                                ) {
                                    Text(
                                        text = "Leave unassigned (require credential before replay)",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }

                                if (matchingOriginCredentials.isEmpty()) {
                                    Text(
                                        text = "No saved credentials in Vault matching origin ${canonicalExpectedOrigin.ifBlank { "for this page" }}.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                } else {
                                    matchingOriginCredentials.forEach { cred ->
                                        val isCredSelected = selectedCredentialId == cred.credentialId
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isCredSelected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHighest
                                            },
                                            border = if (isCredSelected) {
                                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                            } else null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { selectedCredentialId = cred.credentialId }
                                                .testTag("edit_step_cred_${cred.credentialId}")
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Text(
                                                    text = "${cred.label} (${cred.usernameIdentifier})",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = cred.displayOrigin,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                if (onOpenCredentialVault != null) {
                                    OutlinedButton(
                                        onClick = {
                                            onDismiss()
                                            onOpenCredentialVault()
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("edit_step_open_vault_button")
                                    ) {
                                        Text("Open Encrypted Credential Vault")
                                    }
                                }
                            }
                        }
                    }
                }

                if (allowActionTypeChange) {
                    Text(
                        text = "Action: ${selectedType.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            listOf(
                                AutomationActionType.CLICK,
                                AutomationActionType.INPUT,
                                AutomationActionType.SELECT,
                                AutomationActionType.TOGGLE
                            ),
                            listOf(
                                AutomationActionType.NAVIGATE,
                                AutomationActionType.SCROLL,
                                AutomationActionType.EXTRACT,
                                AutomationActionType.SUBMIT
                            )
                        ).forEach { chipRow ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                chipRow.forEach { type ->
                                    val isSelected = selectedType == type
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                        border = if (isSelected) {
                                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                        } else null,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { selectedType = type }
                                            .testTag("edit_step_action_${type.id.lowercase()}")
                                    ) {
                                        Text(
                                            text = type.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val resolvedLabel = targetLabel.trim().ifBlank { description.trim() }
                    val safeValue = if (useSavedCredential) "" else value
                    if (useSavedCredential && onAssignCredential != null) {
                        onAssignCredential(selectedCredentialId, selectedFieldRole)
                    }
                    if (onSaveDetailed != null) {
                        onSaveDetailed(description, selectedType, safeValue, resolvedLabel, selector.trim())
                    } else {
                        onSave(description, selectedType, safeValue)
                    }
                },
                enabled = description.isNotBlank(),
                modifier = Modifier.testTag("edit_step_save_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

package com.example.ui.browser

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.automation.AutomationActionType
import com.example.automation.AutomationEngine
import com.example.automation.AutomationStep
import com.example.automation.AutomationStepCodec
import com.example.automation.AutomationType
import com.example.automation.CredentialFieldRole
import com.example.automation.InterruptedExecutionCandidate
import com.example.automation.LocatorResolutionDiagnostics
import com.example.automation.PendingCredentialReplayAuthorization
import com.example.automation.RecoveryPolicy
import com.example.automation.WorkflowDiffReport
import com.example.automation.WorkflowDocumentMetadata
import com.example.automation.WorkflowDryRunReport
import com.example.automation.WorkflowExecutionState
import com.example.automation.WorkflowOrganizationMetadata
import com.example.automation.WorkflowValidationReport
import com.example.automation.WorkflowVariableDefinition
import com.example.data.local.AutomationExecutionEntity
import com.example.data.local.AutomationWorkflowEntity
import com.example.data.local.AutomationWorkflowVersionEntity
import com.example.data.local.CredentialVaultEntity
import com.example.ui.theme.EmphasizedTypography
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationDetailsScreenOverlay(
    workflow: AutomationWorkflowEntity,
    executions: List<AutomationExecutionEntity>,
    executionState: WorkflowExecutionState?,
    onRunWorkflow: (Long) -> Unit,
    onRenameWorkflow: (Long, String) -> Unit,
    onUpdateSteps: (Long, List<AutomationStep>) -> Unit,
    onAddStep: (Long, AutomationActionType, String, String, String) -> Unit,
    onConfirmDestructiveExecution: () -> Unit,
    onCancelDestructiveExecution: () -> Unit,
    onClearExecutionBanner: () -> Unit,
    backHandlerEnabled: Boolean = true,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCancelExecution: () -> Unit = {},
    credentialVaultEntries: List<CredentialVaultEntity> = emptyList(),
    pendingCredentialReplayAuth: PendingCredentialReplayAuthorization? = null,
    onConfirmCredentialReplay: () -> Unit = {},
    onCancelCredentialReplay: () -> Unit = {},
    onAssignCredentialToStep: (Long, String, String?, CredentialFieldRole) -> Unit = { _, _, _, _ -> },
    onOpenCredentialVault: () -> Unit = {},
    onPauseExecution: () -> Unit = {},
    onResumeExecution: () -> Unit = {},
    allWorkflows: List<AutomationWorkflowEntity> = emptyList(),
    onUpdateDocument: (Long, List<AutomationStep>, WorkflowDocumentMetadata) -> Unit = { _, _, _ -> },
    onAddVariable: (Long, WorkflowVariableDefinition) -> Unit = { _, _ -> },
    onRemoveVariable: (Long, String) -> Unit = { _, _ -> },
    onUpdateDefaultRecovery: (Long, RecoveryPolicy?) -> Unit = { _, _ -> },
    onAddPhase6Step: (Long, AutomationStep) -> Unit = { _, _ -> },
    interruptedCandidate: InterruptedExecutionCandidate? = null,
    onResumeInterrupted: () -> Unit = {},
    onRestartInterrupted: () -> Unit = {},
    onDismissInterrupted: () -> Unit = {},
    validationReport: WorkflowValidationReport? = null,
    dryRunReport: WorkflowDryRunReport? = null,
    versions: List<AutomationWorkflowVersionEntity> = emptyList(),
    diffReport: WorkflowDiffReport? = null,
    onValidateWorkflow: (Long) -> Unit = {},
    onRunDryRun: (Long, Map<String, String>) -> Unit = { _, _ -> },
    onClearDryRun: () -> Unit = {},
    onLoadVersions: (Long) -> Unit = {},
    onCompareVersions: (Long, Int, Int) -> Unit = { _, _, _ -> },
    onRestoreVersion: (Long, Int) -> Unit = { _, _ -> },
    onClearDiffReport: () -> Unit = {},
    onUpdateOrganization: (Long, WorkflowOrganizationMetadata) -> Unit = { _, _ -> },
    onArchiveWorkflow: (Long) -> Unit = {},
    onRestoreArchivedWorkflow: (Long) -> Unit = {},
    onExportSinglePackageJson: (Long, Boolean, (String) -> Unit) -> Unit = { _, _, _ -> },
    onExportSingleZipBytes: (Long, (ByteArray) -> Unit) -> Unit = { _, _ -> },
    onOpenDisambiguation: (() -> Unit)? = null,
    lastLocatorDiagnostics: LocatorResolutionDiagnostics? = null
) {
    BackHandler(enabled = backHandlerEnabled, onBack = onDismiss)

    val context = LocalContext.current
    val document = remember(workflow.stepsJson) { AutomationStepCodec.decodeDocument(workflow.stepsJson) }
    val steps = document.steps
    val type = remember(workflow.type) { AutomationType.fromId(workflow.type) }
    val dateFormatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    var isEditingMode by rememberSaveable(workflow.id) { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddStepDialog by remember { mutableStateOf(false) }
    var showPhase6BuilderDialog by remember { mutableStateOf(false) }
    var editingStep by remember { mutableStateOf<AutomationStep?>(null) }
    var editingRecoveryStep by remember { mutableStateOf<AutomationStep?>(null) }
    var showValidationDialog by remember { mutableStateOf(false) }
    var showVersionHistoryDialog by remember { mutableStateOf(false) }
    var showOrganizationDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    val createJsonFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val json = pendingExportJson
        if (uri != null && json != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    val createZipFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            onExportSingleZipBytes(workflow.id) { bytes ->
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(bytes)
                    }
                }
            }
        }
    }

    if (showValidationDialog && validationReport != null) {
        WorkflowValidationReportDialog(
            report = validationReport,
            onDismiss = { showValidationDialog = false }
        )
    }

    if (dryRunReport != null && dryRunReport.workflowId == workflow.id) {
        WorkflowDryRunReportDialog(
            report = dryRunReport,
            metadata = document.metadata,
            onRerunWithInputs = { inputs -> onRunDryRun(workflow.id, inputs) },
            onDismiss = onClearDryRun
        )
    }

    if (showVersionHistoryDialog) {
        WorkflowVersionHistoryAndDiffDialog(
            workflow = workflow,
            versions = versions,
            diffReport = diffReport,
            onCompareVersions = { baseV, targetV -> onCompareVersions(workflow.id, baseV, targetV) },
            onRestoreVersion = { targetV -> onRestoreVersion(workflow.id, targetV) },
            onDismiss = {
                showVersionHistoryDialog = false
                onClearDiffReport()
            }
        )
    }

    if (showOrganizationDialog) {
        WorkflowOrganizationDialog(
            workflow = workflow,
            onSaveOrganization = { org -> onUpdateOrganization(workflow.id, org) },
            onArchiveToggle = { shouldArchive ->
                if (shouldArchive) onArchiveWorkflow(workflow.id) else onRestoreArchivedWorkflow(workflow.id)
            },
            onDismiss = { showOrganizationDialog = false }
        )
    }

    if (showExportDialog && pendingExportJson != null) {
        WorkflowPackageExportDialog(
            workflowName = workflow.name,
            jsonPreview = pendingExportJson.orEmpty(),
            onSaveJsonFile = {
                val safeName = workflow.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "workflow" }
                createJsonFileLauncher.launch("$safeName-v${workflow.currentVersion}.apexflow.json")
            },
            onSaveZipFile = {
                val safeName = workflow.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "workflow" }
                createZipFileLauncher.launch("$safeName-bundle.zip")
            },
            onDismiss = { showExportDialog = false }
        )
    }

    if (pendingCredentialReplayAuth != null) {
        CredentialReplayAuthorizationDialog(
            authorization = pendingCredentialReplayAuth,
            onConfirm = onConfirmCredentialReplay,
            onDismiss = onCancelCredentialReplay
        )
    }

    if (executionState?.isAwaitingConfirmation == true) {
        DestructiveAutomationConfirmDialog(
            workflowName = executionState.workflowName,
            destructiveSteps = executionState.pendingDestructiveSteps,
            onConfirm = onConfirmDestructiveExecution,
            onDismiss = onCancelDestructiveExecution
        )
    }

    if (showRenameDialog) {
        RenameAutomationDialog(
            currentName = workflow.name,
            onConfirm = { newName ->
                onRenameWorkflow(workflow.id, newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    if (showPhase6BuilderDialog) {
        Phase6StepBuilderDialog(
            currentWorkflowId = workflow.id,
            allWorkflows = allWorkflows,
            onAddStep = { newStep ->
                onAddPhase6Step(workflow.id, newStep)
                showPhase6BuilderDialog = false
            },
            onDismiss = { showPhase6BuilderDialog = false }
        )
    }

    editingRecoveryStep?.let { target ->
        RecoveryPolicyEditorDialog(
            initialPolicy = target.recoveryPolicy,
            onSave = { policy ->
                val updatedSteps = steps.map {
                    if (it.id == target.id) it.copy(recoveryPolicy = policy) else it
                }
                onUpdateDocument(workflow.id, updatedSteps, document.metadata)
                editingRecoveryStep = null
            },
            onClear = {
                val updatedSteps = steps.map {
                    if (it.id == target.id) it.copy(recoveryPolicy = null) else it
                }
                onUpdateDocument(workflow.id, updatedSteps, document.metadata)
                editingRecoveryStep = null
            },
            onDismiss = { editingRecoveryStep = null }
        )
    }

    if (showAddStepDialog) {
        EditAutomationStepDialog(
            step = null,
            allowActionTypeChange = true,
            credentialVaultEntries = credentialVaultEntries,
            expectedOrigin = workflow.sourceUrl,
            onOpenCredentialVault = onOpenCredentialVault,
            onSave = { desc, actionType, value ->
                onAddStep(workflow.id, actionType, desc, value, desc)
                showAddStepDialog = false
            },
            onSaveDetailed = { desc, actionType, value, targetLabel, selector ->
                val resolvedTarget = targetLabel.ifBlank { desc }
                if (selector.isBlank()) {
                    onAddStep(workflow.id, actionType, resolvedTarget, value, desc)
                } else {
                    val isCred = actionType == AutomationActionType.FILL_CREDENTIAL
                    val newStep = AutomationStep(
                        id = "step_${java.util.UUID.randomUUID()}",
                        order = steps.size + 1,
                        actionType = actionType,
                        targetLabel = resolvedTarget,
                        selector = selector,
                        value = if (isCred) "" else value,
                        description = desc.trim().ifBlank { resolvedTarget },
                        isDestructive = actionType == AutomationActionType.SUBMIT ||
                            AutomationStepCodec.isClauseDestructive(desc.lowercase()) ||
                            AutomationStepCodec.isClauseDestructive(resolvedTarget.lowercase()),
                        requiresCredential = isCred,
                        credentialFieldRole = if (isCred) CredentialFieldRole.PASSWORD else null,
                        expectedOrigin = workflow.sourceUrl
                    )
                    onUpdateSteps(workflow.id, steps + newStep)
                }
                showAddStepDialog = false
            },
            onDismiss = { showAddStepDialog = false }
        )
    }

    editingStep?.let { target ->
        EditAutomationStepDialog(
            step = target,
            allowActionTypeChange = true,
            credentialVaultEntries = credentialVaultEntries,
            expectedOrigin = target.expectedOrigin.ifBlank { workflow.sourceUrl },
            onAssignCredential = { credId, role ->
                onAssignCredentialToStep(workflow.id, target.id, credId, role)
            },
            onOpenCredentialVault = onOpenCredentialVault,
            onSave = { desc, actionType, value ->
                val updated = steps.map {
                    if (it.id == target.id) {
                        AutomationStepCodec.applyStepEdit(
                            existing = it,
                            description = desc,
                            actionType = actionType,
                            value = value
                        )
                    } else {
                        it
                    }
                }
                onUpdateSteps(workflow.id, updated)
                editingStep = null
            },
            onSaveDetailed = { desc, actionType, value, targetLabel, selector ->
                val updated = steps.map {
                    if (it.id == target.id) {
                        AutomationStepCodec.applyStepEdit(
                            existing = it,
                            description = desc,
                            actionType = actionType,
                            value = value,
                            targetLabel = targetLabel,
                            selector = selector
                        )
                    } else {
                        it
                    }
                }
                onUpdateSteps(workflow.id, updated)
                editingStep = null
            },
            onDismiss = { editingStep = null }
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("details_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back)
                        )
                    }
                },
                title = {
                    Text(
                        text = workflow.name,
                        style = EmphasizedTypography.headlineHero,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    IconButton(
                        onClick = { showRenameDialog = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("details_rename_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DriveFileRenameOutline,
                            contentDescription = stringResource(R.string.automation_rename)
                        )
                    }
                }
            )
        },
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .testTag("automation_details_screen")
    ) { innerPadding ->
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .testTag("automation_details_list")
            ) {
                // Archived notice banner (blocks execution until restored)
                if (workflow.isArchived) {
                    item(key = "details_archived_banner") {
                        WorkflowArchivedNoticeBanner(
                            onRestore = { onRestoreArchivedWorkflow(workflow.id) }
                        )
                    }
                }

                // Summary & Primary Run / Edit Controls
                item(key = "details_summary_card") {
                    val tags = remember(workflow.tagsJson) {
                        WorkflowOrganizationMetadata.decodeTagsJson(workflow.tagsJson)
                    }
                    ElevatedCard(
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                SettingsIconBadge(icon = ApexIcons.MotionSensorActive, highlighted = true)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = workflow.domain,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${type.label} workflow • ${steps.size} step${if (steps.size == 1) "" else "s"} • v${workflow.currentVersion}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (workflow.folder.isNotBlank() || workflow.category.isNotBlank() || tags.isNotEmpty()) {
                                        Text(
                                            text = listOfNotNull(
                                                workflow.folder.takeIf { it.isNotBlank() }?.let { "Folder: $it" },
                                                workflow.category.takeIf { it.isNotBlank() }?.let { "Category: $it" },
                                                tags.takeIf { it.isNotEmpty() }?.joinToString(", ") { "#$it" }
                                            ).joinToString(" • "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            val lastExecText = workflow.lastExecutedAt?.let { ts ->
                                "${formatExecutionStatusLabel(workflow.lastExecutionStatus)} • ${dateFormatter.format(Date(ts))}"
                            } ?: "Never executed"

                            Text(
                                text = "Last Execution: $lastExecText",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = { onRunWorkflow(workflow.id) },
                                    enabled = steps.isNotEmpty() &&
                                        executionState?.executionStatus?.isActive != true &&
                                        !workflow.isArchived &&
                                        validationReport?.isExecutionPermitted != false,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("details_run_button")
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.automation_run), maxLines = 1)
                                }

                                FilledTonalButton(
                                    onClick = { isEditingMode = !isEditingMode },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("details_edit_toggle_button")
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isEditingMode) "Done Editing" else stringResource(R.string.automation_edit), maxLines = 1)
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        onLoadVersions(workflow.id)
                                        showVersionHistoryDialog = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 36.dp)
                                        .testTag("details_versions_button")
                                ) {
                                    Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.automation_versions), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                }

                                OutlinedButton(
                                    onClick = { showOrganizationDialog = true },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 36.dp)
                                        .testTag("details_organize_button")
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.automation_organize), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                }

                                OutlinedButton(
                                    onClick = {
                                        onExportSinglePackageJson(workflow.id, true) { json ->
                                            pendingExportJson = json
                                            showExportDialog = true
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 36.dp)
                                        .testTag("details_export_button")
                                ) {
                                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Export", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }

                // Interrupted Resume Card (if this workflow has an active checkpoint)
                if (interruptedCandidate?.checkpoint?.workflowId == workflow.id &&
                    executionState?.executionStatus?.isActive != true
                ) {
                    item(key = "details_interrupted_banner") {
                        InterruptedExecutionResumeBanner(
                            candidate = interruptedCandidate,
                            onResume = onResumeInterrupted,
                            onRestart = onRestartInterrupted,
                            onDismiss = onDismissInterrupted
                        )
                    }
                }

                // Live Execution Progress & Step-by-Step Verification Updates
                if (executionState != null &&
                    !executionState.isAwaitingConfirmation &&
                    !executionState.isInterrupted &&
                    executionState.workflowId == workflow.id
                ) {
                    item(key = "details_execution_live_card") {
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
                }

                // Phase 6 Workflow Variables & Default Failure Recovery
                item(key = "details_phase6_variables_card") {
                    WorkflowVariablesEditorCard(
                        variables = document.metadata.variables,
                        onAddVariable = { def -> onAddVariable(workflow.id, def) },
                        onRemoveVariable = { varName -> onRemoveVariable(workflow.id, varName) }
                    )
                }

                item(key = "details_phase6_recovery_card") {
                    RecoveryPolicyEditorCard(
                        policy = document.metadata.defaultRecoveryPolicy,
                        onSavePolicy = { pol -> onUpdateDefaultRecovery(workflow.id, pol) }
                    )
                }

                // Phase 8 Validation & Safe Dry-Run Diagnostics Card
                item(key = "details_validation_card") {
                    WorkflowValidationStatusCard(
                        report = validationReport,
                        onValidate = { onValidateWorkflow(workflow.id) },
                        onRunDryRun = { onRunDryRun(workflow.id, emptyMap()) },
                        onInspectReport = { showValidationDialog = true }
                    )
                }

                // Ordered Steps Header + Add Step / Phase 6 Logic buttons
                item(key = "details_steps_header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        MaterialGroupSectionHeader(
                            title = "Ordered Steps (${steps.size})",
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { showPhase6BuilderDialog = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("details_add_phase6_step_button")
                        ) {
                            Text("Logic / Var")
                        }
                        FilledTonalButton(
                            onClick = { showAddStepDialog = true },
                            modifier = Modifier.testTag("details_add_step_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.automation_add_step))
                        }
                    }
                }

                if (steps.isEmpty()) {
                    item(key = "details_steps_empty") {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "This workflow has no steps yet. Tap Add Step to add an action.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = steps,
                        key = { _, s -> s.id }
                    ) { idx, step ->
                        val shape = groupedListItemShape(idx, steps.size)
                        val isCurrentlyRunningStep = executionState != null &&
                            executionState.workflowId == workflow.id &&
                            executionState.executionStatus.isActive &&
                            executionState.currentStepIndex == idx

                        Surface(
                            shape = shape,
                            color = when {
                                isCurrentlyRunningStep -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                step.isDestructive -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                else -> MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            border = if (isCurrentlyRunningStep) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("details_step_row_${idx + 1}")
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
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
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = buildString {
                                                append(
                                                    if (step.actionType == AutomationActionType.UNSUPPORTED && step.rawActionType.isNotBlank()) {
                                                        "Unsupported (${step.rawActionType})"
                                                    } else {
                                                        step.actionType.label
                                                    }
                                                )
                                                if (!step.isCredentialStep && step.value.isNotBlank()) {
                                                    append(" • \"${step.value}\"")
                                                }
                                                if (step.selector.isNotBlank()) append(" • ${step.selector}")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (step.isCredentialStep) {
                                            val boundEntry = credentialVaultEntries.firstOrNull {
                                                it.credentialId == step.credentialReferenceId
                                            }
                                            val roleLabel = (step.credentialFieldRole ?: CredentialFieldRole.PASSWORD).label
                                            val originRef = step.expectedOrigin.ifBlank { workflow.sourceUrl }
                                            val canonicalOriginRef = if (originRef.isBlank()) "" else AutomationEngine().parseOrigin(originRef).canonicalOrigin.ifBlank { originRef }
                                            val isOriginMismatch = boundEntry != null &&
                                                canonicalOriginRef.isNotBlank() &&
                                                boundEntry.websiteOrigin != canonicalOriginRef
                                            val badgeText = when {
                                                step.credentialReferenceId.isNullOrBlank() ->
                                                    "Unassigned — Requires saved credential ($roleLabel)"
                                                boundEntry == null ->
                                                    "Missing credential — Deleted from Vault ($roleLabel)"
                                                isOriginMismatch ->
                                                    "Origin mismatch — Bound to ${boundEntry.displayOrigin}"
                                                else ->
                                                    "Bound to ${boundEntry.label} (${boundEntry.usernameIdentifier}) • $roleLabel"
                                            }
                                            val isWarning = step.credentialReferenceId.isNullOrBlank() ||
                                                boundEntry == null ||
                                                isOriginMismatch
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isWarning) {
                                                    MaterialTheme.colorScheme.errorContainer
                                                } else {
                                                    MaterialTheme.colorScheme.secondaryContainer
                                                },
                                                modifier = Modifier
                                                    .padding(top = 4.dp)
                                                    .testTag("details_step_credential_badge_${idx + 1}")
                                            ) {
                                                Text(
                                                    text = badgeText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isWarning) {
                                                        MaterialTheme.colorScheme.onErrorContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.onSecondaryContainer
                                                    },
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                if (isCurrentlyRunningStep) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }

                                Phase6StepMetadataPreview(step = step)

                                Row(
                                    horizontalArrangement = Arrangement.End,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = { editingRecoveryStep = step },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("details_step_recovery_${idx + 1}")
                                    ) {
                                        Text(
                                            text = if (step.recoveryPolicy != null) "Recovery ✓" else "Recovery",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = {
                                            if (idx > 0) {
                                                val mutable = steps.toMutableList()
                                                val moved = mutable.removeAt(idx)
                                                mutable.add(idx - 1, moved)
                                                onUpdateSteps(workflow.id, mutable)
                                            }
                                        },
                                        enabled = idx > 0,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("details_step_up_${idx + 1}")
                                    ) {
                                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Move step up", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            if (idx < steps.lastIndex) {
                                                val mutable = steps.toMutableList()
                                                val moved = mutable.removeAt(idx)
                                                mutable.add(idx + 1, moved)
                                                onUpdateSteps(workflow.id, mutable)
                                            }
                                        },
                                        enabled = idx < steps.lastIndex,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("details_step_down_${idx + 1}")
                                    ) {
                                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Move step down", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { editingStep = step },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("details_step_edit_${idx + 1}")
                                    ) {
                                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.automation_edit), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            onUpdateSteps(workflow.id, steps.filterNot { it.id == step.id })
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("details_step_delete_${idx + 1}")
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

                // Execution History for this workflow
                item(key = "details_history_header") {
                    MaterialGroupSectionHeader(
                        title = "Execution History (${executions.size})",
                        icon = Icons.Outlined.History
                    )
                }

                if (executions.isEmpty()) {
                    item(key = "details_history_empty") {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No executions recorded for this workflow yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = executions,
                        key = { _, e -> "hist_${e.id}" }
                    ) { idx, exec ->
                        val ok = exec.status == "COMPLETED"
                        Column(modifier = Modifier.fillMaxWidth()) {
                            MaterialGroupListItem(
                                index = idx,
                                totalCount = executions.size,
                                title = "${formatExecutionStatusLabel(exec.status)} • ${exec.completedSteps}/${exec.totalSteps} steps",
                                subtitle = "${dateFormatter.format(Date(exec.startedAt))} — ${exec.verificationSummary}",
                                leadingContent = {
                                    Icon(
                                        imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                            if (exec.traceJson.isNotBlank()) {
                                ExecutionTraceDetailsCard(traceJson = exec.traceJson)
                            }
                        }
                    }
                }
            }
        }
    }
}

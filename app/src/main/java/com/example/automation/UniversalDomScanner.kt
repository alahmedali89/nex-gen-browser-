package com.example.automation

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

enum class DomScanStatus {
    IDLE,
    INITIALIZING,
    SCANNING,
    PARTIAL,
    COMPLETED,
    CANCELLED,
    FAILED,
    DOCUMENT_CHANGED,
    WEBVIEW_REPLACED,
    SESSION_INVALIDATED
}

data class ScannerLimits(
    val maxElements: Int = 500,
    val maxIframeDepth: Int = 4,
    val maxShadowDepth: Int = 6,
    val maxScanDurationMs: Long = 4_500L,
    val maxBatchSize: Int = 100,
    val maxPayloadBytes: Int = 512 * 1024,
    val mutationDebounceMs: Long = 180L,
    val maxMutationQueueSize: Int = 250,
    val maxExtendedScrollSteps: Int = 8,
    val extendedScrollStepPx: Int = 600,
    val extendedScrollSettleMs: Long = 90L
)

enum class LocatorStrategy(val id: String, val label: String, val baseConfidence: Float) {
    STABLE_ID("STABLE_ID", "Unique ID", 0.98f),
    STABLE_DATA_ATTR("STABLE_DATA_ATTR", "Data Test Attribute", 0.96f),
    ARIA_ROLE_AND_NAME("ARIA_ROLE_AND_NAME", "ARIA Role & Name", 0.94f),
    LABEL_FOR("LABEL_FOR", "Associated Label", 0.93f),
    STABLE_NAME("STABLE_NAME", "Name Attribute", 0.92f),
    HREF_ATTR("HREF_ATTR", "Link Href Attribute", 0.90f),
    PLACEHOLDER_ATTR("PLACEHOLDER_ATTR", "Placeholder Attribute", 0.89f),
    FORM_RELATIVE("FORM_RELATIVE", "Form-Scoped Selector", 0.88f),
    CONTAINER_SCOPED("CONTAINER_SCOPED", "Container-Scoped Selector", 0.87f),
    SHADOW_DOM_CHAIN("SHADOW_DOM_CHAIN", "Shadow DOM Chain", 0.87f),
    IFRAME_CHAIN("IFRAME_CHAIN", "Same-Origin Frame Path", 0.86f),
    SCOPED_TEXT("SCOPED_TEXT", "Scoped Text Locator", 0.80f),
    SCOPED_CSS("SCOPED_CSS", "Scoped CSS Path", 0.76f),
    FALLBACK_TAG_ATTR("FALLBACK_TAG_ATTR", "Tag & Attribute Fallback", 0.55f);

    companion object {
        fun fromId(raw: String?): LocatorStrategy {
            return entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: SCOPED_CSS
        }
    }
}

enum class LocatorStability(val id: String) {
    HIGH("HIGH"),
    MEDIUM("MEDIUM"),
    LOW("LOW");

    companion object {
        fun fromId(raw: String?): LocatorStability {
            return entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: MEDIUM
        }
    }
}

data class LocatorCandidate(
    val strategy: LocatorStrategy,
    val selector: String,
    val scope: String = "DOCUMENT", // DOCUMENT, SHADOW, IFRAME, FORM
    val confidence: Float = strategy.baseConfidence,
    val isUnique: Boolean = true,
    val matchCount: Int = 1,
    val isAmbiguous: Boolean = false,
    val stability: LocatorStability = LocatorStability.HIGH
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("strategy", strategy.id)
        put("selector", selector)
        put("scope", scope)
        put("confidence", confidence.toDouble())
        put("isUnique", isUnique)
        put("matchCount", matchCount)
        put("isAmbiguous", isAmbiguous)
        put("stability", stability.id)
    }

    companion object {
        fun fromJson(obj: JSONObject): LocatorCandidate {
            val strategy = LocatorStrategy.fromId(obj.optString("strategy"))
            val matchCount = obj.optInt("matchCount", 1).coerceAtLeast(1)
            val isUnique = obj.optBoolean("isUnique", matchCount == 1)
            val isAmbiguous = obj.optBoolean("isAmbiguous", !isUnique || matchCount > 1)
            return LocatorCandidate(
                strategy = strategy,
                selector = obj.optString("selector", ""),
                scope = obj.optString("scope", "DOCUMENT"),
                confidence = obj.optDouble("confidence", strategy.baseConfidence.toDouble()).toFloat(),
                isUnique = isUnique,
                matchCount = matchCount,
                isAmbiguous = isAmbiguous,
                stability = LocatorStability.fromId(obj.optString("stability"))
            )
        }
    }
}

enum class SensitiveFieldCategory(val id: String, val isSensitive: Boolean) {
    NONE("NONE", false),
    PASSWORD("PASSWORD", true),
    CREDIT_CARD("CREDIT_CARD", true),
    CVV("CVV", true),
    BANK_ACCOUNT("BANK_ACCOUNT", true),
    OTP("OTP", true),
    SECURITY_QUESTION("SECURITY_QUESTION", true),
    PERSONAL_ID("PERSONAL_ID", true);

    companion object {
        fun fromId(raw: String?): SensitiveFieldCategory {
            return entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: NONE
        }
    }
}

enum class ControlKind(val id: String, val label: String) {
    STANDARD_INPUT("STANDARD_INPUT", "Input Field"),
    HIDDEN_INPUT("HIDDEN_INPUT", "Hidden Input"),
    TEXTAREA("TEXTAREA", "Text Area"),
    SELECT("SELECT", "Native Select"),
    OPTION("OPTION", "Select Option"),
    CHECKBOX("CHECKBOX", "Checkbox"),
    RADIO("RADIO", "Radio Button"),
    BUTTON("BUTTON", "Button"),
    LINK("LINK", "Link"),
    CONTENTEDITABLE("CONTENTEDITABLE", "Editable Region"),
    ARIA_CONTROL("ARIA_CONTROL", "ARIA Control"),
    CUSTOM_DROPDOWN("CUSTOM_DROPDOWN", "Custom Dropdown"),
    DATE_TIME("DATE_TIME", "Date/Time Control"),
    FILE_INPUT("FILE_INPUT", "File Upload"),
    SUMMARY_DETAILS("SUMMARY_DETAILS", "Expandable Summary"),
    FORM_CONTAINER("FORM_CONTAINER", "Form Container"),
    OTHER_INTERACTIVE("OTHER_INTERACTIVE", "Interactive Element");

    companion object {
        fun fromId(raw: String?): ControlKind {
            return entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: OTHER_INTERACTIVE
        }
    }
}

enum class DropdownKind(val id: String, val label: String) {
    NONE("NONE", "None"),
    NATIVE_SELECT("NATIVE_SELECT", "Native Select"),
    CUSTOM_COMBOBOX("CUSTOM_COMBOBOX", "Custom Combobox"),
    CUSTOM_LISTBOX("CUSTOM_LISTBOX", "Custom Listbox"),
    MENU("MENU", "Popup Menu"),
    AUTOCOMPLETE("AUTOCOMPLETE", "Autocomplete");

    companion object {
        fun fromId(raw: String?): DropdownKind {
            return entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: NONE
        }
    }
}

data class DateTimeMetadata(
    val controlType: String,
    val inputFormat: String = "",
    val min: String = "",
    val max: String = "",
    val step: String = "",
    val isCustomPicker: Boolean = false,
    val pickerRole: String = "",
    val selectedDate: String = "",
    val disabledDatesHint: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("controlType", controlType)
        put("inputFormat", inputFormat)
        put("min", min)
        put("max", max)
        put("step", step)
        put("isCustomPicker", isCustomPicker)
        put("pickerRole", pickerRole)
        put("selectedDate", selectedDate)
        put("disabledDatesHint", disabledDatesHint)
    }

    companion object {
        fun fromJson(obj: JSONObject?): DateTimeMetadata? {
            if (obj == null) return null
            val ct = obj.optString("controlType", "").trim()
            if (ct.isEmpty()) return null
            return DateTimeMetadata(
                controlType = ct,
                inputFormat = obj.optString("inputFormat", ""),
                min = obj.optString("min", ""),
                max = obj.optString("max", ""),
                step = obj.optString("step", ""),
                isCustomPicker = obj.optBoolean("isCustomPicker", false),
                pickerRole = obj.optString("pickerRole", ""),
                selectedDate = obj.optString("selectedDate", ""),
                disabledDatesHint = obj.optString("disabledDatesHint", "")
            )
        }
    }
}

data class InaccessibleFrameInfo(
    val framePath: String,
    val frameSrc: String,
    val frameOrigin: String,
    val reason: String // "cross-origin", "unloaded", "detached", "sandboxed", "max-depth"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("framePath", framePath)
        put("frameSrc", frameSrc)
        put("frameOrigin", frameOrigin)
        put("reason", reason)
    }

    companion object {
        fun fromJson(obj: JSONObject): InaccessibleFrameInfo = InaccessibleFrameInfo(
            framePath = obj.optString("framePath", "iframe"),
            frameSrc = obj.optString("frameSrc", ""),
            frameOrigin = obj.optString("frameOrigin", ""),
            reason = obj.optString("reason", "cross-origin")
        )
    }
}

data class DomScanSummary(
    val totalDiscovered: Int = 0,
    val formsFound: Int = 0,
    val inputFieldsFound: Int = 0,
    val hiddenInputsFound: Int = 0,
    val buttonsFound: Int = 0,
    val linksFound: Int = 0,
    val dropdownsFound: Int = 0,
    val checkboxesFound: Int = 0,
    val radioButtonsFound: Int = 0,
    val dateTimeControlsFound: Int = 0,
    val contentEditableFound: Int = 0,
    val ariaControlsFound: Int = 0,
    val shadowDomElementsFound: Int = 0,
    val inaccessibleShadowRootsFound: Int = 0,
    val sameOriginIframeElementsFound: Int = 0,
    val inaccessibleCrossOriginFrames: Int = 0,
    val dynamicUpdatesProcessed: Int = 0,
    val staleElementsCount: Int = 0,
    val ambiguousLocatorsCount: Int = 0,
    val offScreenElementsFound: Int = 0,
    val isPartial: Boolean = false,
    val partialReason: String? = null,
    val structuredRecordsFound: Int = 0
)

data class DomScanSession(
    val scanSessionId: String,
    val tabId: String,
    val expectedOrigin: String,
    val documentUrl: String,
    val navigationEpoch: Long,
    val webViewRef: WeakReference<WebView?>,
    val startedAt: Long = System.currentTimeMillis(),
    var status: DomScanStatus = DomScanStatus.INITIALIZING,
    var dynamicUpdatesCount: Int = 0,
    var isCancelled: Boolean = false
)

data class ManualInstructionCandidateMatch(
    val matchedElements: List<DetectedPageElement>,
    val isAmbiguous: Boolean,
    val primaryCandidate: DetectedPageElement?
)

object SensitiveFieldClassifier {
    private val PASSWORD_PATTERNS = Regex("password|passwd|passcode|pwd|pin_code|secret_pin", RegexOption.IGNORE_CASE)
    private val CREDIT_CARD_PATTERNS = Regex("cc-number|cardnumber|card_number|card-number|creditcard|credit-card|pan_number", RegexOption.IGNORE_CASE)
    private val CVV_PATTERNS = Regex("cc-csc|cvv|cvc|csc|security_code|security-code|card_verification", RegexOption.IGNORE_CASE)
    private val BANK_PATTERNS = Regex("bank_account|bank-account|iban|routing_number|routing-number|swift_code|sort_code", RegexOption.IGNORE_CASE)
    private val OTP_PATTERNS = Regex("one-time-code|otp|totp|2fa|mfa_code|verification_code|auth_code", RegexOption.IGNORE_CASE)
    private val SECURITY_QUESTION_PATTERNS = Regex("security_question|security-question|security_answer|mother_maiden|secret_answer", RegexOption.IGNORE_CASE)
    private val PERSONAL_ID_PATTERNS = Regex("\\bssn\\b|social_security|social-security|tax_id|national_id|passport_number|driver_license", RegexOption.IGNORE_CASE)

    fun classify(
        tagName: String,
        inputType: String,
        autocomplete: String,
        name: String,
        htmlId: String,
        label: String,
        accessibleName: String,
        placeholder: String
    ): SensitiveFieldCategory {
        val lowerType = inputType.trim().lowercase()
        if (lowerType == "password") return SensitiveFieldCategory.PASSWORD

        val combined = listOf(autocomplete, name, htmlId, label, accessibleName, placeholder)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()

        if (combined.isEmpty()) return SensitiveFieldCategory.NONE

        return when {
            autocomplete.equals("current-password", ignoreCase = true) ||
                autocomplete.equals("new-password", ignoreCase = true) ||
                PASSWORD_PATTERNS.containsMatchIn(combined) -> SensitiveFieldCategory.PASSWORD
            CVV_PATTERNS.containsMatchIn(combined) -> SensitiveFieldCategory.CVV
            CREDIT_CARD_PATTERNS.containsMatchIn(combined) -> SensitiveFieldCategory.CREDIT_CARD
            BANK_PATTERNS.containsMatchIn(combined) -> SensitiveFieldCategory.BANK_ACCOUNT
            OTP_PATTERNS.containsMatchIn(combined) -> SensitiveFieldCategory.OTP
            SECURITY_QUESTION_PATTERNS.containsMatchIn(combined) -> SensitiveFieldCategory.SECURITY_QUESTION
            PERSONAL_ID_PATTERNS.containsMatchIn(combined) -> SensitiveFieldCategory.PERSONAL_ID
            else -> SensitiveFieldCategory.NONE
        }
    }

    fun isCredentialCapableField(
        tagName: String,
        inputType: String,
        autocomplete: String,
        name: String,
        htmlId: String,
        label: String,
        accessibleName: String,
        sensitiveCategory: SensitiveFieldCategory
    ): Boolean {
        if (sensitiveCategory == SensitiveFieldCategory.PASSWORD) return true
        val tag = tagName.lowercase()
        if (tag != "input") return false
        val type = inputType.lowercase()
        if (type !in setOf("text", "email", "tel", "")) return false
        val bag = "$autocomplete $name $htmlId $label $accessibleName".lowercase()
        return bag.contains("user") ||
            bag.contains("email") ||
            bag.contains("login") ||
            bag.contains("account") ||
            autocomplete.equals("username", ignoreCase = true) ||
            autocomplete.equals("email", ignoreCase = true)
    }
}

object LocatorQualityEvaluator {
    private val UNSTABLE_ID_REGEX = Regex(
        "^(?:react-select-\\d+.*|:r[0-9a-z]+:|mui-\\d+|ember\\d+|el-id-\\d+.*|headlessui-.*-\\d+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|_[0-9a-z]{7,}|\\d{5,})$",
        RegexOption.IGNORE_CASE
    )
    private val UNSTABLE_HASH_CLASS_REGEX = Regex(
        "^(?:css-[0-9a-z]{5,}|sc-[a-zA-Z0-9]{5,}|jsx-\\d+|svelte-[0-9a-z]{5,}|tw-[0-9a-f]{6,}|style_[0-9a-f]{6,})$"
    )

    fun isStableId(rawId: String?): Boolean {
        val id = rawId?.trim().orEmpty()
        if (id.isEmpty() || id.length > 80 || id.any { it.isWhitespace() }) return false
        if (UNSTABLE_ID_REGEX.matches(id)) return false
        if (UNSTABLE_HASH_CLASS_REGEX.matches(id)) return false
        // Reject IDs with 6+ consecutive random digits or hex suffixes like input-83920192
        if (Regex("\\d{6,}").containsMatchIn(id)) return false
        return true
    }

    fun isStableClassName(rawClass: String?): Boolean {
        val cls = rawClass?.trim().orEmpty()
        if (cls.isEmpty() || cls.length > 48) return false
        if (UNSTABLE_HASH_CLASS_REGEX.matches(cls)) return false
        if (Regex("\\d{5,}").containsMatchIn(cls)) return false
        return true
    }

    fun escapeCssIdentifier(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            if (ch in " !\"#$%&'()*+,./:;<=>?@[\\]^`{|}~") {
                sb.append('\\').append(ch)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun escapeCssAttributeValue(raw: String): String {
        return raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", " ")
            .replace("\n", " ")
    }
}

class UniversalDomScanner(
    private val automationEngine: AutomationEngine,
    val limits: ScannerLimits = ScannerLimits()
) {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val activeSessionsByTab = ConcurrentHashMap<String, DomScanSession>()
    private val latestInspectionByTab = ConcurrentHashMap<String, PageInspectionState>()

    companion object {
        private val fixtureLock = Any()
        private val attachedFixtures = java.util.WeakHashMap<WebView, AttachedDomFixture>()

        data class AttachedDomFixture(
            var url: String,
            var html: String,
            var crossOriginFrameUrls: List<String> = emptyList(),
            var closedShadowHostSelectors: List<String> = emptyList(),
            var lazyScrollHtmlSnippets: List<String> = emptyList(),
            var scrollX: Int = 0,
            var scrollY: Int = 0,
            var observerInstalled: Boolean = false,
            var activeScanSessionId: String = "",
            var activeTabId: String = "",
            var dynamicMutationsApplied: Int = 0
        )

        fun attachHtmlFixture(
            webView: WebView,
            url: String,
            html: String,
            crossOriginFrameUrls: List<String> = emptyList(),
            closedShadowHostSelectors: List<String> = emptyList(),
            lazyScrollHtmlSnippets: List<String> = emptyList()
        ) {
            synchronized(fixtureLock) {
                attachedFixtures[webView] = AttachedDomFixture(
                    url = url,
                    html = html,
                    crossOriginFrameUrls = crossOriginFrameUrls,
                    closedShadowHostSelectors = closedShadowHostSelectors,
                    lazyScrollHtmlSnippets = lazyScrollHtmlSnippets
                )
            }
        }

        fun getAttachedFixture(webView: WebView?): AttachedDomFixture? {
            if (webView == null) return null
            return synchronized(fixtureLock) { attachedFixtures[webView] }
        }

        fun clearAttachedFixture(webView: WebView?) {
            if (webView == null) return
            synchronized(fixtureLock) { attachedFixtures.remove(webView) }
        }
    }

    internal val engine: AutomationEngine
        get() = automationEngine

    internal fun updateCachedInspection(tabId: String, state: PageInspectionState) {
        if (tabId.isNotEmpty()) {
            latestInspectionByTab[tabId] = state
        }
    }

    fun getActiveSession(tabId: String): DomScanSession? = activeSessionsByTab[tabId]

    fun getLatestInspection(tabId: String): PageInspectionState? = latestInspectionByTab[tabId]

    // --- Unified Nano Multi-Agent & Retriever Extraction APIs ---

    fun planAgentTask(
        prompt: String,
        currentUrl: String,
        inspectionState: PageInspectionState? = null
    ): List<PlannedStep> = DomScannerIntelligence.planTaskWithDom(
        scanner = this,
        prompt = prompt,
        currentUrl = currentUrl,
        inspectionState = inspectionState
    )

    suspend fun executeAgentStep(
        step: PlannedStep,
        tabId: String = "",
        webViewProvider: () -> WebView?,
        onNavigateUrl: (String) -> Unit
    ): DomObservation = DomScannerIntelligence.executeAgentStep(
        scanner = this,
        step = step,
        tabId = tabId,
        webViewProvider = webViewProvider,
        onNavigateUrl = onNavigateUrl
    )

    fun validateAgentOutcome(
        userPrompt: String,
        observations: List<DomObservation>,
        inspectionState: PageInspectionState? = null
    ): ValidationOutcome = DomScannerIntelligence.validateAgentOutcome(
        userPrompt = userPrompt,
        observations = observations,
        inspectionState = inspectionState
    )

    suspend fun runAgentTask(
        prompt: String,
        currentUrl: String,
        tabId: String = "",
        webViewProvider: () -> WebView?,
        onNavigateUrl: (String) -> Unit,
        onStepUpdate: (List<NanoReasoningStep>) -> Unit
    ): ValidationOutcome = DomScannerIntelligence.runAgentTask(
        scanner = this,
        prompt = prompt,
        currentUrl = currentUrl,
        tabId = tabId,
        webViewProvider = webViewProvider,
        onNavigateUrl = onNavigateUrl,
        onStepUpdate = onStepUpdate
    )

    fun buildExtractionRule(input: String): com.example.data.local.ExtractionRuleEntity =
        DomScannerIntelligence.buildRuleFromInput(input)

    suspend fun extractStructuredData(
        target: TabSnapshotTarget,
        webView: WebView?,
        rule: com.example.data.local.ExtractionRuleEntity
    ): List<ExtractedRecord> = DomScannerIntelligence.extractFromSingleTab(
        scanner = this,
        target = target,
        webView = webView,
        rule = rule
    )

    suspend fun extractAcrossTabsInParallel(
        tabs: List<TabSnapshotTarget>,
        webViewLookup: (String) -> WebView?,
        rule: com.example.data.local.ExtractionRuleEntity
    ): List<ExtractedRecord> = DomScannerIntelligence.extractAcrossTabsInParallel(
        scanner = this,
        tabs = tabs,
        webViewLookup = webViewLookup,
        rule = rule
    )

    fun invalidateTabSession(
        tabId: String,
        status: DomScanStatus = DomScanStatus.SESSION_INVALIDATED,
        reason: String? = null,
        webView: WebView? = null
    ) {
        val removed = activeSessionsByTab.remove(tabId)
        if (removed != null) {
            removed.isCancelled = true
            removed.status = status
            val targetWv = webView ?: removed.webViewRef.get()
            cleanupDynamicObserverBestEffort(targetWv, removed.scanSessionId)
        }
        val prevInspection = latestInspectionByTab[tabId]
        if (prevInspection != null) {
            val markedStale = prevInspection.elements.map { it.copy(stale = true) }
            latestInspectionByTab[tabId] = prevInspection.copy(
                isScanning = false,
                isExtendedScanRunning = false,
                isDynamicObserverActive = false,
                scanStatus = status,
                elements = markedStale,
                summary = prevInspection.summary.copy(staleElementsCount = markedStale.size),
                errorMessage = reason ?: prevInspection.errorMessage
            )
        }
    }

    fun onTabNavigated(tabId: String, newUrl: String, webView: WebView? = null) {
        val currentSession = activeSessionsByTab[tabId]
        if (currentSession != null) {
            currentSession.isCancelled = true
            currentSession.status = DomScanStatus.DOCUMENT_CHANGED
            activeSessionsByTab.remove(tabId)
            cleanupDynamicObserverBestEffort(webView ?: currentSession.webViewRef.get(), currentSession.scanSessionId)
        }
        val prev = latestInspectionByTab[tabId]
        if (prev != null) {
            val staleElements = prev.elements.map { it.copy(stale = true) }
            latestInspectionByTab[tabId] = prev.copy(
                domain = automationEngine.extractDomain(newUrl),
                url = newUrl,
                elements = staleElements,
                isScanning = false,
                isExtendedScanRunning = false,
                isDynamicObserverActive = false,
                scanStatus = DomScanStatus.DOCUMENT_CHANGED,
                summary = prev.summary.copy(staleElementsCount = staleElements.size),
                errorMessage = "Document changed; rescan required."
            )
        }
    }

    fun onWebViewReplaced(tabId: String, oldWebView: WebView?, newWebView: WebView?) {
        if (oldWebView != null && oldWebView !== newWebView) {
            val session = activeSessionsByTab.remove(tabId)
            if (session != null) {
                session.isCancelled = true
                session.status = DomScanStatus.WEBVIEW_REPLACED
                cleanupDynamicObserverBestEffort(oldWebView, session.scanSessionId)
            }
            val prev = latestInspectionByTab[tabId]
            if (prev != null) {
                val staleElements = prev.elements.map { it.copy(stale = true) }
                latestInspectionByTab[tabId] = prev.copy(
                    elements = staleElements,
                    isScanning = false,
                    isExtendedScanRunning = false,
                    isDynamicObserverActive = false,
                    scanStatus = DomScanStatus.WEBVIEW_REPLACED,
                    summary = prev.summary.copy(staleElementsCount = staleElements.size),
                    errorMessage = "WebView instance was replaced; previous scan results marked stale."
                )
            }
        }
    }

    fun cancelScan(tabId: String, webView: WebView? = null, reason: String = "Scan cancelled by user."): PageInspectionState {
        val session = activeSessionsByTab.remove(tabId)
        val targetWv = webView ?: session?.webViewRef?.get()
        if (session != null) {
            session.isCancelled = true
            session.status = DomScanStatus.CANCELLED
            cleanupDynamicObserverBestEffort(targetWv, session.scanSessionId)
        } else if (targetWv != null) {
            cleanupDynamicObserverBestEffort(targetWv, null)
        }
        val existing = latestInspectionByTab[tabId] ?: PageInspectionState()
        val updated = existing.copy(
            isScanning = false,
            isExtendedScanRunning = false,
            isDynamicObserverActive = false,
            scanStatus = DomScanStatus.CANCELLED,
            errorMessage = reason
        )
        latestInspectionByTab[tabId] = updated
        return updated
    }

    fun cleanupDynamicObserverBestEffort(webView: WebView?, expectedSessionId: String? = null) {
        if (webView == null) return
        val fixture = getAttachedFixture(webView)
        if (fixture != null) {
            if (expectedSessionId == null || fixture.activeScanSessionId == expectedSessionId) {
                fixture.observerInstalled = false
            }
        }
        val script = buildDynamicObserverCleanupScript(expectedSessionId)
        val webViewRef = WeakReference(webView)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { webView.evaluateJavascript(script, null) }
        } else {
            mainHandler.post {
                webViewRef.get()?.let { wv ->
                    runCatching { wv.evaluateJavascript(script, null) }
                }
            }
        }
    }

    suspend fun scanPage(
        webView: WebView?,
        tabId: String,
        currentUrl: String,
        navigationEpoch: Long = 0L,
        installDynamicObserver: Boolean = true,
        customLimits: ScannerLimits = limits
    ): PageInspectionState {
        val domain = automationEngine.extractDomain(currentUrl)
        val trimmedUrl = currentUrl.trim()
        if (trimmedUrl.isEmpty() || trimmedUrl.equals("apex://home", ignoreCase = true) || webView == null) {
            val emptyState = PageInspectionState(
                domain = domain,
                url = currentUrl,
                elements = emptyList(),
                isScanning = false,
                lastScannedAt = System.currentTimeMillis(),
                errorMessage = if (trimmedUrl.equals("apex://home", ignoreCase = true)) {
                    null
                } else {
                    "Open a webpage to inspect interactive DOM elements."
                },
                scanSessionId = "",
                tabId = tabId,
                scanStatus = if (trimmedUrl.equals("apex://home", ignoreCase = true)) DomScanStatus.IDLE else DomScanStatus.FAILED
            )
            if (tabId.isNotEmpty()) {
                latestInspectionByTab[tabId] = emptyState
            }
            return emptyState
        }

        val parsedOrigin = automationEngine.parseOrigin(trimmedUrl)
        val canonicalOrigin = if (parsedOrigin is RecordingOriginResult.Valid) {
            parsedOrigin.canonicalOrigin
        } else {
            ""
        }

        val sessionId = "scan_${UUID.randomUUID()}"
        val documentId = "doc_${tabId}_${navigationEpoch}_${System.currentTimeMillis()}"
        val session = DomScanSession(
            scanSessionId = sessionId,
            tabId = tabId,
            expectedOrigin = canonicalOrigin,
            documentUrl = trimmedUrl,
            navigationEpoch = navigationEpoch,
            webViewRef = WeakReference(webView),
            status = DomScanStatus.SCANNING
        )
        if (tabId.isNotEmpty()) {
            val old = activeSessionsByTab.put(tabId, session)
            if (old != null && old.scanSessionId != sessionId) {
                old.isCancelled = true
                cleanupDynamicObserverBestEffort(old.webViewRef.get(), old.scanSessionId)
            }
        }

        coroutineContext.ensureActive()

        val attachedFixture = getAttachedFixture(webView)
        val rawResultJson: String = if (attachedFixture != null) {
            attachedFixture.activeScanSessionId = sessionId
            attachedFixture.activeTabId = tabId
            attachedFixture.observerInstalled = installDynamicObserver
            // Also invoke evaluateJavascript on the WebView so any test spy recording scripts sees the scan script
            val jsScript = buildUniversalScanScript(
                sessionId = sessionId,
                tabId = tabId,
                expectedOrigin = canonicalOrigin,
                documentId = documentId,
                limits = customLimits
            )
            runCatching {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    webView.evaluateJavascript(jsScript, null)
                } else {
                    mainHandler.post { runCatching { webView.evaluateJavascript(jsScript, null) } }
                }
            }
            HtmlDomFixtureScanEngine.evaluateFixtureToJson(
                fixture = attachedFixture,
                sessionId = sessionId,
                tabId = tabId,
                expectedOrigin = canonicalOrigin,
                documentId = documentId,
                limits = customLimits
            )
        } else {
            val scanScript = buildUniversalScanScript(
                sessionId = sessionId,
                tabId = tabId,
                expectedOrigin = canonicalOrigin,
                documentId = documentId,
                limits = customLimits
            )
            when (val evalResult = automationEngine.evalJsWithResultInternal(webView, scanScript)) {
                is JsEvalResult.Success -> evalResult.rawValue
                is JsEvalResult.Timeout -> {
                    session.status = DomScanStatus.FAILED
                    val timeoutState = PageInspectionState(
                        domain = domain,
                        url = currentUrl,
                        elements = emptyList(),
                        isScanning = false,
                        lastScannedAt = System.currentTimeMillis(),
                        errorMessage = "Page inspection timed out after ${evalResult.timeoutMs}ms.",
                        scanSessionId = sessionId,
                        tabId = tabId,
                        canonicalOrigin = canonicalOrigin,
                        scanStatus = DomScanStatus.FAILED
                    )
                    if (tabId.isNotEmpty()) latestInspectionByTab[tabId] = timeoutState
                    return timeoutState
                }
                is JsEvalResult.Unavailable, is JsEvalResult.Failure -> {
                    session.status = DomScanStatus.FAILED
                    val failState = PageInspectionState(
                        domain = domain,
                        url = currentUrl,
                        elements = emptyList(),
                        isScanning = false,
                        lastScannedAt = System.currentTimeMillis(),
                        errorMessage = "Unable to access active document DOM.",
                        scanSessionId = sessionId,
                        tabId = tabId,
                        canonicalOrigin = canonicalOrigin,
                        scanStatus = DomScanStatus.FAILED
                    )
                    if (tabId.isNotEmpty()) latestInspectionByTab[tabId] = failState
                    return failState
                }
            }
        }

        coroutineContext.ensureActive()
        if (session.isCancelled || (tabId.isNotEmpty() && activeSessionsByTab[tabId]?.scanSessionId != sessionId)) {
            val cancelledStatus = session.status.takeIf { it != DomScanStatus.SCANNING } ?: DomScanStatus.CANCELLED
            val cancelledState = PageInspectionState(
                domain = domain,
                url = currentUrl,
                elements = emptyList(),
                isScanning = false,
                lastScannedAt = System.currentTimeMillis(),
                errorMessage = "Scan interrupted ($cancelledStatus).",
                scanSessionId = sessionId,
                tabId = tabId,
                canonicalOrigin = canonicalOrigin,
                scanStatus = cancelledStatus
            )
            return cancelledState
        }

        val parsedState = parseScanPayloadJson(
            raw = rawResultJson,
            expectedSessionId = sessionId,
            expectedTabId = tabId,
            expectedOrigin = canonicalOrigin,
            domain = domain,
            currentUrl = currentUrl,
            customLimits = customLimits
        )

        session.status = parsedState.scanStatus

        var finalState = parsedState
        if (installDynamicObserver &&
            (parsedState.scanStatus == DomScanStatus.COMPLETED || parsedState.scanStatus == DomScanStatus.PARTIAL)
        ) {
            if (attachedFixture != null) {
                attachedFixture.observerInstalled = true
                finalState = parsedState.copy(isDynamicObserverActive = true)
            } else {
                val obsScript = buildDynamicObserverInstallScript(
                    sessionId = sessionId,
                    tabId = tabId,
                    expectedOrigin = canonicalOrigin,
                    debounceMs = customLimits.mutationDebounceMs,
                    maxBatchSize = customLimits.maxBatchSize,
                    maxMutationQueueSize = customLimits.maxMutationQueueSize
                )
                val obsEval = automationEngine.evalJsWithResultInternal(webView, obsScript)
                val obsOk = obsEval is JsEvalResult.Success && obsEval.rawValue.contains("\"ok\":true")
                finalState = parsedState.copy(isDynamicObserverActive = obsOk)
            }
        }

        val enrichedState = DomScannerIntelligence.enrichInspectionState(
            state = finalState,
            attachedFixture = attachedFixture
        )
        if (tabId.isNotEmpty()) {
            latestInspectionByTab[tabId] = enrichedState
        }
        return enrichedState
    }

    /**
     * Explicit, bounded Extended Scan for long pages and lazy-loaded scroll containers.
     * Saves scroll positions, advances in bounded increments, discovers newly loaded elements,
     * and always restores original scroll positions in a finally block.
     */
    suspend fun runExtendedScan(
        webView: WebView?,
        tabId: String,
        currentUrl: String,
        navigationEpoch: Long = 0L,
        customLimits: ScannerLimits = limits,
        onStepScrolled: (suspend (stepIndex: Int, scrollY: Int) -> Unit)? = null
    ): PageInspectionState {
        // 1. Perform passive scan first
        val initialState = scanPage(
            webView = webView,
            tabId = tabId,
            currentUrl = currentUrl,
            navigationEpoch = navigationEpoch,
            installDynamicObserver = true,
            customLimits = customLimits
        )
        if (webView == null ||
            (initialState.scanStatus != DomScanStatus.COMPLETED && initialState.scanStatus != DomScanStatus.PARTIAL)
        ) {
            return initialState
        }

        val session = activeSessionsByTab[tabId]
        val sessionId = initialState.scanSessionId
        val attachedFixture = getAttachedFixture(webView)
        val originalScrollX = attachedFixture?.scrollX ?: 0
        val originalScrollY = attachedFixture?.scrollY ?: 0

        var savedScrollStateJson = "{\"x\":$originalScrollX,\"y\":$originalScrollY,\"containers\":[]}"
        if (attachedFixture == null) {
            val saveEval = automationEngine.evalJsWithResultInternal(webView, buildSaveScrollPositionScript())
            if (saveEval is JsEvalResult.Success) {
                savedScrollStateJson = unwrapJsQuotedJson(saveEval.rawValue).ifBlank { savedScrollStateJson }
            }
        }

        val mergedElements = LinkedHashMap<String, DetectedPageElement>()
        initialState.elements.forEach { el ->
            mergedElements[elementDedupeKey(el)] = el
        }

        var reachedLimit = initialState.scanStatus == DomScanStatus.PARTIAL
        var partialReason = initialState.summary.partialReason
        var wasCancelled = false

        try {
            val maxSteps = customLimits.maxExtendedScrollSteps.coerceIn(1, 20)
            for (stepIdx in 1..maxSteps) {
                coroutineContext.ensureActive()
                if (session?.isCancelled == true || (tabId.isNotEmpty() && activeSessionsByTab[tabId]?.scanSessionId != sessionId)) {
                    wasCancelled = true
                    break
                }
                if (mergedElements.size >= customLimits.maxElements) {
                    reachedLimit = true
                    partialReason = "Maximum element limit (${customLimits.maxElements}) reached during extended scan."
                    break
                }

                if (attachedFixture != null) {
                    attachedFixture.scrollY += customLimits.extendedScrollStepPx
                    val snippetIdx = stepIdx - 1
                    if (snippetIdx in attachedFixture.lazyScrollHtmlSnippets.indices) {
                        val lazySnippet = attachedFixture.lazyScrollHtmlSnippets[snippetIdx]
                        if (!attachedFixture.html.contains(lazySnippet)) {
                            attachedFixture.html = attachedFixture.html + "\n" + lazySnippet
                        }
                    }
                    onStepScrolled?.invoke(stepIdx, attachedFixture.scrollY)
                } else {
                    val scrollEval = automationEngine.evalJsWithResultInternal(
                        webView,
                        buildExtendedScrollStepScript(customLimits.extendedScrollStepPx)
                    )
                    if (scrollEval is JsEvalResult.Success) {
                        val scrollObj = runCatching { JSONObject(unwrapJsQuotedJson(scrollEval.rawValue)) }.getOrNull()
                        val moved = scrollObj?.optBoolean("moved", false) ?: false
                        val curY = scrollObj?.optInt("scrollY", 0) ?: 0
                        onStepScrolled?.invoke(stepIdx, curY)
                        if (!moved) break
                    } else {
                        break
                    }
                }

                if (customLimits.extendedScrollSettleMs > 0L) {
                    delay(customLimits.extendedScrollSettleMs)
                }
                coroutineContext.ensureActive()

                // Incremental scan pass at the new scroll position
                val stepScanState = if (attachedFixture != null) {
                    val raw = HtmlDomFixtureScanEngine.evaluateFixtureToJson(
                        fixture = attachedFixture,
                        sessionId = sessionId,
                        tabId = tabId,
                        expectedOrigin = initialState.canonicalOrigin,
                        documentId = "doc_ext_$stepIdx",
                        limits = customLimits
                    )
                    parseScanPayloadJson(
                        raw = raw,
                        expectedSessionId = sessionId,
                        expectedTabId = tabId,
                        expectedOrigin = initialState.canonicalOrigin,
                        domain = initialState.domain,
                        currentUrl = currentUrl,
                        customLimits = customLimits
                    )
                } else {
                    val scanScript = buildUniversalScanScript(
                        sessionId = sessionId,
                        tabId = tabId,
                        expectedOrigin = initialState.canonicalOrigin,
                        documentId = "doc_ext_$stepIdx",
                        limits = customLimits
                    )
                    val eval = automationEngine.evalJsWithResultInternal(webView, scanScript)
                    if (eval is JsEvalResult.Success) {
                        parseScanPayloadJson(
                            raw = eval.rawValue,
                            expectedSessionId = sessionId,
                            expectedTabId = tabId,
                            expectedOrigin = initialState.canonicalOrigin,
                            domain = initialState.domain,
                            currentUrl = currentUrl,
                            customLimits = customLimits
                        )
                    } else {
                        null
                    }
                }

                if (stepScanState != null) {
                    for (el in stepScanState.elements) {
                        val key = elementDedupeKey(el)
                        if (!mergedElements.containsKey(key)) {
                            if (mergedElements.size >= customLimits.maxElements) {
                                reachedLimit = true
                                partialReason = "Maximum element limit (${customLimits.maxElements}) reached during extended scan."
                                break
                            }
                            mergedElements[key] = el.copy(
                                id = "el_${mergedElements.size}",
                                discoverySource = "EXTENDED_SCROLL"
                            )
                        }
                    }
                    if (stepScanState.scanStatus == DomScanStatus.PARTIAL) {
                        reachedLimit = true
                        partialReason = stepScanState.summary.partialReason
                            ?: "Configured scan limit reached during extended scan."
                    }
                }
            }
        } catch (ce: CancellationException) {
            wasCancelled = true
            throw ce
        } finally {
            // Always restore user's original scroll position!
            if (attachedFixture != null) {
                attachedFixture.scrollX = originalScrollX
                attachedFixture.scrollY = originalScrollY
            } else {
                val restoreScript = buildRestoreScrollPositionScript(savedScrollStateJson)
                val webViewRef = WeakReference(webView)
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    runCatching { webView.evaluateJavascript(restoreScript, null) }
                } else {
                    mainHandler.post {
                        webViewRef.get()?.let { wv ->
                            runCatching { wv.evaluateJavascript(restoreScript, null) }
                        }
                    }
                }
            }
        }

        val finalElements = mergedElements.values.toList()
        val finalStatus = when {
            wasCancelled || session?.isCancelled == true -> DomScanStatus.CANCELLED
            reachedLimit -> DomScanStatus.PARTIAL
            else -> DomScanStatus.COMPLETED
        }
        val recomputedSummary = computeSummaryFromElements(
            elements = finalElements,
            formsCount = initialState.summary.formsFound,
            inaccessibleShadowRoots = initialState.summary.inaccessibleShadowRootsFound,
            inaccessibleFrames = initialState.inaccessibleFrames.size,
            dynamicUpdates = session?.dynamicUpdatesCount ?: 0,
            isPartial = finalStatus == DomScanStatus.PARTIAL,
            partialReason = partialReason
        )
        val completedExtended = initialState.copy(
            elements = finalElements,
            isScanning = false,
            isExtendedScanRunning = false,
            scanStatus = finalStatus,
            summary = recomputedSummary,
            errorMessage = if (finalStatus == DomScanStatus.PARTIAL) partialReason else null
        )
        val enrichedExtended = DomScannerIntelligence.enrichInspectionState(
            state = completedExtended,
            attachedFixture = attachedFixture
        )
        if (tabId.isNotEmpty()) {
            latestInspectionByTab[tabId] = enrichedExtended
        }
        return enrichedExtended
    }

    /**
     * Validates and applies incremental dynamic DOM mutation batches emitted via `ApexAutomationBridge.onDomMutationBatchJson`
     * or test mutation triggers.
     */
    fun processDynamicMutationBatch(
        rawJson: String,
        sourceTabId: String,
        sourceWebView: WebView?,
        liveWebViewUrl: String,
        currentNavigationEpoch: Long = -1L
    ): PageInspectionState? {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || trimmed.toByteArray(Charsets.UTF_8).size > limits.maxPayloadBytes) {
            return null
        }
        val obj = runCatching { JSONObject(unwrapJsQuotedJson(trimmed)) }.getOrNull() ?: return null
        val payloadSessionId = obj.optString("sessionId", "").trim()
        val payloadTabId = obj.optString("tabId", "").trim()
        val payloadOrigin = obj.optString("origin", "").trim()

        val session = activeSessionsByTab[sourceTabId]
        if (session == null || session.isCancelled) {
            if (sourceWebView != null && payloadSessionId.isNotEmpty()) {
                cleanupDynamicObserverBestEffort(sourceWebView, payloadSessionId)
            }
            return null
        }

        val expectedWv = session.webViewRef.get()
        if (sourceWebView != null && expectedWv != null && sourceWebView !== expectedWv) {
            onWebViewReplaced(sourceTabId, expectedWv, sourceWebView)
            return latestInspectionByTab[sourceTabId]
        }

        if (currentNavigationEpoch >= 0L && session.navigationEpoch > 0L && currentNavigationEpoch != session.navigationEpoch) {
            onTabNavigated(sourceTabId, liveWebViewUrl, sourceWebView)
            return latestInspectionByTab[sourceTabId]
        }

        if (payloadSessionId.isEmpty() || payloadSessionId != session.scanSessionId) {
            if (sourceWebView != null && payloadSessionId.isNotEmpty()) {
                cleanupDynamicObserverBestEffort(sourceWebView, payloadSessionId)
            }
            return null
        }
        if (payloadTabId.isEmpty() || payloadTabId != sourceTabId) return null
        if (session.expectedOrigin.isNotEmpty() && payloadOrigin.isNotEmpty() && payloadOrigin != session.expectedOrigin) {
            return null
        }
        if (liveWebViewUrl.isNotBlank() && session.expectedOrigin.isNotEmpty()) {
            val liveParsed = automationEngine.parseOrigin(liveWebViewUrl)
            if (liveParsed is RecordingOriginResult.Valid && liveParsed.canonicalOrigin != session.expectedOrigin) {
                onTabNavigated(sourceTabId, liveWebViewUrl, sourceWebView)
                return latestInspectionByTab[sourceTabId]
            }
        }

        val existingState = latestInspectionByTab[sourceTabId] ?: return null
        val currentMap = LinkedHashMap<String, DetectedPageElement>()
        existingState.elements.forEach { el ->
            currentMap[el.selector.ifBlank { el.id }] = el
        }

        // 1. Mark removed / replaced selectors as stale
        val removedArr = obj.optJSONArray("removedSelectors") ?: JSONArray()
        for (i in 0 until removedArr.length()) {
            val remSel = removedArr.optString(i, "").trim()
            if (remSel.isNotEmpty()) {
                val match = currentMap[remSel]
                if (match != null) {
                    currentMap[remSel] = match.copy(
                        stale = true,
                        visible = false,
                        interactable = false,
                        lastUpdatedAt = System.currentTimeMillis()
                    )
                }
            }
        }

        // 2. Upsert added or updated elements
        val updatedArr = obj.optJSONArray("updatedElements") ?: JSONArray()
        val now = System.currentTimeMillis()
        var reachedLimit = existingState.scanStatus == DomScanStatus.PARTIAL
        var partialReason = existingState.summary.partialReason

        for (i in 0 until updatedArr.length()) {
            val itemObj = updatedArr.optJSONObject(i) ?: continue
            val parsedEl = parseElementJson(
                item = itemObj,
                index = currentMap.size,
                scanSessionId = session.scanSessionId,
                tabId = sourceTabId,
                defaultDiscoverySource = "DYNAMIC_MUTATION",
                now = now
            )
            val key = parsedEl.selector.ifBlank { parsedEl.id }
            if (currentMap.containsKey(key)) {
                val prev = currentMap[key]!!
                currentMap[key] = parsedEl.copy(
                    id = prev.id,
                    discoveredAt = prev.discoveredAt,
                    lastUpdatedAt = now,
                    stale = false,
                    discoverySource = "DYNAMIC_MUTATION"
                )
            } else {
                val activeNonStaleCount = currentMap.values.count { !it.stale }
                if (activeNonStaleCount >= limits.maxElements) {
                    reachedLimit = true
                    partialReason = "Maximum element limit (${limits.maxElements}) reached after dynamic DOM updates."
                    break
                }
                currentMap[key] = parsedEl.copy(
                    id = "el_${currentMap.size}",
                    discoveredAt = now,
                    lastUpdatedAt = now,
                    stale = false,
                    discoverySource = "DYNAMIC_MUTATION"
                )
            }
        }

        session.dynamicUpdatesCount += 1
        val updatedList = currentMap.values.toList()
        val newStatus = if (reachedLimit) DomScanStatus.PARTIAL else DomScanStatus.COMPLETED
        val updatedSummary = computeSummaryFromElements(
            elements = updatedList,
            formsCount = existingState.summary.formsFound,
            inaccessibleShadowRoots = existingState.summary.inaccessibleShadowRootsFound,
            inaccessibleFrames = existingState.inaccessibleFrames.size,
            dynamicUpdates = session.dynamicUpdatesCount,
            isPartial = reachedLimit,
            partialReason = partialReason
        )
        val newState = existingState.copy(
            elements = updatedList,
            lastScannedAt = now,
            scanStatus = newStatus,
            summary = updatedSummary
        )
        latestInspectionByTab[sourceTabId] = newState
        return newState
    }

    /**
     * Recorder Integration (Requirement 19):
     * Matches an incoming RecordedDomEvent against discovered elements in the current tab/session,
     * enriching the recorded step with the element's accessibleName and highest-confidence unique locator.
     */
    fun matchRecordedEventToElement(
        event: RecordedDomEvent,
        inspectionState: PageInspectionState?
    ): DetectedPageElement? {
        if (inspectionState == null || inspectionState.elements.isEmpty()) return null
        val activeElements = inspectionState.elements.filter { !it.stale }
        if (activeElements.isEmpty()) return null

        val scopeFiltered = activeElements.filter { el ->
            (event.framePath.isEmpty() || el.framePath == event.framePath) &&
                (event.shadowHostPath.isEmpty() || el.shadowHostPath == event.shadowHostPath)
        }.ifEmpty { activeElements }

        val candidateSelectors = buildList {
            event.selector.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            event.locatorCandidates.filter { it.isUnique }.forEach { cand ->
                cand.selector.trim().takeIf { it.isNotEmpty() && !contains(it) }?.let { add(it) }
            }
        }
        for (sel in candidateSelectors) {
            val matches = scopeFiltered.filter { el ->
                el.selector == sel || el.locatorCandidates.any { it.selector == sel && it.isUnique }
            }
            if (matches.size == 1) return matches.first()
        }

        val cleanEventLabel = event.targetLabel
            .replace(Regex("\\s+(?:field|checkbox|option|button)$", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleanEventLabel.isNotEmpty()) {
            val exactLabelMatches = scopeFiltered.filter { el ->
                el.accessibleName.equals(cleanEventLabel, ignoreCase = true) ||
                    el.label.equals(cleanEventLabel, ignoreCase = true) ||
                    el.visibleText.equals(cleanEventLabel, ignoreCase = true)
            }
            if (exactLabelMatches.size == 1) return exactLabelMatches.first()
        }
        return null
    }

    /**
     * Manual Instructions Integration (Requirement 20):
     * Uses deterministic scanner metadata (accessibleName, label, role, controlKind, inputType, autocomplete)
     * to find candidate controls for a manual instruction step, reporting ambiguity if multiple match equally.
     */
    fun matchCandidatesForInstruction(
        clause: String,
        actionType: AutomationActionType,
        targetLabel: String,
        inspectionState: PageInspectionState?
    ): ManualInstructionCandidateMatch {
        if (inspectionState == null || inspectionState.elements.isEmpty()) {
            return ManualInstructionCandidateMatch(emptyList(), isAmbiguous = false, primaryCandidate = null)
        }
        val activeVisible = inspectionState.elements.filter { !it.stale && it.visible && it.controlKind != ControlKind.HIDDEN_INPUT }
        if (activeVisible.isEmpty()) {
            return ManualInstructionCandidateMatch(emptyList(), isAmbiguous = false, primaryCandidate = null)
        }

        val normTarget = targetLabel
            .replace(Regex("^(?:in|into|from|on|at)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(the|a|an|field|input|box|dropdown|button|checkbox|radio|control|area|selector|picker|sheet)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
        val lowerClause = clause.lowercase()
        val quotedValueLower = Regex("""["']([^"']+)["']""").find(clause)?.groupValues?.getOrNull(1)?.trim()?.lowercase().orEmpty()

        // Filter by compatible action/control semantics first
        val roleFiltered = activeVisible.filter { el ->
            val attrs = el.relevantAttributes
            val isWidgetOption = el.role in setOf("option", "radio", "gridcell", "menuitemradio", "menuitemcheckbox") ||
                attrs.containsKey("data-country-code") ||
                attrs.containsKey("data-dial-code") ||
                attrs.containsKey("data-code") ||
                attrs.containsKey("data-gender") ||
                attrs.containsKey("data-date") ||
                attrs.containsKey("data-day") ||
                attrs.containsKey("data-month") ||
                attrs.containsKey("data-year") ||
                attrs.containsKey("data-birth-year") ||
                attrs.containsKey("data-year-picker") ||
                attrs.containsKey("data-option") ||
                attrs.containsKey("data-value") ||
                attrs.containsKey("aria-pressed") ||
                attrs.containsKey("aria-haspopup")
            when (actionType) {
                AutomationActionType.INPUT, AutomationActionType.CLEAR, AutomationActionType.FILL_CREDENTIAL ->
                    el.editable || el.controlKind in setOf(
                        ControlKind.STANDARD_INPUT,
                        ControlKind.TEXTAREA,
                        ControlKind.CONTENTEDITABLE,
                        ControlKind.DATE_TIME,
                        ControlKind.SELECT,
                        ControlKind.CUSTOM_DROPDOWN,
                        ControlKind.ARIA_CONTROL
                    )
                AutomationActionType.SELECT ->
                    el.controlKind in setOf(
                        ControlKind.SELECT,
                        ControlKind.OPTION,
                        ControlKind.CUSTOM_DROPDOWN,
                        ControlKind.DATE_TIME,
                        ControlKind.STANDARD_INPUT,
                        ControlKind.RADIO,
                        ControlKind.ARIA_CONTROL
                    ) || el.dropdownKind != DropdownKind.NONE || isWidgetOption
                AutomationActionType.TOGGLE ->
                    el.controlKind in setOf(ControlKind.CHECKBOX, ControlKind.RADIO, ControlKind.ARIA_CONTROL) ||
                        el.role in setOf("checkbox", "radio", "switch", "menuitemcheckbox", "menuitemradio") ||
                        attrs.containsKey("aria-pressed")
                AutomationActionType.CLICK ->
                    el.interactable && el.tagName != "option"
                AutomationActionType.SUBMIT,
                AutomationActionType.FOCUS,
                AutomationActionType.BLUR,
                AutomationActionType.KEY_PRESS ->
                    el.interactable
                AutomationActionType.WAIT,
                AutomationActionType.SCROLL,
                AutomationActionType.NAVIGATE,
                AutomationActionType.EXTRACT,
                AutomationActionType.CONDITION,
                AutomationActionType.LOOP,
                AutomationActionType.SET_VARIABLE,
                AutomationActionType.CALL_WORKFLOW,
                AutomationActionType.UNSUPPORTED -> true
            }
        }.ifEmpty { activeVisible }

        // Score candidates deterministically
        data class ScoredCandidate(val element: DetectedPageElement, val score: Int)
        val scored = mutableListOf<ScoredCandidate>()

        fun compactNorm(raw: String): String = raw.lowercase().replace(Regex("[\\s_-]+"), "")
        val compactTarget = compactNorm(normTarget)

        val wantsCountryCode = lowerClause.contains("country code") ||
            lowerClause.contains("dial code") ||
            lowerClause.contains("calling code") ||
            lowerClause.contains("phone code") ||
            lowerClause.contains("area code")
        val wantsPhoneNumber = !wantsCountryCode && (
            lowerClause.contains("phone") ||
                lowerClause.contains("mobile") ||
                lowerClause.contains("telephone") ||
                lowerClause.contains("cell")
            )
        val wantsGender = lowerClause.contains("gender") ||
            lowerClause.contains("sex") ||
            quotedValueLower in setOf("male", "female", "non-binary", "nonbinary", "other", "prefer not to say")
        val isFourDigitYearValue = Regex("^(19\\d\\d|20\\d\\d)$").matches(quotedValueLower)
        val wantsBirthYear = lowerClause.contains("birth year") ||
            lowerClause.contains("year of birth") ||
            lowerClause.contains("bday-year") ||
            lowerClause.contains("bday_year") ||
            lowerClause.contains("birth_year") ||
            lowerClause.contains("dob year") ||
            lowerClause.contains("dob_year") ||
            (Regex("\\byear\\b").containsMatchIn(lowerClause) && (
                lowerClause.contains("birth") ||
                    lowerClause.contains("dob") ||
                    lowerClause.contains("born") ||
                    lowerClause.contains("age") ||
                    isFourDigitYearValue
                ))
        val wantsYearPart = wantsBirthYear || Regex("\\byear\\b").containsMatchIn(lowerClause)
        val wantsMonthPart = !wantsYearPart && (
            lowerClause.contains("birth month") ||
                lowerClause.contains("bday-month") ||
                lowerClause.contains("dob month") ||
                Regex("\\bmonth\\b").containsMatchIn(lowerClause)
            )
        val wantsDayPart = !wantsYearPart && !wantsMonthPart && (
            lowerClause.contains("birth day") ||
                lowerClause.contains("bday-day") ||
                lowerClause.contains("dob day") ||
                Regex("\\bday\\b").containsMatchIn(lowerClause)
            )
        val wantsDateOrCalendar = wantsBirthYear || wantsYearPart || wantsMonthPart || wantsDayPart ||
            lowerClause.contains("date of birth") ||
            lowerClause.contains("birthday") ||
            lowerClause.contains("birth date") ||
            lowerClause.contains("dob") ||
            lowerClause.contains("calendar") ||
            lowerClause.contains("date")

        for (el in roleFiltered) {
            val accLower = el.accessibleName.trim().lowercase()
            val lblLower = el.label.trim().lowercase()
            val visLower = el.visibleText.trim().lowercase()
            val nameLower = el.name.trim().lowercase()
            val phLower = el.placeholder.trim().lowercase()
            val idLower = el.htmlId.trim().lowercase()
            val hrefLower = el.href.trim().lowercase()
            val autoLower = el.autocomplete.trim().lowercase()
            val groupLower = el.fieldsetLegend.trim().lowercase()
            val nearbyLower = el.nearbyText.trim().lowercase()
            val containerLower = el.parentContainerSummary.trim().lowercase()
            val describedByLower = el.ariaAttributes["aria-describedby"].orEmpty().trim().lowercase()
            val attrsLower = el.relevantAttributes.entries.joinToString(" ") { "${it.key}=${it.value}" }.lowercase()
            val dtMeta = el.dateTimeMetadata
            val isCountryAttrEl = el.relevantAttributes.containsKey("data-country-code") ||
                el.relevantAttributes.containsKey("data-dial-code") ||
                el.relevantAttributes.containsKey("data-code") ||
                el.relevantAttributes.containsKey("data-country")
            val allIdentityLower = "$accLower $lblLower $nameLower $idLower $phLower $autoLower $attrsLower"
            val allContextLower = "$groupLower $nearbyLower $containerLower $describedByLower"
            val isUnrelatedNumericField = Regex(
                "(?:\\b|_)(zip|postal|postcode|pincode|otp|verif|verification|passcode|pin|cvv|cvc|csc|cc[-_]?|card[-_]?number|routing|iban|ssn|phone|mobile|tel|fax|qty|quantity|amount|price|cost|salary|income|street|apt|suite|floor|graduation|grad[-_]?year|vehicle|model[-_]?year|car[-_]?year|tax[-_]?year|fiscal|expir|exp[-_]?year)(?:\\b|_)"
            ).containsMatchIn(allIdentityLower)
            val isExplicitDayOrMonth = autoLower in setOf("bday-day", "bday-month") ||
                dtMeta?.pickerRole in setOf("day-input", "day-select", "month-input", "month-select", "month-nav", "calendar-day") ||
                dtMeta?.inputFormat in setOf("day", "month") ||
                (Regex("\\b(day|month|dd|mm)\\b").containsMatchIn("$accLower $lblLower $nameLower $idLower $phLower") &&
                    !Regex("\\b(year|yyyy)\\b").containsMatchIn("$accLower $lblLower $nameLower $idLower $phLower"))
            val hasBirthOrAgeContext = Regex(
                "(birth|birthday|bday|dob|date\\s*of\\s*birth|confirm\\s*your\\s*age|your\\s*age|age\\s*verification|born)"
            ).containsMatchIn("$allIdentityLower $allContextLower")
            val hasExplicitBirthYearIdentity = autoLower == "bday-year" ||
                el.relevantAttributes.containsKey("data-birth-year") ||
                Regex("(birth\\s*year|year\\s*of\\s*birth|bday[-_]?year|birth[-_]?year|dob[-_]?year)").containsMatchIn("$allIdentityLower $allContextLower")
            val hasYearCue = hasExplicitBirthYearIdentity ||
                dtMeta?.inputFormat == "year" ||
                dtMeta?.pickerRole in setOf("year-input", "year-select", "year-nav", "year-option", "year-list") ||
                el.relevantAttributes.containsKey("data-year") ||
                el.relevantAttributes.containsKey("data-year-picker") ||
                Regex("\\b(year|yyyy)\\b").containsMatchIn(allIdentityLower) ||
                Regex("^(19\\d\\d|20\\d\\d)$").matches(phLower)

            var score = 0
            if (normTarget.isNotEmpty()) {
                when {
                    accLower == normTarget || lblLower == normTarget || visLower == normTarget -> score += 100
                    compactTarget.isNotEmpty() && (
                        compactNorm(accLower) == compactTarget ||
                            compactNorm(lblLower) == compactTarget ||
                            compactNorm(visLower) == compactTarget
                        ) -> score += 92
                    nameLower == normTarget || idLower == normTarget || phLower == normTarget -> score += 90
                    compactTarget.isNotEmpty() && (
                        compactNorm(nameLower) == compactTarget ||
                            compactNorm(idLower) == compactTarget ||
                            compactNorm(phLower) == compactTarget
                        ) -> score += 84
                    accLower.contains(normTarget) || lblLower.contains(normTarget) || visLower.contains(normTarget) -> score += 75
                    compactTarget.length >= 4 && (
                        compactNorm(accLower).contains(compactTarget) ||
                            compactNorm(lblLower).contains(compactTarget) ||
                            compactNorm(visLower).contains(compactTarget)
                        ) -> score += 70
                    hrefLower.contains(normTarget) || (compactTarget.length >= 4 && compactNorm(hrefLower).contains(compactTarget)) -> score += 68
                    phLower.contains(normTarget) || nameLower.contains(normTarget) || groupLower.contains(normTarget) || nearbyLower.contains(normTarget) || containerLower.contains(normTarget) || describedByLower.contains(normTarget) -> score += 60
                    attrsLower.contains(normTarget) -> score += 55
                }
            }

            if (quotedValueLower.isNotEmpty()) {
                val valDataAttrs = listOfNotNull(
                    el.relevantAttributes["data-date"],
                    el.relevantAttributes["data-day"],
                    el.relevantAttributes["data-month"],
                    el.relevantAttributes["data-year"],
                    el.relevantAttributes["data-country-code"],
                    el.relevantAttributes["data-dial-code"],
                    el.relevantAttributes["data-code"],
                    el.relevantAttributes["data-country"],
                    el.relevantAttributes["data-gender"],
                    el.relevantAttributes["data-value"],
                    el.relevantAttributes["data-option"],
                    el.relevantAttributes["title"],
                    dtMeta?.selectedDate
                ).map { it.trim().lowercase() }
                if (accLower == quotedValueLower || visLower == quotedValueLower || valDataAttrs.any { it == quotedValueLower }) {
                    score += 85
                } else if (el.controlKind != ControlKind.SELECT &&
                    el.controlKind != ControlKind.STANDARD_INPUT &&
                    (accLower.contains(quotedValueLower) || visLower.contains(quotedValueLower) || valDataAttrs.any { it.contains(quotedValueLower) })
                ) {
                    score += 45
                }
            }

            // Semantic domain keyword matching (email, username, password, dob/birth-year, country/dial code, phone, gender, search)
            if (lowerClause.contains("email") && (el.inputType == "email" || autoLower.contains("email") || accLower.contains("email") || nameLower.contains("email"))) {
                score += 80
            }
            if ((lowerClause.contains("username") || lowerClause.contains("user name")) &&
                (autoLower == "username" || accLower.contains("user") || nameLower.contains("user"))
            ) {
                score += 80
            }
            if (lowerClause.contains("password") && (el.inputType == "password" || el.sensitiveCategory == SensitiveFieldCategory.PASSWORD)) {
                score += 85
            }
            if (wantsYearPart) {
                if (isUnrelatedNumericField) {
                    score -= 220
                } else if (isExplicitDayOrMonth) {
                    score -= 180
                } else {
                    when {
                        hasExplicitBirthYearIdentity -> score += 115
                        hasYearCue && hasBirthOrAgeContext -> score += 108
                        hasYearCue && dtMeta != null -> score += 95
                        hasYearCue -> score += 82
                    }
                    if (wantsBirthYear && !hasBirthOrAgeContext && !hasExplicitBirthYearIdentity && dtMeta == null) {
                        score -= 20
                    }
                }
            } else if (wantsMonthPart) {
                if (isUnrelatedNumericField || autoLower in setOf("bday-day", "bday-year") || dtMeta?.pickerRole in setOf("day-input", "day-select", "year-input", "year-select", "year-option")) {
                    score -= 180
                } else if (autoLower == "bday-month" || dtMeta?.pickerRole in setOf("month-input", "month-select", "month-nav") || Regex("\\b(month|mm)\\b").containsMatchIn(allIdentityLower)) {
                    score += 105
                }
            } else if (wantsDayPart) {
                if (isUnrelatedNumericField || autoLower in setOf("bday-month", "bday-year") || dtMeta?.pickerRole in setOf("month-input", "month-select", "year-input", "year-select", "year-option")) {
                    score -= 180
                } else if (autoLower == "bday-day" || dtMeta?.pickerRole in setOf("day-input", "day-select", "calendar-day") || Regex("\\b(day|dd)\\b").containsMatchIn(allIdentityLower)) {
                    score += 105
                }
            } else if (wantsDateOrCalendar &&
                !isUnrelatedNumericField &&
                (el.controlKind == ControlKind.DATE_TIME ||
                    dtMeta != null ||
                    autoLower.startsWith("bday") ||
                    accLower.contains("birth") ||
                    accLower.contains("date") ||
                    nameLower.contains("dob") ||
                    nameLower.contains("birth") ||
                    containerLower.contains("calendar") ||
                    containerLower.contains("datepicker"))
            ) {
                score += 80
            }
            if (wantsCountryCode &&
                (autoLower == "tel-country-code" ||
                    isCountryAttrEl ||
                    el.dropdownKind != DropdownKind.NONE ||
                    accLower.contains("country") ||
                    accLower.contains("dial") ||
                    Regex("^\\+\\d{1,4}\\b").containsMatchIn(accLower) ||
                    containerLower.contains("sheet") ||
                    containerLower.contains("country"))
            ) {
                score += 88
            } else if (lowerClause.contains("country") &&
                (autoLower.contains("country") || accLower.contains("country") || nameLower.contains("country") || lblLower.contains("country") || isCountryAttrEl)
            ) {
                score += 85
            }
            if (wantsPhoneNumber &&
                (el.inputType == "tel" ||
                    el.relevantAttributes["inputmode"]?.equals("tel", ignoreCase = true) == true ||
                    autoLower in setOf("tel", "tel-national") ||
                    accLower.contains("phone") ||
                    accLower.contains("mobile") ||
                    nameLower.contains("phone") ||
                    phLower.contains("phone")) &&
                !autoLower.contains("country-code")
            ) {
                score += 88
            }
            if (wantsGender &&
                (autoLower == "sex" ||
                    autoLower == "gender" ||
                    accLower.contains("gender") ||
                    accLower.contains("sex") ||
                    nameLower.contains("gender") ||
                    groupLower.contains("gender") ||
                    nearbyLower.contains("gender") ||
                    el.radioGroupName.contains("gender", ignoreCase = true) ||
                    el.relevantAttributes.containsKey("data-gender") ||
                    el.relevantAttributes.containsKey("data-sex"))
            ) {
                score += 82
            }
            if (lowerClause.contains("search") &&
                (el.inputType == "search" || el.role == "searchbox" || accLower.contains("search") || phLower.contains("search") || containerLower.contains("sheet"))
            ) {
                score += 80
            }

            if (score > 0) {
                scored.add(ScoredCandidate(el, score))
            }
        }

        if (scored.isEmpty()) {
            return ManualInstructionCandidateMatch(emptyList(), isAmbiguous = false, primaryCandidate = null)
        }

        val maxScore = scored.maxOf { it.score }
        val topMatches = scored.filter { it.score >= maxScore - 5 }.map { it.element }
        val isAmbiguous = topMatches.size > 1 || (topMatches.size == 1 && topMatches.first().isLocatorAmbiguous)
        return ManualInstructionCandidateMatch(
            matchedElements = topMatches,
            isAmbiguous = isAmbiguous,
            primaryCandidate = if (topMatches.size == 1 && !topMatches.first().isLocatorAmbiguous) topMatches.first() else topMatches.firstOrNull()
        )
    }

    internal fun parseScanPayloadJson(
        raw: String,
        expectedSessionId: String,
        expectedTabId: String,
        expectedOrigin: String,
        domain: String,
        currentUrl: String,
        customLimits: ScannerLimits = limits
    ): PageInspectionState {
        val now = System.currentTimeMillis()
        if (raw.isBlank() || raw == "null") {
            return PageInspectionState(
                domain = domain,
                url = currentUrl,
                elements = emptyList(),
                isScanning = false,
                lastScannedAt = now,
                errorMessage = "Unable to access active document DOM.",
                scanSessionId = expectedSessionId,
                tabId = expectedTabId,
                canonicalOrigin = expectedOrigin,
                scanStatus = DomScanStatus.FAILED
            )
        }

        val unquoted = unwrapJsQuotedJson(raw)
        if (unquoted.toByteArray(Charsets.UTF_8).size > customLimits.maxPayloadBytes) {
            return PageInspectionState(
                domain = domain,
                url = currentUrl,
                elements = emptyList(),
                isScanning = false,
                lastScannedAt = now,
                errorMessage = "Scanner payload exceeded maximum size limit (${customLimits.maxPayloadBytes} bytes).",
                scanSessionId = expectedSessionId,
                tabId = expectedTabId,
                canonicalOrigin = expectedOrigin,
                scanStatus = DomScanStatus.FAILED
            )
        }

        return runCatching {
            val obj = JSONObject(unquoted)
            val err = obj.optString("error", "").takeIf { it.isNotBlank() }
            if (err != null && !obj.has("elements")) {
                return@runCatching PageInspectionState(
                    domain = domain,
                    url = currentUrl,
                    elements = emptyList(),
                    isScanning = false,
                    lastScannedAt = now,
                    errorMessage = err,
                    scanSessionId = expectedSessionId,
                    tabId = expectedTabId,
                    canonicalOrigin = expectedOrigin,
                    scanStatus = DomScanStatus.FAILED
                )
            }

            // Validate session/tab/origin if present in payload
            val payloadSession = obj.optString("sessionId", "")
            if (payloadSession.isNotEmpty() && expectedSessionId.isNotEmpty() && payloadSession != expectedSessionId) {
                return@runCatching PageInspectionState(
                    domain = domain,
                    url = currentUrl,
                    elements = emptyList(),
                    isScanning = false,
                    lastScannedAt = now,
                    errorMessage = "Scan session mismatch ($payloadSession != $expectedSessionId).",
                    scanSessionId = expectedSessionId,
                    tabId = expectedTabId,
                    canonicalOrigin = expectedOrigin,
                    scanStatus = DomScanStatus.SESSION_INVALIDATED
                )
            }
            val payloadTab = obj.optString("tabId", "")
            if (payloadTab.isNotEmpty() && expectedTabId.isNotEmpty() && payloadTab != expectedTabId) {
                return@runCatching PageInspectionState(
                    domain = domain,
                    url = currentUrl,
                    elements = emptyList(),
                    isScanning = false,
                    lastScannedAt = now,
                    errorMessage = "Scan callback originated from wrong tab ($payloadTab != $expectedTabId).",
                    scanSessionId = expectedSessionId,
                    tabId = expectedTabId,
                    canonicalOrigin = expectedOrigin,
                    scanStatus = DomScanStatus.SESSION_INVALIDATED
                )
            }
            val payloadOrigin = obj.optString("origin", "")
            if (payloadOrigin.isNotEmpty() && expectedOrigin.isNotEmpty() && payloadOrigin != expectedOrigin) {
                return@runCatching PageInspectionState(
                    domain = domain,
                    url = currentUrl,
                    elements = emptyList(),
                    isScanning = false,
                    lastScannedAt = now,
                    errorMessage = "Scan origin mismatch ($payloadOrigin != $expectedOrigin).",
                    scanSessionId = expectedSessionId,
                    tabId = expectedTabId,
                    canonicalOrigin = expectedOrigin,
                    scanStatus = DomScanStatus.DOCUMENT_CHANGED
                )
            }

            val arr = obj.optJSONArray("elements") ?: JSONArray()
            val deduped = LinkedHashMap<String, DetectedPageElement>()
            var truncatedByLimit = obj.optBoolean("isPartial", false)
            var partialReason = obj.optString("partialReason", "").takeIf { it.isNotBlank() }

            for (i in 0 until arr.length()) {
                if (deduped.size >= customLimits.maxElements) {
                    truncatedByLimit = true
                    if (partialReason == null) {
                        partialReason = "Reached maximum discovered elements limit (${customLimits.maxElements})."
                    }
                    break
                }
                val item = arr.optJSONObject(i) ?: continue
                val parsed = parseElementJson(
                    item = item,
                    index = deduped.size,
                    scanSessionId = expectedSessionId,
                    tabId = expectedTabId,
                    defaultDiscoverySource = "PASSIVE_SCAN",
                    now = now
                )
                val key = elementDedupeKey(parsed)
                if (!deduped.containsKey(key)) {
                    deduped[key] = parsed
                }
            }

            val inaccessibleFramesList = buildList {
                val framesArr = obj.optJSONArray("inaccessibleFrames")
                if (framesArr != null) {
                    for (i in 0 until framesArr.length()) {
                        val fObj = framesArr.optJSONObject(i) ?: continue
                        add(InaccessibleFrameInfo.fromJson(fObj))
                    }
                }
            }

            val formsCount = obj.optInt("formsFound", 0)
            val inaccessibleShadowCount = obj.optInt("inaccessibleShadowRoots", 0)
            val elementsList = deduped.values.toList()
            val status = when {
                err != null && elementsList.isEmpty() -> DomScanStatus.FAILED
                truncatedByLimit -> DomScanStatus.PARTIAL
                else -> DomScanStatus.COMPLETED
            }
            val summary = computeSummaryFromElements(
                elements = elementsList,
                formsCount = formsCount,
                inaccessibleShadowRoots = inaccessibleShadowCount,
                inaccessibleFrames = maxOf(inaccessibleFramesList.size, obj.optInt("inaccessibleCrossOriginFrames", 0)),
                dynamicUpdates = obj.optInt("dynamicUpdatesProcessed", 0),
                isPartial = status == DomScanStatus.PARTIAL,
                partialReason = partialReason
            )

            PageInspectionState(
                domain = domain,
                url = currentUrl,
                elements = elementsList,
                isScanning = false,
                lastScannedAt = now,
                errorMessage = err ?: if (status == DomScanStatus.PARTIAL) partialReason else null,
                scanSessionId = expectedSessionId,
                tabId = expectedTabId,
                canonicalOrigin = expectedOrigin,
                scanStatus = status,
                summary = summary,
                inaccessibleFrames = inaccessibleFramesList
            )
        }.getOrElse {
            PageInspectionState(
                domain = domain,
                url = currentUrl,
                elements = emptyList(),
                isScanning = false,
                lastScannedAt = now,
                errorMessage = "Failed to parse page inspection result.",
                scanSessionId = expectedSessionId,
                tabId = expectedTabId,
                canonicalOrigin = expectedOrigin,
                scanStatus = DomScanStatus.FAILED
            )
        }
    }

    private fun parseElementJson(
        item: JSONObject,
        index: Int,
        scanSessionId: String,
        tabId: String,
        defaultDiscoverySource: String,
        now: Long
    ): DetectedPageElement {
        val id = item.optString("id", "el_$index").ifBlank { "el_$index" }
        val tagName = item.optString("tag", item.optString("tagName", "element")).lowercase()
        val inputType = item.optString("inputType", "").lowercase()
        val role = item.optString("role", "").ifBlank {
            when (tagName) {
                "input" -> inputType.ifBlank { "text" }
                "a" -> "link"
                else -> tagName
            }
        }
        val accessibleName = item.optString("accessibleName", item.optString("label", "")).trim()
        val rawLabel = item.optString("label", accessibleName).trim().ifBlank {
            accessibleName.ifBlank { tagName }
        }
        val effectiveAccName = accessibleName.ifBlank { rawLabel }
        val placeholder = item.optString("placeholder", "")
        val nameAttr = item.optString("name", "")
        val htmlId = item.optString("htmlId", "")
        val autocomplete = item.optString("autocomplete", "")

        val explicitSensitive = SensitiveFieldCategory.fromId(item.optString("sensitiveCategory"))
        val sensitiveCategory = if (explicitSensitive != SensitiveFieldCategory.NONE) {
            explicitSensitive
        } else {
            SensitiveFieldClassifier.classify(
                tagName = tagName,
                inputType = inputType,
                autocomplete = autocomplete,
                name = nameAttr,
                htmlId = htmlId,
                label = rawLabel,
                accessibleName = effectiveAccName,
                placeholder = placeholder
            )
        }

        // Strict Privacy & Phase 4B Rule: Never capture values from password or sensitive fields!
        val safeValue = if (sensitiveCategory.isSensitive || inputType == "password") {
            ""
        } else {
            item.optString("safeValue", "").take(64)
        }

        val isCredentialCapable = item.optBoolean(
            "isCredentialCapable",
            SensitiveFieldClassifier.isCredentialCapableField(
                tagName = tagName,
                inputType = inputType,
                autocomplete = autocomplete,
                name = nameAttr,
                htmlId = htmlId,
                label = rawLabel,
                accessibleName = effectiveAccName,
                sensitiveCategory = sensitiveCategory
            )
        )

        val framePath = parseJsonStringList(item.optJSONArray("framePath"))
        val shadowHostPath = parseJsonStringList(item.optJSONArray("shadowHostPath"))

        val ariaMap = buildMap {
            val ariaObj = item.optJSONObject("ariaAttributes")
            if (ariaObj != null) {
                val keys = ariaObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = ariaObj.optString(k, "")
                    if (v.isNotEmpty()) put(k, v)
                }
            }
        }

        val candidates = buildList {
            val candArr = item.optJSONArray("locatorCandidates")
            if (candArr != null) {
                for (cIdx in 0 until candArr.length()) {
                    val cObj = candArr.optJSONObject(cIdx) ?: continue
                    add(LocatorCandidate.fromJson(cObj))
                }
            }
        }

        val primarySelector = item.optString("selector", "").ifBlank {
            candidates.firstOrNull()?.selector.orEmpty()
        }
        val primaryStrategy = if (item.has("locatorStrategy")) {
            LocatorStrategy.fromId(item.optString("locatorStrategy"))
        } else {
            candidates.firstOrNull()?.strategy ?: when {
                shadowHostPath.isNotEmpty() -> LocatorStrategy.SHADOW_DOM_CHAIN
                framePath.isNotEmpty() -> LocatorStrategy.IFRAME_CHAIN
                htmlId.isNotBlank() && primarySelector.startsWith("#") -> LocatorStrategy.STABLE_ID
                else -> LocatorStrategy.SCOPED_CSS
            }
        }
        val locatorConfidence = item.optDouble(
            "locatorConfidence",
            (candidates.firstOrNull()?.confidence ?: primaryStrategy.baseConfidence).toDouble()
        ).toFloat()
        val isLocatorAmbiguous = item.optBoolean(
            "isLocatorAmbiguous",
            candidates.firstOrNull()?.isAmbiguous ?: false
        )

        val relevantAttributes = buildMap {
            val attrObj = item.optJSONObject("relevantAttributes")
            if (attrObj != null) {
                val keys = attrObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val kl = k.lowercase()
                    if (kl == "value" || kl.contains("password") || kl.contains("secret") || kl.contains("token")) continue
                    val v = attrObj.optString(k, "").trim()
                    if (v.isNotEmpty()) put(k, v.take(120))
                }
            }
        }
        val dateTimeMeta = DateTimeMetadata.fromJson(item.optJSONObject("dateTimeMetadata"))
        val readonly = item.optBoolean("readonly", false)
        val controlKind = if (item.has("controlKind")) {
            ControlKind.fromId(item.optString("controlKind"))
        } else {
            inferControlKind(
                tagName = tagName,
                inputType = inputType,
                role = role,
                contentEditable = item.optString("contentEditable", ""),
                ariaMap = ariaMap,
                dateTimeMeta = dateTimeMeta,
                autocomplete = autocomplete,
                relevantAttributes = relevantAttributes,
                readonly = readonly,
                placeholder = placeholder
            )
        }
        val dropdownKind = if (item.has("dropdownKind")) {
            DropdownKind.fromId(item.optString("dropdownKind"))
        } else {
            inferDropdownKind(tagName, role, ariaMap, relevantAttributes, dateTimeMeta)
        }

        val isHiddenInput = controlKind == ControlKind.HIDDEN_INPUT || inputType == "hidden"
        val visible = if (isHiddenInput) false else item.optBoolean("visible", true)
        val disabled = item.optBoolean("disabled", ariaMap["aria-disabled"].equals("true", ignoreCase = true))
        val editable = item.optBoolean(
            "editable",
            !disabled && !readonly && (
                controlKind == ControlKind.STANDARD_INPUT ||
                    controlKind == ControlKind.TEXTAREA ||
                    controlKind == ControlKind.CONTENTEDITABLE ||
                    (controlKind == ControlKind.DATE_TIME && tagName == "input") ||
                    role in setOf("textbox", "searchbox", "spinbutton")
                )
        )
        val interactable = if (isHiddenInput || disabled || !visible) {
            false
        } else {
            item.optBoolean("interactable", item.optBoolean("isInteractive", true))
        }

        val discoverySource = item.optString("discoverySource", "").ifBlank {
            when {
                shadowHostPath.isNotEmpty() -> "SHADOW_DOM"
                framePath.isNotEmpty() -> "SAME_ORIGIN_IFRAME"
                else -> defaultDiscoverySource
            }
        }

        val visibleText = if (sensitiveCategory.isSensitive || inputType == "password") {
            ""
        } else {
            item.optString("visibleText", "").trim().take(120)
        }
        val hrefVal = item.optString("href", "").trim().take(180)
        val parentContainerSummary = item.optString("parentContainerSummary", "").trim().take(120)
        val nearbyText = if (sensitiveCategory.isSensitive) {
            ""
        } else {
            item.optString("nearbyText", "").trim().take(120)
        }
        val checkedVal: Boolean? = when {
            item.has("checked") && !item.isNull("checked") -> item.optBoolean("checked")
            ariaMap["aria-checked"] != null -> ariaMap["aria-checked"].equals("true", ignoreCase = true)
            ariaMap["aria-pressed"] != null -> ariaMap["aria-pressed"].equals("true", ignoreCase = true)
            else -> null
        }
        val radioGroupName = item.optString("radioGroupName", "").ifBlank {
            if (controlKind == ControlKind.RADIO) nameAttr.ifBlank { item.optString("fieldsetLegend", "") } else ""
        }

        return DetectedPageElement(
            id = id,
            tagName = tagName,
            role = role,
            label = rawLabel,
            selector = primarySelector,
            inputType = inputType,
            isInteractive = interactable,
            scanSessionId = scanSessionId,
            tabId = tabId,
            documentId = item.optString("documentId", ""),
            framePath = framePath,
            frameUrl = item.optString("frameUrl", ""),
            frameOrigin = item.optString("frameOrigin", ""),
            shadowHostPath = shadowHostPath,
            controlKind = controlKind,
            dropdownKind = dropdownKind,
            accessibleName = effectiveAccName,
            placeholder = placeholder,
            name = nameAttr,
            htmlId = htmlId,
            safeValue = safeValue,
            checked = checkedVal,
            selected = if (item.has("selected") && !item.isNull("selected")) item.optBoolean("selected") else null,
            required = item.optBoolean("required", ariaMap["aria-required"].equals("true", ignoreCase = true)),
            disabled = disabled,
            readonly = readonly,
            editable = editable,
            visible = visible,
            inViewport = item.optBoolean("inViewport", true),
            interactable = interactable,
            contentEditable = item.optString("contentEditable", ""),
            autocomplete = autocomplete,
            ariaAttributes = ariaMap,
            controlledElementId = item.optString("controlledElementId", ariaMap["aria-controls"].orEmpty()),
            formGroupId = item.optString("formGroupId", ""),
            formName = item.optString("formName", ""),
            fieldsetLegend = item.optString("fieldsetLegend", ""),
            radioGroupName = radioGroupName,
            dateTimeMetadata = dateTimeMeta,
            sensitiveCategory = sensitiveCategory,
            isCredentialCapable = isCredentialCapable,
            locatorCandidates = candidates,
            locatorStrategy = primaryStrategy,
            locatorConfidence = locatorConfidence,
            isLocatorAmbiguous = isLocatorAmbiguous,
            discoverySource = discoverySource,
            discoveredAt = item.optLong("discoveredAt", now),
            lastUpdatedAt = item.optLong("lastUpdatedAt", now),
            stale = item.optBoolean("stale", false),
            href = hrefVal,
            visibleText = visibleText,
            parentContainerSummary = parentContainerSummary,
            nearbyText = nearbyText,
            relevantAttributes = relevantAttributes,
            boundingBox = ElementBoundingBox.fromJson(item.optJSONObject("boundingBox"))
        )
    }

    internal fun computeSummaryFromElements(
        elements: List<DetectedPageElement>,
        formsCount: Int,
        inaccessibleShadowRoots: Int,
        inaccessibleFrames: Int,
        dynamicUpdates: Int,
        isPartial: Boolean,
        partialReason: String?
    ): DomScanSummary {
        val active = elements.filter { !it.stale }
        return DomScanSummary(
            totalDiscovered = active.size,
            formsFound = maxOf(formsCount, active.mapNotNull { it.formGroupId.takeIf { f -> f.isNotBlank() } }.distinct().size),
            inputFieldsFound = active.count {
                it.controlKind in setOf(
                    ControlKind.STANDARD_INPUT,
                    ControlKind.TEXTAREA,
                    ControlKind.FILE_INPUT,
                    ControlKind.DATE_TIME
                ) || it.role in setOf("textbox", "searchbox", "spinbutton")
            },
            hiddenInputsFound = active.count { it.controlKind == ControlKind.HIDDEN_INPUT || it.inputType == "hidden" },
            buttonsFound = active.count { it.controlKind == ControlKind.BUTTON || it.role == "button" },
            linksFound = active.count { it.controlKind == ControlKind.LINK || it.role == "link" },
            dropdownsFound = active.count {
                it.controlKind in setOf(ControlKind.SELECT, ControlKind.CUSTOM_DROPDOWN) ||
                    it.dropdownKind != DropdownKind.NONE ||
                    it.role in setOf("combobox", "listbox")
            },
            checkboxesFound = active.count { it.controlKind == ControlKind.CHECKBOX || it.role in setOf("checkbox", "switch") },
            radioButtonsFound = active.count { it.controlKind == ControlKind.RADIO || it.role == "radio" },
            dateTimeControlsFound = active.count { it.controlKind == ControlKind.DATE_TIME || it.dateTimeMetadata != null },
            contentEditableFound = active.count { it.controlKind == ControlKind.CONTENTEDITABLE || it.contentEditable.isNotBlank() },
            ariaControlsFound = active.count { it.controlKind == ControlKind.ARIA_CONTROL || it.ariaAttributes.isNotEmpty() },
            shadowDomElementsFound = active.count { it.shadowHostPath.isNotEmpty() },
            inaccessibleShadowRootsFound = inaccessibleShadowRoots,
            sameOriginIframeElementsFound = active.count { it.framePath.isNotEmpty() },
            inaccessibleCrossOriginFrames = inaccessibleFrames,
            dynamicUpdatesProcessed = dynamicUpdates,
            staleElementsCount = elements.count { it.stale },
            ambiguousLocatorsCount = active.count { it.isLocatorAmbiguous },
            offScreenElementsFound = active.count { !it.inViewport && it.visible },
            isPartial = isPartial,
            partialReason = partialReason
        )
    }

    private fun elementDedupeKey(el: DetectedPageElement): String {
        val frameKey = el.framePath.joinToString("/")
        val shadowKey = el.shadowHostPath.joinToString("/")
        val ambigSuffix = if (el.isLocatorAmbiguous) "|${el.id}" else ""
        return "$frameKey|$shadowKey|${el.tagName}|${el.selector}|${el.accessibleName}$ambigSuffix"
    }

    private fun parseJsonStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) add(s)
            }
        }
    }

    private fun inferControlKind(
        tagName: String,
        inputType: String,
        role: String,
        contentEditable: String,
        ariaMap: Map<String, String>,
        dateTimeMeta: DateTimeMetadata? = null,
        autocomplete: String = "",
        relevantAttributes: Map<String, String> = emptyMap(),
        readonly: Boolean = false,
        placeholder: String = ""
    ): ControlKind {
        if (contentEditable.equals("true", ignoreCase = true) ||
            contentEditable.equals("plaintext-only", ignoreCase = true) ||
            contentEditable.equals("inherited", ignoreCase = true)
        ) {
            return ControlKind.CONTENTEDITABLE
        }
        val lowerRole = role.lowercase()
        val hasPopup = ariaMap["aria-haspopup"]?.lowercase().orEmpty()
        val isCalendarNav = dateTimeMeta?.pickerRole in setOf("month-nav", "year-nav", "today-action", "clear-action")
        val isDatePartInput = autocomplete.lowercase().startsWith("bday") ||
            Regex("(?:yyyy|mm/dd|dd/mm)", RegexOption.IGNORE_CASE).containsMatchIn(placeholder)
        return when (tagName) {
            "input" -> when (inputType) {
                "hidden" -> ControlKind.HIDDEN_INPUT
                "checkbox" -> ControlKind.CHECKBOX
                "radio" -> ControlKind.RADIO
                "submit", "reset", "button" -> ControlKind.BUTTON
                "date", "datetime-local", "time", "month", "week" -> ControlKind.DATE_TIME
                "file" -> ControlKind.FILE_INPUT
                else -> when {
                    (dateTimeMeta != null || isDatePartInput) && !isCalendarNav -> ControlKind.DATE_TIME
                    readonly && (lowerRole in setOf("combobox", "listbox") || hasPopup in setOf("listbox", "dialog", "menu")) ->
                        ControlKind.CUSTOM_DROPDOWN
                    else -> ControlKind.STANDARD_INPUT
                }
            }
            "textarea" -> ControlKind.TEXTAREA
            "select" -> ControlKind.SELECT
            "option" -> ControlKind.OPTION
            "button" -> when {
                isCalendarNav -> ControlKind.BUTTON
                dateTimeMeta != null && (
                    dateTimeMeta.pickerRole in setOf("calendar-day", "calendar-grid", "trigger", "date-trigger") ||
                        relevantAttributes.containsKey("data-datepicker") ||
                        relevantAttributes.containsKey("data-timepicker") ||
                        relevantAttributes.containsKey("data-date") ||
                        relevantAttributes.containsKey("data-day")
                    ) -> ControlKind.DATE_TIME
                hasPopup in setOf("listbox", "dialog", "menu", "true") -> ControlKind.CUSTOM_DROPDOWN
                else -> ControlKind.BUTTON
            }
            "a" -> ControlKind.LINK
            "summary", "details" -> ControlKind.SUMMARY_DETAILS
            "form" -> ControlKind.FORM_CONTAINER
            else -> when (lowerRole) {
                "combobox", "listbox" -> ControlKind.CUSTOM_DROPDOWN
                "option" -> ControlKind.OPTION
                "checkbox", "switch", "menuitemcheckbox" -> ControlKind.CHECKBOX
                "radio", "menuitemradio" -> ControlKind.RADIO
                "button" -> when {
                    isCalendarNav -> ControlKind.BUTTON
                    dateTimeMeta != null -> ControlKind.DATE_TIME
                    hasPopup in setOf("listbox", "dialog", "menu") -> ControlKind.CUSTOM_DROPDOWN
                    else -> ControlKind.BUTTON
                }
                "link" -> ControlKind.LINK
                "grid", "gridcell" -> if (dateTimeMeta != null || relevantAttributes.containsKey("data-date") || relevantAttributes.containsKey("data-day")) {
                    ControlKind.DATE_TIME
                } else {
                    ControlKind.ARIA_CONTROL
                }
                "textbox", "searchbox", "slider", "spinbutton",
                "menu", "menuitem", "tab", "tree", "treeitem", "dialog" -> ControlKind.ARIA_CONTROL
                else -> when {
                    dateTimeMeta != null && !isCalendarNav -> ControlKind.DATE_TIME
                    relevantAttributes.containsKey("data-country-code") ||
                        relevantAttributes.containsKey("data-dial-code") ||
                        relevantAttributes.containsKey("data-option") -> ControlKind.OPTION
                    hasPopup in setOf("listbox", "dialog", "menu", "true") -> ControlKind.CUSTOM_DROPDOWN
                    ariaMap.isNotEmpty() -> ControlKind.ARIA_CONTROL
                    else -> ControlKind.OTHER_INTERACTIVE
                }
            }
        }
    }

    private fun inferDropdownKind(
        tagName: String,
        role: String,
        ariaMap: Map<String, String>,
        relevantAttributes: Map<String, String> = emptyMap(),
        dateTimeMeta: DateTimeMetadata? = null
    ): DropdownKind {
        if (tagName == "select") return DropdownKind.NATIVE_SELECT
        val r = role.lowercase()
        val hasPopup = ariaMap["aria-haspopup"]?.lowercase().orEmpty()
        val auto = ariaMap["aria-autocomplete"]?.lowercase().orEmpty()
        val isDateTrigger = dateTimeMeta != null && !relevantAttributes.containsKey("data-dial-code") &&
            !relevantAttributes.containsKey("data-country-code")
        return when {
            auto in setOf("list", "both", "inline") -> DropdownKind.AUTOCOMPLETE
            r == "combobox" || hasPopup == "listbox" -> DropdownKind.CUSTOM_COMBOBOX
            r == "listbox" -> DropdownKind.CUSTOM_LISTBOX
            r == "menu" || hasPopup == "menu" -> DropdownKind.MENU
            (hasPopup == "dialog" || hasPopup == "true") && !isDateTrigger -> DropdownKind.MENU
            else -> DropdownKind.NONE
        }
    }

    private fun unwrapJsQuotedJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return ""
        return if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            runCatching { JSONTokener(trimmed).nextValue().toString() }.getOrDefault(trimmed)
        } else {
            trimmed
        }
    }

    // =========================================================================
    // PRODUCTION JAVASCRIPT UNIVERSAL DOM SCANNER SCRIPTS
    // =========================================================================

    fun buildUniversalScanScript(
        sessionId: String,
        tabId: String,
        expectedOrigin: String,
        documentId: String = "doc_main",
        limits: ScannerLimits = this.limits
    ): String {
        val qSessionId = JSONObject.quote(sessionId)
        val qTabId = JSONObject.quote(tabId)
        val qExpectedOrigin = JSONObject.quote(expectedOrigin)
        val qDocId = JSONObject.quote(documentId)
        return """
            (function() {
                try {
                    const startTime = Date.now();
                    const MAX_ELEMENTS = ${limits.maxElements};
                    const MAX_SHADOW_DEPTH = ${limits.maxShadowDepth};
                    const MAX_IFRAME_DEPTH = ${limits.maxIframeDepth};
                    const TIME_BUDGET_MS = ${limits.maxScanDurationMs};
                    const SESSION_ID = $qSessionId;
                    const TAB_ID = $qTabId;
                    const EXPECTED_ORIGIN = $qExpectedOrigin;
                    const DOC_ID = $qDocId;

                    function getCanonicalOrigin(win) {
                        try {
                            const loc = (win || window).location;
                            if (!loc) return '';
                            const proto = String(loc.protocol || '').toLowerCase();
                            if (proto !== 'http:' && proto !== 'https:') return '';
                            const scheme = proto.slice(0, -1);
                            const host = String(loc.hostname || '').trim().replace(/\.+$/, '').toLowerCase();
                            if (!host) return '';
                            const rawPort = String(loc.port || '');
                            const port = rawPort ? parseInt(rawPort, 10) : (scheme === 'http' ? 80 : 443);
                            if (!port || isNaN(port) || port < 1 || port > 65535) return '';
                            return scheme + '://' + host + ':' + port;
                        } catch (_) {
                            return '';
                        }
                    }

                    const actualOrigin = getCanonicalOrigin(window);
                    if (EXPECTED_ORIGIN && actualOrigin && actualOrigin !== EXPECTED_ORIGIN) {
                        return JSON.stringify({
                            sessionId: SESSION_ID,
                            tabId: TAB_ID,
                            origin: actualOrigin,
                            error: 'Document origin changed during scan',
                            elements: []
                        });
                    }

                    function escCss(v) {
                        if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(String(v));
                        return String(v).replace(/([ !"#$%&'()*+,.\/:;<=>?@\[\\\]^`{|}~])/g, '\\$1');
                    }
                    function escAttr(v) {
                        return String(v).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\r|\n/g, ' ');
                    }
                    function normText(s) {
                        return String(s || '').replace(/\s+/g, ' ').trim();
                    }
                    function isStableId(id) {
                        if (!id) return false;
                        const clean = String(id).trim();
                        if (!clean || clean.length > 80 || /\s/.test(clean)) return false;
                        if (/^(?:react-select-\d+.*|:r[0-9a-z]+:|mui-\d+|ember\d+|el-id-\d+.*|headlessui-.*-\d+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|_[0-9a-z]{7,}|\d{5,})$/i.test(clean)) return false;
                        if (/^(?:css-[0-9a-z]{5,}|sc-[a-zA-Z0-9]{5,}|jsx-\d+|svelte-[0-9a-z]{5,}|tw-[0-9a-f]{6,}|style_[0-9a-f]{6,})$/.test(clean)) return false;
                        if (/\d{6,}/.test(clean)) return false;
                        return true;
                    }

                    const INTERACTIVE_ROLES = new Set([
                        'textbox', 'searchbox', 'combobox', 'listbox', 'option',
                        'checkbox', 'radio', 'switch', 'button', 'link',
                        'slider', 'spinbutton', 'menu', 'menuitem', 'menuitemcheckbox', 'menuitemradio',
                        'tab', 'tree', 'treeitem', 'grid', 'gridcell', 'dialog'
                    ]);
                    const ARIA_ATTRS = [
                        'role', 'aria-label', 'aria-labelledby', 'aria-describedby',
                        'aria-controls', 'aria-expanded', 'aria-selected', 'aria-checked',
                        'aria-pressed', 'aria-current', 'aria-modal',
                        'aria-disabled', 'aria-required', 'aria-haspopup', 'aria-autocomplete',
                        'aria-valuemin', 'aria-valuemax', 'aria-valuenow'
                    ];
                    const DATA_TEST_ATTRS = ['data-testid', 'data-test', 'data-qa', 'data-cy', 'data-automation-id'];
                    const WIDGET_DATA_ATTRS = ['data-date', 'data-country-code', 'data-dial-code', 'data-code', 'data-day', 'data-month', 'data-year', 'data-gender', 'data-sex', 'data-option', 'data-value'];

                    function getHiddenControlForLabel(el, rootDoc) {
                        if (!el || (el.tagName || '').toLowerCase() !== 'label') return null;
                        try {
                            let ctrl = null;
                            const forId = el.getAttribute && el.getAttribute('for');
                            if (forId) {
                                ctrl = (rootDoc && rootDoc.getElementById) ? rootDoc.getElementById(forId) : document.getElementById(forId);
                            }
                            if (!ctrl && el.querySelector) {
                                ctrl = el.querySelector('input[type="radio"], input[type="checkbox"]');
                            }
                            if (!ctrl || (ctrl.tagName || '').toLowerCase() !== 'input') return null;
                            const cType = (ctrl.getAttribute('type') || '').toLowerCase();
                            if (cType !== 'radio' && cType !== 'checkbox') return null;
                            if (!isVisibleWithAncestors(ctrl, window)) return ctrl;
                            const cls = String(ctrl.className || '').toLowerCase();
                            if (/\b(sr-only|visually-hidden)\b/.test(cls)) return ctrl;
                            const r = ctrl.getBoundingClientRect ? ctrl.getBoundingClientRect() : null;
                            if (r && r.width <= 2 && r.height <= 2) return ctrl;
                        } catch (_) {}
                        return null;
                    }

                    function isSheetOrDialogNode(node) {
                        if (!node || node.nodeType !== 1 || !node.tagName) return false;
                        const t = node.tagName.toLowerCase();
                        if (t === 'dialog') return true;
                        const r = (node.getAttribute('role') || '').toLowerCase();
                        if (r === 'dialog' || r === 'alertdialog' || r === 'listbox' || r === 'menu') return true;
                        if (node.hasAttribute('aria-modal') || node.hasAttribute('data-bottom-sheet') ||
                            node.hasAttribute('data-sheet') || node.hasAttribute('data-drawer')) return true;
                        const cls = String(node.className || '').toLowerCase();
                        return /(bottom-sheet|bottomsheet|modal|drawer|sheet-content|datepicker|calendar-popup|country-picker)/.test(cls);
                    }

                    function hasCustomClickableSemantics(el) {
                        if (!el || el.nodeType !== 1) return false;
                        const tag = (el.tagName || '').toLowerCase();
                        const role = (el.getAttribute('role') || '').toLowerCase();
                        if (tag === 'button' || tag === 'a' || tag === 'summary' || tag === 'option' || tag === 'label') return true;
                        if (INTERACTIVE_ROLES.has(role)) return true;
                        if (el.hasAttribute('onclick') || el.hasAttribute('aria-haspopup') ||
                            el.hasAttribute('aria-pressed') || el.hasAttribute('aria-selected') ||
                            el.hasAttribute('aria-checked') || el.hasAttribute('data-datepicker') ||
                            el.hasAttribute('data-timepicker') || el.hasAttribute('data-calendar') ||
                            el.hasAttribute('data-date') || el.hasAttribute('data-day') ||
                            el.hasAttribute('data-dial-code') || el.hasAttribute('data-country-code') ||
                            el.hasAttribute('data-country') || el.hasAttribute('data-code') ||
                            el.hasAttribute('data-gender') || el.hasAttribute('data-sex') ||
                            el.hasAttribute('data-option') || el.hasAttribute('data-value')) {
                            return true;
                        }
                        if (String(el.getAttribute('aria-current') || '').toLowerCase() === 'date') return true;
                        return false;
                    }

                    function resolveFieldGroupLabel(el, rootDoc) {
                        if (!el || !el.tagName) return '';
                        const tag = (el.tagName || '').toLowerCase();
                        const role = (el.getAttribute && el.getAttribute('role') || '').toLowerCase();
                        if (tag === 'button' || tag === 'a' || tag === 'summary' || tag === 'option' || tag === 'label' ||
                            role === 'button' || role === 'link' || role === 'option' || role === 'menuitem') {
                            return '';
                        }
                        const type = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
                        if (type === 'submit' || type === 'button' || type === 'reset' || type === 'hidden') return '';
                        function extractLabelCandidate(node, requireExplicit) {
                            if (!node || node.nodeType !== 1 || node === el || (node.contains && node.contains(el))) return '';
                            const nTag = (node.tagName || '').toLowerCase();
                            if (nTag === 'h1' || nTag === 'h2' || nTag === 'h3' || nTag === 'h4' || nTag === 'h5' || nTag === 'h6' ||
                                nTag === 'button' || nTag === 'a' || nTag === 'input' || nTag === 'select' || nTag === 'textarea' || nTag === 'form') {
                                return '';
                            }
                            const forAttr = node.getAttribute && node.getAttribute('for');
                            const docRef = (rootDoc && rootDoc.getElementById) ? rootDoc : document;
                            if (forAttr && (!el.id || forAttr !== el.id) && docRef.getElementById && docRef.getElementById(forAttr)) return '';
                            if (node.querySelector && node.querySelector('input:not([type="hidden"]), select, textarea, button, a[href]')) return '';
                            const nCls = String(node.className || '').toLowerCase();
                            const isExplicit = nTag === 'label' || nTag === 'legend' || /(?:^|[\s_-])(label|field-label|form-label|control-label|input-label)(?:$|[\s_-])/.test(nCls);
                            if (requireExplicit && !isExplicit && ((nTag !== 'span' && nTag !== 'div') || nCls.indexOf('label') < 0)) return '';
                            const raw = normText(node.innerText || node.textContent);
                            if (!raw || raw.length > 48) return '';
                            if (!isExplicit && /[.!?]$/.test(raw)) return '';
                            return raw;
                        }
                        try {
                            const prev = el.previousElementSibling;
                            if (prev) {
                                const pCtrls = el.parentElement && el.parentElement.querySelectorAll
                                    ? el.parentElement.querySelectorAll('input:not([type="hidden"]):not([type="submit"]):not([type="button"]), select, textarea').length
                                    : 2;
                                const pTxt = extractLabelCandidate(prev, pCtrls > 1);
                                if (pTxt) return pTxt;
                            }
                            let cur = el.parentElement;
                            let depth = 0;
                            while (cur && cur.nodeType === 1 && depth < 3) {
                                const cTag = (cur.tagName || '').toLowerCase();
                                if (cTag === 'form' || cTag === 'body' || cTag === 'html' || cTag === 'main' || cTag === 'section' || cTag === 'fieldset') break;
                                const ctrls = cur.querySelectorAll
                                    ? cur.querySelectorAll('input:not([type="hidden"]):not([type="submit"]):not([type="button"]), select, textarea, [role="combobox"], [role="listbox"]')
                                    : [];
                                if (ctrls.length <= 1) {
                                    const children = cur.children ? Array.from(cur.children) : [];
                                    for (let i = 0; i < children.length; i++) {
                                        const child = children[i];
                                        if (child === el || (child.contains && child.contains(el))) break;
                                        const cTxt = extractLabelCandidate(child, false);
                                        if (cTxt) return cTxt;
                                    }
                                    const cPrev = cur.previousElementSibling;
                                    if (cPrev) {
                                        const parentCtrls = cur.parentElement && cur.parentElement.querySelectorAll
                                            ? cur.parentElement.querySelectorAll('input:not([type="hidden"]):not([type="submit"]):not([type="button"]), select, textarea').length
                                            : 2;
                                        const cpTxt = extractLabelCandidate(cPrev, parentCtrls > 1);
                                        if (cpTxt) return cpTxt;
                                    }
                                }
                                cur = cur.parentElement;
                                depth++;
                            }
                        } catch (_) {}
                        return '';
                    }

                    function resolveAccessibleName(el, rootDoc) {
                        try {
                            const labelledBy = normText(el.getAttribute && el.getAttribute('aria-labelledby'));
                            if (labelledBy) {
                                const parts = labelledBy.split(/\s+/).map(function(id) {
                                    const ref = (rootDoc && rootDoc.getElementById) ? rootDoc.getElementById(id) : document.getElementById(id);
                                    return ref ? normText(ref.innerText || ref.textContent) : '';
                                }).filter(Boolean);
                                if (parts.length > 0) return parts.join(' ').slice(0, 120);
                            }
                            const ariaLabel = normText(el.getAttribute && el.getAttribute('aria-label'));
                            if (ariaLabel) return ariaLabel.slice(0, 120);
                            if (el.id && rootDoc && rootDoc.querySelectorAll) {
                                const lbls = Array.from(rootDoc.querySelectorAll('label[for="' + escCss(el.id) + '"]'))
                                    .map(function(l) { return normText(l.innerText || l.textContent); })
                                    .filter(Boolean);
                                if (lbls.length > 0) return lbls.join(' ').slice(0, 120);
                            }
                            const parentLabel = el.closest ? el.closest('label') : null;
                            if (parentLabel && parentLabel !== el) {
                                const cloneTxt = normText(parentLabel.innerText || parentLabel.textContent);
                                if (cloneTxt) return cloneTxt.slice(0, 120);
                            }
                            const fieldGroupLbl = resolveFieldGroupLabel(el, rootDoc);
                            if (fieldGroupLbl) return fieldGroupLbl.slice(0, 120);
                            const title = normText(el.getAttribute && el.getAttribute('title'));
                            if (title) return title.slice(0, 120);
                            const placeholder = normText(el.getAttribute && el.getAttribute('placeholder'));
                            if (placeholder) return placeholder.slice(0, 120);
                            const tag = (el.tagName || '').toLowerCase();
                            const type = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
                            if (tag === 'button' || tag === 'a' || tag === 'summary' || tag === 'option' || tag === 'label' ||
                                hasCustomClickableSemantics(el)) {
                                const txt = normText(el.innerText || el.textContent);
                                if (txt) return txt.slice(0, 120);
                            }
                            if (tag === 'input' && (type === 'submit' || type === 'button' || type === 'reset')) {
                                const valTxt = normText(el.value);
                                if (valTxt) return valTxt.slice(0, 120);
                            }
                            const nameAttr = normText(el.getAttribute && el.getAttribute('name'));
                            if (nameAttr) return nameAttr.slice(0, 120);
                            if (el.id && isStableId(el.id)) return el.id.slice(0, 80);
                        } catch (_) {}
                        return (el.tagName || 'element').toLowerCase();
                    }

                    function isVisibleWithAncestors(el, win) {
                        let cur = el;
                        let depth = 0;
                        while (cur && depth < 25) {
                            if (cur.nodeType === 1) {
                                const cTag = (cur.tagName || '').toLowerCase();
                                const cType = (cur.getAttribute && cur.getAttribute('type') || '').toLowerCase();
                                if (cType === 'hidden' || (cur.hasAttribute && (cur.hasAttribute('hidden') || cur.hasAttribute('inert')))) return false;
                                if (String(cur.getAttribute && cur.getAttribute('aria-hidden') || '').toLowerCase() === 'true') return false;
                                const dataState = String(cur.getAttribute && cur.getAttribute('data-state') || '').toLowerCase();
                                const dataOpen = String(cur.getAttribute && cur.getAttribute('data-open') || '').toLowerCase();
                                if (cTag === 'dialog' && !cur.hasAttribute('open') && dataState !== 'open' && dataOpen !== 'true') return false;
                                if (isSheetOrDialogNode(cur) && (dataState === 'closed' || dataOpen === 'false')) return false;
                                try {
                                    const st = (win || window).getComputedStyle(cur);
                                    if (st) {
                                        if (st.display === 'none' || st.visibility === 'hidden') return false;
                                        if (st.opacity !== '' && parseFloat(st.opacity) === 0) return false;
                                    }
                                } catch (_) {}
                            }
                            cur = cur.parentElement || (cur.parentNode && cur.parentNode.host ? cur.parentNode.host : null);
                            depth++;
                        }
                        try {
                            const rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
                            if (rect && rect.width === 0 && rect.height === 0) {
                                const clients = el.getClientRects ? el.getClientRects() : null;
                                if (!clients || clients.length === 0) return false;
                            }
                        } catch (_) {}
                        return true;
                    }

                    function describeContainer(node) {
                        if (!node || !node.tagName) return '';
                        const cTag = node.tagName.toLowerCase();
                        if (node.id && isStableId(node.id)) return cTag + '#' + escCss(node.id);
                        const dt = node.getAttribute && (node.getAttribute('data-testid') || node.getAttribute('data-test'));
                        if (dt) {
                            const attrN = node.getAttribute('data-testid') ? 'data-testid' : 'data-test';
                            return cTag + '[' + attrN + '="' + escAttr(dt) + '"]';
                        }
                        if (node.hasAttribute && node.hasAttribute('data-bottom-sheet')) return cTag + '[data-bottom-sheet]';
                        if (node.hasAttribute && node.hasAttribute('data-sheet')) return cTag + '[data-sheet]';
                        if (node.hasAttribute && node.hasAttribute('data-datepicker')) return cTag + '[data-datepicker]';
                        if (node.hasAttribute && node.hasAttribute('data-calendar')) return cTag + '[data-calendar]';
                        const aria = node.getAttribute && node.getAttribute('aria-label');
                        if (aria) return cTag + '[aria-label="' + escAttr(aria.slice(0, 48)) + '"]';
                        const nameAttr = node.getAttribute && node.getAttribute('name');
                        if (cTag === 'form' && nameAttr) return 'form[name="' + escAttr(nameAttr) + '"]';
                        const roleAttr = node.getAttribute && node.getAttribute('role');
                        if (roleAttr) return cTag + '[role="' + escAttr(roleAttr) + '"]';
                        return cTag;
                    }

                    function getContainerContext(el) {
                        const containerEl = el.closest ? el.closest('form, dialog, [role="dialog"], [role="alertdialog"], [aria-modal="true"], [data-bottom-sheet], [data-sheet], [data-drawer], [data-datepicker], [data-calendar], [role="radiogroup"], [role="listbox"], [role="menu"], [role="grid"], [role="group"], header, nav, footer, main, aside, section, article, fieldset') : null;
                        return containerEl ? { element: containerEl, selector: describeContainer(containerEl) } : null;
                    }

                    function getNearbyText(el) {
                        try {
                            const fgLbl = resolveFieldGroupLabel(el, el.ownerDocument || document);
                            if (fgLbl) return fgLbl.slice(0, 120);
                            const container = el.closest ? el.closest('form, dialog, [role="dialog"], [data-bottom-sheet], [data-sheet], [role="radiogroup"], [role="grid"], fieldset, section, article, tr, li, div') : null;
                            if (container) {
                                const heading = container.querySelector('h1, h2, h3, h4, legend, [role="heading"], .sheet-title, .modal-title');
                                if (heading && heading !== el && !el.contains(heading)) {
                                    const ht = normText(heading.innerText || heading.textContent);
                                    if (ht) return ht.slice(0, 120);
                                }
                            }
                            const prev = el.previousElementSibling;
                            if (prev) {
                                const pt = normText(prev.innerText || prev.textContent);
                                if (pt && pt.length <= 100) return pt;
                            }
                        } catch (_) {}
                        return '';
                    }

                    function buildLocators(el, queryRoot, frameChain, shadowChain) {
                        const candidates = [];
                        const seenSel = new Set();
                        const tag = (el.tagName || 'element').toLowerCase();
                        const scopeType = shadowChain.length > 0 ? 'SHADOW' : (frameChain.length > 0 ? 'IFRAME' : 'DOCUMENT');
                        const prefix = shadowChain.length > 0
                            ? shadowChain.join(' >>> ') + ' >>> '
                            : (frameChain.length > 0 ? frameChain.join(' >> ') + ' >> ' : '');

                        function checkCount(sel) {
                            try { return queryRoot.querySelectorAll(sel).length; } catch (_) { return 0; }
                        }
                        function addCandidate(strategy, localSel, baseConf, stability) {
                            if (!localSel || seenSel.has(localSel)) return;
                            const cnt = checkCount(localSel);
                            if (cnt <= 0) return;
                            seenSel.add(localSel);
                            const isUnique = cnt === 1;
                            const finalStrategy = shadowChain.length > 0 ? 'SHADOW_DOM_CHAIN'
                                : (frameChain.length > 0 ? 'IFRAME_CHAIN' : strategy);
                            const conf = isUnique ? baseConf : Math.max(0.35, baseConf - 0.35);
                            candidates.push({
                                strategy: finalStrategy,
                                selector: prefix + localSel,
                                scope: scopeType,
                                confidence: conf,
                                isUnique: isUnique,
                                matchCount: cnt,
                                isAmbiguous: !isUnique,
                                stability: stability
                            });
                        }

                        if (el.id && isStableId(el.id)) {
                            addCandidate('STABLE_ID', '#' + escCss(el.id), 0.98, 'HIGH');
                        }
                        for (let i = 0; i < DATA_TEST_ATTRS.length; i++) {
                            const attr = DATA_TEST_ATTRS[i];
                            const val = el.getAttribute && el.getAttribute(attr);
                            if (val) {
                                addCandidate('STABLE_DATA_ATTR', tag + '[' + attr + '="' + escAttr(val) + '"]', 0.96, 'HIGH');
                            }
                        }
                        const aria = el.getAttribute && el.getAttribute('aria-label');
                        const role = el.getAttribute && el.getAttribute('role');
                        if (aria && role) {
                            addCandidate('ARIA_ROLE_AND_NAME', tag + '[role="' + escAttr(role) + '"][aria-label="' + escAttr(aria) + '"]', 0.94, 'HIGH');
                        } else if (aria) {
                            addCandidate('ARIA_ROLE_AND_NAME', tag + '[aria-label="' + escAttr(aria) + '"]', 0.93, 'HIGH');
                        }
                        const forAttr = tag === 'label' ? (el.getAttribute && el.getAttribute('for')) : '';
                        if (forAttr) {
                            addCandidate('LABEL_FOR', 'label[for="' + escAttr(forAttr) + '"]', 0.93, 'HIGH');
                        }
                        const labelledBy = el.getAttribute && el.getAttribute('aria-labelledby');
                        if (labelledBy) {
                            addCandidate('ARIA_ROLE_AND_NAME', tag + '[aria-labelledby="' + escAttr(labelledBy) + '"]', 0.91, 'HIGH');
                        }
                        const name = el.getAttribute && el.getAttribute('name');
                        if (name) {
                            addCandidate('STABLE_NAME', tag + '[name="' + escAttr(name) + '"]', 0.92, 'HIGH');
                        }
                        for (let w = 0; w < WIDGET_DATA_ATTRS.length; w++) {
                            const wAttr = WIDGET_DATA_ATTRS[w];
                            const wVal = el.getAttribute && el.getAttribute(wAttr);
                            if (wVal) {
                                addCandidate('STABLE_DATA_ATTR', tag + '[' + wAttr + '="' + escAttr(wVal) + '"]', 0.91, 'HIGH');
                            }
                        }
                        const hrefAttr = tag === 'a' ? (el.getAttribute && el.getAttribute('href') || '') : '';
                        if (hrefAttr && hrefAttr !== '#' && hrefAttr.indexOf('javascript:') !== 0) {
                            addCandidate('HREF_ATTR', 'a[href="' + escAttr(hrefAttr) + '"]', 0.90, 'HIGH');
                        }
                        const placeholderAttr = el.getAttribute && el.getAttribute('placeholder');
                        if (placeholderAttr) {
                            addCandidate('PLACEHOLDER_ATTR', tag + '[placeholder="' + escAttr(placeholderAttr) + '"]', 0.89, 'HIGH');
                        }

                        const form = el.closest ? el.closest('form') : null;
                        if (form) {
                            const formSel = (form.id && isStableId(form.id)) ? ('form#' + escCss(form.id))
                                : (form.getAttribute('name') ? ('form[name="' + escAttr(form.getAttribute('name')) + '"]')
                                    : (checkCount('form') === 1 ? 'form' : ''));
                            if (formSel) {
                                if (el.id && isStableId(el.id)) {
                                    addCandidate('FORM_RELATIVE', formSel + ' #' + escCss(el.id), 0.91, 'HIGH');
                                }
                                for (let i = 0; i < DATA_TEST_ATTRS.length; i++) {
                                    const fAttr = DATA_TEST_ATTRS[i];
                                    const fVal = el.getAttribute && el.getAttribute(fAttr);
                                    if (fVal) {
                                        addCandidate('FORM_RELATIVE', formSel + ' ' + tag + '[' + fAttr + '="' + escAttr(fVal) + '"]', 0.90, 'HIGH');
                                    }
                                }
                                if (name) {
                                    addCandidate('FORM_RELATIVE', formSel + ' ' + tag + '[name="' + escAttr(name) + '"]', 0.89, 'HIGH');
                                }
                                if (aria) {
                                    addCandidate('FORM_RELATIVE', formSel + ' ' + tag + '[aria-label="' + escAttr(aria) + '"]', 0.89, 'HIGH');
                                }
                                for (let w = 0; w < WIDGET_DATA_ATTRS.length; w++) {
                                    const wfAttr = WIDGET_DATA_ATTRS[w];
                                    const wfVal = el.getAttribute && el.getAttribute(wfAttr);
                                    if (wfVal) {
                                        addCandidate('FORM_RELATIVE', formSel + ' ' + tag + '[' + wfAttr + '="' + escAttr(wfVal) + '"]', 0.88, 'HIGH');
                                    }
                                }
                                if (hrefAttr) {
                                    addCandidate('FORM_RELATIVE', formSel + ' a[href="' + escAttr(hrefAttr) + '"]', 0.88, 'HIGH');
                                }
                                const inputType = el.getAttribute && el.getAttribute('type');
                                if (inputType) {
                                    addCandidate('FORM_RELATIVE', formSel + ' ' + tag + '[type="' + escAttr(inputType) + '"]', 0.87, 'HIGH');
                                } else if ((tag === 'input' || tag === 'select' || tag === 'textarea') && checkCount(formSel + ' ' + tag) === 1) {
                                    addCandidate('FORM_RELATIVE', formSel + ' ' + tag, 0.86, 'HIGH');
                                }
                            }
                        }

                        const containerCtx = getContainerContext(el);
                        if (containerCtx && containerCtx.selector) {
                            const cSel = containerCtx.selector;
                            if (el.id && isStableId(el.id)) {
                                addCandidate('CONTAINER_SCOPED', cSel + ' #' + escCss(el.id), 0.89, 'HIGH');
                            }
                            for (let i = 0; i < DATA_TEST_ATTRS.length; i++) {
                                const cAttr = DATA_TEST_ATTRS[i];
                                const cVal = el.getAttribute && el.getAttribute(cAttr);
                                if (cVal) {
                                    addCandidate('CONTAINER_SCOPED', cSel + ' ' + tag + '[' + cAttr + '="' + escAttr(cVal) + '"]', 0.88, 'HIGH');
                                }
                            }
                            if (aria) {
                                addCandidate('CONTAINER_SCOPED', cSel + ' ' + tag + '[aria-label="' + escAttr(aria) + '"]', 0.87, 'HIGH');
                            }
                            for (let w = 0; w < WIDGET_DATA_ATTRS.length; w++) {
                                const wcAttr = WIDGET_DATA_ATTRS[w];
                                const wcVal = el.getAttribute && el.getAttribute(wcAttr);
                                if (wcVal) {
                                    addCandidate('CONTAINER_SCOPED', cSel + ' ' + tag + '[' + wcAttr + '="' + escAttr(wcVal) + '"]', 0.87, 'HIGH');
                                }
                            }
                            if (forAttr) {
                                addCandidate('CONTAINER_SCOPED', cSel + ' label[for="' + escAttr(forAttr) + '"]', 0.87, 'HIGH');
                            }
                            if (hrefAttr && hrefAttr !== '#') {
                                addCandidate('CONTAINER_SCOPED', cSel + ' a[href="' + escAttr(hrefAttr) + '"]', 0.87, 'HIGH');
                            }
                            if (name) {
                                addCandidate('CONTAINER_SCOPED', cSel + ' ' + tag + '[name="' + escAttr(name) + '"]', 0.87, 'HIGH');
                            }
                            const inputType = el.getAttribute && el.getAttribute('type');
                            if (inputType) {
                                addCandidate('CONTAINER_SCOPED', cSel + ' ' + tag + '[type="' + escAttr(inputType) + '"]', 0.85, 'MEDIUM');
                            }
                        }

                        // Deeper Scoped CSS path (up to 8 levels) - only break when uniquely matching
                        const parts = [];
                        let cur = el;
                        for (let depth = 0; cur && cur.nodeType === 1 && depth < 8; depth++) {
                            let part = cur.tagName.toLowerCase();
                            if (part === 'html' || part === 'body') break;
                            let anchored = false;
                            if (cur.id && isStableId(cur.id)) {
                                const idSeg = (cur === el && parts.length === 0) ? ('#' + escCss(cur.id)) : (part + '#' + escCss(cur.id));
                                const testParts = [idSeg].concat(parts);
                                if (checkCount(testParts.join(' > ')) === 1) {
                                    parts.unshift(idSeg);
                                    break;
                                }
                                part = idSeg;
                                anchored = true;
                            } else {
                                const curDt = cur.getAttribute && (cur.getAttribute('data-testid') || cur.getAttribute('data-test'));
                                if (curDt) {
                                    const dtName = cur.getAttribute('data-testid') ? 'data-testid' : 'data-test';
                                    const dtSeg = part + '[' + dtName + '="' + escAttr(curDt) + '"]';
                                    const testParts = [dtSeg].concat(parts);
                                    if (checkCount(testParts.join(' > ')) === 1) {
                                        parts.unshift(dtSeg);
                                        break;
                                    }
                                    part = dtSeg;
                                    anchored = true;
                                }
                            }
                            if (!anchored) {
                                const parent = cur.parentElement;
                                if (parent) {
                                    const sameTag = Array.from(parent.children).filter(function(c) { return c.tagName === cur.tagName; });
                                    if (sameTag.length > 1) {
                                        part += ':nth-of-type(' + (sameTag.indexOf(cur) + 1) + ')';
                                    }
                                }
                            }
                            parts.unshift(part);
                            if (checkCount(parts.join(' > ')) === 1) {
                                break;
                            }
                            cur = cur.parentElement;
                        }
                        if (parts.length > 0) {
                            addCandidate('SCOPED_CSS', parts.join(' > '), 0.76, 'MEDIUM');
                        }
                        if (candidates.length === 0) {
                            const type = el.getAttribute && el.getAttribute('type');
                            const fallbackSel = type ? (tag + '[type="' + escAttr(type) + '"]') : tag;
                            addCandidate('FALLBACK_TAG_ATTR', fallbackSel, 0.50, 'LOW');
                        }
                        candidates.sort(function(a, b) {
                            if (a.isUnique !== b.isUnique) return a.isUnique ? -1 : 1;
                            if (a.strategy !== b.strategy) {
                                if (a.strategy === 'SCOPED_CSS' || a.strategy === 'FALLBACK_TAG_ATTR') return 1;
                                if (b.strategy === 'SCOPED_CSS' || b.strategy === 'FALLBACK_TAG_ATTR') return -1;
                            }
                            return b.confidence - a.confidence;
                        });
                        return candidates;
                    }

                    const elementsOut = [];
                    const inaccessibleFramesOut = [];
                    const seenElements = new Set();
                    let formsCount = 0;
                    let inaccessibleShadowCount = 0;
                    let isPartial = false;
                    let partialReason = '';

                    function isElementInteractableCandidate(el, rootDoc) {
                        if (!el || el.nodeType !== 1) return false;
                        const tag = (el.tagName || '').toLowerCase();
                        if (tag === 'input' || tag === 'textarea' || tag === 'select' || tag === 'option' ||
                            tag === 'button' || tag === 'summary' || tag === 'details') {
                            return true;
                        }
                        if (tag === 'a' && el.hasAttribute('href')) return true;
                        const ce = el.getAttribute('contenteditable');
                        if (ce === '' || ce === 'true' || ce === 'plaintext-only') return true;
                        const role = (el.getAttribute('role') || '').toLowerCase();
                        if (INTERACTIVE_ROLES.has(role)) return true;
                        if (tag === 'label' && getHiddenControlForLabel(el, rootDoc)) return true;
                        if (el.hasAttribute('data-datepicker') || el.hasAttribute('data-timepicker') ||
                            el.hasAttribute('data-calendar') || el.hasAttribute('data-date') ||
                            el.hasAttribute('data-day') || el.hasAttribute('data-month') ||
                            el.hasAttribute('data-year') || el.hasAttribute('data-birth-year') ||
                            el.hasAttribute('data-year-picker') || el.hasAttribute('data-scrollable-years') ||
                            el.hasAttribute('data-dial-code') ||
                            el.hasAttribute('data-country-code') || el.hasAttribute('data-country') ||
                            el.hasAttribute('data-code') || el.hasAttribute('data-gender') ||
                            el.hasAttribute('data-sex') || el.hasAttribute('data-option') ||
                            el.hasAttribute('aria-haspopup') || el.hasAttribute('aria-pressed') ||
                            String(el.getAttribute('aria-current') || '').toLowerCase() === 'date' ||
                            el.hasAttribute('onclick')) {
                            return true;
                        }
                        if (el.hasAttribute('data-value')) {
                            const inWidget = el.closest && el.closest('[role="listbox"], [role="dialog"], [role="radiogroup"], [role="menu"], [data-bottom-sheet], [data-sheet], [data-drawer], [data-year-picker], [data-scrollable-years], .year-picker, .year-list');
                            if (inWidget || tag === 'li' || el.hasAttribute('tabindex')) return true;
                        }
                        const calContainer = el.closest && el.closest('[role="grid"], [data-datepicker], [data-calendar], [data-year-picker], [data-scrollable-years], .calendar, .datepicker, .year-picker, .year-list, .year-scroll');
                        if (calContainer && (tag === 'td' || tag === 'div' || tag === 'span' || tag === 'li') && el.children.length === 0) {
                            const txt = normText(el.innerText || el.textContent);
                            const cls = String(el.className || '').toLowerCase();
                            if (/^\d{1,2}$/.test(txt) && /(day|date|cell)/.test(cls)) return true;
                            if (/^(19\d\d|20\d\d)$/.test(txt)) return true;
                        }
                        const tabIdx = el.getAttribute('tabindex');
                        if (tabIdx !== null && parseInt(tabIdx, 10) >= 0) return true;
                        return false;
                    }

                    function scanRoot(queryRoot, rootDoc, win, frameChain, shadowChain, frameUrl, frameOrigin, iframeDepth, shadowDepth) {
                        if (isPartial) return;
                        if (Date.now() - startTime > TIME_BUDGET_MS) {
                            isPartial = true;
                            partialReason = 'Scan time budget (' + TIME_BUDGET_MS + 'ms) reached.';
                            return;
                        }
                        try {
                            formsCount += queryRoot.querySelectorAll('form, [role="form"]').length;
                        } catch (_) {}

                        const allNodes = Array.from(queryRoot.querySelectorAll('*'));
                        for (let i = 0; i < allNodes.length; i++) {
                            if (elementsOut.length >= MAX_ELEMENTS) {
                                isPartial = true;
                                partialReason = 'Reached maximum discovered elements limit (' + MAX_ELEMENTS + ').';
                                return;
                            }
                            if ((i & 31) === 0 && (Date.now() - startTime > TIME_BUDGET_MS)) {
                                isPartial = true;
                                partialReason = 'Scan time budget (' + TIME_BUDGET_MS + 'ms) reached.';
                                return;
                            }
                            const el = allNodes[i];
                            if (seenElements.has(el)) continue;

                            const tag = (el.tagName || '').toLowerCase();

                            // Check for open or closed Shadow DOM
                            if (el.shadowRoot) {
                                if (shadowDepth < MAX_SHADOW_DEPTH) {
                                    const dtId = el.getAttribute && el.getAttribute('data-testid');
                                    const hostSel = (el.id && isStableId(el.id))
                                        ? ('#' + escCss(el.id))
                                        : (dtId ? (tag + '[data-testid="' + escAttr(dtId) + '"]') : tag);
                                    scanRoot(
                                        el.shadowRoot,
                                        el.shadowRoot,
                                        win,
                                        frameChain,
                                        shadowChain.concat([hostSel]),
                                        frameUrl,
                                        frameOrigin,
                                        iframeDepth,
                                        shadowDepth + 1
                                    );
                                } else {
                                    isPartial = true;
                                    partialReason = 'Maximum Shadow DOM depth (' + MAX_SHADOW_DEPTH + ') reached.';
                                }
                            } else if (el.getAttribute('data-shadow-closed') === 'true' || el.getAttribute('shadowrootmode') === 'closed') {
                                inaccessibleShadowCount++;
                            }

                            // Check for iframes
                            if (tag === 'iframe') {
                                const src = el.getAttribute('src') || '';
                                const sandboxAttr = el.getAttribute('sandbox');
                                const isOpaqueSandbox = sandboxAttr !== null && sandboxAttr.toLowerCase().indexOf('allow-same-origin') < 0;
                                const frameSel = (el.id && isStableId(el.id)) ? ('iframe#' + escCss(el.id))
                                    : (el.name ? 'iframe[name="' + escAttr(el.name) + '"]' : 'iframe');
                                if (iframeDepth >= MAX_IFRAME_DEPTH) {
                                    inaccessibleFramesOut.push({
                                        framePath: frameChain.concat([frameSel]).join(' >> '),
                                        frameSrc: src,
                                        frameOrigin: '',
                                        reason: 'max-depth'
                                    });
                                    continue;
                                }
                                if (isOpaqueSandbox) {
                                    inaccessibleFramesOut.push({
                                        framePath: frameChain.concat([frameSel]).join(' >> '),
                                        frameSrc: src,
                                        frameOrigin: '',
                                        reason: 'sandboxed'
                                    });
                                    continue;
                                }
                                try {
                                    const subDoc = el.contentDocument || (el.contentWindow && el.contentWindow.document);
                                    const subWin = el.contentWindow;
                                    if (!subDoc || !subDoc.documentElement) {
                                        inaccessibleFramesOut.push({
                                            framePath: frameChain.concat([frameSel]).join(' >> '),
                                            frameSrc: src,
                                            frameOrigin: '',
                                            reason: 'unloaded'
                                        });
                                    } else {
                                        const subOrigin = getCanonicalOrigin(subWin) || actualOrigin;
                                        if (actualOrigin && subOrigin && subOrigin !== actualOrigin) {
                                            inaccessibleFramesOut.push({
                                                framePath: frameChain.concat([frameSel]).join(' >> '),
                                                frameSrc: src,
                                                frameOrigin: subOrigin,
                                                reason: 'cross-origin'
                                            });
                                        } else {
                                            scanRoot(
                                                subDoc,
                                                subDoc,
                                                subWin || win,
                                                frameChain.concat([frameSel]),
                                                [],
                                                String(subDoc.URL || src || ''),
                                                subOrigin,
                                                iframeDepth + 1,
                                                shadowDepth
                                            );
                                        }
                                    }
                                } catch (_) {
                                    inaccessibleFramesOut.push({
                                        framePath: frameChain.concat([frameSel]).join(' >> '),
                                        frameSrc: src,
                                        frameOrigin: '',
                                        reason: isOpaqueSandbox ? 'sandboxed' : 'cross-origin'
                                    });
                                }
                                continue;
                            }

                            if (!isElementInteractableCandidate(el, rootDoc)) continue;
                            seenElements.add(el);

                            const hiddenToggleCtrl = tag === 'label' ? getHiddenControlForLabel(el, rootDoc) : null;
                            const inputType = (el.getAttribute('type') || '').toLowerCase();
                            const ariaObj = {};
                            for (let a = 0; a < ARIA_ATTRS.length; a++) {
                                const attrName = ARIA_ATTRS[a];
                                const attrVal = el.getAttribute(attrName);
                                if (attrVal !== null && attrVal !== '') {
                                    ariaObj[attrName] = String(attrVal);
                                }
                            }
                            const role = (ariaObj['role'] || (
                                hiddenToggleCtrl ? ((hiddenToggleCtrl.getAttribute('type') || 'radio').toLowerCase()) :
                                tag === 'input' ? (inputType || 'text') :
                                tag === 'a' ? 'link' : tag
                            )).toLowerCase();

                            const accName = resolveAccessibleName(el, rootDoc);
                            const locators = buildLocators(el, queryRoot, frameChain, shadowChain);
                            const bestLocator = locators.length > 0 ? locators[0] : {
                                strategy: 'SCOPED_CSS',
                                selector: tag,
                                confidence: 0.5,
                                isUnique: false,
                                isAmbiguous: true
                            };

                            const visible = isVisibleWithAncestors(el, win);
                            let inViewport = visible;
                            let boundingRect = null;
                            if (visible) {
                                try {
                                    const rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
                                    if (rect) {
                                        const vw = (win || window).innerWidth || 1080;
                                        const vh = (win || window).innerHeight || 1920;
                                        inViewport = rect.bottom >= 0 && rect.top <= vh && rect.right >= 0 && rect.left <= vw;
                                        const topVw = window.innerWidth || vw;
                                        const topVh = window.innerHeight || vh;
                                        if (rect.width > 0 && rect.height > 0) {
                                            boundingRect = {
                                                left: Math.round(rect.left * 10) / 10,
                                                top: Math.round(rect.top * 10) / 10,
                                                width: Math.round(rect.width * 10) / 10,
                                                height: Math.round(rect.height * 10) / 10,
                                                viewportWidth: topVw,
                                                viewportHeight: topVh
                                            };
                                        }
                                    }
                                } catch (_) {}
                            }

                            const disabled = Boolean(el.disabled) ||
                                (hiddenToggleCtrl ? Boolean(hiddenToggleCtrl.disabled) : false) ||
                                String(ariaObj['aria-disabled'] || '').toLowerCase() === 'true';
                            const readonly = Boolean(el.readOnly);
                            const ceAttr = el.getAttribute('contenteditable');
                            const contentEditableMode = (ceAttr === '' || ceAttr === 'true') ? 'true'
                                : (ceAttr === 'plaintext-only' ? 'plaintext-only' : (el.isContentEditable ? 'inherited' : ''));

                            const formEl = el.form || (hiddenToggleCtrl && hiddenToggleCtrl.form) || (el.closest ? el.closest('form, [role="form"]') : null);
                            const formGroupId = formEl ? (formEl.id || formEl.getAttribute('name') || 'form_default') : '';
                            const fieldsetEl = el.closest ? el.closest('fieldset') : null;
                            const legendEl = fieldsetEl ? fieldsetEl.querySelector('legend') : null;
                            let fieldsetLegend = legendEl ? normText(legendEl.innerText || legendEl.textContent) : '';
                            if (!fieldsetLegend && el.closest) {
                                const groupEl = el.closest('[role="radiogroup"], [role="group"], [role="dialog"], [data-bottom-sheet], [data-datepicker], [data-year-picker]');
                                if (groupEl) {
                                    const gLb = normText(groupEl.getAttribute('aria-labelledby'));
                                    if (gLb) {
                                        const ref = (rootDoc && rootDoc.getElementById) ? rootDoc.getElementById(gLb.split(/\s+/)[0]) : document.getElementById(gLb.split(/\s+/)[0]);
                                        if (ref) fieldsetLegend = normText(ref.innerText || ref.textContent);
                                    }
                                    if (!fieldsetLegend) {
                                        fieldsetLegend = normText(groupEl.getAttribute('aria-label'));
                                    }
                                }
                            }
                            let describedByText = '';
                            if (ariaObj['aria-describedby']) {
                                describedByText = ariaObj['aria-describedby'].split(/\s+/).map(function(id) {
                                    const ref = (rootDoc && rootDoc.getElementById) ? rootDoc.getElementById(id) : document.getElementById(id);
                                    return ref ? normText(ref.innerText || ref.textContent) : '';
                                }).filter(Boolean).join(' ');
                            }

                            const autoComp = (el.getAttribute('autocomplete') || '').toLowerCase();
                            const phStr = (el.getAttribute('placeholder') || '');
                            const nearbyRaw = getNearbyText(el);
                            const hintStr = (accName + ' ' + (el.getAttribute('name') || '') + ' ' + (el.id || '') + ' ' + phStr + ' ' + autoComp).toLowerCase();
                            const contextStr = (fieldsetLegend + ' ' + describedByText + ' ' + nearbyRaw).toLowerCase();
                            const isUnrelatedNumeric = /(?:\b|_)(zip|postal|postcode|pincode|otp|verif|verification|passcode|pin|cvv|cvc|csc|cc-|card|routing|iban|ssn|phone|mobile|tel|fax|qty|quantity|amount|price|cost|salary|street|apt|graduation|grad[-_]?year|vehicle|model[-_]?year|car[-_]?year|tax[-_]?year|fiscal|expir|exp[-_]?year)(?:\b|_)/i.test(hintStr);
                            const hasBirthOrAgeContext = /(birth|birthday|bday|dob|date\s*of\s*birth|confirm\s*your\s*age|your\s*age|age\s*verification|born)/i.test(hintStr + ' ' + contextStr);
                            let hasYearSelectOptions = false;
                            if (tag === 'select' && el.options && el.options.length > 0 && !isUnrelatedNumeric) {
                                let yrCnt = 0;
                                for (let oi = 0; oi < Math.min(el.options.length, 40); oi++) {
                                    const ov = normText(el.options[oi].value || el.options[oi].text);
                                    if (/^(19\d\d|20\d\d)$/.test(ov)) yrCnt++;
                                }
                                hasYearSelectOptions = yrCnt >= 2;
                            }
                            const inCalendar = Boolean(el.closest && el.closest('[role="grid"], [data-datepicker], [data-calendar], [data-year-picker], [data-scrollable-years], .calendar, .datepicker, .year-picker, .year-list'));
                            const elTextNorm = normText(el.innerText || el.textContent);
                            const isYearCellOrOption = !isUnrelatedNumeric && (
                                el.hasAttribute('data-year') ||
                                (inCalendar && /^(19\d\d|20\d\d)$/.test(elTextNorm) && tag !== 'select' && tag !== 'input')
                            );
                            const isCalendarDayCell = !isYearCellOrOption && (
                                role === 'gridcell' ||
                                el.hasAttribute('data-date') ||
                                el.hasAttribute('data-day') ||
                                String(ariaObj['aria-current'] || '').toLowerCase() === 'date' ||
                                (inCalendar && (tag === 'td' || tag === 'button' || tag === 'span' || tag === 'div') && /^\d{1,2}$/.test(elTextNorm))
                            );
                            const isCalendarNav = inCalendar && (tag === 'button' || role === 'button') && !isCalendarDayCell && !isYearCellOrOption &&
                                /(prev|previous|next|forward|back|month|year)/.test(hintStr);
                            const hasDateTriggerCue = el.hasAttribute('data-datepicker') ||
                                el.hasAttribute('data-timepicker') ||
                                el.hasAttribute('data-calendar') ||
                                el.hasAttribute('data-year-picker') ||
                                el.hasAttribute('data-birth-year') ||
                                autoComp.indexOf('bday') === 0 ||
                                ((readonly || ariaObj['aria-haspopup'] === 'dialog' || ariaObj['aria-haspopup'] === 'grid' || ariaObj['aria-haspopup'] === 'listbox' || role === 'combobox') &&
                                    (/(date|birth|dob|calendar|yyyy|mm\/dd|dd\/mm)/.test(hintStr + ' ' + contextStr)));

                            const minAttrVal = el.getAttribute('min') || ariaObj['aria-valuemin'] || el.getAttribute('data-min') || '';
                            const maxAttrVal = el.getAttribute('max') || ariaObj['aria-valuemax'] || el.getAttribute('data-max') || '';
                            const isYearRangeBounds = /^(19\d\d|20\d\d)$/.test(minAttrVal) || /^(19\d\d|20\d\d)$/.test(maxAttrVal);
                            const isExplicitBirthYear = !isUnrelatedNumeric && (
                                autoComp === 'bday-year' ||
                                el.hasAttribute('data-birth-year') ||
                                el.hasAttribute('data-year-picker') ||
                                /(birth\s*year|year\s*of\s*birth|bday[-_]?year|birth[-_]?year|dob[-_]?year)/i.test(hintStr + ' ' + contextStr) ||
                                ((/\b(year|yyyy)\b/i.test(hintStr) || /^(19\d\d|20\d\d)$/.test(phStr.trim()) || hasYearSelectOptions || isYearRangeBounds) &&
                                    (hasBirthOrAgeContext || inCalendar || isYearRangeBounds || hasYearSelectOptions))
                            );
                            const isExplicitBirthMonth = !isUnrelatedNumeric && !isExplicitBirthYear && (
                                autoComp === 'bday-month' ||
                                /(birth\s*month|bday[-_]?month|dob[-_]?month)/i.test(hintStr) ||
                                (/\b(month|mm)\b/i.test(hintStr) && !/\byear\b/i.test(hintStr) && hasBirthOrAgeContext)
                            );
                            const isExplicitBirthDay = !isUnrelatedNumeric && !isExplicitBirthYear && !isExplicitBirthMonth && (
                                autoComp === 'bday-day' ||
                                /(birth\s*day|bday[-_]?day|dob[-_]?day)/i.test(hintStr) ||
                                (/\b(day|dd)\b/i.test(hintStr) && !/\b(year|month)\b/i.test(hintStr) && hasBirthOrAgeContext)
                            );

                            let dateTimeMeta = null;
                            if (['date', 'datetime-local', 'time', 'month', 'week'].indexOf(inputType) >= 0) {
                                dateTimeMeta = {
                                    controlType: inputType,
                                    inputFormat: inputType === 'date' ? 'YYYY-MM-DD' : (inputType === 'time' ? 'HH:mm' : inputType),
                                    min: minAttrVal,
                                    max: maxAttrVal,
                                    step: el.getAttribute('step') || '',
                                    isCustomPicker: false
                                };
                            } else if (isYearCellOrOption) {
                                dateTimeMeta = {
                                    controlType: 'year-option',
                                    inputFormat: 'year',
                                    min: minAttrVal,
                                    max: maxAttrVal,
                                    step: '',
                                    isCustomPicker: true,
                                    pickerRole: 'year-option',
                                    selectedDate: el.getAttribute('data-year') || elTextNorm
                                };
                            } else if (isExplicitBirthYear || isExplicitBirthMonth || isExplicitBirthDay) {
                                const partKind = isExplicitBirthYear ? 'year' : (isExplicitBirthMonth ? 'month' : 'day');
                                const isSelectLike = tag === 'select' || role === 'combobox' || role === 'listbox' || Boolean(ariaObj['aria-haspopup']);
                                dateTimeMeta = {
                                    controlType: isSelectLike ? 'date-select' : 'date-part',
                                    inputFormat: partKind,
                                    min: minAttrVal,
                                    max: maxAttrVal,
                                    step: '',
                                    isCustomPicker: tag !== 'input' || isSelectLike,
                                    pickerRole: partKind + (isSelectLike ? '-select' : '-input')
                                };
                            } else if (hasDateTriggerCue || role === 'grid' || isCalendarDayCell || isCalendarNav) {
                                const selDate = el.getAttribute('data-date') ||
                                    el.getAttribute('data-selected-date') ||
                                    el.getAttribute('data-year') ||
                                    ((ariaObj['aria-selected'] === 'true' || ariaObj['aria-current'] === 'date')
                                        ? (el.getAttribute('data-day') || elTextNorm)
                                        : '');
                                const inferredRole = el.getAttribute('data-picker-role') || (
                                    role === 'grid' ? 'calendar-grid' :
                                    isCalendarDayCell ? 'calendar-day' :
                                    isCalendarNav ? (/year/.test(hintStr) ? 'year-nav' : 'month-nav') :
                                    (tag === 'select' ? (/month/.test(hintStr) ? 'month-select' : 'year-select') : 'trigger')
                                );
                                dateTimeMeta = {
                                    controlType: el.hasAttribute('data-timepicker') ? 'custom-timepicker' : 'custom-datepicker',
                                    inputFormat: el.getAttribute('data-format') || (/(yyyy[-/]mm[-/]dd|dd[-/]mm[-/]yyyy|mm[-/]dd[-/]yyyy)/i.test(phStr) ? phStr : 'YYYY-MM-DD'),
                                    min: minAttrVal,
                                    max: maxAttrVal,
                                    step: '',
                                    isCustomPicker: true,
                                    pickerRole: inferredRole,
                                    selectedDate: String(selDate || '').slice(0, 32)
                                };
                            }

                            const fieldMetaHint = (
                                inputType + ' ' +
                                (el.getAttribute('name') || '') + ' ' +
                                (el.id || '') + ' ' +
                                phStr + ' ' +
                                accName + ' ' +
                                autoComp
                            ).toLowerCase();
                            const isSensitiveInJs = inputType === 'password' ||
                                /(password|passcode|pin\b|secret|token|otp|one-time-code|cvv|cvc|csc|cc-number|cc-csc|credit.?card|card.?number|ssn|routing|iban|account.?number)/i.test(fieldMetaHint);
                            const rawSafeVal = (isSensitiveInJs || (tag !== 'input' && !contentEditableMode))
                                ? ''
                                : String(el.value || '').slice(0, 64);

                            const hrefAttrVal = tag === 'a' ? (el.getAttribute('href') || '').slice(0, 180) : '';
                            const visibleTxtVal = isSensitiveInJs ? '' : normText(el.innerText || el.textContent).slice(0, 120);
                            const containerCtxInfo = getContainerContext(el);
                            const nearbyTxtVal = isSensitiveInJs ? '' : (nearbyRaw || describedByText).slice(0, 120);
                            const relAttrs = {};
                            ['id', 'name', 'type', 'role', 'href', 'placeholder', 'autocomplete', 'inputmode', 'title', 'for',
                             'min', 'max', 'aria-label', 'aria-labelledby', 'aria-describedby', 'aria-haspopup', 'aria-expanded', 'aria-controls',
                             'aria-pressed', 'aria-selected', 'aria-checked', 'aria-current', 'aria-valuemin', 'aria-valuemax',
                             'data-testid', 'data-test', 'data-qa', 'data-cy', 'data-automation-id',
                             'data-datepicker', 'data-timepicker', 'data-calendar', 'data-date', 'data-selected-date',
                             'data-day', 'data-month', 'data-year', 'data-birth-year', 'data-year-picker', 'data-scrollable-years',
                             'data-format', 'data-picker-role',
                             'data-dial-code', 'data-country-code', 'data-country', 'data-code',
                             'data-gender', 'data-sex', 'data-option', 'data-value'].forEach(function(k) {
                                const v = el.getAttribute && el.getAttribute(k);
                                if (v !== null && v !== '') relAttrs[k] = String(v).slice(0, 120);
                            });

                            const checkedVal = (tag === 'input' && (inputType === 'checkbox' || inputType === 'radio'))
                                ? Boolean(el.checked)
                                : (hiddenToggleCtrl ? Boolean(hiddenToggleCtrl.checked)
                                    : (ariaObj['aria-checked'] ? ariaObj['aria-checked'] === 'true'
                                        : (ariaObj['aria-pressed'] ? ariaObj['aria-pressed'] === 'true' : null)));
                            const radioGroupNameVal = inputType === 'radio'
                                ? (el.getAttribute('name') || fieldsetLegend || '')
                                : (hiddenToggleCtrl && (hiddenToggleCtrl.getAttribute('type') || '').toLowerCase() === 'radio'
                                    ? (hiddenToggleCtrl.getAttribute('name') || fieldsetLegend || '')
                                    : (role === 'radio'
                                        ? (el.getAttribute('name') || fieldsetLegend || '')
                                        : ''));

                            elementsOut.push({
                                id: 'el_' + elementsOut.length,
                                documentId: DOC_ID,
                                tag: tag,
                                role: role,
                                label: accName,
                                accessibleName: accName,
                                visibleText: visibleTxtVal,
                                href: hrefAttrVal,
                                parentContainerSummary: containerCtxInfo ? containerCtxInfo.selector : '',
                                nearbyText: nearbyTxtVal,
                                relevantAttributes: relAttrs,
                                selector: bestLocator.selector,
                                locatorStrategy: bestLocator.strategy,
                                locatorConfidence: bestLocator.confidence,
                                isLocatorAmbiguous: bestLocator.isAmbiguous,
                                locatorCandidates: locators,
                                inputType: inputType,
                                placeholder: phStr,
                                name: el.getAttribute('name') || '',
                                htmlId: el.id || '',
                                safeValue: rawSafeVal,
                                checked: checkedVal,
                                selected: tag === 'option' ? Boolean(el.selected)
                                    : (ariaObj['aria-selected'] ? ariaObj['aria-selected'] === 'true' : null),
                                required: Boolean(el.required) || ariaObj['aria-required'] === 'true',
                                disabled: disabled,
                                readonly: readonly,
                                visible: visible,
                                inViewport: inViewport,
                                interactable: visible && !disabled && inputType !== 'hidden',
                                contentEditable: contentEditableMode,
                                autocomplete: el.getAttribute('autocomplete') || '',
                                ariaAttributes: ariaObj,
                                controlledElementId: ariaObj['aria-controls'] || '',
                                formGroupId: formGroupId,
                                fieldsetLegend: fieldsetLegend,
                                radioGroupName: radioGroupNameVal,
                                dateTimeMetadata: dateTimeMeta,
                                framePath: frameChain,
                                frameUrl: frameUrl,
                                frameOrigin: frameOrigin,
                                shadowHostPath: shadowChain,
                                boundingBox: boundingRect
                            });
                        }
                    }

                    scanRoot(document, document, window, [], [], String(document.URL || ''), actualOrigin, 0, 0);

                    return JSON.stringify({
                        sessionId: SESSION_ID,
                        tabId: TAB_ID,
                        origin: actualOrigin,
                        formsFound: formsCount,
                        inaccessibleShadowRoots: inaccessibleShadowCount,
                        inaccessibleFrames: inaccessibleFramesOut,
                        isPartial: isPartial,
                        partialReason: partialReason,
                        elements: elementsOut
                    });
                } catch (e) {
                    return JSON.stringify({
                        sessionId: $qSessionId,
                        tabId: $qTabId,
                        error: String(e && e.message ? e.message : e),
                        elements: []
                    });
                }
            })();
        """.trimIndent()
    }

    fun buildDynamicObserverInstallScript(
        sessionId: String,
        tabId: String,
        expectedOrigin: String,
        debounceMs: Long = limits.mutationDebounceMs,
        maxBatchSize: Int = limits.maxBatchSize,
        maxMutationQueueSize: Int = limits.maxMutationQueueSize
    ): String {
        val qSession = JSONObject.quote(sessionId)
        val qTab = JSONObject.quote(tabId)
        val qOrigin = JSONObject.quote(expectedOrigin)
        val effectiveQueueCap = maxMutationQueueSize.coerceAtLeast(maxBatchSize).coerceAtLeast(50)
        return """
            (function() {
                try {
                    if (window.__apexDomScannerObserver && typeof window.__apexDomScannerObserver.cleanup === 'function') {
                        try { window.__apexDomScannerObserver.cleanup(); } catch (_) {}
                    }
                    if (typeof MutationObserver === 'undefined' || !document.documentElement) {
                        return JSON.stringify({ ok: false, reason: 'MutationObserver unavailable' });
                    }
                    const MAX_QUEUE = $effectiveQueueCap;
                    const state = {
                        sessionId: $qSession,
                        tabId: $qTab,
                        expectedOrigin: $qOrigin,
                        destroyed: false,
                        timer: null,
                        removedSelectors: new Set(),
                        addedOrUpdated: new Set(),
                        observers: [],
                        observedRoots: typeof WeakSet !== 'undefined' ? new WeakSet() : null,
                        observer: null,
                        cleanup: function() {
                            state.destroyed = true;
                            if (state.timer) clearTimeout(state.timer);
                            state.timer = null;
                            state.removedSelectors.clear();
                            state.addedOrUpdated.clear();
                            for (let i = 0; i < state.observers.length; i++) {
                                try { state.observers[i].disconnect(); } catch (_) {}
                            }
                            state.observers = [];
                            state.observer = null;
                            if (window.__apexDomScannerObserver === state) {
                                window.__apexDomScannerObserver = null;
                            }
                        }
                    };
                    function getCanonicalOrigin(win) {
                        try {
                            const loc = (win || window).location;
                            if (!loc) return '';
                            const proto = String(loc.protocol || '').toLowerCase();
                            if (proto !== 'http:' && proto !== 'https:') return '';
                            const scheme = proto.slice(0, -1);
                            const host = String(loc.hostname || '').trim().replace(/\.+$/, '').toLowerCase();
                            if (!host) return '';
                            const rawPort = String(loc.port || '');
                            const port = rawPort ? parseInt(rawPort, 10) : (scheme === 'http' ? 80 : 443);
                            if (!port || isNaN(port) || port < 1 || port > 65535) return '';
                            return scheme + '://' + host + ':' + port;
                        } catch (_) {
                            return '';
                        }
                    }
                    function escCss(v) {
                        if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(String(v));
                        return String(v).replace(/([ !"#$%&'()*+,.\/:;<=>?@\[\\\]^`{|}~])/g, '\\$1');
                    }
                    function escAttr(v) {
                        return String(v).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\r|\n/g, ' ');
                    }
                    const INTERACTIVE_ROLES = new Set([
                        'textbox', 'searchbox', 'combobox', 'listbox', 'option',
                        'checkbox', 'radio', 'switch', 'button', 'link',
                        'slider', 'spinbutton', 'menu', 'menuitem', 'menuitemcheckbox', 'menuitemradio',
                        'tab', 'tree', 'treeitem', 'grid', 'gridcell', 'dialog'
                    ]);
                    function isSheetOrDialogNode(node) {
                        if (!node || node.nodeType !== 1 || !node.tagName) return false;
                        const t = node.tagName.toLowerCase();
                        if (t === 'dialog') return true;
                        const r = (node.getAttribute('role') || '').toLowerCase();
                        if (r === 'dialog' || r === 'alertdialog' || r === 'listbox' || r === 'menu') return true;
                        if (node.hasAttribute('aria-modal') || node.hasAttribute('data-bottom-sheet') ||
                            node.hasAttribute('data-sheet') || node.hasAttribute('data-drawer')) return true;
                        const cls = String(node.className || '').toLowerCase();
                        return /(bottom-sheet|bottomsheet|modal|drawer|sheet-content|datepicker|calendar-popup|country-picker)/.test(cls);
                    }
                    function isNodeVisibleWithAncestors(el) {
                        let cur = el;
                        let depth = 0;
                        while (cur && depth < 25) {
                            if (cur.nodeType === 1) {
                                const cTag = (cur.tagName || '').toLowerCase();
                                const cType = (cur.getAttribute && cur.getAttribute('type') || '').toLowerCase();
                                if (cType === 'hidden' || cur.hasAttribute('hidden') || cur.hasAttribute('inert')) return false;
                                if (String(cur.getAttribute('aria-hidden') || '').toLowerCase() === 'true') return false;
                                const dataState = String(cur.getAttribute('data-state') || '').toLowerCase();
                                const dataOpen = String(cur.getAttribute('data-open') || '').toLowerCase();
                                if (cTag === 'dialog' && !cur.hasAttribute('open') && dataState !== 'open' && dataOpen !== 'true') return false;
                                if (isSheetOrDialogNode(cur) && (dataState === 'closed' || dataOpen === 'false')) return false;
                                try {
                                    const st = window.getComputedStyle(cur);
                                    if (st && (st.display === 'none' || st.visibility === 'hidden' || (st.opacity !== '' && parseFloat(st.opacity) === 0))) return false;
                                } catch (_) {}
                            }
                            cur = cur.parentElement || (cur.parentNode && cur.parentNode.host ? cur.parentNode.host : null);
                            depth++;
                        }
                        return true;
                    }
                    function getHiddenControlForLabel(el) {
                        if (!el || (el.tagName || '').toLowerCase() !== 'label') return null;
                        try {
                            let ctrl = null;
                            const forId = el.getAttribute && el.getAttribute('for');
                            if (forId) ctrl = document.getElementById(forId);
                            if (!ctrl && el.querySelector) ctrl = el.querySelector('input[type="radio"], input[type="checkbox"]');
                            if (!ctrl || (ctrl.tagName || '').toLowerCase() !== 'input') return null;
                            const cType = (ctrl.getAttribute('type') || '').toLowerCase();
                            if (cType !== 'radio' && cType !== 'checkbox') return null;
                            if (!isNodeVisibleWithAncestors(ctrl)) return ctrl;
                            if (/\b(sr-only|visually-hidden)\b/i.test(String(ctrl.className || ''))) return ctrl;
                        } catch (_) {}
                        return null;
                    }
                    function isCandidate(el) {
                        if (!el || el.nodeType !== 1) return false;
                        const tag = (el.tagName || '').toLowerCase();
                        if (tag === 'input' || tag === 'textarea' || tag === 'select' || tag === 'option' ||
                            tag === 'button' || tag === 'summary' || tag === 'details') return true;
                        if (tag === 'a' && el.hasAttribute && el.hasAttribute('href')) return true;
                        const ce = el.getAttribute ? el.getAttribute('contenteditable') : null;
                        if (ce === '' || ce === 'true' || ce === 'plaintext-only') return true;
                        const role = (el.getAttribute && el.getAttribute('role') || '').toLowerCase();
                        if (INTERACTIVE_ROLES.has(role)) return true;
                        if (tag === 'label' && getHiddenControlForLabel(el)) return true;
                        if (el.hasAttribute && (el.hasAttribute('data-datepicker') || el.hasAttribute('data-timepicker') ||
                            el.hasAttribute('data-calendar') || el.hasAttribute('data-date') ||
                            el.hasAttribute('data-day') || el.hasAttribute('data-dial-code') ||
                            el.hasAttribute('data-country-code') || el.hasAttribute('data-country') ||
                            el.hasAttribute('data-code') || el.hasAttribute('data-gender') ||
                            el.hasAttribute('data-sex') || el.hasAttribute('data-option') ||
                            el.hasAttribute('data-value') || el.hasAttribute('aria-haspopup') ||
                            el.hasAttribute('aria-pressed') || el.hasAttribute('onclick') ||
                            String(el.getAttribute('aria-current') || '').toLowerCase() === 'date')) return true;
                        return false;
                    }
                    function recordRemovedSubtree(node) {
                        if (!node || node.nodeType !== 1 || state.removedSelectors.size >= MAX_QUEUE) return;
                        if (node.id) state.removedSelectors.add('#' + escCss(node.id));
                        if (node.querySelectorAll) {
                            const childIds = node.querySelectorAll('[id]');
                            for (let k = 0; k < childIds.length && state.removedSelectors.size < MAX_QUEUE; k++) {
                                if (childIds[k].id) state.removedSelectors.add('#' + escCss(childIds[k].id));
                            }
                        }
                    }
                    function recordAddedSubtree(node, shadowDepth) {
                        if (!node || node.nodeType !== 1 || state.addedOrUpdated.size >= MAX_QUEUE) return;
                        if (isCandidate(node)) state.addedOrUpdated.add(node);
                        if (node.shadowRoot && shadowDepth < ${limits.maxShadowDepth}) {
                            observeRoot(node.shadowRoot, shadowDepth + 1, 0);
                        }
                        if (node.querySelectorAll) {
                            const nested = node.querySelectorAll('input, textarea, select, option, button, a[href], summary, label, [contenteditable], [role], [aria-haspopup], [aria-pressed], [aria-current], [data-datepicker], [data-timepicker], [data-calendar], [data-date], [data-day], [data-dial-code], [data-country-code], [data-country], [data-code], [data-gender], [data-value], [data-option], [onclick]');
                            for (let k = 0; k < nested.length && state.addedOrUpdated.size < MAX_QUEUE; k++) {
                                if (isCandidate(nested[k])) state.addedOrUpdated.add(nested[k]);
                            }
                            if (shadowDepth < ${limits.maxShadowDepth}) {
                                const allDesc = node.querySelectorAll('*');
                                const scanLimit = Math.min(allDesc.length, MAX_QUEUE);
                                for (let j = 0; j < scanLimit; j++) {
                                    if (allDesc[j].shadowRoot) {
                                        observeRoot(allDesc[j].shadowRoot, shadowDepth + 1, 0);
                                    }
                                }
                            }
                        }
                    }
                    function flushMutations() {
                        state.timer = null;
                        if (state.destroyed || window.__apexDomScannerObserver !== state) return;
                        const liveOrigin = getCanonicalOrigin(window);
                        if (state.expectedOrigin && liveOrigin && liveOrigin !== state.expectedOrigin) {
                            state.cleanup();
                            return;
                        }
                        const removed = Array.from(state.removedSelectors).slice(0, $maxBatchSize);
                        state.removedSelectors.clear();
                        const updated = [];
                        const candidates = Array.from(state.addedOrUpdated).slice(0, $maxBatchSize);
                        state.addedOrUpdated.clear();
                        candidates.forEach(function(el) {
                            if (!el || !el.isConnected || !isCandidate(el)) return;
                            const tag = (el.tagName || '').toLowerCase();
                            const inputType = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
                            const attrName = el.getAttribute ? (el.getAttribute('name') || '') : '';
                            const testId = el.getAttribute ? (el.getAttribute('data-testid') || '') : '';
                            const dataCountryCode = el.getAttribute ? (el.getAttribute('data-country-code') || '') : '';
                            const dataDialCode = el.getAttribute ? (el.getAttribute('data-dial-code') || '') : '';
                            const dataDate = el.getAttribute ? (el.getAttribute('data-date') || '') : '';
                            const ariaLabel = el.getAttribute ? (el.getAttribute('aria-label') || '') : '';
                            const sel = el.id
                                ? ('#' + escCss(el.id))
                                : (testId ? (tag + '[data-testid="' + escAttr(testId) + '"]')
                                    : (dataCountryCode ? (tag + '[data-country-code="' + escAttr(dataCountryCode) + '"]')
                                        : (dataDate ? (tag + '[data-date="' + escAttr(dataDate) + '"]')
                                            : (ariaLabel ? (tag + '[aria-label="' + escAttr(ariaLabel) + '"]')
                                                : (attrName ? (tag + '[name="' + escAttr(attrName) + '"]') : tag)))));
                            const placeholder = el.getAttribute ? (el.getAttribute('placeholder') || '') : '';
                            const autoComp = el.getAttribute ? (el.getAttribute('autocomplete') || '') : '';
                            const titleAttr = el.getAttribute ? (el.getAttribute('title') || '') : '';
                            let explicitLbl = '';
                            try {
                                if (el.id) {
                                    const forNode = document.querySelector('label[for="' + escCss(el.id) + '"]');
                                    if (forNode) explicitLbl = (forNode.innerText || forNode.textContent || '').replace(/\s+/g, ' ').trim();
                                }
                                if (!explicitLbl && el.closest) {
                                    const wrapLbl = el.closest('label');
                                    if (wrapLbl) explicitLbl = (wrapLbl.innerText || wrapLbl.textContent || '').replace(/\s+/g, ' ').trim();
                                }
                                if (!explicitLbl && el.previousElementSibling) {
                                    const prevTag = (el.previousElementSibling.tagName || '').toLowerCase();
                                    if (prevTag === 'label' || String(el.previousElementSibling.className || '').toLowerCase().indexOf('label') >= 0) {
                                        explicitLbl = (el.previousElementSibling.innerText || el.previousElementSibling.textContent || '').replace(/\s+/g, ' ').trim();
                                    }
                                }
                                if (!explicitLbl && el.parentElement) {
                                    const pLbl = el.parentElement.querySelector('label, [class*="label"]');
                                    if (pLbl && pLbl !== el && !pLbl.contains(el)) {
                                        explicitLbl = (pLbl.innerText || pLbl.textContent || '').replace(/\s+/g, ' ').trim();
                                    }
                                }
                            } catch (_) {}
                            const lbl = (ariaLabel || explicitLbl || titleAttr || placeholder || el.innerText || el.textContent || tag).replace(/\s+/g, ' ').trim().slice(0, 80);
                            const ceAttr = el.getAttribute ? el.getAttribute('contenteditable') : null;
                            const ceMode = (ceAttr === '' || ceAttr === 'true') ? 'true' : (ceAttr === 'plaintext-only' ? 'plaintext-only' : '');
                            const hiddenToggle = getHiddenControlForLabel(el);
                            const relAttrs = {};
                            ['role', 'autocomplete', 'inputmode', 'title', 'for', 'aria-haspopup', 'aria-expanded', 'aria-pressed', 'aria-selected', 'aria-checked', 'aria-current', 'data-datepicker', 'data-calendar', 'data-date', 'data-day', 'data-dial-code', 'data-country-code', 'data-code', 'data-gender', 'data-value', 'data-option'].forEach(function(k) {
                                const v = el.getAttribute && el.getAttribute(k);
                                if (v !== null && v !== '') relAttrs[k] = String(v).slice(0, 120);
                            });
                            const dtMeta = (dataDate || el.hasAttribute('data-day') || el.hasAttribute('data-datepicker') || (el.getAttribute('role') || '').toLowerCase() === 'gridcell')
                                ? {
                                    controlType: 'custom-datepicker',
                                    inputFormat: el.getAttribute('data-format') || 'YYYY-MM-DD',
                                    isCustomPicker: true,
                                    pickerRole: (dataDate || el.hasAttribute('data-day') || (el.getAttribute('role') || '').toLowerCase() === 'gridcell') ? 'calendar-day' : 'trigger',
                                    selectedDate: dataDate || ''
                                }
                                : (/(birth\s*year|year\s*of\s*birth|bday[-_]?year|birth[-_]?year)/i.test(lbl + ' ' + attrName + ' ' + (el.id || ''))
                                    ? {
                                        controlType: tag === 'select' ? 'date-select' : 'date-part',
                                        inputFormat: 'year',
                                        isCustomPicker: tag !== 'input',
                                        pickerRole: tag === 'select' ? 'year-select' : 'year-input',
                                        selectedDate: ''
                                    }
                                    : null);
                            const isVis = isNodeVisibleWithAncestors(el);
                            let bbox = null;
                            if (isVis && el.getBoundingClientRect) {
                                try {
                                    const r = el.getBoundingClientRect();
                                    if (r && r.width > 0 && r.height > 0) {
                                        bbox = {
                                            left: Math.round(r.left * 10) / 10,
                                            top: Math.round(r.top * 10) / 10,
                                            width: Math.round(r.width * 10) / 10,
                                            height: Math.round(r.height * 10) / 10,
                                            viewportWidth: window.innerWidth || 1080,
                                            viewportHeight: window.innerHeight || 1920
                                        };
                                    }
                                } catch (_) {}
                            }
                            updated.push({
                                tag: tag,
                                inputType: inputType,
                                role: (el.getAttribute && el.getAttribute('role')) || (hiddenToggle ? (hiddenToggle.getAttribute('type') || 'radio') : tag),
                                label: lbl,
                                accessibleName: lbl,
                                visibleText: (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 120),
                                relevantAttributes: relAttrs,
                                selector: sel,
                                htmlId: el.id || '',
                                name: attrName,
                                placeholder: placeholder,
                                autocomplete: autoComp,
                                contentEditable: ceMode,
                                dateTimeMetadata: dtMeta,
                                boundingBox: bbox,
                                checked: (tag === 'input' && (inputType === 'checkbox' || inputType === 'radio'))
                                    ? Boolean(el.checked)
                                    : (hiddenToggle ? Boolean(hiddenToggle.checked) : null),
                                radioGroupName: hiddenToggle && (hiddenToggle.getAttribute('type') || '').toLowerCase() === 'radio'
                                    ? (hiddenToggle.getAttribute('name') || '') : '',
                                disabled: Boolean(el.disabled) || (el.getAttribute && el.getAttribute('aria-disabled') === 'true'),
                                readonly: Boolean(el.readOnly),
                                visible: isVis
                            });
                        });
                        if (removed.length === 0 && updated.length === 0) return;
                        if (state.destroyed || window.__apexDomScannerObserver !== state) return;
                        const payload = JSON.stringify({
                            sessionId: state.sessionId,
                            tabId: state.tabId,
                            origin: liveOrigin || state.expectedOrigin,
                            removedSelectors: removed,
                            updatedElements: updated
                        });
                        if (window.ApexAutomationBridge && window.ApexAutomationBridge.onDomMutationBatchJson) {
                            window.ApexAutomationBridge.onDomMutationBatchJson(payload);
                        }
                    }
                    const OBSERVE_OPTIONS = {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        attributeFilter: [
                            'id', 'name', 'type', 'role', 'class', 'open', 'inert',
                            'aria-label', 'aria-labelledby', 'aria-hidden', 'aria-modal',
                            'aria-expanded', 'aria-checked', 'aria-selected', 'aria-pressed', 'aria-current', 'aria-disabled',
                            'data-state', 'data-open', 'data-date', 'data-selected-date', 'data-dial-code', 'data-country-code',
                            'disabled', 'readonly', 'hidden', 'style', 'contenteditable'
                        ]
                    };
                    function observeRoot(root, shadowDepth, iframeDepth) {
                        if (!root || state.destroyed) return;
                        if (state.observers.length >= 24) return;
                        if (state.observedRoots) {
                            if (state.observedRoots.has(root)) return;
                            state.observedRoots.add(root);
                        }
                        const obs = new MutationObserver(function(mutations) {
                            if (state.destroyed || window.__apexDomScannerObserver !== state) return;
                            for (let i = 0; i < mutations.length; i++) {
                                const m = mutations[i];
                                if (m.type === 'childList') {
                                    for (let r = 0; r < m.removedNodes.length && state.removedSelectors.size < MAX_QUEUE; r++) {
                                        recordRemovedSubtree(m.removedNodes[r]);
                                    }
                                    for (let a = 0; a < m.addedNodes.length && state.addedOrUpdated.size < MAX_QUEUE; a++) {
                                        recordAddedSubtree(m.addedNodes[a], shadowDepth);
                                    }
                                } else if (m.type === 'attributes' && m.target && m.target.nodeType === 1) {
                                    if (isCandidate(m.target) && state.addedOrUpdated.size < MAX_QUEUE) {
                                        state.addedOrUpdated.add(m.target);
                                    }
                                    const attrName = String(m.attributeName || '').toLowerCase();
                                    if (attrName === 'open' || attrName === 'hidden' || attrName === 'style' ||
                                        attrName === 'class' || attrName === 'aria-hidden' || attrName === 'aria-modal' ||
                                        attrName === 'inert' || attrName === 'data-state' || attrName === 'data-open' ||
                                        attrName === 'aria-expanded') {
                                        recordAddedSubtree(m.target, shadowDepth);
                                    }
                                }
                            }
                            if (!state.timer && (state.removedSelectors.size > 0 || state.addedOrUpdated.size > 0)) {
                                state.timer = setTimeout(flushMutations, $debounceMs);
                            }
                        });
                        obs.observe(root, OBSERVE_OPTIONS);
                        state.observers.push(obs);
                        if (!state.observer) state.observer = obs;
                        if (shadowDepth < ${limits.maxShadowDepth} && root.querySelectorAll) {
                            const els = root.querySelectorAll('*');
                            const scanCap = Math.min(els.length, MAX_QUEUE);
                            for (let i = 0; i < scanCap; i++) {
                                if (els[i].shadowRoot) {
                                    observeRoot(els[i].shadowRoot, shadowDepth + 1, iframeDepth);
                                }
                            }
                        }
                        if (iframeDepth < ${limits.maxIframeDepth} && root.querySelectorAll) {
                            const frames = root.querySelectorAll('iframe');
                            for (let i = 0; i < frames.length; i++) {
                                try {
                                    const fDoc = frames[i].contentDocument;
                                    if (fDoc && fDoc.documentElement &&
                                        getCanonicalOrigin(frames[i].contentWindow) === state.expectedOrigin) {
                                        observeRoot(fDoc.documentElement, 0, iframeDepth + 1);
                                    }
                                } catch (_) {}
                            }
                        }
                    }
                    observeRoot(document.documentElement, 0, 0);
                    window.__apexDomScannerObserver = state;
                    return JSON.stringify({ ok: true, sessionId: state.sessionId, observerCount: state.observers.length });
                } catch (e) {
                    return JSON.stringify({ ok: false, reason: String(e) });
                }
            })();
        """.trimIndent()
    }

    fun buildDynamicObserverCleanupScript(expectedSessionId: String? = null): String {
        val qSession = JSONObject.quote(expectedSessionId?.trim().orEmpty())
        return """
            (function() {
                try {
                    const target = $qSession;
                    const obs = window.__apexDomScannerObserver;
                    let removed = false;
                    if (obs && (!target || obs.sessionId === target)) {
                        if (typeof obs.cleanup === 'function') obs.cleanup();
                        window.__apexDomScannerObserver = null;
                        removed = true;
                    }
                    return JSON.stringify({ ok: true, removed: removed });
                } catch (e) {
                    return JSON.stringify({ ok: false, reason: String(e) });
                }
            })();
        """.trimIndent()
    }

    fun buildSaveScrollPositionScript(): String = """
        (function() {
            try {
                const containers = [];
                const nodes = Array.from(document.querySelectorAll('[data-scroll-container], div, main, section'));
                for (let i = 0; i < nodes.length && containers.length < 10; i++) {
                    const el = nodes[i];
                    if (el.scrollHeight > el.clientHeight + 20 && el.id) {
                        containers.push({ id: el.id, top: el.scrollTop, left: el.scrollLeft });
                    }
                }
                return JSON.stringify({
                    x: window.scrollX || window.pageXOffset || 0,
                    y: window.scrollY || window.pageYOffset || 0,
                    containers: containers
                });
            } catch (e) {
                return JSON.stringify({ x: 0, y: 0, containers: [] });
            }
        })();
    """.trimIndent()

    fun buildExtendedScrollStepScript(stepPx: Int): String = """
        (function() {
            try {
                const beforeY = window.scrollY || window.pageYOffset || 0;
                const maxScroll = Math.max(
                    document.body ? document.body.scrollHeight : 0,
                    document.documentElement ? document.documentElement.scrollHeight : 0
                ) - (window.innerHeight || 800);
                if (beforeY >= maxScroll && maxScroll > 0) {
                    return JSON.stringify({ moved: false, scrollY: beforeY });
                }
                window.scrollBy(0, $stepPx);
                const afterY = window.scrollY || window.pageYOffset || (beforeY + $stepPx);
                return JSON.stringify({ moved: afterY > beforeY, scrollY: afterY });
            } catch (e) {
                return JSON.stringify({ moved: false, scrollY: 0 });
            }
        })();
    """.trimIndent()

    fun buildRestoreScrollPositionScript(savedStateJson: String): String {
        val qState = JSONObject.quote(savedStateJson)
        return """
            (function() {
                try {
                    const state = JSON.parse($qState);
                    window.scrollTo(state.x || 0, state.y || 0);
                    if (Array.isArray(state.containers)) {
                        state.containers.forEach(function(c) {
                            const el = document.getElementById(c.id);
                            if (el) {
                                el.scrollTop = c.top || 0;
                                el.scrollLeft = c.left || 0;
                            }
                        });
                    }
                    return JSON.stringify({ ok: true });
                } catch (e) {
                    return JSON.stringify({ ok: false });
                }
            })();
        """.trimIndent()
    }
}

// =============================================================================
// DETERMINISTIC HTML DOM FIXTURE SCAN ENGINE (FOR REALISTIC HTML FIXTURES)
// =============================================================================

object HtmlDomFixtureScanEngine {

    private val WIDGET_DATA_ATTRS = listOf(
        "data-date", "data-country-code", "data-dial-code", "data-code",
        "data-day", "data-month", "data-year", "data-birth-year", "data-year-picker",
        "data-gender", "data-sex", "data-option", "data-value"
    )

    private data class HiddenToggleInfo(
        val id: String,
        val inputType: String,
        val name: String,
        val checked: Boolean,
        val disabled: Boolean
    )

    private data class ParsedElementNode(
        val tagName: String,
        val attributes: Map<String, String>,
        val textContent: String,
        val innerHtml: String,
        val formId: String,
        val formName: String,
        val fieldsetLegend: String,
        val wrappingLabelText: String,
        val inheritedContentEditable: Boolean,
        val framePath: List<String>,
        val frameUrl: String,
        val frameOrigin: String,
        val shadowHostPath: List<String>,
        val scopeKey: String,
        val containerSelector: String = "",
        val containerSummary: String = "",
        val nearbyText: String = "",
        val ancestorHidden: Boolean = false,
        val formSelector: String = "",
        val containerDescendantPath: String = "",
        val hiddenToggle: HiddenToggleInfo? = null,
        val inCalendarContainer: Boolean = false,
        val fieldGroupLabelText: String = ""
    )

    fun evaluateFixtureToJson(
        fixture: UniversalDomScanner.Companion.AttachedDomFixture,
        sessionId: String,
        tabId: String,
        expectedOrigin: String,
        documentId: String,
        limits: ScannerLimits
    ): String {
        val elementsOut = JSONArray()
        val inaccessibleFramesOut = JSONArray()
        var formsCount = 0
        var inaccessibleShadowCount = fixture.closedShadowHostSelectors.size
        var isPartial = false
        var partialReason = ""

        val seenKeys = LinkedHashSet<String>()

        fun parseScope(
            html: String,
            framePath: List<String>,
            frameUrl: String,
            frameOrigin: String,
            shadowHostPath: List<String>,
            iframeDepth: Int,
            shadowDepth: Int
        ) {
            if (isPartial) return

            // Count forms
            val formMatches = Regex("<form\\b([^>]*)>(.*?)</form>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(html)
                .toList()
            val roleFormMatches = Regex("<[a-z0-9-]+\\b[^>]*\\brole\\s*=\\s*[\"']form[\"'][^>]*>", RegexOption.IGNORE_CASE)
                .findAll(html)
                .toList()
            formsCount += formMatches.size + roleFormMatches.size

            // Collect explicit <label for="id">text</label> and id->text map for aria-labelledby within this root scope
            val labelForMap = mutableMapOf<String, MutableList<String>>()
            Regex("<label\\b([^>]*)>(.*?)</label>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(html)
                .forEach { m ->
                    val attrs = parseAttributes(m.groupValues[1])
                    val forId = attrs["for"]?.trim().orEmpty()
                    val cleanText = stripTagsAndNormalize(m.groupValues[2])
                    if (forId.isNotEmpty() && cleanText.isNotEmpty()) {
                        labelForMap.getOrPut(forId) { mutableListOf() }.add(cleanText)
                    }
                }

            val idTextMap = mutableMapOf<String, String>()
            Regex("<([a-zA-Z0-9-]+)\\b([^>]*\\bid\\s*=\\s*[\"'][^\"']+[\"'][^>]*)>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(html)
                .forEach { m ->
                    val attrs = parseAttributes(m.groupValues[2])
                    val id = attrs["id"]?.trim().orEmpty()
                    if (id.isNotEmpty() && !idTextMap.containsKey(id)) {
                        idTextMap[id] = stripTagsAndNormalize(m.groupValues[3])
                    }
                }

            // Detect closed shadow roots in markup (<template shadowrootmode="closed"> or data-shadow-closed="true")
            val closedTemplates = Regex("<template\\b[^>]*shadowrootmode\\s*=\\s*[\"']closed[\"'][^>]*>(.*?)</template>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(html)
                .toList()
            inaccessibleShadowCount += closedTemplates.size

            // Remove closed shadow root contents so they are never traversed (encapsulation respected!)
            var cleanedHtml = html.replace(
                Regex("<template\\b[^>]*shadowrootmode\\s*=\\s*[\"']closed[\"'][^>]*>.*?</template>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                ""
            )

            // Process open shadow roots (<host><template shadowrootmode="open">...</template></host>) with balanced template nesting
            val openShadowStartRegex = Regex(
                "<([a-zA-Z0-9-]+-[a-zA-Z0-9-]+|div|section)\\b([^>]*)>\\s*<template\\b[^>]*shadowrootmode\\s*=\\s*[\"']open[\"'][^>]*>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val templateTokenRegex = Regex("<(/?)template\\b[^>]*>", RegexOption.IGNORE_CASE)
            val shadowRangesToRemove = mutableListOf<IntRange>()
            var searchStart = 0
            while (searchStart < cleanedHtml.length) {
                val sm = openShadowStartRegex.find(cleanedHtml, searchStart) ?: break
                val contentStart = sm.range.last + 1
                var depth = 1
                var closeRange: IntRange? = null
                for (tm in templateTokenRegex.findAll(cleanedHtml, contentStart)) {
                    val isClose = tm.groupValues[1] == "/"
                    if (isClose) {
                        depth--
                        if (depth == 0) {
                            closeRange = tm.range
                            break
                        }
                    } else {
                        depth++
                    }
                }
                if (closeRange == null) {
                    searchStart = sm.range.last + 1
                    continue
                }
                val hostTag = sm.groupValues[1].lowercase()
                val hostAttrs = parseAttributes(sm.groupValues[2])
                val hostId = hostAttrs["id"]?.trim().orEmpty()
                val hostDataTestId = hostAttrs["data-testid"]?.trim().orEmpty()
                val hostSel = when {
                    hostId.isNotEmpty() && LocatorQualityEvaluator.isStableId(hostId) ->
                        "$hostTag#${LocatorQualityEvaluator.escapeCssIdentifier(hostId)}"
                    hostDataTestId.isNotEmpty() ->
                        "$hostTag[data-testid=\"${LocatorQualityEvaluator.escapeCssAttributeValue(hostDataTestId)}\"]"
                    else -> hostTag
                }
                val shadowInner = cleanedHtml.substring(contentStart, closeRange.first)
                if (shadowDepth < limits.maxShadowDepth) {
                    parseScope(
                        html = shadowInner,
                        framePath = framePath,
                        frameUrl = frameUrl,
                        frameOrigin = frameOrigin,
                        shadowHostPath = shadowHostPath + hostSel,
                        iframeDepth = iframeDepth,
                        shadowDepth = shadowDepth + 1
                    )
                } else {
                    isPartial = true
                    partialReason = "Maximum Shadow DOM depth (${limits.maxShadowDepth}) reached."
                }
                val templateOpenIdx = cleanedHtml.indexOf("<template", sm.range.first, ignoreCase = true)
                if (templateOpenIdx >= sm.range.first) {
                    shadowRangesToRemove.add(templateOpenIdx..closeRange.last)
                }
                searchStart = closeRange.last + 1
            }
            if (shadowRangesToRemove.isNotEmpty()) {
                val sb = StringBuilder()
                var lastIdx = 0
                for (rng in shadowRangesToRemove) {
                    if (rng.first > lastIdx) {
                        sb.append(cleanedHtml.substring(lastIdx, rng.first))
                    }
                    lastIdx = rng.last + 1
                }
                if (lastIdx < cleanedHtml.length) {
                    sb.append(cleanedHtml.substring(lastIdx))
                }
                cleanedHtml = sb.toString()
            }

            // Process iframes
            val iframeRegex = Regex("<iframe\\b([^>]*)>(.*?)</iframe>|<iframe\\b([^>]*)/?>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            iframeRegex.findAll(cleanedHtml).forEach { im ->
                val attrRaw = im.groupValues[1].ifEmpty { im.groupValues[3] }
                val attrs = parseAttributes(attrRaw)
                val frameId = attrs["id"]?.trim().orEmpty()
                val frameName = attrs["name"]?.trim().orEmpty()
                val src = attrs["src"]?.trim().orEmpty()
                val srcDoc = attrs["srcdoc"]
                val dataState = attrs["data-frame-state"]?.lowercase().orEmpty()
                val frameSel = when {
                    frameId.isNotEmpty() && LocatorQualityEvaluator.isStableId(frameId) -> "iframe#${LocatorQualityEvaluator.escapeCssIdentifier(frameId)}"
                    frameName.isNotEmpty() -> "iframe[name=\"${LocatorQualityEvaluator.escapeCssAttributeValue(frameName)}\"]"
                    else -> "iframe"
                }
                val nextFramePath = framePath + frameSel

                if (iframeDepth >= limits.maxIframeDepth) {
                    inaccessibleFramesOut.put(
                        InaccessibleFrameInfo(
                            framePath = nextFramePath.joinToString(" >> "),
                            frameSrc = src,
                            frameOrigin = "",
                            reason = "max-depth"
                        ).toJson()
                    )
                    return@forEach
                }

                if (dataState == "detached" || dataState == "unloaded") {
                    inaccessibleFramesOut.put(
                        InaccessibleFrameInfo(
                            framePath = nextFramePath.joinToString(" >> "),
                            frameSrc = src,
                            frameOrigin = "",
                            reason = dataState
                        ).toJson()
                    )
                    return@forEach
                }

                val isCrossOrigin = src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)
                val resolvedSubOrigin = if (isCrossOrigin) {
                    val p = AutomationEngine().parseOrigin(src)
                    if (p is RecordingOriginResult.Valid) p.canonicalOrigin else ""
                } else {
                    expectedOrigin
                }

                if (isCrossOrigin && expectedOrigin.isNotEmpty() && resolvedSubOrigin.isNotEmpty() && resolvedSubOrigin != expectedOrigin) {
                    inaccessibleFramesOut.put(
                        InaccessibleFrameInfo(
                            framePath = nextFramePath.joinToString(" >> "),
                            frameSrc = src,
                            frameOrigin = resolvedSubOrigin,
                            reason = "cross-origin"
                        ).toJson()
                    )
                } else if (srcDoc != null || im.groupValues[2].isNotBlank()) {
                    val subHtml = srcDoc ?: im.groupValues[2]
                    parseScope(
                        html = subHtml,
                        framePath = nextFramePath,
                        frameUrl = src.ifBlank { "about:srcdoc" },
                        frameOrigin = expectedOrigin,
                        shadowHostPath = emptyList(),
                        iframeDepth = iframeDepth + 1,
                        shadowDepth = shadowDepth
                    )
                } else if (src.isEmpty() || src == "about:blank") {
                    inaccessibleFramesOut.put(
                        InaccessibleFrameInfo(
                            framePath = nextFramePath.joinToString(" >> "),
                            frameSrc = src,
                            frameOrigin = expectedOrigin,
                            reason = "unloaded"
                        ).toJson()
                    )
                }
            }
            cleanedHtml = cleanedHtml.replace(iframeRegex, "")

            // Extract elements in this scope
            val nodes = extractNodesFromScopeHtml(
                html = cleanedHtml,
                framePath = framePath,
                frameUrl = frameUrl,
                frameOrigin = frameOrigin,
                shadowHostPath = shadowHostPath,
                scopeKey = "${framePath.joinToString("/")}|${shadowHostPath.joinToString("/")}",
                idTextMap = idTextMap
            )

            // Count selector frequencies in this scope for uniqueness/ambiguity validation
            val tagCount = mutableMapOf<String, Int>()
            val idCount = mutableMapOf<String, Int>()
            val nameCount = mutableMapOf<String, Int>()
            val roleAriaCount = mutableMapOf<String, Int>()
            val dataTestCount = mutableMapOf<String, Int>()
            val widgetAttrCount = mutableMapOf<String, Int>()
            val labelForCount = mutableMapOf<String, Int>()
            val hrefCount = mutableMapOf<String, Int>()
            val placeholderCount = mutableMapOf<String, Int>()
            val containerScopedCount = mutableMapOf<String, Int>()
            val formScopedCount = mutableMapOf<String, Int>()
            val summaryScopedCount = mutableMapOf<String, Int>()

            nodes.forEach { n ->
                tagCount[n.tagName] = (tagCount[n.tagName] ?: 0) + 1
                val id = n.attributes["id"].orEmpty()
                if (id.isNotEmpty()) idCount[id] = (idCount[id] ?: 0) + 1
                val name = n.attributes["name"].orEmpty()
                if (name.isNotEmpty()) nameCount["${n.tagName}|$name"] = (nameCount["${n.tagName}|$name"] ?: 0) + 1
                val role = n.attributes["role"].orEmpty()
                val aria = n.attributes["aria-label"].orEmpty()
                if (aria.isNotEmpty()) roleAriaCount["${n.tagName}|$role|$aria"] = (roleAriaCount["${n.tagName}|$role|$aria"] ?: 0) + 1
                val dt = n.attributes["data-testid"] ?: n.attributes["data-test"] ?: n.attributes["data-qa"] ?: n.attributes["data-cy"] ?: n.attributes["data-automation-id"]
                if (!dt.isNullOrEmpty()) dataTestCount[dt] = (dataTestCount[dt] ?: 0) + 1
                val forAttr = if (n.tagName == "label") n.attributes["for"].orEmpty() else ""
                if (forAttr.isNotEmpty()) labelForCount[forAttr] = (labelForCount[forAttr] ?: 0) + 1
                for (wAttr in WIDGET_DATA_ATTRS) {
                    val wVal = n.attributes[wAttr]?.trim().orEmpty()
                    if (wVal.isNotEmpty()) {
                        widgetAttrCount["${n.tagName}|$wAttr=$wVal"] = (widgetAttrCount["${n.tagName}|$wAttr=$wVal"] ?: 0) + 1
                    }
                }
                val href = n.attributes["href"]?.trim().orEmpty()
                if (n.tagName == "a" && href.isNotEmpty() && href != "#" && !href.startsWith("javascript:", ignoreCase = true)) {
                    hrefCount[href] = (hrefCount[href] ?: 0) + 1
                }
                val ph = n.attributes["placeholder"]?.trim().orEmpty()
                if (ph.isNotEmpty()) {
                    placeholderCount["${n.tagName}|$ph"] = (placeholderCount["${n.tagName}|$ph"] ?: 0) + 1
                }
                val typeAttr = n.attributes["type"]?.trim().orEmpty()
                if (n.formSelector.isNotEmpty()) {
                    val fBase = "${n.formSelector}|${n.tagName}"
                    formScopedCount[fBase] = (formScopedCount[fBase] ?: 0) + 1
                    if (id.isNotEmpty()) formScopedCount["${n.formSelector}|id=$id"] = (formScopedCount["${n.formSelector}|id=$id"] ?: 0) + 1
                    if (!dt.isNullOrEmpty()) formScopedCount["$fBase|dt=$dt"] = (formScopedCount["$fBase|dt=$dt"] ?: 0) + 1
                    if (name.isNotEmpty()) formScopedCount["$fBase|name=$name"] = (formScopedCount["$fBase|name=$name"] ?: 0) + 1
                    if (aria.isNotEmpty()) formScopedCount["$fBase|aria=$aria"] = (formScopedCount["$fBase|aria=$aria"] ?: 0) + 1
                    if (typeAttr.isNotEmpty()) formScopedCount["$fBase|type=$typeAttr"] = (formScopedCount["$fBase|type=$typeAttr"] ?: 0) + 1
                    if (href.isNotEmpty()) formScopedCount["$fBase|href=$href"] = (formScopedCount["$fBase|href=$href"] ?: 0) + 1
                }
                if (n.containerSelector.isNotEmpty()) {
                    val baseTagKey = "${n.containerSelector}|${n.tagName}"
                    containerScopedCount[baseTagKey] = (containerScopedCount[baseTagKey] ?: 0) + 1
                    if (id.isNotEmpty()) containerScopedCount["${n.containerSelector}|id=$id"] = (containerScopedCount["${n.containerSelector}|id=$id"] ?: 0) + 1
                    if (!dt.isNullOrEmpty()) containerScopedCount["$baseTagKey|dt=$dt"] = (containerScopedCount["$baseTagKey|dt=$dt"] ?: 0) + 1
                    if (name.isNotEmpty()) containerScopedCount["$baseTagKey|name=$name"] = (containerScopedCount["$baseTagKey|name=$name"] ?: 0) + 1
                    if (aria.isNotEmpty()) containerScopedCount["$baseTagKey|aria=$aria"] = (containerScopedCount["$baseTagKey|aria=$aria"] ?: 0) + 1
                    if (typeAttr.isNotEmpty()) containerScopedCount["$baseTagKey|type=$typeAttr"] = (containerScopedCount["$baseTagKey|type=$typeAttr"] ?: 0) + 1
                    if (href.isNotEmpty()) containerScopedCount["$baseTagKey|href=$href"] = (containerScopedCount["$baseTagKey|href=$href"] ?: 0) + 1
                    if (forAttr.isNotEmpty()) containerScopedCount["$baseTagKey|for=$forAttr"] = (containerScopedCount["$baseTagKey|for=$forAttr"] ?: 0) + 1
                    for (wAttr in WIDGET_DATA_ATTRS) {
                        val wVal = n.attributes[wAttr]?.trim().orEmpty()
                        if (wVal.isNotEmpty()) {
                            containerScopedCount["$baseTagKey|$wAttr=$wVal"] = (containerScopedCount["$baseTagKey|$wAttr=$wVal"] ?: 0) + 1
                        }
                    }
                }
                if (n.containerDescendantPath.isNotEmpty()) {
                    val sBase = "${n.containerDescendantPath}|${n.tagName}"
                    summaryScopedCount[sBase] = (summaryScopedCount[sBase] ?: 0) + 1
                    if (id.isNotEmpty()) summaryScopedCount["${n.containerDescendantPath}|id=$id"] = (summaryScopedCount["${n.containerDescendantPath}|id=$id"] ?: 0) + 1
                    if (!dt.isNullOrEmpty()) summaryScopedCount["$sBase|dt=$dt"] = (summaryScopedCount["$sBase|dt=$dt"] ?: 0) + 1
                    if (name.isNotEmpty()) summaryScopedCount["$sBase|name=$name"] = (summaryScopedCount["$sBase|name=$name"] ?: 0) + 1
                    if (aria.isNotEmpty()) summaryScopedCount["$sBase|aria=$aria"] = (summaryScopedCount["$sBase|aria=$aria"] ?: 0) + 1
                    if (typeAttr.isNotEmpty()) summaryScopedCount["$sBase|type=$typeAttr"] = (summaryScopedCount["$sBase|type=$typeAttr"] ?: 0) + 1
                    if (href.isNotEmpty()) summaryScopedCount["$sBase|href=$href"] = (summaryScopedCount["$sBase|href=$href"] ?: 0) + 1
                }
            }

            for (node in nodes) {
                if (elementsOut.length() >= limits.maxElements) {
                    isPartial = true
                    partialReason = "Reached maximum discovered elements limit (${limits.maxElements})."
                    return
                }
                val itemJson = buildElementJsonFromNode(
                    node = node,
                    index = elementsOut.length(),
                    documentId = documentId,
                    labelForMap = labelForMap,
                    idTextMap = idTextMap,
                    idCount = idCount,
                    nameCount = nameCount,
                    roleAriaCount = roleAriaCount,
                    dataTestCount = dataTestCount,
                    widgetAttrCount = widgetAttrCount,
                    labelForCount = labelForCount,
                    hrefCount = hrefCount,
                    placeholderCount = placeholderCount,
                    containerScopedCount = containerScopedCount,
                    formScopedCount = formScopedCount,
                    summaryScopedCount = summaryScopedCount,
                    tagCount = tagCount
                ) ?: continue

                val ambigSuffix = if (itemJson.optBoolean("isLocatorAmbiguous", false)) "|idx_${elementsOut.length()}" else ""
                val dedupeKey = "${node.scopeKey}|${node.tagName}|${itemJson.optString("selector")}|${itemJson.optString("accessibleName")}$ambigSuffix"
                if (seenKeys.add(dedupeKey)) {
                    elementsOut.put(itemJson)
                }
            }
        }

        parseScope(
            html = fixture.html,
            framePath = emptyList(),
            frameUrl = fixture.url,
            frameOrigin = expectedOrigin,
            shadowHostPath = emptyList(),
            iframeDepth = 0,
            shadowDepth = 0
        )

        fixture.crossOriginFrameUrls.forEachIndexed { idx, crossUrl ->
            val p = AutomationEngine().parseOrigin(crossUrl)
            inaccessibleFramesOut.put(
                InaccessibleFrameInfo(
                    framePath = "iframe:nth-of-type(${idx + 1})",
                    frameSrc = crossUrl,
                    frameOrigin = if (p is RecordingOriginResult.Valid) p.canonicalOrigin else "",
                    reason = "cross-origin"
                ).toJson()
            )
        }

        return JSONObject().apply {
            put("sessionId", sessionId)
            put("tabId", tabId)
            put("origin", expectedOrigin)
            put("formsFound", formsCount)
            put("inaccessibleShadowRoots", inaccessibleShadowCount)
            put("inaccessibleFrames", inaccessibleFramesOut)
            put("isPartial", isPartial)
            put("partialReason", partialReason)
            put("elements", elementsOut)
        }.toString()
    }

    private fun isToggleInputVisuallyHidden(attrs: Map<String, String>): Boolean {
        if (attrs.containsKey("hidden") || attrs.containsKey("inert")) return true
        if (attrs["aria-hidden"]?.equals("true", ignoreCase = true) == true) return true
        val cls = attrs["class"]?.lowercase().orEmpty()
        if (Regex("\\b(sr-only|visually-hidden)\\b").containsMatchIn(cls)) return true
        val style = attrs["style"]?.lowercase().orEmpty()
        return style.contains("display:none") ||
            style.contains("display: none") ||
            style.contains("visibility:hidden") ||
            style.contains("visibility: hidden") ||
            Regex("opacity\\s*:\\s*0(?:\\.0+)?\\s*(?:;|$)").containsMatchIn(style) ||
            Regex("clip\\s*:\\s*rect\\(\\s*0").containsMatchIn(style)
    }

    private fun extractNodesFromScopeHtml(
        html: String,
        framePath: List<String>,
        frameUrl: String,
        frameOrigin: String,
        shadowHostPath: List<String>,
        scopeKey: String,
        idTextMap: Map<String, String> = emptyMap()
    ): List<ParsedElementNode> {
        val result = mutableListOf<ParsedElementNode>()

        // Pre-index visually hidden radio/checkbox inputs by ID for <label for="..."> promotion
        val hiddenToggleInputsById = mutableMapOf<String, HiddenToggleInfo>()
        Regex("<input\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(html).forEach { im ->
            val iAttrs = parseAttributes(im.groupValues[1])
            val iType = iAttrs["type"]?.lowercase().orEmpty()
            val iId = iAttrs["id"]?.trim().orEmpty()
            if (iId.isNotEmpty() && iType in setOf("radio", "checkbox") && isToggleInputVisuallyHidden(iAttrs)) {
                hiddenToggleInputsById[iId] = HiddenToggleInfo(
                    id = iId,
                    inputType = iType,
                    name = iAttrs["name"]?.trim().orEmpty(),
                    checked = iAttrs.containsKey("checked"),
                    disabled = iAttrs.containsKey("disabled") || iAttrs["aria-disabled"]?.equals("true", ignoreCase = true) == true
                )
            }
        }

        // Track enclosing form, fieldset/legend, wrapping label, and inherited contenteditable
        val tagTokenRegex = Regex(
            "<!--.*?-->|<(/?)([a-zA-Z0-9-]+)\\b([^>]*?)(/?)>",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        data class OpenContainer(
            val tag: String,
            val attrs: Map<String, String>,
            val startOffset: Int,
            var legendText: String = "",
            val contentEditable: Boolean = false,
            val ancestorHidden: Boolean = false
        )

        val stack = ArrayDeque<OpenContainer>()
        val matches = tagTokenRegex.findAll(html).toList()
        var recentContextText = ""
        var recentUnlinkedLabelText = ""

        val voidTags = setOf("input", "img", "br", "hr", "meta", "link", "source", "track", "wbr", "area", "base", "col", "embed", "param")
        val interactiveRoles = setOf(
            "textbox", "searchbox", "combobox", "listbox", "option",
            "checkbox", "radio", "switch", "button", "link",
            "slider", "spinbutton", "menu", "menuitem", "menuitemcheckbox", "menuitemradio",
            "tab", "tree", "treeitem", "grid", "gridcell", "dialog"
        )

        fun isSheetOrDialogAttrs(tag: String, attrs: Map<String, String>): Boolean {
            if (tag == "dialog") return true
            val role = attrs["role"]?.lowercase().orEmpty()
            if (role in setOf("dialog", "alertdialog", "listbox", "menu")) return true
            if (attrs.containsKey("aria-modal") || attrs.containsKey("data-bottom-sheet") ||
                attrs.containsKey("data-sheet") || attrs.containsKey("data-drawer")
            ) return true
            val cls = attrs["class"]?.lowercase().orEmpty()
            return Regex("(bottom-sheet|bottomsheet|modal|drawer|sheet-content|datepicker|calendar-popup|country-picker)").containsMatchIn(cls)
        }

        fun buildContainerSelector(c: OpenContainer): String {
            val cid = c.attrs["id"]?.trim().orEmpty()
            val cdt = c.attrs["data-testid"]?.trim().orEmpty()
            val cRole = c.attrs["role"]?.trim().orEmpty()
            val cAria = c.attrs["aria-label"]?.trim().orEmpty()
            val cName = c.attrs["name"]?.trim().orEmpty()
            return when {
                cid.isNotEmpty() && LocatorQualityEvaluator.isStableId(cid) ->
                    "${c.tag}#${LocatorQualityEvaluator.escapeCssIdentifier(cid)}"
                cdt.isNotEmpty() ->
                    "${c.tag}[data-testid=\"${LocatorQualityEvaluator.escapeCssAttributeValue(cdt)}\"]"
                c.attrs.containsKey("data-bottom-sheet") -> "${c.tag}[data-bottom-sheet]"
                c.attrs.containsKey("data-sheet") -> "${c.tag}[data-sheet]"
                c.attrs.containsKey("data-datepicker") -> "${c.tag}[data-datepicker]"
                c.attrs.containsKey("data-calendar") -> "${c.tag}[data-calendar]"
                c.tag == "form" && cName.isNotEmpty() ->
                    "form[name=\"${LocatorQualityEvaluator.escapeCssAttributeValue(cName)}\"]"
                cAria.isNotEmpty() ->
                    "${c.tag}[aria-label=\"${LocatorQualityEvaluator.escapeCssAttributeValue(cAria)}\"]"
                c.tag in setOf("form", "dialog", "header", "nav", "footer", "main", "aside") -> c.tag
                cRole in setOf("dialog", "alertdialog", "radiogroup", "listbox", "grid", "navigation", "banner", "main", "form", "region", "menu") ->
                    "${c.tag}[role=\"${LocatorQualityEvaluator.escapeCssAttributeValue(cRole)}\"]"
                else -> ""
            }
        }

        fun isContainerHidden(tag: String, attrs: Map<String, String>): Boolean {
            if (attrs.containsKey("hidden") || attrs.containsKey("inert")) return true
            if (attrs["aria-hidden"]?.equals("true", ignoreCase = true) == true) return true
            val dataState = attrs["data-state"]?.lowercase().orEmpty()
            val dataOpen = attrs["data-open"]?.lowercase().orEmpty()
            if (tag == "dialog" && !attrs.containsKey("open") && dataState != "open" && dataOpen != "true") return true
            if (isSheetOrDialogAttrs(tag, attrs) && (dataState == "closed" || dataOpen == "false")) return true
            val style = attrs["style"]?.lowercase().orEmpty()
            return style.contains("display:none") ||
                style.contains("display: none") ||
                style.contains("visibility:hidden") ||
                style.contains("visibility: hidden") ||
                Regex("opacity\\s*:\\s*0(?:\\.0+)?\\s*(?:;|$)").containsMatchIn(style)
        }

        fun findHiddenToggleForLabel(labelAttrs: Map<String, String>, labelInnerHtml: String): HiddenToggleInfo? {
            val forId = labelAttrs["for"]?.trim().orEmpty()
            if (forId.isNotEmpty()) {
                hiddenToggleInputsById[forId]?.let { return it }
            }
            val nestedInputMatch = Regex("<input\\b([^>]*)/?>", RegexOption.IGNORE_CASE).find(labelInnerHtml)
            if (nestedInputMatch != null) {
                val nAttrs = parseAttributes(nestedInputMatch.groupValues[1])
                val nType = nAttrs["type"]?.lowercase().orEmpty()
                if (nType in setOf("radio", "checkbox") && isToggleInputVisuallyHidden(nAttrs)) {
                    return HiddenToggleInfo(
                        id = nAttrs["id"]?.trim().orEmpty(),
                        inputType = nType,
                        name = nAttrs["name"]?.trim().orEmpty(),
                        checked = nAttrs.containsKey("checked"),
                        disabled = nAttrs.containsKey("disabled") || nAttrs["aria-disabled"]?.equals("true", ignoreCase = true) == true
                    )
                }
            }
            return null
        }

        for (i in matches.indices) {
            val m = matches[i]
            val fullMatch = m.value
            if (fullMatch.startsWith("<!--")) continue
            val isClosing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val attrRaw = m.groupValues[3]
            val isSelfClosing = m.groupValues[4] == "/" || tag in voidTags

            if (isClosing) {
                if (tag == "legend") {
                    val openLegend = stack.lastOrNull { it.tag == "legend" }
                    if (openLegend != null) {
                        val inner = html.substring(openLegend.startOffset, m.range.first)
                        val legendTxt = stripTagsAndNormalize(inner)
                        stack.findLast { it.tag == "fieldset" }?.legendText = legendTxt
                    }
                }
                if (tag in setOf("form", "fieldset", "section", "article", "dialog")) {
                    recentUnlinkedLabelText = ""
                }
                val idx = stack.indexOfLast { it.tag == tag }
                if (idx >= 0) {
                    while (stack.size > idx) stack.removeLast()
                }
                continue
            }

            val attrs = parseAttributes(attrRaw)
            val ceRaw = attrs["contenteditable"]?.lowercase()
            val parentEditable = stack.lastOrNull()?.contentEditable ?: false
            val thisEditable = when (ceRaw) {
                "true", "", "plaintext-only" -> true
                "false" -> false
                else -> parentEditable
            }
            val parentHidden = stack.lastOrNull()?.ancestorHidden ?: false
            val thisAncestorHidden = parentHidden || isContainerHidden(tag, attrs)

            // Extract inner content for paired tags
            var innerHtml = ""
            if (!isSelfClosing) {
                var depth = 1
                for (j in (i + 1) until matches.size) {
                    val nm = matches[j]
                    if (nm.value.startsWith("<!--")) continue
                    val nClose = nm.groupValues[1] == "/"
                    val nTag = nm.groupValues[2].lowercase()
                    val nSelf = nm.groupValues[4] == "/" || nTag in voidTags
                    if (nTag == tag) {
                        if (nClose) {
                            depth--
                            if (depth == 0) {
                                innerHtml = html.substring(m.range.last + 1, nm.range.first)
                                break
                            }
                        } else if (!nSelf) {
                            depth++
                        }
                    }
                }
            }

            if (tag in setOf("h1", "h2", "h3", "h4", "legend", "label") && innerHtml.isNotBlank()) {
                val cleanHeading = stripTagsAndNormalize(innerHtml)
                if (cleanHeading.isNotBlank()) {
                    recentContextText = cleanHeading.take(80)
                }
            }
            val attrClsLower = attrs["class"]?.lowercase().orEmpty()
            val isExplicitLabelNode = tag == "label" ||
                (tag in setOf("div", "span", "p") && Regex("(?:^|[\\s_-])(label|field-label|form-label|control-label|input-label)(?:$|[\\s_-])").containsMatchIn(attrClsLower))
            if (isExplicitLabelNode && innerHtml.isNotBlank() && !Regex("<(input|select|textarea|button)\\b", RegexOption.IGNORE_CASE).containsMatchIn(innerHtml)) {
                val forAttrVal = attrs["for"]?.trim().orEmpty()
                if (forAttrVal.isEmpty() || !idTextMap.containsKey(forAttrVal)) {
                    val cleanLbl = stripTagsAndNormalize(innerHtml)
                    if (cleanLbl.isNotBlank() && cleanLbl.length <= 48) {
                        recentUnlinkedLabelText = cleanLbl
                    }
                }
            }

            val role = attrs["role"]?.lowercase().orEmpty()
            val tabIndex = attrs["tabindex"]?.toIntOrNull()
            val isExplicitEditable = ceRaw == "true" || ceRaw == "" || ceRaw == "plaintext-only"
            val isInheritedEditableLeaf = !isExplicitEditable && thisEditable && tag in setOf("p", "span", "div") && !innerHtml.contains("<")

            val inCalendar = stack.any { c ->
                c.attrs["role"]?.equals("grid", ignoreCase = true) == true ||
                    c.attrs.containsKey("data-datepicker") ||
                    c.attrs.containsKey("data-calendar") ||
                    c.attrs.containsKey("data-year-picker") ||
                    c.attrs["class"]?.lowercase()?.let {
                        it.contains("calendar") || it.contains("datepicker") || it.contains("year-picker") || it.contains("year-list")
                    } == true
            }
            val inWidgetListOrSheet = stack.any { c ->
                c.tag == "dialog" ||
                    c.attrs["role"]?.lowercase() in setOf("listbox", "dialog", "radiogroup", "menu") ||
                    c.attrs.containsKey("data-bottom-sheet") ||
                    c.attrs.containsKey("data-sheet") ||
                    c.attrs.containsKey("data-drawer") ||
                    c.attrs.containsKey("data-year-picker") ||
                    c.attrs["class"]?.lowercase()?.let {
                        it.contains("year-picker") || it.contains("year-list") || it.contains("year-wheel")
                    } == true
            }
            val labelHiddenToggle = if (tag == "label") findHiddenToggleForLabel(attrs, innerHtml) else null
            val cleanText = stripTagsAndNormalize(innerHtml)
            val clsLower = attrs["class"]?.lowercase().orEmpty()
            val isCalendarLeafCell = inCalendar &&
                tag in setOf("td", "div", "span", "li", "button") &&
                !innerHtml.contains("<") &&
                (
                    (Regex("^\\d{1,2}$").matches(cleanText) && Regex("(day|date|cell)").containsMatchIn(clsLower)) ||
                        (Regex("^(19\\d{2}|20\\d{2})$").matches(cleanText) &&
                            (Regex("(year|cell|option|item)").containsMatchIn(clsLower) || inWidgetListOrSheet))
                    )
            val isScrollableYearItem = !innerHtml.contains("<") &&
                tag in setOf("li", "div", "span", "button") &&
                Regex("^(19\\d{2}|20\\d{2})$").matches(cleanText) &&
                (inWidgetListOrSheet || inCalendar || attrs.containsKey("data-year") || Regex("(year-option|year-item|year-cell)").containsMatchIn(clsLower))

            val isCandidate = tag in setOf("input", "textarea", "select", "option", "button", "summary", "details") ||
                (tag == "a" && attrs.containsKey("href")) ||
                isExplicitEditable ||
                isInheritedEditableLeaf ||
                role in interactiveRoles ||
                labelHiddenToggle != null ||
                isCalendarLeafCell ||
                isScrollableYearItem ||
                attrs.containsKey("data-datepicker") ||
                attrs.containsKey("data-timepicker") ||
                attrs.containsKey("data-calendar") ||
                attrs.containsKey("data-year-picker") ||
                attrs.containsKey("data-birth-year") ||
                attrs.containsKey("data-date") ||
                attrs.containsKey("data-day") ||
                attrs.containsKey("data-month") ||
                attrs.containsKey("data-year") ||
                attrs.containsKey("data-dial-code") ||
                attrs.containsKey("data-country-code") ||
                attrs.containsKey("data-country") ||
                attrs.containsKey("data-code") ||
                attrs.containsKey("data-gender") ||
                attrs.containsKey("data-sex") ||
                attrs.containsKey("data-option") ||
                attrs.containsKey("aria-haspopup") ||
                attrs.containsKey("aria-pressed") ||
                attrs["aria-current"]?.equals("date", ignoreCase = true) == true ||
                attrs.containsKey("onclick") ||
                (attrs.containsKey("data-value") && (inWidgetListOrSheet || tag == "li" || tabIndex != null)) ||
                (tabIndex != null && tabIndex >= 0)

            if (isCandidate) {
                val enclosingForm = stack.findLast { it.tag == "form" || it.attrs["role"]?.equals("form", ignoreCase = true) == true }
                val formAttrId = attrs["form"]?.trim().orEmpty()
                val enclosingFormId = enclosingForm?.attrs?.get("id")?.trim().orEmpty()
                val enclosingFormName = enclosingForm?.attrs?.get("name")?.trim().orEmpty()
                val formId = formAttrId.ifEmpty {
                    enclosingFormId.ifEmpty { enclosingFormName }
                }
                val formName = enclosingFormName.ifEmpty { formId }
                val formSelector = when {
                    formAttrId.isNotEmpty() && LocatorQualityEvaluator.isStableId(formAttrId) ->
                        "form#${LocatorQualityEvaluator.escapeCssIdentifier(formAttrId)}"
                    enclosingFormId.isNotEmpty() && LocatorQualityEvaluator.isStableId(enclosingFormId) ->
                        "form#${LocatorQualityEvaluator.escapeCssIdentifier(enclosingFormId)}"
                    enclosingFormName.isNotEmpty() ->
                        "form[name=\"${LocatorQualityEvaluator.escapeCssAttributeValue(enclosingFormName)}\"]"
                    else -> ""
                }
                val rawFieldsetLegend = stack.findLast { it.tag == "fieldset" }?.legendText.orEmpty()
                val groupLegendFallback = if (rawFieldsetLegend.isEmpty()) {
                    val grp = stack.findLast {
                        it.attrs["role"]?.lowercase() in setOf("radiogroup", "group", "dialog") ||
                            it.attrs.containsKey("data-bottom-sheet") ||
                            it.attrs.containsKey("data-datepicker") ||
                            it.attrs.containsKey("data-dob-group") ||
                            it.attrs["class"]?.lowercase()?.let { cls ->
                                Regex("(dob|birth[-_]?date|date[-_]?of[-_]?birth)").containsMatchIn(cls)
                            } == true
                    }
                    if (grp != null) {
                        val grpLb = grp.attrs["aria-labelledby"]?.trim().orEmpty()
                        val lbTxt = if (grpLb.isNotEmpty()) idTextMap[grpLb.split(Regex("\\s+")).first()].orEmpty() else ""
                        lbTxt.ifBlank { grp.attrs["aria-label"]?.trim().orEmpty() }
                    } else ""
                } else ""
                val fieldsetLegend = rawFieldsetLegend.ifBlank { groupLegendFallback }

                val wrappingLabel = if (tag == "label") {
                    ""
                } else {
                    stack.findLast { it.tag == "label" }?.let { lblContainer ->
                        val closeLabelIdx = html.indexOf("</label>", lblContainer.startOffset, ignoreCase = true)
                        if (closeLabelIdx > lblContainer.startOffset) {
                            stripTagsAndNormalize(html.substring(lblContainer.startOffset, closeLabelIdx))
                        } else ""
                    }.orEmpty()
                }

                val containerChain = stack.mapNotNull { c ->
                    val sel = buildContainerSelector(c)
                    if (sel.isNotEmpty()) sel else null
                }
                val nearestContainerSel = containerChain.lastOrNull().orEmpty()
                val containerSummary = containerChain.takeLast(2).joinToString(" > ")
                val containerDescendantPath = containerChain.takeLast(3).joinToString(" ")

                val effectiveAttrs = if (isInheritedEditableLeaf && !attrs.containsKey("contenteditable")) {
                    attrs + ("contenteditable" to "inherited")
                } else if (ceRaw == "" && attrs.containsKey("contenteditable")) {
                    attrs + ("contenteditable" to "true")
                } else {
                    attrs
                }

                val isFormFieldControl = tag in setOf("input", "select", "textarea") &&
                    attrs["type"]?.lowercase() !in setOf("submit", "button", "reset", "hidden") ||
                    role in setOf("combobox", "listbox", "textbox", "spinbutton")
                val resolvedFieldGroupLabel = if (isFormFieldControl && wrappingLabel.isEmpty()) {
                    recentUnlinkedLabelText.also { recentUnlinkedLabelText = "" }
                } else ""

                result.add(
                    ParsedElementNode(
                        tagName = tag,
                        attributes = effectiveAttrs,
                        textContent = cleanText,
                        innerHtml = innerHtml,
                        formId = formId,
                        formName = formName,
                        fieldsetLegend = fieldsetLegend,
                        wrappingLabelText = wrappingLabel,
                        inheritedContentEditable = isInheritedEditableLeaf,
                        framePath = framePath,
                        frameUrl = frameUrl,
                        frameOrigin = frameOrigin,
                        shadowHostPath = shadowHostPath,
                        scopeKey = scopeKey,
                        containerSelector = nearestContainerSel,
                        containerSummary = containerSummary,
                        nearbyText = resolvedFieldGroupLabel.ifBlank { fieldsetLegend.ifBlank { recentContextText } },
                        ancestorHidden = parentHidden,
                        formSelector = formSelector,
                        containerDescendantPath = containerDescendantPath,
                        hiddenToggle = labelHiddenToggle,
                        inCalendarContainer = inCalendar,
                        fieldGroupLabelText = resolvedFieldGroupLabel
                    )
                )
            }

            if (!isSelfClosing) {
                stack.addLast(
                    OpenContainer(
                        tag = tag,
                        attrs = attrs,
                        startOffset = m.range.last + 1,
                        contentEditable = thisEditable,
                        ancestorHidden = thisAncestorHidden
                    )
                )
            }
        }
        return result
    }

    private fun buildElementJsonFromNode(
        node: ParsedElementNode,
        index: Int,
        documentId: String,
        labelForMap: Map<String, List<String>>,
        idTextMap: Map<String, String>,
        idCount: Map<String, Int>,
        nameCount: Map<String, Int>,
        roleAriaCount: Map<String, Int>,
        dataTestCount: Map<String, Int>,
        widgetAttrCount: Map<String, Int>,
        labelForCount: Map<String, Int>,
        hrefCount: Map<String, Int>,
        placeholderCount: Map<String, Int>,
        containerScopedCount: Map<String, Int>,
        formScopedCount: Map<String, Int>,
        summaryScopedCount: Map<String, Int>,
        tagCount: Map<String, Int>
    ): JSONObject? {
        val tag = node.tagName
        val attrs = node.attributes
        val inputType = attrs["type"]?.lowercase().orEmpty()
        val htmlId = attrs["id"]?.trim().orEmpty()
        val nameAttr = attrs["name"]?.trim().orEmpty()
        val placeholder = attrs["placeholder"]?.trim().orEmpty()
        val titleAttr = attrs["title"]?.trim().orEmpty()
        val autocomplete = attrs["autocomplete"]?.trim().orEmpty()
        val roleAttr = attrs["role"]?.lowercase()?.trim().orEmpty()
        val hiddenToggle = node.hiddenToggle
        val role = roleAttr.ifEmpty {
            when {
                hiddenToggle != null -> hiddenToggle.inputType
                tag == "input" -> inputType.ifEmpty { "text" }
                tag == "a" -> "link"
                else -> tag
            }
        }

        val hasCustomClickableSemantics = tag in setOf("button", "a", "summary", "option", "label") ||
            role in setOf("button", "link", "menuitem", "menuitemcheckbox", "menuitemradio", "tab", "option", "treeitem", "switch", "checkbox", "radio", "combobox", "gridcell") ||
            attrs.containsKey("onclick") ||
            attrs.containsKey("aria-haspopup") ||
            attrs.containsKey("aria-pressed") ||
            attrs.containsKey("aria-selected") ||
            attrs.containsKey("aria-checked") ||
            attrs.containsKey("data-datepicker") ||
            attrs.containsKey("data-timepicker") ||
            attrs.containsKey("data-calendar") ||
            attrs.containsKey("data-year-picker") ||
            attrs.containsKey("data-birth-year") ||
            attrs.containsKey("data-date") ||
            attrs.containsKey("data-day") ||
            attrs.containsKey("data-month") ||
            attrs.containsKey("data-year") ||
            attrs.containsKey("data-dial-code") ||
            attrs.containsKey("data-country-code") ||
            attrs.containsKey("data-country") ||
            attrs.containsKey("data-code") ||
            attrs.containsKey("data-gender") ||
            attrs.containsKey("data-sex") ||
            attrs.containsKey("data-option") ||
            attrs.containsKey("data-value") ||
            attrs["aria-current"]?.equals("date", ignoreCase = true) == true

        // Accessible Name Resolution Precedence:
        // 1. aria-labelledby -> 2. aria-label -> 3. explicit label[for] -> 4. wrapping label
        // -> 5. fieldGroupLabel -> 6. title -> 7. placeholder -> 8. aria-describedby -> 9. button/link/option/custom trigger text -> 10. name/id fallback
        val labelledByIds = attrs["aria-labelledby"]?.trim().orEmpty()
        val labelledByText = if (labelledByIds.isNotEmpty()) {
            labelledByIds.split(Regex("\\s+"))
                .mapNotNull { id -> idTextMap[id]?.takeIf { it.isNotBlank() } }
                .joinToString(" ")
        } else ""
        val describedByIds = attrs["aria-describedby"]?.trim().orEmpty()
        val describedByText = if (describedByIds.isNotEmpty()) {
            describedByIds.split(Regex("\\s+"))
                .mapNotNull { id -> idTextMap[id]?.takeIf { it.isNotBlank() } }
                .joinToString(" ")
        } else ""

        val ariaLabel = attrs["aria-label"]?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val explicitLabels = if (htmlId.isNotEmpty()) labelForMap[htmlId]?.joinToString(" ").orEmpty() else ""
        val wrappingLabel = node.wrappingLabelText
        val fieldGroupLabel = node.fieldGroupLabelText
        val semanticInnerText = if (hasCustomClickableSemantics) {
            node.textContent
        } else if (tag == "input" && inputType in setOf("submit", "button", "reset")) {
            attrs["value"]?.trim().orEmpty()
        } else ""

        val accessibleName = sequenceOf(
            labelledByText,
            ariaLabel,
            explicitLabels,
            wrappingLabel,
            fieldGroupLabel,
            titleAttr,
            placeholder,
            describedByText,
            semanticInnerText,
            nameAttr,
            if (LocatorQualityEvaluator.isStableId(htmlId)) htmlId else ""
        ).map { it.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(120)
            ?: tag

        val associatedLabel = sequenceOf(
            explicitLabels,
            wrappingLabel,
            fieldGroupLabel,
            ariaLabel,
            labelledByText,
            describedByText,
            accessibleName
        ).firstOrNull { it.isNotEmpty() } ?: accessibleName

        // Build Locator Candidates
        val prefix = when {
            node.shadowHostPath.isNotEmpty() -> node.shadowHostPath.joinToString(" >>> ") + " >>> "
            node.framePath.isNotEmpty() -> node.framePath.joinToString(" >> ") + " >> "
            else -> ""
        }
        val scopeName = when {
            node.shadowHostPath.isNotEmpty() -> "SHADOW"
            node.framePath.isNotEmpty() -> "IFRAME"
            node.formId.isNotEmpty() -> "FORM"
            else -> "DOCUMENT"
        }

        val candidates = mutableListOf<LocatorCandidate>()
        fun addCand(strategy: LocatorStrategy, rawLocalSel: String, matchCnt: Int, stability: LocatorStability) {
            val effectiveStrategy = when {
                node.shadowHostPath.isNotEmpty() -> LocatorStrategy.SHADOW_DOM_CHAIN
                node.framePath.isNotEmpty() -> LocatorStrategy.IFRAME_CHAIN
                else -> strategy
            }
            val unique = matchCnt == 1
            val conf = if (unique) effectiveStrategy.baseConfidence else (effectiveStrategy.baseConfidence - 0.35f).coerceAtLeast(0.30f)
            candidates.add(
                LocatorCandidate(
                    strategy = effectiveStrategy,
                    selector = prefix + rawLocalSel,
                    scope = scopeName,
                    confidence = conf,
                    isUnique = unique,
                    matchCount = matchCnt.coerceAtLeast(1),
                    isAmbiguous = !unique,
                    stability = stability
                )
            )
        }

        if (htmlId.isNotEmpty() && LocatorQualityEvaluator.isStableId(htmlId)) {
            val cnt = idCount[htmlId] ?: 1
            addCand(LocatorStrategy.STABLE_ID, "#${LocatorQualityEvaluator.escapeCssIdentifier(htmlId)}", cnt, LocatorStability.HIGH)
        }

        val dataTestAttr = listOf("data-testid", "data-test", "data-qa", "data-cy", "data-automation-id")
            .firstOrNull { !attrs[it].isNullOrBlank() }
        if (dataTestAttr != null) {
            val dtVal = attrs[dataTestAttr]!!
            val cnt = dataTestCount[dtVal] ?: 1
            addCand(
                LocatorStrategy.STABLE_DATA_ATTR,
                "$tag[$dataTestAttr=\"${LocatorQualityEvaluator.escapeCssAttributeValue(dtVal)}\"]",
                cnt,
                LocatorStability.HIGH
            )
        }

        val forAttr = if (tag == "label") attrs["for"]?.trim().orEmpty() else ""
        if (forAttr.isNotEmpty()) {
            val cnt = labelForCount[forAttr] ?: 1
            addCand(
                LocatorStrategy.LABEL_FOR,
                "label[for=\"${LocatorQualityEvaluator.escapeCssAttributeValue(forAttr)}\"]",
                cnt,
                LocatorStability.HIGH
            )
        }

        if (nameAttr.isNotEmpty()) {
            val cnt = nameCount["$tag|$nameAttr"] ?: 1
            addCand(
                LocatorStrategy.STABLE_NAME,
                "$tag[name=\"${LocatorQualityEvaluator.escapeCssAttributeValue(nameAttr)}\"]",
                cnt,
                LocatorStability.HIGH
            )
        }

        for (wAttr in WIDGET_DATA_ATTRS) {
            val wVal = attrs[wAttr]?.trim().orEmpty()
            if (wVal.isNotEmpty()) {
                val cnt = widgetAttrCount["$tag|$wAttr=$wVal"] ?: 1
                addCand(
                    LocatorStrategy.STABLE_DATA_ATTR,
                    "$tag[$wAttr=\"${LocatorQualityEvaluator.escapeCssAttributeValue(wVal)}\"]",
                    cnt,
                    LocatorStability.HIGH
                )
            }
        }

        if (htmlId.isNotEmpty() && explicitLabels.isNotEmpty() && LocatorQualityEvaluator.isStableId(htmlId)) {
            val cnt = idCount[htmlId] ?: 1
            addCand(
                LocatorStrategy.LABEL_FOR,
                "$tag#${LocatorQualityEvaluator.escapeCssIdentifier(htmlId)}",
                cnt,
                LocatorStability.HIGH
            )
        }

        if (ariaLabel.isNotEmpty()) {
            val cnt = roleAriaCount["$tag|$roleAttr|$ariaLabel"] ?: 1
            val sel = if (roleAttr.isNotEmpty()) {
                "$tag[role=\"${LocatorQualityEvaluator.escapeCssAttributeValue(roleAttr)}\"][aria-label=\"${LocatorQualityEvaluator.escapeCssAttributeValue(ariaLabel)}\"]"
            } else {
                "$tag[aria-label=\"${LocatorQualityEvaluator.escapeCssAttributeValue(ariaLabel)}\"]"
            }
            addCand(LocatorStrategy.ARIA_ROLE_AND_NAME, sel, cnt, LocatorStability.HIGH)
        }

        val formSel = node.formSelector
        val hrefAttr = attrs["href"]?.trim().orEmpty()
        if (formSel.isNotEmpty()) {
            if (htmlId.isNotEmpty() && LocatorQualityEvaluator.isStableId(htmlId)) {
                val cnt = formScopedCount["$formSel|id=$htmlId"] ?: 1
                addCand(
                    LocatorStrategy.FORM_RELATIVE,
                    "$formSel #${LocatorQualityEvaluator.escapeCssIdentifier(htmlId)}",
                    cnt,
                    LocatorStability.HIGH
                )
            }
            if (dataTestAttr != null) {
                val dtVal = attrs[dataTestAttr]!!
                val cnt = formScopedCount["$formSel|$tag|dt=$dtVal"] ?: 1
                addCand(
                    LocatorStrategy.FORM_RELATIVE,
                    "$formSel $tag[$dataTestAttr=\"${LocatorQualityEvaluator.escapeCssAttributeValue(dtVal)}\"]",
                    cnt,
                    LocatorStability.HIGH
                )
            }
            if (nameAttr.isNotEmpty()) {
                val cnt = formScopedCount["$formSel|$tag|name=$nameAttr"] ?: 1
                addCand(
                    LocatorStrategy.FORM_RELATIVE,
                    "$formSel $tag[name=\"${LocatorQualityEvaluator.escapeCssAttributeValue(nameAttr)}\"]",
                    cnt,
                    LocatorStability.HIGH
                )
            }
            if (ariaLabel.isNotEmpty()) {
                val cnt = formScopedCount["$formSel|$tag|aria=$ariaLabel"] ?: 1
                addCand(
                    LocatorStrategy.FORM_RELATIVE,
                    "$formSel $tag[aria-label=\"${LocatorQualityEvaluator.escapeCssAttributeValue(ariaLabel)}\"]",
                    cnt,
                    LocatorStability.HIGH
                )
            }
            if (tag == "a" && hrefAttr.isNotEmpty() && hrefAttr != "#") {
                val cnt = formScopedCount["$formSel|a|href=$hrefAttr"] ?: 1
                addCand(
                    LocatorStrategy.FORM_RELATIVE,
                    "$formSel a[href=\"${LocatorQualityEvaluator.escapeCssAttributeValue(hrefAttr)}\"]",
                    cnt,
                    LocatorStability.HIGH
                )
            }
            if (inputType.isNotEmpty()) {
                val cnt = formScopedCount["$formSel|$tag|type=$inputType"] ?: 1
                addCand(
                    LocatorStrategy.FORM_RELATIVE,
                    "$formSel $tag[type=\"${LocatorQualityEvaluator.escapeCssAttributeValue(inputType)}\"]",
                    cnt,
                    LocatorStability.MEDIUM
                )
            }
        }

        if (tag == "a" && hrefAttr.isNotEmpty() && hrefAttr != "#" && !hrefAttr.startsWith("javascript:", ignoreCase = true)) {
            val cnt = hrefCount[hrefAttr] ?: 1
            addCand(
                LocatorStrategy.HREF_ATTR,
                "a[href=\"${LocatorQualityEvaluator.escapeCssAttributeValue(hrefAttr)}\"]",
                cnt,
                LocatorStability.HIGH
            )
        }

        if (placeholder.isNotEmpty()) {
            val cnt = placeholderCount["$tag|$placeholder"] ?: 1
            addCand(
                LocatorStrategy.PLACEHOLDER_ATTR,
                "$tag[placeholder=\"${LocatorQualityEvaluator.escapeCssAttributeValue(placeholder)}\"]",
                cnt,
                LocatorStability.MEDIUM
            )
        }

        if (node.containerSelector.isNotEmpty()) {
            if (htmlId.isNotEmpty() && LocatorQualityEvaluator.isStableId(htmlId)) {
                val cnt = containerScopedCount["${node.containerSelector}|id=$htmlId"] ?: 1
                addCand(
                    LocatorStrategy.CONTAINER_SCOPED,
                    "${node.containerSelector} #${LocatorQualityEvaluator.escapeCssIdentifier(htmlId)}",
                    cnt,
                    LocatorStability.HIGH
                )
            }
            if (dataTestAttr != null) {
                val dtVal = attrs[dataTestAttr]!!
                val cnt = containerScopedCount["${node.containerSelector}|$tag|dt=$dtVal"] ?: 1
                addCand(
                    LocatorStrategy.CONTAINER_SCOPED,
                    "${node.containerSelector} $tag[$dataTestAttr=\"${LocatorQualityEvaluator.escapeCssAttributeValue(dtVal)}\"]",
                    cnt,
                    LocatorStability.HIGH
                )
            }
            if (ariaLabel.isNotEmpty()) {
                val cnt = containerScopedCount["${node.containerSelector}|$tag|aria=$ariaLabel"] ?: 1
                addCand(
                    LocatorStrategy.CONTAINER_SCOPED,
                    "${node.containerSelector} $tag[aria-label=\"${LocatorQualityEvaluator.escapeCssAttributeValue(ariaLabel)}\"]",
                    cnt,
                    LocatorStability.HIGH
                )
            }
            for (wAttr in WIDGET_DATA_ATTRS) {
                val wVal = attrs[wAttr]?.trim().orEmpty()
                if (wVal.isNotEmpty()) {
                    val cnt = containerScopedCount["${node.containerSelector}|$tag|$wAttr=$wVal"] ?: 1
                    addCand(
                        LocatorStrategy.CONTAINER_SCOPED,
                        "${node.containerSelector} $tag[$wAttr=\"${LocatorQualityEvaluator.escapeCssAttributeValue(wVal)}\"]",
                        cnt,
                        LocatorStability.HIGH
                    )
                }
            }
            if (forAttr.isNotEmpty()) {
                val cnt = containerScopedCount["${node.containerSelector}|$tag|for=$forAttr"] ?: 1
                addCand(
                    LocatorStrategy.CONTAINER_SCOPED,
                    "${node.containerSelector} label[for=\"${LocatorQualityEvaluator.escapeCssAttributeValue(forAttr)}\"]",
                    cnt,
                    LocatorStability.HIGH
                )
            }
            if (nameAttr.isNotEmpty()) {
                val cnt = containerScopedCount["${node.containerSelector}|$tag|name=$nameAttr"] ?: 1
                addCand(
                    LocatorStrategy.CONTAINER_SCOPED,
                    "${node.containerSelector} $tag[name=\"${LocatorQualityEvaluator.escapeCssAttributeValue(nameAttr)}\"]",
                    cnt,
                    LocatorStability.HIGH
                )
            }
            if (tag == "a" && hrefAttr.isNotEmpty() && hrefAttr != "#") {
                val key = "${node.containerSelector}|a|href=$hrefAttr"
                val cnt = containerScopedCount[key] ?: 1
                addCand(
                    LocatorStrategy.CONTAINER_SCOPED,
                    "${node.containerSelector} a[href=\"${LocatorQualityEvaluator.escapeCssAttributeValue(hrefAttr)}\"]",
                    cnt,
                    LocatorStability.HIGH
                )
            } else if (inputType.isNotEmpty()) {
                val key = "${node.containerSelector}|$tag|type=$inputType"
                val cnt = containerScopedCount[key] ?: 1
                addCand(
                    LocatorStrategy.CONTAINER_SCOPED,
                    "${node.containerSelector} $tag[type=\"${LocatorQualityEvaluator.escapeCssAttributeValue(inputType)}\"]",
                    cnt,
                    LocatorStability.MEDIUM
                )
            } else {
                val key = "${node.containerSelector}|$tag"
                val cnt = containerScopedCount[key] ?: 1
                if (cnt == 1) {
                    addCand(
                        LocatorStrategy.CONTAINER_SCOPED,
                        "${node.containerSelector} $tag",
                        1,
                        LocatorStability.MEDIUM
                    )
                }
            }
        }

        if (node.containerDescendantPath.isNotEmpty() && node.containerDescendantPath != node.containerSelector) {
            val dPath = node.containerDescendantPath
            when {
                htmlId.isNotEmpty() && LocatorQualityEvaluator.isStableId(htmlId) -> {
                    val cnt = summaryScopedCount["$dPath|id=$htmlId"] ?: 1
                    addCand(LocatorStrategy.CONTAINER_SCOPED, "$dPath #${LocatorQualityEvaluator.escapeCssIdentifier(htmlId)}", cnt, LocatorStability.HIGH)
                }
                dataTestAttr != null -> {
                    val dtVal = attrs[dataTestAttr]!!
                    val cnt = summaryScopedCount["$dPath|$tag|dt=$dtVal"] ?: 1
                    addCand(LocatorStrategy.CONTAINER_SCOPED, "$dPath $tag[$dataTestAttr=\"${LocatorQualityEvaluator.escapeCssAttributeValue(dtVal)}\"]", cnt, LocatorStability.HIGH)
                }
                nameAttr.isNotEmpty() -> {
                    val cnt = summaryScopedCount["$dPath|$tag|name=$nameAttr"] ?: 1
                    addCand(LocatorStrategy.CONTAINER_SCOPED, "$dPath $tag[name=\"${LocatorQualityEvaluator.escapeCssAttributeValue(nameAttr)}\"]", cnt, LocatorStability.HIGH)
                }
                ariaLabel.isNotEmpty() -> {
                    val cnt = summaryScopedCount["$dPath|$tag|aria=$ariaLabel"] ?: 1
                    addCand(LocatorStrategy.CONTAINER_SCOPED, "$dPath $tag[aria-label=\"${LocatorQualityEvaluator.escapeCssAttributeValue(ariaLabel)}\"]", cnt, LocatorStability.HIGH)
                }
                tag == "a" && hrefAttr.isNotEmpty() && hrefAttr != "#" -> {
                    val cnt = summaryScopedCount["$dPath|a|href=$hrefAttr"] ?: 1
                    addCand(LocatorStrategy.CONTAINER_SCOPED, "$dPath a[href=\"${LocatorQualityEvaluator.escapeCssAttributeValue(hrefAttr)}\"]", cnt, LocatorStability.HIGH)
                }
                inputType.isNotEmpty() -> {
                    val cnt = summaryScopedCount["$dPath|$tag|type=$inputType"] ?: 1
                    addCand(LocatorStrategy.CONTAINER_SCOPED, "$dPath $tag[type=\"${LocatorQualityEvaluator.escapeCssAttributeValue(inputType)}\"]", cnt, LocatorStability.MEDIUM)
                }
                else -> {
                    val cnt = summaryScopedCount["$dPath|$tag"] ?: 1
                    addCand(LocatorStrategy.CONTAINER_SCOPED, "$dPath $tag", cnt, LocatorStability.MEDIUM)
                }
            }
        }

        if (candidates.isEmpty()) {
            val cnt = tagCount[tag] ?: 1
            val fallbackSel = if (inputType.isNotEmpty()) {
                "$tag[type=\"${LocatorQualityEvaluator.escapeCssAttributeValue(inputType)}\"]"
            } else if (roleAttr.isNotEmpty()) {
                "$tag[role=\"${LocatorQualityEvaluator.escapeCssAttributeValue(roleAttr)}\"]"
            } else {
                tag
            }
            addCand(LocatorStrategy.SCOPED_CSS, fallbackSel, cnt, if (cnt == 1) LocatorStability.MEDIUM else LocatorStability.LOW)
        }

        val sortedCandidates = candidates.sortedWith(
            compareByDescending<LocatorCandidate> { it.isUnique }.thenByDescending { it.confidence }
        )
        val best = sortedCandidates.first()

        val ariaJson = JSONObject()
        attrs.forEach { (k, v) ->
            if (k == "role" || k.startsWith("aria-")) {
                ariaJson.put(k, v)
            }
        }

        val sensitiveCat = SensitiveFieldClassifier.classify(
            tagName = tag,
            inputType = inputType,
            autocomplete = autocomplete,
            name = nameAttr,
            htmlId = htmlId,
            label = associatedLabel,
            accessibleName = accessibleName,
            placeholder = placeholder
        )
        val rawValue = attrs["value"].orEmpty()
        val safeValue = if (sensitiveCat.isSensitive || inputType == "password") "" else rawValue.take(64)

        val safeAttrsJson = JSONObject()
        val blockedAttrKeys = setOf("value", "password", "secret", "token", "cvv", "cvc", "otp", "ssn", "style")
        val allowedRelAttrs = setOf(
            "type", "role", "name", "id", "href", "placeholder", "autocomplete", "inputmode", "title", "for",
            "min", "max", "step", "pattern", "maxlength", "minlength",
            "aria-label", "aria-labelledby", "aria-describedby", "aria-haspopup", "aria-expanded", "aria-controls",
            "aria-pressed", "aria-selected", "aria-checked", "aria-current",
            "data-testid", "data-test", "data-qa", "data-cy", "data-action",
            "data-datepicker", "data-timepicker", "data-calendar", "data-year-picker", "data-birth-year",
            "data-date", "data-selected-date", "data-selected-year",
            "data-day", "data-month", "data-year", "data-format", "data-picker-role",
            "data-dial-code", "data-country-code", "data-country", "data-code",
            "data-gender", "data-sex", "data-option", "data-value"
        )
        attrs.forEach { (k, v) ->
            val lk = k.lowercase()
            if (lk in blockedAttrKeys || lk.contains("password") || lk.contains("secret") || lk.contains("token")) return@forEach
            if (lk == "id" && !LocatorQualityEvaluator.isStableId(v)) return@forEach
            if (lk in allowedRelAttrs) {
                if (v.isNotBlank()) safeAttrsJson.put(k, v.take(120))
            }
        }

        val styleAttr = attrs["style"]?.lowercase().orEmpty()
        val isHiddenStyle = styleAttr.contains("display:none") ||
            styleAttr.contains("display: none") ||
            styleAttr.contains("visibility:hidden") ||
            styleAttr.contains("visibility: hidden") ||
            Regex("opacity\\s*:\\s*0(?:\\.0+)?\\s*(?:;|$)").containsMatchIn(styleAttr)
        val isHiddenAttr = attrs.containsKey("hidden") || attrs.containsKey("inert") ||
            attrs["aria-hidden"]?.equals("true", ignoreCase = true) == true || inputType == "hidden"
        val visible = !isHiddenAttr && !isHiddenStyle && !node.ancestorHidden
        val isOffScreen = attrs["data-offscreen"] == "true" ||
            styleAttr.contains("margin-top: 2") ||
            styleAttr.contains("top: 2")
        val inViewport = visible && !isOffScreen

        val disabled = attrs.containsKey("disabled") ||
            (hiddenToggle?.disabled == true) ||
            attrs["aria-disabled"]?.equals("true", ignoreCase = true) == true
        val readonly = attrs.containsKey("readonly")
        val required = attrs.containsKey("required") || attrs["aria-required"]?.equals("true", ignoreCase = true) == true

        val autoCompLower = autocomplete.lowercase()
        val hintStr = "$accessibleName $associatedLabel $nameAttr $htmlId $placeholder $autoCompLower ${node.fieldsetLegend} ${node.nearbyText} $describedByText".lowercase()
        val directHintStr = "$accessibleName $associatedLabel $nameAttr $htmlId $placeholder $autoCompLower".lowercase()
        val isUnrelatedNumeric = Regex("\\b(zip|postal|postcode|phone|tel|mobile|otp|verification|passcode|pin|cvv|cvc|card|cc-|ssn|tax|routing|account[-_ ]?number|price|amount|salary|income|quantity|qty|ext|extension|street|apt|suite|floor|graduation|grad[-_ ]?year|class[-_ ]?of|vehicle|car[-_ ]?year|model[-_ ]?year|expir|cc-exp)\\b").containsMatchIn(directHintStr)
        val hasDobContext = Regex("(birth|bday|dob|date\\s*of\\s*birth|born|confirm\\s+your\\s+age|verify\\s+your\\s+age|age\\s+verification)").containsMatchIn(hintStr)
        val isExplicitBirthYearCue = !isUnrelatedNumeric && (
            autoCompLower == "bday-year" ||
                attrs.containsKey("data-birth-year") ||
                Regex("(birth\\s*year|year\\s*of\\s*birth|bday[-_]?year|birth[-_]?year|dob[-_]?year|born\\s*year)").containsMatchIn(hintStr) ||
                (hasDobContext &&
                    !Regex("\\b(month|day)\\b").containsMatchIn(directHintStr) &&
                    (Regex("\\byear\\b").containsMatchIn(directHintStr) ||
                        Regex("^(yyyy|19\\d{2}|20\\d{2})$", RegexOption.IGNORE_CASE).matches(placeholder.trim()) ||
                        (attrs["maxlength"] == "4" && (inputType in setOf("number", "tel", "text") || attrs["inputmode"] in setOf("numeric", "decimal")))))
            )
        val isExplicitBirthMonthCue = !isUnrelatedNumeric && (
            autoCompLower == "bday-month" ||
                Regex("(birth\\s*month|month\\s*of\\s*birth|bday[-_]?month|birth[-_]?month|dob[-_]?month)").containsMatchIn(hintStr) ||
                (hasDobContext && Regex("\\bmonth\\b").containsMatchIn(directHintStr) && !Regex("\\b(year|day)\\b").containsMatchIn(directHintStr))
            )
        val isExplicitBirthDayCue = !isUnrelatedNumeric && (
            autoCompLower == "bday-day" ||
                Regex("(birth\\s*day|day\\s*of\\s*birth|bday[-_]?day|birth[-_]?day|dob[-_]?day)").containsMatchIn(hintStr) ||
                (hasDobContext && Regex("\\bday\\b").containsMatchIn(directHintStr) && !Regex("\\b(year|month)\\b").containsMatchIn(directHintStr))
            )
        val hasSelectYearOptions = if (tag == "select" && !isUnrelatedNumeric) {
            val optMatches = Regex("<option\\b([^>]*)>(.*?)</option>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(node.innerHtml)
                .toList()
            val yearCount = optMatches.count { om ->
                val oAttrs = parseAttributes(om.groupValues[1])
                val oVal = oAttrs["value"]?.trim().orEmpty()
                val oTxt = stripTagsAndNormalize(om.groupValues[2])
                Regex("^(19\\d{2}|20\\d{2})$").matches(oVal) || Regex("^(19\\d{2}|20\\d{2})$").matches(oTxt)
            }
            yearCount >= 2 && (hasDobContext || Regex("\\byear\\b").containsMatchIn(directHintStr))
        } else false

        val isCalendarDayCell = role == "gridcell" ||
            attrs.containsKey("data-date") ||
            attrs.containsKey("data-day") ||
            attrs["aria-current"]?.equals("date", ignoreCase = true) == true ||
            (node.inCalendarContainer && tag in setOf("td", "button", "span", "div") && Regex("^\\d{1,2}$").matches(node.textContent))
        val isCalendarYearOption = attrs.containsKey("data-year") ||
            (Regex("^(19\\d{2}|20\\d{2})$").matches(node.textContent) &&
                (node.inCalendarContainer || role in setOf("option", "gridcell", "button", "menuitemradio") ||
                    Regex("(year-option|year-item|year-cell)").containsMatchIn(attrs["class"]?.lowercase().orEmpty())))
        val isCalendarNav = node.inCalendarContainer && (tag == "button" || role == "button") && !isCalendarDayCell && !isCalendarYearOption &&
            Regex("(prev|previous|next|forward|back|month|year)").containsMatchIn(hintStr)
        val hasDateTriggerCue = attrs.containsKey("data-datepicker") ||
            attrs.containsKey("data-timepicker") ||
            attrs.containsKey("data-calendar") ||
            attrs.containsKey("data-year-picker") ||
            attrs.containsKey("data-birth-year") ||
            autoCompLower.startsWith("bday") ||
            ((readonly || role in setOf("combobox", "listbox", "button") || attrs["aria-haspopup"]?.lowercase() in setOf("dialog", "grid", "listbox", "menu", "true")) &&
                !isUnrelatedNumeric &&
                Regex("(date|birth|dob|calendar|yyyy|mm/dd|dd/mm|year\\s*of\\s*birth|birth\\s*year)").containsMatchIn(hintStr))

        val dateTimeMeta: JSONObject? = when {
            inputType in setOf("date", "datetime-local", "time", "month", "week") -> DateTimeMetadata(
                controlType = inputType,
                inputFormat = when (inputType) {
                    "date" -> "YYYY-MM-DD"
                    "datetime-local" -> "YYYY-MM-DDThh:mm"
                    "time" -> "HH:mm"
                    "month" -> "YYYY-MM"
                    "week" -> "YYYY-Www"
                    else -> inputType
                },
                min = attrs["min"].orEmpty(),
                max = attrs["max"].orEmpty(),
                step = attrs["step"].orEmpty(),
                isCustomPicker = false
            ).toJson()
            isExplicitBirthYearCue || isExplicitBirthMonthCue || isExplicitBirthDayCue || hasSelectYearOptions -> {
                val partKind = when {
                    isExplicitBirthMonthCue -> "month"
                    isExplicitBirthDayCue -> "day"
                    else -> "year"
                }
                val isCustomDropdownOrTrigger = tag != "input" && tag != "select" ||
                    role in setOf("combobox", "listbox", "button") ||
                    attrs.containsKey("aria-haspopup") ||
                    attrs.containsKey("data-datepicker") ||
                    attrs.containsKey("data-year-picker")
                val pickerRoleVal = when {
                    tag == "select" -> "$partKind-select"
                    isCustomDropdownOrTrigger -> "$partKind-dropdown"
                    else -> "$partKind-input"
                }
                DateTimeMetadata(
                    controlType = when {
                        tag == "select" -> "date-select"
                        isCustomDropdownOrTrigger -> "custom-datepicker"
                        else -> "date-part"
                    },
                    inputFormat = partKind,
                    min = attrs["min"] ?: attrs["data-min"] ?: "",
                    max = attrs["max"] ?: attrs["data-max"] ?: "",
                    step = attrs["step"].orEmpty(),
                    isCustomPicker = tag != "input" || isCustomDropdownOrTrigger,
                    pickerRole = pickerRoleVal,
                    selectedDate = (attrs["data-year"] ?: attrs["data-selected-year"] ?: safeValue).take(32)
                ).toJson()
            }
            hasDateTriggerCue || role == "grid" || isCalendarDayCell || isCalendarYearOption || isCalendarNav -> {
                val selDate = attrs["data-date"]
                    ?: attrs["data-selected-date"]
                    ?: attrs["data-year"]
                    ?: attrs["data-selected-year"]
                    ?: if (attrs["aria-selected"].equals("true", ignoreCase = true) || attrs["aria-current"].equals("date", ignoreCase = true)) {
                        attrs["data-day"] ?: node.textContent
                    } else ""
                val inferredRole = attrs["data-picker-role"] ?: when {
                    role == "grid" -> "calendar-grid"
                    isCalendarYearOption -> "calendar-year"
                    isCalendarDayCell -> "calendar-day"
                    isCalendarNav -> if (hintStr.contains("year")) "year-nav" else "month-nav"
                    tag == "select" -> if (hintStr.contains("month")) "month-select" else "year-select"
                    else -> "trigger"
                }
                val inferredFormat = attrs["data-format"]
                    ?: Regex("(yyyy[-/]mm[-/]dd|dd[-/]mm[-/]yyyy|mm[-/]dd[-/]yyyy|yyyy)", RegexOption.IGNORE_CASE)
                        .find(placeholder)?.value?.uppercase()
                    ?: if (isCalendarYearOption) "YYYY" else "YYYY-MM-DD"
                DateTimeMetadata(
                    controlType = if (attrs.containsKey("data-timepicker")) "custom-timepicker" else "custom-datepicker",
                    inputFormat = inferredFormat,
                    min = attrs["min"] ?: attrs["data-min"] ?: "",
                    max = attrs["max"] ?: attrs["data-max"] ?: "",
                    step = attrs["step"].orEmpty(),
                    isCustomPicker = true,
                    pickerRole = inferredRole,
                    selectedDate = selDate.take(32)
                ).toJson()
            }
            else -> null
        }

        val checked: Boolean? = when {
            tag == "input" && (inputType == "checkbox" || inputType == "radio") -> attrs.containsKey("checked")
            hiddenToggle != null -> hiddenToggle.checked
            attrs.containsKey("aria-checked") -> attrs["aria-checked"].equals("true", ignoreCase = true)
            attrs.containsKey("aria-pressed") -> attrs["aria-pressed"].equals("true", ignoreCase = true)
            else -> null
        }
        val selected: Boolean? = when {
            tag == "option" -> attrs.containsKey("selected")
            attrs.containsKey("aria-selected") -> attrs["aria-selected"].equals("true", ignoreCase = true)
            else -> null
        }
        val radioGroupName = when {
            inputType == "radio" -> nameAttr.ifBlank { node.fieldsetLegend }
            hiddenToggle?.inputType == "radio" -> hiddenToggle.name.ifBlank { node.fieldsetLegend }
            role == "radio" -> nameAttr.ifBlank { node.fieldsetLegend }
            else -> ""
        }

        return JSONObject().apply {
            put("id", "el_$index")
            put("documentId", documentId)
            put("tag", tag)
            put("role", role)
            put("label", associatedLabel)
            put("accessibleName", accessibleName)
            put("visibleText", node.textContent.take(120))
            put("href", hrefAttr.take(180))
            put("parentContainerSummary", node.containerSummary.take(120))
            put("nearbyText", node.nearbyText.take(120))
            put("relevantAttributes", safeAttrsJson)
            put("selector", best.selector)
            put("locatorStrategy", best.strategy.id)
            put("locatorConfidence", best.confidence.toDouble())
            put("isLocatorAmbiguous", best.isAmbiguous)
            put("locatorCandidates", JSONArray().also { arr -> sortedCandidates.forEach { arr.put(it.toJson()) } })
            put("inputType", inputType)
            put("placeholder", placeholder)
            put("name", nameAttr)
            put("htmlId", htmlId)
            put("safeValue", safeValue)
            put("sensitiveCategory", sensitiveCat.id)
            if (checked != null) put("checked", checked)
            if (selected != null) put("selected", selected)
            put("required", required)
            put("disabled", disabled)
            put("readonly", readonly)
            put("visible", visible)
            put("inViewport", inViewport)
            put("interactable", visible && !disabled && inputType != "hidden")
            put("contentEditable", attrs["contenteditable"].orEmpty())
            put("autocomplete", autocomplete)
            put("ariaAttributes", ariaJson)
            put("controlledElementId", attrs["aria-controls"].orEmpty())
            put("formGroupId", node.formId)
            put("formName", node.formName)
            put("fieldsetLegend", node.fieldsetLegend)
            put("radioGroupName", radioGroupName)
            if (dateTimeMeta != null) put("dateTimeMetadata", dateTimeMeta)
            put("framePath", JSONArray(node.framePath))
            put("frameUrl", node.frameUrl)
            put("frameOrigin", node.frameOrigin)
            put("shadowHostPath", JSONArray(node.shadowHostPath))
            if (visible) {
                put(
                    "boundingBox",
                    JSONObject().apply {
                        put("left", 24.0)
                        put("top", (56.0 + (index % 12) * 58.0))
                        put("width", 340.0)
                        put("height", 44.0)
                        put("viewportWidth", 412.0)
                        put("viewportHeight", 915.0)
                    }
                )
            }
        }
    }

    private fun parseAttributes(rawAttrs: String): Map<String, String> {
        if (rawAttrs.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        val attrRegex = Regex("""([a-zA-Z0-9_:-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""")
        attrRegex.findAll(rawAttrs).forEach { m ->
            val key = m.groupValues[1].lowercase()
            val hasEquals = m.value.contains("=")
            val value = when {
                !hasEquals -> ""
                m.groups[2] != null -> m.groupValues[2]
                m.groups[3] != null -> m.groupValues[3]
                else -> m.groupValues[4]
            }
            map[key] = decodeHtmlEntities(value)
        }
        return map
    }

    private fun stripTagsAndNormalize(rawHtml: String): String {
        return decodeHtmlEntities(
            rawHtml
                .replace(Regex("<script\\b[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                .replace(Regex("<style\\b[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                .replace(Regex("<[^>]+>"), " ")
        ).replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        return input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
    }
}

package com.example.automation

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.example.data.local.AutomationWorkflowEntity
import java.lang.ref.WeakReference
import java.net.URI
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class StepExecutionResult(
    val success: Boolean,
    val message: String,
    val errorCode: AutomationErrorCode = if (success) AutomationErrorCode.NONE else AutomationErrorCode.JS_EXCEPTION,
    val resolvedSelector: String = "",
    val recoveredViaFallback: Boolean = false,
    val verified: Boolean = success,
    val extractedValue: String = "",
    val extractedItems: List<String> = emptyList(),
    val ambiguousCandidates: List<AmbiguousCandidateInfo> = emptyList(),
    val locatorDiagnostics: LocatorResolutionDiagnostics? = null,
    val targetBoundingBox: ElementBoundingBox? = null
)

data class WorkflowExecutionOutcome(
    val status: String, // COMPLETED, FAILED, CANCELLED, INTERRUPTED
    val completedSteps: Int,
    val totalSteps: Int,
    val verificationSummary: String,
    val errorCode: AutomationErrorCode = if (status == "COMPLETED") AutomationErrorCode.NONE else AutomationErrorCode.JS_EXCEPTION,
    val durationMs: Long = 0L,
    val stepRecords: List<StepExecutionRecord> = emptyList(),
    val recoveredStepsCount: Int = 0,
    val branchTraces: List<BranchTraceRecord> = emptyList(),
    val recoveryAttempts: List<RecoveryAttemptRecord> = emptyList(),
    val variableSnapshots: List<VariableDisplayBadge> = emptyList(),
    val lastCheckpoint: PersistedExecutionCheckpoint? = null,
    val ambiguousCandidates: List<AmbiguousCandidateInfo> = emptyList(),
    val lastLocatorDiagnostics: LocatorResolutionDiagnostics? = null
)

sealed class JsEvalResult {
    data class Success(val rawValue: String) : JsEvalResult()
    data class Timeout(val timeoutMs: Long) : JsEvalResult()
    data class Unavailable(val reason: String) : JsEvalResult()
    data class Failure(val reason: String) : JsEvalResult()
}

data class SelectorValidationResult(
    val isValid: Boolean,
    val reason: String = ""
)

class AutomationEngine(
    private val jsEvalTimeoutMs: Long = DEFAULT_JS_EVAL_TIMEOUT_MS,
    private val navTimeoutMs: Long = DEFAULT_NAV_TIMEOUT_MS,
    private val domReadyTimeoutMs: Long = DEFAULT_DOM_READY_TIMEOUT_MS,
    private val domStabilityPollMs: Long = DEFAULT_DOM_POLL_MS
) {

    companion object {
        const val DEFAULT_JS_EVAL_TIMEOUT_MS = 5_000L
        const val DEFAULT_NAV_TIMEOUT_MS = 15_000L
        const val DEFAULT_DOM_READY_TIMEOUT_MS = 3_500L
        const val DEFAULT_DOM_POLL_MS = 60L

        private const val JS_TARGET_HELPERS = """
            function apexCssEsc(v) {
                if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(String(v));
                return String(v).replace(/([ !"#$%&'()*+,.\/:;<=>?@\[\\\]^`{|}~])/g, '\\$1');
            }
            function apexEscAttr(v) {
                return String(v).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\r|\n/g, ' ');
            }
            function apexNormalize(str) {
                return String(str || '').replace(/\s+/g, ' ').trim().toLowerCase();
            }
            function apexStripRoleSuffix(hint) {
                return String(hint || '').replace(/\s+(?:field|checkbox|option|button)$/i, '').trim();
            }
            function apexResolveFieldGroupLabel(el) {
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
                    if (forAttr && (!el.id || forAttr !== el.id) && document.getElementById(forAttr)) return '';
                    if (node.querySelector && node.querySelector('input:not([type="hidden"]), select, textarea, button, a[href]')) return '';
                    const nCls = String(node.className || '').toLowerCase();
                    const isExplicit = nTag === 'label' || nTag === 'legend' || /(?:^|[\s_-])(label|field-label|form-label|control-label|input-label)(?:$|[\s_-])/.test(nCls);
                    if (requireExplicit && !isExplicit && ((nTag !== 'span' && nTag !== 'div') || nCls.indexOf('label') < 0)) return '';
                    const raw = apexNormalize(node.innerText || node.textContent);
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
                        if (cTag === 'form' || cTag === 'body' || cTag === 'html' || cTag === 'main' || cTag === 'table' || cTag === 'fieldset') break;
                        const ctrls = cur.querySelectorAll
                            ? cur.querySelectorAll('input:not([type="hidden"]):not([type="submit"]):not([type="button"]), select, textarea, [role="combobox"], [role="listbox"], [role="spinbutton"]')
                            : [];
                        if (ctrls.length > 1) break;
                        const curPrev = cur.previousElementSibling;
                        if (curPrev) {
                            const parentCtrls = cur.parentElement && cur.parentElement.querySelectorAll
                                ? cur.parentElement.querySelectorAll('input:not([type="hidden"]):not([type="submit"]):not([type="button"]), select, textarea, [role="combobox"], [role="listbox"], [role="spinbutton"]')
                                : [];
                            const cpTxt = extractLabelCandidate(curPrev, parentCtrls.length > 1);
                            if (cpTxt) return cpTxt;
                        }
                        if (ctrls.length <= 1 && cur.querySelector) {
                            const innerLbl = cur.querySelector('label, [class*="label"], [class*="Label"]');
                            if (innerLbl) {
                                const ilTxt = extractLabelCandidate(innerLbl, false);
                                if (ilTxt) return ilTxt;
                            }
                        }
                        cur = cur.parentElement;
                        depth++;
                    }
                } catch (_) {}
                return '';
            }
            function apexGetLabel(el) {
                if (!el) return '';
                try {
                    const aria = apexNormalize(el.getAttribute && el.getAttribute('aria-label'));
                    if (aria) return aria;
                    if (el.id) {
                        const lbl = document.querySelector('label[for="' + apexCssEsc(el.id) + '"]');
                        if (lbl && (lbl.innerText || lbl.textContent)) {
                            const txt = apexNormalize(lbl.innerText || lbl.textContent);
                            if (txt) return txt;
                        }
                    }
                    const parentLbl = el.closest ? el.closest('label') : null;
                    if (parentLbl && (parentLbl.innerText || parentLbl.textContent)) {
                        const txt = apexNormalize(parentLbl.innerText || parentLbl.textContent);
                        if (txt) return txt;
                    }
                    const fieldGroupLbl = apexResolveFieldGroupLabel(el);
                    if (fieldGroupLbl) return fieldGroupLbl;
                    const ph = apexNormalize(el.getAttribute && el.getAttribute('placeholder'));
                    if (ph) return ph;
                    const title = apexNormalize(el.getAttribute && el.getAttribute('title'));
                    if (title) return title;
                    const tag = (el.tagName || '').toLowerCase();
                    const type = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
                    const inner = (tag === 'input' || tag === 'textarea')
                        ? ((type === 'submit' || type === 'button' || type === 'reset') ? apexNormalize(el.value) : '')
                        : apexNormalize(el.innerText || el.textContent);
                    if (inner) return inner;
                    const name = apexNormalize(el.getAttribute && el.getAttribute('name'));
                    if (name) return name;
                } catch (_) {}
                return '';
            }
            function apexIsElementDisabled(el) {
                if (!el) return true;
                if (el.disabled === true) return true;
                const ariaDisabled = (el.getAttribute && el.getAttribute('aria-disabled')) || '';
                return ariaDisabled.toLowerCase() === 'true';
            }
            function apexElementMatchesHint(el, rawHint) {
                const normHint = apexNormalize(rawHint);
                if (!normHint) return { matched: true, exact: false };
                const strippedHint = apexStripRoleSuffix(normHint);
                const elLabel = apexGetLabel(el);
                if (elLabel === normHint || (strippedHint && elLabel === strippedHint)) {
                    return { matched: true, exact: true };
                }
                const tag = (el.tagName || '').toLowerCase();
                const type = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
                const valueText = (tag === 'input' && (type === 'submit' || type === 'button' || type === 'reset'))
                    ? (el.value || '')
                    : '';
                const bag = apexNormalize([
                    elLabel,
                    el.getAttribute && el.getAttribute('name'),
                    el.getAttribute && el.getAttribute('placeholder'),
                    el.getAttribute && el.getAttribute('aria-label'),
                    el.getAttribute && el.getAttribute('title'),
                    (tag !== 'input' && tag !== 'textarea') ? (el.innerText || el.textContent || '') : '',
                    valueText
                ].filter(Boolean).join(' '));
                if (bag.includes(normHint) || (strippedHint && bag.includes(strippedHint))) {
                    return { matched: true, exact: false };
                }
                return { matched: false, exact: false };
            }
            function apexQueryChainInScope(scopes, segment, isShadowHop, isIframeHop) {
                const nextScopes = [];
                const cleanSeg = String(segment || '').trim();
                if (!cleanSeg) return { ok: false, nodes: [], error: 'Empty selector segment' };
                for (let s = 0; s < scopes.length; s++) {
                    const scope = scopes[s];
                    if (!scope || typeof scope.querySelectorAll !== 'function') continue;
                    let matched = [];
                    try {
                        matched = Array.from(scope.querySelectorAll(cleanSeg));
                    } catch (e) {
                        return { ok: false, nodes: [], error: (e && e.message ? e.message : String(e)) };
                    }
                    for (let m = 0; m < matched.length; m++) {
                        const node = matched[m];
                        if (isShadowHop) {
                            if (node && node.shadowRoot) nextScopes.push(node.shadowRoot);
                        } else if (isIframeHop) {
                            try {
                                const doc = node && (node.contentDocument || (node.contentWindow && node.contentWindow.document));
                                if (doc) nextScopes.push(doc);
                            } catch (_) {}
                        } else {
                            nextScopes.push(node);
                        }
                    }
                }
                return { ok: true, nodes: nextScopes, error: '' };
            }
            function apexQuerySelectorAllUniversal(rawSel) {
                const trimmed = String(rawSel || '').trim();
                if (!trimmed) return { ok: true, nodes: [], error: '' };
                if (trimmed.indexOf('>>') === -1) {
                    try {
                        return { ok: true, nodes: Array.from(document.querySelectorAll(trimmed)), error: '' };
                    } catch (e) {
                        return { ok: false, nodes: [], error: (e && e.message ? e.message : String(e)) };
                    }
                }
                const tokens = [];
                let rem = trimmed;
                while (rem.length > 0) {
                    const idxShadow = rem.indexOf('>>>');
                    const idxFrame = rem.indexOf('>>');
                    if (idxShadow !== -1 && (idxFrame === -1 || idxShadow <= idxFrame)) {
                        tokens.push({ seg: rem.slice(0, idxShadow).trim(), hop: 'shadow' });
                        rem = rem.slice(idxShadow + 3);
                    } else if (idxFrame !== -1) {
                        tokens.push({ seg: rem.slice(0, idxFrame).trim(), hop: 'iframe' });
                        rem = rem.slice(idxFrame + 2);
                    } else {
                        tokens.push({ seg: rem.trim(), hop: 'none' });
                        rem = '';
                    }
                }
                let currentScopes = [document];
                for (let i = 0; i < tokens.length; i++) {
                    const tok = tokens[i];
                    const res = apexQueryChainInScope(
                        currentScopes,
                        tok.seg,
                        tok.hop === 'shadow',
                        tok.hop === 'iframe'
                    );
                    if (!res.ok) return res;
                    currentScopes = res.nodes;
                    if (currentScopes.length === 0) break;
                }
                return { ok: true, nodes: currentScopes, error: '' };
            }
            function apexResolveTarget(sel, labelHint, defaultQuery) {
                const trimmedSel = String(sel || '').trim();
                const trimmedHint = apexNormalize(labelHint);
                let candidates = [];
                if (trimmedSel) {
                    const queryRes = apexQuerySelectorAllUniversal(trimmedSel);
                    if (!queryRes.ok) {
                        return {
                            ok: false,
                            code: 'INVALID_SELECTOR',
                            msg: 'Invalid CSS selector "' + trimmedSel + '": ' + queryRes.error
                        };
                    }
                    candidates = queryRes.nodes;
                    if (candidates.length === 0) {
                        return {
                            ok: false,
                            code: 'ELEMENT_NOT_FOUND',
                            msg: 'No element matched selector "' + trimmedSel + '"'
                        };
                    }
                    if (candidates.length === 1) {
                        const only = candidates[0];
                        if (apexIsElementDisabled(only)) {
                            return {
                                ok: false,
                                code: 'ELEMENT_DISABLED',
                                msg: 'Target element is disabled for "' + (labelHint || trimmedSel) + '"'
                            };
                        }
                        return { ok: true, el: only };
                    }
                    if (!trimmedHint) {
                        return {
                            ok: false,
                            code: 'ELEMENT_AMBIGUOUS',
                            msg: 'Ambiguous selector "' + trimmedSel + '" matched ' + candidates.length + ' elements'
                        };
                    }
                } else {
                    if (!trimmedHint) {
                        return {
                            ok: false,
                            code: 'INVALID_SELECTOR',
                            msg: 'Step has neither CSS selector nor target label'
                        };
                    }
                    try {
                        candidates = Array.from(document.querySelectorAll(defaultQuery));
                    } catch (e) {
                        return {
                            ok: false,
                            code: 'INVALID_SELECTOR',
                            msg: 'Invalid fallback query: ' + (e && e.message ? e.message : String(e))
                        };
                    }
                    if (candidates.length === 0) {
                        return {
                            ok: false,
                            code: 'ELEMENT_NOT_FOUND',
                            msg: 'No matching elements found on page for "' + labelHint + '"'
                        };
                    }
                }
                const exactMatches = [];
                const partialMatches = [];
                for (let i = 0; i < candidates.length; i++) {
                    const el = candidates[i];
                    const match = apexElementMatchesHint(el, trimmedHint);
                    if (match.matched) {
                        if (match.exact) exactMatches.push(el);
                        else partialMatches.push(el);
                    }
                }
                const chosen = exactMatches.length > 0 ? exactMatches : partialMatches;
                if (chosen.length === 0) {
                    return {
                        ok: false,
                        code: 'ELEMENT_NOT_FOUND',
                        msg: 'Target element not found for "' + (labelHint || trimmedSel) + '"'
                    };
                }
                if (chosen.length > 1) {
                    return {
                        ok: false,
                        code: 'ELEMENT_AMBIGUOUS',
                        msg: 'Ambiguous target: ' + chosen.length + ' elements match "' + (labelHint || trimmedSel) + '"'
                    };
                }
                const target = chosen[0];
                if (apexIsElementDisabled(target)) {
                    return {
                        ok: false,
                        code: 'ELEMENT_DISABLED',
                        msg: 'Target element is disabled for "' + (labelHint || trimmedSel) + '"'
                    };
                }
                return { ok: true, el: target };
            }
        """
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    val universalScanner: UniversalDomScanner = UniversalDomScanner(this)

    fun extractDomain(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank() || trimmed.equals("apex://home", ignoreCase = true)) {
            return "Start Page"
        }
        if (trimmed.equals("apex://news", ignoreCase = true)) return "news.apex.browser"
        if (trimmed.equals("apex://store", ignoreCase = true)) return "store.apex.browser"
        return runCatching {
            URI(trimmed).host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')
            .substringBefore('?')
            .ifBlank { "Web Page" }
    }

    fun validateSelector(rawSelector: String): SelectorValidationResult {
        val trimmed = rawSelector.trim()
        if (trimmed.isEmpty()) {
            return SelectorValidationResult(isValid = false, reason = "Selector is empty")
        }
        if (trimmed.contains(">>>") || trimmed.contains(">>")) {
            val segments = trimmed.split(Regex(">>>|>>")).map { it.trim() }
            if (segments.any { it.isEmpty() }) {
                return SelectorValidationResult(isValid = false, reason = "Empty segment in Shadow DOM or iframe selector chain")
            }
            for (seg in segments) {
                val segResult = validateSingleCssSelectorSegment(seg)
                if (!segResult.isValid) return segResult
            }
            return SelectorValidationResult(isValid = true)
        }
        return validateSingleCssSelectorSegment(trimmed)
    }

    private fun validateSingleCssSelectorSegment(trimmed: String): SelectorValidationResult {
        if (trimmed.any { it.code < 32 }) {
            return SelectorValidationResult(isValid = false, reason = "Selector contains invalid control characters")
        }
        if (trimmed.startsWith(">") || trimmed.startsWith("+") || trimmed.startsWith("~") || trimmed.startsWith(",") ||
            trimmed.endsWith(">") || trimmed.endsWith("+") || trimmed.endsWith("~") || trimmed.endsWith(",")
        ) {
            return SelectorValidationResult(isValid = false, reason = "Selector has leading or trailing combinator")
        }
        var quote: Char? = null
        var bracketDepth = 0
        var parenDepth = 0
        var escaped = false
        for (i in trimmed.indices) {
            val ch = trimmed[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (quote != null) {
                if (ch == quote) quote = null
                continue
            }
            when (ch) {
                '\'', '"' -> quote = ch
                '[' -> bracketDepth++
                ']' -> {
                    bracketDepth--
                    if (bracketDepth < 0) {
                        return SelectorValidationResult(isValid = false, reason = "Unmatched ']' in selector")
                    }
                }
                '(' -> parenDepth++
                ')' -> {
                    parenDepth--
                    if (parenDepth < 0) {
                        return SelectorValidationResult(isValid = false, reason = "Unmatched ')' in selector")
                    }
                }
            }
        }
        if (escaped) {
            return SelectorValidationResult(isValid = false, reason = "Trailing backslash in selector")
        }
        if (quote != null) {
            return SelectorValidationResult(isValid = false, reason = "Unclosed string quote in selector")
        }
        if (bracketDepth != 0) {
            return SelectorValidationResult(isValid = false, reason = "Unclosed '[' attribute bracket in selector")
        }
        if (parenDepth != 0) {
            return SelectorValidationResult(isValid = false, reason = "Unclosed '(' pseudo-class parenthesis in selector")
        }
        if (trimmed.contains(Regex(",\\s*,"))) {
            return SelectorValidationResult(isValid = false, reason = "Selector contains an empty list segment")
        }
        return SelectorValidationResult(isValid = true)
    }

    suspend fun scanPageElements(
        webView: WebView?,
        currentUrl: String,
        tabId: String = "",
        navigationEpoch: Long = 0L
    ): PageInspectionState {
        return universalScanner.scanPage(
            webView = webView,
            tabId = tabId,
            currentUrl = currentUrl,
            navigationEpoch = navigationEpoch
        )
    }

    fun parseOrigin(rawUrl: String?): RecordingOriginResult {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
            return RecordingOriginResult.OpaqueOrUnsupported("", "No active document URL.")
        }

        val schemePrefix = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (schemePrefix in setOf("about", "data", "javascript", "blob", "file", "apex", "chrome", "intent", "mailto", "tel")) {
            return RecordingOriginResult.OpaqueOrUnsupported(
                scheme = schemePrefix,
                reason = "Unsupported or opaque URL scheme '$schemePrefix'."
            )
        }

        val authorityCandidate = trimmed.substringBefore('#').substringBefore('?')
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: runCatching { URI(authorityCandidate) }.getOrNull()
            ?: return RecordingOriginResult.OpaqueOrUnsupported(
                scheme = schemePrefix,
                reason = "Malformed URL."
            )

        val scheme = uri.scheme?.lowercase()?.trim().orEmpty()
        if (scheme != "http" && scheme != "https") {
            return RecordingOriginResult.OpaqueOrUnsupported(
                scheme = scheme,
                reason = "Only HTTP and HTTPS origins support automation recording."
            )
        }

        val rawHost = uri.host?.trim().orEmpty()
        val host = rawHost.trimEnd('.').lowercase()
        if (host.isEmpty() || host.contains("..") || host.any { it.isWhitespace() }) {
            return RecordingOriginResult.OpaqueOrUnsupported(
                scheme = scheme,
                reason = "Missing or invalid host in URL."
            )
        }

        val effectivePort = when (val port = uri.port) {
            -1 -> if (scheme == "http") 80 else 443
            in 1..65535 -> port
            else -> return RecordingOriginResult.OpaqueOrUnsupported(
                scheme = scheme,
                reason = "Invalid port in URL."
            )
        }

        val canonicalOrigin = "$scheme://$host:$effectivePort"
        val isDefaultPort = (scheme == "http" && effectivePort == 80) ||
            (scheme == "https" && effectivePort == 443)
        val displayOrigin = if (isDefaultPort) "$scheme://$host" else canonicalOrigin

        return RecordingOriginResult.Valid(
            canonicalOrigin = canonicalOrigin,
            displayOrigin = displayOrigin,
            scheme = scheme,
            host = host,
            effectivePort = effectivePort
        )
    }

    fun areSameOrigin(firstUrl: String?, secondUrl: String?): Boolean {
        val first = parseOrigin(firstUrl)
        val second = parseOrigin(secondUrl)
        return first is RecordingOriginResult.Valid &&
            second is RecordingOriginResult.Valid &&
            first.canonicalOrigin == second.canonicalOrigin
    }

    internal fun buildRecorderInstallScript(
        sessionId: String,
        tabId: String,
        expectedOrigin: String,
        initialSequenceNumber: Long = 0L
    ): String {
        val qSessionId = JSONObject.quote(sessionId)
        val qTabId = JSONObject.quote(tabId)
        val qExpectedOrigin = JSONObject.quote(expectedOrigin)
        val safeInitialSeq = initialSequenceNumber.coerceAtLeast(0L)
        return """
            (function() {
                try {
                    if (window.__apexRecorderInstance && typeof window.__apexRecorderInstance.cleanup === 'function') {
                        try { window.__apexRecorderInstance.cleanup(); } catch (_) {}
                    }
                    window.__apexRecorderInstance = null;
                    window.__apexRecordingActive = false;
                    window.__apexRecorderInstalled = false;

                    function getCanonicalOrigin() {
                        try {
                            if (typeof window.origin === 'string' && window.origin === 'null') return '';
                            var loc = window.location;
                            if (!loc) return '';
                            var proto = String(loc.protocol || '').toLowerCase();
                            if (proto !== 'http:' && proto !== 'https:') return '';
                            var scheme = proto.slice(0, -1);
                            var host = String(loc.hostname || '').trim().replace(/\.+$/, '').toLowerCase();
                            if (!host) return '';
                            var rawPort = String(loc.port || '');
                            var port = rawPort ? parseInt(rawPort, 10) : (scheme === 'http' ? 80 : 443);
                            if (!port || isNaN(port) || port < 1 || port > 65535) return '';
                            return scheme + '://' + host + ':' + port;
                        } catch (_) {
                            return '';
                        }
                    }

                    var actualOrigin = getCanonicalOrigin();
                    var expectedOrigin = $qExpectedOrigin;
                    if (!actualOrigin || actualOrigin !== expectedOrigin) {
                        return JSON.stringify({
                            ok: false,
                            reason: 'origin_mismatch',
                            actualOrigin: actualOrigin || ''
                        });
                    }

                    function escCss(v) {
                        if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(String(v));
                        return String(v).replace(/([ !"#$%&'()*+,.\/:;<=>?@\[\\\]^`{|}~])/g, '\\$1');
                    }
                    function escAttr(v) {
                        return String(v).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\r|\n/g, ' ');
                    }
                    function isStableId(id) {
                        if (!id) return false;
                        var clean = String(id).trim();
                        if (!clean || clean.length > 80 || /\s/.test(clean)) return false;
                        if (/^(?:react-select-\d+.*|:r[0-9a-z]+:|mui-\d+|ember\d+|el-id-\d+.*|headlessui-.*-\d+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|_[0-9a-z]{7,}|\d{5,})$/i.test(clean)) return false;
                        if (/^(?:css-[0-9a-z]{5,}|sc-[a-zA-Z0-9]{5,}|jsx-\d+|svelte-[0-9a-z]{5,}|tw-[0-9a-f]{6,}|style_[0-9a-f]{6,})$/.test(clean)) return false;
                        if (/\d{6,}/.test(clean)) return false;
                        return true;
                    }
                    function getContexts(el) {
                        var framePath = [];
                        var shadowHostPath = [];
                        try {
                            var rootNode = el.getRootNode ? el.getRootNode() : document;
                            var guard = 0;
                            while (typeof ShadowRoot !== 'undefined' && rootNode instanceof ShadowRoot && rootNode.host && guard < 8) {
                                var h = rootNode.host;
                                var ht = (h.tagName || '').toLowerCase();
                                var hs = isStableId(h.id) ? (ht + '#' + escCss(h.id)) :
                                    (h.getAttribute('data-testid') ? (ht + '[data-testid="' + escAttr(h.getAttribute('data-testid')) + '"]') : ht);
                                shadowHostPath.unshift(hs);
                                rootNode = h.getRootNode ? h.getRootNode() : document;
                                guard++;
                            }
                            var win = (el.ownerDocument && el.ownerDocument.defaultView) || window;
                            guard = 0;
                            while (win && win !== window && win.frameElement && guard < 6) {
                                var f = win.frameElement;
                                var fid = f.id || '';
                                var fname = f.getAttribute && f.getAttribute('name') || '';
                                var fs = isStableId(fid) ? ('iframe#' + escCss(fid)) :
                                    (fname ? ('iframe[name="' + escAttr(fname) + '"]') : 'iframe');
                                framePath.unshift(fs);
                                win = win.parent;
                                guard++;
                            }
                        } catch (_) {}
                        return { framePath: framePath, shadowHostPath: shadowHostPath };
                    }
                    function getParentContext(el) {
                        try {
                            var parts = [];
                            var p = el.parentElement;
                            var steps = 0;
                            while (p && p.nodeType === 1 && steps < 5 && parts.length < 2) {
                                var pt = (p.tagName || '').toLowerCase();
                                if (pt === 'form' || pt === 'header' || pt === 'nav' || pt === 'footer' || pt === 'main' || pt === 'aside' || pt === 'section' || pt === 'fieldset' ||
                                    p.getAttribute('role') || p.getAttribute('aria-label') || isStableId(p.id)) {
                                    var d = pt;
                                    if (isStableId(p.id)) d += '#' + p.id;
                                    else if (p.getAttribute('data-testid')) d += '[data-testid="' + p.getAttribute('data-testid') + '"]';
                                    else if (p.getAttribute('role')) d += '[role="' + p.getAttribute('role') + '"]';
                                    parts.unshift(d);
                                }
                                p = p.parentElement;
                                steps++;
                            }
                            return parts.join(' > ').slice(0, 120);
                        } catch (_) { return ''; }
                    }
                    function isSensitiveNode(el) {
                        if (!el) return false;
                        var t = String((el.getAttribute && el.getAttribute('type')) || '').toLowerCase();
                        if ((el.tagName || '').toLowerCase() === 'input' && t === 'password') return true;
                        var hint = [
                            t,
                            (el.getAttribute && el.getAttribute('name')) || '',
                            el.id || '',
                            (el.getAttribute && el.getAttribute('autocomplete')) || '',
                            (el.getAttribute && el.getAttribute('aria-label')) || '',
                            (el.getAttribute && el.getAttribute('placeholder')) || ''
                        ].join(' ').toLowerCase();
                        return /(password|passcode|pin\b|secret|token|otp|one-time-code|cvv|cvc|csc|cc-number|cc-csc|ssn|routing)/i.test(hint);
                    }
                    function resolveFieldGroupLabel(el, rootNode) {
                        if (!el || !el.tagName) return '';
                        var tag = (el.tagName || '').toLowerCase();
                        var role = (el.getAttribute && el.getAttribute('role') || '').toLowerCase();
                        if (tag === 'button' || tag === 'a' || tag === 'summary' || tag === 'option' || tag === 'label' ||
                            role === 'button' || role === 'link' || role === 'option' || role === 'menuitem') {
                            return '';
                        }
                        var type = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
                        if (type === 'submit' || type === 'button' || type === 'reset' || type === 'hidden') return '';
                        var doc = el.ownerDocument || document;
                        var rNode = rootNode || (el.getRootNode && el.getRootNode()) || doc;
                        function extractValidLabelText(node, requireExplicit) {
                            if (!node || node.nodeType !== 1 || node === el || (node.contains && node.contains(el))) return '';
                            var nTag = (node.tagName || '').toLowerCase();
                            if (nTag === 'h1' || nTag === 'h2' || nTag === 'h3' || nTag === 'h4' || nTag === 'h5' || nTag === 'h6' ||
                                nTag === 'button' || nTag === 'a' || nTag === 'input' || nTag === 'select' || nTag === 'textarea' || nTag === 'form') {
                                return '';
                            }
                            var forAttr = node.getAttribute && node.getAttribute('for');
                            if (forAttr && (!el.id || forAttr !== el.id)) {
                                try {
                                    var targetById = (rNode.getElementById ? rNode.getElementById(forAttr) : null) || (doc.getElementById ? doc.getElementById(forAttr) : null);
                                    if (targetById) return '';
                                } catch (_) { return ''; }
                            }
                            if (node.querySelector && node.querySelector('input:not([type="hidden"]), select, textarea, button, a[href]')) return '';
                            var nCls = String(node.className || '').toLowerCase();
                            var isExplicit = nTag === 'label' || nTag === 'legend' || /(?:^|[\s_-])(label|field-label|form-label|control-label|input-label)(?:$|[\s_-])/.test(nCls);
                            if (requireExplicit && !isExplicit && ((nTag !== 'span' && nTag !== 'div') || nCls.indexOf('label') < 0)) return '';
                            var raw = String(node.innerText || node.textContent || '').replace(/\s+/g, ' ').trim();
                            if (!raw || raw.length > 48) return '';
                            if (!isExplicit && /[.!?]$/.test(raw)) return '';
                            return raw;
                        }
                        try {
                            var prev = el.previousElementSibling;
                            if (prev) {
                                var pCtrls = el.parentElement && el.parentElement.querySelectorAll
                                    ? el.parentElement.querySelectorAll('input:not([type="hidden"]):not([type="submit"]):not([type="button"]), select, textarea').length
                                    : 2;
                                var pTxt = extractValidLabelText(prev, pCtrls > 1);
                                if (pTxt) return pTxt;
                            }
                            var cur = el.parentElement;
                            var depth = 0;
                            while (cur && cur.nodeType === 1 && depth < 3) {
                                var cTag = (cur.tagName || '').toLowerCase();
                                if (cTag === 'form' || cTag === 'body' || cTag === 'html' || cTag === 'main' || cTag === 'table' || cTag === 'fieldset') break;
                                var ctrls = cur.querySelectorAll
                                    ? cur.querySelectorAll('input:not([type="hidden"]):not([type="submit"]):not([type="button"]), select, textarea, [role="combobox"], [role="listbox"], [role="spinbutton"]')
                                    : [];
                                if (ctrls.length > 1) break;
                                var curPrev = cur.previousElementSibling;
                                if (curPrev) {
                                    var parentCtrls = cur.parentElement && cur.parentElement.querySelectorAll
                                        ? cur.parentElement.querySelectorAll('input:not([type="hidden"]):not([type="submit"]):not([type="button"]), select, textarea, [role="combobox"], [role="listbox"], [role="spinbutton"]')
                                        : [];
                                    var cpTxt = extractValidLabelText(curPrev, parentCtrls.length > 1);
                                    if (cpTxt) return cpTxt;
                                }
                                if (ctrls.length <= 1 && cur.querySelector) {
                                    var innerLbl = cur.querySelector('label, [class*="label"], [class*="Label"]');
                                    if (innerLbl) {
                                        var ilTxt = extractValidLabelText(innerLbl, false);
                                        if (ilTxt) return ilTxt;
                                    }
                                }
                                cur = cur.parentElement;
                                depth++;
                            }
                        } catch (_) {}
                        return '';
                    }
                    function getNearbyText(el) {
                        if (isSensitiveNode(el)) return '';
                        try {
                            var fgLbl = resolveFieldGroupLabel(el, null);
                            if (fgLbl) return fgLbl.slice(0, 80);
                            var fs = el.closest ? el.closest('fieldset') : null;
                            if (fs) {
                                var lg = fs.querySelector('legend');
                                if (lg && (lg.innerText || lg.textContent)) return String(lg.innerText || lg.textContent).replace(/\s+/g, ' ').trim().slice(0, 80);
                            }
                            var prev = el.previousElementSibling;
                            if (prev && !isSensitiveNode(prev) && (prev.innerText || prev.textContent)) {
                                return String(prev.innerText || prev.textContent).replace(/\s+/g, ' ').trim().slice(0, 80);
                            }
                        } catch (_) {}
                        return '';
                    }
                    function getSafeAttributes(el) {
                        var out = {};
                        var sensitive = isSensitiveNode(el);
                        var allowed = [
                            'type', 'role', 'name', 'href', 'placeholder', 'autocomplete', 'inputmode', 'title', 'for',
                            'aria-label', 'aria-labelledby', 'aria-haspopup', 'aria-expanded', 'aria-controls',
                            'aria-pressed', 'aria-selected', 'aria-checked', 'aria-current',
                            'data-testid', 'data-test', 'data-qa', 'data-cy', 'data-action',
                            'data-date', 'data-day', 'data-month', 'data-year', 'data-datepicker', 'data-dial-code', 'data-country-code',
                            'data-code', 'data-gender', 'data-value', 'data-option'
                        ];
                        for (var i = 0; i < allowed.length; i++) {
                            var k = allowed[i];
                            if (sensitive && k === 'placeholder') continue;
                            var v = el.getAttribute && el.getAttribute(k);
                            if (v && String(v).trim()) out[k] = String(v).trim().slice(0, 120);
                        }
                        if (isStableId(el.id)) out.id = String(el.id).trim();
                        return out;
                    }
                    function getLabel(el) {
                        if (!el) return 'Element';
                        var root = (el.getRootNode && el.getRootNode()) || document;
                        var doc = el.ownerDocument || document;
                        var lb = (el.getAttribute && el.getAttribute('aria-labelledby') || '').trim();
                        if (lb) {
                            var refTxt = lb.split(/\s+/).map(function(id) {
                                var ref = (root.getElementById ? root.getElementById(id) : null) || (doc.getElementById ? doc.getElementById(id) : null);
                                return ref ? (ref.innerText || ref.textContent || '').trim() : '';
                            }).filter(Boolean).join(' ');
                            if (refTxt) return refTxt.slice(0, 64);
                        }
                        var aria = (el.getAttribute && el.getAttribute('aria-label') || '').trim();
                        if (aria) return aria;
                        if (el.id) {
                            try {
                                var lbl = (root.querySelector ? root.querySelector('label[for="' + escCss(el.id) + '"]') : null) ||
                                    doc.querySelector('label[for="' + escCss(el.id) + '"]');
                                if (lbl && (lbl.innerText || lbl.textContent)) return String(lbl.innerText || lbl.textContent).trim();
                            } catch (_) {}
                        }
                        var parentLbl = el.closest ? el.closest('label') : null;
                        if (parentLbl && (parentLbl.innerText || parentLbl.textContent)) return String(parentLbl.innerText || parentLbl.textContent).trim();
                        var fgLabel = resolveFieldGroupLabel(el, root);
                        if (fgLabel) return fgLabel.slice(0, 64);
                        var titleAttr = (el.getAttribute && el.getAttribute('title') || '').trim();
                        if (titleAttr) return titleAttr;
                        var ph = (el.getAttribute && el.getAttribute('placeholder') || '').trim();
                        if (ph) return ph;
                        var name = (el.getAttribute && el.getAttribute('name') || '').trim();
                        if (name) return name;
                        var type = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
                        var rawText = type === 'password' ? '' : (el.innerText || el.textContent || ((type === 'submit' || type === 'button') ? (el.value || '') : ''));
                        var txt = String(rawText).replace(/\s+/g, ' ').trim();
                        if (txt) return txt.slice(0, 64);
                        return (el.tagName || 'Element').toLowerCase();
                    }
                    function isNodeVisible(node) {
                        try {
                            var cur = node;
                            var d = 0;
                            while (cur && cur.nodeType === 1 && d < 25) {
                                var cType = (cur.getAttribute && cur.getAttribute('type') || '').toLowerCase();
                                if (cType === 'hidden' || (cur.hasAttribute && (cur.hasAttribute('hidden') || cur.hasAttribute('inert')))) return false;
                                if (String(cur.getAttribute && cur.getAttribute('aria-hidden') || '').toLowerCase() === 'true') return false;
                                var st = window.getComputedStyle ? window.getComputedStyle(cur) : null;
                                if (st && (st.display === 'none' || st.visibility === 'hidden' || (st.opacity !== '' && parseFloat(st.opacity) === 0))) return false;
                                cur = cur.parentElement;
                                d++;
                            }
                            return true;
                        } catch (_) { return true; }
                    }
                    function buildUniqueSelectorForNode(node, root, prefix) {
                        if (!node || !node.tagName) return '';
                        var nTag = node.tagName.toLowerCase();
                        function isUniq(sel) {
                            try { return root.querySelectorAll(sel).length === 1; } catch (_) { return false; }
                        }
                        var direct = [];
                        if (isStableId(node.id)) direct.push('#' + escCss(node.id));
                        [
                            'data-testid', 'data-test', 'data-qa', 'data-cy', 'data-action',
                            'data-date', 'data-country-code', 'data-dial-code', 'data-code',
                            'data-day', 'data-gender', 'data-value', 'data-option'
                        ].forEach(function(a) {
                            var v = node.getAttribute && node.getAttribute(a);
                            if (v) direct.push(nTag + '[' + a + '="' + escAttr(v) + '"]');
                        });
                        if (nTag === 'label') {
                            var nFor = node.getAttribute && node.getAttribute('for');
                            if (nFor) direct.push('label[for="' + escAttr(nFor) + '"]');
                        }
                        var nRole = (node.getAttribute && node.getAttribute('role') || '').toLowerCase();
                        var nAria = node.getAttribute && node.getAttribute('aria-label');
                        if (nAria) {
                            if (nRole) direct.push(nTag + '[role="' + escAttr(nRole) + '"][aria-label="' + escAttr(nAria) + '"]');
                            direct.push(nTag + '[aria-label="' + escAttr(nAria) + '"]');
                        }
                        var nName = node.getAttribute && node.getAttribute('name');
                        if (nName) direct.push(nTag + '[name="' + escAttr(nName) + '"]');
                        var nHref = (nTag === 'a' && node.getAttribute) ? (node.getAttribute('href') || '').trim() : '';
                        if (nHref && nHref !== '#' && nHref.indexOf('javascript:') !== 0) {
                            direct.push('a[href="' + escAttr(nHref) + '"]');
                        }
                        var nPh = node.getAttribute && node.getAttribute('placeholder');
                        if (nPh) direct.push(nTag + '[placeholder="' + escAttr(nPh) + '"]');
                        for (var di = 0; di < direct.length; di++) {
                            if (isUniq(direct[di])) return prefix + direct[di];
                        }
                        var aNode = node.parentElement;
                        var aStep = 0;
                        while (aNode && aNode.nodeType === 1 && aStep < 6) {
                            var aTag = (aNode.tagName || '').toLowerCase();
                            var aRole = (aNode.getAttribute && aNode.getAttribute('role') || '').toLowerCase();
                            var aSel = '';
                            if (isStableId(aNode.id)) aSel = aTag + '#' + escCss(aNode.id);
                            else if (aNode.getAttribute && aNode.getAttribute('data-testid')) aSel = aTag + '[data-testid="' + escAttr(aNode.getAttribute('data-testid')) + '"]';
                            else if (aNode.hasAttribute && aNode.hasAttribute('data-bottom-sheet')) aSel = aTag + '[data-bottom-sheet]';
                            else if (aNode.hasAttribute && aNode.hasAttribute('data-sheet')) aSel = aTag + '[data-sheet]';
                            else if (aNode.hasAttribute && aNode.hasAttribute('data-datepicker')) aSel = aTag + '[data-datepicker]';
                            else if (aRole === 'dialog' || aRole === 'alertdialog' || aRole === 'listbox' || aRole === 'radiogroup' || aRole === 'grid') aSel = aTag + '[role="' + escAttr(aRole) + '"]';
                            else if (aTag === 'form' && aNode.getAttribute && aNode.getAttribute('name')) aSel = 'form[name="' + escAttr(aNode.getAttribute('name')) + '"]';
                            else if (aTag === 'dialog') aSel = aNode.hasAttribute('open') ? 'dialog[open]' : 'dialog';
                            else if (aTag === 'form' || aTag === 'header' || aTag === 'nav' || aTag === 'footer' || aTag === 'main' || aTag === 'aside') aSel = aTag;
                            if (aSel) {
                                for (var ci = 0; ci < direct.length; ci++) {
                                    if (isUniq(aSel + ' ' + direct[ci])) return prefix + aSel + ' ' + direct[ci];
                                }
                            }
                            aNode = aNode.parentElement;
                            aStep++;
                        }
                        var segs = [];
                        var curNode = node;
                        for (var lv = 0; curNode && curNode.nodeType === 1 && lv < 8; lv++) {
                            var ct = (curNode.tagName || '').toLowerCase();
                            if (!ct || ct === 'html' || ct === 'body') break;
                            var seg = ct;
                            var anchored = false;
                            if (isStableId(curNode.id)) {
                                seg = ct + '#' + escCss(curNode.id);
                                if (isUniq([seg].concat(segs).join(' > '))) {
                                    segs.unshift(seg);
                                    return prefix + segs.join(' > ');
                                }
                                anchored = true;
                            }
                            if (!anchored) {
                                var pEl = curNode.parentElement;
                                if (pEl) {
                                    var sameT = Array.from(pEl.children).filter(function(ch) { return ch.tagName === curNode.tagName; });
                                    if (sameT.length > 1) seg += ':nth-of-type(' + (sameT.indexOf(curNode) + 1) + ')';
                                }
                            }
                            segs.unshift(seg);
                            if (isUniq(segs.join(' > '))) return prefix + segs.join(' > ');
                            curNode = curNode.parentElement;
                        }
                        return prefix + (segs.join(' > ') || nTag);
                    }
                    function buildLocatorBundle(el) {
                        if (!el || !el.tagName) return { selector: '', candidates: [], matchCount: 0, contexts: { framePath: [], shadowHostPath: [] }, parentContext: '', nearbyText: '', relevantAttributes: {}, ambiguousCandidates: [] };
                        var root = (el.getRootNode && el.getRootNode()) || document;
                        var contexts = getContexts(el);
                        var prefix = contexts.shadowHostPath.length > 0 ? (contexts.shadowHostPath.join(' >>> ') + ' >>> ') :
                            (contexts.framePath.length > 0 ? (contexts.framePath.join(' >> ') + ' >> ') : '');
                        var scopeName = contexts.shadowHostPath.length > 0 ? 'SHADOW' : (contexts.framePath.length > 0 ? 'IFRAME' : 'DOCUMENT');
                        var tag = el.tagName.toLowerCase();
                        var role = (el.getAttribute && el.getAttribute('role') || '').toLowerCase();
                        var rawCands = [];
                        var seen = {};
                        function pushCand(strategy, localSel, baseConf, stability) {
                            if (!localSel) return;
                            var fullSel = prefix + localSel;
                            if (seen[fullSel]) return;
                            seen[fullSel] = true;
                            var cnt = 0;
                            try { cnt = root.querySelectorAll(localSel).length; } catch (_) { cnt = 0; }
                            if (cnt <= 0) return;
                            var uniq = cnt === 1;
                            var effStrategy = contexts.shadowHostPath.length > 0 ? 'shadow_dom_chain' : (contexts.framePath.length > 0 ? 'iframe_chain' : strategy);
                            var conf = uniq ? baseConf : Math.max(0.30, baseConf - 0.35);
                            rawCands.push({
                                strategy: effStrategy,
                                selector: fullSel,
                                scope: scopeName,
                                confidence: conf,
                                isUnique: uniq,
                                matchCount: cnt,
                                isAmbiguous: !uniq,
                                stability: stability
                            });
                        }
                        if (isStableId(el.id)) pushCand('stable_id', '#' + escCss(el.id), 0.99, 'HIGH');
                        ['data-testid', 'data-test', 'data-qa', 'data-cy', 'data-action'].forEach(function(a) {
                            var v = el.getAttribute && el.getAttribute(a);
                            if (v) pushCand('stable_data_attr', tag + '[' + a + '="' + escAttr(v) + '"]', 0.95, 'HIGH');
                        });
                        [
                            'data-date', 'data-country-code', 'data-dial-code', 'data-code',
                            'data-day', 'data-gender', 'data-value', 'data-option'
                        ].forEach(function(a) {
                            var v = el.getAttribute && el.getAttribute(a);
                            if (v) pushCand('stable_data_attr', tag + '[' + a + '="' + escAttr(v) + '"]', 0.93, 'HIGH');
                        });
                        if (tag === 'label') {
                            var labelForAttr = el.getAttribute && el.getAttribute('for');
                            if (labelForAttr) {
                                pushCand('label_for', 'label[for="' + escAttr(labelForAttr) + '"]', 0.90, 'HIGH');
                            }
                        }
                        var aria = el.getAttribute && el.getAttribute('aria-label');
                        if (aria) {
                            if (role) pushCand('aria_role_and_name', tag + '[role="' + escAttr(role) + '"][aria-label="' + escAttr(aria) + '"]', 0.94, 'HIGH');
                            pushCand('aria_role_and_name', tag + '[aria-label="' + escAttr(aria) + '"]', 0.91, 'HIGH');
                        }
                        if (isStableId(el.id)) {
                            try {
                                if (root.querySelector('label[for="' + escCss(el.id) + '"]')) {
                                    pushCand('label_for', tag + '#' + escCss(el.id), 0.90, 'HIGH');
                                }
                            } catch (_) {}
                        }
                        var name = el.getAttribute && el.getAttribute('name');
                        if (name) pushCand('stable_name', tag + '[name="' + escAttr(name) + '"]', 0.89, 'HIGH');
                        var href = (tag === 'a' && el.getAttribute) ? (el.getAttribute('href') || '').trim() : '';
                        if (href && href !== '#' && href.indexOf('javascript:') !== 0) {
                            pushCand('href_attr', 'a[href="' + escAttr(href) + '"]', 0.88, 'HIGH');
                        }
                        var ph = el.getAttribute && el.getAttribute('placeholder');
                        if (ph) pushCand('placeholder_attr', tag + '[placeholder="' + escAttr(ph) + '"]', 0.82, 'MEDIUM');

                        // Form or semantic / dialog / sheet / radiogroup / grid container scoped locators
                        var anc = el.parentElement;
                        var aDepth = 0;
                        while (anc && anc.nodeType === 1 && aDepth < 6) {
                            var at = (anc.tagName || '').toLowerCase();
                            var aRoleAttr = (anc.getAttribute && anc.getAttribute('role') || '').toLowerCase();
                            var cSel = '';
                            if (isStableId(anc.id)) cSel = at + '#' + escCss(anc.id);
                            else if (anc.getAttribute && anc.getAttribute('data-testid')) cSel = at + '[data-testid="' + escAttr(anc.getAttribute('data-testid')) + '"]';
                            else if (anc.hasAttribute && anc.hasAttribute('data-bottom-sheet')) cSel = at + '[data-bottom-sheet]';
                            else if (anc.hasAttribute && anc.hasAttribute('data-sheet')) cSel = at + '[data-sheet]';
                            else if (anc.hasAttribute && anc.hasAttribute('data-datepicker')) cSel = at + '[data-datepicker]';
                            else if (aRoleAttr === 'dialog' || aRoleAttr === 'alertdialog' || aRoleAttr === 'listbox' || aRoleAttr === 'radiogroup' || aRoleAttr === 'grid') cSel = at + '[role="' + escAttr(aRoleAttr) + '"]';
                            else if (at === 'form' && anc.getAttribute && anc.getAttribute('name')) cSel = 'form[name="' + escAttr(anc.getAttribute('name')) + '"]';
                            else if (at === 'dialog') cSel = anc.hasAttribute('open') ? 'dialog[open]' : 'dialog';
                            else if (at === 'form' || at === 'header' || at === 'nav' || at === 'footer' || at === 'main' || at === 'aside') cSel = at;
                            if (cSel) {
                                var strat = (at === 'form') ? 'form_relative' : 'container_scoped';
                                var baseConf = (at === 'form') ? 0.85 : 0.78;
                                if (isStableId(el.id)) pushCand(strat, cSel + ' #' + escCss(el.id), baseConf + 0.03, 'HIGH');
                                if (href && href !== '#') pushCand(strat, cSel + ' a[href="' + escAttr(href) + '"]', baseConf, 'HIGH');
                                if (name) pushCand(strat, cSel + ' ' + tag + '[name="' + escAttr(name) + '"]', baseConf, 'HIGH');
                                if (aria) pushCand(strat, cSel + ' ' + tag + '[aria-label="' + escAttr(aria) + '"]', baseConf, 'HIGH');
                                ['data-date', 'data-country-code', 'data-dial-code', 'data-code', 'data-day', 'data-gender', 'data-value'].forEach(function(wa) {
                                    var wv = el.getAttribute && el.getAttribute(wa);
                                    if (wv) pushCand(strat, cSel + ' ' + tag + '[' + wa + '="' + escAttr(wv) + '"]', baseConf + 0.02, 'HIGH');
                                });
                                var typeAttr = el.getAttribute && el.getAttribute('type');
                                if (typeAttr) pushCand(strat, cSel + ' ' + tag + '[type="' + escAttr(typeAttr) + '"]', baseConf - 0.05, 'MEDIUM');
                                else pushCand(strat, cSel + ' ' + tag, baseConf - 0.08, 'MEDIUM');
                            }
                            anc = anc.parentElement;
                            aDepth++;
                        }

                        // Structural path up to 8 levels (only break when uniquely matching)
                        var parts = [];
                        var cur = el;
                        for (var depth = 0; cur && cur.nodeType === 1 && depth < 8; depth++) {
                            var pt = cur.tagName.toLowerCase();
                            if (pt === 'html' || pt === 'body') break;
                            var anchored = false;
                            if (isStableId(cur.id)) {
                                pt = pt + '#' + escCss(cur.id);
                                if (root.querySelectorAll([pt].concat(parts).join(' > ')).length === 1) {
                                    parts.unshift(pt);
                                    pushCand('scoped_css', parts.join(' > '), 0.68, 'MEDIUM');
                                    break;
                                }
                                anchored = true;
                            }
                            if (!anchored) {
                                var parent = cur.parentElement;
                                if (parent) {
                                    var sameTag = Array.from(parent.children).filter(function(c) { return c.tagName === cur.tagName; });
                                    if (sameTag.length > 1) pt += ':nth-of-type(' + (sameTag.indexOf(cur) + 1) + ')';
                                }
                            }
                            parts.unshift(pt);
                            var cand = parts.join(' > ');
                            try {
                                if (root.querySelectorAll(cand).length === 1) {
                                    pushCand('scoped_css', cand, 0.64, 'MEDIUM');
                                    break;
                                }
                            } catch (_) {}
                            cur = cur.parentElement;
                        }
                        if (rawCands.length === 0) {
                            var fallbackType = el.getAttribute && el.getAttribute('type');
                            pushCand('scoped_css', fallbackType ? (tag + '[type="' + escAttr(fallbackType) + '"]') : tag, 0.55, 'LOW');
                        }
                        rawCands.sort(function(a, b) {
                            if (a.isUnique !== b.isUnique) return a.isUnique ? -1 : 1;
                            return b.confidence - a.confidence;
                        });
                        var top = rawCands[0] || { selector: tag, matchCount: 1, isAmbiguous: false };
                        var ambigList = [];
                        if (top.matchCount > 1) {
                            try {
                                var localTop = prefix && top.selector.indexOf(prefix) === 0 ? top.selector.slice(prefix.length) : top.selector;
                                var found = Array.from(root.querySelectorAll(localTop)).slice(0, 8);
                                for (var m = 0; m < found.length; m++) {
                                    var f = found[m];
                                    var fRole = (f.getAttribute && f.getAttribute('role') || '').toLowerCase() || (f.tagName.toLowerCase() === 'a' ? 'link' : f.tagName.toLowerCase());
                                    var fSensitive = isSensitiveNode(f);
                                    ambigList.push({
                                        index: m,
                                        tagName: f.tagName.toLowerCase(),
                                        role: fRole,
                                        accessibleName: fSensitive ? ((f.getAttribute && (f.getAttribute('aria-label') || f.getAttribute('name'))) || f.tagName.toLowerCase()) : getLabel(f),
                                        visibleText: fSensitive ? '' : String(f.innerText || f.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 80),
                                        attributes: getSafeAttributes(f),
                                        parentContext: getParentContext(f),
                                        nearbyText: fSensitive ? '' : getNearbyText(f),
                                        framePath: contexts.framePath,
                                        shadowHostPath: contexts.shadowHostPath,
                                        isVisible: isNodeVisible(f),
                                        isEnabled: !Boolean(f.disabled),
                                        recommendedSelector: buildUniqueSelectorForNode(f, root, prefix) || top.selector
                                    });
                                }
                            } catch (_) {}
                        }
                        return {
                            selector: top.selector || '',
                            candidates: rawCands.slice(0, 6),
                            matchCount: top.matchCount || 1,
                            contexts: contexts,
                            parentContext: getParentContext(el),
                            nearbyText: getNearbyText(el),
                            relevantAttributes: getSafeAttributes(el),
                            ambiguousCandidates: ambigList
                        };
                    }

                    function getNodeRect(el) {
                        try {
                            if (!el || typeof el.getBoundingClientRect !== 'function') return null;
                            var r = el.getBoundingClientRect();
                            var offsetX = 0;
                            var offsetY = 0;
                            var curWin = (el.ownerDocument && el.ownerDocument.defaultView) || window;
                            var guard = 0;
                            while (curWin && curWin !== window && curWin.frameElement && guard < 6) {
                                var fr = curWin.frameElement.getBoundingClientRect();
                                offsetX += fr.left;
                                offsetY += fr.top;
                                curWin = curWin.parent;
                                guard++;
                            }
                            var vw = Math.max(1, window.innerWidth || (document.documentElement && document.documentElement.clientWidth) || 360);
                            var vh = Math.max(1, window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 640);
                            return {
                                left: Math.round((r.left + offsetX) * 10) / 10,
                                top: Math.round((r.top + offsetY) * 10) / 10,
                                width: Math.round(r.width * 10) / 10,
                                height: Math.round(r.height * 10) / 10,
                                viewportWidth: vw,
                                viewportHeight: vh
                            };
                        } catch (_) {
                            return null;
                        }
                    }

                    function highlightRecordedNode(el) {
                        try {
                            if (!el || !el.style) return;
                            var prevOutline = el.getAttribute('data-apex-orig-outline');
                            if (prevOutline === null) {
                                el.setAttribute('data-apex-orig-outline', el.style.outline || '');
                                el.setAttribute('data-apex-orig-outline-offset', el.style.outlineOffset || '');
                                el.setAttribute('data-apex-orig-box-shadow', el.style.boxShadow || '');
                            }
                            el.style.outline = '2.5px solid #00E5FF';
                            el.style.outlineOffset = '2px';
                            el.style.boxShadow = '0 0 0 4px rgba(0, 229, 255, 0.24), 0 0 14px rgba(0, 229, 255, 0.42)';
                            if (el.__apexRecHighlightTimer) {
                                clearTimeout(el.__apexRecHighlightTimer);
                            }
                            el.__apexRecHighlightTimer = setTimeout(function() {
                                try {
                                    el.style.outline = el.getAttribute('data-apex-orig-outline') || '';
                                    el.style.outlineOffset = el.getAttribute('data-apex-orig-outline-offset') || '';
                                    el.style.boxShadow = el.getAttribute('data-apex-orig-box-shadow') || '';
                                    el.removeAttribute('data-apex-orig-outline');
                                    el.removeAttribute('data-apex-orig-outline-offset');
                                    el.removeAttribute('data-apex-orig-box-shadow');
                                } catch (_) {}
                            }, 1400);
                        } catch (_) {}
                    }

                    var inst = {
                        sessionId: $qSessionId,
                        tabId: $qTabId,
                        expectedOrigin: expectedOrigin,
                        seq: $safeInitialSeq,
                        active: true,
                        destroyed: false,
                        lastDomEvent: null,
                        lastFocusKey: '',
                        pendingInput: null,
                        inputTimer: null,
                        lastEmittedInput: null,
                        onFocusIn: null,
                        onFocusOut: null,
                        onInput: null,
                        onChange: null,
                        onClick: null,
                        cleanup: function() {
                            inst.destroyed = true;
                            inst.active = false;
                            if (inst.inputTimer) {
                                try { clearTimeout(inst.inputTimer); } catch (_) {}
                                inst.inputTimer = null;
                            }
                            inst.pendingInput = null;
                            try {
                                if (inst.onFocusIn) document.removeEventListener('focusin', inst.onFocusIn, true);
                                if (inst.onFocusOut) document.removeEventListener('focusout', inst.onFocusOut, true);
                                if (inst.onInput) document.removeEventListener('input', inst.onInput, true);
                                if (inst.onChange) document.removeEventListener('change', inst.onChange, true);
                                if (inst.onClick) document.removeEventListener('click', inst.onClick, true);
                            } catch (_) {}
                            if (window.__apexRecorderInstance === inst) {
                                window.__apexRecorderInstance = null;
                                window.__apexRecordingActive = false;
                                window.__apexRecorderInstalled = false;
                            }
                        }
                    };

                    function emit(domEvent, actionType, label, el, value, isDestructive, isPasswordField) {
                        if (inst.destroyed || !inst.active || window.__apexRecorderInstance !== inst) return;
                        var nowOrigin = getCanonicalOrigin();
                        if (!nowOrigin || nowOrigin !== inst.expectedOrigin) {
                            inst.cleanup();
                            return;
                        }
                        if (domEvent && inst.lastDomEvent === domEvent) return;
                        inst.lastDomEvent = domEvent || null;
                        inst.seq = inst.seq + 1;
                        var isPwd = Boolean(isPasswordField);
                        highlightRecordedNode(el);
                        var bundle = buildLocatorBundle(el);
                        var rectObj = getNodeRect(el);
                        var payload = JSON.stringify({
                            sessionId: inst.sessionId,
                            tabId: inst.tabId,
                            origin: nowOrigin,
                            sequenceNumber: inst.seq,
                            eventType: actionType,
                            targetLabel: label || 'Element',
                            tagName: (el && el.tagName ? el.tagName.toLowerCase() : ''),
                            selector: bundle.selector || '',
                            locatorCandidates: bundle.candidates || [],
                            framePath: bundle.contexts.framePath || [],
                            shadowHostPath: bundle.contexts.shadowHostPath || [],
                            parentContext: bundle.parentContext || '',
                            nearbyText: bundle.nearbyText || '',
                            relevantAttributes: bundle.relevantAttributes || {},
                            matchCount: bundle.matchCount || 1,
                            ambiguousCandidates: bundle.ambiguousCandidates || [],
                            boundingBox: rectObj,
                            value: isPwd ? '' : (value || ''),
                            isDestructive: Boolean(isDestructive),
                            isPasswordField: isPwd,
                            fieldRole: isPwd ? 'PASSWORD' : '',
                            timestamp: Date.now()
                        });
                        if (window.ApexAutomationBridge && window.ApexAutomationBridge.onRecordedEventJson) {
                            window.ApexAutomationBridge.onRecordedEventJson(payload);
                        }
                    }

                    function flushPendingInput(domEvent) {
                        if (inst.inputTimer) {
                            try { clearTimeout(inst.inputTimer); } catch (_) {}
                            inst.inputTimer = null;
                        }
                        var pending = inst.pendingInput;
                        inst.pendingInput = null;
                        if (!pending || !pending.el) return;
                        if (inst.lastEmittedInput &&
                            inst.lastEmittedInput.el === pending.el &&
                            inst.lastEmittedInput.value === pending.value) {
                            return;
                        }
                        inst.lastEmittedInput = { el: pending.el, value: pending.value };
                        emit(domEvent, 'INPUT', getLabel(pending.el), pending.el, pending.value, false, pending.isPwd);
                    }

                    function resolveEventEl(e) {
                        if (e && typeof e.composedPath === 'function') {
                            var path = e.composedPath();
                            if (path && path.length > 0 && path[0] && path[0].tagName) return path[0];
                        }
                        return e ? e.target : null;
                    }

                    inst.onFocusIn = function(e) {
                        try {
                            if (inst.destroyed || !inst.active) return;
                            var el = resolveEventEl(e);
                            if (!el || !el.tagName) return;
                            if (inst.pendingInput && inst.pendingInput.el && inst.pendingInput.el !== el) {
                                flushPendingInput(null);
                            }
                            var tag = el.tagName.toUpperCase();
                            var role = (el.getAttribute && el.getAttribute('role') || '').toLowerCase();
                            var isEditableOrSelect = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' ||
                                role === 'combobox' || role === 'listbox' || role === 'spinbutton' ||
                                Boolean(el.isContentEditable);
                            if (isEditableOrSelect) {
                                var type = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
                                if (type !== 'checkbox' && type !== 'radio' && type !== 'submit' && type !== 'button') {
                                    var lbl = getLabel(el) + ' field';
                                    var sel = buildLocatorBundle(el).selector;
                                    var focusKey = lbl + '|' + sel;
                                    if (inst.lastFocusKey === focusKey) return;
                                    inst.lastFocusKey = focusKey;
                                    emit(e, 'SELECT', lbl, el, '', false, false);
                                }
                            }
                        } catch (_) {}
                    };

                    inst.onInput = function(e) {
                        try {
                            if (inst.destroyed || !inst.active) return;
                            var el = resolveEventEl(e);
                            if (!el || !el.tagName) return;
                            var tag = el.tagName.toUpperCase();
                            var type = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
                            if (type === 'checkbox' || type === 'radio' || type === 'submit' || type === 'button' || type === 'hidden') return;
                            var isEditable = tag === 'INPUT' || tag === 'TEXTAREA' || Boolean(el.isContentEditable);
                            if (!isEditable) return;
                            inst.lastFocusKey = '';
                            highlightRecordedNode(el);
                            var isPwd = type === 'password' || isSensitiveNode(el);
                            var rawVal = (tag === 'INPUT' || tag === 'TEXTAREA')
                                ? String(el.value || '')
                                : String(el.innerText || el.textContent || '');
                            var safeVal = isPwd ? '' : rawVal.slice(0, 64);
                            inst.pendingInput = { el: el, value: safeVal, isPwd: isPwd };
                            if (inst.inputTimer) {
                                try { clearTimeout(inst.inputTimer); } catch (_) {}
                            }
                            inst.inputTimer = setTimeout(function() {
                                flushPendingInput(null);
                            }, 350);
                        } catch (_) {}
                    };

                    inst.onFocusOut = function(e) {
                        try {
                            if (inst.destroyed || !inst.active) return;
                            if (inst.pendingInput) {
                                flushPendingInput(null);
                            }
                        } catch (_) {}
                    };

                    inst.onChange = function(e) {
                        try {
                            if (inst.destroyed || !inst.active) return;
                            var el = resolveEventEl(e);
                            if (!el || !el.tagName) return;
                            if (inst.inputTimer) {
                                try { clearTimeout(inst.inputTimer); } catch (_) {}
                                inst.inputTimer = null;
                            }
                            if (inst.pendingInput && inst.pendingInput.el === el) {
                                inst.pendingInput = null;
                            } else if (inst.pendingInput) {
                                flushPendingInput(null);
                            }
                            inst.lastFocusKey = '';
                            var tag = el.tagName.toUpperCase();
                            var type = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
                            if (type === 'checkbox' || type === 'radio') {
                                emit(
                                    e,
                                    'TOGGLE',
                                    getLabel(el) + ' ' + (type === 'checkbox' ? 'checkbox' : 'option'),
                                    el,
                                    el.checked ? 'checked' : 'unchecked',
                                    false,
                                    false
                                );
                            } else if (tag === 'SELECT') {
                                emit(e, 'SELECT', getLabel(el), el, el.value || '', false, false);
                            } else if (tag === 'INPUT' || tag === 'TEXTAREA') {
                                var isPwd = type === 'password' || isSensitiveNode(el);
                                var safeVal = isPwd ? '' : String(el.value || '').slice(0, 64);
                                if (inst.lastEmittedInput &&
                                    inst.lastEmittedInput.el === el &&
                                    inst.lastEmittedInput.value === safeVal) {
                                    return;
                                }
                                inst.lastEmittedInput = { el: el, value: safeVal };
                                emit(e, 'INPUT', getLabel(el), el, safeVal, false, isPwd);
                            }
                        } catch (_) {}
                    };

                    inst.onClick = function(e) {
                        try {
                            if (inst.destroyed || !inst.active) return;
                            var rawTarget = resolveEventEl(e);
                            if (inst.pendingInput && (!rawTarget || inst.pendingInput.el !== rawTarget)) {
                                flushPendingInput(null);
                            }
                            var widgetSelector = 'button, a, summary, [role="button"], [role="link"], [role="tab"], [role="menuitem"], [role="menuitemradio"], [role="menuitemcheckbox"], [role="option"], [role="combobox"], [role="listbox"], [role="radio"], [role="switch"], [role="gridcell"], [aria-haspopup], [aria-pressed], [data-date], [data-day], [data-month], [data-year], [data-datepicker], [data-timepicker], [data-calendar], [data-dial-code], [data-country-code], [data-country], [data-code], [data-gender], [data-value], [data-option], [onclick], input[type="submit"], input[type="button"], label';
                            var el = rawTarget && rawTarget.closest
                                ? (rawTarget.closest(widgetSelector) || rawTarget)
                                : rawTarget;
                            if (!el || !el.tagName) return;
                            var tag = el.tagName.toUpperCase();
                            var type = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
                            var role = (el.getAttribute && el.getAttribute('role') || '').toLowerCase();
                            var hasWidgetAttr = Boolean(
                                el.hasAttribute && (
                                    el.hasAttribute('aria-haspopup') || el.hasAttribute('aria-pressed') ||
                                    el.hasAttribute('data-date') || el.hasAttribute('data-day') ||
                                    el.hasAttribute('data-month') || el.hasAttribute('data-year') ||
                                    el.hasAttribute('data-datepicker') || el.hasAttribute('data-timepicker') ||
                                    el.hasAttribute('data-calendar') || el.hasAttribute('data-dial-code') ||
                                    el.hasAttribute('data-country-code') || el.hasAttribute('data-country') ||
                                    el.hasAttribute('data-code') || el.hasAttribute('data-gender') ||
                                    el.hasAttribute('data-value') || el.hasAttribute('data-option') ||
                                    el.hasAttribute('onclick')
                                )
                            );
                            var isHiddenToggleLabel = false;
                            if (tag === 'LABEL') {
                                var assocCtrl = null;
                                var lblFor = el.getAttribute && el.getAttribute('for');
                                var rootNode = (el.getRootNode && el.getRootNode()) || document;
                                if (lblFor) {
                                    try { assocCtrl = rootNode.querySelector('#' + escCss(lblFor)) || document.getElementById(lblFor); } catch (_) {}
                                }
                                if (!assocCtrl && el.querySelector) {
                                    assocCtrl = el.querySelector('input[type="radio"], input[type="checkbox"]');
                                }
                                if (assocCtrl) {
                                    var assocType = (assocCtrl.getAttribute && assocCtrl.getAttribute('type') || '').toLowerCase();
                                    if ((assocType === 'radio' || assocType === 'checkbox') && !isNodeVisible(assocCtrl)) {
                                        isHiddenToggleLabel = true;
                                    }
                                }
                            }
                            var clsStr = String(el.className || '').toLowerCase();
                            var isCustomPickerOrOption = /(?:^|[\s_-])(select-trigger|select-selection|ant-select|el-select|mui-select|picker-item|select-option|dropdown-item|year-item)(?:$|[\s_-])/.test(clsStr) ||
                                ((tag === 'LI' || tag === 'DIV' || tag === 'SPAN') && el.children && el.children.length === 0 && /^(19|20)\d{2}$/.test(String(el.innerText || el.textContent || '').trim()) &&
                                    Boolean(el.closest && el.closest('ul, ol, [role="listbox"], [role="dialog"], [role="menu"], [class*="select"], [class*="picker"], [class*="dropdown"], [class*="popup"]')));
                            var isSemanticClickable = tag === 'BUTTON' || tag === 'A' || tag === 'SUMMARY' ||
                                role === 'button' || role === 'link' || role === 'tab' || role === 'menuitem' ||
                                role === 'menuitemradio' || role === 'menuitemcheckbox' || role === 'option' ||
                                role === 'combobox' || role === 'listbox' ||
                                role === 'radio' || role === 'switch' || role === 'gridcell' ||
                                type === 'submit' || type === 'button' || hasWidgetAttr || isHiddenToggleLabel || isCustomPickerOrOption;
                            if (isSemanticClickable) {
                                inst.lastFocusKey = '';
                                var lbl = getLabel(el);
                                var lower = lbl.toLowerCase();
                                var destructive = /delete|remove|pay|purchase|checkout|submit|confirm|reset|clear/.test(lower);
                                var act = type === 'submit' ? 'SUBMIT' : ((role === 'radio' || role === 'switch' || role === 'menuitemradio' || role === 'menuitemcheckbox' || isHiddenToggleLabel) ? 'TOGGLE' : 'CLICK');
                                emit(
                                    e,
                                    act,
                                    lbl + (tag === 'BUTTON' ? ' button' : ''),
                                    el,
                                    '',
                                    destructive,
                                    false
                                );
                            }
                        } catch (_) {}
                    };

                    window.__apexRecorderInstance = inst;
                    window.__apexRecordingActive = true;
                    window.__apexRecorderInstalled = true;
                    document.addEventListener('focusin', inst.onFocusIn, true);
                    document.addEventListener('focusout', inst.onFocusOut, true);
                    document.addEventListener('input', inst.onInput, true);
                    document.addEventListener('change', inst.onChange, true);
                    document.addEventListener('click', inst.onClick, true);

                    return JSON.stringify({
                        ok: true,
                        sessionId: inst.sessionId,
                        tabId: inst.tabId,
                        origin: actualOrigin
                    });
                } catch (err) {
                    return JSON.stringify({
                        ok: false,
                        reason: 'exception',
                        message: String(err && err.message || err || 'Install error')
                    });
                }
            })();
        """.trimIndent()
    }

    internal fun buildRecorderUninstallScript(expectedSessionId: String? = null): String {
        val qSessionId = JSONObject.quote(expectedSessionId?.trim().orEmpty())
        return """
            (function() {
                try {
                    var targetSession = $qSessionId;
                    var inst = window.__apexRecorderInstance;
                    var removed = false;
                    if (inst) {
                        if (!targetSession || inst.sessionId === targetSession) {
                            if (typeof inst.cleanup === 'function') {
                                inst.cleanup();
                            }
                            window.__apexRecorderInstance = null;
                            window.__apexRecordingActive = false;
                            window.__apexRecorderInstalled = false;
                            removed = true;
                        }
                    } else {
                        window.__apexRecordingActive = false;
                        window.__apexRecorderInstalled = false;
                    }
                    return JSON.stringify({ ok: true, removed: removed });
                } catch (err) {
                    return JSON.stringify({
                        ok: false,
                        reason: 'exception',
                        message: String(err && err.message || err || 'Uninstall error')
                    });
                }
            })();
        """.trimIndent()
    }

    suspend fun installRecorderScript(
        webView: WebView?,
        sessionId: String,
        tabId: String,
        expectedOrigin: String,
        initialSequenceNumber: Long = 0L
    ): RecorderLifecycleResult {
        if (webView == null) {
            return RecorderLifecycleResult(
                success = false,
                errorCode = AutomationErrorCode.RECORDING_DOCUMENT_UNAVAILABLE,
                message = "WebView is unavailable for recording."
            )
        }
        val cleanSessionId = sessionId.trim()
        val cleanTabId = tabId.trim()
        val parsedOrigin = parseOrigin(expectedOrigin)
        if (cleanSessionId.isEmpty() || cleanTabId.isEmpty() || parsedOrigin !is RecordingOriginResult.Valid) {
            return RecorderLifecycleResult(
                success = false,
                errorCode = AutomationErrorCode.RECORDING_DOCUMENT_UNAVAILABLE,
                message = "Invalid recording session or website origin."
            )
        }
        val script = buildRecorderInstallScript(
            sessionId = cleanSessionId,
            tabId = cleanTabId,
            expectedOrigin = parsedOrigin.canonicalOrigin,
            initialSequenceNumber = initialSequenceNumber
        )
        return when (val eval = evalJsWithResult(webView, script)) {
            is JsEvalResult.Success -> {
                val json = parseEvalJsonObject(eval.rawValue)
                if (json == null) {
                    RecorderLifecycleResult(
                        success = false,
                        errorCode = AutomationErrorCode.RECORDING_INSTALL_FAILED,
                        message = "Recorder confirmation response was invalid."
                    )
                } else {
                    val ok = json.optBoolean("ok", false)
                    val confirmedSession = json.optString("sessionId", "")
                    if (ok && confirmedSession == cleanSessionId) {
                        RecorderLifecycleResult(success = true)
                    } else {
                        val reason = json.optString("reason", "")
                        val errCode = if (reason == "origin_mismatch") {
                            AutomationErrorCode.RECORDING_ORIGIN_CHANGED
                        } else {
                            AutomationErrorCode.RECORDING_INSTALL_FAILED
                        }
                        RecorderLifecycleResult(
                            success = false,
                            errorCode = errCode,
                            message = json.optString("message", "").ifBlank {
                                if (reason == "origin_mismatch") {
                                    "Page origin changed before recorder could attach."
                                } else {
                                    "Recorder failed to attach to webpage."
                                }
                            }
                        )
                    }
                }
            }
            is JsEvalResult.Timeout -> RecorderLifecycleResult(
                success = false,
                errorCode = AutomationErrorCode.RECORDING_INSTALL_FAILED,
                message = "Recorder installation timed out."
            )
            is JsEvalResult.Unavailable, is JsEvalResult.Failure -> RecorderLifecycleResult(
                success = false,
                errorCode = AutomationErrorCode.RECORDING_INSTALL_FAILED,
                message = "Recorder installation failed in WebView."
            )
        }
    }

    suspend fun uninstallRecorderScript(
        webView: WebView?,
        sessionId: String? = null
    ): RecorderLifecycleResult {
        if (webView == null) {
            return RecorderLifecycleResult(
                success = false,
                errorCode = AutomationErrorCode.RECORDING_CLEANUP_FAILED,
                message = "WebView unavailable during recorder cleanup."
            )
        }
        val script = buildRecorderUninstallScript(sessionId)
        return when (val eval = evalJsWithResult(webView, script)) {
            is JsEvalResult.Success -> {
                val json = parseEvalJsonObject(eval.rawValue)
                if (json?.optBoolean("ok", false) == true) {
                    RecorderLifecycleResult(success = true)
                } else {
                    RecorderLifecycleResult(
                        success = false,
                        errorCode = AutomationErrorCode.RECORDING_CLEANUP_FAILED,
                        message = "Recorder cleanup returned unexpected response."
                    )
                }
            }
            is JsEvalResult.Timeout, is JsEvalResult.Unavailable, is JsEvalResult.Failure -> {
                RecorderLifecycleResult(
                    success = false,
                    errorCode = AutomationErrorCode.RECORDING_CLEANUP_FAILED,
                    message = "Failed to uninstall recorder script."
                )
            }
        }
    }

    fun detachRecorderBestEffort(webView: WebView?, sessionId: String? = null) {
        if (webView == null) return
        val script = buildRecorderUninstallScript(sessionId)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { webView.evaluateJavascript(script, null) }
        } else {
            mainHandler.post {
                runCatching { webView.evaluateJavascript(script, null) }
            }
        }
    }

    internal fun parseEvalJsonObject(rawValue: String): JSONObject? {
        if (rawValue.isBlank() || rawValue == "null") return null
        return runCatching {
            val decoded = JSONTokener(rawValue).nextValue()
            val text = when (decoded) {
                is String -> decoded
                is JSONObject -> return@runCatching decoded
                else -> rawValue
            }
            if (text.isBlank()) null else JSONObject(text)
        }.getOrNull()
    }

    fun parseInstructionsToSteps(
        rawInstructions: String,
        currentUrl: String,
        inspectionState: PageInspectionState? = null
    ): List<AutomationStep> {
        val lines = rawInstructions
            .split(Regex("[\n;]+|\\bthen\\b", RegexOption.IGNORE_CASE))
            .map { it.trim().removePrefix("-").removePrefix("•").replace(Regex("^\\d+[.)]\\s*"), "").trim() }
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) return emptyList()

        return lines.mapIndexed { idx, clause ->
            val lower = clause.lowercase()
            val isDestructive = isClauseDestructive(lower)
            val actionType = when {
                lower.startsWith("go to ") || lower.startsWith("open ") || lower.startsWith("navigate ") || lower.contains("http://") || lower.contains("https://") ->
                    AutomationActionType.NAVIGATE
                lower.startsWith("wait ") || lower.contains("wait until ") || lower.contains("wait for ") ->
                    AutomationActionType.WAIT
                lower.startsWith("clear ") || lower.startsWith("empty ") || lower.contains("clear field") ->
                    AutomationActionType.CLEAR
                lower.startsWith("focus ") || lower.startsWith("focus on ") ->
                    AutomationActionType.FOCUS
                lower.startsWith("blur ") || lower.startsWith("unfocus ") ->
                    AutomationActionType.BLUR
                lower.startsWith("press key ") || lower.startsWith("key press ") || Regex("^press\\s+(enter|return|escape|esc|tab|space|backspace|arrow\\w+)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower) ->
                    AutomationActionType.KEY_PRESS
                lower.contains("type ") || lower.contains("enter ") || lower.contains("fill ") || lower.contains("input ") || lower.contains("set date") ->
                    AutomationActionType.INPUT
                lower.contains("check ") || lower.contains("uncheck ") || lower.contains("toggle ") || lower.contains("checkbox") ->
                    AutomationActionType.TOGGLE
                lower.contains("select ") || lower.contains("choose ") || lower.contains("dropdown") ->
                    AutomationActionType.SELECT
                lower.contains("scroll") ->
                    AutomationActionType.SCROLL
                lower.contains("extract") || lower.contains("scrape") || lower.contains("collect") || lower.contains("summarize") ->
                    AutomationActionType.EXTRACT
                lower.contains("submit") || lower.contains("confirm") || lower.contains("delete") ->
                    AutomationActionType.SUBMIT
                else -> AutomationActionType.CLICK
            }

            val extractedValue = extractQuotedOrAfterKeyword(clause)
            val rawTargetLabel = deriveTargetLabel(clause, actionType, extractedValue)
            val candidateMatch = if (inspectionState != null &&
                inspectionState.elements.isNotEmpty() &&
                actionType != AutomationActionType.NAVIGATE &&
                actionType != AutomationActionType.SCROLL
            ) {
                universalScanner.matchCandidatesForInstruction(
                    clause = clause,
                    actionType = actionType,
                    targetLabel = rawTargetLabel,
                    inspectionState = inspectionState
                )
            } else {
                null
            }

            val primaryCandidate = candidateMatch?.primaryCandidate
            val resolvedLabel = if (primaryCandidate != null && !candidateMatch.isAmbiguous) {
                primaryCandidate.accessibleName.ifBlank { primaryCandidate.label.ifBlank { rawTargetLabel } }
            } else {
                rawTargetLabel
            }
            val resolvedSelector = when {
                primaryCandidate != null && !candidateMatch.isAmbiguous && primaryCandidate.selector.isNotBlank() ->
                    primaryCandidate.selector
                actionType == AutomationActionType.WAIT -> ""
                else -> inferSelector(actionType, resolvedLabel, lower)
            }

            val isSensitivePassword = primaryCandidate?.sensitiveCategory == SensitiveFieldCategory.PASSWORD ||
                (actionType == AutomationActionType.INPUT && lower.contains("password"))
            val safeValue = if (isSensitivePassword) "" else extractedValue
            val expectedOrig = if (isSensitivePassword) parseOrigin(currentUrl).canonicalOrigin else ""
            val desiredChecked = if (actionType == AutomationActionType.TOGGLE) {
                when {
                    lower.contains("uncheck") || lower.contains("deselect") || lower.contains("off") -> false
                    lower.contains("check") || lower.contains("select") || lower.contains("enable") -> true
                    else -> null
                }
            } else {
                null
            }
            val parsedKey = if (actionType == AutomationActionType.KEY_PRESS) {
                parseKeyboardKeyFromClause(clause, extractedValue)
            } else {
                ""
            }
            val waitSpec = if (actionType == AutomationActionType.WAIT) {
                parseWaitConditionFromClause(
                    clause = clause,
                    targetLabel = resolvedLabel,
                    selector = if (candidateMatch?.isAmbiguous == false) primaryCandidate?.selector.orEmpty() else "",
                    extractedValue = extractedValue
                )
            } else {
                null
            }

            AutomationStep(
                id = "inst_${UUID.randomUUID()}",
                order = idx + 1,
                actionType = actionType,
                targetLabel = resolvedLabel,
                selector = resolvedSelector,
                value = if (actionType == AutomationActionType.KEY_PRESS && safeValue.isBlank()) parsedKey else safeValue,
                description = clause,
                isDestructive = isDestructive,
                credentialFieldRole = if (isSensitivePassword) CredentialFieldRole.PASSWORD else null,
                expectedOrigin = expectedOrig,
                requiresCredential = isSensitivePassword,
                locatorCandidates = if (candidateMatch?.isAmbiguous == false) {
                    primaryCandidate?.locatorCandidates.orEmpty()
                } else {
                    emptyList()
                },
                controlKindHint = if (candidateMatch?.isAmbiguous == false) primaryCandidate?.controlKind else null,
                dropdownKindHint = if (candidateMatch?.isAmbiguous == false) primaryCandidate?.dropdownKind else null,
                waitCondition = waitSpec,
                keyboardKey = parsedKey,
                desiredCheckedState = desiredChecked,
                framePath = if (candidateMatch?.isAmbiguous == false) primaryCandidate?.framePath.orEmpty() else emptyList(),
                shadowHostPath = if (candidateMatch?.isAmbiguous == false) primaryCandidate?.shadowHostPath.orEmpty() else emptyList()
            )
        }
    }

    private fun parseKeyboardKeyFromClause(clause: String, extractedValue: String): String {
        if (extractedValue.isNotBlank()) return extractedValue
        val match = Regex("""\b(enter|return|escape|esc|tab|space|backspace|arrowdown|arrowup|arrowleft|arrowright|down|up|left|right)\b""", RegexOption.IGNORE_CASE)
            .find(clause)
        return match?.value?.replaceFirstChar { it.uppercaseChar() } ?: "Enter"
    }

    private fun parseWaitConditionFromClause(
        clause: String,
        targetLabel: String,
        selector: String,
        extractedValue: String
    ): WaitConditionSpec {
        val lower = clause.lowercase()
        val type = when {
            lower.contains("disappear") || lower.contains("gone") || lower.contains("hide") || lower.contains("hidden") ->
                WaitConditionType.ELEMENT_DISAPPEARS
            lower.contains("interactable") || lower.contains("enabled") || lower.contains("clickable") ->
                WaitConditionType.ELEMENT_INTERACTABLE
            lower.contains("navigation") || lower.contains("page load") ->
                WaitConditionType.NAVIGATION_COMPLETED
            lower.contains("origin change") ->
                WaitConditionType.ORIGIN_CHANGE
            lower.contains("url change") || lower.contains("url contains") ->
                WaitConditionType.URL_CHANGE
            lower.contains("stabilize") || lower.contains("stable") || lower.contains("settle") ->
                WaitConditionType.CONTENT_STABILIZED
            lower.contains("text") || lower.contains("state") || lower.contains("attribute") ->
                WaitConditionType.DOM_STATE_CHANGE
            else -> WaitConditionType.ELEMENT_APPEARS
        }
        return WaitConditionSpec(
            type = type,
            selector = selector,
            targetLabel = targetLabel,
            expectedUrlContains = if (type == WaitConditionType.URL_CHANGE) extractedValue else "",
            expectedText = if (type == WaitConditionType.DOM_STATE_CHANGE) extractedValue else ""
        )
    }

    fun isClauseDestructive(lowerClause: String): Boolean {
        val keywords = listOf(
            "delete", "remove", "erase", "wipe", "clear all",
            "purchase", "buy now", "checkout", "pay ", "payment",
            "submit", "transfer", "send money", "confirm order", "unsubscribe"
        )
        return keywords.any { kw -> lowerClause.contains(kw) }
    }

    private fun extractQuotedOrAfterKeyword(clause: String): String {
        val quoted = Regex("""["']([^"']+)["']""").find(clause)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted
        val urlMatch = Regex("""(https?://\S+|apex://\S+)""", RegexOption.IGNORE_CASE).find(clause)
        if (urlMatch != null) return urlMatch.groupValues[1]
        return ""
    }

    private fun deriveTargetLabel(
        clause: String,
        actionType: AutomationActionType,
        extractedValue: String
    ): String {
        return when (actionType) {
            AutomationActionType.NAVIGATE -> extractedValue.ifBlank {
                clause.replace(Regex("^(go to|open|navigate to|navigate)\\s+", RegexOption.IGNORE_CASE), "").trim()
            }
            AutomationActionType.SCROLL -> if (clause.contains("up", ignoreCase = true)) "up" else "down"
            else -> {
                var cleaned = clause
                    .replace(Regex("""["'][^"']+["']"""), "")
                    .replace(
                        Regex(
                            "^(click|tap|press key|press|enter|type|fill|clear|empty|focus|blur|unfocus|wait until|wait for|wait|select|choose|toggle|check|uncheck|extract|submit)\\s+(on\\s+|the\\s+|for\\s+)?",
                            RegexOption.IGNORE_CASE
                        ),
                        ""
                    )
                    .trim()
                if (extractedValue.isNotBlank()) {
                    cleaned = cleaned.replace(
                        Regex("^(?:in|into|from|on|at)\\s+(?:the\\s+)?", RegexOption.IGNORE_CASE),
                        ""
                    ).trim()
                }
                cleaned.ifBlank { clause.take(40) }
            }
        }
    }

    private fun inferSelector(
        actionType: AutomationActionType,
        targetLabel: String,
        lowerClause: String
    ): String {
        return when (actionType) {
            AutomationActionType.INPUT, AutomationActionType.CLEAR -> when {
                lowerClause.contains("email") -> "input[type=\"email\"], input[name*=\"mail\" i]"
                lowerClause.contains("password") -> "input[type=\"password\"]"
                lowerClause.contains("country code") || lowerClause.contains("dial code") ->
                    "input[autocomplete=\"tel-country-code\"], [data-dial-code], [data-country-code], input[type=\"search\"], input[placeholder*=\"country\" i]"
                lowerClause.contains("phone") || lowerClause.contains("mobile") || lowerClause.contains("tel") ->
                    "input[type=\"tel\"], input[inputmode=\"tel\"], input[autocomplete*=\"tel\" i], input[name*=\"phone\" i]"
                lowerClause.contains("date") || lowerClause.contains("birth") || lowerClause.contains("dob") || lowerClause.contains("year") || lowerClause.contains("age") ->
                    "input[type=\"date\"], input[autocomplete*=\"bday\" i], [data-datepicker], [data-date], [data-year], input:not([type=\"hidden\"]):not([type=\"submit\"]):not([type=\"button\"]), select, [role=\"combobox\"]"
                lowerClause.contains("search") -> "input[type=\"search\"], input[name*=\"q\" i], input[name*=\"search\" i]"
                else -> "input:not([type=\"hidden\"]), textarea"
            }
            AutomationActionType.TOGGLE -> when {
                lowerClause.contains("gender") || lowerClause.contains("sex") ->
                    "[role=\"radio\"], input[type=\"radio\"], label[for], [aria-pressed]"
                else -> "input[type=\"checkbox\"], input[type=\"radio\"], [role=\"checkbox\"], [role=\"radio\"], [role=\"switch\"], [aria-pressed]"
            }
            AutomationActionType.SELECT -> when {
                lowerClause.contains("country code") || lowerClause.contains("dial code") ->
                    "[data-country-code], [data-dial-code], [role=\"option\"], [aria-haspopup], select"
                lowerClause.contains("gender") || lowerClause.contains("sex") ->
                    "select[autocomplete=\"sex\"], select, [role=\"radiogroup\"] [role=\"radio\"], [role=\"combobox\"], [role=\"listbox\"], [role=\"option\"], [aria-haspopup]"
                lowerClause.contains("date") || lowerClause.contains("birth") || lowerClause.contains("calendar") || lowerClause.contains("month") || lowerClause.contains("day") || lowerClause.contains("year") || lowerClause.contains("age") ->
                    "input[type=\"date\"], select[autocomplete*=\"bday\" i], input[autocomplete*=\"bday\" i], [role=\"gridcell\"], [data-date], [data-day], [data-year], [aria-haspopup], select, [role=\"combobox\"], input:not([type=\"hidden\"]):not([type=\"submit\"]):not([type=\"button\"])"
                else -> "select, [role=\"combobox\"], [role=\"listbox\"], [role=\"option\"], [aria-haspopup], [role=\"gridcell\"]"
            }
            AutomationActionType.SUBMIT -> "button[type=\"submit\"], input[type=\"submit\"], button"
            AutomationActionType.CLICK -> when {
                lowerClause.contains("country code") || lowerClause.contains("dial code") ->
                    "[data-country-code], [data-dial-code], [role=\"option\"], button[aria-haspopup], [aria-haspopup]"
                lowerClause.contains("calendar") || lowerClause.contains("date") ->
                    "[role=\"gridcell\"], [data-date], [data-day], [data-datepicker], button, [role=\"button\"]"
                else -> "button, a, [role=\"button\"], [role=\"option\"], [role=\"radio\"], [role=\"gridcell\"], [aria-haspopup]"
            }
            AutomationActionType.FOCUS, AutomationActionType.BLUR, AutomationActionType.KEY_PRESS ->
                "input:not([type=\"hidden\"]), textarea, select, button, a[href], [contenteditable=\"true\"]"
            AutomationActionType.EXTRACT -> "article, h1, h2, h3, p, a"
            else -> ""
        }
    }

    suspend fun executeWorkflow(
        workflow: AutomationWorkflowEntity,
        steps: List<AutomationStep>,
        currentTabUrl: String,
        webViewProvider: () -> WebView?,
        onNavigateUrl: (String) -> Unit,
        onStepProgress: (stepIndex: Int, logMessage: String) -> Unit,
        tabId: String = "",
        navigationSynchronizer: TabNavigationSynchronizer? = null,
        onStepCompleted: (Int) -> Unit = {},
        credentialStepExecutor: (suspend (AutomationStep, String) -> StepExecutionResult)? = null,
        lifecycleController: ExecutionLifecycleController? = null,
        isTabOpenProvider: () -> Boolean = { true },
        liveUrlProvider: (() -> String)? = null,
        onStepDetailedProgress: ((stepIndex: Int, step: AutomationStep, recoveryNotice: String, stepRecords: List<StepExecutionRecord>) -> Unit)? = null,
        workflowDocument: WorkflowDocument? = null,
        initialCheckpoint: PersistedExecutionCheckpoint? = null,
        workflowLookup: (suspend (Long) -> Pair<String, WorkflowDocument>?)? = null,
        confirmedUnsafeRetry: Boolean = false,
        executionId: Long = 0L,
        onPhase6Progress: ((Phase6LiveProgress) -> Unit)? = null
    ): WorkflowExecutionOutcome {
        val doc = workflowDocument ?: WorkflowDocument(steps = steps)
        return Phase6ExecutionCoordinator(this).executeDocumentWorkflow(
            workflow = workflow,
            document = doc,
            currentTabUrl = currentTabUrl,
            webViewProvider = webViewProvider,
            onNavigateUrl = onNavigateUrl,
            onStepProgress = onStepProgress,
            tabId = tabId,
            navigationSynchronizer = navigationSynchronizer,
            onStepCompleted = onStepCompleted,
            credentialStepExecutor = credentialStepExecutor,
            lifecycleController = lifecycleController,
            isTabOpenProvider = isTabOpenProvider,
            liveUrlProvider = liveUrlProvider,
            onStepDetailedProgress = onStepDetailedProgress,
            initialCheckpoint = initialCheckpoint,
            workflowLookup = workflowLookup,
            confirmedUnsafeRetry = confirmedUnsafeRetry,
            executionId = executionId,
            onPhase6Progress = onPhase6Progress
        )
    }

    private fun isSensitiveStepDescriptor(step: AutomationStep): Boolean {
        if (step.isCredentialStep) return true
        val descriptor = "${step.targetLabel} ${step.description} ${step.selector}".lowercase()
        return Regex("""\b(password|passcode|pin|cvv|cvc|csc|secret|token|otp|ssn)\b""")
            .containsMatchIn(descriptor)
    }

    internal fun sanitizeExecutionMessage(rawMsg: String, step: AutomationStep): String {
        var clean = rawMsg.ifBlank { step.description }
        if (isSensitiveStepDescriptor(step)) {
            if (step.value.isNotBlank()) {
                clean = clean.replace(step.value, "••••")
            }
            clean = clean.replace(Regex("[\"'`][^\"'`]{2,}[\"'`]"), "••••")
        }
        return clean
    }

    internal suspend fun executeSingleStepWithRecovery(
        step: AutomationStep,
        activeUrl: String,
        tabId: String = "",
        navigationSynchronizer: TabNavigationSynchronizer? = null,
        nextStep: AutomationStep? = null,
        webViewProvider: () -> WebView?,
        onNavigateUrl: (String) -> Unit,
        credentialStepExecutor: (suspend (AutomationStep, String) -> StepExecutionResult)? = null
    ): StepExecutionResult {
        val initial = executeSingleStep(
            step = step,
            activeUrl = activeUrl,
            tabId = tabId,
            navigationSynchronizer = navigationSynchronizer,
            nextStep = nextStep,
            webViewProvider = webViewProvider,
            onNavigateUrl = onNavigateUrl,
            credentialStepExecutor = credentialStepExecutor
        )
        if (initial.success ||
            step.isCredentialStep ||
            step.actionType == AutomationActionType.NAVIGATE ||
            step.actionType == AutomationActionType.WAIT
        ) {
            return initial
        }

        val recoverableError = initial.errorCode == AutomationErrorCode.ELEMENT_NOT_FOUND ||
            initial.errorCode == AutomationErrorCode.ELEMENT_STALE
        if (!recoverableError) {
            return initial
        }

        val expectedOrigin = parseOrigin(activeUrl).canonicalOrigin
        val preSnap = if (tabId.isNotEmpty() && navigationSynchronizer != null) {
            navigationSynchronizer.snapshot(tabId)
        } else {
            null
        }
        val wv = webViewProvider() ?: return initial
        if (preSnap != null && preSnap.activeUrl.isNotBlank()) {
            val snapOrigin = parseOrigin(preSnap.activeUrl).canonicalOrigin
            if (expectedOrigin.isNotEmpty() && snapOrigin.isNotEmpty() && snapOrigin != expectedOrigin) {
                return StepExecutionResult(
                    success = false,
                    message = "Aborted stale locator recovery due to origin change ('$expectedOrigin' -> '$snapOrigin').",
                    errorCode = AutomationErrorCode.UNEXPECTED_ORIGIN_CHANGE,
                    locatorDiagnostics = initial.locatorDiagnostics
                )
            }
        }
        val epoch = preSnap?.epoch ?: 0L
        val freshInspection = universalScanner.scanPage(
            webView = wv,
            tabId = tabId,
            currentUrl = activeUrl,
            navigationEpoch = epoch,
            installDynamicObserver = false
        )
        if (freshInspection.elements.isEmpty()) {
            return initial
        }

        if (preSnap != null && navigationSynchronizer != null) {
            val postSnap = navigationSynchronizer.snapshot(tabId)
            val postOrigin = if (postSnap.activeUrl.isNotBlank()) parseOrigin(postSnap.activeUrl).canonicalOrigin else expectedOrigin
            if (expectedOrigin.isNotEmpty() && postOrigin.isNotEmpty() && postOrigin != expectedOrigin) {
                return StepExecutionResult(
                    success = false,
                    message = "Aborted stale locator recovery after origin shifted to '$postOrigin'.",
                    errorCode = AutomationErrorCode.UNEXPECTED_ORIGIN_CHANGE,
                    locatorDiagnostics = initial.locatorDiagnostics
                )
            }
            if (postSnap.epoch != preSnap.epoch) {
                return StepExecutionResult(
                    success = false,
                    message = "Stale locator recovery aborted: page navigation epoch changed (${preSnap.epoch} -> ${postSnap.epoch}).",
                    errorCode = AutomationErrorCode.ELEMENT_STALE,
                    locatorDiagnostics = initial.locatorDiagnostics
                )
            }
        }

        val match = universalScanner.matchCandidatesForInstruction(
            clause = step.description,
            actionType = step.actionType,
            targetLabel = step.targetLabel.ifBlank { step.description },
            inspectionState = freshInspection
        )
        if (match.isAmbiguous) {
            val candInfos = match.matchedElements.take(12).mapIndexed { idx, el ->
                el.toAmbiguousCandidateInfo(idx)
            }
            val diagnostics = LocatorResolutionDiagnostics(
                recordedLocator = step.selector,
                targetLabel = step.targetLabel,
                totalMatches = match.matchedElements.size,
                visibleMatches = candInfos.count { it.isVisible },
                interactableMatches = candInfos.count { it.isVisible && it.isEnabled },
                finalOutcome = "ELEMENT_AMBIGUOUS",
                attemptedCandidates = initial.locatorDiagnostics?.attemptedCandidates.orEmpty()
            )
            return StepExecutionResult(
                success = false,
                message = "Stale locator recovery found ${match.matchedElements.size} ambiguous candidates for '${step.targetLabel}'.",
                errorCode = AutomationErrorCode.ELEMENT_AMBIGUOUS,
                ambiguousCandidates = candInfos,
                locatorDiagnostics = diagnostics
            )
        }
        val recoveredEl = match.primaryCandidate ?: return initial
        if (recoveredEl.isLocatorAmbiguous || recoveredEl.selector.isBlank()) {
            return if (recoveredEl.isLocatorAmbiguous) {
                val candInfos = match.matchedElements.ifEmpty { listOf(recoveredEl) }
                    .take(12)
                    .mapIndexed { idx, el -> el.toAmbiguousCandidateInfo(idx) }
                StepExecutionResult(
                    success = false,
                    message = "Fresh scan locator for '${step.targetLabel}' is ambiguous.",
                    errorCode = AutomationErrorCode.ELEMENT_AMBIGUOUS,
                    ambiguousCandidates = candInfos,
                    locatorDiagnostics = LocatorResolutionDiagnostics(
                        recordedLocator = step.selector,
                        targetLabel = step.targetLabel,
                        totalMatches = candInfos.size.coerceAtLeast(1),
                        visibleMatches = candInfos.count { it.isVisible },
                        interactableMatches = candInfos.count { it.isVisible && it.isEnabled },
                        finalOutcome = "ELEMENT_AMBIGUOUS",
                        attemptedCandidates = initial.locatorDiagnostics?.attemptedCandidates.orEmpty()
                    )
                )
            } else {
                initial
            }
        }
        if (recoveredEl.selector == step.selector && recoveredEl.locatorCandidates == step.locatorCandidates) {
            return initial
        }

        val recoveredStep = step.copy(
            selector = recoveredEl.selector,
            locatorCandidates = recoveredEl.locatorCandidates,
            controlKindHint = step.controlKindHint ?: recoveredEl.controlKind,
            dropdownKindHint = step.dropdownKindHint ?: recoveredEl.dropdownKind,
            framePath = recoveredEl.framePath.ifEmpty { step.framePath },
            shadowHostPath = recoveredEl.shadowHostPath.ifEmpty { step.shadowHostPath }
        )
        val retryResult = executeSingleStep(
            step = recoveredStep,
            activeUrl = activeUrl,
            tabId = tabId,
            navigationSynchronizer = navigationSynchronizer,
            nextStep = nextStep,
            webViewProvider = webViewProvider,
            onNavigateUrl = onNavigateUrl,
            credentialStepExecutor = credentialStepExecutor
        )
        return if (retryResult.success) {
            retryResult.copy(
                recoveredViaFallback = true,
                resolvedSelector = retryResult.resolvedSelector.ifBlank { recoveredEl.selector }
            )
        } else {
            retryResult.copy(
                locatorDiagnostics = retryResult.locatorDiagnostics ?: initial.locatorDiagnostics
            )
        }
    }

    private fun DetectedPageElement.toAmbiguousCandidateInfo(idx: Int): AmbiguousCandidateInfo {
        val isSensitive = sensitiveCategory.isSensitive
        val attrs = LinkedHashMap<String, String>()
        relevantAttributes.forEach { (k, v) ->
            val lk = k.lowercase()
            val isBlocked = lk == "value" || lk == "style" ||
                lk.contains("password") || lk.contains("secret") ||
                lk.contains("token") || lk.contains("otp") ||
                lk.contains("cvv") || lk.contains("cvc") ||
                lk.contains("ssn") || lk == "pin" ||
                (isSensitive && lk == "placeholder")
            if (!isBlocked) {
                attrs[k] = v
            }
        }
        if (htmlId.isNotBlank() && LocatorQualityEvaluator.isStableId(htmlId)) attrs.putIfAbsent("id", htmlId)
        if (inputType.isNotBlank()) attrs.putIfAbsent("type", inputType)
        if (role.isNotBlank()) attrs.putIfAbsent("role", role)
        if (name.isNotBlank()) attrs.putIfAbsent("name", name)
        if (href.isNotBlank()) attrs.putIfAbsent("href", href)
        if (!isSensitive && placeholder.isNotBlank()) attrs.putIfAbsent("placeholder", placeholder)
        val bestSelector = locatorCandidates
            .sortedWith(compareByDescending<LocatorCandidate> { it.isUnique }.thenByDescending { it.confidence })
            .firstOrNull { it.selector.isNotBlank() }?.selector ?: selector
        return AmbiguousCandidateInfo(
            candidateIndex = idx,
            tagName = tagName,
            role = role,
            accessibleName = if (isSensitive) {
                accessibleName.ifBlank { name }.ifBlank { tagName }
            } else {
                accessibleName.ifBlank { label }
            },
            visibleText = if (isSensitive) "" else visibleText.ifBlank { label },
            attributes = attrs,
            parentContext = parentContainerSummary.ifBlank { fieldsetLegend },
            nearbyText = if (isSensitive) "" else nearbyText,
            framePath = framePath,
            shadowHostPath = shadowHostPath,
            isVisible = visible,
            isEnabled = !disabled,
            recommendedSelector = bestSelector
        )
    }

    internal suspend fun awaitWaitCondition(
        condition: WaitConditionSpec,
        tabId: String,
        baselineUrl: String,
        baselineOrigin: String,
        navigationSynchronizer: TabNavigationSynchronizer?,
        webViewProvider: () -> WebView?,
        lifecycleController: ExecutionLifecycleController? = null,
        isTabOpenProvider: () -> Boolean = { true },
        liveUrlProvider: (() -> String)? = null
    ): StepExecutionResult {
        val timeoutMs = condition.timeoutMs.coerceIn(100L, 60_000L)
        val deadline = System.currentTimeMillis() + timeoutMs
        val probeScript = UniversalActionScripts.buildWaitProbeScript(condition)
        var lastSignature = ""
        var stableSinceMs = 0L
        val pollMs = minOf(domStabilityPollMs, 80L).coerceAtLeast(25L)

        while (true) {
            coroutineContext.ensureActive()
            if (lifecycleController != null) {
                lifecycleController.awaitRunnableOrAbort()?.let { abort ->
                    return StepExecutionResult(
                        success = false,
                        message = abort.message,
                        errorCode = abort.code
                    )
                }
                val allowCross = condition.type == WaitConditionType.ORIGIN_CHANGE ||
                    condition.type == WaitConditionType.URL_CHANGE
                val liveUrlNow = liveUrlProvider?.invoke()?.takeIf { it.isNotBlank() } ?: baselineUrl
                lifecycleController.validateBoundContext(
                    currentWebView = webViewProvider(),
                    isTabStillOpen = isTabOpenProvider(),
                    liveUrl = liveUrlNow,
                    expectedOrigin = baselineOrigin,
                    allowCrossOrigin = allowCross,
                    originParser = { parseOrigin(it) }
                )?.let { abort ->
                    return StepExecutionResult(
                        success = false,
                        message = abort.message,
                        errorCode = abort.code
                    )
                }
            }

            if (tabId.isNotEmpty() && navigationSynchronizer != null) {
                val snap = navigationSynchronizer.snapshot(tabId)
                if (snap.phase == NavigationLifecyclePhase.FAILED) {
                    return StepExecutionResult(
                        success = false,
                        message = snap.failureReason ?: "Navigation failed while waiting.",
                        errorCode = AutomationErrorCode.NAVIGATION_FAILED
                    )
                }
                if (condition.type == WaitConditionType.NAVIGATION_COMPLETED &&
                    snap.phase == NavigationLifecyclePhase.FINISHED &&
                    snap.activeUrl.isNotBlank()
                ) {
                    return StepExecutionResult(
                        success = true,
                        message = "Wait satisfied: navigation completed (${extractDomain(snap.activeUrl)}).",
                        verified = true
                    )
                }
            }

            val wv = webViewProvider()
            if (wv == null) {
                return StepExecutionResult(
                    success = false,
                    message = "WebView became unavailable while waiting for ${condition.type.label}.",
                    errorCode = AutomationErrorCode.WEBVIEW_UNAVAILABLE
                )
            }

            when (val evalRes = evalJsWithResult(wv, probeScript, timeoutMs = minOf(jsEvalTimeoutMs, 1_500L))) {
                is JsEvalResult.Success -> {
                    val obj = parseEvalJsonObject(evalRes.rawValue)
                    if (obj != null) {
                        val satisfied = obj.optBoolean("satisfied", false)
                        val probeUrl = obj.optString("url", "")
                            .ifBlank { liveUrlProvider?.invoke().orEmpty() }
                            .ifBlank { navigationSynchronizer?.snapshot(tabId)?.activeUrl.orEmpty() }
                        val sig = obj.optString("signature", "")

                        when (condition.type) {
                            WaitConditionType.URL_CHANGE -> {
                                val changed = probeUrl.isNotBlank() && probeUrl != baselineUrl
                                val matchesFilter = condition.expectedUrlContains.isBlank() ||
                                    probeUrl.contains(condition.expectedUrlContains, ignoreCase = true)
                                if (changed && matchesFilter) {
                                    return StepExecutionResult(
                                        success = true,
                                        message = "Wait satisfied: URL changed to $probeUrl.",
                                        verified = true
                                    )
                                }
                            }
                            WaitConditionType.ORIGIN_CHANGE -> {
                                val probeOrig = parseOrigin(probeUrl).canonicalOrigin
                                val changed = probeOrig.isNotBlank() && probeOrig != baselineOrigin
                                val matchesExpected = condition.expectedOrigin.isBlank() ||
                                    parseOrigin(condition.expectedOrigin).canonicalOrigin == probeOrig
                                if (changed && matchesExpected) {
                                    return StepExecutionResult(
                                        success = true,
                                        message = "Wait satisfied: origin changed to $probeOrig.",
                                        verified = true
                                    )
                                }
                            }
                            WaitConditionType.CONTENT_STABILIZED -> {
                                val now = System.currentTimeMillis()
                                if (satisfied && sig.isNotBlank()) {
                                    if (sig == lastSignature) {
                                        if (stableSinceMs == 0L) stableSinceMs = now
                                        if (now - stableSinceMs >= condition.stabilizeQuietMs) {
                                            return StepExecutionResult(
                                                success = true,
                                                message = "Wait satisfied: page content stabilized.",
                                                verified = true
                                            )
                                        }
                                    } else {
                                        lastSignature = sig
                                        stableSinceMs = now
                                    }
                                }
                            }
                            WaitConditionType.NAVIGATION_COMPLETED -> {
                                if (satisfied && obj.optString("readyState") == "complete") {
                                    return StepExecutionResult(
                                        success = true,
                                        message = "Wait satisfied: document readyState complete.",
                                        verified = true
                                    )
                                }
                            }
                            WaitConditionType.ELEMENT_APPEARS,
                            WaitConditionType.ELEMENT_DISAPPEARS,
                            WaitConditionType.ELEMENT_INTERACTABLE,
                            WaitConditionType.DOM_STATE_CHANGE -> {
                                if (satisfied) {
                                    return StepExecutionResult(
                                        success = true,
                                        message = "Wait satisfied: ${condition.type.label} (${condition.targetLabel.ifBlank { condition.selector }}).",
                                        verified = true
                                    )
                                }
                            }
                        }
                    }
                }
                is JsEvalResult.Timeout -> {
                    // Continue within overall wait timeout unless deadline elapsed
                }
                is JsEvalResult.Unavailable -> {
                    return StepExecutionResult(
                        success = false,
                        message = "WebView became unavailable while waiting: ${evalRes.reason}",
                        errorCode = AutomationErrorCode.WEBVIEW_UNAVAILABLE
                    )
                }
                is JsEvalResult.Failure -> {
                    // Transient evaluation failure during navigation transition; retry until deadline
                }
            }

            if (System.currentTimeMillis() >= deadline) {
                return StepExecutionResult(
                    success = false,
                    message = "Timed out after ${timeoutMs}ms waiting for ${condition.type.label}${
                        condition.targetLabel.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    }.",
                    errorCode = AutomationErrorCode.WAIT_TIMEOUT
                )
            }
            if (lifecycleController != null) {
                lifecycleController.interruptibleDelay(pollMs)
            } else {
                delay(pollMs)
            }
        }
    }

    internal suspend fun executeSingleStep(
        step: AutomationStep,
        activeUrl: String,
        tabId: String = "",
        navigationSynchronizer: TabNavigationSynchronizer? = null,
        nextStep: AutomationStep? = null,
        webViewProvider: () -> WebView?,
        onNavigateUrl: (String) -> Unit,
        credentialStepExecutor: (suspend (AutomationStep, String) -> StepExecutionResult)? = null
    ): StepExecutionResult {
        coroutineContext.ensureActive()

        if (step.isCredentialStep) {
            if (credentialStepExecutor == null) {
                val errCode = if (step.credentialReferenceId.isNullOrBlank()) {
                    AutomationErrorCode.CREDENTIAL_UNASSIGNED
                } else {
                    AutomationErrorCode.CREDENTIAL_AUTH_REQUIRED
                }
                return StepExecutionResult(
                    success = false,
                    message = if (step.credentialReferenceId.isNullOrBlank()) {
                        "Step '${step.description}' requires a saved credential, but none is assigned."
                    } else {
                        "Step '${step.description}' requires explicit user authorization and an unlocked Credential Vault."
                    },
                    errorCode = errCode
                )
            }
            return credentialStepExecutor(step, activeUrl)
        }

        if (step.actionType == AutomationActionType.NAVIGATE) {
            val dest = step.value.ifBlank { step.targetLabel }.trim()
            if (dest.isBlank() || dest.startsWith("javascript:", ignoreCase = true)) {
                return StepExecutionResult(
                    success = false,
                    message = "Invalid or missing target URL for navigation step.",
                    errorCode = AutomationErrorCode.INVALID_URL
                )
            }
            return navigateAndAwaitSettlement(
                dest = dest,
                tabId = tabId,
                navigationSynchronizer = navigationSynchronizer,
                webViewProvider = webViewProvider,
                nextStep = nextStep,
                onNavigateUrl = onNavigateUrl
            )
        }

        val webView = webViewProvider()
        if (webView == null || activeUrl.equals("apex://home", ignoreCase = true)) {
            return StepExecutionResult(
                success = false,
                message = "No active webpage loaded in WebView to execute '${step.description}'.",
                errorCode = AutomationErrorCode.WEBVIEW_UNAVAILABLE
            )
        }

        val trimmedSelector = step.selector.trim()
        if (trimmedSelector.isNotEmpty()) {
            val validation = validateSelector(trimmedSelector)
            if (!validation.isValid) {
                return StepExecutionResult(
                    success = false,
                    message = "Unsafe or malformed CSS selector '$trimmedSelector': ${validation.reason}",
                    errorCode = AutomationErrorCode.INVALID_SELECTOR
                )
            }
        } else if (step.targetLabel.isBlank() && step.actionType != AutomationActionType.SCROLL) {
            return StepExecutionResult(
                success = false,
                message = "Step '${step.description}' is missing both selector and target label.",
                errorCode = AutomationErrorCode.INVALID_SELECTOR
            )
        }

        val selectorEsc = JSONObject.quote(trimmedSelector)
        val labelEsc = JSONObject.quote(step.targetLabel.trim())
        val valueEsc = JSONObject.quote(step.value)

        val script = buildStepExecutionScript(
            step = step,
            selectorEsc = selectorEsc,
            labelEsc = labelEsc,
            valueEsc = valueEsc
        )

        val effectiveTimeout = if (step.stepTimeoutMs > 0L) step.stepTimeoutMs else jsEvalTimeoutMs
        var dynamicAttempt = 0
        while (true) {
            when (val evalResult = evalJsWithResult(webView, script, timeoutMs = effectiveTimeout)) {
                is JsEvalResult.Timeout -> return StepExecutionResult(
                    success = false,
                    message = "WebView DOM timed out after ${evalResult.timeoutMs}ms for '${step.description}'.",
                    errorCode = AutomationErrorCode.JS_TIMEOUT
                )
                is JsEvalResult.Unavailable -> return StepExecutionResult(
                    success = false,
                    message = "WebView became unavailable while executing '${step.description}': ${evalResult.reason}",
                    errorCode = AutomationErrorCode.WEBVIEW_UNAVAILABLE
                )
                is JsEvalResult.Failure -> return StepExecutionResult(
                    success = false,
                    message = "WebView script evaluation failed for '${step.description}': ${evalResult.reason}",
                    errorCode = AutomationErrorCode.JS_EXCEPTION
                )
                is JsEvalResult.Success -> {
                    val isPendingDynamic = runCatching {
                        val raw = evalResult.rawValue
                        val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                            JSONTokener(raw).nextValue().toString()
                        } else {
                            raw
                        }
                        JSONObject(unquoted).optBoolean("pendingDynamic", false)
                    }.getOrDefault(false)
                    if (isPendingDynamic && dynamicAttempt < 4) {
                        dynamicAttempt++
                        delay(35L)
                        continue
                    }
                    return parseStepJsonResponse(evalResult.rawValue, step)
                }
            }
        }
    }

    internal fun buildStepExecutionScript(
        step: AutomationStep,
        selectorEsc: String = JSONObject.quote(step.selector.trim()),
        labelEsc: String = JSONObject.quote(step.targetLabel.trim()),
        valueEsc: String = JSONObject.quote(step.value)
    ): String {
        return UniversalActionScripts.buildStepExecutionScript(step)
    }

    private fun parseStepJsonResponse(raw: String, step: AutomationStep): StepExecutionResult {
        if (raw.isBlank() || raw == "null") {
            return StepExecutionResult(
                success = false,
                message = "WebView DOM did not respond for '${step.description}'.",
                errorCode = AutomationErrorCode.JS_EXCEPTION
            )
        }
        return runCatching {
            val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                JSONTokener(raw).nextValue().toString()
            } else {
                raw
            }
            val obj = JSONObject(unquoted)
            val ok = obj.optBoolean("ok", false)
            val rawCode = obj.optString("code", "")
            val parsedCode = runCatching {
                if (rawCode.isNotBlank()) AutomationErrorCode.valueOf(rawCode) else null
            }.getOrNull() ?: if (ok) AutomationErrorCode.NONE else AutomationErrorCode.JS_EXCEPTION
            val ambigArr = obj.optJSONArray("ambiguousCandidates")
            val ambigList = if (ambigArr != null) {
                buildList {
                    for (i in 0 until ambigArr.length()) {
                        AmbiguousCandidateInfo.fromJson(ambigArr.optJSONObject(i))?.let { add(it) }
                    }
                }
            } else emptyList()
            val diagnostics = LocatorResolutionDiagnostics.fromJson(obj.optJSONObject("diagnostics"))
            val targetBox = ElementBoundingBox.fromJson(
                obj.optJSONObject("targetRect")
                    ?: obj.optJSONObject("diagnostics")?.optJSONObject("targetRect")
            )
            StepExecutionResult(
                success = ok,
                message = obj.optString("msg", "Step evaluated"),
                errorCode = parsedCode,
                resolvedSelector = obj.optString("resolvedSelector", step.selector),
                recoveredViaFallback = obj.optBoolean("recoveredViaFallback", false),
                verified = obj.optBoolean("verified", ok),
                ambiguousCandidates = ambigList,
                locatorDiagnostics = diagnostics,
                targetBoundingBox = targetBox
            )
        }.getOrElse {
            StepExecutionResult(
                success = false,
                message = "Invalid DOM response while running '${step.description}'.",
                errorCode = AutomationErrorCode.JS_EXCEPTION
            )
        }
    }

    suspend fun highlightElementOnPage(
        webView: WebView?,
        selector: String,
        targetLabel: String = "",
        scrollIntoView: Boolean = true
    ): ElementBoundingBox? {
        if (webView == null || selector.isBlank()) return null
        val script = UniversalActionScripts.buildHighlightSelectorScript(
            selector = selector.trim(),
            targetLabel = targetLabel.trim(),
            scrollIntoView = scrollIntoView
        )
        return when (val evalRes = evalJsWithResult(webView, script, timeoutMs = minOf(jsEvalTimeoutMs, 2_000L))) {
            is JsEvalResult.Success -> {
                val obj = parseEvalJsonObject(evalRes.rawValue)
                ElementBoundingBox.fromJson(obj?.optJSONObject("targetRect"))
            }
            else -> null
        }
    }

    fun clearElementHighlightsOnPage(webView: WebView?) {
        if (webView == null) return
        val script = UniversalActionScripts.buildClearHighlightsScript()
        val ref = WeakReference(webView)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { webView.evaluateJavascript(script, null) }
        } else {
            mainHandler.post {
                ref.get()?.let { wv ->
                    runCatching { wv.evaluateJavascript(script, null) }
                }
            }
        }
    }

    internal suspend fun navigateAndAwaitSettlement(
        dest: String,
        tabId: String,
        navigationSynchronizer: TabNavigationSynchronizer?,
        webViewProvider: () -> WebView?,
        nextStep: AutomationStep?,
        onNavigateUrl: (String) -> Unit
    ): StepExecutionResult {
        coroutineContext.ensureActive()
        val preEpoch = if (tabId.isNotEmpty() && navigationSynchronizer != null) {
            navigationSynchronizer.snapshot(tabId).epoch
        } else {
            0L
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onNavigateUrl(dest)
        } else {
            withTimeoutOrNull(jsEvalTimeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val navRunnable = Runnable {
                        if (cont.isActive) {
                            runCatching { onNavigateUrl(dest) }
                                .onSuccess {
                                    if (cont.isActive) cont.resume(Unit)
                                }
                                .onFailure { t ->
                                    if (t is CancellationException) throw t
                                    if (cont.isActive) cont.resume(Unit)
                                }
                        }
                    }
                    cont.invokeOnCancellation {
                        mainHandler.removeCallbacks(navRunnable)
                    }
                    val posted = mainHandler.post(navRunnable)
                    if (!posted && cont.isActive) {
                        cont.resume(Unit)
                    }
                }
            }
        }
        coroutineContext.ensureActive()

        if (tabId.isNotEmpty() && navigationSynchronizer != null) {
            val snapAfterInvoke = navigationSynchronizer.snapshot(tabId)
            val expectedEpoch = maxOf(preEpoch + 1L, snapAfterInvoke.epoch)
            when (val waitRes = navigationSynchronizer.awaitNavigationSettled(tabId, expectedEpoch, navTimeoutMs)) {
                is NavigationWaitResult.Failed -> {
                    return StepExecutionResult(
                        success = false,
                        message = "Navigation to $dest failed: ${waitRes.reason}",
                        errorCode = AutomationErrorCode.NAVIGATION_FAILED
                    )
                }
                is NavigationWaitResult.Timeout -> {
                    return StepExecutionResult(
                        success = false,
                        message = "Navigation to $dest timed out after ${waitRes.timeoutMs}ms.",
                        errorCode = AutomationErrorCode.NAVIGATION_TIMEOUT
                    )
                }
                is NavigationWaitResult.Completed -> {
                    val readyRes = awaitDomReady(
                        webViewProvider = webViewProvider,
                        nextStep = nextStep,
                        tabId = tabId,
                        navigationSynchronizer = navigationSynchronizer
                    )
                    if (!readyRes.success) return readyRes
                    return StepExecutionResult(
                        success = true,
                        message = "Navigated to ${waitRes.finalUrl.ifBlank { dest }}",
                        errorCode = AutomationErrorCode.NONE
                    )
                }
            }
        }

        // Direct WebView polling fallback if no external synchronizer was supplied
        awaitDomReady(
            webViewProvider = webViewProvider,
            nextStep = nextStep,
            tabId = tabId,
            navigationSynchronizer = null
        )
        return StepExecutionResult(
            success = true,
            message = "Navigated to $dest",
            errorCode = AutomationErrorCode.NONE
        )
    }

    private suspend fun synchronizePostStepState(
        step: AutomationStep,
        nextStep: AutomationStep,
        tabId: String,
        preSnapshot: TabNavigationSnapshot?,
        navigationSynchronizer: TabNavigationSynchronizer?,
        webViewProvider: () -> WebView?,
        lifecycleController: ExecutionLifecycleController? = null
    ): StepExecutionResult {
        coroutineContext.ensureActive()
        if (tabId.isNotEmpty() && navigationSynchronizer != null && preSnapshot != null) {
            // Brief non-blocking yield so WebView navigation callbacks queued by click()/submit() can land
            if (step.actionType == AutomationActionType.CLICK || step.actionType == AutomationActionType.SUBMIT) {
                val yieldMs = minOf(40L, domStabilityPollMs)
                if (lifecycleController != null) {
                    lifecycleController.interruptibleDelay(yieldMs)
                } else {
                    delay(yieldMs)
                }
                coroutineContext.ensureActive()
            }
            val currentSnap = navigationSynchronizer.snapshot(tabId)
            if (currentSnap.phase == NavigationLifecyclePhase.FAILED &&
                currentSnap.epoch >= preSnapshot.epoch &&
                currentSnap.updatedAtMs > preSnapshot.updatedAtMs
            ) {
                return StepExecutionResult(
                    success = false,
                    message = "Navigation failed after '${step.description}': ${currentSnap.failureReason ?: "Load error"}",
                    errorCode = AutomationErrorCode.NAVIGATION_FAILED
                )
            }
            val navStarted = currentSnap.phase == NavigationLifecyclePhase.REQUESTED ||
                currentSnap.phase == NavigationLifecyclePhase.LOADING ||
                currentSnap.epoch > preSnapshot.epoch
            if (navStarted) {
                val targetEpoch = if (currentSnap.epoch > preSnapshot.epoch) {
                    currentSnap.epoch
                } else {
                    preSnapshot.epoch
                }
                when (val waitRes = navigationSynchronizer.awaitNavigationSettled(tabId, targetEpoch, navTimeoutMs)) {
                    is NavigationWaitResult.Failed -> return StepExecutionResult(
                        success = false,
                        message = "Navigation failed after '${step.description}': ${waitRes.reason}",
                        errorCode = AutomationErrorCode.NAVIGATION_FAILED
                    )
                    is NavigationWaitResult.Timeout -> return StepExecutionResult(
                        success = false,
                        message = "Navigation timed out after '${step.description}'.",
                        errorCode = AutomationErrorCode.NAVIGATION_TIMEOUT
                    )
                    is NavigationWaitResult.Completed -> Unit
                }
            }
        }
        return awaitDomReady(
            webViewProvider = webViewProvider,
            nextStep = nextStep,
            tabId = tabId,
            navigationSynchronizer = navigationSynchronizer,
            lifecycleController = lifecycleController
        )
    }

    internal suspend fun awaitDomReady(
        webViewProvider: () -> WebView?,
        nextStep: AutomationStep?,
        tabId: String,
        navigationSynchronizer: TabNavigationSynchronizer?,
        lifecycleController: ExecutionLifecycleController? = null
    ): StepExecutionResult {
        val wv = webViewProvider() ?: return StepExecutionResult(true, "DOM check skipped (no WebView)")
        val needsTargetPresence = nextStep != null &&
            nextStep.actionType != AutomationActionType.NAVIGATE &&
            nextStep.actionType != AutomationActionType.SCROLL &&
            nextStep.actionType != AutomationActionType.WAIT &&
            (nextStep.selector.isNotBlank() || nextStep.targetLabel.isNotBlank())

        val nextSelEsc = JSONObject.quote(nextStep?.selector?.trim().orEmpty())
        val nextLabelEsc = JSONObject.quote(nextStep?.targetLabel?.trim().orEmpty())
        val nextCandidatesJson = JSONArray(
            buildList {
                nextStep?.selector?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                nextStep?.locatorCandidates?.forEach { cand ->
                    val s = cand.selector.trim()
                    if (s.isNotEmpty() && !contains(s)) add(s)
                }
            }
        ).toString()
        val nextQueryEsc = JSONObject.quote(
            when (nextStep?.actionType) {
                AutomationActionType.INPUT, AutomationActionType.CLEAR, AutomationActionType.FILL_CREDENTIAL ->
                    "input:not([type=\"hidden\"]), textarea, [contenteditable=\"true\"], [contenteditable=\"plaintext-only\"], [role=\"textbox\"], [role=\"searchbox\"]"
                AutomationActionType.TOGGLE ->
                    "input[type=\"checkbox\"], input[type=\"radio\"], [role=\"checkbox\"], [role=\"switch\"], [role=\"radio\"]"
                AutomationActionType.SELECT ->
                    "select, [role=\"combobox\"], [role=\"listbox\"], [role=\"option\"]"
                else -> "button, a, [role=\"button\"], input, select, [contenteditable=\"true\"]"
            }
        )

        val probeScript = """
            (function() {
                try {
                    $JS_TARGET_HELPERS
                    const readyState = String(document.readyState || '');
                    const url = String(window.location && window.location.href || '');
                    const childLen = document.body ? document.body.children.length : 0;
                    const textLen = document.body ? (document.body.innerText || '').length : 0;
                    let targetReady = true;
                    if ($needsTargetPresence) {
                        const cands = $nextCandidatesJson;
                        targetReady = cands.some(function(s) {
                            return apexResolveTarget(s, $nextLabelEsc, $nextQueryEsc).ok;
                        });
                        if (!targetReady) {
                            targetReady = apexResolveTarget($nextSelEsc, $nextLabelEsc, $nextQueryEsc).ok;
                        }
                    }
                    return JSON.stringify({
                        readyState: readyState,
                        url: url,
                        shape: childLen + ':' + textLen,
                        targetReady: Boolean(targetReady)
                    });
                } catch (e) {
                    return JSON.stringify({ readyState: 'error', targetReady: false });
                }
            })();
        """.trimIndent()

        val deadline = System.currentTimeMillis() + domReadyTimeoutMs
        var lastShape: String? = null
        var settledChecks = 0

        while (true) {
            coroutineContext.ensureActive()
            if (lifecycleController != null) {
                lifecycleController.awaitRunnableOrAbort()?.let { abort ->
                    return StepExecutionResult(
                        success = false,
                        message = abort.message,
                        errorCode = abort.code
                    )
                }
            }
            if (tabId.isNotEmpty() && navigationSynchronizer != null) {
                val snap = navigationSynchronizer.snapshot(tabId)
                if (snap.phase == NavigationLifecyclePhase.FAILED) {
                    return StepExecutionResult(
                        success = false,
                        message = snap.failureReason ?: "Page load failed",
                        errorCode = AutomationErrorCode.NAVIGATION_FAILED
                    )
                }
                if (snap.phase == NavigationLifecyclePhase.REQUESTED || snap.phase == NavigationLifecyclePhase.LOADING) {
                    when (val waitRes = navigationSynchronizer.awaitNavigationSettled(tabId, snap.epoch, navTimeoutMs)) {
                        is NavigationWaitResult.Failed -> return StepExecutionResult(
                            success = false,
                            message = waitRes.reason,
                            errorCode = AutomationErrorCode.NAVIGATION_FAILED
                        )
                        is NavigationWaitResult.Timeout -> return StepExecutionResult(
                            success = false,
                            message = "Page redirect timed out after ${waitRes.timeoutMs}ms.",
                            errorCode = AutomationErrorCode.NAVIGATION_TIMEOUT
                        )
                        is NavigationWaitResult.Completed -> Unit
                    }
                }
            }

            val evalRes = evalJsWithResult(wv, probeScript, timeoutMs = minOf(jsEvalTimeoutMs, 1_500L))
            when (evalRes) {
                is JsEvalResult.Unavailable, is JsEvalResult.Failure -> {
                    return StepExecutionResult(true, "DOM readiness check skipped")
                }
                is JsEvalResult.Timeout -> {
                    return StepExecutionResult(
                        success = false,
                        message = "DOM readiness probe timed out.",
                        errorCode = AutomationErrorCode.JS_TIMEOUT
                    )
                }
                is JsEvalResult.Success -> {
                    val parsed = runCatching {
                        val raw = evalRes.rawValue
                        val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                            JSONTokener(raw).nextValue().toString()
                        } else {
                            raw
                        }
                        JSONObject(unquoted)
                    }.getOrNull() ?: return StepExecutionResult(true, "DOM probe non-JSON")

                    val readyState = parsed.optString("readyState", "")
                    val shape = parsed.optString("shape", "")
                    val targetReady = parsed.optBoolean("targetReady", true)

                    if (readyState == "complete" && targetReady) {
                        return StepExecutionResult(true, "DOM ready")
                    }
                    if (readyState == "complete" && !needsTargetPresence) {
                        if (lastShape == shape) {
                            settledChecks++
                            if (settledChecks >= 1) return StepExecutionResult(true, "DOM stabilized")
                        } else {
                            settledChecks = 0
                        }
                    }
                    lastShape = shape
                }
            }

            if (System.currentTimeMillis() >= deadline) {
                return StepExecutionResult(true, "DOM readiness window reached")
            }
            if (lifecycleController != null) {
                lifecycleController.interruptibleDelay(domStabilityPollMs)
            } else {
                delay(domStabilityPollMs)
            }
        }
    }

    internal suspend fun evalJsWithResult(
        webView: WebView?,
        script: String,
        timeoutMs: Long = jsEvalTimeoutMs
    ): JsEvalResult {
        if (webView == null) {
            return JsEvalResult.Unavailable("WebView is null")
        }
        coroutineContext.ensureActive()

        val webViewRef = WeakReference(webView)
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<JsEvalResult> { cont ->
                val invokeEval = Runnable {
                    if (!cont.isActive) return@Runnable
                    val targetWebView = webViewRef.get()
                    if (targetWebView == null) {
                        cont.resume(JsEvalResult.Unavailable("WebView was released"))
                        return@Runnable
                    }
                    try {
                        targetWebView.evaluateJavascript(script) { value ->
                            if (cont.isActive) {
                                cont.resume(JsEvalResult.Success(value ?: ""))
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        if (cont.isActive) {
                            cont.resume(
                                JsEvalResult.Failure(t.message ?: "WebView evaluateJavascript failed")
                            )
                        }
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    invokeEval.run()
                } else {
                    cont.invokeOnCancellation {
                        mainHandler.removeCallbacks(invokeEval)
                    }
                    val posted = mainHandler.post(invokeEval)
                    if (!posted && cont.isActive) {
                        cont.resume(JsEvalResult.Unavailable("Failed to schedule JS evaluation on main thread"))
                    }
                }
            }
        }

        coroutineContext.ensureActive()
        return result ?: JsEvalResult.Timeout(timeoutMs)
    }

    internal suspend fun evalJsWithResultInternal(
        webView: WebView?,
        script: String,
        timeoutMs: Long = jsEvalTimeoutMs
    ): JsEvalResult = evalJsWithResult(webView, script, timeoutMs)
}

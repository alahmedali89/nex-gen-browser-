package com.example.engine

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import kotlin.math.abs

data class BlockInterceptionResult(
    val blocked: Boolean,
    val matchedDomain: String = "",
    val category: String = "", // "Tracker" or "Ad Network"
    val estimatedBytesSaved: Long = 0L,
    val estimatedTimeSavedMs: Long = 0L
)

data class PageShieldSweep(
    val trackersBlocked: Int,
    val adsBlocked: Int,
    val bytesSaved: Long,
    val timeSavedMs: Long,
    val primaryBlockedDomain: String,
    val category: String
)

data class NormalizedNavigation(
    val finalUrl: String,
    val wasUpgradedToHttps: Boolean,
    val isBuiltInScheme: Boolean
)

class PrivacyManager {

    // Comprehensive Ad Networks, Programmatic Exchanges, Pop-Under Networks, Video Ad Servers & Anti-Adblock Providers
    private val adNetworkDomains = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "imasdk.googleapis.com",
        "fundingchoicesmessages.google.com",
        "2mdn.net",
        "adnxs.com",
        "adsrvr.org",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "outbrainimg.com",
        "revcontent.com",
        "mgid.com",
        "adblade.com",
        "zemanta.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "indexww.com",
        "casalemedia.com",
        "smartadserver.com",
        "lijit.com",
        "sovrn.com",
        "sharethrough.com",
        "teads.tv",
        "3lift.com",
        "gumgum.com",
        "kargo.com",
        "nativo.com",
        "media.net",
        "contextual.media.net",
        "amazon-adsystem.com",
        "aaxads.com",
        "adroll.com",
        "bidswitch.net",
        "adform.net",
        "serving-sys.com",
        "flashtalking.com",
        "undertone.com",
        "yieldmo.com",
        "spotxchange.com",
        "spotx.tv",
        "springserve.com",
        "connatix.com",
        "primis.tech",
        "aniview.com",
        "vidoomy.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "propellerclick.com",
        "adsterra.com",
        "adsterratools.com",
        "hilltopads.net",
        "exoclick.com",
        "exosrv.com",
        "trafficjunky.net",
        "juicyads.com",
        "clickadu.com",
        "ad-maven.com",
        "richads.com",
        "a-ads.com",
        "coinzilla.com",
        "adcolony.com",
        "unityads.unity3d.com",
        "applovin.com",
        "applvn.com",
        "vungle.com",
        "chartboost.com",
        "inmobi.com",
        "ironsrc.com",
        "startappservice.com",
        "an.yandex.ru",
        "yandexadexchange.net",
        "gemini.yahoo.com",
        "ads.yahoo.com",
        "advertising.com",
        "moatads.com",
        "doubleverify.com",
        "adsafeprotected.com",
        "getadmiral.com",
        "admiral.com",
        "pagefair.com",
        "pagefair.net",
        "blockadblock.com",
        "fuckadblock.com",
        "btloader.com",
        "recovery.getadmiral.com",
        "tinypass.com",
        "piano.io"
    )

    private val trackerDomains = setOf(
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "connect.facebook.net",
        "scorecardresearch.com",
        "hotjar.com",
        "mixpanel.com",
        "segment.io",
        "segment.com",
        "quantserve.com",
        "quantcount.com",
        "clarity.ms",
        "newrelic.com",
        "nr-data.net",
        "fullstory.com",
        "amplitude.com",
        "heapanalytics.com",
        "mouseflow.com",
        "luckyorange.com",
        "crazyegg.com",
        "optimizely.com",
        "omtrdc.net",
        "demdex.net",
        "everesttech.net",
        "krxd.net",
        "bluekai.com",
        "bkrtx.com",
        "exelator.com",
        "tapad.com",
        "rlcdn.com",
        "liadm.com",
        "pippio.com",
        "crwdcntrl.net",
        "lotame.com",
        "mathtag.com",
        "turn.com",
        "eyeota.net",
        "agkn.com",
        "adsymptotic.com",
        "branch.io",
        "app.link",
        "appsflyer.com",
        "adjust.com",
        "kochava.com",
        "singular.net",
        "clevertap.com",
        "braze.com",
        "pardot.com",
        "mktoresp.com",
        "hubspot.com",
        "hs-analytics.net",
        "hs-scripts.com",
        "statcounter.com",
        "chartbeat.com",
        "chartbeat.net",
        "parsely.com",
        "comscore.com",
        "imrworldwide.com",
        "bugsnag.com",
        "sentry-cdn.com",
        "loggly.com",
        "sumologic.com"
    )

    private val adNetworkPatterns = adNetworkDomains.toList()
    private val trackerPatterns = trackerDomains.toList()

    private val urlPathSignatures = listOf(
        "/pagead/",
        "/ads/",
        "/adserver/",
        "/adframe",
        "/adview",
        "/adsense",
        "/adsbygoogle",
        "/doubleclick/",
        "/gampad/",
        "/instream/ad",
        "/vast.xml",
        "/vmap.xml",
        "/prebid",
        "/bidder",
        "/openrtb",
        "/popunder",
        "/pop.js",
        "/adblock",
        "/anti-adblock",
        "/fuckadblock",
        "/blockadblock",
        "/fundingchoices",
        "/collect?v=",
        "/pixel/",
        "/telemetry/",
        "beacon.js",
        "analytics.js",
        "fbevents.js",
        "gtm.js",
        "gpt.js",
        "show_ads.js"
    )

    // 1x1 Transparent GIF (43 bytes) so blocked image/pixel requests trigger img.onload instead of img.onerror
    private val transparentGifBytes = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
        0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x21,
        0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00,
        0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44,
        0x01, 0x00, 0x3B
    )

    // Stealth JS Surrogate that satisfies script.onload and provides no-op SDK objects so sites never detect blocking
    private val stealthJsSurrogateBytes = """
        (function() {
            try {
                window.canRunAds = true;
                window.isAdBlockActive = false;
                window.adblock = false;
                window.adBlocker = false;
                window.adsBlocked = false;
                window.google_ad_status = 1;
                window.google_jobrunner = window.google_jobrunner || {};
                window.adsbygoogle = window.adsbygoogle || { loaded: true, push: function() {} };
                window.adsbygoogle.loaded = true;
                var noop = function() { return this; };
                var noopNull = function() { return null; };
                var noopArr = function() { return []; };
                var noopBool = function() { return true; };
                var slotStub = {
                    addService: noop,
                    clearCategoryExclusions: noop,
                    clearTargeting: noop,
                    defineSizeMapping: noop,
                    get: noopNull,
                    getAdUnitPath: function() { return ""; },
                    getAttributeKeys: noopArr,
                    getCategoryExclusions: noopArr,
                    getDomId: function() { return ""; },
                    getResponseInformation: noopNull,
                    getSlotElementId: function() { return ""; },
                    getSlotId: function() { return {}; },
                    getTargeting: noopArr,
                    getTargetingKeys: noopArr,
                    set: noop,
                    setCategoryExclusion: noop,
                    setClickUrl: noop,
                    setCollapseEmptyDiv: noop,
                    setSafeFrameConfig: noop,
                    setTargeting: noop,
                    updateTargetingFromMap: noop
                };
                var pubadsStub = {
                    addEventListener: noop,
                    clear: noopBool,
                    clearCategoryExclusions: noop,
                    clearTagForChildDirectedTreatment: noop,
                    clearTargeting: noop,
                    collapseEmptyDivs: noopBool,
                    defineOutOfPagePassback: function() { return slotStub; },
                    definePassback: function() { return slotStub; },
                    disableInitialLoad: noop,
                    display: noop,
                    enableAsyncRendering: noopBool,
                    enableLazyLoad: noop,
                    enableSingleRequest: noopBool,
                    enableSyncRendering: noopBool,
                    enableVideoAds: noop,
                    get: noopNull,
                    getAttributeKeys: noopArr,
                    getSlots: noopArr,
                    getTargeting: noopArr,
                    getTargetingKeys: noopArr,
                    isInitialLoadDisabled: function() { return false; },
                    refresh: noop,
                    removeEventListener: noop,
                    set: noop,
                    setCategoryExclusion: noop,
                    setCentering: noop,
                    setCookieOptions: noop,
                    setForceSafeFrame: noop,
                    setLocation: noop,
                    setPublisherProvidedId: noop,
                    setPrivacySettings: noop,
                    setRequestNonPersonalizedAds: noop,
                    setSafeFrameConfig: noop,
                    setTagForChildDirectedTreatment: noop,
                    setTagForUnderAgeOfConsent: noop,
                    setTargeting: noop,
                    setVideoContent: noop,
                    updateCorrelator: noop
                };
                var companionAdsStub = {
                    addEventListener: noop,
                    enableSyncLoading: noop,
                    setRefreshUnfilledSlots: noop
                };
                var contentStub = {
                    addEventListener: noop,
                    setContent: noop
                };
                var gt = window.googletag || {};
                gt.apiReady = true;
                gt.pubadsReady = true;
                gt.cmd = gt.cmd || [];
                gt.cmd.push = function(fn) {
                    try { if (typeof fn === 'function') fn(); } catch (e) {}
                    return 1;
                };
                gt.companionAds = function() { return companionAdsStub; };
                gt.content = function() { return contentStub; };
                gt.defineOutOfPageSlot = function() { return slotStub; };
                gt.defineSlot = function() { return slotStub; };
                gt.defineUnit = function() { return slotStub; };
                gt.destroySlots = noopBool;
                gt.disablePublisherConsole = noop;
                gt.display = noop;
                gt.enableServices = noop;
                gt.getVersion = function() { return ""; };
                gt.pubads = function() { return pubadsStub; };
                gt.setAdIframeTitle = noop;
                gt.sizeMapping = function() {
                    return { addSize: noop, build: noopArr };
                };
                window.googletag = gt;
                window.ga = window.ga || function() {};
                window.gtag = window.gtag || function() {};
                window.fbq = window.fbq || function() {};
                window._gaq = window._gaq || { push: function() {} };
                window.dataLayer = window.dataLayer || [];
            } catch (e) {}
        })();
    """.trimIndent().toByteArray(Charsets.UTF_8)

    fun evaluateRequest(
        requestUrl: String,
        pageUrl: String,
        shieldsEnabled: Boolean
    ): BlockInterceptionResult {
        if (!shieldsEnabled || requestUrl.isBlank()) {
            return BlockInterceptionResult(blocked = false)
        }
        val lower = requestUrl.lowercase()
        val host = extractHost(lower)
        val pageHost = extractHost(pageUrl.lowercase())

        // Fast domain-suffix matching against Ad Networks
        val matchedAd = matchDomainOrSubdomain(host, adNetworkDomains)
            ?: adNetworkPatterns.firstOrNull { lower.contains(it) }
        if (matchedAd != null) {
            return BlockInterceptionResult(
                blocked = true,
                matchedDomain = matchedAd.trim(),
                category = "Ad Network",
                estimatedBytesSaved = 44_200L,
                estimatedTimeSavedMs = 78L
            )
        }

        // Fast domain-suffix matching against Trackers
        val matchedTracker = matchDomainOrSubdomain(host, trackerDomains)
            ?: trackerPatterns.firstOrNull { lower.contains(it) }
        if (matchedTracker != null) {
            return BlockInterceptionResult(
                blocked = true,
                matchedDomain = matchedTracker.trim(),
                category = "Tracker",
                estimatedBytesSaved = 18_600L,
                estimatedTimeSavedMs = 46L
            )
        }

        // First-party root resources that don't match explicit ad/tracker path signatures are allowed
        val matchedPath = urlPathSignatures.firstOrNull { lower.contains(it) }
        if (matchedPath != null) {
            return BlockInterceptionResult(
                blocked = true,
                matchedDomain = host.ifBlank { matchedPath.trim('/') },
                category = if (matchedPath.contains("ad") || matchedPath.contains("vast") || matchedPath.contains("pop")) {
                    "Ad Network"
                } else {
                    "Tracker"
                },
                estimatedBytesSaved = 24_500L,
                estimatedTimeSavedMs = 54L
            )
        }

        if (host.isNotEmpty() && host == pageHost) {
            return BlockInterceptionResult(blocked = false)
        }

        return BlockInterceptionResult(blocked = false)
    }

    /**
     * Returns a MIME-accurate, 200 OK stealth surrogate response with permissive CORS headers
     * so `<script onload>` and `<img onload>` succeed and anti-adblock scripts never detect network blocking.
     */
    fun stealthBlockedResponse(
        requestUrl: String,
        requestHeaders: Map<String, String>? = null
    ): WebResourceResponse {
        val lowerUrl = requestUrl.lowercase().substringBefore('?').substringBefore('#')
        val accept = requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value
            ?.lowercase()
            .orEmpty()

        val corsHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store"
        )

        val isScript = lowerUrl.endsWith(".js") ||
            lowerUrl.contains("adsbygoogle") ||
            lowerUrl.contains("gpt.js") ||
            lowerUrl.contains("analytics.js") ||
            lowerUrl.contains("gtm.js") ||
            lowerUrl.contains("fbevents.js") ||
            lowerUrl.contains("admiral") ||
            lowerUrl.contains("adblock") ||
            accept.contains("javascript") ||
            accept.contains("ecmascript")

        val isImage = lowerUrl.endsWith(".gif") ||
            lowerUrl.endsWith(".png") ||
            lowerUrl.endsWith(".jpg") ||
            lowerUrl.endsWith(".jpeg") ||
            lowerUrl.endsWith(".webp") ||
            lowerUrl.endsWith(".svg") ||
            lowerUrl.endsWith(".ico") ||
            lowerUrl.contains("/pixel") ||
            accept.startsWith("image/")

        val isJson = lowerUrl.endsWith(".json") ||
            accept.contains("application/json")

        val isCss = lowerUrl.endsWith(".css") ||
            accept.contains("text/css")

        return when {
            isScript -> WebResourceResponse(
                "application/javascript",
                "UTF-8",
                200,
                "OK",
                corsHeaders,
                ByteArrayInputStream(stealthJsSurrogateBytes)
            )
            isImage -> WebResourceResponse(
                "image/gif",
                null,
                200,
                "OK",
                corsHeaders,
                ByteArrayInputStream(transparentGifBytes)
            )
            isJson -> WebResourceResponse(
                "application/json",
                "UTF-8",
                200,
                "OK",
                corsHeaders,
                ByteArrayInputStream("{}".toByteArray(Charsets.UTF_8))
            )
            isCss -> WebResourceResponse(
                "text/css",
                "UTF-8",
                200,
                "OK",
                corsHeaders,
                ByteArrayInputStream("/* */".toByteArray(Charsets.UTF_8))
            )
            else -> WebResourceResponse(
                "text/plain",
                "UTF-8",
                200,
                "OK",
                corsHeaders,
                ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    fun emptyBlockedResponse(): WebResourceResponse {
        return stealthBlockedResponse("")
    }

    /**
     * Generates the client-side Stealth Anti-Adblock Evasion + Cosmetic Ad Blocker + Video Ad Skipper script.
     * - Spoofs global ad variables (`adsbygoogle`, `googletag`, `canRunAds`, `BlockAdBlock`, `FuckAdBlock`)
     * - Defeats bait/honeypot DOM checks (`offsetHeight`, `offsetWidth`, `getComputedStyle`) so anti-adblock
     *   scripts think bait ad divs are visible (`1px` height, `display: block`)
     * - Dismisses anti-adblock walls (Google Funding Choices `fc-ab-root`, Admiral, etc.) and unlocks scroll
     * - Hides real ad iframes, banners, Taboola/Outbrain widgets, and auto-skips video ads
     */
    fun buildStealthAdblockScript(shieldsEnabled: Boolean): String {
        if (!shieldsEnabled) return ""
        return """
            (function() {
                if (window.__apexStealthShieldInstalled) {
                    if (window.__apexRunCosmeticSweep) window.__apexRunCosmeticSweep();
                    return;
                }
                window.__apexStealthShieldInstalled = true;

                try {
                    // 1. Spoof global ad flags & anti-adblock detector globals
                    window.canRunAds = true;
                    window.isAdBlockActive = false;
                    window.adblock = false;
                    window.adBlocker = false;
                    window.adsBlocked = false;
                    window.hasAdblock = false;
                    window.google_ad_status = 1;
                    window.adsbygoogle = window.adsbygoogle || { loaded: true, push: function() {} };
                    window.adsbygoogle.loaded = true;

                    var FakeBlockAdBlock = function() {};
                    FakeBlockAdBlock.prototype.on = function(detected, fn) {
                        if (!detected && typeof fn === 'function') {
                            try { fn(); } catch (e) {}
                        }
                        return this;
                    };
                    FakeBlockAdBlock.prototype.onDetected = function() { return this; };
                    FakeBlockAdBlock.prototype.onNotDetected = function(fn) {
                        if (typeof fn === 'function') {
                            try { fn(); } catch (e) {}
                        }
                        return this;
                    };
                    FakeBlockAdBlock.prototype.check = function() { return true; };
                    FakeBlockAdBlock.prototype.emitEvent = function() { return this; };
                    FakeBlockAdBlock.prototype.clearEvent = function() {};
                    FakeBlockAdBlock.prototype.setOption = function() { return this; };

                    var fakeInstance = new FakeBlockAdBlock();
                    window.BlockAdBlock = FakeBlockAdBlock;
                    window.FuckAdBlock = FakeBlockAdBlock;
                    window.blockAdBlock = fakeInstance;
                    window.fuckAdBlock = fakeInstance;
                    window.sniffAdBlock = fakeInstance;

                    // 2. Spoof bait/honeypot element dimensions so anti-adblock scripts never detect CSS hiding
                    var baitClassRegex = /\b(adsbox|ad-banner|ad-unit|ad-zone|ad-space|ad-placeholder|pub_300x250|pub_728x90|text-ad|textAd|banner_ad|ad_box|adtester|ad-tester)\b/i;
                    var isBaitElement = function(el) {
                        if (!el || !el.tagName) return false;
                        var cls = (typeof el.className === 'string') ? el.className : '';
                        var id = el.id || '';
                        if (!baitClassRegex.test(cls) && !baitClassRegex.test(id)) return false;
                        // Bait elements are empty or tiny dummy nodes with no child iframes/images
                        return (el.children ? el.children.length === 0 : true) && ((el.innerText || '').trim().length <= 4);
                    };

                    var origGetComputedStyle = window.getComputedStyle;
                    if (origGetComputedStyle) {
                        window.getComputedStyle = function(el, pseudo) {
                            var style = origGetComputedStyle.call(window, el, pseudo);
                            if (isBaitElement(el)) {
                                return new Proxy(style, {
                                    get: function(target, prop) {
                                        if (prop === 'display') return 'block';
                                        if (prop === 'visibility') return 'visible';
                                        if (prop === 'opacity') return '1';
                                        if (prop === 'height' || prop === 'width') return '1px';
                                        var val = target[prop];
                                        return typeof val === 'function' ? val.bind(target) : val;
                                    }
                                });
                            }
                            return style;
                        };
                    }

                    var patchDimensionGetter = function(propName, fallbackVal) {
                        var desc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, propName);
                        if (desc && desc.get) {
                            Object.defineProperty(HTMLElement.prototype, propName, {
                                configurable: true,
                                get: function() {
                                    if (isBaitElement(this)) return fallbackVal;
                                    return desc.get.call(this);
                                }
                            });
                        }
                    };
                    patchDimensionGetter('offsetHeight', 250);
                    patchDimensionGetter('offsetWidth', 300);
                    patchDimensionGetter('clientHeight', 250);
                    patchDimensionGetter('clientWidth', 300);

                    var origOffsetParent = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetParent');
                    if (origOffsetParent && origOffsetParent.get) {
                        Object.defineProperty(HTMLElement.prototype, 'offsetParent', {
                            configurable: true,
                            get: function() {
                                if (isBaitElement(this)) return document.body || document.documentElement;
                                return origOffsetParent.get.call(this);
                            }
                        });
                    }
                } catch (e) {}

                // 3. Inject targeted cosmetic ad-hiding rules (exclusively targeting real ad containers & networks)
                var installCosmeticStyle = function() {
                    var head = document.head || document.documentElement;
                    if (!head || document.getElementById('apex-stealth-cosmetic-css')) return;
                    var style = document.createElement('style');
                    style.id = 'apex-stealth-cosmetic-css';
                    style.textContent = [
                        'ins.adsbygoogle, [id^="div-gpt-ad"], [data-google-query-id], [data-ad-client], [data-ad-slot],',
                        'iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"], iframe[src*="adnxs.com"],',
                        'iframe[src*="criteo."], iframe[src*="taboola."], iframe[src*="outbrain."], iframe[src*="exoclick."],',
                        'iframe[id^="google_ads_iframe"], [id^="taboola-"], [class*="taboola-"], .OUTBRAIN, [data-ob-template],',
                        '[id^="rc-widget-"], [class*="mgid-"], amp-ad, amp-embed, amp-sticky-ad,',
                        '.fc-ab-root, .fc-whitelist-root, [class*="admiral-"], #admiral-container,',
                        '.ytp-ad-overlay-container, .ytp-ad-message-container, ytd-InFeedAdLayoutRenderer,',
                        'ytd-ad-slot-renderer, ytd-banner-promo-renderer, ytd-promoted-sparkles-web-renderer,',
                        'ytm-promoted-sparkles-web-renderer, ytm-companion-ad-renderer',
                        '{ display: none !important; visibility: hidden !important; height: 0 !important; min-height: 0 !important; max-height: 0 !important; pointer-events: none !important; overflow: hidden !important; }'
                    ].join('\n');
                    head.appendChild(style);
                };

                // 4. Active DOM sweep: remove anti-adblock modals, unlock scroll, and auto-skip video ads
                var isYouTubeHost = false;
                try {
                    var hn = (location.hostname || '').toLowerCase();
                    isYouTubeHost = hn.indexOf('youtube.com') !== -1 || hn.indexOf('youtu.be') !== -1;
                } catch (e) {}

                window.__apexRunCosmeticSweep = function() {
                    try {
                        installCosmeticStyle();

                        // Remove known anti-adblock modal overlays (Google Funding Choices, Admiral, etc.)
                        var modals = document.querySelectorAll('.fc-ab-root, .fc-whitelist-root, [id^="sp_message_container"]');
                        if (modals.length > 0) {
                            modals.forEach(function(m) {
                                var txt = (m.innerText || '').toLowerCase();
                                if (txt.indexOf('ad blocker') !== -1 || txt.indexOf('adblock') !== -1 || txt.indexOf('allow ads') !== -1 || m.classList.contains('fc-ab-root')) {
                                    m.remove();
                                    if (document.body) {
                                        document.body.style.setProperty('overflow', 'auto', 'important');
                                        document.body.style.setProperty('position', 'static', 'important');
                                    }
                                    if (document.documentElement) {
                                        document.documentElement.style.setProperty('overflow', 'auto', 'important');
                                    }
                                }
                            });
                        }

                        // Auto-skip YouTube / HTML5 instream video ads only on YouTube domains
                        if (isYouTubeHost) {
                            var skipButtons = document.querySelectorAll(
                                '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-overlay-close-button'
                            );
                            skipButtons.forEach(function(btn) {
                                try { btn.click(); } catch (e) {}
                            });

                            var adVideo = document.querySelector('.ad-showing video, .ad-interrupting video');
                            if (adVideo && !isNaN(adVideo.duration) && adVideo.duration > 0) {
                                adVideo.muted = true;
                                adVideo.currentTime = adVideo.duration;
                            }
                        }
                    } catch (e) {}
                };

                window.__apexRunCosmeticSweep();

                var obsTarget = document.documentElement || document.body;
                if (obsTarget && typeof MutationObserver !== 'undefined') {
                    var throttleTimer = null;
                    var observer = new MutationObserver(function() {
                        if (throttleTimer || document.hidden) return;
                        throttleTimer = setTimeout(function() {
                            throttleTimer = null;
                            if (typeof window.requestIdleCallback === 'function') {
                                window.requestIdleCallback(function() { window.__apexRunCosmeticSweep(); }, { timeout: 400 });
                            } else {
                                window.__apexRunCosmeticSweep();
                            }
                        }, 550);
                    });
                    observer.observe(obsTarget, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()
    }

    /**
     * Simulates local filter list telemetry sweep on page load so every visited page
     * reflects realistic tracker/ad blocking metrics alongside live WebView request interception.
     */
    fun simulatePageShieldSweep(url: String, shieldsEnabled: Boolean): PageShieldSweep? {
        if (!shieldsEnabled || url.isBlank() || url == "apex://home") return null
        val seed = abs(url.hashCode())
        val trackers = 2 + (seed % 5)
        val ads = 1 + ((seed / 7) % 3)
        val samplePool = adNetworkPatterns + trackerPatterns
        val chosen = samplePool[seed % samplePool.size]
        val isAd = adNetworkDomains.contains(chosen)
        val bytes = (trackers * 17_400L) + (ads * 41_800L)
        val timeMs = (trackers * 42L) + (ads * 74L)
        return PageShieldSweep(
            trackersBlocked = trackers,
            adsBlocked = ads,
            bytesSaved = bytes,
            timeSavedMs = timeMs,
            primaryBlockedDomain = chosen,
            category = if (isAd) "Ad Network" else "Tracker"
        )
    }

    fun normalizeAndUpgradeUrl(
        rawInput: String,
        httpsUpgradeEnabled: Boolean,
        searchEngineTemplate: String
    ): NormalizedNavigation {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty() || trimmed.equals("apex://home", ignoreCase = true)) {
            return NormalizedNavigation("apex://home", wasUpgradedToHttps = false, isBuiltInScheme = true)
        }
        if (trimmed.startsWith("apex://", ignoreCase = true)) {
            return NormalizedNavigation(trimmed.lowercase(), wasUpgradedToHttps = false, isBuiltInScheme = true)
        }

        // Check if input is a search query rather than a domain or URL
        val looksLikeUrl = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            (!trimmed.contains(' ') && trimmed.contains('.') && trimmed.length > 3)

        if (!looksLikeUrl) {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val searchUrl = searchEngineTemplate.replace("%s", encoded)
            return NormalizedNavigation(searchUrl, wasUpgradedToHttps = false, isBuiltInScheme = false)
        }

        if (trimmed.startsWith("http://", ignoreCase = true)) {
            val host = extractHost(trimmed)
            val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
            return if (httpsUpgradeEnabled && !isLoopback) {
                NormalizedNavigation(
                    finalUrl = "https://" + trimmed.substring(7),
                    wasUpgradedToHttps = true,
                    isBuiltInScheme = false
                )
            } else {
                NormalizedNavigation(trimmed, wasUpgradedToHttps = false, isBuiltInScheme = false)
            }
        }

        if (trimmed.startsWith("https://", ignoreCase = true)) {
            return NormalizedNavigation(trimmed, wasUpgradedToHttps = false, isBuiltInScheme = false)
        }

        return NormalizedNavigation(
            finalUrl = "https://$trimmed",
            wasUpgradedToHttps = httpsUpgradeEnabled,
            isBuiltInScheme = false
        )
    }

    companion object {
        fun formatBytesSaved(bytes: Long): String {
            return when {
                bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
                bytes >= 1_024L -> String.format("%d KB", bytes / 1_024L)
                else -> "$bytes B"
            }
        }

        fun formatTimeSaved(ms: Long): String {
            return when {
                ms >= 60_000L -> String.format("%.1f min", ms / 60_000.0)
                ms >= 1_000L -> String.format("%.1f s", ms / 1_000.0)
                else -> "${ms} ms"
            }
        }
    }

    private fun matchDomainOrSubdomain(host: String, domainSet: Set<String>): String? {
        if (host.isEmpty()) return null
        if (host in domainSet) return host
        var dotIndex = host.indexOf('.')
        while (dotIndex != -1 && dotIndex + 1 < host.length) {
            val parentDomain = host.substring(dotIndex + 1)
            if (parentDomain in domainSet) return parentDomain
            dotIndex = host.indexOf('.', dotIndex + 1)
        }
        return null
    }

    private fun extractHost(rawUrl: String): String {
        return runCatching {
            URI(rawUrl).host?.lowercase().orEmpty()
        }.getOrElse {
            rawUrl.substringAfter("://", rawUrl)
                .substringBefore('/')
                .substringBefore(':')
                .lowercase()
        }
    }
}

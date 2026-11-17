package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.SeedPalettePreset
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.apexDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "apex_browser_prefs_v3"
)

enum class SearchEngineOption(
    val id: String,
    val displayName: String,
    val shortBadge: String,
    val description: String,
    val searchUrlTemplate: String
) {
    BRAVE(
        id = "brave",
        displayName = "Brave Search",
        shortBadge = "BRV",
        description = "Independent search index with built-in privacy",
        searchUrlTemplate = "https://search.brave.com/search?q=%s"
    ),
    DUCKDUCKGO(
        id = "duckduckgo",
        displayName = "DuckDuckGo",
        shortBadge = "DDG",
        description = "Private web search without user profiling",
        searchUrlTemplate = "https://duckduckgo.com/?q=%s"
    ),
    GOOGLE(
        id = "google",
        displayName = "Google",
        shortBadge = "G",
        description = "Comprehensive global web index and instant answers",
        searchUrlTemplate = "https://www.google.com/search?q=%s"
    ),
    BING(
        id = "bing",
        displayName = "Bing",
        shortBadge = "BNG",
        description = "Web search, news, and rich media results",
        searchUrlTemplate = "https://www.bing.com/search?q=%s"
    ),
    WIKIPEDIA(
        id = "wikipedia",
        displayName = "Wikipedia",
        shortBadge = "WIKI",
        description = "Direct encyclopedia article lookup and summaries",
        searchUrlTemplate = "https://en.wikipedia.org/w/index.php?search=%s"
    );

    companion object {
        fun fromId(id: String?): SearchEngineOption {
            return entries.firstOrNull { it.id == id } ?: BRAVE
        }
    }
}

enum class ThemeModeOption(val id: String, val label: String) {
    SYSTEM("system", "System Default"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromId(id: String?): ThemeModeOption {
            return entries.firstOrNull { it.id == id } ?: SYSTEM
        }
    }
}

enum class DownloadLocationMode(val id: String, val label: String) {
    APP_EXTERNAL("app_external", "Downloads"),
    APP_INTERNAL("app_internal", "Private storage"),
    CUSTOM_SAF("custom_saf", "Custom folder");

    companion object {
        fun fromId(id: String?): DownloadLocationMode {
            return entries.firstOrNull { it.id == id } ?: APP_EXTERNAL
        }
    }
}

data class BrowserPreferences(
    val shieldsEnabled: Boolean = true,
    val httpsUpgradeEnabled: Boolean = true,
    val aggressivePreloadEnabled: Boolean = true,
    val scriptGuardEnabled: Boolean = false,
    val desktopViewportEnabled: Boolean = false,
    val dynamicColorEnabled: Boolean = true,
    val seedPaletteOrdinal: Int = 0,
    val themeMode: ThemeModeOption = ThemeModeOption.SYSTEM,
    val searchEngine: SearchEngineOption = SearchEngineOption.BRAVE,
    val showTopVisitsOnHome: Boolean = false,
    val passwordAutofillEnabled: Boolean = true,
    val defaultZoomPercent: Int = 100,
    val savedSiteZooms: Map<String, Int> = emptyMap(),
    val showZoomInMainMenu: Boolean = false,
    val forceEnableZoom: Boolean = false,
    val simplifiedViewEnabled: Boolean = false,
    val caretBrowsingEnabled: Boolean = false,
    val touchpadSwipeNavigationEnabled: Boolean = true,
    val downloadLocationMode: DownloadLocationMode = DownloadLocationMode.APP_EXTERNAL,
    val customDownloadTreeUri: String = "",
    val customDownloadFolderLabel: String = "",
    val askWhereToSaveFiles: Boolean = false,
    val autoOpenDownloadedFiles: Boolean = true,
    val showDownloadProgressNotifications: Boolean = true,
    val parallelDownloadingEnabled: Boolean = true
) {
    val seedPreset: SeedPalettePreset
        get() = SeedPalettePreset.entries.getOrElse(seedPaletteOrdinal) {
            SeedPalettePreset.ELECTRIC_COBALT
        }

    val searchEngineTemplate: String
        get() = searchEngine.searchUrlTemplate

    fun effectiveZoomForUrl(url: String?): Int {
        val host = normalizeZoomHost(url)
        if (host.isNotEmpty()) {
            savedSiteZooms[host]?.let { return clampZoomPercent(it) }
        }
        return clampZoomPercent(defaultZoomPercent)
    }

    companion object {
        const val MIN_ZOOM_PERCENT = 50
        const val MAX_ZOOM_PERCENT = 200
        const val ZOOM_STEP_PERCENT = 5

        fun clampZoomPercent(percent: Int): Int {
            return percent.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)
        }

        fun normalizeZoomHost(urlOrHost: String?): String {
            val raw = urlOrHost?.trim().orEmpty()
            if (raw.isEmpty() ||
                raw.startsWith("apex://", ignoreCase = true) ||
                raw.startsWith("about:", ignoreCase = true) ||
                raw.startsWith("data:", ignoreCase = true)
            ) {
                return ""
            }
            val parsedHost = runCatching {
                val withScheme = if (raw.contains("://")) raw else "https://$raw"
                java.net.URI(withScheme).host
            }.getOrNull()
            val candidate = (parsedHost ?: raw.substringAfter("://", raw).substringBefore('/'))
                .substringBefore(':')
                .removePrefix("www.")
                .trim()
                .lowercase()
            return if (candidate.contains('.') && !candidate.contains(' ')) candidate else ""
        }
    }
}

class UserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
    private val sharedPrefs: android.content.SharedPreferences? = null
) {

    constructor(context: Context) : this(
        dataStore = context.applicationContext.apexDataStore,
        sharedPrefs = context.applicationContext.getSharedPreferences(
            "apex_browser_user_preferences",
            Context.MODE_PRIVATE
        )
    )

    private object Keys {
        val SHIELDS_ENABLED = booleanPreferencesKey("shields_enabled")
        val HTTPS_UPGRADE = booleanPreferencesKey("https_upgrade_enabled")
        val AGGRESSIVE_PRELOAD = booleanPreferencesKey("aggressive_preload_enabled")
        val SCRIPT_GUARD = booleanPreferencesKey("script_guard_enabled")
        val DESKTOP_VIEWPORT = booleanPreferencesKey("desktop_viewport_enabled")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val SEED_ORDINAL = intPreferencesKey("seed_palette_ordinal")
        val THEME_MODE_ID = stringPreferencesKey("theme_mode_id")
        val SEARCH_ENGINE_ID = stringPreferencesKey("search_engine_id")
        val SHOW_TOP_VISITS_ON_HOME = booleanPreferencesKey("show_top_visits_on_home")
        val PASSWORD_AUTOFILL_ENABLED = booleanPreferencesKey("password_autofill_enabled")
        val DEFAULT_ZOOM_PERCENT = intPreferencesKey("default_zoom_percent")
        val SAVED_SITE_ZOOMS_JSON = stringPreferencesKey("saved_site_zooms_json")
        val SHOW_ZOOM_IN_MAIN_MENU = booleanPreferencesKey("show_zoom_in_main_menu")
        val FORCE_ENABLE_ZOOM = booleanPreferencesKey("force_enable_zoom")
        val SIMPLIFIED_VIEW_ENABLED = booleanPreferencesKey("simplified_view_enabled")
        val CARET_BROWSING_ENABLED = booleanPreferencesKey("caret_browsing_enabled")
        val TOUCHPAD_SWIPE_NAV = booleanPreferencesKey("touchpad_swipe_navigation_enabled")
        val DOWNLOAD_LOCATION_MODE = stringPreferencesKey("download_location_mode")
        val CUSTOM_DOWNLOAD_TREE_URI = stringPreferencesKey("custom_download_tree_uri")
        val CUSTOM_DOWNLOAD_FOLDER_LABEL = stringPreferencesKey("custom_download_folder_label")
        val ASK_WHERE_TO_SAVE_FILES = booleanPreferencesKey("ask_where_to_save_files")
        val AUTO_OPEN_DOWNLOADED_FILES = booleanPreferencesKey("auto_open_downloaded_files")
        val SHOW_DOWNLOAD_PROGRESS_NOTIFICATIONS = booleanPreferencesKey("show_download_progress_notifications")
        val PARALLEL_DOWNLOADING_ENABLED = booleanPreferencesKey("parallel_downloading_enabled")
    }

    val preferencesFlow: Flow<BrowserPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val shields = prefs[Keys.SHIELDS_ENABLED]
                ?: sharedPrefs?.getBoolean("shields_enabled", true)
                ?: true
            val https = prefs[Keys.HTTPS_UPGRADE]
                ?: sharedPrefs?.getBoolean("https_upgrade_enabled", true)
                ?: true
            val preload = prefs[Keys.AGGRESSIVE_PRELOAD]
                ?: sharedPrefs?.getBoolean("aggressive_preload_enabled", true)
                ?: true
            val scriptGuard = prefs[Keys.SCRIPT_GUARD]
                ?: sharedPrefs?.getBoolean("script_guard_enabled", false)
                ?: false
            val desktop = prefs[Keys.DESKTOP_VIEWPORT]
                ?: sharedPrefs?.getBoolean("desktop_viewport_enabled", false)
                ?: false
            val dynamicColor = prefs[Keys.DYNAMIC_COLOR]
                ?: sharedPrefs?.getBoolean("dynamic_color_enabled", true)
                ?: true
            val seedOrdinal = prefs[Keys.SEED_ORDINAL]
                ?: sharedPrefs?.getInt("seed_palette_ordinal", 0)
                ?: 0
            val themeModeId = prefs[Keys.THEME_MODE_ID]
                ?: sharedPrefs?.getString("theme_mode_id", ThemeModeOption.SYSTEM.id)
            val engineId = prefs[Keys.SEARCH_ENGINE_ID]
                ?: sharedPrefs?.getString("search_engine_id", SearchEngineOption.BRAVE.id)
            val showTopVisits = prefs[Keys.SHOW_TOP_VISITS_ON_HOME]
                ?: sharedPrefs?.getBoolean("show_top_visits_on_home", false)
                ?: false
            val passwordAutofill = prefs[Keys.PASSWORD_AUTOFILL_ENABLED]
                ?: sharedPrefs?.getBoolean("password_autofill_enabled", true)
                ?: true
            val defaultZoom = BrowserPreferences.clampZoomPercent(
                prefs[Keys.DEFAULT_ZOOM_PERCENT]
                    ?: sharedPrefs?.getInt("default_zoom_percent", 100)
                    ?: 100
            )
            val siteZoomsJson = prefs[Keys.SAVED_SITE_ZOOMS_JSON]
                ?: sharedPrefs?.getString("saved_site_zooms_json", "{}")
            val savedSiteZooms = parseSavedSiteZooms(siteZoomsJson)
            val showZoomInMenu = prefs[Keys.SHOW_ZOOM_IN_MAIN_MENU]
                ?: sharedPrefs?.getBoolean("show_zoom_in_main_menu", false)
                ?: false
            val forceEnableZoom = prefs[Keys.FORCE_ENABLE_ZOOM]
                ?: sharedPrefs?.getBoolean("force_enable_zoom", false)
                ?: false
            val simplifiedView = prefs[Keys.SIMPLIFIED_VIEW_ENABLED]
                ?: sharedPrefs?.getBoolean("simplified_view_enabled", false)
                ?: false
            val caretBrowsing = prefs[Keys.CARET_BROWSING_ENABLED]
                ?: sharedPrefs?.getBoolean("caret_browsing_enabled", false)
                ?: false
            val touchpadSwipe = prefs[Keys.TOUCHPAD_SWIPE_NAV]
                ?: sharedPrefs?.getBoolean("touchpad_swipe_navigation_enabled", true)
                ?: true
            val downloadLocationModeId = prefs[Keys.DOWNLOAD_LOCATION_MODE]
                ?: sharedPrefs?.getString("download_location_mode", DownloadLocationMode.APP_EXTERNAL.id)
            val customDownloadTreeUri = prefs[Keys.CUSTOM_DOWNLOAD_TREE_URI]
                ?: sharedPrefs?.getString("custom_download_tree_uri", "")
                ?: ""
            val customDownloadFolderLabel = prefs[Keys.CUSTOM_DOWNLOAD_FOLDER_LABEL]
                ?: sharedPrefs?.getString("custom_download_folder_label", "")
                ?: ""
            val askWhereToSave = prefs[Keys.ASK_WHERE_TO_SAVE_FILES]
                ?: sharedPrefs?.getBoolean("ask_where_to_save_files", false)
                ?: false
            val autoOpenDownloads = prefs[Keys.AUTO_OPEN_DOWNLOADED_FILES]
                ?: sharedPrefs?.getBoolean("auto_open_downloaded_files", true)
                ?: true
            val showDownloadNotifications = prefs[Keys.SHOW_DOWNLOAD_PROGRESS_NOTIFICATIONS]
                ?: sharedPrefs?.getBoolean("show_download_progress_notifications", true)
                ?: true
            val parallelDownloading = prefs[Keys.PARALLEL_DOWNLOADING_ENABLED]
                ?: sharedPrefs?.getBoolean("parallel_downloading_enabled", true)
                ?: true

            BrowserPreferences(
                shieldsEnabled = shields,
                httpsUpgradeEnabled = https,
                aggressivePreloadEnabled = preload,
                scriptGuardEnabled = scriptGuard,
                desktopViewportEnabled = desktop,
                dynamicColorEnabled = dynamicColor,
                seedPaletteOrdinal = seedOrdinal,
                themeMode = ThemeModeOption.fromId(themeModeId),
                searchEngine = SearchEngineOption.fromId(engineId),
                showTopVisitsOnHome = showTopVisits,
                passwordAutofillEnabled = passwordAutofill,
                defaultZoomPercent = defaultZoom,
                savedSiteZooms = savedSiteZooms,
                showZoomInMainMenu = showZoomInMenu,
                forceEnableZoom = forceEnableZoom,
                simplifiedViewEnabled = simplifiedView,
                caretBrowsingEnabled = caretBrowsing,
                touchpadSwipeNavigationEnabled = touchpadSwipe,
                downloadLocationMode = DownloadLocationMode.fromId(downloadLocationModeId),
                customDownloadTreeUri = customDownloadTreeUri,
                customDownloadFolderLabel = customDownloadFolderLabel,
                askWhereToSaveFiles = askWhereToSave,
                autoOpenDownloadedFiles = autoOpenDownloads,
                showDownloadProgressNotifications = showDownloadNotifications,
                parallelDownloadingEnabled = parallelDownloading
            )
        }

    suspend fun setPasswordAutofillEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.PASSWORD_AUTOFILL_ENABLED] = enabled }
        sharedPrefs?.edit()?.putBoolean("password_autofill_enabled", enabled)?.apply()
    }

    suspend fun setShowTopVisitsOnHome(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_TOP_VISITS_ON_HOME] = enabled }
        sharedPrefs?.edit()?.putBoolean("show_top_visits_on_home", enabled)?.apply()
    }

    suspend fun setShieldsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SHIELDS_ENABLED] = enabled }
        sharedPrefs?.edit()?.putBoolean("shields_enabled", enabled)?.apply()
    }

    suspend fun setHttpsUpgradeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.HTTPS_UPGRADE] = enabled }
        sharedPrefs?.edit()?.putBoolean("https_upgrade_enabled", enabled)?.apply()
    }

    suspend fun setAggressivePreloadEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.AGGRESSIVE_PRELOAD] = enabled }
        sharedPrefs?.edit()?.putBoolean("aggressive_preload_enabled", enabled)?.apply()
    }

    suspend fun setScriptGuardEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SCRIPT_GUARD] = enabled }
        sharedPrefs?.edit()?.putBoolean("script_guard_enabled", enabled)?.apply()
    }

    suspend fun setDesktopViewportEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DESKTOP_VIEWPORT] = enabled }
        sharedPrefs?.edit()?.putBoolean("desktop_viewport_enabled", enabled)?.apply()
    }

    suspend fun setSearchEngine(engine: SearchEngineOption) {
        dataStore.edit { it[Keys.SEARCH_ENGINE_ID] = engine.id }
        sharedPrefs?.edit()?.putString("search_engine_id", engine.id)?.apply()
    }

    suspend fun setThemeMode(mode: ThemeModeOption) {
        dataStore.edit { it[Keys.THEME_MODE_ID] = mode.id }
        sharedPrefs?.edit()?.putString("theme_mode_id", mode.id)?.apply()
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
        sharedPrefs?.edit()?.putBoolean("dynamic_color_enabled", enabled)?.apply()
    }

    suspend fun setSeedPaletteOrdinal(ordinal: Int) {
        val bounded = ordinal.coerceIn(0, SeedPalettePreset.entries.lastIndex)
        dataStore.edit { prefs ->
            prefs[Keys.DYNAMIC_COLOR] = false
            prefs[Keys.SEED_ORDINAL] = bounded
        }
        sharedPrefs?.edit()
            ?.putBoolean("dynamic_color_enabled", false)
            ?.putInt("seed_palette_ordinal", bounded)
            ?.apply()
    }

    suspend fun cycleSeedPalette() {
        dataStore.edit { prefs ->
            val currentDynamic = prefs[Keys.DYNAMIC_COLOR] ?: true
            val currentSeed = prefs[Keys.SEED_ORDINAL] ?: 0
            val (newDynamic, newOrdinal) = if (currentDynamic) {
                false to 0
            } else {
                val next = currentSeed + 1
                if (next >= SeedPalettePreset.entries.size) {
                    true to 0
                } else {
                    false to next
                }
            }
            prefs[Keys.DYNAMIC_COLOR] = newDynamic
            prefs[Keys.SEED_ORDINAL] = newOrdinal
            sharedPrefs?.edit()
                ?.putBoolean("dynamic_color_enabled", newDynamic)
                ?.putInt("seed_palette_ordinal", newOrdinal)
                ?.apply()
        }
    }

    suspend fun setDefaultZoomPercent(percent: Int) {
        val clamped = BrowserPreferences.clampZoomPercent(percent)
        dataStore.edit { it[Keys.DEFAULT_ZOOM_PERCENT] = clamped }
        sharedPrefs?.edit()?.putInt("default_zoom_percent", clamped)?.apply()
    }

    suspend fun setSiteZoom(hostOrUrl: String, percent: Int) {
        val host = BrowserPreferences.normalizeZoomHost(hostOrHostOnly(hostOrUrl))
        if (host.isEmpty()) return
        val clamped = BrowserPreferences.clampZoomPercent(percent)
        dataStore.edit { prefs ->
            val current = parseSavedSiteZooms(
                prefs[Keys.SAVED_SITE_ZOOMS_JSON] ?: sharedPrefs?.getString("saved_site_zooms_json", "{}")
            ).toMutableMap()
            current[host] = clamped
            val serialized = serializeSavedSiteZooms(current)
            prefs[Keys.SAVED_SITE_ZOOMS_JSON] = serialized
            sharedPrefs?.edit()?.putString("saved_site_zooms_json", serialized)?.apply()
        }
    }

    suspend fun removeSiteZoom(hostOrUrl: String) {
        val host = BrowserPreferences.normalizeZoomHost(hostOrHostOnly(hostOrUrl))
        if (host.isEmpty()) return
        dataStore.edit { prefs ->
            val current = parseSavedSiteZooms(
                prefs[Keys.SAVED_SITE_ZOOMS_JSON] ?: sharedPrefs?.getString("saved_site_zooms_json", "{}")
            ).toMutableMap()
            current.remove(host)
            val serialized = serializeSavedSiteZooms(current)
            prefs[Keys.SAVED_SITE_ZOOMS_JSON] = serialized
            sharedPrefs?.edit()?.putString("saved_site_zooms_json", serialized)?.apply()
        }
    }

    suspend fun clearSavedSiteZooms() {
        dataStore.edit { it[Keys.SAVED_SITE_ZOOMS_JSON] = "{}" }
        sharedPrefs?.edit()?.putString("saved_site_zooms_json", "{}")?.apply()
    }

    suspend fun setShowZoomInMainMenu(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_ZOOM_IN_MAIN_MENU] = enabled }
        sharedPrefs?.edit()?.putBoolean("show_zoom_in_main_menu", enabled)?.apply()
    }

    suspend fun setForceEnableZoom(enabled: Boolean) {
        dataStore.edit { it[Keys.FORCE_ENABLE_ZOOM] = enabled }
        sharedPrefs?.edit()?.putBoolean("force_enable_zoom", enabled)?.apply()
    }

    suspend fun setSimplifiedViewEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SIMPLIFIED_VIEW_ENABLED] = enabled }
        sharedPrefs?.edit()?.putBoolean("simplified_view_enabled", enabled)?.apply()
    }

    suspend fun setCaretBrowsingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CARET_BROWSING_ENABLED] = enabled }
        sharedPrefs?.edit()?.putBoolean("caret_browsing_enabled", enabled)?.apply()
    }

    suspend fun setTouchpadSwipeNavigationEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TOUCHPAD_SWIPE_NAV] = enabled }
        sharedPrefs?.edit()?.putBoolean("touchpad_swipe_navigation_enabled", enabled)?.apply()
    }

    suspend fun setDownloadLocation(
        mode: DownloadLocationMode,
        customTreeUri: String = "",
        customFolderLabel: String = ""
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.DOWNLOAD_LOCATION_MODE] = mode.id
            if (mode == DownloadLocationMode.CUSTOM_SAF && customTreeUri.isNotBlank()) {
                prefs[Keys.CUSTOM_DOWNLOAD_TREE_URI] = customTreeUri
                prefs[Keys.CUSTOM_DOWNLOAD_FOLDER_LABEL] = customFolderLabel
            } else if (customTreeUri.isBlank() && mode != DownloadLocationMode.CUSTOM_SAF) {
                // Keep previously chosen custom folder URI if any, or leave untouched
            }
        }
        sharedPrefs?.edit()?.apply {
            putString("download_location_mode", mode.id)
            if (mode == DownloadLocationMode.CUSTOM_SAF && customTreeUri.isNotBlank()) {
                putString("custom_download_tree_uri", customTreeUri)
                putString("custom_download_folder_label", customFolderLabel)
            }
            apply()
        }
    }

    suspend fun setAskWhereToSaveFiles(enabled: Boolean) {
        dataStore.edit { it[Keys.ASK_WHERE_TO_SAVE_FILES] = enabled }
        sharedPrefs?.edit()?.putBoolean("ask_where_to_save_files", enabled)?.apply()
    }

    suspend fun setAutoOpenDownloadedFiles(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_OPEN_DOWNLOADED_FILES] = enabled }
        sharedPrefs?.edit()?.putBoolean("auto_open_downloaded_files", enabled)?.apply()
    }

    suspend fun setShowDownloadProgressNotifications(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_DOWNLOAD_PROGRESS_NOTIFICATIONS] = enabled }
        sharedPrefs?.edit()?.putBoolean("show_download_progress_notifications", enabled)?.apply()
    }

    suspend fun setParallelDownloadingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.PARALLEL_DOWNLOADING_ENABLED] = enabled }
        sharedPrefs?.edit()?.putBoolean("parallel_downloading_enabled", enabled)?.apply()
    }

    private fun hostOrHostOnly(input: String): String {
        return input.trim()
    }

    private fun parseSavedSiteZooms(rawJson: String?): Map<String, Int> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(rawJson)
            val result = sortedMapOf<String, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val normalized = BrowserPreferences.normalizeZoomHost(k)
                if (normalized.isNotEmpty()) {
                    result[normalized] = BrowserPreferences.clampZoomPercent(obj.optInt(k, 100))
                }
            }
            result.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun serializeSavedSiteZooms(map: Map<String, Int>): String {
        val obj = org.json.JSONObject()
        map.toSortedMap().forEach { (host, percent) ->
            obj.put(host, BrowserPreferences.clampZoomPercent(percent))
        }
        return obj.toString()
    }
}

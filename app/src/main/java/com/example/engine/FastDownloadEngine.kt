package com.example.engine

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import com.example.data.local.DownloadItemEntity
import com.example.data.preferences.BrowserPreferences
import com.example.data.preferences.DownloadLocationMode
import com.example.data.repository.BrowserRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * High-speed 8× Parallel Multi-Chunk Turbo & 128 KB NIO Buffered Download Engine.
 *
 * Features:
 * - Process-wide survival with `DownloadForegroundService` (`dataSync`) so switching to other apps
 *   or switching browser tabs never interrupts active downloads.
 * - True 8× Parallel HTTP `Range` Turbo acceleration using per-chunk `.part` files and zero-copy
 *   NIO `FileChannel.transferTo` merging.
 * - Manual cross-protocol & cross-host redirect resolution (`openConnectionWithRedirects`) that
 *   preserves `Range`, `Cookie`, and `User-Agent` headers across CDN hops (GitHub Releases, S3,
 *   Cloudflare, SourceForge, etc.).
 * - Automatic per-chunk and single-stream socket retry with exponential backoff so transient
 *   network handoffs or background switches recover seamlessly from the exact byte offset.
 * - Non-destructive pause, resume, and smart retry that preserve `.part` files on disk.
 */
class FastDownloadEngine(
    private val context: Context,
    private val repository: BrowserRepository
) {
    private val appContext: Context = context.applicationContext
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val pausedFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val canceledFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val activeConnections = ConcurrentHashMap<Long, MutableSet<HttpURLConnection>>()
    private val downloadMutexes = ConcurrentHashMap<Long, Mutex>()
    private val downloadUserAgents = ConcurrentHashMap<Long, String>()
    private val downloadSafTargets = ConcurrentHashMap<Long, String>()
    private val notificationHelper = DownloadNotificationHelper(appContext)
    private val lastForegroundSyncMs = AtomicLong(0L)
    @Volatile
    private var currentPreferences: BrowserPreferences = BrowserPreferences()
    private val _autoOpenEvents = MutableSharedFlow<DownloadItemEntity>(extraBufferCapacity = 8)
    val autoOpenEvents: SharedFlow<DownloadItemEntity> = _autoOpenEvents.asSharedFlow()

    init {
        engineScope.launch {
            repository.preferencesFlow.collect { prefs ->
                updateDownloadSettings(prefs)
            }
        }
        resumeStrandedDownloadsOnStartup()
    }

    fun updateDownloadSettings(prefs: BrowserPreferences) {
        val prevShowNotifications = currentPreferences.showDownloadProgressNotifications
        currentPreferences = prefs
        if (prevShowNotifications && !prefs.showDownloadProgressNotifications) {
            notificationHelper.cancelAll()
            DownloadForegroundService.stop(appContext)
        }
    }

    fun notifyExternalState(item: DownloadItemEntity) {
        if (!currentPreferences.showDownloadProgressNotifications &&
            (item.status == STATUS_DOWNLOADING || item.status == STATUS_PAUSED)
        ) {
            notificationHelper.cancel(item.id)
            return
        }
        notificationHelper.notifyDownloadState(item, forceImmediate = true)
    }

    private fun notifyStateRespectingPreference(item: DownloadItemEntity, forceImmediate: Boolean) {
        if (!currentPreferences.showDownloadProgressNotifications) {
            notificationHelper.cancel(item.id)
            return
        }
        notificationHelper.notifyDownloadState(item, forceImmediate = forceImmediate)
    }

    private val defaultUserAgent =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun mutexFor(downloadId: Long): Mutex {
        return downloadMutexes.computeIfAbsent(downloadId) { Mutex() }
    }

    private fun pauseFlagFor(downloadId: Long, defaultValue: Boolean = false): AtomicBoolean {
        return pausedFlags.computeIfAbsent(downloadId) { AtomicBoolean(defaultValue) }
    }

    private fun cancelFlagFor(downloadId: Long, defaultValue: Boolean = false): AtomicBoolean {
        return canceledFlags.computeIfAbsent(downloadId) { AtomicBoolean(defaultValue) }
    }

    private fun registerConnection(downloadId: Long, conn: HttpURLConnection) {
        activeConnections.computeIfAbsent(downloadId) { ConcurrentHashMap.newKeySet() }.add(conn)
    }

    private fun unregisterConnection(downloadId: Long, conn: HttpURLConnection) {
        activeConnections[downloadId]?.remove(conn)
    }

    private fun disconnectAllConnections(downloadId: Long) {
        val conns = activeConnections.remove(downloadId) ?: return
        conns.forEach { conn ->
            runCatching { conn.disconnect() }
        }
    }

    private fun syncForegroundService(item: DownloadItemEntity? = null, force: Boolean = false) {
        if (!currentPreferences.showDownloadProgressNotifications) {
            DownloadForegroundService.stop(appContext)
            return
        }
        val runningCount = activeJobs.count { (_, job) -> job.isActive }
        if (runningCount <= 0 && item?.status != STATUS_DOWNLOADING) {
            DownloadForegroundService.stop(appContext)
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastForegroundSyncMs.get() < 900L) {
            return
        }
        lastForegroundSyncMs.set(now)

        val activeCount = max(runningCount, 1)
        val title = item?.fileName?.takeIf { it.isNotBlank() }
            ?: if (activeCount == 1) "Downloading file…" else "Downloading $activeCount files…"
        val subtitle = if (item != null) {
            val dl = formatBytes(item.downloadedBytes)
            val total = if (item.totalBytes > 0L) " / ${formatBytes(item.totalBytes)}" else ""
            val speed = if (item.speedBytesPerSec > 0L) " • ${formatSpeed(item.speedBytesPerSec)}" else ""
            val turbo = if (item.chunksCount > 1) " (${item.chunksCount}× Turbo)" else ""
            "$dl$total$speed$turbo"
        } else {
            "${DEFAULT_TURBO_CHUNKS}× Parallel Turbo Engine active"
        }
        val percent = if (item != null && item.totalBytes > 0L) {
            ((item.downloadedBytes * 100L) / item.totalBytes).toInt().coerceIn(0, 100)
        } else {
            -1
        }
        DownloadForegroundService.startOrUpdate(
            context = appContext,
            activeCount = activeCount,
            title = title,
            subtitle = subtitle,
            progressPercent = percent
        )
    }

    private fun resumeStrandedDownloadsOnStartup() {
        engineScope.launch {
            runCatching {
                val all = repository.getAllDownloadsOnce()
                all.filter { it.status == STATUS_DOWNLOADING }.forEach { stranded ->
                    if (activeJobs[stranded.id]?.isActive != true) {
                        val diskBytes = computeCurrentDiskBytes(stranded)
                        val resumed = stranded.copy(
                            downloadedBytes = max(stranded.downloadedBytes, diskBytes),
                            speedBytesPerSec = 0L,
                            errorMessage = null
                        )
                        repository.upsertDownload(resumed)
                        startOrResumeJob(resumed, downloadUserAgents[resumed.id] ?: defaultUserAgent)
                    }
                }
            }
        }
    }

    /**
     * Automatically resumes any interrupted or stranded downloads when network connectivity returns.
     */
    fun onNetworkAvailable() {
        engineScope.launch {
            runCatching {
                val all = repository.getAllDownloadsOnce()
                all.forEach { item ->
                    val isRunning = activeJobs[item.id]?.isActive == true
                    val isStrandedActive = item.status == STATUS_DOWNLOADING && !isRunning
                    val isTransientNetworkFailure = item.status == STATUS_FAILED &&
                        !isRunning &&
                        (computeCurrentDiskBytes(item) > 0L || item.downloadedBytes > 0L) &&
                        pausedFlags[item.id]?.get() != true &&
                        canceledFlags[item.id]?.get() != true
                    if (isStrandedActive || isTransientNetworkFailure) {
                        resumeDownload(item.id)
                    }
                }
            }
        }
    }

    fun getDownloadsDirectory(
        mode: DownloadLocationMode = currentPreferences.downloadLocationMode
    ): File {
        return DownloadStorageHelper.resolveDirectory(appContext, mode)
    }

    fun enqueueDownload(
        url: String,
        suggestedFileName: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
        contentLength: Long = -1L,
        userAgent: String? = null,
        locationModeOverride: DownloadLocationMode? = null,
        customTreeUriOverride: String? = null,
        onStarted: (DownloadItemEntity) -> Unit = {}
    ) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return

        engineScope.launch {
            val prefs = currentPreferences
            val effectiveMode = locationModeOverride ?: prefs.downloadLocationMode
            val effectiveCustomUri = customTreeUriOverride?.takeIf { it.isNotBlank() }
                ?: prefs.customDownloadTreeUri
            val resolvedMime = resolveMimeType(trimmedUrl, mimeType)
            val rawName = when {
                !suggestedFileName.isNullOrBlank() && suggestedFileName.contains('.') ->
                    sanitizeFileName(suggestedFileName)
                !suggestedFileName.isNullOrBlank() ->
                    sanitizeFileName(suggestedFileName)
                else -> sanitizeFileName(
                    URLUtil.guessFileName(trimmedUrl, contentDisposition, resolvedMime)
                )
            }
            val uniqueFile = allocateUniqueFile(getDownloadsDirectory(effectiveMode), rawName)
            val category = inferCategory(uniqueFile.name, resolvedMime)
            val initialChunks = when {
                !prefs.parallelDownloadingEnabled -> 1
                trimmedUrl.startsWith("data:", ignoreCase = true) -> 1
                contentLength in 1 until MIN_PARALLEL_BYTES -> 1
                contentLength in MIN_PARALLEL_BYTES until HIGH_TURBO_THRESHOLD_BYTES -> MEDIUM_TURBO_CHUNKS
                else -> DEFAULT_TURBO_CHUNKS
            }

            val initialEntity = DownloadItemEntity(
                fileName = uniqueFile.name,
                url = trimmedUrl,
                mimeType = resolvedMime,
                category = category,
                totalBytes = if (contentLength > 0) contentLength else -1L,
                downloadedBytes = 0L,
                speedBytesPerSec = 0L,
                chunksCount = initialChunks,
                status = STATUS_DOWNLOADING,
                localFilePath = uniqueFile.absolutePath,
                createdAt = System.currentTimeMillis()
            )

            val id = repository.upsertDownload(initialEntity)
            val savedEntity = initialEntity.copy(id = id)
            if (effectiveMode == DownloadLocationMode.CUSTOM_SAF && effectiveCustomUri.isNotBlank()) {
                downloadSafTargets[id] = effectiveCustomUri
            }
            notifyStateRespectingPreference(savedEntity, forceImmediate = true)
            val effectiveUa = userAgent ?: defaultUserAgent
            downloadUserAgents[id] = effectiveUa
            val pFlag = pauseFlagFor(id, defaultValue = false)
            val cFlag = cancelFlagFor(id, defaultValue = false)

            engineScope.launch(Dispatchers.Main) {
                onStarted(savedEntity)
            }

            if (pFlag.get() || cFlag.get()) {
                return@launch
            }
            startOrResumeJob(savedEntity, effectiveUa)
        }
    }

    fun pauseDownload(downloadId: Long) {
        val pFlag = pauseFlagFor(downloadId, defaultValue = true)
        pFlag.set(true)

        engineScope.launch {
            disconnectAllConnections(downloadId)
            val runningJob = activeJobs.remove(downloadId)
            runningJob?.cancel()
            runCatching { runningJob?.join() }

            withContext(NonCancellable) {
                mutexFor(downloadId).withLock {
                    val existing = repository.getDownloadById(downloadId) ?: return@withLock
                    if (existing.status == STATUS_DOWNLOADING ||
                        existing.status == STATUS_PAUSED ||
                        existing.status == STATUS_FAILED
                    ) {
                        val bestBytes = computeCurrentDiskBytes(existing).let { diskBytes ->
                            val maxKnown = maxOf(existing.downloadedBytes, diskBytes)
                            if (existing.totalBytes > 0L) {
                                maxKnown.coerceAtMost(existing.totalBytes)
                            } else {
                                maxKnown
                            }
                        }
                        val pausedItem = existing.copy(
                            status = STATUS_PAUSED,
                            downloadedBytes = bestBytes,
                            speedBytesPerSec = 0L,
                            errorMessage = null
                        )
                        repository.upsertDownload(pausedItem)
                        notifyStateRespectingPreference(pausedItem, forceImmediate = true)
                    }
                }
                syncForegroundService(force = true)
            }
        }
    }

    fun resumeDownload(downloadId: Long) {
        val pFlag = pauseFlagFor(downloadId, defaultValue = false)
        val cFlag = cancelFlagFor(downloadId, defaultValue = false)
        pFlag.set(false)
        cFlag.set(false)

        engineScope.launch {
            val itemToStart = mutexFor(downloadId).withLock {
                val existing = repository.getDownloadById(downloadId) ?: return@withLock null
                if (existing.status == STATUS_COMPLETED || existing.status == STATUS_CANCELED) {
                    return@withLock null
                }
                if (existing.status == STATUS_DOWNLOADING && activeJobs[downloadId]?.isActive == true) {
                    return@withLock null
                }
                if (pFlag.get() || cFlag.get()) {
                    return@withLock null
                }
                val diskBytes = computeCurrentDiskBytes(existing)
                val resumedBytes = max(existing.downloadedBytes, diskBytes).let { raw ->
                    if (existing.totalBytes > 0L) raw.coerceAtMost(existing.totalBytes) else raw
                }
                val updated = existing.copy(
                    status = STATUS_DOWNLOADING,
                    downloadedBytes = resumedBytes,
                    errorMessage = null,
                    speedBytesPerSec = 0L
                )
                repository.upsertDownload(updated)
                notifyStateRespectingPreference(updated, forceImmediate = true)
                updated
            } ?: return@launch

            startOrResumeJob(itemToStart, downloadUserAgents[downloadId] ?: defaultUserAgent)
        }
    }

    fun retryDownload(downloadId: Long, forceCleanRestart: Boolean = false) {
        val pFlag = pauseFlagFor(downloadId, defaultValue = false)
        val cFlag = cancelFlagFor(downloadId, defaultValue = false)
        pFlag.set(false)
        cFlag.set(false)

        engineScope.launch {
            disconnectAllConnections(downloadId)
            val prevJob = activeJobs.remove(downloadId)
            prevJob?.cancel()
            runCatching { prevJob?.join() }

            val itemToStart = mutexFor(downloadId).withLock {
                val existing = repository.getDownloadById(downloadId) ?: return@withLock null
                val diskBytes = computeCurrentDiskBytes(existing)
                val canResumePartial = !forceCleanRestart &&
                    existing.status != STATUS_COMPLETED &&
                    existing.localFilePath.isNotBlank() &&
                    diskBytes > 0L

                if (canResumePartial) {
                    val resumedBytes = max(existing.downloadedBytes, diskBytes).let { raw ->
                        if (existing.totalBytes > 0L) raw.coerceAtMost(existing.totalBytes) else raw
                    }
                    val resumedItem = existing.copy(
                        downloadedBytes = resumedBytes,
                        speedBytesPerSec = 0L,
                        status = STATUS_DOWNLOADING,
                        errorMessage = null,
                        completedAt = null
                    )
                    repository.upsertDownload(resumedItem)
                    notifyStateRespectingPreference(resumedItem, forceImmediate = true)
                    return@withLock resumedItem
                }

                if (existing.localFilePath.isNotBlank()) {
                    deleteFileAndParts(File(existing.localFilePath))
                }
                val targetFile = if (existing.localFilePath.isNotBlank() &&
                    !existing.localFilePath.startsWith("content://", ignoreCase = true)
                ) {
                    File(existing.localFilePath)
                } else {
                    allocateUniqueFile(getDownloadsDirectory(), existing.fileName)
                }
                val resetItem = existing.copy(
                    downloadedBytes = 0L,
                    speedBytesPerSec = 0L,
                    chunksCount = if (currentPreferences.parallelDownloadingEnabled) DEFAULT_TURBO_CHUNKS else 1,
                    status = STATUS_DOWNLOADING,
                    localFilePath = targetFile.absolutePath,
                    errorMessage = null,
                    completedAt = null
                )
                repository.upsertDownload(resetItem)
                notifyStateRespectingPreference(resetItem, forceImmediate = true)
                resetItem
            } ?: return@launch

            startOrResumeJob(itemToStart, downloadUserAgents[downloadId] ?: defaultUserAgent)
        }
    }

    fun cancelDownload(downloadId: Long) {
        val cFlag = cancelFlagFor(downloadId, defaultValue = true)
        cFlag.set(true)
        pauseFlagFor(downloadId, defaultValue = false).set(false)

        engineScope.launch {
            disconnectAllConnections(downloadId)
            val prevJob = activeJobs.remove(downloadId)
            prevJob?.cancel()
            runCatching { prevJob?.join() }

            withContext(NonCancellable) {
                mutexFor(downloadId).withLock {
                    val existing = repository.getDownloadById(downloadId) ?: return@withLock
                    if (existing.localFilePath.isNotBlank()) {
                        deleteFileAndParts(File(existing.localFilePath))
                    }
                    repository.upsertDownload(
                        existing.copy(
                            status = STATUS_CANCELED,
                            speedBytesPerSec = 0L
                        )
                    )
                    notificationHelper.cancel(downloadId)
                }
                syncForegroundService(force = true)
            }
        }
    }

    fun deleteDownload(downloadId: Long, deleteFileFromDisk: Boolean = true) {
        cancelFlagFor(downloadId, defaultValue = true).set(true)
        pauseFlagFor(downloadId, defaultValue = false).set(false)

        engineScope.launch {
            disconnectAllConnections(downloadId)
            val prevJob = activeJobs.remove(downloadId)
            prevJob?.cancel()
            runCatching { prevJob?.join() }

            withContext(NonCancellable) {
                mutexFor(downloadId).withLock {
                    val existing = repository.getDownloadById(downloadId)
                    if (deleteFileFromDisk && existing != null && existing.localFilePath.isNotBlank()) {
                        deleteFileAndParts(File(existing.localFilePath))
                    }
                    repository.deleteDownloadById(downloadId)
                }
                notificationHelper.cancel(downloadId)
                pausedFlags.remove(downloadId)
                canceledFlags.remove(downloadId)
                downloadUserAgents.remove(downloadId)
                downloadSafTargets.remove(downloadId)
                downloadMutexes.remove(downloadId)
                syncForegroundService(force = true)
            }
        }
    }

    fun clearAllDownloads() {
        val ids = activeJobs.keys.toList()
        ids.forEach { id ->
            cancelFlagFor(id, defaultValue = true).set(true)
            pauseFlagFor(id, defaultValue = false).set(false)
        }
        engineScope.launch {
            ids.forEach { id ->
                disconnectAllConnections(id)
                activeJobs.remove(id)?.cancel()
            }
            withContext(NonCancellable) {
                DownloadLocationMode.entries.forEach { mode ->
                    if (mode != DownloadLocationMode.CUSTOM_SAF) {
                        getDownloadsDirectory(mode).listFiles()?.forEach { file ->
                            runCatching { file.delete() }
                        }
                    }
                }
                repository.clearAllDownloads()
                notificationHelper.cancelAll()
                pausedFlags.clear()
                canceledFlags.clear()
                downloadUserAgents.clear()
                downloadSafTargets.clear()
                downloadMutexes.clear()
                syncForegroundService(force = true)
            }
        }
    }

    private fun startOrResumeJob(item: DownloadItemEntity, userAgent: String) {
        activeJobs.remove(item.id)?.cancel()
        val pauseFlag = pauseFlagFor(item.id, defaultValue = false)
        val cancelFlag = cancelFlagFor(item.id, defaultValue = false)

        val job = engineScope.launch {
            val latestDownloaded = AtomicLong(item.downloadedBytes.coerceAtLeast(0L))
            val latestTotal = AtomicLong(item.totalBytes)
            syncForegroundService(item, force = true)
            try {
                if (pauseFlag.get()) {
                    persistPausedState(item.id, latestDownloaded.get(), latestTotal.get())
                    return@launch
                }
                if (cancelFlag.get()) return@launch

                when {
                    item.url.startsWith("data:", ignoreCase = true) -> {
                        executeDataUrlDownload(item, pauseFlag, cancelFlag)
                    }
                    else -> {
                        executeHttpFastDownload(
                            item = item,
                            userAgent = userAgent,
                            pauseFlag = pauseFlag,
                            cancelFlag = cancelFlag,
                            latestDownloaded = latestDownloaded,
                            latestTotal = latestTotal
                        )
                    }
                }
            } catch (ce: CancellationException) {
                if (pauseFlag.get() && !cancelFlag.get()) {
                    withContext(NonCancellable) {
                        persistPausedState(item.id, latestDownloaded.get(), latestTotal.get())
                    }
                }
                throw ce
            } catch (e: Exception) {
                if (pauseFlag.get() && !cancelFlag.get()) {
                    withContext(NonCancellable) {
                        persistPausedState(item.id, latestDownloaded.get(), latestTotal.get())
                    }
                } else if (!cancelFlag.get()) {
                    withContext(NonCancellable) {
                        mutexFor(item.id).withLock {
                            val latest = repository.getDownloadById(item.id) ?: item
                            if (latest.status != STATUS_PAUSED &&
                                latest.status != STATUS_CANCELED &&
                                latest.status != STATUS_COMPLETED
                            ) {
                                val diskBytes = computeCurrentDiskBytes(latest)
                                val savedBytes = maxOf(
                                    latest.downloadedBytes,
                                    latestDownloaded.get(),
                                    diskBytes
                                ).let { raw ->
                                    val resolvedTotal = if (latest.totalBytes > 0L) {
                                        latest.totalBytes
                                    } else {
                                        latestTotal.get()
                                    }
                                    if (resolvedTotal > 0L) raw.coerceAtMost(resolvedTotal) else raw
                                }
                                val failedItem = latest.copy(
                                    status = STATUS_FAILED,
                                    downloadedBytes = savedBytes,
                                    speedBytesPerSec = 0L,
                                    errorMessage = e.message?.takeIf { it.isNotBlank() }
                                        ?: "Connection interrupted — tap Resume to continue"
                                )
                                repository.upsertDownload(failedItem)
                                notifyStateRespectingPreference(failedItem, forceImmediate = true)
                            }
                        }
                    }
                }
            } finally {
                disconnectAllConnections(item.id)
                activeJobs.remove(item.id, coroutineContext[Job])
                syncForegroundService(force = true)
            }
        }
        activeJobs[item.id] = job
        syncForegroundService(item, force = true)
    }

    private suspend fun persistPausedState(
        downloadId: Long,
        fallbackBytes: Long,
        fallbackTotal: Long
    ) {
        mutexFor(downloadId).withLock {
            val latest = repository.getDownloadById(downloadId) ?: return@withLock
            if (latest.status == STATUS_CANCELED || latest.status == STATUS_COMPLETED) {
                return@withLock
            }
            val diskBytes = computeCurrentDiskBytes(latest)
            val resolvedTotal = if (latest.totalBytes > 0L) latest.totalBytes else fallbackTotal
            val bestBytes = maxOf(latest.downloadedBytes, fallbackBytes, diskBytes).let { raw ->
                if (resolvedTotal > 0L) raw.coerceAtMost(resolvedTotal) else raw
            }
            val pausedItem = latest.copy(
                status = STATUS_PAUSED,
                downloadedBytes = bestBytes,
                totalBytes = resolvedTotal,
                speedBytesPerSec = 0L,
                errorMessage = null
            )
            repository.upsertDownload(pausedItem)
            notifyStateRespectingPreference(pausedItem, forceImmediate = true)
        }
    }

    private suspend fun persistDownloadingProgress(
        downloadId: Long,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        chunksCount: Int,
        pauseFlag: AtomicBoolean,
        cancelFlag: AtomicBoolean
    ) {
        if (pauseFlag.get() || cancelFlag.get()) return
        mutexFor(downloadId).withLock {
            if (pauseFlag.get() || cancelFlag.get()) return@withLock
            val latest = repository.getDownloadById(downloadId) ?: return@withLock
            if (latest.status != STATUS_DOWNLOADING) return@withLock
            val clampedBytes = if (totalBytes > 0L) {
                downloadedBytes.coerceAtMost(totalBytes)
            } else {
                downloadedBytes
            }
            val updatedItem = latest.copy(
                downloadedBytes = max(latest.downloadedBytes, clampedBytes),
                totalBytes = if (totalBytes > 0L) totalBytes else latest.totalBytes,
                speedBytesPerSec = speedBytesPerSec.coerceAtLeast(0L),
                chunksCount = chunksCount,
                status = STATUS_DOWNLOADING
            )
            repository.upsertDownload(updatedItem)
            notifyStateRespectingPreference(updatedItem, forceImmediate = false)
            syncForegroundService(updatedItem, force = false)
        }
    }

    private suspend fun finalizeCompletedDownload(
        downloadId: Long,
        completedBase: DownloadItemEntity,
        targetFile: File
    ) {
        val explicitSafUri = downloadSafTargets.remove(downloadId)
        val effectiveSafUri = explicitSafUri ?: if (currentPreferences.downloadLocationMode == DownloadLocationMode.CUSTOM_SAF) {
            currentPreferences.customDownloadTreeUri.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val exportedSafUri = if (!effectiveSafUri.isNullOrBlank()) {
            DownloadStorageHelper.copyFileToSafTree(
                context = appContext,
                sourceFile = targetFile,
                treeUriString = effectiveSafUri,
                mimeType = completedBase.mimeType,
                fileName = completedBase.fileName
            )
        } else {
            null
        }
        val finalItem = if (exportedSafUri != null) {
            completedBase.copy(localFilePath = exportedSafUri.toString())
        } else {
            completedBase
        }
        repository.upsertDownload(finalItem)
        notifyStateRespectingPreference(finalItem, forceImmediate = true)
        if (currentPreferences.autoOpenDownloadedFiles) {
            _autoOpenEvents.tryEmit(finalItem)
        }
    }

    private suspend fun executeDataUrlDownload(
        item: DownloadItemEntity,
        pauseFlag: AtomicBoolean,
        cancelFlag: AtomicBoolean
    ) {
        if (pauseFlag.get() || cancelFlag.get()) return
        val targetFile = File(
            item.localFilePath.ifBlank {
                allocateUniqueFile(getDownloadsDirectory(), item.fileName).absolutePath
            }
        )
        targetFile.parentFile?.mkdirs()
        val payload = item.url.substringAfter(',', "")
        val meta = item.url.substringBefore(',', "")
        val bytes = if (meta.contains(";base64", ignoreCase = true)) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
        }
        if (pauseFlag.get() || cancelFlag.get()) return
        FileOutputStream(targetFile).use { it.write(bytes) }
        val total = bytes.size.toLong()
        mutexFor(item.id).withLock {
            if (pauseFlag.get()) {
                val latest = repository.getDownloadById(item.id) ?: item
                val pausedItem = latest.copy(
                    totalBytes = total,
                    downloadedBytes = total,
                    speedBytesPerSec = 0L,
                    chunksCount = 1,
                    status = STATUS_PAUSED,
                    localFilePath = targetFile.absolutePath
                )
                repository.upsertDownload(pausedItem)
                notifyStateRespectingPreference(pausedItem, forceImmediate = true)
                return@withLock
            }
            if (cancelFlag.get()) return@withLock
            val latest = repository.getDownloadById(item.id) ?: item
            val completedItem = latest.copy(
                totalBytes = total,
                downloadedBytes = total,
                speedBytesPerSec = max(total * 8L, 1_048_576L),
                chunksCount = 1,
                status = STATUS_COMPLETED,
                localFilePath = targetFile.absolutePath,
                completedAt = System.currentTimeMillis()
            )
            finalizeCompletedDownload(item.id, completedItem, targetFile)
        }
    }

    private suspend fun executeHttpFastDownload(
        item: DownloadItemEntity,
        userAgent: String,
        pauseFlag: AtomicBoolean,
        cancelFlag: AtomicBoolean,
        latestDownloaded: AtomicLong,
        latestTotal: AtomicLong
    ) {
        val targetFile = File(
            item.localFilePath.ifBlank {
                allocateUniqueFile(getDownloadsDirectory(), item.fileName).absolutePath
            }
        )
        targetFile.parentFile?.mkdirs()

        val cookieHeader = runCatching {
            CookieManager.getInstance().getCookie(item.url)
        }.getOrNull()

        val existingPartChunkCount = detectExistingPartChunkCount(targetFile, item.chunksCount)
        val hasExistingParts = existingPartChunkCount > 0
        val hasSinglePartialFile = targetFile.exists() && targetFile.length() > 0L && !hasExistingParts

        val probe = probeRemoteResource(
            downloadId = item.id,
            url = item.url,
            userAgent = userAgent,
            cookieHeader = cookieHeader,
            pauseFlag = pauseFlag,
            cancelFlag = cancelFlag
        )
        if (pauseFlag.get()) {
            withContext(NonCancellable) {
                persistPausedState(item.id, latestDownloaded.get(), latestTotal.get())
            }
            return
        }
        if (cancelFlag.get()) return

        val resolvedTotalBytes = when {
            hasExistingParts && item.totalBytes > 0L -> item.totalBytes
            probe.contentLength > 0L -> probe.contentLength
            else -> item.totalBytes
        }
        latestTotal.set(resolvedTotalBytes)

        val resolvedMime = probe.mimeType.ifBlank { item.mimeType }
        val resolvedFileName = if (probe.suggestedName.isNotBlank() &&
            (item.fileName.startsWith("downloadfile", true) || item.fileName.endsWith(".bin", true))
        ) {
            sanitizeFileName(probe.suggestedName)
        } else {
            item.fileName
        }
        val resolvedCategory = inferCategory(resolvedFileName, resolvedMime)
        val transferUrl = probe.finalUrl.ifBlank { item.url }

        // If part files already exist on disk and we know totalBytes, ALWAYS resume in parallel mode
        // with the exact same chunk layout so we never lose partial progress.
        val allowParallel = currentPreferences.parallelDownloadingEnabled
        val useParallelChunks = when {
            hasExistingParts && resolvedTotalBytes > 0L -> true
            !allowParallel -> false
            hasSinglePartialFile -> false
            probe.supportsRanges && resolvedTotalBytes >= MIN_PARALLEL_BYTES -> true
            else -> false
        }

        val chunkCount = when {
            hasExistingParts -> existingPartChunkCount
            !useParallelChunks -> 1
            resolvedTotalBytes >= HIGH_TURBO_THRESHOLD_BYTES -> DEFAULT_TURBO_CHUNKS
            else -> MEDIUM_TURBO_CHUNKS
        }

        val currentDiskBytes = computeCurrentDiskBytesForTarget(targetFile, chunkCount)
        if (currentDiskBytes > 0L) {
            latestDownloaded.set(max(latestDownloaded.get(), currentDiskBytes))
        }

        var workingItem = item
        val shouldAbort = mutexFor(item.id).withLock {
            if (pauseFlag.get() || cancelFlag.get()) return@withLock true
            val latest = repository.getDownloadById(item.id) ?: item
            if (latest.status != STATUS_DOWNLOADING) return@withLock true
            val bestDownloaded = maxOf(latest.downloadedBytes, latestDownloaded.get(), currentDiskBytes).let { raw ->
                if (resolvedTotalBytes > 0L) raw.coerceAtMost(resolvedTotalBytes) else raw
            }
            workingItem = latest.copy(
                fileName = resolvedFileName,
                mimeType = resolvedMime,
                category = resolvedCategory,
                totalBytes = resolvedTotalBytes,
                downloadedBytes = bestDownloaded,
                chunksCount = chunkCount,
                status = STATUS_DOWNLOADING,
                localFilePath = targetFile.absolutePath,
                errorMessage = null
            )
            repository.upsertDownload(workingItem)
            notifyStateRespectingPreference(workingItem, forceImmediate = true)
            syncForegroundService(workingItem, force = true)
            false
        }
        if (shouldAbort) {
            if (pauseFlag.get() && !cancelFlag.get()) {
                withContext(NonCancellable) {
                    persistPausedState(item.id, latestDownloaded.get(), resolvedTotalBytes)
                }
            }
            return
        }

        if (useParallelChunks) {
            try {
                downloadInParallelRanges(
                    item = workingItem,
                    transferUrl = transferUrl,
                    targetFile = targetFile,
                    totalBytes = resolvedTotalBytes,
                    chunks = chunkCount,
                    userAgent = userAgent,
                    cookieHeader = cookieHeader,
                    pauseFlag = pauseFlag,
                    cancelFlag = cancelFlag,
                    latestDownloaded = latestDownloaded
                )
            } catch (e: RangeNotSupportedException) {
                if (pauseFlag.get() || cancelFlag.get()) return
                // Server claimed Accept-Ranges: bytes in headers but rejected 206 Partial Content;
                // fall back cleanly to single-stream download without failing the user's download.
                deletePartFilesOnly(targetFile)
                latestDownloaded.set(0L)
                downloadSingleFastStream(
                    item = workingItem.copy(chunksCount = 1, downloadedBytes = 0L),
                    transferUrl = transferUrl,
                    targetFile = targetFile,
                    totalBytes = resolvedTotalBytes,
                    supportsRanges = false,
                    userAgent = userAgent,
                    cookieHeader = cookieHeader,
                    pauseFlag = pauseFlag,
                    cancelFlag = cancelFlag,
                    latestDownloaded = latestDownloaded,
                    latestTotal = latestTotal
                )
            }
        } else {
            downloadSingleFastStream(
                item = workingItem,
                transferUrl = transferUrl,
                targetFile = targetFile,
                totalBytes = resolvedTotalBytes,
                supportsRanges = probe.supportsRanges || hasSinglePartialFile,
                userAgent = userAgent,
                cookieHeader = cookieHeader,
                pauseFlag = pauseFlag,
                cancelFlag = cancelFlag,
                latestDownloaded = latestDownloaded,
                latestTotal = latestTotal
            )
        }
    }

    private suspend fun downloadInParallelRanges(
        item: DownloadItemEntity,
        transferUrl: String,
        targetFile: File,
        totalBytes: Long,
        chunks: Int,
        userAgent: String,
        cookieHeader: String?,
        pauseFlag: AtomicBoolean,
        cancelFlag: AtomicBoolean,
        latestDownloaded: AtomicLong
    ) = coroutineScope {
        val partSize = totalBytes / chunks
        val initialBytes = (0 until chunks).sumOf { index ->
            val startByte = index * partSize
            val endByte = if (index == chunks - 1) totalBytes - 1 else (startByte + partSize - 1)
            val expectedLen = (endByte - startByte) + 1L
            val partFile = partFileFor(targetFile, index)
            if (partFile.exists()) partFile.length().coerceAtMost(expectedLen) else 0L
        }
        val totalDownloaded = AtomicLong(initialBytes)
        latestDownloaded.set(initialBytes)
        val startTimeMs = System.currentTimeMillis()

        val progressReporter = launch {
            var lastBytes = initialBytes
            var lastTickMs = startTimeMs
            var smoothedSpeed = 0L
            while (isActive && !pauseFlag.get() && !cancelFlag.get()) {
                delay(180L)
                if (pauseFlag.get() || cancelFlag.get()) break
                val now = System.currentTimeMillis()
                val currentBytes = totalDownloaded.get()
                latestDownloaded.set(currentBytes)
                val dtMs = (now - lastTickMs).coerceAtLeast(1L)
                val instantSpeed = ((currentBytes - lastBytes).coerceAtLeast(0L) * 1000L) / dtMs
                smoothedSpeed = if (smoothedSpeed <= 0L) {
                    instantSpeed
                } else {
                    ((instantSpeed * 65L) + (smoothedSpeed * 35L)) / 100L
                }
                lastBytes = currentBytes
                lastTickMs = now

                persistDownloadingProgress(
                    downloadId = item.id,
                    downloadedBytes = currentBytes,
                    totalBytes = totalBytes,
                    speedBytesPerSec = smoothedSpeed,
                    chunksCount = chunks,
                    pauseFlag = pauseFlag,
                    cancelFlag = cancelFlag
                )
            }
        }

        try {
            val tasks = (0 until chunks).map { index ->
                val startByte = index * partSize
                val endByte = if (index == chunks - 1) totalBytes - 1 else (startByte + partSize - 1)
                val partFile = partFileFor(targetFile, index)
                async(Dispatchers.IO) {
                    downloadChunkToPartFile(
                        downloadId = item.id,
                        url = transferUrl,
                        partFile = partFile,
                        chunkIndex = index,
                        startByte = startByte,
                        endByte = endByte,
                        totalBytes = totalBytes,
                        userAgent = userAgent,
                        cookieHeader = cookieHeader,
                        pauseFlag = pauseFlag,
                        cancelFlag = cancelFlag,
                        totalDownloaded = totalDownloaded
                    )
                }
            }
            tasks.awaitAll()
        } finally {
            progressReporter.cancel()
            latestDownloaded.set(totalDownloaded.get())
        }

        if (pauseFlag.get() && !cancelFlag.get()) {
            withContext(NonCancellable) {
                persistPausedState(item.id, totalDownloaded.get(), totalBytes)
            }
            return@coroutineScope
        }
        if (cancelFlag.get()) return@coroutineScope

        // Verify all parts completed before merging
        val verifiedSum = (0 until chunks).sumOf { index ->
            val startByte = index * partSize
            val endByte = if (index == chunks - 1) totalBytes - 1 else (startByte + partSize - 1)
            val expectedLen = (endByte - startByte) + 1L
            val pf = partFileFor(targetFile, index)
            if (pf.exists()) pf.length().coerceAtMost(expectedLen) else 0L
        }
        if (verifiedSum < totalBytes) {
            if (pauseFlag.get() && !cancelFlag.get()) {
                withContext(NonCancellable) {
                    persistPausedState(item.id, verifiedSum, totalBytes)
                }
                return@coroutineScope
            }
            throw IOException("Incomplete chunk transfer ($verifiedSum / $totalBytes bytes)")
        }

        mergePartFilesToTarget(targetFile, chunks)

        val elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
        val transferredThisRun = (totalBytes - initialBytes).coerceAtLeast(1L)
        val avgSpeed = (transferredThisRun * 1000L) / elapsedMs

        mutexFor(item.id).withLock {
            if (pauseFlag.get() || cancelFlag.get()) return@withLock
            val latest = repository.getDownloadById(item.id) ?: item
            val completedItem = latest.copy(
                downloadedBytes = totalBytes,
                totalBytes = totalBytes,
                speedBytesPerSec = avgSpeed,
                chunksCount = chunks,
                status = STATUS_COMPLETED,
                completedAt = System.currentTimeMillis(),
                errorMessage = null
            )
            finalizeCompletedDownload(item.id, completedItem, targetFile)
        }
    }

    private suspend fun downloadChunkToPartFile(
        downloadId: Long,
        url: String,
        partFile: File,
        chunkIndex: Int,
        startByte: Long,
        endByte: Long,
        totalBytes: Long,
        userAgent: String,
        cookieHeader: String?,
        pauseFlag: AtomicBoolean,
        cancelFlag: AtomicBoolean,
        totalDownloaded: AtomicLong
    ) {
        val chunkLength = (endByte - startByte) + 1L
        var attempt = 0
        var lastException: IOException? = null

        while (attempt <= MAX_STREAM_RETRIES) {
            if (pauseFlag.get() || cancelFlag.get()) return

            val existingPartBytes = if (partFile.exists()) {
                val len = partFile.length()
                if (len > chunkLength) {
                    val excess = len - chunkLength
                    FileOutputStream(partFile, false).close()
                    totalDownloaded.addAndGet(-len)
                    0L
                } else {
                    len
                }
            } else {
                0L
            }

            var remaining = chunkLength - existingPartBytes
            if (remaining <= 0L) return

            val requestStart = startByte + existingPartBytes
            var activeConn: HttpURLConnection? = null

            try {
                val connected = openConnectionWithRedirects(
                    downloadId = downloadId,
                    initialUrl = url,
                    method = "GET",
                    userAgent = userAgent,
                    cookieHeader = cookieHeader,
                    rangeHeader = "bytes=$requestStart-$endByte",
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 25_000,
                    pauseFlag = pauseFlag,
                    cancelFlag = cancelFlag
                )
                val conn = connected.connection
                activeConn = conn

                if (pauseFlag.get() || cancelFlag.get()) return
                val responseCode = connected.responseCode

                if (responseCode == HttpURLConnection.HTTP_OK && chunkIndex > 0) {
                    throw RangeNotSupportedException("Server ignored Range header on chunk $chunkIndex")
                }
                if (responseCode == 416 && existingPartBytes >= chunkLength) {
                    return
                }
                if (responseCode != HttpURLConnection.HTTP_PARTIAL &&
                    !(requestStart == 0L && responseCode == HttpURLConnection.HTTP_OK)
                ) {
                    throw IOException("HTTP $responseCode while downloading chunk $chunkIndex")
                }

                BufferedInputStream(conn.inputStream, BUFFER_SIZE).use { input ->
                    FileOutputStream(partFile, existingPartBytes > 0L).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (remaining > 0L) {
                            if (pauseFlag.get() || cancelFlag.get()) {
                                output.flush()
                                return
                            }
                            val toRead = min(buffer.size.toLong(), remaining).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            remaining -= read
                            totalDownloaded.addAndGet(read.toLong())
                        }
                        output.flush()
                    }
                }

                if (remaining <= 0L || pauseFlag.get() || cancelFlag.get()) {
                    return
                }
                // Server closed stream before full chunk range was delivered; retry continuation
                throw IOException("Premature EOF on chunk $chunkIndex ($remaining bytes remaining)")
            } catch (rne: RangeNotSupportedException) {
                throw rne
            } catch (e: IOException) {
                if (pauseFlag.get() || cancelFlag.get()) {
                    return
                }
                lastException = e
                attempt++
                if (attempt > MAX_STREAM_RETRIES) {
                    throw e
                }
                val backoffMs = RETRY_BACKOFF_BASE_MS * attempt
                delay(backoffMs)
            } finally {
                if (activeConn != null) {
                    unregisterConnection(downloadId, activeConn)
                    runCatching { activeConn.disconnect() }
                }
            }
        }

        if (lastException != null) {
            throw lastException
        }
    }

    private suspend fun downloadSingleFastStream(
        item: DownloadItemEntity,
        transferUrl: String,
        targetFile: File,
        totalBytes: Long,
        supportsRanges: Boolean,
        userAgent: String,
        cookieHeader: String?,
        pauseFlag: AtomicBoolean,
        cancelFlag: AtomicBoolean,
        latestDownloaded: AtomicLong,
        latestTotal: AtomicLong
    ) {
        var canResumeRange = supportsRanges
        var allow416Reset = true
        var attempt = 0
        val startTimeMs = System.currentTimeMillis()
        val initialBytesOnDisk = if (targetFile.exists()) targetFile.length() else 0L

        while (attempt <= MAX_STREAM_RETRIES) {
            if (pauseFlag.get() || cancelFlag.get()) {
                if (pauseFlag.get() && !cancelFlag.get()) {
                    withContext(NonCancellable) {
                        persistPausedState(item.id, latestDownloaded.get(), latestTotal.get())
                    }
                }
                return
            }

            val existingBytes = if (canResumeRange && targetFile.exists() && targetFile.length() > 0L) {
                targetFile.length()
            } else {
                0L
            }

            val knownTotal = latestTotal.get().let { if (it > 0L) it else totalBytes }
            if (knownTotal > 0L && existingBytes >= knownTotal) {
                mutexFor(item.id).withLock {
                    if (cancelFlag.get()) return@withLock
                    val latest = repository.getDownloadById(item.id) ?: item
                    val completedItem = latest.copy(
                        downloadedBytes = knownTotal,
                        totalBytes = knownTotal,
                        speedBytesPerSec = 0L,
                        chunksCount = 1,
                        status = STATUS_COMPLETED,
                        completedAt = System.currentTimeMillis(),
                        errorMessage = null
                    )
                    repository.upsertDownload(completedItem)
                    notificationHelper.notifyDownloadState(completedItem, forceImmediate = true)
                }
                return
            }

            var activeConn: HttpURLConnection? = null
            try {
                val rangeHeader = if (existingBytes > 0L && canResumeRange) {
                    "bytes=$existingBytes-"
                } else {
                    null
                }
                val connected = openConnectionWithRedirects(
                    downloadId = item.id,
                    initialUrl = transferUrl,
                    method = "GET",
                    userAgent = userAgent,
                    cookieHeader = cookieHeader,
                    rangeHeader = rangeHeader,
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 25_000,
                    pauseFlag = pauseFlag,
                    cancelFlag = cancelFlag
                )
                val conn = connected.connection
                activeConn = conn

                if (pauseFlag.get() || cancelFlag.get()) {
                    if (pauseFlag.get() && !cancelFlag.get()) {
                        withContext(NonCancellable) {
                            persistPausedState(item.id, existingBytes, knownTotal)
                        }
                    }
                    return
                }

                val responseCode = connected.responseCode

                // Recover gracefully from HTTP 416 Range Not Satisfiable by restarting from byte 0 once
                if (responseCode == 416 && existingBytes > 0L && allow416Reset) {
                    allow416Reset = false
                    canResumeRange = false
                    unregisterConnection(item.id, conn)
                    runCatching { conn.disconnect() }
                    activeConn = null
                    runCatching { targetFile.delete() }
                    latestDownloaded.set(0L)
                    continue
                }

                if (responseCode !in 200..299) {
                    throw IOException("Server returned HTTP $responseCode")
                }

                val isResuming = responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
                if (isResuming || conn.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true) {
                    canResumeRange = true
                }
                val contentLenHeader = conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull()
                    ?: conn.contentLengthLong
                val effectiveTotal = when {
                    knownTotal > 0L -> knownTotal
                    isResuming && contentLenHeader > 0L -> existingBytes + contentLenHeader
                    contentLenHeader > 0L -> contentLenHeader
                    else -> -1L
                }
                latestTotal.set(effectiveTotal)

                var downloaded = if (isResuming) existingBytes else 0L
                latestDownloaded.set(downloaded)
                var lastReportedBytes = downloaded
                var lastReportTimeMs = System.currentTimeMillis()
                var smoothedSpeed = 0L

                BufferedInputStream(conn.inputStream, BUFFER_SIZE).use { input ->
                    FileOutputStream(targetFile, isResuming).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (pauseFlag.get() || cancelFlag.get()) {
                                output.flush()
                                latestDownloaded.set(downloaded)
                                if (pauseFlag.get() && !cancelFlag.get()) {
                                    withContext(NonCancellable) {
                                        persistPausedState(item.id, downloaded, effectiveTotal)
                                    }
                                }
                                return
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            latestDownloaded.set(downloaded)

                            val now = System.currentTimeMillis()
                            if (now - lastReportTimeMs >= 180L) {
                                val dt = (now - lastReportTimeMs).coerceAtLeast(1L)
                                val instantSpeed = ((downloaded - lastReportedBytes).coerceAtLeast(0L) * 1000L) / dt
                                smoothedSpeed = if (smoothedSpeed <= 0L) {
                                    instantSpeed
                                } else {
                                    ((instantSpeed * 65L) + (smoothedSpeed * 35L)) / 100L
                                }
                                lastReportedBytes = downloaded
                                lastReportTimeMs = now
                                persistDownloadingProgress(
                                    downloadId = item.id,
                                    downloadedBytes = downloaded,
                                    totalBytes = effectiveTotal,
                                    speedBytesPerSec = smoothedSpeed,
                                    chunksCount = 1,
                                    pauseFlag = pauseFlag,
                                    cancelFlag = cancelFlag
                                )
                            }
                        }
                        output.flush()
                    }
                }

                if (pauseFlag.get() && !cancelFlag.get()) {
                    withContext(NonCancellable) {
                        persistPausedState(item.id, downloaded, effectiveTotal)
                    }
                    return
                }
                if (cancelFlag.get()) return

                if (effectiveTotal > 0L && downloaded < effectiveTotal) {
                    throw IOException("Stream ended early ($downloaded / $effectiveTotal bytes)")
                }

                val finalTotal = if (effectiveTotal > 0L) effectiveTotal else downloaded
                val totalElapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(1L)
                val avgSpeed = ((downloaded - initialBytesOnDisk).coerceAtLeast(1L) * 1000L) / totalElapsedMs

                mutexFor(item.id).withLock {
                    if (pauseFlag.get() || cancelFlag.get()) return@withLock
                    val latest = repository.getDownloadById(item.id) ?: item
                    val completedItem = latest.copy(
                        downloadedBytes = downloaded,
                        totalBytes = finalTotal,
                        speedBytesPerSec = avgSpeed,
                        chunksCount = 1,
                        status = STATUS_COMPLETED,
                        completedAt = System.currentTimeMillis(),
                        errorMessage = null
                    )
                    finalizeCompletedDownload(item.id, completedItem, targetFile)
                }
                return
            } catch (e: IOException) {
                if (pauseFlag.get() && !cancelFlag.get()) {
                    withContext(NonCancellable) {
                        persistPausedState(item.id, latestDownloaded.get(), latestTotal.get())
                    }
                    return
                }
                if (cancelFlag.get()) return
                attempt++
                if (attempt > MAX_STREAM_RETRIES || (!canResumeRange && latestDownloaded.get() > 0L && attempt > 2)) {
                    throw e
                }
                delay(RETRY_BACKOFF_BASE_MS * attempt)
            } finally {
                if (activeConn != null) {
                    unregisterConnection(item.id, activeConn)
                    runCatching { activeConn.disconnect() }
                }
            }
        }
    }

    /**
     * Merges all `.part0`..`.part{chunks-1}` files into `targetFile` using zero-copy
     * NIO `FileChannel.transferTo` (with buffered fallback) for near-instantaneous completion.
     */
    private fun mergePartFilesToTarget(targetFile: File, chunks: Int) {
        val tempMerged = File("${targetFile.absolutePath}.merging")
        FileOutputStream(tempMerged, false).use { outStream ->
            val outChannel = outStream.channel
            var writePos = 0L
            for (index in 0 until chunks) {
                val partFile = partFileFor(targetFile, index)
                if (partFile.exists()) {
                    FileInputStream(partFile).use { inStream ->
                        val inChannel = inStream.channel
                        val size = inChannel.size()
                        var transferred = 0L
                        while (transferred < size) {
                            val step = inChannel.transferTo(
                                transferred,
                                size - transferred,
                                outChannel
                            )
                            if (step <= 0L) break
                            transferred += step
                        }
                        if (transferred < size) {
                            // Fallback for any remaining bytes if channel transfer stopped early
                            inChannel.position(transferred)
                            outChannel.position(writePos + transferred)
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = inStream.read(buffer)
                                if (read == -1) break
                                outStream.write(buffer, 0, read)
                                transferred += read
                            }
                        }
                        writePos += size
                        outChannel.position(writePos)
                    }
                }
            }
            outStream.flush()
        }
        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempMerged.renameTo(targetFile)) {
            tempMerged.copyTo(targetFile, overwrite = true)
            tempMerged.delete()
        }
        deletePartFilesOnly(targetFile)
    }

    private fun partFileFor(targetFile: File, index: Int): File {
        return File("${targetFile.absolutePath}.part$index")
    }

    private fun detectExistingPartChunkCount(targetFile: File, savedChunksCount: Int): Int {
        var maxPartIdx = -1
        for (i in 0 until MAX_SUPPORTED_CHUNKS) {
            val pf = partFileFor(targetFile, i)
            if (pf.exists() && pf.length() > 0L) {
                maxPartIdx = i
            }
        }
        if (maxPartIdx < 0) return 0
        return when {
            savedChunksCount > maxPartIdx && savedChunksCount in 2..MAX_SUPPORTED_CHUNKS -> savedChunksCount
            maxPartIdx >= 4 -> DEFAULT_TURBO_CHUNKS
            else -> MEDIUM_TURBO_CHUNKS
        }
    }

    private fun deletePartFilesOnly(targetFile: File) {
        for (i in 0 until MAX_SUPPORTED_CHUNKS) {
            runCatching { partFileFor(targetFile, i).delete() }
        }
        runCatching { File("${targetFile.absolutePath}.merging").delete() }
    }

    private fun deleteFileAndParts(targetFile: File) {
        runCatching { targetFile.delete() }
        deletePartFilesOnly(targetFile)
    }

    private fun computeCurrentDiskBytesForTarget(targetFile: File, maxChunks: Int = MAX_SUPPORTED_CHUNKS): Long {
        var partSum = 0L
        var foundParts = false
        val limit = max(maxChunks, MAX_SUPPORTED_CHUNKS)
        for (i in 0 until limit) {
            val part = partFileFor(targetFile, i)
            if (part.exists()) {
                foundParts = true
                partSum += part.length()
            }
        }
        if (foundParts) return partSum
        return if (targetFile.exists()) targetFile.length() else 0L
    }

    fun computeCurrentDiskBytes(item: DownloadItemEntity): Long {
        if (item.localFilePath.isBlank()) return 0L
        return computeCurrentDiskBytesForTarget(File(item.localFilePath), item.chunksCount)
    }

    private class RangeNotSupportedException(message: String) : IOException(message)

    private data class ConnectedHttpStream(
        val connection: HttpURLConnection,
        val finalUrl: String,
        val responseCode: Int
    )

    /**
     * Opens an [HttpURLConnection] and manually follows HTTP 301/302/303/307/308 redirects
     * (including cross-protocol `http` -> `https` and cross-domain CDN redirects) while
     * preserving `Range`, `User-Agent`, and `Cookie` headers on every hop.
     */
    private fun openConnectionWithRedirects(
        downloadId: Long,
        initialUrl: String,
        method: String,
        userAgent: String,
        cookieHeader: String?,
        rangeHeader: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        pauseFlag: AtomicBoolean,
        cancelFlag: AtomicBoolean
    ): ConnectedHttpStream {
        var currentUrl = initialUrl
        var redirects = 0
        var currentMethod = method

        while (redirects <= MAX_REDIRECTS) {
            if (pauseFlag.get() || cancelFlag.get()) {
                throw IOException("Download paused or canceled")
            }
            val urlObj = URL(currentUrl)
            val conn = (urlObj.openConnection() as HttpURLConnection).apply {
                requestMethod = currentMethod
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "keep-alive")
                if (!rangeHeader.isNullOrBlank()) {
                    setRequestProperty("Range", rangeHeader)
                }
                val hopCookie = runCatching {
                    CookieManager.getInstance().getCookie(currentUrl)
                }.getOrNull() ?: cookieHeader
                if (!hopCookie.isNullOrBlank()) {
                    setRequestProperty("Cookie", hopCookie)
                }
            }
            registerConnection(downloadId, conn)
            try {
                conn.connect()
                val code = conn.responseCode
                if (code in REDIRECT_CODES) {
                    val location = conn.getHeaderField("Location")
                    unregisterConnection(downloadId, conn)
                    runCatching { conn.disconnect() }
                    if (location.isNullOrBlank()) {
                        throw IOException("Redirect (HTTP $code) missing Location header")
                    }
                    currentUrl = URL(urlObj, location.trim()).toString()
                    if (code == HttpURLConnection.HTTP_SEE_OTHER && currentMethod != "HEAD") {
                        currentMethod = "GET"
                    }
                    redirects++
                    continue
                }
                return ConnectedHttpStream(
                    connection = conn,
                    finalUrl = conn.url?.toString() ?: currentUrl,
                    responseCode = code
                )
            } catch (e: Exception) {
                unregisterConnection(downloadId, conn)
                runCatching { conn.disconnect() }
                throw e
            }
        }
        throw IOException("Too many redirects (> $MAX_REDIRECTS)")
    }

    private data class ProbeResult(
        val finalUrl: String,
        val contentLength: Long,
        val supportsRanges: Boolean,
        val mimeType: String,
        val suggestedName: String
    )

    private fun probeRemoteResource(
        downloadId: Long,
        url: String,
        userAgent: String,
        cookieHeader: String?,
        pauseFlag: AtomicBoolean,
        cancelFlag: AtomicBoolean
    ): ProbeResult {
        return runCatching {
            if (pauseFlag.get() || cancelFlag.get()) {
                return ProbeResult(url, -1L, false, "", "")
            }
            val connected = openConnectionWithRedirects(
                downloadId = downloadId,
                initialUrl = url,
                method = "GET",
                userAgent = userAgent,
                cookieHeader = cookieHeader,
                rangeHeader = "bytes=0-0",
                connectTimeoutMs = 12_000,
                readTimeoutMs = 12_000,
                pauseFlag = pauseFlag,
                cancelFlag = cancelFlag
            )
            val conn = connected.connection
            try {
                val code = connected.responseCode
                val finalUrl = connected.finalUrl
                val contentRange = conn.getHeaderField("Content-Range").orEmpty()
                val acceptRanges = conn.getHeaderField("Accept-Ranges").orEmpty()
                val supportsRanges = (code == HttpURLConnection.HTTP_PARTIAL &&
                    contentRange.startsWith("bytes", ignoreCase = true)) ||
                    acceptRanges.contains("bytes", ignoreCase = true)

                val totalFromRange = contentRange.substringAfter('/', "")
                    .trim()
                    .takeIf { it.isNotEmpty() && it != "*" }
                    ?.toLongOrNull()
                val headerContentLen = conn.getHeaderField("Content-Length")
                    ?.trim()
                    ?.toLongOrNull()
                    ?: conn.contentLengthLong

                val contentLength = when {
                    totalFromRange != null && totalFromRange > 0L -> totalFromRange
                    code == HttpURLConnection.HTTP_OK && headerContentLen > 0L -> headerContentLen
                    headerContentLen > 1L -> headerContentLen
                    else -> -1L
                }
                val mimeType = conn.contentType?.substringBefore(';')?.trim().orEmpty()
                val disposition = conn.getHeaderField("Content-Disposition")
                val suggestedName = if (!disposition.isNullOrBlank()) {
                    URLUtil.guessFileName(finalUrl, disposition, mimeType)
                } else {
                    ""
                }
                ProbeResult(
                    finalUrl = finalUrl,
                    contentLength = contentLength,
                    supportsRanges = supportsRanges,
                    mimeType = mimeType,
                    suggestedName = suggestedName
                )
            } finally {
                unregisterConnection(downloadId, conn)
                runCatching { conn.disconnect() }
            }
        }.getOrElse {
            ProbeResult(
                finalUrl = url,
                contentLength = -1L,
                supportsRanges = false,
                mimeType = "",
                suggestedName = ""
            )
        }
    }

    companion object {
        private const val BUFFER_SIZE = 128 * 1024 // 128 KB high-throughput NIO buffer
        const val DEFAULT_TURBO_CHUNKS = 8
        const val MEDIUM_TURBO_CHUNKS = 4
        private const val MAX_SUPPORTED_CHUNKS = 16
        private const val MIN_PARALLEL_BYTES = 64 * 1024L // 64 KB
        private const val HIGH_TURBO_THRESHOLD_BYTES = 512 * 1024L // 512 KB -> 8× Turbo
        private const val MAX_STREAM_RETRIES = 5
        private const val RETRY_BACKOFF_BASE_MS = 400L
        private const val MAX_REDIRECTS = 10
        private val REDIRECT_CODES = intArrayOf(301, 302, 303, 307, 308)

        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELED = "CANCELED"

        @Volatile
        private var INSTANCE: FastDownloadEngine? = null

        fun getInstance(context: Context, repository: BrowserRepository): FastDownloadEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FastDownloadEngine(context.applicationContext, repository).also {
                    INSTANCE = it
                }
            }
        }

        fun sanitizeFileName(raw: String): String {
            val cleaned = raw.trim()
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(120)
            return cleaned.ifBlank { "download_${System.currentTimeMillis()}.bin" }
        }

        fun allocateUniqueFile(directory: File, desiredName: String): File {
            var candidate = File(directory, desiredName)
            if (!candidate.exists() && !File("${candidate.absolutePath}.part0").exists()) {
                return candidate
            }
            val baseName = desiredName.substringBeforeLast('.', desiredName)
            val ext = desiredName.substringAfterLast('.', "")
            var counter = 1
            while (candidate.exists() || File("${candidate.absolutePath}.part0").exists()) {
                val numbered = if (ext.isNotEmpty() && baseName != desiredName) {
                    "$baseName ($counter).$ext"
                } else {
                    "$desiredName ($counter)"
                }
                candidate = File(directory, numbered)
                counter++
            }
            return candidate
        }

        fun resolveMimeType(url: String, providedMime: String?): String {
            if (!providedMime.isNullOrBlank() && providedMime != "application/octet-stream") {
                return providedMime.substringBefore(';').trim()
            }
            val cleanUrl = url.substringBefore('?').substringBefore('#')
            val ext = MimeTypeMap.getFileExtensionFromUrl(cleanUrl)?.lowercase(Locale.US).orEmpty()
            if (ext.isNotEmpty()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
            }
            return providedMime?.ifBlank { "application/octet-stream" } ?: "application/octet-stream"
        }

        fun inferCategory(fileName: String, mimeType: String): String {
            val lowerName = fileName.lowercase(Locale.US)
            val lowerMime = mimeType.lowercase(Locale.US)
            return when {
                lowerMime.startsWith("image/") ||
                    lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                    lowerName.endsWith(".png") || lowerName.endsWith(".webp") ||
                    lowerName.endsWith(".gif") || lowerName.endsWith(".svg") -> "Image"
                lowerMime.startsWith("video/") ||
                    lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") ||
                    lowerName.endsWith(".webm") || lowerName.endsWith(".mov") -> "Video"
                lowerMime.startsWith("audio/") ||
                    lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") ||
                    lowerName.endsWith(".flac") || lowerName.endsWith(".m4a") ||
                    lowerName.endsWith(".ogg") -> "Audio"
                lowerMime.contains("pdf") || lowerMime.contains("word") ||
                    lowerMime.contains("presentation") || lowerMime.contains("spreadsheet") ||
                    lowerMime.startsWith("text/") ||
                    lowerName.endsWith(".pdf") || lowerName.endsWith(".doc") ||
                    lowerName.endsWith(".docx") || lowerName.endsWith(".txt") ||
                    lowerName.endsWith(".md") || lowerName.endsWith(".html") ||
                    lowerName.endsWith(".csv") || lowerName.endsWith(".json") -> "Document"
                lowerMime.contains("zip") || lowerMime.contains("tar") ||
                    lowerMime.contains("Compressed") || lowerMime.contains("archive") ||
                    lowerName.endsWith(".zip") || lowerName.endsWith(".rar") ||
                    lowerName.endsWith(".7z") || lowerName.endsWith(".tar") ||
                    lowerName.endsWith(".gz") || lowerName.endsWith(".apk") -> "Archive"
                else -> "Other"
            }
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val kb = 1024.0
            val mb = kb * 1024.0
            val gb = mb * 1024.0
            return when {
                bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
                bytes >= mb -> String.format(Locale.US, "%.2f MB", bytes / mb)
                bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
                else -> "$bytes B"
            }
        }

        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0L) return "0 KB/s"
            return "${formatBytes(bytesPerSec)}/s"
        }
    }
}

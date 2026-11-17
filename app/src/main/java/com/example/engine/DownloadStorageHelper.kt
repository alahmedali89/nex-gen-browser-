package com.example.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.example.data.preferences.BrowserPreferences
import com.example.data.preferences.DownloadLocationMode
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Resolves Play-compliant scoped download directories, Storage Access Framework (SAF) folder
 * exports, display summaries, and FileProvider-backed open intents without broad storage permissions.
 */
object DownloadStorageHelper {

    private val BLOCKED_AUTO_OPEN_EXTENSIONS = setOf(
        "apk", "dex", "jar", "so", "exe", "bat", "cmd", "sh", "bin", "msi"
    )

    fun resolveDirectory(context: Context, mode: DownloadLocationMode): File {
        val appContext = context.applicationContext
        val dir = when (mode) {
            DownloadLocationMode.APP_INTERNAL -> File(appContext.filesDir, "downloads")
            DownloadLocationMode.APP_EXTERNAL,
            DownloadLocationMode.CUSTOM_SAF -> {
                appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(appContext.filesDir, "downloads")
            }
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun extractFolderLabelFromUri(uriString: String): String {
        if (uriString.isBlank()) return ""
        return runCatching {
            val uri = Uri.parse(uriString)
            val segment = uri.lastPathSegment.orEmpty()
            val cleaned = segment.substringAfter(':', segment).trim('/')
            cleaned.ifBlank { uri.host.orEmpty() }
        }.getOrDefault("")
    }

    fun formatLocationSummary(
        context: Context,
        preferences: BrowserPreferences
    ): Pair<String, String> {
        return formatLocationSummary(
            context = context,
            mode = preferences.downloadLocationMode,
            customTreeUri = preferences.customDownloadTreeUri,
            customLabel = preferences.customDownloadFolderLabel
        )
    }

    fun formatLocationSummary(
        context: Context,
        mode: DownloadLocationMode,
        customTreeUri: String,
        customLabel: String
    ): Pair<String, String> {
        return when (mode) {
            DownloadLocationMode.APP_EXTERNAL -> {
                val path = resolveDirectory(context, DownloadLocationMode.APP_EXTERNAL).absolutePath
                "Downloads" to path
            }
            DownloadLocationMode.APP_INTERNAL -> {
                val path = resolveDirectory(context, DownloadLocationMode.APP_INTERNAL).absolutePath
                "Private storage" to path
            }
            DownloadLocationMode.CUSTOM_SAF -> {
                val label = customLabel.ifBlank { extractFolderLabelFromUri(customTreeUri) }
                    .ifBlank { "Selected folder" }
                val pathOrUri = customTreeUri.ifBlank {
                    resolveDirectory(context, DownloadLocationMode.APP_EXTERNAL).absolutePath
                }
                label to pathOrUri
            }
        }
    }

    fun copyFileToSafTree(
        context: Context,
        sourceFile: File,
        treeUriString: String,
        mimeType: String,
        fileName: String
    ): Uri? {
        if (!sourceFile.exists() || treeUriString.isBlank()) return null
        return runCatching {
            val resolver = context.applicationContext.contentResolver
            val treeUri = Uri.parse(treeUriString)
            val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            val safeMime = mimeType.ifBlank { "application/octet-stream" }
            val destUri = DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                safeMime,
                fileName
            ) ?: return null

            resolver.openOutputStream(destUri)?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
                output.flush()
            } ?: return null

            destUri
        }.getOrNull()
    }

    fun isSafeToAutoOpen(fileName: String, mimeType: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext in BLOCKED_AUTO_OPEN_EXTENSIONS) {
            return false
        }
        val lowerMime = mimeType.lowercase(Locale.US)
        if (lowerMime == "application/vnd.android.package-archive") {
            return false
        }
        return true
    }

    fun buildOpenFileIntent(
        context: Context,
        localFilePath: String,
        fileName: String,
        mimeType: String
    ): Intent? {
        if (localFilePath.isBlank() || !isSafeToAutoOpen(fileName, mimeType)) {
            return null
        }
        val appContext = context.applicationContext
        val uri: Uri = if (localFilePath.startsWith("content://", ignoreCase = true)) {
            Uri.parse(localFilePath)
        } else {
            val file = File(localFilePath)
            if (!file.exists()) return null
            runCatching {
                FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    file
                )
            }.getOrNull() ?: return null
        }

        val resolvedMime = FastDownloadEngine.resolveMimeType(fileName, mimeType)
            .ifBlank { "*/*" }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (viewIntent.resolveActivity(appContext.packageManager) != null) {
            viewIntent
        } else {
            null
        }
    }
}

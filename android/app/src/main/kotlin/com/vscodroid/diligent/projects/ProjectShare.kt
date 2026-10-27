package com.vscodroid.diligent.projects

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * The Android half of sharing a project as a zip ([ProjectZip] is the
 * other): the zip goes through the share sheet by Diligent's FileProvider,
 * and a zip comes in from the system document picker.
 *
 * Both work in `cacheDir`, emptied before each use, so at most one shared
 * zip and one incoming copy are ever kept; Android may clear them too.
 */
object ProjectShare {
    /** Diligent's FileProvider (AndroidManifest.xml, `res/xml/diligent_file_paths.xml`). */
    private const val AUTHORITY_SUFFIX = ".diligent.files"

    /** Under `cacheDir`, as `diligent_file_paths.xml` names it. */
    private const val SHARE_DIR = "diligent-share"
    private const val IMPORT_DIR = "diligent-import"

    const val ZIP_TYPE = "application/zip"

    /** What the picker offers: zips, under the names file managers give them. */
    val PICK_TYPES = arrayOf(ZIP_TYPE, "application/x-zip-compressed", "application/x-zip", "application/octet-stream")

    /**
     * Writes [folder] as a zip into the cache and answers it, or null when
     * the folder is not one (a workspace file) or the disk said no. Off the
     * main thread.
     */
    fun exportZip(context: Context, folder: File): File? {
        if (!folder.isDirectory) return null
        val dir = File(context.cacheDir, SHARE_DIR)
        dir.deleteRecursively()
        if (!dir.mkdirs()) return null
        val zip = File(dir, "${folder.name.ifEmpty { ProjectNames.FALLBACK_NAME }}.zip")
        return try {
            zip.outputStream().use { ProjectZip.export(folder, it) }
            zip
        } catch (_: IOException) {
            zip.delete()
            null
        }
    }

    /** Opens the share sheet for [zip]. Answers false when nothing can take it. */
    fun share(context: Context, zip: File, chooserTitle: String): Boolean {
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = ZIP_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(zip.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Copies the zip at [uri] into the cache and unpacks it into
     * [projectsDir] ([ProjectZip.import]). Off the main thread.
     */
    fun importZip(context: Context, uri: Uri, projectsDir: File): ZipImport {
        val dir = File(context.cacheDir, IMPORT_DIR)
        dir.deleteRecursively()
        if (!dir.mkdirs()) return ZipImport.Failed
        val copy = File(dir, "incoming.zip")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return ZipImport.Failed
            input.use { source -> copy.outputStream().use { source.copyTo(it) } }
            if (!projectsDir.isDirectory && !projectsDir.mkdirs()) return ZipImport.Failed
            ProjectZip.import(copy, projectsDir, displayName(context, uri)?.let(::nameOf) ?: ProjectNames.FALLBACK_NAME)
        } catch (_: IOException) {
            ZipImport.Failed
        } catch (_: SecurityException) {
            ZipImport.Failed
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A project name from a zip's file name: without `.zip`, and nothing when that leaves nothing. */
    fun nameOf(displayName: String): String? = displayName.trim().removeSuffix(".zip").removeSuffix(".ZIP").trim().ifEmpty { null }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

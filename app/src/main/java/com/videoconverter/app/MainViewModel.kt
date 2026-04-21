package com.videoconverter.app

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel that manages selected video files, conversion state, and progress.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VideoConverter"
    }

    // ── Supported formats ──────────────────────────────────────────────────
    val supportedFormats = listOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "mpeg")

    // ── Selected files ─────────────────────────────────────────────────────
    private val _videoFiles = MutableLiveData<List<VideoFile>>(emptyList())
    val videoFiles: LiveData<List<VideoFile>> = _videoFiles

    // ── Conversion progress (0..100 per file, total across all files) ─────
    private val _progress = MutableLiveData(0)
    val progress: LiveData<Int> = _progress

    private val _currentFileIndex = MutableLiveData(0)
    val currentFileIndex: LiveData<Int> = _currentFileIndex

    // ── Status message shown in the UI ────────────────────────────────────
    private val _statusMessage = MutableLiveData("Select video files to convert")
    val statusMessage: LiveData<String> = _statusMessage

    // ── Is a conversion running? ──────────────────────────────────────────
    private val _isConverting = MutableLiveData(false)
    val isConverting: LiveData<Boolean> = _isConverting

    // ── Conversion finished – triggers delete prompt ──────────────────────
    private val _conversionFinished = MutableLiveData(false)
    val conversionFinished: LiveData<Boolean> = _conversionFinished

    // ── Paths of successfully converted output files ──────────────────────
    private val _convertedOutputPaths = mutableListOf<String>()

    // ── Paths of original input files (for deletion) ──────────────────────
    private val _originalInputPaths = mutableListOf<String>()

    // ── Last conversion errors ────────────────────────────────────────────
    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    // ─── MIME type mapping ────────────────────────────────────────────────
    private fun mimeTypeForFormat(format: String): String = when (format) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "mpeg" -> "video/mpeg"
        else -> "video/*"
    }

    // ─── Add files from URIs returned by the file picker ──────────────────
    fun addFiles(uris: List<Uri>) {
        val resolver: ContentResolver = getApplication<Application>().contentResolver
        val current = _videoFiles.value.orEmpty().toMutableList()

        for (uri in uris) {
            // Skip duplicates
            if (current.any { it.uri == uri }) continue

            var displayName = "unknown"
            var size = 0L
            var lastModified = 0L
            var absolutePath: String? = null

            // Try to resolve display name and size via ContentResolver
            val cursor: Cursor? = resolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = it.getString(nameIdx) ?: "unknown"
                    if (sizeIdx >= 0) size = it.getLong(sizeIdx)

                    // Try to get DATA column (absolute path) – works on many devices
                    val dataIdx = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataIdx >= 0) {
                        absolutePath = it.getString(dataIdx)
                    }

                    val modIdx = it.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    if (modIdx >= 0) {
                        lastModified = it.getLong(modIdx) * 1000 // seconds → ms
                    }
                }
            }

            // If absolute path still null, try to get it from the URI path
            if (absolutePath == null) {
                absolutePath = resolvePathFromUri(uri)
            }

            Log.d(TAG, "Added file: name=$displayName, path=$absolutePath, size=$size, uri=$uri")

            current.add(
                VideoFile(
                    uri = uri,
                    displayName = displayName,
                    absolutePath = absolutePath,
                    sizeBytes = size,
                    lastModified = lastModified
                )
            )
        }

        _videoFiles.value = current
        _statusMessage.value = "${current.size} file(s) selected"
    }

    fun removeFile(file: VideoFile) {
        val current = _videoFiles.value.orEmpty().toMutableList()
        current.remove(file)
        _videoFiles.value = current
        _statusMessage.value = if (current.isEmpty()) "Select video files to convert"
        else "${current.size} file(s) selected"
    }

    fun clearFiles() {
        _videoFiles.value = emptyList()
        _statusMessage.value = "Select video files to convert"
    }

    // ─── Start conversion ────────────────────────────────────────────────
    fun startConversion(outputFormat: String) {
        val files = _videoFiles.value.orEmpty()
        if (files.isEmpty()) {
            _statusMessage.value = "No files selected"
            return
        }

        _isConverting.value = true
        _conversionFinished.value = false
        _progress.value = 0
        _currentFileIndex.value = 0
        _convertedOutputPaths.clear()
        _originalInputPaths.clear()
        _errorMessage.value = null

        viewModelScope.launch(Dispatchers.IO) {
            val errors = mutableListOf<String>()

            try {
                for ((index, videoFile) in files.withIndex()) {
                    withContext(Dispatchers.Main) {
                        _currentFileIndex.value = index + 1
                        _statusMessage.value = "Converting ${index + 1} / ${files.size}: ${videoFile.displayName}"
                    }

                    Log.d(TAG, "Starting conversion ${index + 1}/${files.size}: " +
                            "${videoFile.displayName}, absolutePath=${videoFile.absolutePath}")

                    val result = convertFile(videoFile, outputFormat)
                    if (result != null) errors.add(result)

                    withContext(Dispatchers.Main) {
                        _progress.value = ((index + 1) * 100) / files.size
                    }
                }
            } catch (e: Throwable) {
                // Catch everything including java.lang.Error from native library failures
                Log.e(TAG, "Conversion failed with exception", e)
                errors.add("FFmpeg engine error: ${e.message ?: e.javaClass.simpleName}")
            }

            withContext(Dispatchers.Main) {
                _isConverting.value = false
                if (errors.isEmpty()) {
                    _statusMessage.value = "Conversion complete! ${files.size} file(s) converted."
                    _conversionFinished.value = true
                } else {
                    _statusMessage.value = "Completed with ${errors.size} error(s)."
                    _errorMessage.value = errors.joinToString("\n")
                    _conversionFinished.value = true
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Unified conversion:
    //    1. Copy source URI → app cache (guarantees readable file path)
    //    2. FFmpeg convert in cache
    //    3. Copy result to final destination (next to original, or Movies/VideoConverter)
    //    4. MediaStore scan so the file is visible immediately
    // ═══════════════════════════════════════════════════════════════════════
    private fun convertFile(videoFile: VideoFile, outputFormat: String): String? {
        val context = getApplication<Application>()
        val resolver = context.contentResolver
        val cacheDir = context.cacheDir

        val baseName = videoFile.displayName.substringBeforeLast(".")
        val outputFileName = "$baseName.$outputFormat"

        // If input and output names are the same extension, skip
        if (videoFile.displayName.equals(outputFileName, ignoreCase = true)) {
            return "Input and output are the same format: ${videoFile.displayName}"
        }

        // ── Step 1: Copy source to cache ──────────────────────────────────
        val tempInput = File(cacheDir, "in_${System.currentTimeMillis()}_${videoFile.displayName}")
        try {
            resolver.openInputStream(videoFile.uri)?.use { inputStream ->
                tempInput.outputStream().use { out -> inputStream.copyTo(out) }
            } ?: return "Cannot read file: ${videoFile.displayName}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy input to cache", e)
            return "Cannot read file: ${videoFile.displayName} (${e.message})"
        }

        Log.d(TAG, "Copied input to cache: ${tempInput.absolutePath} (${tempInput.length()} bytes)")

        // ── Step 2: FFmpeg conversion in cache ────────────────────────────
        val tempOutput = File(cacheDir, "out_${System.currentTimeMillis()}_$outputFileName")

        val cmd = "-y -i \"${tempInput.absolutePath}\" -c copy \"${tempOutput.absolutePath}\""
        Log.d(TAG, "FFmpeg command: $cmd")

        val ffmpegResult = try {
            val session = FFmpegKit.execute(cmd)
            val rc = session.returnCode
            Log.d(TAG, "FFmpeg return code: $rc")
            if (ReturnCode.isSuccess(rc)) {
                null // success
            } else {
                val output = session.output?.take(300) ?: "unknown error"
                Log.e(TAG, "FFmpeg failed: $output")
                "FFmpeg error: $output"
            }
        } catch (e: Throwable) {
            Log.e(TAG, "FFmpeg execution threw", e)
            "FFmpeg engine error: ${e.message ?: e.javaClass.simpleName}"
        }

        // Clean up input temp
        tempInput.delete()

        if (ffmpegResult != null) {
            tempOutput.delete()
            return "Failed to convert ${videoFile.displayName}: $ffmpegResult"
        }

        if (!tempOutput.exists() || tempOutput.length() == 0L) {
            Log.e(TAG, "FFmpeg succeeded but output file is missing or empty")
            tempOutput.delete()
            return "Conversion produced no output for ${videoFile.displayName}"
        }

        Log.d(TAG, "FFmpeg output: ${tempOutput.absolutePath} (${tempOutput.length()} bytes)")

        // ── Step 3: Move result to final destination ──────────────────────
        val finalPath = moveToFinalDestination(
            tempOutput, videoFile, outputFileName, outputFormat
        )

        // Clean up temp output (moveToFinalDestination copies it elsewhere)
        if (finalPath != null && finalPath != tempOutput.absolutePath) {
            tempOutput.delete()
        }

        if (finalPath == null) {
            tempOutput.delete()
            return "Could not save output file for ${videoFile.displayName}"
        }

        // ── Step 4: Restore timestamp ─────────────────────────────────────
        val finalFile = File(finalPath)
        if (videoFile.lastModified > 0 && finalFile.exists()) {
            finalFile.setLastModified(videoFile.lastModified)
        }

        // ── Step 5: Trigger MediaStore scan ───────────────────────────────
        scanFile(finalPath, mimeTypeForFormat(outputFormat))

        _convertedOutputPaths.add(finalPath)
        if (videoFile.absolutePath != null) {
            _originalInputPaths.add(videoFile.absolutePath!!)
        }

        Log.i(TAG, "Conversion complete: ${videoFile.displayName} → $finalPath")
        return null // success
    }

    // ─── Move converted file to the right place ──────────────────────────
    private fun moveToFinalDestination(
        tempOutput: File,
        videoFile: VideoFile,
        outputFileName: String,
        outputFormat: String
    ): String? {
        val context = getApplication<Application>()

        // Strategy 1: Write next to original via direct file path
        val originalPath = videoFile.absolutePath
        if (originalPath != null) {
            val originalFile = File(originalPath)
            val parentDir = originalFile.parentFile
            if (parentDir != null && parentDir.exists() && parentDir.canWrite()) {
                val target = File(parentDir, outputFileName)
                try {
                    if (target.exists()) target.delete()
                    tempOutput.copyTo(target, overwrite = true)
                    if (target.exists() && target.length() > 0) {
                        Log.d(TAG, "Saved next to original: ${target.absolutePath}")
                        return target.absolutePath
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot write next to original: ${e.message}")
                }
            } else {
                Log.w(TAG, "Parent dir not writable: parentDir=$parentDir, " +
                        "exists=${parentDir?.exists()}, canWrite=${parentDir?.canWrite()}")
            }
        }

        // Strategy 2: Use MediaStore to insert into Movies/VideoConverter (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, outputFileName)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeTypeForFormat(outputFormat))
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/VideoConverter"
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { outStream ->
                        tempOutput.inputStream().use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    // Resolve the actual file path for display
                    val savedPath = resolveMediaStoreFilePath(uri)
                        ?: "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)}/VideoConverter/$outputFileName"
                    Log.d(TAG, "Saved via MediaStore: $savedPath (uri=$uri)")
                    return savedPath
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore insert failed: ${e.message}")
            }
        }

        // Strategy 3: Fall back to the Downloads directory
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val target = File(downloadsDir, outputFileName)
            tempOutput.copyTo(target, overwrite = true)
            if (target.exists() && target.length() > 0) {
                Log.d(TAG, "Saved to Downloads: ${target.absolutePath}")
                return target.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "Downloads fallback failed: ${e.message}")
        }

        Log.w(TAG, "All output strategies failed for $outputFileName")
        return null
    }

    // ─── Resolve file path from MediaStore URI ───────────────────────────
    private fun resolveMediaStoreFilePath(uri: Uri): String? {
        val context = getApplication<Application>()
        try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        } catch (_: Exception) { }
        return null
    }

    // ─── Trigger MediaStore scan so files appear immediately ─────────────
    private fun scanFile(path: String, mimeType: String) {
        val context = getApplication<Application>()
        MediaScannerConnection.scanFile(
            context, arrayOf(path), arrayOf(mimeType)
        ) { scannedPath, uri ->
            Log.d(TAG, "MediaScanner completed: path=$scannedPath, uri=$uri")
        }
    }

    // ─── Delete original input files ─────────────────────────────────────
    fun deleteOriginalFiles(): Int {
        var deleted = 0
        for (path in _originalInputPaths) {
            val file = File(path)
            if (file.exists() && file.delete()) {
                deleted++
                scanFile(path, "video/*")
            }
        }

        // Also try deleting via ContentResolver for files added through URIs
        val resolver = getApplication<Application>().contentResolver
        val files = _videoFiles.value.orEmpty()
        for (videoFile in files) {
            if (videoFile.absolutePath != null && _originalInputPaths.contains(videoFile.absolutePath)) {
                continue // already handled above
            }
            try {
                val rows = resolver.delete(videoFile.uri, null, null)
                if (rows > 0) deleted++
            } catch (_: Exception) {
                // Some URIs may not support deletion
            }
        }

        _originalInputPaths.clear()
        return deleted
    }

    fun resetAfterConversion() {
        _conversionFinished.value = false
        _videoFiles.value = emptyList()
        _progress.value = 0
        _currentFileIndex.value = 0
        _statusMessage.value = "Select video files to convert"
    }

    // ─── Helpers ─────────────────────────────────────────────────────────
    private fun resolvePathFromUri(uri: Uri): String? {
        // Handle file:// scheme
        if (uri.scheme == "file") return uri.path

        // Handle content:// with document path for external storage
        val docPath = uri.path ?: return null
        if (docPath.contains("/document/")) {
            val raw = Uri.decode(docPath.substringAfter("/document/"))
            if (raw.startsWith("primary:")) {
                val relative = raw.removePrefix("primary:")
                val base = Environment.getExternalStorageDirectory().absolutePath
                return "$base/$relative"
            }
        }
        return null
    }
}

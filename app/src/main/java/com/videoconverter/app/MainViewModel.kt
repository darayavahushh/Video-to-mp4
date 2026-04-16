package com.videoconverter.app

import android.app.Application
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel that manages selected video files, conversion state, and progress.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

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

            for ((index, videoFile) in files.withIndex()) {
                withContext(Dispatchers.Main) {
                    _currentFileIndex.value = index + 1
                    _statusMessage.value = "Converting ${index + 1} / ${files.size}: ${videoFile.displayName}"
                }

                val inputPath = videoFile.absolutePath
                if (inputPath == null) {
                    // Fall back: copy the URI content to a temp file, convert, then copy back
                    val result = convertViaUri(videoFile, outputFormat)
                    if (result != null) errors.add(result)
                } else {
                    val result = convertDirectPath(inputPath, outputFormat, videoFile.lastModified)
                    if (result != null) errors.add(result)
                    else _originalInputPaths.add(inputPath)
                }

                withContext(Dispatchers.Main) {
                    _progress.value = ((index + 1) * 100) / files.size
                }
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

    // ─── Convert using a direct file path ────────────────────────────────
    private fun convertDirectPath(
        inputPath: String,
        outputFormat: String,
        originalTimestamp: Long
    ): String? {
        val inputFile = File(inputPath)
        if (!inputFile.exists()) return "File not found: $inputPath"

        val baseName = inputFile.nameWithoutExtension
        val parentDir = inputFile.parentFile ?: return "Cannot resolve parent directory"
        val outputFile = File(parentDir, "$baseName.$outputFormat")

        // If input and output are the same, skip
        if (inputFile.absolutePath == outputFile.absolutePath) {
            return "Input and output are the same file: ${inputFile.name}"
        }

        // Use a temp output to avoid overwriting issues
        val tempOutput = File(parentDir, "${baseName}_converting_tmp.$outputFormat")

        // Build FFmpeg command
        val cmd = "-y -i \"${inputFile.absolutePath}\" -c copy \"${tempOutput.absolutePath}\""

        val session = FFmpegKit.execute(cmd)

        return if (ReturnCode.isSuccess(session.returnCode)) {
            // If a file with the target name already exists, delete it
            if (outputFile.exists()) outputFile.delete()
            tempOutput.renameTo(outputFile)

            // Restore original timestamp
            if (originalTimestamp > 0) {
                outputFile.setLastModified(originalTimestamp)
            } else if (inputFile.lastModified() > 0) {
                outputFile.setLastModified(inputFile.lastModified())
            }

            _convertedOutputPaths.add(outputFile.absolutePath)
            null // success
        } else {
            tempOutput.delete()
            "Failed to convert ${inputFile.name}: ${session.output?.take(200) ?: "unknown error"}"
        }
    }

    // ─── Convert via content URI (copy to cache, convert, copy back) ─────
    private fun convertViaUri(videoFile: VideoFile, outputFormat: String): String? {
        val context = getApplication<Application>()
        val resolver = context.contentResolver

        try {
            // Copy input to cache
            val cacheDir = context.cacheDir
            val tempInput = File(cacheDir, "input_${System.currentTimeMillis()}_${videoFile.displayName}")
            resolver.openInputStream(videoFile.uri)?.use { input ->
                tempInput.outputStream().use { output -> input.copyTo(output) }
            } ?: return "Cannot read file: ${videoFile.displayName}"

            val baseName = videoFile.displayName.substringBeforeLast(".")
            val tempOutput = File(cacheDir, "${baseName}.$outputFormat")

            val cmd = "-y -i \"${tempInput.absolutePath}\" -c copy \"${tempOutput.absolutePath}\""
            val session = FFmpegKit.execute(cmd)

            return if (ReturnCode.isSuccess(session.returnCode)) {
                // Restore timestamp
                if (videoFile.lastModified > 0) {
                    tempOutput.setLastModified(videoFile.lastModified)
                }

                // Try to write back next to original – put in Downloads as fallback
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val finalOutput = File(downloadsDir, "${baseName}.$outputFormat")
                tempOutput.copyTo(finalOutput, overwrite = true)
                if (videoFile.lastModified > 0) {
                    finalOutput.setLastModified(videoFile.lastModified)
                }
                _convertedOutputPaths.add(finalOutput.absolutePath)

                // Cleanup temp files
                tempInput.delete()
                tempOutput.delete()

                null // success
            } else {
                tempInput.delete()
                tempOutput.delete()
                "Failed to convert ${videoFile.displayName}: ${session.output?.take(200) ?: "unknown error"}"
            }
        } catch (e: Exception) {
            return "Error converting ${videoFile.displayName}: ${e.message}"
        }
    }

    // ─── Delete original input files ─────────────────────────────────────
    fun deleteOriginalFiles(): Int {
        var deleted = 0
        for (path in _originalInputPaths) {
            val file = File(path)
            if (file.exists() && file.delete()) deleted++
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

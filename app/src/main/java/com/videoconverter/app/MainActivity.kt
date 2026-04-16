package com.videoconverter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.videoconverter.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: VideoFileAdapter

    // ─── File picker launcher ────────────────────────────────────────────
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            // Take persistable permission so we can access the files later
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Some providers don't support persistable permissions
                }
            }
            viewModel.addFiles(uris)
        }
    }

    // ─── Permission launcher ─────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            launchFilePicker()
        } else {
            Toast.makeText(this, "Storage permission is required to select files", Toast.LENGTH_LONG).show()
        }
    }

    // ─── MANAGE_EXTERNAL_STORAGE launcher (Android 11+) ─────────────────
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            launchFilePicker()
        } else {
            Toast.makeText(this, "Permission required for full file access", Toast.LENGTH_LONG).show()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupFormatSpinners()
        setupButtons()
        observeViewModel()
    }

    // ─── RecyclerView ────────────────────────────────────────────────────
    private fun setupRecyclerView() {
        adapter = VideoFileAdapter { file -> viewModel.removeFile(file) }
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = adapter
    }

    // ─── Format spinners ─────────────────────────────────────────────────
    private fun setupFormatSpinners() {
        val formats = viewModel.supportedFormats
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, formats)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.spinnerInputFormat.adapter = spinnerAdapter
        binding.spinnerOutputFormat.adapter = spinnerAdapter

        // Default: input = mkv, output = mp4
        binding.spinnerInputFormat.setSelection(formats.indexOf("mkv").coerceAtLeast(0))
        binding.spinnerOutputFormat.setSelection(formats.indexOf("mp4").coerceAtLeast(0))
    }

    // ─── Buttons ─────────────────────────────────────────────────────────
    private fun setupButtons() {
        binding.btnSelectFiles.setOnClickListener { checkPermissionsAndPick() }

        binding.btnClearFiles.setOnClickListener {
            viewModel.clearFiles()
        }

        binding.btnConvert.setOnClickListener {
            val outputFormat = binding.spinnerOutputFormat.selectedItem as? String ?: "mp4"
            viewModel.startConversion(outputFormat)
        }
    }

    // ─── Observe LiveData ────────────────────────────────────────────────
    private fun observeViewModel() {
        viewModel.videoFiles.observe(this) { files ->
            adapter.submitList(files.toList())
            binding.tvFileCount.text = if (files.isEmpty()) "" else "${files.size} file(s)"
            binding.btnClearFiles.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
            binding.btnConvert.isEnabled = files.isNotEmpty()
        }

        viewModel.statusMessage.observe(this) { msg ->
            binding.tvStatus.text = msg
        }

        viewModel.progress.observe(this) { pct ->
            binding.progressBar.progress = pct
            binding.tvProgressPercent.text = "$pct%"
        }

        viewModel.currentFileIndex.observe(this) { idx ->
            val total = viewModel.videoFiles.value?.size ?: 0
            if (total > 0 && idx > 0) {
                binding.tvCurrentFile.text = "File $idx / $total"
                binding.tvCurrentFile.visibility = View.VISIBLE
            } else {
                binding.tvCurrentFile.visibility = View.GONE
            }
        }

        viewModel.isConverting.observe(this) { converting ->
            binding.btnConvert.isEnabled = !converting && (viewModel.videoFiles.value?.isNotEmpty() == true)
            binding.btnSelectFiles.isEnabled = !converting
            binding.btnClearFiles.isEnabled = !converting
            binding.spinnerInputFormat.isEnabled = !converting
            binding.spinnerOutputFormat.isEnabled = !converting
            binding.progressContainer.visibility = if (converting) View.VISIBLE else View.GONE
        }

        viewModel.conversionFinished.observe(this) { finished ->
            if (finished) showDeletePrompt()
        }

        viewModel.errorMessage.observe(this) { err ->
            if (!err.isNullOrBlank()) {
                AlertDialog.Builder(this, R.style.AlertDialogTheme)
                    .setTitle("Conversion Errors")
                    .setMessage(err)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ─── Delete prompt ───────────────────────────────────────────────────
    private fun showDeletePrompt() {
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle("Delete Original Files?")
            .setMessage("Conversion complete. Would you like to delete the original input video files?")
            .setPositiveButton("Delete") { _, _ ->
                val count = viewModel.deleteOriginalFiles()
                Toast.makeText(this, "$count original file(s) deleted", Toast.LENGTH_SHORT).show()
                viewModel.resetAfterConversion()
            }
            .setNegativeButton("Keep") { _, _ ->
                viewModel.resetAfterConversion()
            }
            .setCancelable(false)
            .show()
    }

    // ─── Permissions ─────────────────────────────────────────────────────
    private fun checkPermissionsAndPick() {
        when {
            // Android 11+ – request MANAGE_EXTERNAL_STORAGE for best file access
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    launchFilePicker()
                } else {
                    AlertDialog.Builder(this, R.style.AlertDialogTheme)
                        .setTitle("Permission Required")
                        .setMessage("This app needs access to manage all files on your device for video conversion. Please grant 'All files access' in the next screen.")
                        .setPositiveButton("Grant") { _, _ ->
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            manageStorageLauncher.launch(intent)
                        }
                        .setNegativeButton("Use Document Picker") { _, _ ->
                            launchFilePicker()
                        }
                        .show()
                }
            }
            // Android 13+ – use READ_MEDIA_VIDEO
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    launchFilePicker()
                } else {
                    permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO))
                }
            }
            // Older versions
            else -> {
                val perms = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                val allGranted = perms.all {
                    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                }
                if (allGranted) {
                    launchFilePicker()
                } else {
                    permissionLauncher.launch(perms.toTypedArray())
                }
            }
        }
    }

    private fun launchFilePicker() {
        filePickerLauncher.launch(arrayOf("video/*"))
    }
}

package com.klischa.llmnotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.klischa.llmnotes.ui.ChatScreen
import com.klischa.llmnotes.ui.LLMNotesTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: LLMViewModel

    companion object {
        const val EXTRA_PREFILL_PROMPT = "extra_prefill_prompt"
    }

    // Лаунчер выбора файла .gguf из проводника Android
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val directPath = getRealPathFromUri(it)
            if (directPath != null && File(directPath).canRead() && File(directPath).length() > 0) {
                viewModel.saveLastModelInfo(directPath, it.toString(), File(directPath).name)
                viewModel.loadModel(directPath)
            } else {
                viewModel.saveLastModelInfo(null, it.toString(), "GGUF Модель")
                viewModel.loadModelUri(it)
            }
        }
    }

    // Универсальный лаунчер выбора файла ЛЮБОГО формата (скриншоты, фото, PDF, Office, архивы, аудио/видео, текст, бинарники)
    private val openAttachmentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val directPath = getRealPathFromUri(it)
            viewModel.attachUri(it, directPath) { errMsg ->
                runOnUiThread {
                    Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Лаунчер импорта локального SKILL.md / .txt файла в библиотеку скиллов
    private val openSkillFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { targetUri ->
            viewModel.importSkillFromUri(targetUri) { _, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Запрос разрешений на чтение медиа (фото/видео) для Device Tools
    private val requestMediaPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Телеметрия обновится автоматически при первом обращении
    }

    // Запрос разрешения com.termux.permission.RUN_COMMAND (при отказе открывает настройки приложения)
    private val requestTermuxPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshTermuxStatus()
        if (granted) {
            Toast.makeText(this, "✅ Разрешение Termux RUN_COMMAND выдано!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Откройте «Разрешения → Доп. разрешения → Run commands in Termux»",
                Toast.LENGTH_LONG
            ).show()
            openAppSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            System.setProperty("http.agent", com.klischa.llmnotes.api.OpenCodeClient.OPENCODE_USER_AGENT)
        } catch (_: Exception) {}

        viewModel = ViewModelProvider(this)[LLMViewModel::class.java]

        // 1. Запрос доступа к медиафайлам (для работы с фото/видео)
        requestMediaPermissions()

        // 2. Однократный запрос доступа ко всем файлам (не навязываем на каждом запуске)
        checkAndRequestAllFilesAccessOnce()

        // 3. Автоматическая загрузка локальной GGUF модели только в локальном режиме
        viewModel.tryAutoLoadLastModel()

        // 4. Поддержка передачи текста и файлов из виджета / системного «Поделиться»
        handleIncomingIntent(intent)

        setContent {
            LLMNotesTheme {
                ChatScreen(
                    viewModel = viewModel,
                    onSelectModelClick = {
                        openDocumentLauncher.launch(arrayOf("*/*"))
                    },
                    onAttachFileClick = {
                        openAttachmentLauncher.launch(arrayOf("*/*"))
                    },
                    onImportSkillFileClick = {
                        openSkillFileLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
                    },
                    onRequestTermuxPermissionClick = {
                        requestTermuxPermission()
                    },
                    onOpenStoragePermissionClick = {
                        openAllFilesAccessSettings()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::viewModel.isInitialized) {
            viewModel.refreshTermuxStatus()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val prefill = intent.getStringExtra(EXTRA_PREFILL_PROMPT)?.trim()
        if (!prefill.isNullOrEmpty()) {
            viewModel.updateInputText(prefill)
            intent.removeExtra(EXTRA_PREFILL_PROMPT)
        }

        // Поддержка системного «Поделиться» (скриншоты, фото, PDF, документы, текст и любые файлы)
        if (intent.action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!sharedText.isNullOrEmpty()) {
                viewModel.updateInputText(sharedText)
                intent.removeExtra(Intent.EXTRA_TEXT)
            }
            @Suppress("DEPRECATION")
            val sharedUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (sharedUri != null) {
                viewModel.attachUri(sharedUri) { errMsg ->
                    runOnUiThread {
                        Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
                    }
                }
                intent.removeExtra(Intent.EXTRA_STREAM)
            }
        }
    }

    private fun requestMediaPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
        try {
            requestMediaPermissionsLauncher.launch(permissions)
        } catch (_: Exception) {}
    }

    private fun requestTermuxPermission() {
        val status = TermuxBridge.checkStatus(this)
        if (!status.isInstalled) {
            Toast.makeText(this, "Termux не найден. Установите Termux с F-Droid или GitHub.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            requestTermuxPermissionLauncher.launch(TermuxBridge.PERMISSION_RUN_COMMAND)
        } catch (_: Exception) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        openAppSettings()
    }

    private fun checkAndRequestAllFilesAccessOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val prefs = getSharedPreferences("llm_app_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("asked_all_files_access", false)) return
            prefs.edit().putBoolean("asked_all_files_access", true).apply()
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Попытка извлечь реальный путь к файлу из URI провайдера Android,
     * если приложению предоставлен доступ к файловой системе (All Files Access).
     */
    private fun getRealPathFromUri(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme == "content") {
            try {
                if (DocumentsContract.isDocumentUri(this, uri)) {
                    val docId = DocumentsContract.getDocumentId(uri)
                    if (docId.startsWith("primary:")) {
                        val relPath = docId.substring("primary:".length)
                        val extDir = Environment.getExternalStorageDirectory().absolutePath
                        val directFile = File(extDir, relPath)
                        if (directFile.exists() && directFile.canRead()) {
                            return directFile.absolutePath
                        }
                    } else if (docId.startsWith("raw:")) {
                        val rawPath = docId.substring("raw:".length)
                        val rawFile = File(rawPath)
                        if (rawFile.exists() && rawFile.canRead()) {
                            return rawFile.absolutePath
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }
}

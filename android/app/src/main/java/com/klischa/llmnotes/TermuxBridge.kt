package com.klischa.llmnotes

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class TermuxStatus(
    val isInstalled: Boolean,
    val hasRunPermission: Boolean,
    val versionName: String? = null
)

data class TermuxResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val errorMessage: String? = null,
    val timedOut: Boolean = false
)

/**
 * Официальный мост к Termux через Intent `com.termux.RUN_COMMAND`.
 * Позволяет агенту запускать bash/python/git/shell-команды в фоне в окружении Termux
 * и получать обратно stdout, stderr и exitCode.
 */
object TermuxBridge {
    private const val TAG = "TermuxBridge"

    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_SERVICE_CLASS = "com.termux.app.RunCommandService"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    // Официальные ключи TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
    const val SETUP_PROPERTIES_CMD =
        "mkdir -p ~/.termux && grep -q '^allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings"

    private val nextExecutionId = AtomicInteger(1000)
    private val pendingExecutions = ConcurrentHashMap<Int, CompletableDeferred<TermuxResult>>()

    fun checkStatus(context: Context): TermuxStatus {
        val pm = context.packageManager
        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TERMUX_PACKAGE, 0)
            }
        } catch (_: Exception) {
            null
        }

        val isInstalled = pkgInfo != null
        val hasPerm = if (isInstalled) {
            context.checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        return TermuxStatus(
            isInstalled = isInstalled,
            hasRunPermission = hasPerm,
            versionName = pkgInfo?.versionName
        )
    }

    internal fun completeExecution(executionId: Int, result: TermuxResult) {
        pendingExecutions.remove(executionId)?.complete(result)
    }

    fun cancelAllPending() {
        val keys = pendingExecutions.keys.toList()
        for (id in keys) {
            pendingExecutions.remove(id)?.complete(
                TermuxResult(
                    success = false,
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    errorMessage = "Выполнение команды отменено пользователем"
                )
            )
        }
    }

    /**
     * Выполняет shell-команду в фоновом окружении Termux и ожидает stdout/stderr/exitCode.
     */
    suspend fun runCommand(
        context: Context,
        command: String,
        workdir: String = "/storage/emulated/0",
        timeoutSeconds: Int = 35
    ): TermuxResult {
        val appContext = context.applicationContext
        val status = checkStatus(appContext)
        if (!status.isInstalled) {
            return TermuxResult(
                success = false,
                exitCode = -1,
                stdout = "",
                stderr = "",
                errorMessage = "Termux не установлен на устройстве. Установите актуальный Termux с F-Droid или GitHub."
            )
        }
        if (!status.hasRunPermission) {
            return TermuxResult(
                success = false,
                exitCode = -1,
                stdout = "",
                stderr = "",
                errorMessage = "Нет разрешения com.termux.permission.RUN_COMMAND. Откройте Настройки Агента (иконка робота вверху) и нажмите «Выдать разрешение Termux»."
            )
        }

        val execId = nextExecutionId.getAndIncrement()
        val deferred = CompletableDeferred<TermuxResult>()
        pendingExecutions[execId] = deferred

        val resultIntent = Intent(appContext, TermuxResultReceiver::class.java).apply {
            action = "com.klischa.llmnotes.TERMUX_RESULT_$execId"
            putExtra(TermuxResultReceiver.EXTRA_EXECUTION_ID, execId)
        }

        val piFlags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(appContext, execId, resultIntent, piFlags)

        val effectiveWorkdir = workdir.trim().ifBlank { "/storage/emulated/0" }

        val runIntent = Intent().apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE_CLASS)
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH_PATH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, effectiveWorkdir)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_SESSION_ACTION, "0")
            putExtra(EXTRA_COMMAND_LABEL, "LLM Agent")
            putExtra(EXTRA_COMMAND_DESCRIPTION, command.take(120))
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    appContext.startForegroundService(runIntent)
                } catch (fgErr: Exception) {
                    Log.w(TAG, "startForegroundService fallback to startService: ${fgErr.message}")
                    appContext.startService(runIntent)
                }
            } else {
                appContext.startService(runIntent)
            }
        } catch (sec: SecurityException) {
            pendingExecutions.remove(execId)
            return TermuxResult(
                success = false,
                exitCode = -1,
                stdout = "",
                stderr = "",
                errorMessage = "Отказано в запуске Termux (${sec.message}). Проверьте разрешение RUN_COMMAND в настройках Android."
            )
        } catch (e: Exception) {
            pendingExecutions.remove(execId)
            return TermuxResult(
                success = false,
                exitCode = -1,
                stdout = "",
                stderr = "",
                errorMessage = "Не удалось запустить команду в Termux: ${e.message}"
            )
        }

        val boundedTimeoutMs = timeoutSeconds.coerceIn(5, 180) * 1000L
        val result = withTimeoutOrNull(boundedTimeoutMs) {
            deferred.await()
        }

        if (result == null) {
            pendingExecutions.remove(execId)
            return TermuxResult(
                success = false,
                exitCode = -1,
                stdout = "",
                stderr = "",
                timedOut = true,
                errorMessage = "Превышено время ожидания ответа от Termux (${timeoutSeconds}с). " +
                    "Убедитесь, что в ~/.termux/termux.properties прописано `allow-external-apps=true` и выполнено `termux-reload-settings` " +
                    "(кнопка копирования команды есть в меню Агента)."
            )
        }

        return result
    }
}

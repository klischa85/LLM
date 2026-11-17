package com.klischa.llmnotes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Приёмник результатов выполнения команд Termux RUN_COMMAND.
 * Регистрируется в AndroidManifest.xml (android:exported="false") и вызывается
 * через explicit mutable PendingIntent, переданный в com.termux.app.RunCommandService.
 */
class TermuxResultReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "TermuxResultReceiver"
        const val EXTRA_EXECUTION_ID = "llm_termux_execution_id"
        // Официальные ключи TermuxConstants.TERMUX_APP.TERMUX_SERVICE
        private const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"
        private const val KEY_STDOUT = "stdout"
        private const val KEY_STDERR = "stderr"
        private const val KEY_EXIT_CODE = "exitCode"
        private const val KEY_ERR = "err"
        private const val KEY_ERRMSG = "errmsg"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        if (executionId == -1) {
            Log.w(TAG, "Получен broadcast без execution_id")
            return
        }

        try {
            intent.extras?.classLoader = context?.classLoader
            val bundle = intent.getBundleExtra(EXTRA_PLUGIN_RESULT_BUNDLE)
            if (bundle == null) {
                TermuxBridge.completeExecution(
                    executionId,
                    TermuxResult(
                        success = false,
                        exitCode = -1,
                        stdout = "",
                        stderr = "",
                        errorMessage = "Termux вернул пустой результат (проверьте версию Termux >= 0.118 и настройку allow-external-apps = true)."
                    )
                )
                return
            }

            bundle.classLoader = context?.classLoader
            val stdout = bundle.getString(KEY_STDOUT) ?: ""
            val stderr = bundle.getString(KEY_STDERR) ?: ""
            val exitCode = bundle.getInt(KEY_EXIT_CODE, -1)
            val err = bundle.getInt(KEY_ERR, -1) // Activity.RESULT_OK == -1 означает отсутствие внутренних ошибок Termux
            val errMsg = bundle.getString(KEY_ERRMSG) ?: ""

            val hasInternalError = err != -1 && errMsg.isNotBlank()
            TermuxBridge.completeExecution(
                executionId,
                TermuxResult(
                    success = !hasInternalError && exitCode == 0,
                    exitCode = exitCode,
                    stdout = stdout,
                    stderr = stderr,
                    errorMessage = if (hasInternalError) errMsg else null
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка разбора результата Termux: ${e.message}", e)
            TermuxBridge.completeExecution(
                executionId,
                TermuxResult(
                    success = false,
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    errorMessage = "Ошибка обработки ответа Termux: ${e.message}"
                )
            )
        }
    }
}

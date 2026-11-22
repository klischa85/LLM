package com.klischa.llmnotes.web

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Мост к невидимому WebView веб-чата DeepSeek.
 *
 * Зачем: раньше каждое сообщение при провайдере DEEPSEEK_WEB открывало
 * полноэкранный веб-чат, и пользователь был вынужден прыгать между
 * нативным чатом и сайтом. Теперь WebView живёт скрытым (1-2 dp) прямо
 * в ChatScreen, а ViewModel обращается к нему через этот мост:
 * текст уходит программно, ответ возвращается в обычную ленту чата.
 *
 * Полноэкранный экран остаётся только для входа в аккаунт и ручного
 * управления (капча, смена аккаунта).
 *
 * Куки общие (CookieManager), поэтому сессия переживает пересоздание WebView.
 */
object DeepSeekWebBridge {

    private const val READY_TIMEOUT_MS = 25_000L
    private const val INPUT_TIMEOUT_MS = 20_000L

    /**
     * Счётчик запросов на перезагрузку скрытого WebView.
     * Это Compose-состояние: хост в ChatScreen подписан на него
     * и перечитывает страницу, когда полноэкранный экран закрылся
     * (например, после входа в аккаунт, чтобы подхватились куки).
     */
    var reloadTick by mutableStateOf(0)
        private set

    /** Просит скрытый хост перечитать страницу. */
    fun requestReload() { reloadTick++ }

    @Volatile private var webRef: WebView? = null
    @Volatile private var controllerRef: DeepSeekWebController? = null

    /** Последнее известное состояние входа в аккаунт DeepSeek. */
    @Volatile var isLoggedIn: Boolean = false
        private set

    /** Регистрирует WebView и контроллер при монтировании хоста. */
    fun bind(web: WebView, controller: DeepSeekWebController) {
        webRef = web
        controllerRef = controller
        isLoggedIn = runCatching { controller.isLoggedIn() }.getOrDefault(false)
    }

    /** Снимает регистрацию, если размонтирован именно этот WebView. */
    fun unbind(web: WebView) {
        if (webRef === web) {
            webRef = null
            controllerRef = null
            isLoggedIn = false
        }
    }

    fun updateLoginState(loggedIn: Boolean) {
        isLoggedIn = loggedIn
    }

    fun isBound(): Boolean = webRef != null && controllerRef != null

    /**
     * Ждёт появления живого WebView (хост мог ещё не смонтироваться).
     * Возвращает пару «WebView + контроллер» либо null по таймауту.
     */
    suspend fun awaitReady(
        timeoutMs: Long = READY_TIMEOUT_MS
    ): Pair<WebView, DeepSeekWebController>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val web = webRef
            val ctrl = controllerRef
            if (web != null && ctrl != null) {
                isLoggedIn = runCatching { ctrl.isLoggedIn() }.getOrDefault(isLoggedIn)
                return web to ctrl
            }
            delay(250L)
        }
        return null
    }

    /** Ждёт, пока на странице появится поле ввода (страница реально отрисована). */
    suspend fun awaitInputReady(
        web: WebView,
        controller: DeepSeekWebController,
        timeoutMs: Long = INPUT_TIMEOUT_MS
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { controller.isInputReady(web) }.getOrDefault(false)) return true
            delay(400L)
        }
        return false
    }
}

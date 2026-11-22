package com.klischa.llmnotes.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.klischa.llmnotes.web.DeepSeekWebBridge
import com.klischa.llmnotes.web.DeepSeekWebController

/**
 * Невидимый фоновый WebView веб-чата DeepSeek.
 *
 * Зачем: раньше каждое сообщение при провайдере DEEPSEEK_WEB открывало
 * полноэкранный сайт, и приходилось прыгать между нативным чатом и им.
 * Теперь страница живёт здесь постоянно (реальный размер, сдвинут за край экрана,
 * alpha 0, касания не ловит),
 * ViewModel отправляет в неё текст через [DeepSeekWebBridge], а ответ
 * возвращается в обычную ленту чата.
 *
 * Полноэкранный [DeepSeekWebScreen] остаётся только для входа в аккаунт,
 * капчи и ручного управления. После его закрытия мост просит перезагрузку
 * (reloadTick), чтобы подхватились обновлённые куки.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeepSeekHiddenHost() {
    val context = LocalContext.current
    val controller = remember { DeepSeekWebController(context.applicationContext) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Подписка на Compose-состояние моста: ручной вход завершён -> перечитываем страницу.
    val reloadTick = DeepSeekWebBridge.reloadTick
    LaunchedEffect(reloadTick) {
        if (reloadTick > 0) {
            runCatching { controller.persistCookies() }
            webView?.let { w -> runCatching { w.reload() } }
        }
    }

    AndroidView(
        // ВАЖНО: размер должен быть реальным. При 2x2 dp вьюпорт страницы
        // сжимался до нескольких пикселей, React-вёрстка DeepSeek не отрисовывала
        // кнопку отправки, форма молча не отправлялась — и чат «висел» в ожидании.
        // Поэтому даём нормальный размер и уводим окно далеко за левый край экрана.
        modifier = Modifier
            .size(width = 420.dp, height = 900.dp)
            .offset(x = (-10000).dp)
            // alpha 0f делало окно невидимым для рендерера: Chrome троттлит
            // отрисовку таких View, и SPA DeepSeek оставался пустым каркасом
            // (textLen 61-62 в логах 1.4.15). 0.01f визуально неразличимо,
            // но окно остаётся «видимым» и рисуется полноценно.
            .alpha(0.01f),
        factory = { ctx ->
            WebView(ctx.applicationContext).also { web ->
                controller.configure(web)
                // Просим не понижать приоритет рендерера: иначе в фоне страница
                // не догружается до нормального DOM (API 26+, minSdk 26).
                runCatching {
                    web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                }
                DeepSeekWebBridge.bind(web, controller)
                web.loadUrl(DeepSeekWebController.HOME_URL)
                webView = web
            }
        },
        onRelease = { web ->
            DeepSeekWebBridge.unbind(web)
            runCatching { controller.persistCookies() }
            runCatching { web.stopLoading() }
            runCatching { web.destroy() }
            webView = null
        }
    )
}

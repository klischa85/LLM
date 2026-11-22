package com.klischa.llmnotes.ui

import android.annotation.SuppressLint
import android.webkit.WebView
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
 * Теперь страница живёт здесь постоянно (2x2 dp, alpha 0, касания не ловит),
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
        modifier = Modifier
            .size(2.dp)
            .alpha(0f),
        factory = { ctx ->
            WebView(ctx.applicationContext).also { web ->
                controller.configure(web)
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

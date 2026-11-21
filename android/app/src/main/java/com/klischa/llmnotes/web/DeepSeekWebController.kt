package com.klischa.llmnotes.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Управляет отдельным WebView для официального веб-чата DeepSeek (провайдер без API-ключа).
 *
 * Сознательно НЕ переиспользует AgentBrowserEngine: тот является object с единственным
 * shared WebView и собственными WebViewClient, поэтому два экрана конфликтовали бы.
 */
class DeepSeekWebController(private val appContext: Context) {

    companion object {
        const val HOME_URL = "https://chat.deepseek.com"
        private const val HOST = "chat.deepseek.com"

        private val SEED_INPUT_JS = """
            (function(){
              const ta = document.querySelector('textarea');
              if (!ta) return false;
              const proto = window.HTMLTextAreaElement.prototype;
              const desc = Object.getOwnPropertyDescriptor(proto, 'value');
              if (desc && desc.set) desc.set.call(ta, %s); else ta.value = %s;
              ta.dispatchEvent(new Event('input', { bubbles: true }));
              ta.dispatchEvent(new Event('change', { bubbles: true }));
              ta.focus();
              return true;
            })()
        """

        private val EXTRACT_JS = """
            (function(){
              const tries = [
                () => document.querySelectorAll('[class*="ds-markdown"]'),
                () => document.querySelectorAll('[class*="markdown"]'),
                () => document.querySelectorAll('[class*="message"]')
              ];
              for (const pick of tries) {
                try {
                  const nodes = pick();
                  if (nodes && nodes.length) {
                    const t = nodes[nodes.length - 1].innerText;
                    if (t && t.trim().length > 1) return t.trim();
                  }
                } catch (e) {}
              }
              return '';
            })()
        """
    }

    private val cookies: CookieManager get() = CookieManager.getInstance()

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView) {
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            // убираем маркер "wv": снижает шанс антибот-проверок
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val host = request.url.host ?: return false
                val internal = host == HOST || host.endsWith(".deepseek.com") ||
                    host.endsWith("google.com") || host.endsWith("gstatic.com") ||
                    host.endsWith("cloudflare.com")
                if (internal) return false
                runCatching {
                    appContext.startActivity(
                        Intent(Intent.ACTION_VIEW, request.url)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return true
            }
        }
    }

    /** Сессия DeepSeek хранится в HttpOnly-cookie, поэтому читаем её только через CookieManager. */
    fun isLoggedIn(): Boolean {
        val raw = cookies.getCookie("https://$HOST") ?: return false
        return raw.split(";").any { part ->
            val name = part.substringBefore('=').trim()
            name.contains("session", ignoreCase = true) &&
                part.substringAfter('=', "").trim().isNotEmpty()
        }
    }

    fun persistCookies() {
        runCatching { cookies.flush() }
    }

    fun navigateHome(web: WebView) = web.loadUrl(HOME_URL)

    /** Вставляет исходный промпт в поле ввода веб-чата (если оно уже отрисовано). */
    suspend fun seedPrompt(web: WebView, prompt: String): Boolean {
        if (prompt.isBlank()) return false
        val quoted = JSONObject.quote(prompt)
        val js = SEED_INPUT_JS.trimIndent().replace("%s", quoted)
        return suspendCancellableCoroutine { cont ->
            web.evaluateJavascript(js) { result ->
                if (cont.isActive) cont.resume(result?.trim() == "true")
            }
        }
    }

    /** Извлекает текст последнего ответа ассистента: три стратегии по очереди. */
    suspend fun readLastAnswer(web: WebView): String? = suspendCancellableCoroutine { cont ->
        web.evaluateJavascript(EXTRACT_JS.trimIndent()) { result ->
            if (!cont.isActive) return@evaluateJavascript
            val clean = result
                ?.removeSurrounding("\"")
                ?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?.takeIf { it.isNotBlank() && it != "null" }
            cont.resume(clean)
        }
    }
}

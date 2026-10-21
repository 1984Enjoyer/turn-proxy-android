@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.captcha

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.lang.ref.WeakReference
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.freeturn.app.R
import com.freeturn.app.ui.components.BusyProgressIndicator
import java.net.URI
import java.util.Locale

/** Только локальный captcha-прокси ядра допускается к загрузке в WebView. */
internal fun isLocalCaptchaUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    val host = uri.host?.lowercase(Locale.ROOT)
    uri.scheme?.lowercase(Locale.ROOT) == "http" &&
        uri.rawUserInfo == null &&
        uri.port == 8765 &&
        (host == "127.0.0.1" || host == "localhost")
}.getOrDefault(false)

/**
 * Ручная капча провайдера во встроенном WebView. Грузит локальный прокси ядра
 * (127.0.0.1:8765), который переписывает страницы провайдера так, чтобы весь трафик шёл
 * через loopback. Диалог закрывается, когда ядро отрапортует о решении капчи
 * (onCaptcha("") -> ProxyStore.setCaptcha("") -> пустой captchaUrl в status).
 */
@Composable
fun CaptchaWebViewDialog(
    captchaUrl: String,
    onDismiss: () -> Unit
) {
    val valid = remember(captchaUrl) { isLocalCaptchaUrl(captchaUrl) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                stringResource(R.string.captcha_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.captcha_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        TextButton(shapes = ButtonDefaults.shapes(), onClick = onDismiss) {
                            Text(stringResource(R.string.captcha_close))
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (valid) {
                    CaptchaWebView(url = captchaUrl)
                } else {
                    Text(
                        text = stringResource(R.string.captcha_browser_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
    }
}

/**
 * Host for the automatic captcha page. It is mounted while an automatic challenge is active.
 * The Activity owns the WebView; the service only queues URLs through the controller, so
 * no Activity is launched when the app is backgrounded.
 */
@Composable
fun AutomaticCaptchaWebViewHost() {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize().alpha(0f).zIndex(-1f),
        factory = { createCaptchaWebView(context).also(AutomaticCaptchaWebViewHostController::attach) },
        onRelease = {
            AutomaticCaptchaWebViewHostController.detach(it)
            it.stopLoading()
            it.destroy()
        }
    )
}

/** Main-thread bridge from the long-lived VPN service to the Activity-owned WebView. */
object AutomaticCaptchaWebViewHostController {
    private val main = Handler(Looper.getMainLooper())
    private var webView = WeakReference<WebView>(null)
    private var pendingUrl = ""
    private var pendingChallengeId = Long.MIN_VALUE
    private var loadedChallengeId = Long.MIN_VALUE

    fun attach(view: WebView) {
        main.post {
            webView = WeakReference(view)
            loadedChallengeId = Long.MIN_VALUE
            loadPending(view)
        }
    }

    fun detach(view: WebView) {
        main.post {
            if (webView.get() === view) {
                webView.clear()
                loadedChallengeId = Long.MIN_VALUE
            }
        }
    }

    fun show(url: String, challengeId: Long) {
        val safeUrl = url.takeIf(::isLocalCaptchaUrl).orEmpty()
        main.post {
            pendingUrl = safeUrl
            pendingChallengeId = challengeId
            webView.get()?.let(::loadPending)
        }
    }

    fun clear() {
        main.post {
            pendingUrl = ""
            pendingChallengeId = Long.MIN_VALUE
            loadedChallengeId = Long.MIN_VALUE
            webView.get()?.apply {
                stopLoading()
                if (url != "about:blank") loadUrl("about:blank")
            }
        }
    }

    private fun loadPending(view: WebView) {
        if (pendingUrl.isBlank() || loadedChallengeId == pendingChallengeId) return
        loadedChallengeId = pendingChallengeId
        // The VPN service can deliver a challenge while the Activity is stopped.
        // Resume WebView timers before loading so the page's PoW work is not left
        // paused with the UI lifecycle.
        view.onResume()
        view.loadUrl(pendingUrl)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CaptchaWebView(url: String) {
    val context = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                createCaptchaWebView(context).apply {
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress / 100f
                        }
                    }
                    loadUrl(url)
                }
            },
            onRelease = { it.destroy() }
        )
        if (progress < 1f) {
            BusyProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createCaptchaWebView(context: android.content.Context) = WebView(context).apply {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // Keep the renderer bound to this app while the VPN foreground service is active,
        // even though the hidden CAPTCHA view is not on screen.
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
    }
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: android.webkit.WebResourceRequest
        ): Boolean = !isLocalCaptchaUrl(request.url.toString())
    }
}

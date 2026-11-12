package com.cardoctor.demo.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.cardoctor.demo.engine.CarPart
import com.cardoctor.demo.engine.DiagnosisReport

/**
 * 3D car viewer: a three.js scene (assets/car3d) in a transparent WebView, showing a glTF car
 * model. Falls back to the 2D [CarDiagram] when WebGL or the model cannot be loaded.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Car3DView(report: DiagnosisReport, ambient: Color, modifier: Modifier = Modifier) {
    var webGlFailed by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }

    if (webGlFailed) {
        CarDiagram(report, ambient, modifier)
        return
    }

    val stateJson = buildString {
        append("{\"parts\":{")
        append(
            CarPart.entries
                .mapNotNull { part -> report.gradeOf(part).takeIf { it > 0 }?.let { "\"${part.name}\":$it" } }
                .joinToString(",")
        )
        append("},\"ambient\":\"")
        append(String.format("#%06X", ambient.toArgb() and 0xFFFFFF))
        append("\"}")
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = true
                val main = Handler(Looper.getMainLooper())
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onReady(ok: Boolean) {
                            main.post { if (ok) pageReady = true else webGlFailed = true }
                        }
                    },
                    "Android"
                )
                // ES modules and the glTF loader need a real origin, so the assets are served
                // from https://appassets.androidplatform.net instead of file://.
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
                }
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.d(
                            "CAR3D",
                            "${message.messageLevel()} ${message.message()} (line ${message.lineNumber()})"
                        )
                        if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR && !pageReady) {
                            main.post { webGlFailed = true }
                        }
                        return true
                    }
                }
                loadUrl("https://appassets.androidplatform.net/assets/car3d/index.html")
            }
        },
        update = { webView ->
            if (pageReady) {
                webView.evaluateJavascript("window.setCarState && window.setCarState($stateJson)", null)
            }
        },
        onRelease = { it.destroy() }
    )
}

package tv.own.owntv.diplocine

import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import tv.own.owntv.R
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.core.theme.ThemeMode
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.theme.OwnTVTheme
import java.util.Locale

class DiploMobileActivity : ComponentActivity() {
    private lateinit var frame: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private var customView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val scan = registerForActivityResult(ScanContract()) { result ->
        val scanned = result.contents ?: return@registerForActivityResult
        val uri = Uri.parse(scanned)
        val code = if (DiploSite.isFirstParty(uri) && uri.path == "/dispositivos") {
            uri.getQueryParameter("pair").orEmpty().replace("-", "").trim().uppercase(Locale.ROOT)
        } else ""
        if (DiploSite.codeFormat.matches(code)) web.loadUrl(DiploSite.devicePage(code))
        else Toast.makeText(this, getString(R.string.diplo_invalid_qr), Toast.LENGTH_LONG).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); fitsSystemWindows = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        val tools = ComposeView(this).apply {
            setContent {
                OwnTVTheme(ThemeMode.DARK, AccentColor.TEAL, true, customAccent = "#E50914") {
                    Row(
                        Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFF101014))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StageButton(getString(R.string.diplo_modes), ::openModes, Modifier.weight(1f), icon = OwnTVIcon.BACK,
                            height = 42.dp, textSize = 16)
                        StageButton(getString(R.string.diplo_connect_tv), { web.loadUrl(DiploSite.origin + "/dispositivos") },
                            Modifier.weight(1.25f), icon = OwnTVIcon.LIVE_TV, height = 42.dp, textSize = 16)
                        StageButton(getString(R.string.diplo_qr), { scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt(getString(R.string.diplo_scan_prompt)).setBeepEnabled(false)) },
                            Modifier.weight(.68f), height = 42.dp, textSize = 16)
                    }
                }
            }
        }
        content.addView(tools)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        content.addView(progress, LinearLayout.LayoutParams(-1, (3 * resources.displayMetrics.density).toInt()))
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame || DiploSite.isFirstParty(request.url)) return false
                    if (request.url.scheme == "https") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    }
                    return true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        Toast.makeText(this@DiploMobileActivity, this@DiploMobileActivity.getString(R.string.diplo_site_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, value: Int) {
                    this@DiploMobileActivity.progress.progress = value
                    this@DiploMobileActivity.progress.visibility = if (value == 100) View.GONE else View.VISIBLE
                }
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (customView != null) { callback.onCustomViewHidden(); return }
                    customView = view
                    fullscreenCallback = callback
                    content.visibility = View.GONE
                    frame.addView(view, FrameLayout.LayoutParams(-1, -1))
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                override fun onHideCustomView() = closeFullscreen()
            }
        }
        content.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(DiploSite.origin)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> closeFullscreen()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closeFullscreen()
        content.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private fun openModes() {
        startActivity(Intent(this, DiploStartActivity::class.java).putExtra("choose", true))
        finish()
    }

    private fun closeFullscreen() {
        val view = customView ?: return
        frame.removeView(view)
        customView = null
        content.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }
}

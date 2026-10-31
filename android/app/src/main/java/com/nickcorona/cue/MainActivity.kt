package com.nickcorona.cue

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import kotlin.concurrent.thread

// The screens are the web app's (queue, focus, lift, cook, build, mood), so they never drift
// from what Claude renders; the app wraps them with native push, the widget and capture.
class MainActivity : Activity() {
    private var web: WebView? = null
    private var retriedSignIn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Cue.key(this) == null) setup() else browse(intent.getStringExtra("url"))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.getStringExtra("url") ?: return
        web?.loadUrl(Cue.BASE + url) ?: browse(url)
    }

    override fun onResume() {
        super.onResume()
        if (Cue.key(this) == null) return
        Push.sync(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !Cue.prefs(this).getBoolean("asked_notif", false)) {
            Cue.prefs(this).edit().putBoolean("asked_notif", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onPause() {
        super.onPause()
        if (Cue.key(this) != null) NowWidget.refreshAsync(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // Insets: targetSdk 35 draws edge to edge, so pad for the status and nav bars (and keyboard).
    private fun inset(v: View) {
        v.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private fun dp(x: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x.toFloat(), resources.displayMetrics).toInt()

    // First launch: the APP_KEY, once. It's checked against the server before it's kept.
    private fun setup(msg: String? = null) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(28)); setBackgroundColor(getColor(R.color.bg))
        }
        col.addView(TextView(this).apply { text = "Cue"; setTextColor(getColor(R.color.mint)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f) })
        col.addView(TextView(this).apply {
            text = msg ?: "Paste your app key (the part after /app/ in your sign-in link). It stays on this phone."
            setTextColor(getColor(R.color.dim)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(0, dp(8), 0, dp(16))
        })
        val box = EditText(this).apply {
            hint = "App key"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(getColor(R.color.dim))
        }
        col.addView(box)
        val go = Button(this).apply { text = "Sign in" }
        col.addView(go)
        go.setOnClickListener {
            val k = box.text.toString().trim().substringAfterLast("/app/").trim('/')
            if (k.isEmpty()) return@setOnClickListener
            go.isEnabled = false; go.text = "Checking…"
            thread {
                val ok = runCatching { Api(k).get("/push", "widget") }
                runOnUiThread {
                    if (ok.isSuccess) {
                        Cue.prefs(this).edit().putString("key", k).apply()
                        browse(null, signIn = true)
                        onResume()
                        NowWidget.refreshAsync(this)
                    } else {
                        val e = ok.exceptionOrNull()
                        setup(if (e is HttpError && e.code == 401) "That key didn't work. Try again." else "Couldn't reach Cue: ${e?.message}")
                    }
                }
            }
        }
        inset(col)
        setContentView(col)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun browse(url: String?, signIn: Boolean = false) {
        val w = WebView(this)
        web = w
        w.setBackgroundColor(getColor(R.color.bg))
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "$userAgentString CueAndroid/${BuildConfig.VERSION_NAME}"
        }
        w.addJavascriptInterface(Bridge(), "CueAndroid")
        w.webChromeClient = WebChromeClient() // alert() and confirm() dialogs
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == Uri.parse(Cue.BASE).host) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }
            override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, res: WebResourceResponse) {
                // The sign-in cookie went missing: sign in again with the stored key, once.
                if (req.isForMainFrame && res.statusCode == 401 && !retriedSignIn) {
                    retriedSignIn = true
                    view.loadUrl("${Cue.BASE}/app/${Cue.key(this@MainActivity)}")
                }
            }
            override fun onPageFinished(view: WebView, u: String) {
                view.evaluateJavascript(POLYFILL, null)
                CookieManager.getInstance().flush()
            }
        }
        val frame = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        frame.addView(w)
        inset(frame)
        setContentView(frame)
        val cookie = CookieManager.getInstance().getCookie(Cue.BASE) ?: ""
        if (signIn || !cookie.contains("nt_key=")) w.loadUrl("${Cue.BASE}/app/${Cue.key(this)}")
        else w.loadUrl(Cue.BASE + (url ?: "/"))
    }

    // What the web pages use that a WebView lacks: the share sheet, the clipboard, vibration
    // and the screen wake lock. The app's own notifications replace web push, so the page's
    // "Turn on notifications" stays hidden (no PushManager here).
    inner class Bridge {
        @JavascriptInterface fun share(text: String) = runOnUiThread {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        }
        @JavascriptInterface fun copy(text: String) = runOnUiThread {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Cue", text))
        }
        @JavascriptInterface fun vibrate(pattern: String) {
            val a = runCatching { JSONArray(pattern) }.getOrNull()
            val ms = if (a != null) LongArray(a.length()) { a.optLong(it) } else longArrayOf(pattern.toLongOrNull() ?: 0)
            if (ms.all { it == 0L }) return
            val v = getSystemService(Vibrator::class.java)
            if (ms.size == 1) v.vibrate(VibrationEffect.createOneShot(ms[0], VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(VibrationEffect.createWaveform(longArrayOf(0) + ms, -1))
        }
        @JavascriptInterface fun keepAwake(on: Boolean) = runOnUiThread {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private companion object {
        val POLYFILL = """(function(){if(window.__cueApp)return;window.__cueApp=1;
var d=function(o,k,v){try{Object.defineProperty(o,k,{value:v,configurable:true,writable:true})}catch(e){}};
d(navigator,'share',function(x){CueAndroid.share(String((x&&(x.text||x.url))||''));return Promise.resolve()});
d(navigator,'vibrate',function(p){CueAndroid.vibrate(JSON.stringify(p));return true});
d(navigator,'clipboard',{writeText:function(t){CueAndroid.copy(String(t));return Promise.resolve()}});
d(navigator,'wakeLock',{request:function(){CueAndroid.keepAwake(true);return Promise.resolve({release:function(){CueAndroid.keepAwake(false);return Promise.resolve()},addEventListener:function(){}})}});
})();"""
    }
}

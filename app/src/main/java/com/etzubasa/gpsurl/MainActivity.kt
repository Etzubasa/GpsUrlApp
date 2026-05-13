package com.etzubasa.gpsurl

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var loginLayout: LinearLayout
    private lateinit var webLayout: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var cbRemember: CheckBox
    private lateinit var btnLogin: Button
    private lateinit var prefs: SharedPreferences

    // WebView popup per Cloudflare Turnstile
    private var popupWebView: WebView? = null

    private val BASE_URL    = "https://www.gpsurl.com"
    private val DESKTOP_URL = "$BASE_URL/misc.php?do=setmobilebrowsing&mobile=no"
    private val LOGIN_URL   = "$BASE_URL/login.php?do=login"
    private val PREFS_NAME  = "gpsurl_prefs"
    private val KEY_ALIAS   = "gpsurl_key"
    private val CLEAN_HEADERS = mapOf("X-Requested-With" to "")

    private var pendingUser: String? = null
    private var pendingPass: String? = null
    private var loginAttempted = false
    private var desktopModeSet = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs       = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loginLayout = findViewById(R.id.loginLayout)
        webLayout   = findViewById(R.id.webLayout)
        progressBar = findViewById(R.id.progressBar)
        etUsername  = findViewById(R.id.etUsername)
        etPassword  = findViewById(R.id.etPassword)
        cbRemember  = findViewById(R.id.cbRemember)
        btnLogin    = findViewById(R.id.btnLogin)
        webView     = findViewById(R.id.webView)

        findViewById<View?>(R.id.tvCloudflare)?.visibility = View.GONE

        val savedUser = prefs.getString("username", "")
        val savedPass = loadEncryptedPassword()
        if (!savedUser.isNullOrEmpty() && savedPass != null) {
            etUsername.setText(savedUser)
            etPassword.setText(savedPass)
            cbRemember.isChecked = true
        }

        btnLogin.setOnClickListener { doLogin() }
        setupWebView()
    }

    private fun loadUrlClean(url: String) {
        webView.loadUrl(url, CLEAN_HEADERS)
    }

    private fun doLogin() {
        val user = etUsername.text.toString().trim()
        val pass = etPassword.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "Inserisci username e password", Toast.LENGTH_SHORT).show()
            return
        }
        if (cbRemember.isChecked) {
            prefs.edit().putString("username", user).apply()
            saveEncryptedPassword(pass)
        } else {
            prefs.edit().remove("username").remove("enc_pass").remove("enc_iv").apply()
        }
        pendingUser    = user
        pendingPass    = pass
        loginAttempted = false
        desktopModeSet = false
        loginLayout.visibility = View.GONE
        webLayout.visibility   = View.VISIBLE
        loadUrlClean(DESKTOP_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val defaultUA = WebSettings.getDefaultUserAgent(this)
        val desktopUA = defaultUA.replace(" Mobile ", " ").replace(" Mobile/", "/")

        with(webView.settings) {
            javaScriptEnabled        = true
            domStorageEnabled        = true
            databaseEnabled          = true
            loadsImagesAutomatically = true
            useWideViewPort          = true
            loadWithOverviewMode     = true
            mixedContentMode         = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess          = true
            userAgentString          = desktopUA
            // CHIAVE: abilita popup — necessario per Cloudflare Turnstile
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.contains("challenges.cloudflare.com") ||
                    url.contains("cloudflare.com")) return false
                view?.loadUrl(url, CLEAN_HEADERS)
                return true
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                val currentUrl = url ?: ""
                if (!desktopModeSet && currentUrl.contains("setmobilebrowsing")) {
                    desktopModeSet = true
                    loadUrlClean(LOGIN_URL)
                    return
                }
                if (!loginAttempted && pendingUser != null &&
                    currentUrl.contains("login", ignoreCase = true)) {
                    loginAttempted = true
                    injectLogin(pendingUser!!, pendingPass!!)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
            }

            // Gestisce il popup di Cloudflare Turnstile
            @SuppressLint("SetJavaScriptEnabled")
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                val popup = WebView(this@MainActivity)
                popup.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString   = webView.settings.userAgentString
                    mixedContentMode  = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

                popup.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) {
                        h?.proceed()
                    }
                }
                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(w: WebView?) {
                        // Chiude il popup quando Cloudflare ha finito
                        webLayout.removeView(popupWebView)
                        popupWebView?.destroy()
                        popupWebView = null
                    }
                }

                // Mostra il popup sopra il WebView principale
                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                popup.layoutParams = params
                webLayout.addView(popup)
                popupWebView = popup

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popup
                resultMsg?.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                webLayout.removeView(popupWebView)
                popupWebView?.destroy()
                popupWebView = null
            }
        }
    }

    private fun injectLogin(user: String, pass: String) {
        val safeUser = user.replace("\\", "\\\\").replace("'", "\\'")
        val safePass = pass.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("""
            (function() {
                var u = document.querySelector('input[name="vb_login_username"]')
                      || document.querySelector('input[name="username"]')
                      || document.querySelector('input[type="text"]');
                var p = document.querySelector('input[name="vb_login_password"]')
                      || document.querySelector('input[name="password"]')
                      || document.querySelector('input[type="password"]');
                var b = document.querySelector('input[type="submit"]')
                      || document.querySelector('button[type="submit"]');
                if (u) u.value = '$safeUser';
                if (p) p.value = '$safePass';
                if (b) b.click();
            })();
        """.trimIndent(), null)
    }

    private fun logout() {
        pendingUser    = null
        pendingPass    = null
        loginAttempted = false
        desktopModeSet = false
        popupWebView?.let { webLayout.removeView(it); it.destroy(); popupWebView = null }
        CookieManager.getInstance().removeAllCookies(null)
        webView.clearHistory()
        webView.loadUrl("about:blank")
        webLayout.visibility   = View.GONE
        loginLayout.visibility = View.VISIBLE
        Toast.makeText(this, "Logout effettuato", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            popupWebView?.let {
                webLayout.removeView(it); it.destroy(); popupWebView = null; return true
            }
            if (webLayout.visibility == View.VISIBLE && webView.canGoBack()) {
                webView.goBack(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webLayout.visibility == View.VISIBLE) {
            logout(); return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        ks.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return kg.generateKey()
    }

    private fun saveEncryptedPassword(pass: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val enc = cipher.doFinal(pass.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("enc_pass", Base64.encodeToString(enc, Base64.DEFAULT))
            .putString("enc_iv",   Base64.encodeToString(cipher.iv, Base64.DEFAULT))
            .apply()
    }

    private fun loadEncryptedPassword(): String? {
        val encStr = prefs.getString("enc_pass", null) ?: return null
        val ivStr  = prefs.getString("enc_iv",   null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivStr, Base64.DEFAULT)))
            String(cipher.doFinal(Base64.decode(encStr, Base64.DEFAULT)), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }
}

package com.lightbrowse

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lightbrowse.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var zoomManager: ZoomManager
    private lateinit var zoomPersistence: ZoomPersistence

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- Init Zoom Persistence ---
        zoomPersistence = ZoomPersistence(this)

        // --- WebView Setup (performance-tuned for low-spec devices) ---
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT

            // Performance tuning for 4GB RAM devices
            blockNetworkImage = false
            loadsImagesAutomatically = true
            defaultTextEncodingName = "UTF-8"

            // Layout settings
            useWideViewPort = true
            loadWithOverviewMode = true

            // Zoom: disable built-in overlay controls, keep zoom engine active
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(true)

            // Text reflow — textZoom will be managed by ZoomManager
            textZoom = 100

            // Reduce memory pressure
            databaseEnabled = true
            allowContentAccess = true
            allowFileAccess = false
        }

        // Hardware acceleration
        binding.webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // --- Init Zoom Manager ---
        zoomManager = ZoomManager(this, binding.webView) { zoomPercent ->
            // Callback: zoom changed → update UI + persist
            showZoomIndicator(zoomPercent)
            zoomPersistence.saveZoomForDomain(binding.webView.url, zoomPercent)
        }
        zoomManager.attach()

        // --- WebView Client ---
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.progress = 0
                updateNavButtons()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                updateNavButtons()
                updateUrlBar(view?.url)

                // Restore per-domain zoom (like Chrome)
                val savedZoom = zoomPersistence.getZoomForDomain(url)
                zoomManager.setZoom(savedZoom, animate = false)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        // --- WebChrome Client (progress) ---
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }

        // --- Navigation Buttons ---
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            }
        }

        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) {
                binding.webView.goForward()
            }
        }

        // --- Search / URL Bar ---
        binding.urlBar.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl(textView.text.toString().trim())
                hideKeyboard()
                true
            } else false
        }

        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.urlBar.selectAll()
            }
        }

        binding.btnGo.setOnClickListener {
            loadUrl(binding.urlBar.text.toString().trim())
            hideKeyboard()
        }

        // --- Zoom Buttons ---
        binding.btnZoomIn.setOnClickListener {
            if (!zoomManager.zoomIn()) {
                Toast.makeText(this, "Max zoom", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnZoomOut.setOnClickListener {
            if (!zoomManager.zoomOut()) {
                Toast.makeText(this, "Min zoom", Toast.LENGTH_SHORT).show()
            }
        }

        // --- Load default page ---
        loadUrl("https://www.google.com")
    }

    /**
     * Show the zoom indicator overlay.
     */
    private fun showZoomIndicator(zoomPercent: Int) {
        binding.zoomIndicator.showZoomLevel(zoomPercent)
    }

    private fun loadUrl(input: String) {
        if (input.isEmpty()) return

        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${input.replace(" ", "+")}"
        }

        binding.webView.loadUrl(url)
        binding.urlBar.clearFocus()
    }

    private fun updateNavButtons() {
        binding.btnBack.isEnabled = binding.webView.canGoBack()
        binding.btnBack.alpha = if (binding.webView.canGoBack()) 1.0f else 0.3f

        binding.btnForward.isEnabled = binding.webView.canGoForward()
        binding.btnForward.alpha = if (binding.webView.canGoForward()) 1.0f else 0.3f
    }

    private fun updateUrlBar(url: String?) {
        url?.let {
            binding.urlBar.setText(it)
            binding.urlBar.setSelection(0)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}

/*
 * Copyright (c) 2026 Stromkreis contributors
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.ui.activity

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.ScriptHandler
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.openhab.habdroid.R
import org.openhab.habdroid.core.connection.CloudConnection
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.databinding.FragmentWebviewBinding
import org.openhab.habdroid.ui.ConnectionWebViewClient
import org.openhab.habdroid.ui.MainActivity
import org.openhab.habdroid.ui.setUpForConnection
import org.openhab.habdroid.util.getConnectionFactory
import org.openhab.habdroid.util.isDarkModeActive
import org.openhab.habdroid.util.loadActiveServerConfig

/**
 * Shows the openHAB Main UI of the Stromkreis gateway in a WebView, using the current
 * connection's credentials. Reloads itself only when the connection's URL or credentials change -
 * reloading otherwise would throw away the Main UI's live page state.
 */
class MainUiWebViewFragment :
    Fragment(),
    CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + job

    private var binding: FragmentWebviewBinding? = null
    private val webView get() = binding?.webview
    private var chromeScript: ScriptHandler? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext().getConnectionFactory().activeFlow.collectLatest { info ->
                    // Compare what is shown with what would be shown: the connection check runs
                    // on every resume and network change, but usually yields the same connection.
                    val newConnection = info.usableConnection
                    if (newConnection != null && identityOf(newConnection) != webView?.tag) {
                        loadWebsite()
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val binding = FragmentWebviewBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        webView?.apply {
            // Make sure not to pass window insets into the WebView, we already handle them in the activity
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ ->
                WindowInsetsCompat.CONSUMED
            }

            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress == 100) {
                        updateViewVisibility(null, null)
                    } else {
                        updateViewVisibility(null, newProgress)
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    // The Main UI never gets camera or microphone access (WebRTC)
                    Log.d(TAG, "Denying web permission request for ${request.resources.joinToString()}")
                    request.deny()
                }

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(TAG, "${message.message()} -- From line ${message.lineNumber()} of ${message.sourceId()}")
                    return true
                }
            }
        }

        binding?.retryButton?.setOnClickListener {
            Log.d(TAG, "Retry button clicked, reload website")
            loadWebsite()
        }
        binding?.emptyMessage?.text = getString(R.string.main_ui_error)

        if (savedInstanceState != null) {
            val savedUrl = savedInstanceState.getString(KEY_CURRENT_URL, DEFAULT_URL)
            Log.d(TAG, "Load website from savedInstanceState: $savedUrl")
            webView?.restoreState(savedInstanceState)
            loadWebsite(savedUrl)
        } else {
            loadWebsite()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        StromkreisChrome.uninstall(chromeScript)
        chromeScript = null
        webView?.destroy()
        binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
        webView?.pauseTimers()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.url?.let { outState.putString(KEY_CURRENT_URL, it) }
        webView?.saveState(outState)
    }

    fun goBack(): Boolean {
        if (webView?.canGoBack() == true) {
            val oldUrl = webView?.url
            do {
                webView?.goBack()
                // Skip redundant history entries while going back
            } while (webView?.url == oldUrl && webView?.canGoBack() == true)
            return true
        }
        return false
    }

    fun reload() {
        loadWebsite()
    }

    private fun loadWebsite(urlToLoad: String = DEFAULT_URL) {
        val conn = requireContext().getConnectionFactory().currentActive?.usableConnection
        if (conn == null) {
            updateViewVisibility(true, null)
            return
        }
        updateViewVisibility(false, 0)

        val webView = webView ?: return
        val url = buildUrl(conn, urlToLoad)

        Log.d(TAG, "Loading web page $url")
        webView.setUpForConnection(conn)
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.addJavascriptInterface(OHAppInterface(requireContext(), this), "OHApp")
        StromkreisChrome.uninstall(chromeScript)
        chromeScript = StromkreisChrome.installAtDocumentStart(webView, url)

        val onCredentialsRejected = {
            launch { (activity as? MainActivity)?.onCredentialsRejected() }
            Unit
        }
        webView.webViewClient = object : ConnectionWebViewClient(conn, onCredentialsRejected) {
            override fun onPageCommitVisible(view: WebView, url: String) {
                if (chromeScript == null) {
                    // No document-start scripts on this WebView version
                    StromkreisChrome.injectNow(view)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (chromeScript == null) {
                    StromkreisChrome.injectNow(view)
                }
            }

            private fun handleError(url: Uri) {
                if (url.path == PATH_FOR_ERROR) {
                    updateViewVisibility(true, null)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                Log.e(TAG, "onReceivedError() on URL: ${request.url}")
                handleError(request.url)
            }

            @Deprecated(message = "Function is called on older Android versions")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                Log.e(TAG, "onReceivedError() (deprecated) on URL: $failingUrl")
                updateViewVisibility(true, null)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                Log.e(TAG, "onReceivedHttpError() on URL: ${request.url}")
                handleError(request.url)
            }
        }
        webView.tag = identityOf(conn)
        webView.loadUrl(url.toString())
    }

    private fun buildUrl(connection: Connection, url: String): HttpUrl {
        val connectionUrl = connection.httpClient.buildUrl(url)
        val urlBuilder = connectionUrl.newBuilder()
        if (connection is CloudConnection && connectionUrl.host == connection.httpClient.targetHost) {
            urlBuilder
                .scheme(connection.proxyUrl.scheme)
                .host(connection.proxyUrl.host)
                .port(connection.proxyUrl.port)
        }

        val mainUiStartPage = context?.loadActiveServerConfig()?.mainUiStartPage
        if (!mainUiStartPage.isNullOrEmpty()) {
            urlBuilder.encodedPath(mainUiStartPage)
        }

        return urlBuilder.build()
    }

    /**
     * Change the visibility of the progress and error indicators and the WebView.
     * @param error null if the error state didn't change, true if an error occurred, false if an error was cleared.
     * @param loadingProgress null if no loading happens, current progress otherwise.
     */
    private fun updateViewVisibility(error: Boolean?, loadingProgress: Int?) {
        error?.let {
            webView?.isVisible = !error
            binding?.empty?.isVisible = error
        }
        binding?.progress?.apply {
            isVisible = loadingProgress != null
            progress = loadingProgress ?: 0
        }
    }

    private class OHAppInterface(private val context: Context, private val fragment: MainUiWebViewFragment) {
        @JavascriptInterface
        fun preferTheme(): String = "md" // Material design

        @JavascriptInterface
        fun preferDarkMode(): String {
            val nightMode = if (context.isDarkModeActive()) "dark" else "light"
            Log.d(TAG, "preferDarkMode(): $nightMode")
            return nightMode
        }

        @JavascriptInterface
        fun exitToApp() {
            Log.d(TAG, "exitToApp()")
            fragment.launch {
                fragment.activity?.moveTaskToBack(true)
            }
        }

        @JavascriptInterface
        fun goFullscreen() {
            Log.d(TAG, "goFullscreen()")
            fragment.launch {
                (fragment.activity as? MainActivity)?.appBarShown = false
            }
        }
    }

    companion object {
        private val TAG = MainUiWebViewFragment::class.java.simpleName

        /**
         * What determines the page content: target URL(s) and credentials. Deliberately not
         * [Connection.equals], which also covers the network and metered state, and never matches
         * between the remote connection and the cloud connection derived from it.
         */
        private fun identityOf(connection: Connection): String {
            val proxyUrl = (connection as? CloudConnection)?.proxyUrl
            return "${connection.httpClient.buildUrl("/")}|$proxyUrl|${connection.username}|${connection.password}"
        }

        private const val DEFAULT_URL = "/"
        private const val PATH_FOR_ERROR = "/"
        private const val KEY_CURRENT_URL = "url"
    }
}

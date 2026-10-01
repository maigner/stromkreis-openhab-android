/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
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

package org.openhab.habdroid.ui

import android.net.http.SslError
import android.util.Base64
import android.util.Log
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import org.openhab.habdroid.R
import org.openhab.habdroid.core.StromkreisSetup
import org.openhab.habdroid.core.connection.CloudConnection
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.util.openInBrowser

/**
 * @param onCredentialsRejected called when one of the connection's own hosts rejects the stored
 * credentials (e.g. the Stromkreis Cloud password changed). The app has no credential entry -
 * access is provisioned via the QR/link setup - so the challenge is cancelled instead of retried.
 */
open class ConnectionWebViewClient(
    val connection: Connection,
    private val onCredentialsRejected: (() -> Unit)? = null
) : WebViewClient() {
    private val answeredAuthHosts = mutableSetOf<String>()

    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
        val proxyHost = (connection as? CloudConnection)?.proxyUrl?.host
        if ((proxyHost != null && host == proxyHost) || host == connection.httpClient.targetHost) {
            // A second challenge for a host we already answered means the credentials were rejected
            if (!answeredAuthHosts.add(host) || connection.username.isNullOrEmpty()) {
                Log.w(TAG, "Stored credentials rejected by $host")
                handler.cancel()
                onCredentialsRejected?.invoke()
                return
            }
            handler.proceed(connection.username, connection.password)
        } else {
            super.onReceivedHttpAuthRequest(view, handler, host, realm)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        @Suppress("DEPRECATION")
        return shouldOverrideUrlLoading(view, request.url.toString())
    }

    // This is called on older Android versions
    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        StromkreisSetup.upgradedToHttps(url)?.let { upgraded ->
            // A redirect to the server's own http:// address; cleartext traffic is not permitted
            Log.d(TAG, "Upgrading $url to https")
            view.loadUrl(upgraded)
            return true
        }
        if (url == EMPTY_PAGE || view.url == EMPTY_PAGE) {
            Log.d(TAG, "Either current or new page is '$EMPTY_PAGE'")
            return false
        }

        val uri = url.toUri()
        val viewUri = view.url?.toUri()
        if (uri.host == viewUri?.host) {
            Log.d(TAG, "Same host: Load in WebView ($url)")
            return false
        }

        Log.d(TAG, "New host: Open in external browser ($url, WebView is on $viewUri)")
        uri.openInBrowser(view.context)

        return true
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Standard certificate validation only: invalid certificates are never accepted
        Log.e(TAG, "Invalid certificate for ${error.url}")
        handler.cancel()
        val context = view.context
        val host = connection.httpClient.targetHost
        val errorMessage = when (error.primaryError) {
            SslError.SSL_NOTYETVALID -> context.getString(R.string.error_certificate_not_valid_yet)
            SslError.SSL_EXPIRED -> context.getString(R.string.error_certificate_expired)
            SslError.SSL_IDMISMATCH -> context.getString(R.string.error_certificate_wrong_host, host)
            SslError.SSL_DATE_INVALID -> context.getString(R.string.error_certificate_invalid_date)
            else -> context.getString(R.string.webview_ssl)
        }

        val html = "<html><body><p>$errorMessage</p><p>${error.certificate}</p></body></html>"
        val encodedHtml = Base64.encodeToString(html.toByteArray(), Base64.NO_PADDING)
        view.loadData(encodedHtml, "text/html; charset=UTF-8", "base64")
    }

    companion object {
        private val TAG = ConnectionWebViewClient::class.java.simpleName

        const val EMPTY_PAGE = "about:blank"
    }
}

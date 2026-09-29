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

package org.openhab.habdroid.ui

import android.webkit.HttpAuthHandler
import android.webkit.WebView
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.util.HttpClient

class ConnectionWebViewClientTest {
    private val webView = mock<WebView>()

    private fun connection(username: String?, password: String?) = mock<Connection> {
        on { httpClient } doReturn HttpClient(OkHttpClient(), "https://hac.stromkreis.net/", username, password)
        on { this.username } doReturn username
        on { this.password } doReturn password
    }

    @Test
    fun answersFirstChallengeWithStoredCredentials() {
        var rejected = 0
        val client = ConnectionWebViewClient(connection("user", "secret")) { rejected++ }
        val handler = mock<HttpAuthHandler>()

        client.onReceivedHttpAuthRequest(webView, handler, "hac.stromkreis.net", "realm")

        verify(handler).proceed("user", "secret")
        assertEquals(0, rejected)
    }

    @Test
    fun repeatedChallengeMeansCredentialsWereRejected() {
        var rejected = 0
        val client = ConnectionWebViewClient(connection("user", "wrong")) { rejected++ }
        client.onReceivedHttpAuthRequest(webView, mock(), "hac.stromkreis.net", "realm")

        val secondHandler = mock<HttpAuthHandler>()
        client.onReceivedHttpAuthRequest(webView, secondHandler, "hac.stromkreis.net", "realm")

        verify(secondHandler).cancel()
        verify(secondHandler, never()).proceed("user", "wrong")
        assertEquals(1, rejected)
    }

    @Test
    fun challengeWithoutStoredCredentialsIsRejected() {
        var rejected = 0
        val client = ConnectionWebViewClient(connection(null, null)) { rejected++ }
        val handler = mock<HttpAuthHandler>()

        client.onReceivedHttpAuthRequest(webView, handler, "hac.stromkreis.net", "realm")

        verify(handler).cancel()
        assertEquals(1, rejected)
    }

    @Test
    fun foreignHostsAreNotTreatedAsRejection() {
        var rejected = 0
        val client = ConnectionWebViewClient(connection("user", "secret")) { rejected++ }
        val handler = mock<HttpAuthHandler>()

        client.onReceivedHttpAuthRequest(webView, handler, "example.org", "realm")
        client.onReceivedHttpAuthRequest(webView, handler, "example.org", "realm")

        verify(handler, never()).proceed("user", "secret")
        assertEquals(0, rejected)
    }
}

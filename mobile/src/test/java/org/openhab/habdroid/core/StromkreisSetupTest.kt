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

package org.openhab.habdroid.core

import java.net.URI
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StromkreisSetupTest {
    @Test
    fun appLinkWithPathToken() {
        val link = StromkreisSetup.parse("https://stromkreis.net/app/setup/AbC123-xyz")
        assertEquals(StromkreisSetupLink.Token("AbC123-xyz", "https://stromkreis.net"), link)
    }

    @Test
    fun appLinkWithQueryToken() {
        val link = StromkreisSetup.parse(URI("https://www.stromkreis.net/app/setup?token=t0k3n&x=1"))
        assertEquals(StromkreisSetupLink.Token("t0k3n", "https://www.stromkreis.net"), link)
    }

    @Test
    fun selfHostedPlatformKeepsOrigin() {
        val link = StromkreisSetup.parse("https://platform.example.org:8443/app/setup/tok")
        assertEquals(StromkreisSetupLink.Token("tok", "https://platform.example.org:8443"), link)
    }

    @Test
    fun customSchemeToken() {
        assertEquals(
            StromkreisSetupLink.Token("abc", StromkreisSetup.PLATFORM_ORIGIN),
            StromkreisSetup.parse("stromkreis://setup?token=abc")
        )
        assertEquals(
            StromkreisSetupLink.Token("abc", "https://dev.stromkreis.net"),
            StromkreisSetup.parse("stromkreis://setup?token=abc&origin=https%3A%2F%2Fdev.stromkreis.net%2Ffoo")
        )
    }

    @Test
    fun customSchemeInlineCredentials() {
        val link = StromkreisSetup.parse(
            "stromkreis://setup?username=anlage-7@stromkreis.net&password=abcdefghi123&siteName=Haus%207"
        )
        assertEquals(
            StromkreisSetupLink.Credentials(
                StromkreisCloudCredentials(
                    username = "anlage-7@stromkreis.net",
                    password = "abcdefghi123",
                    siteName = "Haus 7"
                )
            ),
            link
        )
    }

    @Test
    fun jsonInlineCredentials() {
        val json = """{"v":1,"cloudUrl":"https://hac.example.net","username":"u","password":"p"}"""
        assertEquals(
            StromkreisSetupLink.Credentials(StromkreisCloudCredentials("https://hac.example.net", "u", "p")),
            StromkreisSetup.parse(json)
        )
    }

    @Test
    fun rejectsUnrelatedPayloads() {
        assertNull(StromkreisSetup.parse(null))
        assertNull(StromkreisSetup.parse(""))
        assertNull(StromkreisSetup.parse("https://stromkreis.net/"))
        assertNull(StromkreisSetup.parse("https://stromkreis.net/app/setup/"))
        assertNull(StromkreisSetup.parse("openhab://command:Item:ON"))
        assertNull(StromkreisSetup.parse("stromkreis://other?token=x"))
        assertNull(StromkreisSetup.parse("{\"username\":\"u\"}"))
        assertNull(StromkreisSetup.parse("hello world"))
    }

    @Test
    fun cloudHostDetection() {
        assertTrue(StromkreisSetup.isCloudHost("hac.stromkreis.net"))
        assertTrue(StromkreisSetup.isCloudHost("HAC.stromkreis.net"))
        assertFalse(StromkreisSetup.isCloudHost("stromkreis.net"))
        assertFalse(StromkreisSetup.isCloudHost("myopenhab.org"))
        assertFalse(StromkreisSetup.isCloudHost(null))
    }

    @Test
    fun redeemsTokenAtPlatform() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"cloudUrl":"https://hac.stromkreis.net","username":"anlage-7@stromkreis.net",""" +
                    """"password":"secret","siteName":"Haus Mustermann"}"""
            )
        )
        server.start()
        try {
            val origin = server.url("/").toString().trimEnd('/')
            val credentials = runBlocking {
                StromkreisSetup.resolve(StromkreisSetupLink.Token("one-time", origin), OkHttpClient())
            }
            assertEquals(
                StromkreisCloudCredentials(
                    "https://hac.stromkreis.net",
                    "anlage-7@stromkreis.net",
                    "secret",
                    "Haus Mustermann"
                ),
                credentials
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(StromkreisSetup.REDEEM_PATH, request.path)
            assertEquals("one-time", JSONObject(request.body.readUtf8()).getString("token"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectedTokenCarriesServerMessage() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(410).setBody("""{"error":"Bereits verwendet."}"""))
        server.start()
        try {
            val origin = server.url("/").toString().trimEnd('/')
            try {
                runBlocking { StromkreisSetup.redeem("used", origin, OkHttpClient()) }
                fail("Expected TokenRejected")
            } catch (e: StromkreisSetupException.TokenRejected) {
                assertEquals(410, e.status)
                assertEquals("Bereits verwendet.", e.serverMessage)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun inlineCredentialsResolveWithoutNetwork() {
        val credentials = StromkreisCloudCredentials(username = "u", password = "p")
        val resolved = runBlocking {
            StromkreisSetup.resolve(StromkreisSetupLink.Credentials(credentials), OkHttpClient())
        }
        assertEquals(credentials, resolved)
        assertEquals(StromkreisSetup.DEFAULT_CLOUD_URL, resolved.cloudUrl)
    }
}

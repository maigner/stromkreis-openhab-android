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

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import org.openhab.habdroid.R
import org.openhab.habdroid.model.ServerConfiguration
import org.openhab.habdroid.model.ServerPath
import org.openhab.habdroid.util.getActiveServerId
import org.openhab.habdroid.util.getNextAvailableServerId
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.getSecretPrefs

/**
 * Cloud login for a member's Stromkreis gateway, as handed out by the Stromkreis platform.
 */
data class StromkreisCloudCredentials(
    val cloudUrl: String = StromkreisSetup.DEFAULT_CLOUD_URL,
    val username: String,
    val password: String,
    val siteName: String? = null
)

/**
 * What a scanned QR code or an opened link asks the app to do.
 */
sealed class StromkreisSetupLink {
    /** A one-time token that must be redeemed at the Stromkreis platform ([origin]) for credentials. */
    data class Token(val token: String, val origin: String) : StromkreisSetupLink()

    /** Credentials embedded directly in the code (offline QR codes). */
    data class Credentials(val credentials: StromkreisCloudCredentials) : StromkreisSetupLink()
}

/**
 * Outcome of [StromkreisSetup.apply]. [connectionChanged] is false when re-redeeming a link for the
 * account that is already configured, so callers can skip a disruptive reload.
 */
data class StromkreisSetupResult(val config: ServerConfiguration, val connectionChanged: Boolean)

sealed class StromkreisSetupException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnrecognizedPayload : StromkreisSetupException("Not a Stromkreis setup code")

    /** The platform rejected the token; carries the HTTP status and the server's `error` text, if any. */
    class TokenRejected(val status: Int, val serverMessage: String?) :
        StromkreisSetupException("Setup token rejected with HTTP $status")

    class InvalidResponse : StromkreisSetupException("Unexpected reply from the Stromkreis platform")

    class Network(cause: Throwable) : StromkreisSetupException("Could not reach the Stromkreis platform", cause)
}

/**
 * Parses Stromkreis setup links / QR payloads, redeems one-time tokens and writes the resulting
 * cloud login into the app's server configuration.
 *
 * Accepted payloads:
 * - `https://stromkreis.net/app/setup/<token>` (also `?token=<token>`) - app link, printed as QR code
 * - `stromkreis://setup?token=<token>[&origin=https://stromkreis.net]` - custom scheme fallback
 * - `stromkreis://setup?cloudUrl=…&username=…&password=…[&siteName=…]` - inline credentials
 * - JSON `{"v":1,"username":"…","password":"…"[,"cloudUrl":"…","siteName":"…"]}` - inline credentials
 *
 * Redeeming: `POST <origin>/api/app/setup/v1` with body `{"token":"…"}` returns
 * `{"cloudUrl":"…","username":"…","password":"…","siteName":"…"}` (`cloudUrl` and `siteName` optional).
 * Any non-2xx reply may carry `{"error":"human readable reason"}`.
 *
 * See docs/stromkreis-onboarding.md for the full contract.
 */
object StromkreisSetup {
    private val TAG = StromkreisSetup::class.java.simpleName

    const val DEFAULT_CLOUD_URL = "https://hac.stromkreis.net"
    const val DEFAULT_CLOUD_HOST = "hac.stromkreis.net"
    const val PLATFORM_ORIGIN = "https://stromkreis.net"
    const val URL_SCHEME = "stromkreis"
    const val SETUP_PATH_PREFIX = "/app/setup"
    const val REDEEM_PATH = "/api/app/setup/v1"
    const val TRUSTED_DOMAIN = "stromkreis.net"
    private const val REDEEM_TIMEOUT_SECONDS = 15L

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * True when [host] is the Stromkreis Cloud (the openHAB Cloud instance members reach their gateway through).
     */
    fun isCloudHost(host: String?): Boolean = host.equals(DEFAULT_CLOUD_HOST, ignoreCase = true)

    /**
     * True for `https` URLs on `stromkreis.net` or one of its subdomains. The app never talks to
     * anything else: setup links, redeem origins and cloud URLs pointing elsewhere are rejected.
     */
    fun isTrusted(uri: URI): Boolean {
        if (uri.scheme?.lowercase() != "https") {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return host == TRUSTED_DOMAIN || host.endsWith(".$TRUSTED_DOMAIN")
    }

    fun isTrusted(url: String): Boolean = runCatching { isTrusted(URI(url)) }.getOrDefault(false)

    /**
     * For an `http` URL on the trusted domain, the same URL over `https`; null for anything else.
     * Used to upgrade redirects that the proxied gateway emits with the wrong scheme - cleartext
     * traffic is not permitted.
     */
    fun upgradedToHttps(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull()?.takeIf { it.scheme == "http" } ?: return null
        val upgraded = httpUrl.newBuilder()
            .scheme("https")
            .port(if (httpUrl.port == 80) 443 else httpUrl.port)
            .build()
            .toString()
        return upgraded.takeIf { isTrusted(it) }
    }

    // Parsing

    fun parse(text: String?): StromkreisSetupLink? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        if (trimmed.startsWith("{")) {
            return parseJsonCredentials(trimmed)?.let { StromkreisSetupLink.Credentials(it) }
        }
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return null
        }
        return parse(uri)
    }

    fun parse(uri: URI): StromkreisSetupLink? {
        val scheme = uri.scheme?.lowercase() ?: return null
        val query = parseQuery(uri.rawQuery)

        if (scheme == URL_SCHEME) {
            // stromkreis://setup?...  - the host is "setup" (or the path when written as stromkreis:/setup)
            val action = (uri.host ?: uri.path?.trim('/')).orEmpty().lowercase()
            if (action != "setup") {
                return null
            }
            credentialsFromQuery(query)?.let { return StromkreisSetupLink.Credentials(it) }
            val token = query["token"] ?: return null
            val originString = query["origin"] ?: return StromkreisSetupLink.Token(token, PLATFORM_ORIGIN)
            val origin = runCatching { URI(originString) }.getOrNull()
                ?.takeIf { isTrusted(it) }
                ?.let { originOf(it) }
                ?: return null
            return StromkreisSetupLink.Token(token, origin)
        }

        // https://<platform>/app/setup/<token>, only on stromkreis.net
        if (!isTrusted(uri)) {
            return null
        }
        val origin = originOf(uri) ?: return null
        val path = uri.path.orEmpty()
        if (!path.lowercase().startsWith(SETUP_PATH_PREFIX)) {
            return null
        }
        query["token"]?.let { return StromkreisSetupLink.Token(it, origin) }
        val rest = path.substring(SETUP_PATH_PREFIX.length).trim('/')
        if (rest.isNotEmpty() && !rest.contains('/')) {
            return StromkreisSetupLink.Token(rest, origin)
        }
        val fragment = uri.fragment
        if (!fragment.isNullOrEmpty()) {
            return StromkreisSetupLink.Token(fragment, origin)
        }
        return null
    }

    private fun originOf(uri: URI): String? {
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://$host$port"
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) {
            return emptyMap()
        }
        val result = mutableMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isEmpty()) {
                return@forEach
            }
            val separator = pair.indexOf('=')
            val name = if (separator < 0) pair else pair.substring(0, separator)
            val value = if (separator < 0) "" else pair.substring(separator + 1)
            val decodedName = decode(name)
            val decodedValue = decode(value)
            if (decodedValue.isNotEmpty() && !result.containsKey(decodedName)) {
                result[decodedName] = decodedValue
            }
        }
        return result
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        value
    }

    private fun credentialsFromQuery(query: Map<String, String>): StromkreisCloudCredentials? {
        val username = query["username"] ?: return null
        val password = query["password"] ?: return null
        val cloudUrl = query["cloudUrl"] ?: DEFAULT_CLOUD_URL
        if (!isTrusted(cloudUrl)) {
            return null
        }
        return StromkreisCloudCredentials(
            cloudUrl = cloudUrl,
            username = username,
            password = password,
            siteName = query["siteName"]
        )
    }

    private fun parseJsonCredentials(text: String): StromkreisCloudCredentials? {
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        }
        val username = json.optString("username")
        val password = json.optString("password")
        if (username.isEmpty() || password.isEmpty()) {
            return null
        }
        val cloudUrl = json.optString("cloudUrl").ifEmpty { DEFAULT_CLOUD_URL }
        if (!isTrusted(cloudUrl)) {
            return null
        }
        return StromkreisCloudCredentials(
            cloudUrl = cloudUrl,
            username = username,
            password = password,
            siteName = json.optString("siteName").ifEmpty { null }
        )
    }

    // Redeeming

    /**
     * Resolves a setup link to credentials, contacting the platform when the link carries a token.
     */
    @Throws(StromkreisSetupException::class)
    suspend fun resolve(link: StromkreisSetupLink, client: OkHttpClient): StromkreisCloudCredentials = when (link) {
        is StromkreisSetupLink.Credentials -> link.credentials
        is StromkreisSetupLink.Token -> redeem(link.token, link.origin, client)
    }

    @Throws(StromkreisSetupException::class)
    suspend fun redeem(token: String, origin: String, client: OkHttpClient): StromkreisCloudCredentials =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("token", token).toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(origin.trimEnd('/') + REDEEM_PATH)
                .post(body)
                .header("Accept", "application/json")
                .build()
            val redeemClient = client.newBuilder()
                .callTimeout(REDEEM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

            val (status, responseText) = try {
                redeemClient.newCall(request).execute().use { response ->
                    Pair(response.code, response.body?.string().orEmpty())
                }
            } catch (e: IOException) {
                Log.e(TAG, "Redeeming setup token failed", e)
                throw StromkreisSetupException.Network(e)
            }

            if (status !in 200..299) {
                val message = try {
                    JSONObject(responseText).optString("error").ifEmpty { null }
                } catch (e: JSONException) {
                    null
                }
                Log.e(TAG, "Setup token rejected with HTTP $status")
                throw StromkreisSetupException.TokenRejected(status, message)
            }
            parseJsonCredentials(responseText) ?: throw StromkreisSetupException.InvalidResponse()
        }

    // Applying

    /**
     * Writes the credentials into the active server's remote (Stromkreis Cloud) connection, creating
     * the server if none exists yet.
     */
    fun apply(context: Context, credentials: StromkreisCloudCredentials): StromkreisSetupResult {
        val prefs = context.getPrefs()
        val secretPrefs = context.getSecretPrefs()
        val existing = ServerConfiguration.load(prefs, secretPrefs, prefs.getActiveServerId())
        val remotePath = ServerPath(credentials.cloudUrl, credentials.username, credentials.password)
        val siteName = credentials.siteName?.trim().orEmpty()
        val name = when {
            siteName.isNotEmpty() -> siteName
            existing != null && existing.name.isNotEmpty() -> existing.name
            else -> context.getString(R.string.stromkreis)
        }
        val config = if (existing != null) {
            ServerConfiguration.createFrom(existing, name = name, remotePath = remotePath)
        } else {
            ServerConfiguration(
                prefs.getNextAvailableServerId(),
                name,
                null,
                remotePath,
                null,
                null,
                false,
                null,
                null
            )
        }
        val connectionChanged = existing?.remotePath != config.remotePath
        if (config != existing) {
            config.saveToPrefs(prefs, secretPrefs)
        }
        Log.i(TAG, "Stromkreis Cloud connection configured for server ${config.id} (changed: $connectionChanged)")
        return StromkreisSetupResult(config, connectionChanged)
    }

    /**
     * True when the given server has a usable Stromkreis Cloud login (URL, user name and password).
     */
    fun isConfigured(config: ServerConfiguration?): Boolean {
        val remote = config?.remotePath ?: return false
        return remote.url.isNotEmpty() && !remote.userName.isNullOrEmpty() && !remote.password.isNullOrEmpty()
    }

    /**
     * True when the active server has a usable Stromkreis Cloud login.
     */
    fun isActiveServerConfigured(prefs: SharedPreferences, secretPrefs: SharedPreferences): Boolean =
        isConfigured(ServerConfiguration.load(prefs, secretPrefs, prefs.getActiveServerId()))

    fun isActiveServerConfigured(context: Context): Boolean =
        isActiveServerConfigured(context.getPrefs(), context.getSecretPrefs())
}

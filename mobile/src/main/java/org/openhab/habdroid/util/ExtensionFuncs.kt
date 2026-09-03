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

package org.openhab.habdroid.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import java.io.IOException
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.openhab.habdroid.R
import org.openhab.habdroid.core.OpenHabApplication
import org.openhab.habdroid.core.connection.ConnectionFactory
import org.openhab.habdroid.model.ServerConfiguration
import org.openhab.habdroid.util.Util.TAG

fun String?.toNormalizedUrl(): String? {
    if (isNullOrEmpty()) {
        return null
    }
    return try {
        val url = this
            .replace("\n", "")
            .replace(" ", "")
            .toHttpUrl()
            .toString()
        if (url.endsWith("/")) url else "$url/"
    } catch (e: IllegalArgumentException) {
        Log.d(TAG, "toNormalizedUrl(): Invalid URL '$this'")
        null
    }
}

fun String?.orDefaultIfEmpty(defaultValue: String) = if (isNullOrEmpty()) defaultValue else this

fun Uri?.openInBrowser(context: Context) {
    if (this == null) {
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, this)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.d(TAG, "Unable to open url in browser: $intent")
        context.showToast(R.string.error_no_browser_found, Toast.LENGTH_LONG)
    }
}

fun Context.getPrefs(): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

fun Context.getSecretPrefs(): SharedPreferences = (applicationContext as OpenHabApplication).secretPrefs

fun Context.getConnectionFactory(): ConnectionFactory = (applicationContext as OpenHabApplication).connectionFactory

/**
 * Shows an Toast and can be called from the background.
 */
fun Context.showToast(message: CharSequence, length: Int = Toast.LENGTH_SHORT) {
    GlobalScope.launch(Dispatchers.Main) {
        Toast.makeText(this@showToast, message, length).show()
    }
}

/**
 * Shows an Toast and can be called from the background.
 */
fun Context.showToast(@StringRes message: Int, length: Int = Toast.LENGTH_SHORT) {
    showToast(getString(message), length)
}

fun Context.hasPermissions(permissions: Array<String>) = permissions.firstOrNull {
    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
} == null

fun Context.openInAppStore(app: String) {
    val intent = Intent(Intent.ACTION_VIEW, "market://details?id=$app".toUri())
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        "http://play.google.com/store/apps/details?id=$app".toUri().openInBrowser(this)
    }
}

@ColorInt
fun Context.resolveThemedColor(@AttrRes colorAttr: Int, @ColorInt fallbackColor: Int = 0): Int =
    MaterialColors.getColor(this, colorAttr, fallbackColor)

fun Context.isDarkModeActive(): Boolean = when (getPrefs().getDayNightMode(this)) {
    AppCompatDelegate.MODE_NIGHT_NO -> false

    AppCompatDelegate.MODE_NIGHT_YES -> true

    else -> {
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        currentNightMode != Configuration.UI_MODE_NIGHT_NO
    }
}

fun Context.loadActiveServerConfig(): ServerConfiguration? {
    val activeServerId = getPrefs().getActiveServerId()
    return ServerConfiguration.load(getPrefs(), getSecretPrefs(), activeServerId)
}

fun Activity.shouldUseDynamicColors(): Boolean {
    val colorScheme = getPrefs().getStringOrEmpty(PrefKeys.COLOR_SCHEME)
    return DynamicColors.isDynamicColorAvailable() && colorScheme == getString(R.string.color_scheme_value_dynamic)
}

fun Activity.applyUserSelectedTheme() {
    setTheme(getActivityThemeId())
    if (shouldUseDynamicColors()) {
        DynamicColors.applyToActivityIfAvailable(this)
    }
}

@StyleRes fun Context.getActivityThemeId(): Int {
    val isBlackTheme = getPrefs().getStringOrNull(PrefKeys.THEME) == getString(R.string.theme_value_black)
    val colorScheme = getPrefs().getStringOrEmpty(PrefKeys.COLOR_SCHEME)
    val basicUiScheme = getString(R.string.color_scheme_value_basicui)
    return when {
        colorScheme == basicUiScheme && isBlackTheme -> R.style.openHAB_DayNight_Black_basicui
        colorScheme == basicUiScheme -> R.style.openHAB_DayNight_basicui
        isBlackTheme -> R.style.openHAB_DayNight_Black_orange
        else -> R.style.openHAB_DayNight_orange
    }
}

fun String.extractWifiSsid(): String? {
    if (this == WifiManager.UNKNOWN_SSID) {
        return null
    }
    return this.removeSurrounding("\"")
}

fun Context.getCurrentWifiSsid(attributionTag: String): String? {
    val wifiManager = getWifiManager(attributionTag)
    // TODO: Replace deprecated function
    @Suppress("DEPRECATION")
    return wifiManager.connectionInfo?.let { info ->
        if (info.networkId == -1) null else info.ssid.extractWifiSsid()
    }
}

fun Context.withAttribution(tag: String): Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    createAttributionContext(tag)
} else {
    this
}

fun Context.getWifiManager(attributionTag: String): WifiManager {
    // Android < N requires applicationContext for getting WifiManager, otherwise leaks may occur
    val context = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        applicationContext
    } else {
        withAttribution(attributionTag)
    }

    return context.getSystemService(Context.WIFI_SERVICE) as WifiManager
}

fun Socket.bindToNetworkIfPossible(network: Network?) {
    try {
        network?.bindSocket(this)
    } catch (e: IOException) {
        Log.d(TAG, "Binding socket $this to network $network failed: $e")
    }
}

@SuppressLint("UnspecifiedRegisterReceiverFlag")
fun Context.registerExportedReceiver(receiver: BroadcastReceiver?, intentFilter: IntentFilter): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED)
    } else {
        registerReceiver(receiver, intentFilter)
    }

inline fun <reified T> Intent.parcelable(key: String): T? {
    setExtrasClassLoader(T::class.java.classLoader)
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(key, T::class.java)

        else ->
            @Suppress("DEPRECATION")
            getParcelableExtra(key) as? T
    }
}

inline fun <reified T> Bundle.parcelable(key: String): T? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelable(key, T::class.java)

    else ->
        @Suppress("DEPRECATION")
        getParcelable(key) as? T
}

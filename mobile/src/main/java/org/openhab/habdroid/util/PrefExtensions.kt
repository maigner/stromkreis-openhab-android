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

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.openhab.habdroid.R

fun SharedPreferences.getActiveServerId(): Int = getInt(PrefKeys.ACTIVE_SERVER_ID, 0)

fun SharedPreferences.getPrimaryServerId(): Int = getInt(PrefKeys.PRIMARY_SERVER_ID, 0)

fun SharedPreferences.getNextAvailableServerId(): Int = getStringSet(PrefKeys.SERVER_IDS, null)
    ?.lastOrNull()
    .orDefaultIfEmpty("0")
    .let { idString -> idString.toInt() + 1 }

fun SharedPreferences.getConfiguredServerIds(): MutableSet<Int> = getStringSet(PrefKeys.SERVER_IDS, null)
    ?.map { id -> id.toInt() }
    ?.toMutableSet()
    ?: mutableSetOf()

fun SharedPreferences.isDebugModeEnabled(): Boolean = getBoolean(PrefKeys.DEBUG_MESSAGES, false)

fun SharedPreferences.isScreenTimerDisabled(): Boolean = getBoolean(PrefKeys.SCREEN_TIMER_OFF, false)

fun SharedPreferences.getDayNightMode(context: Context): Int = when (getStringOrNull(PrefKeys.THEME)) {
    context.getString(R.string.theme_value_light) -> AppCompatDelegate.MODE_NIGHT_NO

    context.getString(R.string.theme_value_dark), context.getString(R.string.theme_value_black) ->
        AppCompatDelegate.MODE_NIGHT_YES

    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    } else {
        AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
    }
}

fun SharedPreferences.getStringOrNull(key: String): String? = getString(key, null)

fun SharedPreferences.getStringOrEmpty(key: String): String = getString(key, "").orEmpty()

fun SharedPreferences.getStringOrFallbackIfEmpty(key: String, fallback: String): String {
    val value = getStringOrNull(key)
    return if (value.isNullOrEmpty()) fallback else value
}

fun SharedPreferences.Editor.putConfiguredServerIds(ids: Set<Int>) {
    putStringSet(PrefKeys.SERVER_IDS, ids.map { id -> id.toString() }.toSet())
}

fun SharedPreferences.Editor.putActiveServerId(id: Int) {
    putInt(PrefKeys.ACTIVE_SERVER_ID, id)
}

fun SharedPreferences.Editor.putPrimaryServerId(id: Int) {
    putInt(PrefKeys.PRIMARY_SERVER_ID, id)
}

fun PreferenceFragmentCompat.getPreference(key: String): Preference =
    findPreference(key) ?: throw IllegalArgumentException("No such preference: $key")

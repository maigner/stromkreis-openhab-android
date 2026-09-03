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

package org.openhab.habdroid.ui.preference.fragments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.openhab.habdroid.R
import org.openhab.habdroid.model.ServerConfiguration
import org.openhab.habdroid.ui.AbstractBaseActivity
import org.openhab.habdroid.ui.LogActivity
import org.openhab.habdroid.ui.OnboardingActivity
import org.openhab.habdroid.util.CacheManager
import org.openhab.habdroid.util.PrefKeys
import org.openhab.habdroid.util.getActiveServerId
import org.openhab.habdroid.util.getDayNightMode
import org.openhab.habdroid.util.getPreference
import org.openhab.habdroid.util.getPrefs

class MainSettingsFragment : AbstractSettingsFragment() {
    override val titleResId: Int @StringRes get() = R.string.action_settings

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences)

        getPreference("stromkreis_setup").setOnPreferenceClickListener {
            startActivity(Intent(it.context, OnboardingActivity::class.java))
            true
        }

        getPreference(PrefKeys.THEME).setOnPreferenceChangeListener { _, _ ->
            // getDayNightMode() needs the new preference value, so delay the call until
            // after this listener has returned
            parentActivity.launch(Dispatchers.Main) {
                AppCompatDelegate.setDefaultNightMode(parentActivity.getPrefs().getDayNightMode(parentActivity))
                parentActivity.handleThemeChange()
            }
            true
        }

        getPreference(PrefKeys.FULLSCREEN).setOnPreferenceChangeListener { _, newValue ->
            (activity as AbstractBaseActivity).setFullscreen(newValue as Boolean)
            true
        }

        getPreference(PrefKeys.CLEAR_CACHE).setOnPreferenceClickListener { pref ->
            clearCaches(pref.context)
            true
        }

        getPreference(PrefKeys.LOG).setOnPreferenceClickListener { preference ->
            startActivity(Intent(preference.context, LogActivity::class.java))
            true
        }
    }

    override fun onStart() {
        super.onStart()
        updateSetupSummary()
    }

    private fun updateSetupSummary() {
        val config = ServerConfiguration.load(prefs, secretPrefs, prefs.getActiveServerId())
        getPreference("stromkreis_setup").summary = if (config?.remotePath != null) {
            getString(R.string.settings_stromkreis_setup_connected, config.name)
        } else {
            getString(R.string.settings_stromkreis_setup_summary)
        }
    }

    private fun clearCaches(context: Context) {
        WebView(context).clearCache(true)
        // Get launch intent for application
        val restartIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        restartIntent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Finish current activity
        activity?.finish()
        CacheManager.getInstance(context).clearCache()
        // Start launch activity
        restartIntent?.let { startActivity(it) }
    }
}

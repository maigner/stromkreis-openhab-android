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

package org.openhab.habdroid.core

import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDexApplication
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.GeneralSecurityException
import org.openhab.habdroid.BuildConfig
import org.openhab.habdroid.R
import org.openhab.habdroid.core.connection.ConnectionFactory
import org.openhab.habdroid.core.connection.ConnectionManagerHelper
import org.openhab.habdroid.util.CrashReportingHelper
import org.openhab.habdroid.util.getDayNightMode
import org.openhab.habdroid.util.getPrefs

class OpenHabApplication : MultiDexApplication() {
    val secretPrefs: SharedPreferences by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                getEncryptedSharedPrefs()
            } catch (e: GeneralSecurityException) {
                // See https://github.com/openhab/openhab-android/issues/1807
                CrashReportingHelper.e(TAG, "Error getting encrypted shared prefs, try again.", exception = e)
                getEncryptedSharedPrefs()
            }
        } else {
            getSharedPreferences("secret_shared_prefs", MODE_PRIVATE)
        }
    }

    val connectionFactory: ConnectionFactory by lazy {
        ConnectionFactory(this, getPrefs(), secretPrefs, ConnectionManagerHelper.create(this))
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getEncryptedSharedPrefs() = EncryptedSharedPreferences.create(
        "secret_shared_prefs_encrypted",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        this,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun onCreate() {
        super.onCreate()
        if (CrashReportingHelper.isCrashReporterProcess()) {
            // No initialization of the app required
            Log.d(TAG, "Skip onCreate()")
            return
        }

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        CrashReportingHelper.initialize(this)
        AppCompatDelegate.setDefaultNightMode(getPrefs().getDayNightMode(this))

        connectionFactory.start()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Enable WebView debugging")
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        connectionFactory.shutdown()
    }

    companion object {
        private val TAG = OpenHabApplication::class.java.simpleName

        const val DATA_ACCESS_TAG_SERVER_DISCOVERY = "SERVER_DISCOVERY"
    }
}

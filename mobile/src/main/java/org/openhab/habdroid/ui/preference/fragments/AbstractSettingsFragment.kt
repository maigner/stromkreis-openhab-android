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

import androidx.annotation.StringRes
import androidx.preference.PreferenceFragmentCompat
import org.openhab.habdroid.ui.preference.PreferencesActivity
import org.openhab.habdroid.util.getSecretPrefs

abstract class AbstractSettingsFragment : PreferenceFragmentCompat() {
    @get:StringRes
    protected abstract val titleResId: Int

    protected val parentActivity get() = activity as PreferencesActivity
    protected val prefs get() = preferenceScreen.sharedPreferences!!
    protected val secretPrefs get() = requireContext().getSecretPrefs()

    override fun onStart() {
        super.onStart()
        parentActivity.supportActionBar?.setTitle(titleResId)
    }
}

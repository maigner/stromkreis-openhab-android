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

package org.openhab.habdroid.ui.preference

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.fragment.app.commit
import org.openhab.habdroid.R
import org.openhab.habdroid.databinding.ActivityPrefsBinding
import org.openhab.habdroid.ui.AbstractBaseActivity
import org.openhab.habdroid.ui.preference.fragments.MainSettingsFragment
import org.openhab.habdroid.util.parcelable

/**
 * The app's settings screen.
 */
class PreferencesActivity : AbstractBaseActivity() {
    private lateinit var resultIntent: Intent

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            resultIntent = Intent()
            supportFragmentManager.commit {
                add(R.id.activity_content, MainSettingsFragment())
            }
        } else {
            resultIntent = savedInstanceState.parcelable<Intent>(STATE_KEY_RESULT) ?: Intent()
        }
        setResult(RESULT_OK, resultIntent)
    }

    override fun inflateBinding(): CommonBinding {
        val binding = ActivityPrefsBinding.inflate(layoutInflater)
        return CommonBinding(binding.root, binding.appBar, binding.coordinator, binding.activityContent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(STATE_KEY_RESULT, resultIntent)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (isFinishing) {
            return true
        }
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    fun handleThemeChange() {
        resultIntent.putExtra(RESULT_EXTRA_THEME_CHANGED, true)
        recreate()
    }

    companion object {
        const val RESULT_EXTRA_THEME_CHANGED = "theme_changed"
        private const val STATE_KEY_RESULT = "result"
    }
}

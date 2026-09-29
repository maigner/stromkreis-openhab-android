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

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.openhab.habdroid.R
import org.openhab.habdroid.core.StromkreisSetup
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.core.connection.ConnectionFactory
import org.openhab.habdroid.core.connection.ConnectionNotInitializedException
import org.openhab.habdroid.core.connection.NetworkNotAvailableException
import org.openhab.habdroid.core.connection.NoUrlInformationException
import org.openhab.habdroid.databinding.ActivityMainBinding
import org.openhab.habdroid.databinding.FragmentStatusBinding
import org.openhab.habdroid.ui.activity.MainUiWebViewFragment
import org.openhab.habdroid.ui.preference.PreferencesActivity
import org.openhab.habdroid.util.HttpClient
import org.openhab.habdroid.util.getConnectionFactory
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.isScreenTimerDisabled
import org.openhab.habdroid.util.loadActiveServerConfig
import org.openhab.habdroid.util.orDefaultIfEmpty

/**
 * The app's main (and only) content screen: it shows the openHAB Main UI of the member's
 * Stromkreis gateway through the Stromkreis Cloud. When no connection is available, a status
 * screen with retry / setup actions is shown instead. The initial setup happens in
 * [OnboardingActivity].
 */
class MainActivity : AbstractBaseActivity() {
    private lateinit var prefs: SharedPreferences
    var connection: Connection? = null
        private set
    private var lastConnectionResult: ConnectionFactory.ConnectionResult? = null

    /**
     * The connection whose credentials the server rejected (e.g. the Stromkreis Cloud password
     * changed). While it is still the active one, the status screen is shown instead of the Main
     * UI, and the onboarding is not opened again for it.
     */
    private var rejectedConnection: Connection? = null

    private val preferenceActivityCallback =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.data?.getBooleanExtra(PreferencesActivity.RESULT_EXTRA_THEME_CHANGED, false) == true) {
                recreate()
            }
        }

    private val webViewFragment get() =
        supportFragmentManager.findFragmentById(R.id.activity_content) as? MainUiWebViewFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getPrefs()
        super.onCreate(savedInstanceState)
        // There is no navigation hierarchy, the toolbar only hosts the menu
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        if (!StromkreisSetup.isActiveServerConfigured(this)) {
            Log.d(TAG, "No Stromkreis Cloud login configured, starting onboarding")
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webViewFragment?.goBack() != true) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                getConnectionFactory().activeFlow.collectLatest { info ->
                    if (info.conn != lastConnectionResult) {
                        lastConnectionResult = info.conn
                        handleConnectionChange(info.conn)
                    }
                    // The Stromkreis Cloud check is an authenticated request, so a 401 there
                    // means the stored credentials are no longer valid
                    val cloudFailure = info.cloud?.failureReason as? HttpClient.HttpException
                    if (info.conn?.connection != null && cloudFailure?.statusCode == 401) {
                        onCredentialsRejected()
                    }
                }
            }
        }
    }

    override fun inflateBinding(): CommonBinding {
        val binding = ActivityMainBinding.inflate(layoutInflater)
        return CommonBinding(binding.root, binding.appBar, binding.coordinator, binding.activityContent)
    }

    override fun onStart() {
        super.onStart()
        window.setFlags(
            if (prefs.isScreenTimerDisabled()) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        updateTitle()
        // Make sure the connection state is up-to-date, e.g. after coming back from the onboarding
        getConnectionFactory().restartNetworkCheck()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.mainmenu_reload -> {
            webViewFragment?.reload() ?: retryConnection()
            true
        }

        R.id.mainmenu_settings -> {
            preferenceActivityCallback.launch(Intent(this, PreferencesActivity::class.java))
            true
        }

        R.id.mainmenu_about -> {
            startActivity(Intent(this, AboutActivity::class.java))
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun handleConnectionChange(result: ConnectionFactory.ConnectionResult?) {
        Log.d(TAG, "handleConnectionChange($result)")
        connection = result?.connection
        val failureReason = result?.failureReason
        when {
            connection != null && connection == rejectedConnection -> showCredentialsRejected()

            connection != null -> showMainUi()

            failureReason is NoUrlInformationException -> showStatus(
                StatusFragment.newInstance(
                    getString(R.string.configuration_missing),
                    R.drawable.ic_openhab_appicon_340dp,
                    showProgress = false,
                    button1TextResId = R.string.try_again_button,
                    button2TextResId = R.string.scan_setup_code_button
                )
            )

            result == null || failureReason is ConnectionNotInitializedException -> showStatus(
                StatusFragment.newInstance(null, 0, showProgress = true)
            )

            failureReason is NetworkNotAvailableException -> showConnectionError()

            else -> showConnectionError()
        }
        updateTitle()
    }

    private fun showConnectionError() {
        showStatus(
            StatusFragment.newInstance(
                getString(R.string.error_network_not_available),
                R.drawable.ic_network_strength_off_outline_black_24dp,
                showProgress = false,
                button1TextResId = R.string.try_again_button
            )
        )
    }

    private fun showMainUi() {
        if (webViewFragment != null) {
            // The fragment reloads by itself when the connection changes
            return
        }
        appBarShown = true
        supportFragmentManager.commit(allowStateLoss = true) {
            replace(R.id.activity_content, MainUiWebViewFragment())
        }
    }

    private fun showStatus(fragment: Fragment) {
        appBarShown = true
        supportFragmentManager.commit(allowStateLoss = true) {
            replace(R.id.activity_content, fragment)
        }
    }

    fun retryConnection() {
        if (rejectedConnection != null && rejectedConnection == connection) {
            // Give the stored credentials another chance, e.g. after a temporary server problem
            rejectedConnection = null
            showMainUi()
        }
        getConnectionFactory().restartNetworkCheck()
    }

    /**
     * Called when a server rejected the stored credentials. The app has no credential entry, so
     * the QR/link setup is opened again (once per rejected connection) with an explanation.
     */
    fun onCredentialsRejected() {
        val current = connection ?: return
        if (current == rejectedConnection) {
            return
        }
        Log.w(TAG, "Stored credentials rejected, starting onboarding")
        rejectedConnection = current
        showCredentialsRejected()
        startActivity(
            Intent(this, OnboardingActivity::class.java)
                .putExtra(OnboardingActivity.EXTRA_CREDENTIALS_REJECTED, true)
        )
    }

    private fun showCredentialsRejected() {
        showStatus(
            StatusFragment.newInstance(
                getString(R.string.credentials_rejected),
                R.drawable.ic_openhab_appicon_340dp,
                showProgress = false,
                button1TextResId = R.string.try_again_button,
                button2TextResId = R.string.scan_setup_code_button
            )
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!intent.hasExtra(EXTRA_SETUP_CONNECTION_CHANGED) || rejectedConnection == null) {
            return
        }
        if (intent.getBooleanExtra(EXTRA_SETUP_CONNECTION_CHANGED, false)) {
            // New credentials: the connection factory is switching to them. Wait for that
            // connection instead of reloading with the rejected one.
            showStatus(StatusFragment.newInstance(null, 0, showProgress = true))
        } else {
            // The setup re-confirmed the same credentials: forget the rejection and retry
            rejectedConnection = null
            if (connection != null) {
                showMainUi()
            }
        }
        getConnectionFactory().restartNetworkCheck()
    }

    private fun updateTitle() {
        title = loadActiveServerConfig()?.name.orDefaultIfEmpty(getString(R.string.app_name))
    }

    class StatusFragment :
        Fragment(),
        View.OnClickListener {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val arguments = requireArguments()
            val binding = FragmentStatusBinding.inflate(inflater, container, false)

            binding.description.apply {
                text = arguments.getCharSequence(KEY_MESSAGE)
                isVisible = !text.isNullOrEmpty()
            }
            binding.progress.isVisible = arguments.getBoolean(KEY_PROGRESS)

            val drawableResId = arguments.getInt(KEY_DRAWABLE)
            if (drawableResId != 0) {
                binding.image.setImageDrawable(ContextCompat.getDrawable(binding.root.context, drawableResId))
            } else {
                binding.image.isVisible = false
            }

            for ((button, key) in mapOf(binding.button1 to KEY_BUTTON_1_TEXT, binding.button2 to KEY_BUTTON_2_TEXT)) {
                val buttonTextResId = arguments.getInt(key)
                if (buttonTextResId != 0) {
                    button.setText(buttonTextResId)
                    button.setOnClickListener(this)
                } else {
                    button.isVisible = false
                }
            }
            return binding.root
        }

        override fun onClick(view: View) {
            if (view.id == R.id.button1) {
                (activity as? MainActivity)?.retryConnection()
            } else {
                startActivity(Intent(activity, OnboardingActivity::class.java))
            }
        }

        companion object {
            private const val KEY_MESSAGE = "message"
            private const val KEY_DRAWABLE = "drawable"
            private const val KEY_PROGRESS = "progress"
            private const val KEY_BUTTON_1_TEXT = "button1text"
            private const val KEY_BUTTON_2_TEXT = "button2text"

            fun newInstance(
                message: CharSequence?,
                @DrawableRes drawableResId: Int,
                showProgress: Boolean,
                @StringRes button1TextResId: Int = 0,
                @StringRes button2TextResId: Int = 0
            ) = StatusFragment().apply {
                arguments = bundleOf(
                    KEY_MESSAGE to message,
                    KEY_DRAWABLE to drawableResId,
                    KEY_PROGRESS to showProgress,
                    KEY_BUTTON_1_TEXT to button1TextResId,
                    KEY_BUTTON_2_TEXT to button2TextResId
                )
            }
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        /** Set by [OnboardingActivity] after a successful setup: whether the connection changed. */
        const val EXTRA_SETUP_CONNECTION_CHANGED = "setupConnectionChanged"
    }
}

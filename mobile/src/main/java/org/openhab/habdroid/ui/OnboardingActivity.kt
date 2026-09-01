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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.openhab.habdroid.R
import org.openhab.habdroid.core.StromkreisSetup
import org.openhab.habdroid.core.StromkreisSetupException
import org.openhab.habdroid.core.StromkreisSetupLink
import org.openhab.habdroid.databinding.ActivityOnboardingBinding

/**
 * First-run screen: scan the one-time QR code from the member's Stromkreis page (or paste the
 * setup link) to configure the Stromkreis Cloud connection. Also the target of
 * `stromkreis://setup?…` and `https://stromkreis.net/app/setup/…` links, which run the setup
 * automatically.
 *
 * The screen can only be closed once the active server has a Stromkreis Cloud login.
 */
class OnboardingActivity :
    AppCompatActivity(),
    CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + job

    private lateinit var binding: ActivityOnboardingBinding
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val httpClient by lazy { OkHttpClient() }
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraBound = false
    private var phase: Phase = Phase.Idle

    private sealed class Phase {
        object Idle : Phase()
        object Working : Phase()
        class Failed(val message: String) : Phase()
        class Succeeded(val name: String) : Phase()
    }

    private val cameraPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted ->
        if (granted) {
            bindCamera()
        } else {
            showScannerMessage(R.string.onboarding_camera_denied)
        }
    }

    private val canDismiss: Boolean get() = StromkreisSetup.isActiveServerConfigured(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Rounded corners for the camera preview (clipToOutline is not an XML attribute before API 31)
        binding.scannerFrame.clipToOutline = true

        binding.close.setOnClickListener { openMainAndFinish() }
        binding.continueButton.setOnClickListener { openMainAndFinish() }
        binding.retryButton.setOnClickListener { setPhase(Phase.Idle) }
        binding.connectButton.setOnClickListener { submitPastedLink() }
        binding.linkField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submitPastedLink()
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                phase is Phase.Working -> {
                    // wait for the setup to finish
                }
                canDismiss -> openMainAndFinish()
                // Without a Stromkreis Cloud login there is nothing to show behind this screen
                else -> finishAffinity()
            }
        }

        updateUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (phase is Phase.Idle) {
            startScanner()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        analysisExecutor.shutdown()
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        // Handle each link only once, e.g. not again after a configuration change
        intent.data = null
        Log.d(TAG, "Got setup link via intent")
        handleText(data.toString())
    }

    private fun submitPastedLink() {
        currentFocus?.let { focused ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(focused.windowToken, 0)
        }
        handleText(binding.linkField.text?.toString())
    }

    /**
     * Handles scanned, pasted or opened text. Reports an error when it is not a setup code.
     */
    private fun handleText(text: String?) {
        val link = StromkreisSetup.parse(text)
        if (link == null) {
            setPhase(Phase.Failed(getString(R.string.onboarding_error_not_setup_code)))
            return
        }
        runSetup(link)
    }

    private fun runSetup(link: StromkreisSetupLink) {
        if (phase is Phase.Working) {
            return
        }
        setPhase(Phase.Working)
        launch {
            try {
                val credentials = StromkreisSetup.resolve(link, httpClient)
                val config = StromkreisSetup.apply(this@OnboardingActivity, credentials)
                setPhase(Phase.Succeeded(config.name))
            } catch (e: StromkreisSetupException) {
                Log.e(TAG, "Stromkreis setup failed", e)
                setPhase(Phase.Failed(messageFor(e)))
            }
        }
    }

    private fun messageFor(e: StromkreisSetupException): String = when (e) {
        is StromkreisSetupException.UnrecognizedPayload -> getString(R.string.onboarding_error_not_setup_code)
        is StromkreisSetupException.TokenRejected -> {
            val serverMessage = e.serverMessage
            when {
                !serverMessage.isNullOrEmpty() -> serverMessage
                e.status in INVALID_TOKEN_STATUS_CODES -> getString(R.string.onboarding_error_token_rejected)
                else -> getString(R.string.onboarding_error_server_rejected, e.status)
            }
        }
        is StromkreisSetupException.InvalidResponse -> getString(R.string.onboarding_error_invalid_response)
        is StromkreisSetupException.Network -> {
            val detail = e.cause?.localizedMessage
            getString(R.string.onboarding_error_network) + if (detail.isNullOrEmpty()) "" else "\n$detail"
        }
    }

    private fun setPhase(newPhase: Phase) {
        phase = newPhase
        updateUi()
        if (newPhase is Phase.Idle) {
            startScanner()
        } else {
            stopScanner()
        }
    }

    private fun updateUi() {
        val currentPhase = phase
        binding.scannerCard.isVisible = currentPhase is Phase.Idle
        binding.pasteCard.isVisible = currentPhase is Phase.Idle
        binding.statusCard.isVisible = currentPhase !is Phase.Idle
        binding.statusProgress.isVisible = currentPhase is Phase.Working
        binding.statusIcon.isVisible = currentPhase is Phase.Succeeded || currentPhase is Phase.Failed
        binding.statusTitle.isVisible = currentPhase is Phase.Succeeded
        binding.continueButton.isVisible = currentPhase is Phase.Succeeded
        binding.retryButton.isVisible = currentPhase is Phase.Failed
        binding.close.isVisible = canDismiss && currentPhase !is Phase.Working

        when (currentPhase) {
            is Phase.Idle -> {}
            is Phase.Working -> binding.statusText.setText(R.string.onboarding_working)
            is Phase.Succeeded -> {
                binding.statusIcon.setImageResource(R.drawable.ic_check_circle_white_48dp)
                binding.statusTitle.text = getString(R.string.onboarding_connected, currentPhase.name)
                binding.statusText.setText(R.string.onboarding_ready)
            }
            is Phase.Failed -> {
                binding.statusIcon.setImageResource(R.drawable.ic_warning_yellow_48dp)
                binding.statusText.text = currentPhase.message
            }
        }
    }

    private fun openMainAndFinish() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    // QR code scanner

    private fun startScanner() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            showScannerMessage(R.string.onboarding_camera_unavailable)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            cameraPermissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindCamera() {
        val provider = cameraProvider
        if (provider != null) {
            bindUseCases(provider)
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val resolvedProvider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "Getting camera provider failed", e)
                showScannerMessage(R.string.onboarding_camera_unavailable)
                return@addListener
            }
            cameraProvider = resolvedProvider
            if (phase is Phase.Idle && !isFinishing) {
                bindUseCases(resolvedProvider)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        if (cameraBound) {
            return
        }
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(
            analysisExecutor,
            QrCodeAnalyzer { text -> runOnUiThread { onCodeScanned(text) } }
        )
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            cameraBound = true
            binding.scannerMessage.isVisible = false
        } catch (e: Exception) {
            Log.e(TAG, "Binding camera failed", e)
            showScannerMessage(R.string.onboarding_camera_unavailable)
        }
    }

    private fun stopScanner() {
        cameraProvider?.unbindAll()
        cameraBound = false
    }

    private fun onCodeScanned(text: String) {
        if (phase !is Phase.Idle) {
            return
        }
        Log.d(TAG, "Scanned QR code")
        handleText(text)
    }

    private fun showScannerMessage(@StringRes messageResId: Int) {
        binding.scannerMessage.setText(messageResId)
        binding.scannerMessage.isVisible = true
    }

    companion object {
        private val TAG = OnboardingActivity::class.java.simpleName
        private val INVALID_TOKEN_STATUS_CODES = listOf(401, 403, 404, 410)
    }
}

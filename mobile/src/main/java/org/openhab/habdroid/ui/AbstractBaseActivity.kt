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

package org.openhab.habdroid.ui

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.internal.EdgeToEdgeUtils
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.openhab.habdroid.R
import org.openhab.habdroid.databinding.AppBarBinding
import org.openhab.habdroid.util.PrefKeys
import org.openhab.habdroid.util.applyUserSelectedTheme
import org.openhab.habdroid.util.getConnectionFactory
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.resolveThemedColor

abstract class AbstractBaseActivity :
    AppCompatActivity(),
    CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + job
    lateinit var layoutForSnackbar: View
    private lateinit var binding: CommonBinding
    private lateinit var appBarBackground: Drawable
    private lateinit var insetsController: WindowInsetsControllerCompat
    private var lastInsets: WindowInsetsCompat? = null
    protected var lastSnackbar: Snackbar? = null
        private set
    private var snackbarQueue = mutableListOf<Snackbar>()

    var appBarShown = true
        set(value) {
            field = value
            // ScrollingViewBehavior assigns the AppBarLayout height as offset to other views (here: activity content)
            // even if the ABL is set to 'gone', hence we have to do this ugly workaround
            binding.appBar.root.layoutParams.height = if (value) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            applyPaddingsForWindowInsets()
        }

    protected val isFullscreenEnabled: Boolean
        get() = getPrefs().getBoolean(PrefKeys.FULLSCREEN, false)

    protected data class CommonBinding(
        val root: View,
        val appBar: AppBarBinding,
        val coordinator: CoordinatorLayout,
        val activityContent: View
    )

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        applyUserSelectedTheme()
        super.onCreate(savedInstanceState)

        binding = inflateBinding()
        setContentView(binding.root)

        setSupportActionBar(binding.appBar.openhabToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        enableDrawingBehindStatusBar()

        layoutForSnackbar = binding.coordinator
        insetsController = WindowInsetsControllerCompat(window, binding.coordinator)

        setNavigationBarColor()

        appBarBackground = MaterialShapeDrawable.createWithElevationOverlay(this)
        binding.appBar.root.statusBarForeground = appBarBackground
    }

    protected abstract fun inflateBinding(): CommonBinding

    @CallSuper
    override fun onStart() {
        super.onStart()
        getConnectionFactory().trustManager.bindDisplayActivity(this)
    }

    @CallSuper
    override fun onStop() {
        super.onStop()
        getConnectionFactory().trustManager.unbindDisplayActivity(this)
    }

    @CallSuper
    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    @CallSuper
    override fun onResume() {
        super.onResume()
        setFullscreen()
    }

    fun setFullscreen(isEnabled: Boolean = isFullscreenEnabled) {
        if (isEnabled) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun enableDrawingBehindStatusBar() {
        EdgeToEdgeUtils.applyEdgeToEdge(window, true)
        // Set up a listener to get the window insets so we can apply it to our views. It's important this listener
        // is applied to the toolbar for a combination of reasons:
        // 1) toolbar must be set fitsSystemWindows=true, as otherwise AppBarLayout does its own insets management,
        //    which conflicts with ours
        // 2) if the toolbar is set to fitsSystemWindow=true, it must not consume insets by itself, as otherwise
        //    it applies the insets to its own padding, which we do not want
        // -> Conclusion is that a) we need a listener on toolbar which consumes the insets, and b) we need a listener
        //    on something early in the hierarchy to get the full insets
        // -> Putting the listener on the toolbar fulfills both a) and b)
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar.openhabToolbar) { _, insets ->
            lastInsets = insets
            applyPaddingsForWindowInsets()
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun applyPaddingsForWindowInsets() {
        // On API levels < 30, insets visibility isn't factored in correctly, so getInsets() returns the
        // status bar and navigation bar insets there even if they're not currently visible due to us enabling
        // fullscreen mode. Work around this by manually checking the fullscreen mode in those cases.
        val insets = if (Build.VERSION.SDK_INT < 30 && isFullscreenEnabled) {
            Insets.NONE
        } else {
            val insetsType =
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout()
            lastInsets?.getInsets(insetsType) ?: Insets.NONE
        }
        // AppBarLayout uses its own insets calculations, which doesn't factor in status bar visibility on API < 30
        // (basically the same issue as above). To make sure it doesn't draw the background of the status bar (which
        // it thinks is present) over the actual toolbar, unset the status bar background if we think the status bar
        // is not to be shown.
        binding.appBar.root.apply {
            statusBarForeground = if (insets.top > 0) appBarBackground else null
            updatePadding(top = insets.top)
        }
        binding.activityContent.updatePadding(top = if (appBarShown) 0 else insets.top)
        binding.coordinator.updatePadding(bottom = insets.bottom)
    }

    private fun setNavigationBarColor() {
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        @ColorInt val black = ContextCompat.getColor(this, R.color.black)

        @ColorInt val windowColor = when {
            currentNightMode == Configuration.UI_MODE_NIGHT_YES ->
                resolveThemedColor(android.R.attr.windowBackground, black)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                resolveThemedColor(android.R.attr.windowBackground, black)

            else -> black
        }
        @Suppress("DEPRECATION")
        window.navigationBarColor = windowColor

        insetsController.isAppearanceLightNavigationBars = currentNightMode != Configuration.UI_MODE_NIGHT_YES
    }

    internal fun showSnackbar(
        tag: String,
        @StringRes messageResId: Int,
        @BaseTransientBottomBar.Duration duration: Int = Snackbar.LENGTH_LONG,
        @StringRes actionResId: Int = 0,
        onDismissListener: (() -> Unit)? = null,
        onClickListener: (() -> Unit)? = null
    ) {
        showSnackbar(tag, getString(messageResId), duration, actionResId, onDismissListener, onClickListener)
    }

    protected fun showSnackbar(
        tag: String,
        message: String,
        @BaseTransientBottomBar.Duration duration: Int = Snackbar.LENGTH_LONG,
        @StringRes actionResId: Int = 0,
        onDismissListener: (() -> Unit)? = null,
        onClickListener: (() -> Unit)? = null
    ) {
        fun showNextSnackbar() {
            if (lastSnackbar?.isShown == true || snackbarQueue.isEmpty()) {
                Log.d(TAG, "No next snackbar to show")
                return
            }
            val nextSnackbar = snackbarQueue.removeFirstOrNull()
            nextSnackbar?.show()
            lastSnackbar = nextSnackbar
        }

        if (tag.isEmpty()) {
            throw IllegalArgumentException("Tag is empty")
        }

        val snackbar = Snackbar.make(layoutForSnackbar, message, duration)
        if (actionResId != 0 && onClickListener != null) {
            snackbar.setAction(actionResId) { onClickListener() }
        }
        snackbar.view.tag = tag
        snackbar.addCallback(
            object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                override fun onShown(transientBottomBar: Snackbar?) {
                    super.onShown(transientBottomBar)
                    Log.d(TAG, "Show snackbar with tag $tag")
                }

                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    super.onDismissed(transientBottomBar, event)
                    if (event != Snackbar.Callback.DISMISS_EVENT_ACTION) {
                        onDismissListener?.invoke()
                    }
                    showNextSnackbar()
                }
            }
        )
        hideSnackbar(tag)
        Log.d(TAG, "Queue snackbar with tag $tag")
        snackbarQueue.add(snackbar)
        showNextSnackbar()
    }

    protected fun hideSnackbar(tag: String) {
        snackbarQueue.firstOrNull { it.view.tag == tag }?.let { snackbar ->
            Log.d(TAG, "Remove snackbar with tag $tag from queue")
            snackbarQueue.remove(snackbar)
        }
        if (lastSnackbar?.view?.tag == tag) {
            Log.d(TAG, "Hide snackbar with tag $tag")
            lastSnackbar?.dismiss()
            lastSnackbar = null
        }
    }

    companion object {
        private val TAG = AbstractBaseActivity::class.java.simpleName
    }
}

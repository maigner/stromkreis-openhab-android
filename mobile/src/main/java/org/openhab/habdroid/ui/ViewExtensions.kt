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

import android.annotation.SuppressLint
import android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.openhab.habdroid.R
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.util.resolveThemedColor

/**
 * Sets [SwipeRefreshLayout] color scheme according to colorPrimary and colorAccent
 */
fun SwipeRefreshLayout.applyColors() {
    val colors = listOf(R.attr.colorPrimary, R.attr.colorAccent)
        .map { attr -> context.resolveThemedColor(attr) }
        .toIntArray()
    setColorSchemeColors(*colors)
}

fun WebView.setUpForConnection(connection: Connection) {
    with(settings) {
        domStorageEnabled = true
        @SuppressLint("SetJavaScriptEnabled")
        javaScriptEnabled = true
        mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE
    }

    webViewClient = ConnectionWebViewClient(connection)
}

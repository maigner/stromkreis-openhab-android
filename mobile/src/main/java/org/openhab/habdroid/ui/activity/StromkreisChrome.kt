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

package org.openhab.habdroid.ui.activity

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.HttpUrl

/**
 * Hides the openHAB administration chrome of the Main UI: the left sidebar (admin login, openHAB
 * chat, help & about) with its hamburger toggle and swipe-open gesture, and the navbar actions on
 * the right (edit page, page settings, ...) that would let members change the setup. Stromkreis
 * members only use the pages on their gateway.
 *
 * Mirrors `stromkreisChromeJS` in the iOS app (`OpenHABWebViewModel.swift`).
 */
object StromkreisChrome {
    const val SCRIPT = """
(function () {
    if (window.__stromkreisChrome) return;
    window.__stromkreisChrome = true;
    var css = '.panel-left, .panel[data-panel="left"] { display: none !important; }'
        + ' .panel-backdrop { display: none !important; }'
        + ' .navbar .right a.link:not(.back), .navbar .right .link:not(.back) { display: none !important; }'
        + ' a.panel-open[data-panel="left"], a.panel-toggle[data-panel="left"], .navbar .left a.panel-open:not([data-panel="right"]) { display: none !important; }'
        + ' html.with-panel-left-cover .views, html.with-panel-left-reveal .views, .framework7-root > .views, .framework7-root > .view { margin-left: 0 !important; }';
    function addStyle() {
        if (document.getElementById('stromkreis-chrome')) return;
        var style = document.createElement('style');
        style.id = 'stromkreis-chrome';
        style.textContent = css;
        (document.head || document.documentElement).appendChild(style);
    }
    function disableSwipe() {
        var el = document.querySelector('.panel-left');
        var panel = el && el.f7Panel;
        if (panel && !panel.__stromkreisSwipeOff) {
            panel.__stromkreisSwipeOff = true;
            try { if (panel.opened) panel.close(false); } catch (e) {}
            try { if (typeof panel.disableSwipe === 'function') panel.disableSwipe(); } catch (e) {}
            try { if (typeof panel.disableVisibleBreakpoint === 'function') panel.disableVisibleBreakpoint(); } catch (e) {}
            try { panel.params.swipe = false; } catch (e) {}
        }
    }
    addStyle();
    var observer = new MutationObserver(function () { addStyle(); disableSwipe(); });
    function start() {
        addStyle();
        disableSwipe();
        if (document.documentElement) {
            observer.observe(document.documentElement, { childList: true, subtree: true });
        }
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
"""

    /**
     * Registers [SCRIPT] to run at document start for [url]'s origin, if the WebView supports it.
     * Returns null otherwise; callers then fall back to [injectNow] once the page has started loading.
     */
    fun installAtDocumentStart(webView: WebView, url: HttpUrl): ScriptHandler? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return null
        }
        val defaultPort = HttpUrl.defaultPort(url.scheme)
        val origin = "${url.scheme}://${url.host}" + if (url.port != defaultPort) ":${url.port}" else ""
        return WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf(origin))
    }

    fun uninstall(handler: ScriptHandler?) {
        if (handler != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handler.remove()
        }
    }

    fun injectNow(webView: WebView) {
        webView.evaluateJavascript(SCRIPT, null)
    }
}

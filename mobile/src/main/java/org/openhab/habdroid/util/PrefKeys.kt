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

object PrefKeys {
    /**
     * Server configuration
     */
    const val SERVER_IDS = "server_ids"
    const val ACTIVE_SERVER_ID = "active_server_id"
    const val PRIMARY_SERVER_ID = "primary_server_id"
    const val SERVER_NAME_PREFIX = "server_name_"
    const val LOCAL_URL_PREFIX = "local_url_"
    const val LOCAL_USERNAME_PREFIX = "local_username_"
    const val LOCAL_PASSWORD_PREFIX = "local_password_"
    const val REMOTE_URL_PREFIX = "remote_url_"
    const val REMOTE_USERNAME_PREFIX = "remote_username_"
    const val REMOTE_PASSWORD_PREFIX = "remote_password_"
    const val SSL_CLIENT_CERT_PREFIX = "sslclientcert_"
    const val DEFAULT_SITEMAP_NAME_PREFIX = "default_sitemap_name_"
    const val DEFAULT_SITEMAP_LABEL_PREFIX = "default_sitemap_label_"
    const val WIFI_SSID_PREFIX = "wifi_ssid_"
    const val RESTRICT_TO_SSID_PREFIX = "restrict_to_ssid_"
    const val FRONTAIL_URL_PREFIX = "frontail_url_"
    const val MAIN_UI_START_PAGE_PREFIX = "main_ui_start_page_"

    fun buildServerKey(id: Int, prefix: String) = "$prefix$id"

    /**
     * Settings
     */
    const val THEME = "theme"
    const val COLOR_SCHEME = "color_scheme"
    const val SCREEN_TIMER_OFF = "default_openhab_screentimeroff"
    const val FULLSCREEN = "default_openhab_fullscreen"
    const val CLEAR_CACHE = "default_openhab_clear_cache"
    const val DEBUG_MESSAGES = "default_openhab_debug_messages"
    const val LOG = "default_openhab_log"
}

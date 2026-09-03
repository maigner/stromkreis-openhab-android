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

import android.app.Application
import android.util.Log
import org.acra.ACRA
import org.acra.config.CoreConfigurationBuilder
import org.acra.config.DialogConfigurationBuilder
import org.acra.config.MailSenderConfigurationBuilder
import org.openhab.habdroid.BuildConfig
import org.openhab.habdroid.R

object CrashReportingHelper {
    private val TAG = CrashReportingHelper::class.java.simpleName

    // Stromkreis support mailbox (stromkreis.net mail is hosted on the Stromkreis mailcow)
    private const val CRASH_REPORT_MAIL = "app@stromkreis.net"

    fun initialize(app: Application) {
        val outdatedBuildMillis = BuildConfig.TIMESTAMP + (6L * 30 * 24 * 60 * 60 * 1000) // 6 months after build
        val isOutdated = outdatedBuildMillis < System.currentTimeMillis()
        Log.d(TAG, "ACRA status: isOutdated $isOutdated")
        if (isOutdated) {
            return
        }

        val builder = CoreConfigurationBuilder()
            .withBuildConfigClass(BuildConfig::class.java)
            .withPluginConfigurations(
                DialogConfigurationBuilder()
                    .withEnabled(true)
                    .withTitle(app.getString(R.string.crash_report_notification_title))
                    .withText(app.getString(R.string.crash_report_notification_text))
                    .withPositiveButtonText(app.getString(R.string.crash_report_notification_send_mail))
                    .build(),
                MailSenderConfigurationBuilder()
                    .withEnabled(true)
                    .withMailTo(CRASH_REPORT_MAIL)
                    .build()
            )

        ACRA.init(app, builder)
    }

    fun isCrashReporterProcess() = ACRA.isACRASenderServiceProcess()

    fun d(tag: String, message: String, remoteOnly: Boolean = false, exception: Exception? = null) {
        if (!remoteOnly) {
            Log.d(tag, message, exception)
        }
    }

    fun e(tag: String, message: String, remoteOnly: Boolean = false, exception: Exception? = null) {
        if (!remoteOnly) {
            Log.e(tag, message, exception)
        }
    }
}

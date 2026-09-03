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

import java.util.Locale
import org.openhab.habdroid.BuildConfig

object Util {
    val TAG: String = Util::class.java.simpleName

    val isFlavorStable get() = BuildConfig.FLAVOR.lowercase(Locale.ROOT).contains("stable")
    val isFlavorBeta get() = !isFlavorStable
}

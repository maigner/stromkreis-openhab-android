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

import android.content.Context
import java.io.File
import java.io.IOException
import okhttp3.Cache

class CacheManager private constructor(appContext: Context) {
    val httpCache: Cache = Cache(File(appContext.cacheDir, "http"), (10 * 1024 * 1024).toLong())

    fun clearCache() {
        try {
            httpCache.evictAll()
        } catch (ignored: IOException) {
            // ignored
        }
    }

    companion object {
        private var instance: CacheManager? = null

        fun getInstance(context: Context): CacheManager {
            val inst = instance ?: CacheManager(context.applicationContext)
            instance = inst
            return inst
        }
    }
}

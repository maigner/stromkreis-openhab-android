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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openhab.habdroid.model.toWifiSsids
import org.openhab.habdroid.ui.preference.fragments.AbstractSettingsFragment.Companion.isWeakPassword
import org.openhab.habdroid.ui.preference.fragments.ServerEditorFragment.Companion.beautifyUrl

class PreferencesUtilTest {
    @Test
    fun testIsWeakPassword() {
        assertTrue(isWeakPassword(""))
        assertTrue(isWeakPassword("abc"))
        assertTrue(isWeakPassword("abcd1234"))
        assertTrue(isWeakPassword("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertFalse(isWeakPassword("AbcD1234"))
        assertFalse(isWeakPassword("4BCd+-efgh"))
        assertFalse(isWeakPassword("Mb2.r5oHf-0t"))
        assertFalse(isWeakPassword("abcdefg1+"))
    }

    @Test
    fun testBeautifyHostName() {
        assertEquals("For invalid urls it should return the input value", "abc", beautifyUrl("abc"))
        assertEquals("For an empty string it should return an empty string", "", beautifyUrl(""))
        assertEquals(
            "URLs without scheme should treated like one with",
            "Stromkreis-Cloud",
            beautifyUrl("hac.stromkreis.net")
        )
        assertEquals(
            "For hac.stromkreis.net it should return Stromkreis-Cloud",
            "Stromkreis-Cloud",
            beautifyUrl("https://hac.stromkreis.net")
        )
        assertEquals("not.stromkreis.net", beautifyUrl("https://not.stromkreis.net"))
        assertEquals("myopenhab.org", beautifyUrl("https://myopenhab.org"))
        assertEquals("stromkreis.wrong_tld", beautifyUrl("https://stromkreis.WRONG_TLD"))
    }

    @Test
    fun testStringToWifiSsids() {
        val expected = setOf("foo", "bar")
        assertEquals(expected, "foo\nbar".toWifiSsids())
        assertEquals(expected, "foo \nbar".toWifiSsids())
        assertEquals(expected, "foo \n bar ".toWifiSsids())
        assertEquals(expected, " foo \n bar ".toWifiSsids())
        assertEquals(expected, "\n foo \n bar ".toWifiSsids())
        assertEquals(expected, "foo\nfoo\n bar ".toWifiSsids())
        assertEquals(expected, "\nfoo\nfoo\n bar ".toWifiSsids())
        assertEquals(expected, "foo\nfoo\n bar \nfoo".toWifiSsids())
    }
}

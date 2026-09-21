package com.firebolt9907.tangentbrowser.engine

import com.firebolt9907.tangentbrowser.state.PasskeyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAuthnGeckoPrefsTest {
    @Test
    fun nativeUsesAndroidFido2WithoutSofttoken() {
        val prefs = webAuthnGeckoPrefs(PasskeyMode.Native)

        assertTrue(prefs.contains("security.webauth.webauthn: true"))
        assertTrue(prefs.contains("security.webauth.webauthn_enable_softtoken: false"))
        assertTrue(prefs.contains("security.webauth.webauthn_enable_android_fido2: true"))
    }

    @Test
    fun nonNativeModesCannotFallThroughToAndroidFido2() {
        listOf(PasskeyMode.Softtoken, PasskeyMode.Bitwarden).forEach { mode ->
            assertTrue(
                "$mode must keep the Android route disabled",
                webAuthnGeckoPrefs(mode).contains(
                    "security.webauth.webauthn_enable_android_fido2: false",
                ),
            )
        }
        assertEquals("security.webauth.webauthn: false", webAuthnGeckoPrefs(PasskeyMode.Off))
    }
}

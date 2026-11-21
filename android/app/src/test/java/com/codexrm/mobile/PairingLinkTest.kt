package com.codexrm.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinkTest {
    @Test fun parsesAgentQrLink() {
        assertEquals(
            PairingData("https://rm.example.com", "ABCD-EFGH"),
            PairingLink.parse("https://rm.example.com/#pair=ABCD-EFGH"),
        )
    }

    @Test fun parsesEscapedCodeAndCustomPort() {
        assertEquals(
            PairingData("http://127.0.0.1:8080", "A B"),
            PairingLink.parse("http://127.0.0.1:8080/#pair=A%20B"),
        )
    }

    @Test fun manualCodeUsesNormalizedFallback() {
        assertEquals(
            PairingData("https://rm.example.com", "123456"),
            PairingLink.parse("123456", "rm.example.com/"),
        )
    }

    @Test fun manualHttpPairingRequiresCleartextWarning() {
        assertTrue(PairingLink.parse("123456", "http://127.0.0.1:8080").requiresCleartextWarning)
    }

    @Test fun scannedHttpPairingRequiresCleartextWarning() {
        assertTrue(PairingLink.parse("HTTP://127.0.0.1:8080/#pair=ABCD-EFGH").requiresCleartextWarning)
    }

    @Test fun httpsPairingDoesNotRequireCleartextWarning() {
        assertFalse(PairingLink.parse("https://rm.example.com/#pair=ABCD-EFGH").requiresCleartextWarning)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialInServer() {
        PairingLink.normalizeServer("https://user:secret@example.com")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpScheme() {
        PairingLink.parse("file:///tmp/#pair=123")
    }
}

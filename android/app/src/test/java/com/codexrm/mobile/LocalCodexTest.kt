package com.codexrm.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCodexTest {
    @Test fun foregroundRenewalTargetsColorOsDevices() {
        assertTrue(needsLocalForegroundRenewal("OPPO", "OPPO"))
        assertTrue(needsLocalForegroundRenewal("OnePlus", "oneplus"))
        assertTrue(needsLocalForegroundRenewal("Unknown", "realme"))
        assertFalse(needsLocalForegroundRenewal("Google", "google"))
        assertFalse(needsLocalForegroundRenewal("Samsung", "samsung"))
    }
}

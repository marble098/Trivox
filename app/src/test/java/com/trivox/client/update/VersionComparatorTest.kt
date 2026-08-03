package com.trivox.client.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test fun newerPatchIsDetected() {
        assertTrue(VersionComparator.isNewer("v0.2.31", "0.2.30"))
    }
    @Test fun sameOrOlderIsRejected() {
        assertFalse(VersionComparator.isNewer("0.2.30", "0.2.30"))
        assertFalse(VersionComparator.isNewer("0.2.29", "0.2.30"))
    }
}

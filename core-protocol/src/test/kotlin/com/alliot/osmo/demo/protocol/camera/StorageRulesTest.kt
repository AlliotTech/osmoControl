package com.alliot.osmo.demo.protocol.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [StorageRules.mountGuess] against the reverse-engineered handle bit, pinned to the real handles the
 * KDoc cites: internal handles set [StorageRules.INTERNAL_BIT] (Nano `0x4010xxxx`, Xtra/Action 5
 * internal `0x4004xxxx`, Action 6 `0x4010xxxx`), SD handles clear it (Xtra SD `0x0004xxxx`, Pocket 3
 * `0x0004xxxx`). Single-SD models (Pocket 3) are pinned to `storage=0` regardless of the bit.
 */
class StorageRulesTest {

    @Test
    fun `an internal-bit handle maps to storage 1`() {
        // Nano 0x4010xxxx / Action 6 0x4010xxxx / Xtra internal 0x4004xxxx.
        assertEquals(1, StorageRules.mountGuess(singleSdStorage = false, handle = 0x40100001L, cmdHandle = 0L))
        assertEquals(1, StorageRules.mountGuess(singleSdStorage = false, handle = 0x40040002L, cmdHandle = 0L))
    }

    @Test
    fun `an SD handle with the bit clear maps to storage 0`() {
        // Xtra SD 0x0004xxxx / Pocket 3 0x0004xxxx.
        assertEquals(0, StorageRules.mountGuess(singleSdStorage = false, handle = 0x00040003L, cmdHandle = 0L))
    }

    @Test
    fun `a single-SD model is always storage 0 regardless of the handle bit`() {
        // Even an internal-looking handle can't move a single-SD Pocket 3 off storage=0.
        assertEquals(0, StorageRules.mountGuess(singleSdStorage = true, handle = 0x40100001L, cmdHandle = 0L))
    }

    @Test
    fun `a photo with no delete handle falls back to the group cmd handle`() {
        // Photos carry no delete handle, so cmdHandle stands in — same per-store namespace, same bit.
        assertEquals(1, StorageRules.mountGuess(singleSdStorage = false, handle = 0L, cmdHandle = 0x40100005L))
        assertEquals(0, StorageRules.mountGuess(singleSdStorage = false, handle = 0L, cmdHandle = 0x00040005L))
    }

    @Test
    fun `no handle at all yields null so the caller probes`() {
        assertNull(StorageRules.mountGuess(singleSdStorage = false, handle = 0L, cmdHandle = 0L))
    }

    @Test
    fun `the internal bit constant is 0x40000000`() {
        assertEquals(0x40000000L, StorageRules.INTERNAL_BIT)
    }
}

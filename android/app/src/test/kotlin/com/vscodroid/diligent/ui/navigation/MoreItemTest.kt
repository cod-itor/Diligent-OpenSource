package com.vscodroid.diligent.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MoreItemTest {

    @Test
    fun `the list follows the guide's order`() {
        assertEquals(
            listOf(MoreItem.Run, MoreItem.Setup, MoreItem.AI, MoreItem.Devices, MoreItem.Share, MoreItem.Health, MoreItem.Settings),
            MoreItem.ordered,
        )
    }

    @Test
    fun `run, setup, share, health and settings open now, the rest are coming soon`() {
        assertEquals(listOf(MoreItem.Run, MoreItem.Setup, MoreItem.Share, MoreItem.Health, MoreItem.Settings), MoreItem.ordered.filter { it.available })
        for (item in listOf(MoreItem.AI, MoreItem.Devices)) {
            assertFalse(item.available, "$item")
        }
    }

    @Test
    fun `phase 1 entries are never coming soon`() {
        for (item in MoreItem.ordered.filter { it.phase == 1 }) {
            assertTrue(item.available, "$item is phase 1 and has no screen")
        }
        for (item in MoreItem.ordered.filterNot { it.available }) {
            assertTrue(item.phase > 1, "$item is coming soon but marked phase ${item.phase}")
        }
    }

    @Test
    fun `each detail screen belongs to exactly one entry`() {
        for (detail in MoreDetail.entries) {
            val owners = MoreItem.ordered.filter { it.detail == detail }
            assertEquals(1, owners.size, "$detail")
            assertEquals(owners.single(), MoreItem.of(detail))
        }
        assertTrue(MoreItem.of(MoreDetail.Run).available)
    }
}

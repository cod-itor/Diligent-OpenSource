package com.vscodroid.diligent.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MoreItemTest {

    @Test
    fun `the list follows the guide's order`() {
        assertEquals(
            listOf(MoreItem.Run, MoreItem.Setup, MoreItem.Laptop, MoreItem.AI, MoreItem.Devices, MoreItem.Share, MoreItem.Health, MoreItem.Storage, MoreItem.Settings),
            MoreItem.listed(beginnerMode = false),
        )
    }

    @Test
    fun `beginner mode keeps Run, Setup and Settings and moves the rest under Advanced`() {
        assertEquals(
            listOf(MoreItem.Run, MoreItem.Setup, MoreItem.Advanced, MoreItem.Settings),
            MoreItem.listed(beginnerMode = true),
        )
        assertEquals(
            listOf(MoreItem.Laptop, MoreItem.AI, MoreItem.Devices, MoreItem.Share, MoreItem.Health, MoreItem.Storage),
            MoreItem.advancedItems,
        )
    }

    @Test
    fun `every entry is reachable in both modes, exactly once`() {
        for (beginner in listOf(false, true)) {
            val top = MoreItem.listed(beginner)
            val reachable = top + if (MoreItem.Advanced in top) MoreItem.advancedItems else emptyList()
            assertEquals(MoreItem.ordered.filter { it != MoreItem.Advanced }.toSet(), reachable.toSet() - MoreItem.Advanced, "beginner=$beginner")
            assertEquals(reachable.size, reachable.toSet().size, "beginner=$beginner")
        }
        assertTrue(MoreItem.advancedItems.none { it.detail == MoreDetail.Advanced })
    }

    @Test
    fun `every entry opens now`() {
        assertEquals(MoreItem.ordered, MoreItem.ordered.filter { it.available })
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

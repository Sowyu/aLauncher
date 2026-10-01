package com.github.codeworkscreativehub.mlauncher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerSettleTest {
    private val fling = 1000f

    @Test
    fun flingDirectionWins() {
        assertTrue(HomeFragment.shouldSettleOpen(-2000f, 0.1f, fling))  // fast up, barely open
        assertFalse(HomeFragment.shouldSettleOpen(2000f, 0.9f, fling))  // fast down, nearly open
    }

    @Test
    fun slowReleaseSnapsToNearestEnd() {
        assertTrue(HomeFragment.shouldSettleOpen(-100f, 0.6f, fling))
        assertFalse(HomeFragment.shouldSettleOpen(100f, 0.4f, fling))
        assertTrue(HomeFragment.shouldSettleOpen(0f, 0.5f, fling))
    }
}

package com.github.codeworkscreativehub.mlauncher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerSettleTest {
    private val fling = 2600f // 1000dp/s at ~2.6x density

    @Test
    fun flingDirectionWins() {
        assertTrue(HomeFragment.shouldSettleOpen(-4000f, 0.1f, fling, opening = true))
        assertFalse(HomeFragment.shouldSettleOpen(4000f, 0.9f, fling, opening = false))
    }

    @Test
    fun shortFastSwipeUpOpens() {
        // 950px of a 2392px screen in 400ms, release velocity lost by the injector
        assertTrue(HomeFragment.shouldSettleOpen(0f, 950f / 2392f, fling, opening = true))
    }

    @Test
    fun slowReleaseFavoursGestureDirection() {
        assertFalse(HomeFragment.shouldSettleOpen(0f, 0.3f, fling, opening = true))
        assertTrue(HomeFragment.shouldSettleOpen(0f, 0.7f, fling, opening = false))
        assertFalse(HomeFragment.shouldSettleOpen(0f, 0.6f, fling, opening = false))
    }
}

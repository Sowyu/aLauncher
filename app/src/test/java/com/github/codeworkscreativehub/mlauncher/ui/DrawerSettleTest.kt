package com.github.codeworkscreativehub.mlauncher.ui

import com.github.codeworkscreativehub.mlauncher.ui.HomeFragment.Companion.SHEET_HALF
import com.github.codeworkscreativehub.mlauncher.ui.HomeFragment.Companion.settleTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerSettleTest {
    private val fling = 2600f // 1000dp/s at ~2.6x density

    @Test
    fun openingSwipeSettlesAtHalf() {
        assertEquals(SHEET_HALF, settleTarget(0f, 0.3f, fling, 0f))      // slow, past 35% of the way
        assertEquals(SHEET_HALF, settleTarget(-4000f, 0.2f, fling, 0f))  // fling up
        assertEquals(0f, settleTarget(0f, 0.15f, fling, 0f))             // barely moved
    }

    @Test
    fun hardFlingFromClosedGoesFull() {
        assertEquals(SHEET_HALF, settleTarget(-9000f, 0.3f, fling, 0f))  // even a hard fling stops at half
    }

    @Test
    fun halfToFullAndBack() {
        assertEquals(1f, settleTarget(0f, 0.8f, fling, SHEET_HALF))       // dragged up past 35%
        assertEquals(SHEET_HALF, settleTarget(0f, 0.7f, fling, SHEET_HALF))
        assertEquals(SHEET_HALF, settleTarget(4000f, 0.95f, fling, 1f))   // fling down from full
        assertEquals(0f, settleTarget(4000f, 0.5f, fling, SHEET_HALF))    // fling down from half
    }

    @Test
    fun withoutFullScreenModeTheSheetStaysAtHalf() {
        assertEquals(SHEET_HALF, settleTarget(-4000f, 0.7f, fling, SHEET_HALF, allowFull = false))
        assertEquals(SHEET_HALF, settleTarget(0f, 0.9f, fling, SHEET_HALF, allowFull = false))
        assertEquals(0f, settleTarget(4000f, 0.5f, fling, SHEET_HALF, allowFull = false))
    }
}

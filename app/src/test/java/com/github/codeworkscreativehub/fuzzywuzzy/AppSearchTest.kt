package com.github.codeworkscreativehub.fuzzywuzzy

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSearchTest {

    private val apps = listOf(
        "YouTube" to "com.google.android.youtube",
        "YouTube Morphe" to "app.morphe.android.youtube",
        "Google Maps" to "com.google.android.apps.maps",
        "WhatsApp" to "com.whatsapp",
        "WeChat" to "com.tencent.mm",
        "Calculator" to "com.google.android.calculator",
        "Revenge" to "com.discord",
        "Café Zürich" to "ch.cafe.zurich",
        "Wallet" to "com.google.android.apps.walletnfcrel",
        "Messages" to "com.google.android.apps.messaging",
        "Clock" to "com.google.android.deskclock",
        "Find Hub" to "com.google.android.apps.adm",
        "Gmail" to "com.google.android.gm",
        "BlueWallet" to "io.bluewallet.bluewallet",
        "Sound Connect" to "com.sony.songpal.mdr",
        "Google Fit" to "com.google.android.apps.fitness",
        "Signal" to "org.thoughtcrime.securesms",
    )
    private val keys = apps.map { AppSearch.SearchKey(it.first, it.second) }

    private fun search(q: String, fromStart: Boolean = false) =
        AppSearch.rank(keys, q, fuzzyThreshold = 0.25f, fromStart = fromStart).map { apps[it].first }

    @Test
    fun prefixMatchesShortestFirst() = assertEquals(listOf("YouTube", "YouTube Morphe"), search("you"))

    @Test
    fun initials() = assertEquals(listOf("Gmail", "Google Maps"), search("gm").take(2)) // prefix, then initials

    @Test
    fun prefixThenWordStartThenInitials() =
        assertEquals(listOf("Wallet", "BlueWallet", "WhatsApp"), search("wa").take(3))

    @Test
    fun camelCaseWordStart() {
        assertEquals(listOf("WeChat"), search("chat"))
        assertEquals(listOf("YouTube", "YouTube Morphe"), search("tube"))
    }

    @Test
    fun multiWordQuery() = assertEquals("Google Maps", search("goo ma").first())

    @Test
    fun calc() = assertEquals(listOf("Calculator"), search("calc"))

    @Test
    fun typo() = assertEquals(listOf("YouTube", "YouTube Morphe"), search("yotube").take(2))

    @Test
    fun transposition() = assertEquals("YouTube", search("youtbue").first())

    @Test
    fun accentsAndCase() = assertEquals(listOf("Café Zürich"), search("CAFE zur"))

    @Test
    fun packageNameRanksBelowLabel() = assertEquals(listOf("Revenge"), search("discord"))

    @Test
    fun exactBeatsPrefix() = assertEquals("YouTube", search("youtube").first())

    @Test
    fun fuzzyOnlyAppendedWhenFewStrongMatches() {
        // "Smart Pal" only matches "map" as a scattered subsequence
        val few = listOf("Maps", "Smart Pal").map { AppSearch.SearchKey(it, "x.y") }
        assertEquals(listOf(0, 1), AppSearch.rank(few, "map", 0.0001f))

        val many = listOf("Maps", "Map Tools", "Mapper", "Smart Pal").map { AppSearch.SearchKey(it, "x.y") }
        assertEquals(listOf(0, 2, 1), AppSearch.rank(many, "map", 0.0001f))
    }

    @Test
    fun vendorFromPackage() {
        val google = search("google")
        // Label matches first, then apps that are Google's only by package name
        assertEquals("Google Fit", google[0])
        assertEquals("Google Maps", google[1])
        assertEquals(
            setOf("Wallet", "Messages", "Clock", "Find Hub", "Gmail", "YouTube", "Calculator"),
            google.drop(2).toSet()
        )
        assertEquals(listOf("Sound Connect"), search("sony"))
    }

    @Test
    fun multiWordMatchesAllWordsFirst() {
        val result = search("google wallet")
        assertEquals("Wallet", result[0])
        assertEquals("BlueWallet", result[1]) // "wallet" in the label beats "google" only in the package
        assertEquals(true, result.indexOf("Google Maps") > 1)
        assertEquals(true, result.indexOf("Gmail") > result.indexOf("Google Maps"))
        assertEquals(false, "Signal" in result)
    }

    @Test
    fun searchFromStart() = assertEquals(emptyList<String>(), search("tube", fromStart = true))
}

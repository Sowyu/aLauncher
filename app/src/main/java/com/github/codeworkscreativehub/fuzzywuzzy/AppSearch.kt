package com.github.codeworkscreativehub.fuzzywuzzy

import java.text.Normalizer
import java.util.Locale

/**
 * Tiered app search. Results are ranked by:
 * 1. how many query words matched (label or package), so "google wallet" puts Google's Wallet
 *    above BlueWallet, which only matches "wallet";
 * 2. how many of those matched the label rather than only the package name;
 * 3. for partial matches, rarer query words count more: "wallet" (2 apps) outweighs "google" (20);
 * 4. label match quality: exact > prefix > word start (spaces, punctuation, camelCase) > initials
 *    > substring > package segment ("sony" finds Sound Connect, com.sony.songpal.mdr);
 * 5. shorter label first, then alphabetical.
 * Fuzzy matches (typos, scattered letters) are sorted by score and only appended when fewer
 * than [MIN_STRONG_RESULTS] real matches exist.
 *
 * Build one [SearchKey] per app when the list loads; [rank] then does no normalisation per app.
 */
object AppSearch {

    const val MIN_STRONG_RESULTS = 3

    private const val TIER_EXACT = 0
    private const val TIER_PREFIX = 1
    private const val TIER_WORD_START = 2
    private const val TIER_INITIALS = 3
    private const val TIER_SUBSTRING = 4
    private const val TIER_PACKAGE = 5
    private const val TIER_FUZZY = 6

    /** Package segments that say nothing about the app, e.g. the "com" and "android" in com.android.chrome. */
    private val PACKAGE_STOPWORDS = setOf("com", "org", "net", "io", "dev", "app", "apps", "android", "www")

    private val DIACRITICS = Regex("\\p{Mn}+")

    class SearchKey(label: String, packageName: String) {
        /** Lowercase letters and digits only: "Google Maps" -> "googlemaps". */
        val compact: String

        /** Lowercase with separators as spaces, for the subsequence scorer: "google maps". */
        val spaced: String

        /** Words split on non-alphanumerics and camelCase: "WhatsApp" -> [whats, app]. */
        val words: List<String>

        /** Start offset of each word inside [compact]. */
        val wordOffsets: IntArray

        /** First letter of each word: "Google Maps" -> "gm", "YouTube" -> "yt". */
        val initials: String

        /** Package segments minus generic ones: "com.google.android.deskclock" -> [google, deskclock]. */
        val packageSegments: List<String>

        val sortLabel: String

        init {
            val plain = stripDiacritics(label)
            val words = ArrayList<String>(4)
            val offsets = ArrayList<Int>(4)
            val compact = StringBuilder(plain.length)
            val current = StringBuilder()
            var prev = ' '
            for (c in plain) {
                if (!c.isLetterOrDigit()) {
                    if (current.isNotEmpty()) words += current.toString().also { current.clear() }
                    prev = c
                    continue
                }
                val boundary = !prev.isLetterOrDigit() ||
                        (prev.isLowerCase() && c.isUpperCase()) ||
                        (prev.isDigit() != c.isDigit())
                if (boundary && current.isNotEmpty()) words += current.toString().also { current.clear() }
                if (boundary) offsets += compact.length
                val lower = c.lowercaseChar()
                current.append(lower)
                compact.append(lower)
                prev = c
            }
            if (current.isNotEmpty()) words += current.toString()

            this.compact = compact.toString()
            this.words = words
            this.wordOffsets = offsets.toIntArray()
            this.initials = words.joinToString("") { it.take(1) }
            this.spaced = plain.lowercase(Locale.ROOT).map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            this.sortLabel = plain.lowercase(Locale.ROOT)
            this.packageSegments = packageName.lowercase(Locale.ROOT)
                .split('.', '_')
                .filter { it.length >= 2 && it !in PACKAGE_STOPWORDS }
        }
    }

    class Query(raw: String) {
        val compact: String
        val tokens: List<String>

        init {
            val lower = stripDiacritics(raw).lowercase(Locale.ROOT)
            tokens = lower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            compact = tokens.joinToString("")
        }
    }

    /**
     * Returns indices into [keys], best match first.
     *
     * @param fuzzyThreshold 0..1, minimum fuzzy score; null disables fuzzy matching.
     * @param fromStart only label prefix matches count ("search from start" setting).
     */
    fun rank(keys: List<SearchKey>, rawQuery: String, fuzzyThreshold: Float?, fromStart: Boolean = false): List<Int> {
        val query = Query(rawQuery)
        val q = query.compact
        if (q.isEmpty()) return keys.indices.toList()

        val size = keys.size
        val matched = IntArray(size)      // query words matched anywhere
        val tokenMasks = LongArray(size)  // which query words matched
        val labelMatched = IntArray(size) // query words matched in the label
        val tiers = IntArray(size) { -1 }
        val scores = FloatArray(size)
        var strong = 0
        for (i in 0 until size) {
            if (match(keys[i], query, fromStart, i, matched, labelMatched, tiers, tokenMasks)) strong++
        }

        // Weight partial matches by how rare each matched query word is
        val weights = FloatArray(size)
        val tokenCount = minOf(query.tokens.size, 64)
        if (tokenCount > 1) {
            val docFreq = IntArray(tokenCount)
            for (mask in tokenMasks) for (t in 0 until tokenCount) if (mask and (1L shl t) != 0L) docFreq[t]++
            for (i in 0 until size) {
                for (t in 0 until tokenCount) {
                    if (tokenMasks[i] and (1L shl t) != 0L) weights[i] += 1f / docFreq[t]
                }
            }
        }

        if (fuzzyThreshold != null && !fromStart && strong < MIN_STRONG_RESULTS && q.length >= 3) {
            for (i in 0 until size) {
                if (tiers[i] >= 0) continue
                val score = fuzzyScore(keys[i], q)
                if (score > 0f && score >= fuzzyThreshold) {
                    tiers[i] = TIER_FUZZY
                    scores[i] = score
                }
            }
        }

        return (0 until size)
            .filter { tiers[it] >= 0 }
            .sortedWith(
                compareByDescending<Int> { matched[it] }
                    .thenByDescending { labelMatched[it] }
                    .thenByDescending { weights[it] }
                    .thenBy { tiers[it] }
                    .thenByDescending { scores[it] }
                    .thenBy { keys[it].compact.length }
                    .thenBy { keys[it].sortLabel }
            )
    }

    /** Fills the match arrays for item [i]; returns whether it matched without fuzzy help. */
    private fun match(
        key: SearchKey, query: Query, fromStart: Boolean,
        i: Int, matched: IntArray, labelMatched: IntArray, tiers: IntArray, tokenMasks: LongArray,
    ): Boolean {
        val q = query.compact
        val tokenCount = query.tokens.size
        val label = key.compact

        // The whole query against the label
        val whole = when {
            label == q -> TIER_EXACT
            label.startsWith(q) -> TIER_PREFIX
            fromStart -> -1
            wordsStartWith(key.words, query.tokens, q) -> TIER_WORD_START
            tokenCount == 1 && q.length >= 2 && key.initials.startsWith(q) -> TIER_INITIALS
            tokenCount == 1 && label.contains(q) -> TIER_SUBSTRING
            else -> -1
        }
        if (whole >= 0) {
            matched[i] = tokenCount
            labelMatched[i] = tokenCount
            tiers[i] = whole
            tokenMasks[i] = -1L
            return true
        }
        if (fromStart) return false

        // Each query word on its own, against the label and then the package name
        var any = 0
        var inLabel = 0
        var mask = 0L
        query.tokens.forEachIndexed { t, token ->
            val bit = if (t < 64) 1L shl t else 0L
            if (labelHas(key, token)) {
                any++; inLabel++; mask = mask or bit
            } else if (token.length >= 3 && key.packageSegments.any { it.startsWith(token) }) {
                any++; mask = mask or bit
            }
        }
        if (any == 0) return false
        tokenMasks[i] = mask
        matched[i] = any
        labelMatched[i] = inLabel
        tiers[i] = if (inLabel > 0) TIER_SUBSTRING else TIER_PACKAGE
        return true
    }

    private fun labelHas(key: SearchKey, token: String): Boolean =
        key.words.any { it.startsWith(token) } || (token.length >= 3 && key.compact.contains(token))

    /** "tube" -> YouTube; "goo ma" -> Google Maps (each query word starts a later label word). */
    private fun wordsStartWith(words: List<String>, tokens: List<String>, compact: String): Boolean {
        if (tokens.size <= 1) return words.any { it.startsWith(compact) }
        var w = 0
        for (token in tokens) {
            while (w < words.size && !words[w].startsWith(token)) w++
            if (w == words.size) return false
            w++
        }
        return true
    }

    /**
     * Best of: up to [maxTypos] typos against the start of any word ("yotube" -> YouTube),
     * or the subsequence score from [FuzzyFinder.calculateFuzzyScore].
     */
    internal fun fuzzyScore(key: SearchKey, q: String): Float {
        // Short queries get no typo tolerance: "chat" is one letter away from "what"
        val maxTypos = when {
            q.length >= 8 -> 2
            q.length >= 5 -> 1
            else -> 0
        }
        var bestTypos = Int.MAX_VALUE
        for (start in key.wordOffsets) {
            for (len in q.length - 1..q.length + 1) {
                if (len <= 0 || start + len > key.compact.length) continue
                val d = osaDistance(q, key.compact, start, len, maxTypos)
                if (d < bestTypos) bestTypos = d
            }
        }
        val typoScore = if (maxTypos > 0 && bestTypos <= maxTypos) 1f - 0.1f * bestTypos else 0f
        val subsequenceScore = FuzzyFinder.calculateFuzzyScore(key.spaced, q)
        return maxOf(typoScore, subsequenceScore)
    }

    /** Optimal string alignment distance between [a] and b[start, start+len), capped at [cap] + 1. */
    private fun osaDistance(a: String, b: String, start: Int, len: Int, cap: Int): Int {
        val n = a.length
        val m = len
        if (kotlin.math.abs(n - m) > cap) return cap + 1
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[start + j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[start + j - 2] && a[i - 2] == b[start + j - 1]) {
                    v = minOf(v, prev2[j - 2] + 1)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > cap) return cap + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    private fun stripDiacritics(s: String): String =
        DIACRITICS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")
}

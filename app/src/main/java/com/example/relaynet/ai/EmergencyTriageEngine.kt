package com.example.relaynet.ai

data class TriageResult(
    val category: String,       // "SECURITY", "ACCIDENT", "MEDICAL", "FIRE", "DAMAGE", "TRAPPED", "CHAT"
    val urgencyLevel: String,   // "CRITICAL", "HIGH", "NORMAL"
    val badgeIcon: String,      // "🚨", "💥", "🚑", "🔥", "🏚️", "🧱", "🆘", "💬"
    val summary: String
)

/**
 * Intelligent Edge NLP Triage Engine running 100% locally on device (Zero Internet, Zero Cloud).
 * Combines Morphological Root-Stemming, Typo-Tolerant Fuzzy Levenshtein Distance Matching,
 * and Comprehensive Bilingual Emergency Lexicons (English + Roman Urdu).
 */
object EmergencyTriageEngine {

    // 1. Security, Crime, Kidnapping & Hostage Threats (CRITICAL)
    private val SECURITY_KEYWORDS = setOf(
        "kidnap", "kinap", "hostag", "abduct", "agva", "aghwa", "gun", "weapon", "shoot",
        "firing", "goli", "attack", "hamla", "terror", "dacoit", "robber", "chori", "dakoo",
        "threat", "danger", "khatra", "kill", "murder", "qatal", "ransom", "snatch", "bhatta",
        "extort", "assault", "bomb", "blast", "hostage"
    )

    // 2. Accidents, Crashes & Vehicular Collisions (CRITICAL)
    private val ACCIDENT_KEYWORDS = setOf(
        "accident", "accid", "crash", "collis", "hit", "takar", "takkar", "overturn",
        "pulat", "derail", "wreck", "run over", "truck", "vehicle", "highway", "gari"
    )

    // 3. Medical, Casualties & Bodily Harm (CRITICAL)
    private val MEDICAL_KEYWORDS = setOf(
        "blood", "injur", "wound", "bleed", "doctor", "zakhmi", "chot", "dard", "pain",
        "hurt", "unconscious", "behosh", "faint", "heart", "oxygen", "dawa", "ambulance",
        "casualty", "patient", "fracture", "cpr", "hospital", "sick", "ill", "marz",
        "poison", "zeher", "saans", "breath", "cardiac", "stroke"
    )

    // 4. Fire, Thermal Hazards & Explosions (CRITICAL)
    private val FIRE_KEYWORDS = setOf(
        "fire", "smoke", "aag", "dhuan", "flame", "jal", "burn", "blast",
        "dhamaka", "explosion", "spark", "cylinder", "pyro", "heat", "short circuit",
        "blaze", "gas leak"
    )

    // 5. Structural Damage, Earthquakes & Building Collapse (HIGH)
    private val DAMAGE_KEYWORDS = setOf(
        "damag", "damae", "destruct", "collaps", "earthquake", "zalzala", "quake",
        "landslid", "crack", "malba", "debris", "shatter", "tuta", "toot", "cave in",
        "sinkhole", "roof", "chath", "diwar", "wall", "building", "pillar"
    )

    // 6. Trapped, Entrapment & Flood/Water Rescue (HIGH)
    private val TRAPPED_KEYWORDS = setOf(
        "trap", "stuck", "phanse", "phansa", "phansi", "underground", "basement", "locked",
        "gir", "gira", "help", "madad", "bachao", "rescue", "drown", "doob", "flood",
        "sailab", "stranded", "water rising"
    )

    // Core target words for fuzzy Levenshtein distance check (tolerance for panic typos)
    private val FUZZY_ANCHORS = mapOf(
        "kidnap" to "SECURITY",
        "kidnapped" to "SECURITY",
        "accident" to "ACCIDENT",
        "crash" to "ACCIDENT",
        "collision" to "ACCIDENT",
        "damage" to "DAMAGE",
        "damaged" to "DAMAGE",
        "collapse" to "DAMAGE",
        "injury" to "MEDICAL",
        "injured" to "MEDICAL",
        "emergency" to "MEDICAL",
        "hospital" to "MEDICAL",
        "bleeding" to "MEDICAL"
    )

    fun classifyEmergency(text: String): TriageResult {
        // Bypass Walkie-Talkie Voice payload (base64 audio) to avoid false keyword triggering
        if (text.startsWith("🎤 VOICE")) {
            return TriageResult(
                category = "CHAT",
                urgencyLevel = "NORMAL",
                badgeIcon = "🎙️",
                summary = "Normal Mesh Message"
            )
        }

        val lower = text.lowercase()
        val tokens = lower.split(Regex("[\\s,;:.!?\\-_()\\[\\]{}]+")).filter { it.isNotEmpty() }

        // 1. Direct or Stem Matching across categories
        val hasSecurity = matchesCategory(lower, tokens, SECURITY_KEYWORDS)
        val hasAccident = matchesCategory(lower, tokens, ACCIDENT_KEYWORDS)
        val hasMedical = matchesCategory(lower, tokens, MEDICAL_KEYWORDS)
        val hasFire = matchesCategory(lower, tokens, FIRE_KEYWORDS)
        val hasDamage = matchesCategory(lower, tokens, DAMAGE_KEYWORDS)
        val hasTrapped = matchesCategory(lower, tokens, TRAPPED_KEYWORDS)

        // 2. Fuzzy Anchor Matching (typos like "kinapped", "damae", "acident")
        val fuzzyCategory = checkFuzzyAnchors(tokens)

        return when {
            hasSecurity || fuzzyCategory == "SECURITY" -> TriageResult(
                category = "SECURITY",
                urgencyLevel = "CRITICAL",
                badgeIcon = "🚨",
                summary = "AI DETECTED: Security / Kidnap / Threat Risk"
            )
            hasAccident || fuzzyCategory == "ACCIDENT" -> TriageResult(
                category = "ACCIDENT",
                urgencyLevel = "CRITICAL",
                badgeIcon = "💥",
                summary = "AI DETECTED: Accident / Collision Emergency"
            )
            hasMedical || fuzzyCategory == "MEDICAL" -> TriageResult(
                category = "MEDICAL",
                urgencyLevel = "CRITICAL",
                badgeIcon = "🚑",
                summary = "AI DETECTED: Medical / Casualty Risk"
            )
            hasFire -> TriageResult(
                category = "FIRE",
                urgencyLevel = "CRITICAL",
                badgeIcon = "🔥",
                summary = "AI DETECTED: Fire / Smoke Hazard"
            )
            hasDamage || fuzzyCategory == "DAMAGE" -> TriageResult(
                category = "DAMAGE",
                urgencyLevel = "CRITICAL",
                badgeIcon = "🏚️",
                summary = "AI DETECTED: Structural Damage / Hazard"
            )
            hasTrapped -> TriageResult(
                category = "TRAPPED",
                urgencyLevel = "HIGH",
                badgeIcon = "🧱",
                summary = "AI DETECTED: Structural Trap / Entrapment"
            )
            text.startsWith("🆘") -> TriageResult(
                category = "BROADCAST_SOS",
                urgencyLevel = "HIGH",
                badgeIcon = "🆘",
                summary = "EMERGENCY BROADCAST"
            )
            else -> TriageResult(
                category = "CHAT",
                urgencyLevel = "NORMAL",
                badgeIcon = "💬",
                summary = "Normal Mesh Message"
            )
        }
    }

    private fun matchesCategory(text: String, tokens: List<String>, keywords: Set<String>): Boolean {
        // Direct phrase or substring containment
        for (kw in keywords) {
            if (text.contains(kw)) return true
        }
        // Sub-word stem match on tokens
        for (token in tokens) {
            if (token.length < 3) continue
            for (kw in keywords) {
                if (token.startsWith(kw) || kw.startsWith(token)) return true
            }
        }
        return false
    }

    private fun checkFuzzyAnchors(tokens: List<String>): String? {
        for (token in tokens) {
            if (token.length < 4) continue
            for ((anchor, category) in FUZZY_ANCHORS) {
                val maxDistance = if (anchor.length >= 7) 2 else 1
                if (Math.abs(token.length - anchor.length) <= maxDistance) {
                    if (levenshteinDistance(token, anchor) <= maxDistance) {
                        return category
                    }
                }
            }
        }
        return null
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // deletion
                    dp[i][j - 1] + 1,       // insertion
                    dp[i - 1][j - 1] + cost  // substitution
                )
            }
        }
        return dp[m][n]
    }
}

package com.localsmsrelay

object OtpExtractor {
    private val keywordRegex = Regex(
        "验证码|校验码|动态码|一次性密码|OTP|verification\\s*code|security\\s*code|passcode",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val numberRegex = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    fun extract(text: String): String? {
        val keywords = keywordRegex.findAll(text).toList()
        if (keywords.isEmpty()) return null
        val candidates = numberRegex.findAll(text).toList()
        if (candidates.isEmpty()) return null

        return candidates.minWithOrNull(
            compareBy<MatchResult> { candidate ->
                keywords.minOf { keyword -> rangeDistance(candidate.range, keyword.range) }
            }.thenBy { candidate -> kotlin.math.abs(candidate.value.length - 6) }
                .thenBy { candidate -> candidate.range.first }
        )?.value
    }

    private fun rangeDistance(a: IntRange, b: IntRange): Int = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }
}


package com.localsmsrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEnvelopeTest {
    @Test
    fun parsesCompleteSmsFrame() {
        val frame = RelayEnvelope.parse(
            """{"type":"sms","messageId":"abc-123","sender":"95588","text":"您的验证码是123456","timestamp":"1726680000"}"""
        )
        assertTrue(frame is RelayFrame.Sms)
        val sms = frame as RelayFrame.Sms
        assertEquals("abc-123", sms.messageId)
        assertEquals("95588", sms.message.sender)
        assertEquals("您的验证码是123456", sms.message.text)
        assertEquals("1726680000", sms.message.timestamp)
        assertEquals("abc-123", sms.message.messageId)
    }

    @Test
    fun numericTimestampIsAcceptedAsString() {
        val frame = RelayEnvelope.parse(
            """{"type":"sms","messageId":"m1","text":"验证码123456","timestamp":1726680000}"""
        ) as RelayFrame.Sms
        assertEquals("1726680000", frame.message.timestamp)
    }

    /**
     * Android 的 org.json 对 JSON null 调 optString 会返回字符串 "null"，
     * 而不是空串。必须靠 isNull 先判断，否则发件人会显示成 "null"、
     * 兜底哈希也会把 "null" 当成真实时间戳参与计算。
     */
    @Test
    fun jsonNullFieldsDoNotLeakTheStringNull() {
        val frame = RelayEnvelope.parse(
            """{"type":"sms","messageId":"m1","sender":null,"text":"验证码123456","timestamp":null}"""
        ) as RelayFrame.Sms
        assertNull(frame.message.sender)
        assertNull(frame.message.timestamp)
        assertEquals("m1", frame.messageId)
    }

    @Test
    fun missingOptionalFieldsAreTreatedAsAbsent() {
        val frame = RelayEnvelope.parse("""{"type":"sms","text":"验证码123456"}""") as RelayFrame.Sms
        assertNull(frame.message.sender)
        assertNull(frame.message.timestamp)
        // 没有 messageId 时用内容哈希兜底
        assertTrue(frame.messageId.startsWith("h1-"))
    }

    @Test
    fun parsesAckFrame() {
        val frame = RelayEnvelope.parse("""{"type":"ack","messageId":"abc-123"}""")
        assertTrue(frame is RelayFrame.Ack)
        assertEquals("abc-123", (frame as RelayFrame.Ack).messageId)
    }

    @Test
    fun ignoresUnknownMalformedAndOversizedFrames() {
        assertEquals(RelayFrame.Ignored, RelayEnvelope.parse("not json"))
        assertEquals(RelayFrame.Ignored, RelayEnvelope.parse("""{"type":"other","text":"hi"}"""))
        assertEquals(RelayFrame.Ignored, RelayEnvelope.parse("""{"type":"sms","text":""}"""))
        assertEquals(RelayFrame.Ignored, RelayEnvelope.parse("""{"type":"sms","text":"   "}"""))
        assertEquals(RelayFrame.Ignored, RelayEnvelope.parse("""{"type":"sms"}"""))
        assertEquals(RelayFrame.Ignored, RelayEnvelope.parse("""{"type":"ack"}"""))
        assertEquals(
            RelayFrame.Ignored,
            RelayEnvelope.parse("""{"type":"sms","text":"${"a".repeat(RelayEnvelope.MAX_TEXT_CHARS + 1)}"}""")
        )
    }

    @Test
    fun textAtExactLimitIsAccepted() {
        val text = "a".repeat(RelayEnvelope.MAX_TEXT_CHARS)
        assertTrue(RelayEnvelope.parse("""{"type":"sms","messageId":"m","text":"$text"}""") is RelayFrame.Sms)
    }

    @Test
    fun overlongMessageIdIsTreatedAsAbsentRatherThanTruncated() {
        val tooLong = "x".repeat(RelayEnvelope.MAX_MESSAGE_ID_CHARS + 1)
        val frame = RelayEnvelope.parse("""{"type":"sms","messageId":"$tooLong","text":"验证码123456"}""")
            as RelayFrame.Sms
        // 截断会破坏与其它设备的一致性，所以整条消息改用兜底哈希
        assertTrue(frame.messageId.startsWith("h1-"))
        assertNotEquals(tooLong, frame.messageId)
    }

    @Test
    fun overlongSenderIsTreatedAsAbsent() {
        val tooLong = "s".repeat(RelayEnvelope.MAX_METADATA_CHARS + 1)
        val frame = RelayEnvelope.parse(
            """{"type":"sms","messageId":"m","sender":"$tooLong","text":"验证码123456"}"""
        ) as RelayFrame.Sms
        assertNull(frame.message.sender)
    }

    /**
     * 跨语言一致性：这些期望值是用服务端 index.js 的同一算法在 Node 中算出来的。
     * 两边只要有一处改动（分隔符、字段顺序、时间戳归一化），这个测试立刻失败。
     */
    @Test
    fun fallbackHashMatchesServerImplementation() {
        assertEquals(
            "h1-b01a2d7ef68ad0ecbb342e1bbfd1068f2571d7b9f88e013d021005f97e676fef",
            RelayEnvelope.fallbackMessageId("95588", "验证码123456", "1726680000")
        )
        assertEquals(
            "h1-f3ceed60489401e74366d8d7bb7b26307255b7f0655145fb9413bc6b2bed6c36",
            RelayEnvelope.fallbackMessageId("95588", "您的验证码是123456", "1726680000")
        )
        assertEquals(
            "h1-c24cf8878ed55524a50894d5f87b82155c1105d24e033af086c009ae19291b11",
            RelayEnvelope.fallbackMessageId(null, "验证码123456", "1726680000")
        )
        assertEquals(
            "h1-c21ad6bb587e01970ff05f9caf138a74a340e7c4345f0e124308432c6f8e7905",
            RelayEnvelope.fallbackMessageId("95588", "验证码123456", null)
        )
    }

    @Test
    fun fallbackHashIsDeterministicAndFieldSensitive() {
        val base = RelayEnvelope.fallbackMessageId("95588", "验证码123456", "1726680000")
        assertEquals(base, RelayEnvelope.fallbackMessageId("95588", "验证码123456", "1726680000"))
        assertNotEquals(base, RelayEnvelope.fallbackMessageId("10086", "验证码123456", "1726680000"))
        assertNotEquals(base, RelayEnvelope.fallbackMessageId("95588", "验证码654321", "1726680000"))
        assertNotEquals(base, RelayEnvelope.fallbackMessageId("95588", "验证码123456", "1726680001"))
    }

    /** 分隔符必须真的起作用，否则 ("ab","c") 和 ("a","bc") 会撞成同一个 ID。 */
    @Test
    fun fallbackHashSeparatesFieldsWithNul() {
        assertNotEquals(
            RelayEnvelope.fallbackMessageId("ab", "c", "1"),
            RelayEnvelope.fallbackMessageId("a", "bc", "1")
        )
        assertNotEquals(
            RelayEnvelope.fallbackMessageId("", "ab", "1"),
            RelayEnvelope.fallbackMessageId("a", "b", "1")
        )
    }

    @Test
    fun fallbackHashHasExpectedShape() {
        val id = RelayEnvelope.fallbackMessageId("95588", "验证码123456", "1726680000")
        assertEquals("h1-".length + 64, id.length)
        assertTrue(id.removePrefix("h1-").all { it in "0123456789abcdef" })
    }

    /** 中文与 emoji 都必须按 UTF-8 参与哈希，否则跨端结果会不一致。 */
    @Test
    fun fallbackHashHandlesNonAsciiConsistently() {
        val chinese = RelayEnvelope.fallbackMessageId("95588", "验证码123456", "1")
        val emoji = RelayEnvelope.fallbackMessageId("95588", "验证码123456🔐", "1")
        assertNotEquals(chinese, emoji)
        assertEquals(chinese, RelayEnvelope.fallbackMessageId("95588", "验证码123456", "1"))
    }
}

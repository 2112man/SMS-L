package com.localsmsrelay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class Utf8ChineseSmsTest {
    private val cases = listOf(
        "测试验证码12345" to "12345",
        "【测试银行】您的验证码为583921，5分钟内有效" to "583921",
        "【招商银行】您的验证码为583921，5分钟内有效" to "583921",
        "您正在登录，验证码：952761，请勿泄露" to "952761"
    )

    @Test
    fun decodesCompleteJsonBodyAsUtf8AndPreservesNotificationText() {
        for ((expectedText, expectedOtp) in cases) {
            val json = JSONObject()
                .put("text", expectedText)
                .put("token", "test-token-not-logged")
                .toString()
            val requestBytes = json.toByteArray(Charsets.UTF_8)

            val decodedJson = Utf8JsonBodyReader.read(
                ByteArrayInputStream(requestBytes),
                requestBytes.size.toLong(),
                16 * 1024
            )
            val parsedText = JSONObject(decodedJson).getString("text")
            val otp = OtpExtractor.extract(parsedText)
            val notification = NotificationContentFormatter.format(null, parsedText, otp)

            assertEquals(expectedText, parsedText)
            assertEquals(expectedOtp, otp)
            assertEquals(expectedText, notification.text)
            assertEquals("iPhone 验证码 · $expectedOtp", notification.title)
            assertFalse(notification.text.contains('?'))
            assertFalse(notification.text.contains('\uFFFD'))
        }
    }

    @Test
    fun acceptsJsonContentTypeWithOrWithoutUtf8Parameter() {
        assertTrue(Utf8JsonBodyReader.isApplicationJson("application/json"))
        assertTrue(Utf8JsonBodyReader.isApplicationJson("application/json; charset=utf-8"))
        assertTrue(Utf8JsonBodyReader.isApplicationJson("Application/Json ; Charset=UTF-8"))
        assertFalse(Utf8JsonBodyReader.isApplicationJson("application/x-www-form-urlencoded"))
    }

    @Test(expected = Utf8JsonBodyReader.BodyReadException::class)
    fun rejectsMalformedUtf8InsteadOfProducingReplacementCharacters() {
        val invalidUtf8 = byteArrayOf(0x7B, 0x22, 0xC3.toByte(), 0x28, 0x22, 0x7D)
        Utf8JsonBodyReader.read(
            ByteArrayInputStream(invalidUtf8),
            invalidUtf8.size.toLong(),
            16 * 1024
        )
    }
}

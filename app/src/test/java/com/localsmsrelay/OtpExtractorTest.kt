package com.localsmsrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpExtractorTest {
    @Test
    fun choosesCodeNearestKeywordInsteadOfFirstNumber() {
        assertEquals("583921", OtpExtractor.extract("订单 20260823，【XX银行】验证码583921，请在5分钟内输入。"))
    }

    @Test
    fun supportsEnglishOtpKeyword() {
        assertEquals("7462", OtpExtractor.extract("Your verification code is 7462. Ref 12345678."))
    }

    @Test
    fun doesNotGuessWithoutKeyword() {
        assertNull(OtpExtractor.extract("订单号 123456，金额 8888 元"))
    }

    @Test
    fun acceptsFourToEightDigitsOnly() {
        assertNull(OtpExtractor.extract("验证码 123，10 分钟内有效"))
        assertEquals("12345678", OtpExtractor.extract("OTP: 12345678"))
    }
}


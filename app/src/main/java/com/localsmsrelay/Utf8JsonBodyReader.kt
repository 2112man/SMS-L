package com.localsmsrelay

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

object Utf8JsonBodyReader {
    fun isApplicationJson(contentType: String): Boolean =
        contentType.substringBefore(';').trim().equals("application/json", ignoreCase = true)

    @Throws(BodyReadException::class)
    fun read(inputStream: InputStream, contentLength: Long?, maxBytes: Int): String {
        if (contentLength == null || contentLength <= 0L) {
            throw BodyReadException("missing_body")
        }
        if (contentLength > maxBytes || contentLength > Int.MAX_VALUE) {
            throw BodyReadException("body_too_large")
        }

        val bytes = ByteArray(contentLength.toInt())
        var offset = 0
        try {
            while (offset < bytes.size) {
                val count = inputStream.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw BodyReadException("invalid_body")
                if (count == 0) continue
                offset += count
            }
        } catch (error: BodyReadException) {
            throw error
        } catch (_: IOException) {
            throw BodyReadException("invalid_body")
        }

        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw BodyReadException("invalid_utf8")
        }
    }

    class BodyReadException(val responseError: String) : IOException(responseError)
}


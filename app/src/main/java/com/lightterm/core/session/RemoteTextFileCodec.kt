package com.lightterm.core.session

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal fun readLimitedBytes(
    source: InputStream,
    maxBytes: Int,
    tooLargeMessage: String,
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var totalBytes = 0
    while (true) {
        val read = source.read(buffer)
        if (read < 0) {
            return output.toByteArray()
        }
        if (read == 0) {
            continue
        }
        totalBytes += read
        if (totalBytes > maxBytes) {
            throw IllegalStateException(tooLargeMessage)
        }
        output.write(buffer, 0, read)
    }
}

internal fun decodeEditableUtf8Text(
    bytes: ByteArray,
    binaryMessage: String,
): String {
    if (bytes.any { it == 0.toByte() }) {
        throw IllegalStateException(binaryMessage)
    }

    val text = try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Throwable) {
        throw IllegalStateException(binaryMessage)
    }

    val hasUnsupportedControlChars = text.any { char ->
        char < ' ' && char != '\n' && char != '\r' && char != '\t'
    }
    if (hasUnsupportedControlChars) {
        throw IllegalStateException(binaryMessage)
    }
    return text
}

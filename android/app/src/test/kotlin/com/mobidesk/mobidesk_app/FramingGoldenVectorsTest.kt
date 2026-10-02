package com.mobidesk.mobidesk_app

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Verifies Kotlin FramingProtocol and FramingDemuxer against the shared
 * golden test vectors defined in tests/golden/frames.json.
 */
class FramingGoldenVectorsTest {

    private fun findWorkspaceFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath"),
            File(System.getProperty("user.dir"), relativePath),
            File(System.getProperty("user.dir"), "../$relativePath")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Cannot find file '$relativePath' in candidates: $candidates")
    }

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.isEmpty()) return ByteArray(0)
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun testKotlinReadsAndVerifiesGoldenVectorsJson() {
        val goldenFile = findWorkspaceFile("tests/golden/frames.json")
        val jsonText = goldenFile.readText()
        val root = JSONObject(jsonText)

        assertEquals("BIG_ENDIAN", root.getString("endianness"))
        assertEquals("4d42", root.getString("magic_hex"))
        assertEquals(16, root.getInt("header_size_bytes"))

        val vectors = root.getJSONArray("vectors")
        assertEquals(7, vectors.length())

        for (i in 0 until vectors.length()) {
            val vec = vectors.getJSONObject(i)
            val name = vec.getString("name")
            val type = vec.getInt("type").toByte()
            val flags = vec.getInt("flags").toByte()
            val payloadLength = vec.getInt("payload_length")
            val ptsUs = vec.getLong("pts_us")
            val payloadHex = vec.getString("payload_hex")
            val expectedPacketHex = vec.getString("packet_hex")

            val expectedPayload = hexToBytes(payloadHex)
            assertEquals("$name: payload length check", payloadLength, expectedPayload.size)

            // 1. Verify packet encoding
            val out = ByteArrayOutputStream()
            when (name) {
                "DISPLAY_INFO" -> {
                    val fields = vec.getJSONObject("fields")
                    val w = fields.getInt("width")
                    val h = fields.getInt("height")
                    val fps = fields.getInt("fps")
                    FramingProtocol.writeDisplayInfo(out, w, h, fps)
                }
                "INPUT_MOUSE" -> {
                    val fields = vec.getJSONObject("fields")
                    FramingProtocol.writeInputMouse(
                        out,
                        fields.getInt("norm_x"),
                        fields.getInt("norm_y"),
                        fields.getInt("button_mask"),
                        fields.getInt("wheel_dx"),
                        fields.getInt("wheel_dy"),
                        ptsUs
                    )
                }
                "INPUT_KEY" -> {
                    val fields = vec.getJSONObject("fields")
                    FramingProtocol.writeInputKey(
                        out,
                        fields.getInt("key_code"),
                        fields.getInt("state"),
                        fields.getInt("modifier_mask"),
                        ptsUs
                    )
                }
                else -> {
                    FramingProtocol.writeFrame(
                        out,
                        type,
                        flags,
                        expectedPayload,
                        0,
                        expectedPayload.size,
                        ptsUs
                    )
                }
            }

            val encodedPacket = out.toByteArray()
            assertEquals("$name: Packet hex mismatch", expectedPacketHex, bytesToHex(encodedPacket))

            // 2. Verify packet demuxing
            var demuxedType: Byte? = null
            var demuxedFlags: Byte? = null
            var demuxedPtsUs: Long? = null
            var demuxedPayload: ByteArray? = null

            val demuxer = FramingDemuxer { rType, rFlags, rPtsUs, rPayload ->
                demuxedType = rType
                demuxedFlags = rFlags
                demuxedPtsUs = rPtsUs
                demuxedPayload = rPayload
            }

            val packetBytes = hexToBytes(expectedPacketHex)
            demuxer.feedData(packetBytes, 0, packetBytes.size)

            assertNotNull("$name: Demuxer did not produce frame", demuxedType)
            assertEquals("$name: Demuxed type mismatch", type, demuxedType)
            assertEquals("$name: Demuxed flags mismatch", flags, demuxedFlags)
            assertEquals("$name: Demuxed ptsUs mismatch", ptsUs, demuxedPtsUs)
            assertArrayEquals("$name: Demuxed payload mismatch", expectedPayload, demuxedPayload)
        }
    }
}

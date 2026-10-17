package org.iphoto.mobile.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class FileHasherTest {

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `computeHashesFromBytes returns identical realMd5 and redactedMd5 when asset has no GPS metadata`() {
        val plainJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), // SOI
            0xFF.toByte(), 0xFE.toByte(), 0x00, 0x06, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
            0xFF.toByte(), 0xD9.toByte()  // EOI
        )

        val hashes = FileHasher.computeHashesFromBytes(plainJpeg, "image/jpeg")

        assertEquals(md5Hex(plainJpeg), hashes.realMd5)
        assertEquals(hashes.realMd5, hashes.redactedMd5)
        assertEquals(listOf(hashes.realMd5), hashes.allDistinct())
    }

    @Test
    fun `computeHashesFromBytes zeroes out EXIF GPS IFD tags to produce without-localization redactedMd5`() {
        // Construct a valid JPEG with APP1 Exif header containing IFD0 -> GPS IFD (0x8825)
        // with inline GPSLatitudeRef (0x0001, ASCII "N\0") and out-of-line GPSLatitude (0x0002, 3 RATIONALs = 24 bytes).
        val tiffBuf = ByteBuffer.allocate(128).order(ByteOrder.BIG_ENDIAN)
        // TIFF Header (8 bytes): "MM", magic 42, IFD0 offset = 8
        tiffBuf.put('M'.code.toByte()).put('M'.code.toByte())
        tiffBuf.putShort(42)
        tiffBuf.putInt(8)

        // IFD0 at offset 8: 1 entry (0x8825 GPS IFD pointer -> offset 26), nextIFD = 0
        tiffBuf.position(8)
        tiffBuf.putShort(1)
        tiffBuf.putShort(0x8825.toShort()) // GPSInfoIFDPointer
        tiffBuf.putShort(4) // ULONG
        tiffBuf.putInt(1)
        tiffBuf.putInt(26) // GPS IFD at TIFF offset 26
        tiffBuf.putInt(0)  // nextIFD = 0

        // GPS IFD at offset 26: 2 entries (2 + 24 + 4 = 30 bytes, ends at 56)
        tiffBuf.position(26)
        tiffBuf.putShort(2)
        // Entry 0 (at TIFF offset 28, value at 28+8=36): tag 0x0001 (GPSLatitudeRef), ASCII(2), count 2, inline "N\0"
        tiffBuf.putShort(0x0001)
        tiffBuf.putShort(2)
        tiffBuf.putInt(2)
        tiffBuf.put('N'.code.toByte()).put(0).put(0).put(0)
        // Entry 1 (at TIFF offset 40): tag 0x0002 (GPSLatitude), URATIONAL(5), count 3 (24 bytes) -> offset 60
        tiffBuf.putShort(0x0002)
        tiffBuf.putShort(5)
        tiffBuf.putInt(3)
        tiffBuf.putInt(60)
        tiffBuf.putInt(0) // next GPS IFD = 0

        // GPSLatitude payload at TIFF offset 60..84 (24 non-zero bytes)
        tiffBuf.position(60)
        for (i in 0 until 24) {
            tiffBuf.put((i + 1).toByte())
        }
        val tiffBytes = tiffBuf.array()

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte())) // APP1
        val segLen = 2 + 6 + tiffBytes.size
        out.write(byteArrayOf(((segLen shr 8) and 0xFF).toByte(), (segLen and 0xFF).toByte()))
        out.write("Exif\u0000\u0000".toByteArray(StandardCharsets.US_ASCII))
        out.write(tiffBytes)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte())) // EOI
        val jpegWithGps = out.toByteArray()

        // Expected redacted bytes: TIFF offset starts at 2 (SOI) + 4 (APP1 hdr) + 6 ("Exif\0\0") = 12
        // Inline GPSLatitudeRef is at 12 + 36 = 48..50 (2 bytes)
        // Out-of-line GPSLatitude is at 12 + 60 = 72..96 (24 bytes)
        val expectedRedacted = jpegWithGps.copyOf()
        expectedRedacted.fill(0, 48, 50)
        expectedRedacted.fill(0, 72, 96)

        val hashes = FileHasher.computeHashesFromBytes(jpegWithGps, "image/jpeg")

        assertEquals(md5Hex(jpegWithGps), hashes.realMd5)
        assertEquals(md5Hex(expectedRedacted), hashes.redactedMd5)
        assertNotEquals(hashes.realMd5, hashes.redactedMd5)
        assertEquals(listOf(hashes.realMd5, hashes.redactedMd5), hashes.allDistinct())
    }

    @Test
    fun `computeHashesFromBytes zeroes out ISO MP4 ©xyz location box matching AOSP RedactionUtils`() {
        // Build a minimal MP4 with ftyp box + moov -> udta -> ©xyz box
        val xyzPayload = "+48.8566+002.3522/".toByteArray(StandardCharsets.UTF_8)
        val xyzBoxLen = 8 + xyzPayload.size
        val udtaBoxLen = 8 + xyzBoxLen
        val moovBoxLen = 8 + udtaBoxLen
        val ftypBoxLen = 16

        val buf = ByteBuffer.allocate(ftypBoxLen + moovBoxLen).order(ByteOrder.BIG_ENDIAN)
        // ftyp box (0..16)
        buf.putInt(ftypBoxLen)
        buf.putInt(0x66747970) // 'ftyp'
        buf.putInt(0x69736F6D) // 'isom'
        buf.putInt(0x00000200)
        // moov box (16..16+moovBoxLen)
        buf.putInt(moovBoxLen)
        buf.putInt(0x6d6f6f76) // 'moov'
        // udta box (24..24+udtaBoxLen)
        buf.putInt(udtaBoxLen)
        buf.putInt(0x75647461) // 'udta'
        // ©xyz box (32..32+xyzBoxLen): size at 32..36, type at 36..40, payload at 40..40+xyzPayload.size
        buf.putInt(xyzBoxLen)
        buf.putInt(0xa978797a.toInt()) // '©xyz'
        buf.put(xyzPayload)

        val mp4Bytes = buf.array()
        // AOSP RedactionUtils zeroes from boxTypeOffset (contentStart - 4 = 36) to contentEnd (32 + xyzBoxLen)
        val expectedRedacted = mp4Bytes.copyOf()
        expectedRedacted.fill(0, 36, 32 + xyzBoxLen)

        val hashes = FileHasher.computeHashesFromBytes(mp4Bytes, "video/mp4")

        assertEquals(md5Hex(mp4Bytes), hashes.realMd5)
        assertEquals(md5Hex(expectedRedacted), hashes.redactedMd5)
        assertNotEquals(hashes.realMd5, hashes.redactedMd5)
    }
}

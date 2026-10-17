package org.iphoto.mobile.sync

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Holds both the unredacted (real) MD5 and the location-redacted MD5 (without GPS metadata,
 * matching what Android's MediaProvider produces when ACCESS_MEDIA_LOCATION is not granted).
 * When an asset has no location metadata to redact, [redactedMd5] equals [realMd5].
 */
data class AssetHashes(
    val realMd5: String,
    val redactedMd5: String? = null
) {
    /**
     * Returns the distinct non-blank MD5 hashes to check against the remote server
     * ([realMd5] first, followed by [redactedMd5] if present and different from [realMd5]).
     */
    fun allDistinct(): List<String> =
        if (redactedMd5.isNullOrBlank() || redactedMd5 == realMd5) {
            listOf(realMd5)
        } else {
            listOf(realMd5, redactedMd5)
        }
}

/**
 * Content-hashes a local file the same way the backend does (PhotoUploadServiceImpl /
 * PhotoServiceImpl / EditPhotoServiceImpl all use HexFormat.of().formatHex(MessageDigest
 * .getInstance("MD5").digest(bytes))) - matching algorithms is what lets the sync diff compare a
 * local file directly against a Space photo's `fileHash` without ever uploading it first.
 *
 * Also computes the location-redacted MD5 (without GPS metadata) in the same single streaming pass
 * by zeroing out (`0x00`) the exact byte ranges that Android's `MediaProvider` (`RedactionUtils`,
 * `IsoInterface`, `XmpDataParser`) zeroes out when `ACCESS_MEDIA_LOCATION` is not granted. This
 * allows `check-hashes` to recognize assets that were uploaded before `ACCESS_MEDIA_LOCATION` was
 * enabled.
 */
object FileHasher {
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_XMP_SIZE_BYTES = 1024 * 1024

    /**
     * Exact set of 31 EXIF GPS tags redacted by AOSP `com.android.providers.media.util.RedactionUtils`.
     */
    internal val REDACTED_EXIF_TAGS = arrayOf(
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_VERSION_ID
    )

    private val REDACTED_EXIF_TAG_SET = REDACTED_EXIF_TAGS.toSet()

    private const val BOX_ILST = 0x696c7374
    private const val BOX_FTYP = 0x66747970
    private const val BOX_HDLR = 0x68646c72
    private const val BOX_UUID = 0x75756964
    private const val BOX_META = 0x6d657461
    private const val BOX_XMP = 0x584d505f
    private const val BOX_LOCI = 0x6c6f6369
    private const val BOX_XYZ = 0xa978797a.toInt()
    private const val BOX_GPS = 0x67707320
    private const val BOX_GPS0 = 0x67707330

    private val REDACTED_ISO_BOXES = intArrayOf(BOX_LOCI, BOX_XYZ, BOX_GPS, BOX_GPS0)
    private val XMP_UUID = UUID.fromString("be7acfcb-97a9-42e8-9c71-999491e3afac")

    // TIFF data format component sizes (formats 1..12)
    private val TIFF_FORMAT_BYTES = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8)

    fun resolveUnredactedUri(context: Context, uri: Uri, mediaStoreUri: Uri? = null): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val targetMediaUri = mediaStoreUri ?: try {
                    MediaStore.getMediaUri(context, uri)
                } catch (_: Throwable) {
                    null
                }
                if (targetMediaUri != null) {
                    return MediaStore.setRequireOriginal(targetMediaUri)
                }
            } catch (_: Throwable) {
                // Fall back to the document URI directly
            }
        }
        return uri
    }

    fun openUnredactedInputStream(context: Context, uri: Uri, mediaStoreUri: Uri? = null): InputStream? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val targetUri = resolveUnredactedUri(context, uri, mediaStoreUri)
                if (targetUri != uri) {
                    context.contentResolver.openInputStream(targetUri)?.let { return it }
                }
            } catch (_: Throwable) {
                // Fall back to opening the document URI directly if ACCESS_MEDIA_LOCATION is not granted
            }
        }
        return context.contentResolver.openInputStream(uri)
    }

    private fun openUnredactedFileDescriptor(
        context: Context,
        uri: Uri,
        mediaStoreUri: Uri? = null
    ): ParcelFileDescriptor? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val targetUri = resolveUnredactedUri(context, uri, mediaStoreUri)
                if (targetUri != uri) {
                    context.contentResolver.openFileDescriptor(targetUri, "r")?.let { return it }
                }
            } catch (_: Throwable) {
                // Fall back to opening the document URI directly
            }
        }
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Throwable) {
            null
        }
    }

    fun md5(context: Context, uri: Uri, mediaStoreUri: Uri? = null): String =
        computeHashes(context, uri, mediaStoreUri).realMd5

    /**
     * Computes both the unredacted [AssetHashes.realMd5] and the location-redacted
     * [AssetHashes.redactedMd5] (without GPS metadata) in a single streaming pass over the file.
     */
    fun computeHashes(
        context: Context,
        uri: Uri,
        mediaStoreUri: Uri? = null,
        mimeType: String? = null
    ): AssetHashes {
        val resolvedMime = mimeType ?: try {
            context.contentResolver.getType(uri)
        } catch (_: Throwable) {
            null
        }

        val redactionRanges = try {
            openUnredactedFileDescriptor(context, uri, mediaStoreUri)?.use { pfd ->
                extractRedactionRanges(pfd, resolvedMime)
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }

        val stream = openUnredactedInputStream(context, uri, mediaStoreUri)
            ?: throw IllegalStateException("Cannot open $uri")
        return stream.use { input ->
            computeHashesFromStream(input, redactionRanges)
        }
    }

    /**
     * Computes [AssetHashes] directly from an in-memory [bytes] array (detecting EXIF GPS tags,
     * ISO MP4 location boxes, and XMP GPS tags, plus any [extraRedactionRanges]).
     */
    fun computeHashesFromBytes(
        bytes: ByteArray,
        mimeType: String? = null,
        extraRedactionRanges: List<LongRange> = emptyList()
    ): AssetHashes {
        val detectedRanges = extractRedactionRangesFromChannel(
            ByteArraySeekableByteChannel(bytes),
            mimeType
        )
        val merged = mergeRedactionRanges(detectedRanges + extraRedactionRanges)
        return computeHashesFromStream(bytes.inputStream(), merged)
    }

    /**
     * Streams [input] once and computes both `realMd5` and `redactedMd5` (with byte offsets in
     * [redactionRanges] zeroed out as `0x00`, where each [LongRange] is `[start, endInclusive]`).
     */
    internal fun computeHashesFromStream(
        input: InputStream,
        redactionRanges: List<LongRange>
    ): AssetHashes {
        val mergedRanges = mergeRedactionRanges(redactionRanges)
        val realDigest = MessageDigest.getInstance("MD5")
        val redactedDigest = if (mergedRanges.isNotEmpty()) MessageDigest.getInstance("MD5") else null
        val buffer = ByteArray(BUFFER_SIZE)
        val redactedBuffer = if (mergedRanges.isNotEmpty()) ByteArray(BUFFER_SIZE) else null

        var streamOffset = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            realDigest.update(buffer, 0, read)

            if (redactedDigest != null && redactedBuffer != null) {
                val chunkStart = streamOffset
                val chunkEndExclusive = streamOffset + read
                val hasOverlap = mergedRanges.any { range ->
                    range.first < chunkEndExclusive && (range.last + 1) > chunkStart
                }
                if (!hasOverlap) {
                    redactedDigest.update(buffer, 0, read)
                } else {
                    System.arraycopy(buffer, 0, redactedBuffer, 0, read)
                    for (range in mergedRanges) {
                        val rangeStart = range.first
                        val rangeEndExclusive = range.last + 1
                        if (rangeEndExclusive <= chunkStart) continue
                        if (rangeStart >= chunkEndExclusive) break
                        val localStart = maxOf(0L, rangeStart - chunkStart).toInt()
                        val localEnd = minOf(read.toLong(), rangeEndExclusive - chunkStart).toInt()
                        if (localStart < localEnd) {
                            redactedBuffer.fill(0, localStart, localEnd)
                        }
                    }
                    redactedDigest.update(redactedBuffer, 0, read)
                }
            }
            streamOffset += read
        }

        val realHex = realDigest.digest().toHex()
        val redactedHex = redactedDigest?.digest()?.toHex() ?: realHex
        return AssetHashes(realMd5 = realHex, redactedMd5 = redactedHex)
    }

    private fun extractRedactionRanges(
        pfd: ParcelFileDescriptor,
        mimeType: String?
    ): List<LongRange> {
        val ranges = mutableListOf<LongRange>()
        val fd = pfd.fileDescriptor

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (mimeType == null || ExifInterface.isSupportedMimeType(mimeType)) {
                    val exif = ExifInterface(fd)
                    for (tag in REDACTED_EXIF_TAGS) {
                        val range = exif.getAttributeRange(tag)
                        if (range != null && range.size >= 2 && range[0] >= 0L && range[1] > 0L) {
                            ranges.add(range[0] until (range[0] + range[1]))
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        exif.hasAttribute(ExifInterface.TAG_XMP)
                    ) {
                        val rawXmp = exif.getAttributeBytes(ExifInterface.TAG_XMP)
                        val xmpRange = exif.getAttributeRange(ExifInterface.TAG_XMP)
                        if (rawXmp != null && xmpRange != null && xmpRange.isNotEmpty() && xmpRange[0] >= 0L) {
                            ranges.addAll(extractXmpRedactionRanges(rawXmp, xmpRange[0]))
                        }
                    }
                }
            } catch (_: Throwable) {
                // Fall back to channel-based parsing below
            }
        }

        try {
            val channel = FileInputStream(fd).channel
            ranges.addAll(
                extractRedactionRangesFromChannel(
                    channel = channel,
                    mimeType = mimeType,
                    skipExifIfAlreadyFound = ranges.isNotEmpty()
                )
            )
        } catch (_: Throwable) {
            // Ignore channel errors
        }

        return mergeRedactionRanges(ranges)
    }

    internal fun extractRedactionRangesFromChannel(
        channel: SeekableByteChannel,
        mimeType: String? = null,
        skipExifIfAlreadyFound: Boolean = false
    ): List<LongRange> {
        val ranges = mutableListOf<LongRange>()
        val normalizedMime = mimeType?.lowercase(Locale.ROOT)

        if (!skipExifIfAlreadyFound && (normalizedMime == null || !normalizedMime.startsWith("video/"))) {
            try {
                channel.position(0L)
                ranges.addAll(extractExifAndXmpRangesFromChannel(channel))
            } catch (_: Throwable) {
                // Ignore malformed EXIF
            }
        }

        if (normalizedMime == null || isSupportedIsoMimeType(normalizedMime)) {
            try {
                channel.position(0L)
                ranges.addAll(extractIsoRedactionRanges(channel))
            } catch (_: Throwable) {
                // Ignore malformed ISO boxes
            }
        }

        return mergeRedactionRanges(ranges)
    }

    internal fun mergeRedactionRanges(ranges: List<LongRange>): List<LongRange> {
        val valid = ranges.filter { !it.isEmpty() && it.first >= 0L }
            .sortedWith(compareBy<LongRange> { it.first }.thenBy { it.last })
        if (valid.isEmpty()) return emptyList()

        val merged = mutableListOf<LongRange>()
        var current = valid[0]
        for (i in 1 until valid.size) {
            val next = valid[i]
            if (next.first <= current.last + 1) {
                current = current.first..maxOf(current.last, next.last)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    // --- Pure-Kotlin JPEG / TIFF EXIF GPS IFD & XMP range parser ---

    private fun extractExifAndXmpRangesFromChannel(channel: SeekableByteChannel): List<LongRange> {
        val size = channel.size()
        if (size < 4L) return emptyList()
        val head = readBytesAt(channel, 0L, minOf(size, 12L).toInt())
        if (head.size < 4) return emptyList()

        // 1. Direct TIFF header (II*\0 or MM\0*)
        if ((head[0] == 0x49.toByte() && head[1] == 0x49.toByte() && head[2] == 0x2A.toByte() && head[3] == 0x00.toByte()) ||
            (head[0] == 0x4D.toByte() && head[1] == 0x4D.toByte() && head[2] == 0x00.toByte() && head[3] == 0x2A.toByte())
        ) {
            return parseTiffGpsRanges(channel, 0L, size)
        }

        // 2. JPEG container (0xFF 0xD8)
        if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) {
            val ranges = mutableListOf<LongRange>()
            var pos = 2L
            while (pos + 4L <= size) {
                val markerPrefix = readBytesAt(channel, pos, 2)
                if (markerPrefix.size < 2 || markerPrefix[0] != 0xFF.toByte()) break
                var marker = markerPrefix[1].toInt() and 0xFF
                pos += 2L
                while (marker == 0xFF && pos < size) {
                    val b = readBytesAt(channel, pos, 1)
                    if (b.isEmpty()) break
                    marker = b[0].toInt() and 0xFF
                    pos += 1L
                }
                if (marker == 0xD9 || marker == 0xDA) break // EOI or SOS
                if (marker in 0xD0..0xD8 || marker == 0x01) continue

                if (pos + 2L > size) break
                val lenBytes = readBytesAt(channel, pos, 2)
                if (lenBytes.size < 2) break
                val segLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
                if (segLen < 2 || pos + segLen > size) break

                val payloadStart = pos + 2L
                val payloadLen = segLen - 2
                if (marker == 0xE1 && payloadLen >= 6) {
                    val sig = readBytesAt(channel, payloadStart, minOf(payloadLen, 29))
                    if (sig.size >= 6 &&
                        sig[0] == 'E'.code.toByte() &&
                        sig[1] == 'x'.code.toByte() &&
                        sig[2] == 'i'.code.toByte() &&
                        sig[3] == 'f'.code.toByte() &&
                        sig[4] == 0.toByte() &&
                        sig[5] == 0.toByte()
                    ) {
                        val tiffStart = payloadStart + 6L
                        val tiffEnd = payloadStart + payloadLen
                        ranges.addAll(parseTiffGpsRanges(channel, tiffStart, tiffEnd))
                    } else if (sig.size >= 29) {
                        val xmpHeader = String(sig, 0, 29, StandardCharsets.US_ASCII)
                        if (xmpHeader == "http://ns.adobe.com/xap/1.0/\u0000") {
                            val xmpOffset = payloadStart + 29L
                            val xmpLen = payloadLen - 29
                            if (xmpLen in 1..MAX_XMP_SIZE_BYTES) {
                                val rawXmp = readBytesAt(channel, xmpOffset, xmpLen)
                                ranges.addAll(extractXmpRedactionRanges(rawXmp, xmpOffset))
                            }
                        }
                    }
                }
                pos += segLen.toLong()
            }
            return ranges
        }

        return emptyList()
    }

    private fun parseTiffGpsRanges(
        channel: SeekableByteChannel,
        tiffStart: Long,
        tiffEnd: Long
    ): List<LongRange> {
        if (tiffEnd - tiffStart < 8L) return emptyList()
        val header = readBytesAt(channel, tiffStart, 8)
        if (header.size < 8) return emptyList()

        val order = when {
            header[0] == 0x49.toByte() && header[1] == 0x49.toByte() -> ByteOrder.LITTLE_ENDIAN
            header[0] == 0x4D.toByte() && header[1] == 0x4D.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return emptyList()
        }
        val buf = ByteBuffer.wrap(header).order(order)
        buf.position(2)
        val magic = buf.short.toInt() and 0xFFFF
        if (magic != 42) return emptyList()
        val firstIfdOffset = buf.int.toLong() and 0xFFFFFFFFL

        val ranges = mutableListOf<LongRange>()
        val visitedIfds = mutableSetOf<Long>()
        val ifdQueue = ArrayDeque<Pair<Long, Boolean>>() // (relativeOffset, isGpsIfd)
        if (firstIfdOffset > 0L) {
            ifdQueue.add(firstIfdOffset to false)
        }

        while (ifdQueue.isNotEmpty()) {
            val (relOffset, isGpsIfd) = ifdQueue.removeFirst()
            val ifdPos = tiffStart + relOffset
            if (!visitedIfds.add(ifdPos) || ifdPos + 2L > tiffEnd) continue

            val countBytes = readBytesAt(channel, ifdPos, 2)
            if (countBytes.size < 2) continue
            val entryCount = ByteBuffer.wrap(countBytes).order(order).short.toInt() and 0xFFFF
            val entriesLen = entryCount * 12
            if (entryCount <= 0 || entryCount > 1024 || ifdPos + 2L + entriesLen > tiffEnd) continue

            val entriesBytes = readBytesAt(channel, ifdPos + 2L, entriesLen)
            if (entriesBytes.size < entriesLen) continue
            val entriesBuf = ByteBuffer.wrap(entriesBytes).order(order)

            for (i in 0 until entryCount) {
                val entryBase = i * 12
                val entryFilePos = ifdPos + 2L + entryBase
                val tagId = entriesBuf.getShort(entryBase).toInt() and 0xFFFF
                val format = entriesBuf.getShort(entryBase + 2).toInt() and 0xFFFF
                val componentCount = entriesBuf.getInt(entryBase + 4).toLong() and 0xFFFFFFFFL
                val valueOrOffset = entriesBuf.getInt(entryBase + 8).toLong() and 0xFFFFFFFFL

                if (isGpsIfd) {
                    // GPS tags 0x0000..0x001E correspond to the 31 REDACTED_EXIF_TAGS in RedactionUtils
                    if (tagId in 0x0000..0x001E && format in 1..12 && componentCount > 0L) {
                        val totalBytes = componentCount * TIFF_FORMAT_BYTES[format]
                        if (totalBytes > 0L) {
                            val dataStart = if (totalBytes <= 4L) {
                                entryFilePos + 8L
                            } else {
                                tiffStart + valueOrOffset
                            }
                            if (dataStart >= tiffStart && dataStart + totalBytes <= tiffEnd) {
                                ranges.add(dataStart until (dataStart + totalBytes))
                            }
                        }
                    }
                } else {
                    when (tagId) {
                        0x8825 -> { // TAG_GPS_INFO_IFD_POINTER
                            if (valueOrOffset > 0L) {
                                ifdQueue.add(valueOrOffset to true)
                            }
                        }
                        0x8769 -> { // ExifIFDPointer
                            if (valueOrOffset > 0L) {
                                ifdQueue.add(valueOrOffset to false)
                            }
                        }
                        0x02BC -> { // TAG_XMP inside TIFF
                            if (format in 1..12 && componentCount in 1..MAX_XMP_SIZE_BYTES.toLong()) {
                                val totalBytes = componentCount * TIFF_FORMAT_BYTES[format]
                                val dataStart = if (totalBytes <= 4L) entryFilePos + 8L else tiffStart + valueOrOffset
                                if (dataStart >= tiffStart && dataStart + totalBytes <= tiffEnd) {
                                    val rawXmp = readBytesAt(channel, dataStart, totalBytes.toInt())
                                    ranges.addAll(extractXmpRedactionRanges(rawXmp, dataStart))
                                }
                            }
                        }
                    }
                }
            }

            if (!isGpsIfd && ifdPos + 2L + entriesLen + 4L <= tiffEnd) {
                val nextBytes = readBytesAt(channel, ifdPos + 2L + entriesLen, 4)
                if (nextBytes.size == 4) {
                    val nextOffset = ByteBuffer.wrap(nextBytes).order(order).int.toLong() and 0xFFFFFFFFL
                    if (nextOffset > 0L) {
                        ifdQueue.add(nextOffset to false)
                    }
                }
            }
        }

        return ranges
    }

    // --- Port of AOSP IsoInterface + RedactionUtils for ISO base media files (MP4/3GP/MOV) ---

    private class IsoBox(
        val type: Int,
        var range: LongArray,
        var headerSize: Int = 8,
        var uuid: UUID? = null,
        var data: ByteArray? = null
    )

    private fun isSupportedIsoMimeType(mimeType: String): Boolean =
        when (mimeType.lowercase(Locale.ROOT)) {
            "audio/3gp2",
            "audio/3gpp",
            "audio/3gpp2",
            "audio/aac",
            "audio/mp4",
            "audio/mpeg",
            "video/3gp2",
            "video/3gpp",
            "video/3gpp2",
            "video/mj2",
            "video/mp4",
            "video/mpeg",
            "video/quicktime",
            "video/x-flv" -> true
            else -> false
        }

    private fun isIsoBoxParent(type: Int): Boolean =
        when (type) {
            0x6d6f6f76, // moov
            0x6d6f6f66, // moof
            0x74726166, // traf
            0x6d667261, // mfra
            0x7472616b, // trak
            0x74726566, // tref
            0x6d646961, // mdia
            0x6d696e66, // minf
            0x64696e66, // dinf
            0x7374626c, // stbl
            0x65647473, // edts
            0x75647461, // udta
            0x6970726f, // ipro
            0x73696e66, // sinf
            0x686e7469, // hnti
            0x68696e66, // hinf
            0x6a703268, // jp2h
            0x696c7374, // ilst
            0x6d657461  // meta
            -> true
            else -> false
        }

    private fun extractIsoRedactionRanges(channel: SeekableByteChannel): List<LongRange> {
        val end = channel.size()
        if (end < 8L) return emptyList()
        val ftypCheck = readBytesAt(channel, 4L, 4)
        if (ftypCheck.size < 4 || ByteBuffer.wrap(ftypCheck).order(ByteOrder.BIG_ENDIAN).int != BOX_FTYP) {
            return emptyList()
        }

        val flattened = mutableListOf<IsoBox>()
        channel.position(0L)
        while (parseNextIsoBox(channel, flattened, end, -1)) {
            // Continue parsing top-level boxes
        }

        val ranges = mutableListOf<LongRange>()
        for (boxType in REDACTED_ISO_BOXES) {
            for (box in flattened) {
                if (box.type == boxType) {
                    var i = 0
                    while (i < box.range.size) {
                        val contentStart = box.range[i] + box.headerSize
                        val contentEnd = box.range[i] + box.range[i + 1]
                        val boxTypeOffset = contentStart - 4L
                        if (boxTypeOffset >= 0L && contentEnd > boxTypeOffset) {
                            ranges.add(boxTypeOffset until contentEnd)
                        }
                        i += 2
                    }
                }
            }
        }

        // XMP in UUID box or XMP_ box
        val xmpBox = flattened.firstOrNull { it.type == BOX_UUID && it.uuid == XMP_UUID && it.data != null }
            ?: flattened.firstOrNull { it.type == BOX_XMP && it.data != null }
        if (xmpBox != null && xmpBox.data != null && xmpBox.range.size >= 2) {
            val xmpOffset = xmpBox.range[0] + xmpBox.headerSize
            ranges.addAll(extractXmpRedactionRanges(xmpBox.data!!, xmpOffset))
        }

        return ranges
    }

    private fun parseNextIsoBox(
        channel: SeekableByteChannel,
        flattened: MutableList<IsoBox>,
        end: Long,
        parentType: Int
    ): Boolean {
        val pos = channel.position()
        var headerSize = 8
        if (end - pos < headerSize) return false

        val headerBytes = readBytesAt(channel, pos, 8)
        if (headerBytes.size < 8) return false
        val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        var len = Integer.toUnsignedLong(headerBuf.int)
        val type = headerBuf.int

        if (len == 0L) {
            len = end - pos
        } else if (len == 1L) {
            headerSize += 8
            if (end - pos < headerSize) return false
            val extBytes = readBytesAt(channel, pos + 8L, 8)
            if (extBytes.size < 8) return false
            len = ByteBuffer.wrap(extBytes).order(ByteOrder.BIG_ENDIAN).long
        }

        if (len < headerSize || pos + len > end) return false

        val box = IsoBox(type = type, range = longArrayOf(pos, len), headerSize = headerSize)
        channel.position(pos + headerSize)

        if (type == BOX_UUID) {
            box.headerSize += 16
            if (len < box.headerSize) return false
            val uuidBytes = readBytesAt(channel, pos + headerSize, 16)
            if (uuidBytes.size < 16) return false
            val uuidBuf = ByteBuffer.wrap(uuidBytes).order(ByteOrder.BIG_ENDIAN)
            box.uuid = UUID(uuidBuf.long, uuidBuf.long)
            if (box.uuid == XMP_UUID) {
                val dataLen = (len - box.headerSize).toInt()
                if (dataLen in 1..MAX_XMP_SIZE_BYTES) {
                    box.data = readBytesAt(channel, pos + box.headerSize, dataLen)
                }
            }
        } else if (type == BOX_XMP) {
            val dataLen = (len - box.headerSize).toInt()
            if (dataLen in 1..MAX_XMP_SIZE_BYTES) {
                box.data = readBytesAt(channel, pos + box.headerSize, dataLen)
            }
        } else if (type == BOX_META && len != headerSize.toLong()) {
            if (len >= headerSize + 8L) {
                val metaProbe = readBytesAt(channel, pos + headerSize, 8)
                if (metaProbe.size == 8) {
                    val maybeBoxType = ByteBuffer.wrap(metaProbe).order(ByteOrder.BIG_ENDIAN).getInt(4)
                    if (maybeBoxType != BOX_HDLR) {
                        box.headerSize += 4
                    }
                }
            }
            channel.position(pos + box.headerSize)
        } else if (type == BOX_XYZ && parentType == BOX_ILST) {
            box.range = longArrayOf(
                pos,
                headerSize.toLong(),
                pos + headerSize,
                len - headerSize
            )
        }

        when (type) {
            BOX_UUID -> if (box.uuid == XMP_UUID) flattened.add(box)
            BOX_META, BOX_HDLR, BOX_XYZ, BOX_LOCI, BOX_GPS, BOX_GPS0, BOX_XMP -> flattened.add(box)
        }

        if (isIsoBoxParent(type)) {
            channel.position(pos + box.headerSize)
            while (parseNextIsoBox(channel, flattened, pos + len, type)) {
                // Parse children
            }
        }

        channel.position(pos + len)
        return true
    }

    // --- XMP GPS element redaction range parser ---

    internal fun extractXmpRedactionRanges(rawXmp: ByteArray, xmpOffsetInFile: Long): List<LongRange> {
        if (rawXmp.isEmpty()) return emptyList()
        val xmpString = String(rawXmp, StandardCharsets.UTF_8)
        // Only ISO-8859-1 / ASCII-compatible byte offsets or exact UTF-8 slice matching:
        // Find `<exif:GPS...` elements whose local name is in REDACTED_EXIF_TAG_SET.
        val ranges = mutableListOf<LongRange>()
        val tagPattern = Regex("""<([A-Za-z0-9_]+:)?(GPS[A-Za-z0-9_]+)(\s[^>]*)?(?:/>|>[\s\S]*?</\1?\2\s*>)""")
        for (match in tagPattern.findAll(xmpString)) {
            val localName = match.groupValues[2]
            if (localName in REDACTED_EXIF_TAG_SET) {
                val startByte = xmpString.substring(0, match.range.first).toByteArray(StandardCharsets.UTF_8).size
                val matchByteLen = match.value.toByteArray(StandardCharsets.UTF_8).size
                if (matchByteLen > 0 && startByte + matchByteLen <= rawXmp.size) {
                    ranges.add((xmpOffsetInFile + startByte) until (xmpOffsetInFile + startByte + matchByteLen))
                }
            }
        }
        return ranges
    }

    private fun readBytesAt(channel: SeekableByteChannel, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        channel.position(offset)
        val buf = ByteBuffer.allocate(length)
        while (buf.hasRemaining()) {
            val r = channel.read(buf)
            if (r <= 0) break
        }
        val readCount = buf.position()
        return if (readCount == length) buf.array() else buf.array().copyOf(readCount)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

internal class ByteArraySeekableByteChannel(private val data: ByteArray) : SeekableByteChannel {
    private var pos: Int = 0
    private var open: Boolean = true

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }

    override fun read(dst: ByteBuffer): Int {
        if (pos >= data.size) return -1
        val toRead = minOf(dst.remaining(), data.size - pos)
        dst.put(data, pos, toRead)
        pos += toRead
        return toRead
    }

    override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()

    override fun position(): Long = pos.toLong()

    override fun position(newPosition: Long): SeekableByteChannel {
        pos = newPosition.coerceIn(0L, data.size.toLong()).toInt()
        return this
    }

    override fun size(): Long = data.size.toLong()

    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
}


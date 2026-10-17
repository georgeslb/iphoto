package org.iphoto.mobile.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class ChunkedVideoDataSourceTest {

    private fun fakeUri(url: String): Uri = mockk<Uri> {
        every { this@mockk.toString() } returns url
    }

    private fun buildRangeCallFactory(
        fileBytes: ByteArray,
        serverMaxChunkCap: Int = Int.MAX_VALUE,
        recordedRanges: MutableList<String> = mutableListOf()
    ): Call.Factory = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request()
            val rangeHeader = req.header("Range") ?: "bytes=0-${fileBytes.size - 1}"
            recordedRanges.add(rangeHeader)
            val match = Regex("bytes=(\\d+)-(\\d*)").find(rangeHeader)!!
            val start = match.groupValues[1].toInt()
            val requestedEnd = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
                ?: (fileBytes.size - 1)
            val cappedEnd = min(
                requestedEnd.toLong(),
                min((fileBytes.size - 1).toLong(), start.toLong() + serverMaxChunkCap.toLong() - 1L)
            ).toInt()
            val slice = fileBytes.copyOfRange(start, cappedEnd + 1)
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Accept-Ranges", "bytes")
                .header("Content-Range", "bytes $start-$cappedEnd/${fileBytes.size}")
                .header("Content-Length", slice.size.toString())
                .body(slice.toResponseBody("video/mp4".toMediaType()))
                .build()
        }
        .build()

    @Test
    fun `chains multiple requests when server caps 206 responses without premature EOF`() {
        val totalSize = 2_500_000
        val fileBytes = ByteArray(totalSize) { (it * 31 + 7).toByte() }
        val recordedRanges = mutableListOf<String>()
        val callFactory = buildRangeCallFactory(
            fileBytes = fileBytes,
            serverMaxChunkCap = 512 * 1024,
            recordedRanges = recordedRanges
        )
        val cache = ChunkedVideoDataSource.VideoBlockCache()
        val dataSource = ChunkedVideoDataSource(callFactory, sharedCache = cache)

        val uri = fakeUri("http://server/api/photos/v1/stream")
        val reportedLength = dataSource.open(DataSpec(uri, 0L, C.LENGTH_UNSET.toLong()))

        assertEquals(totalSize.toLong(), reportedLength)

        val output = ByteArray(totalSize)
        var totalRead = 0
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = dataSource.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            System.arraycopy(buf, 0, output, totalRead, n)
            totalRead += n
        }
        dataSource.close()

        assertEquals(totalSize, totalRead)
        assertArrayEquals(fileBytes, output)
        assertTrue("Expected multiple range requests, got $recordedRanges", recordedRanges.size >= 5)
    }

    @Test
    fun `serves non-faststart moov seek and return to mdat from 128 KB block cache without re-fetching block 0`() {
        val totalSize = 2_000_000
        val fileBytes = ByteArray(totalSize) { (it xor (it ushr 8)).toByte() }
        val recordedRanges = mutableListOf<String>()
        val callFactory = buildRangeCallFactory(
            fileBytes = fileBytes,
            recordedRanges = recordedRanges
        )
        val cache = ChunkedVideoDataSource.VideoBlockCache()
        val dataSource = ChunkedVideoDataSource(callFactory, sharedCache = cache)
        val uri = fakeUri("http://server/api/photos/v2/stream")

        // Step 1: ExoPlayer probes first 32 bytes at offset 0 (ftyp + mdat header), then closes to seek to moov
        val len1 = dataSource.open(DataSpec(uri, 0L, C.LENGTH_UNSET.toLong()))
        assertEquals(totalSize.toLong(), len1)
        val header = ByteArray(32)
        assertEquals(32, dataSource.read(header, 0, 32))
        assertArrayEquals(fileBytes.copyOfRange(0, 32), header)
        dataSource.close() // Drains remaining bytes of initial 256 KB chunk into cache

        assertEquals(1, recordedRanges.size)

        // Step 2: ExoPlayer seeks to moov at end of file (offset 1,900,000)
        val moovOffset = 1_900_000L
        val len2 = dataSource.open(DataSpec(uri, moovOffset, C.LENGTH_UNSET.toLong()))
        assertEquals(totalSize - moovOffset, len2)
        val moovBuf = ByteArray(4096)
        val moovRead = dataSource.read(moovBuf, 0, moovBuf.size)
        assertTrue(moovRead > 0)
        assertArrayEquals(fileBytes.copyOfRange(moovOffset.toInt(), moovOffset.toInt() + moovRead), moovBuf.copyOf(moovRead))
        dataSource.close()

        val requestsAfterMoov = recordedRanges.size
        assertEquals(2, requestsAfterMoov)

        // Step 3: ExoPlayer seeks back to byte 32 (start of mdat) -> first 256 KB must be served from RAM cache!
        val len3 = dataSource.open(DataSpec(uri, 32L, C.LENGTH_UNSET.toLong()))
        assertEquals(totalSize - 32L, len3)
        val mdatStart = ByteArray(200_000)
        var mdatRead = 0
        while (mdatRead < mdatStart.size) {
            val n = dataSource.read(mdatStart, mdatRead, mdatStart.size - mdatRead)
            assertTrue(n > 0)
            mdatRead += n
        }
        dataSource.close()

        assertArrayEquals(fileBytes.copyOfRange(32, 32 + mdatStart.size), mdatStart)
        assertEquals(requestsAfterMoov, recordedRanges.size)
    }

    @Test
    fun `retries automatically on transient network error during read`() {
        val totalSize = 300_000
        val fileBytes = ByteArray(totalSize) { (it * 17).toByte() }
        val callCount = AtomicInteger(0)

        val callFactory = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val count = callCount.incrementAndGet()
                if (count == 2) {
                    throw IOException("Simulated socket timeout on second chunk")
                }
                val match = Regex("bytes=(\\d+)-(\\d*)").find(req.header("Range")!!)!!
                val start = match.groupValues[1].toInt()
                val end = min(match.groupValues[2].toInt(), fileBytes.size - 1)
                val slice = fileBytes.copyOfRange(start, end + 1)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes $start-$end/${fileBytes.size}")
                    .header("Content-Length", slice.size.toString())
                    .body(slice.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()

        val cache = ChunkedVideoDataSource.VideoBlockCache()
        val dataSource = ChunkedVideoDataSource(
            callFactory,
            initialChunkSize = ChunkedVideoDataSource.BLOCK_SIZE,
            streamChunkSize = ChunkedVideoDataSource.BLOCK_SIZE,
            sharedCache = cache
        )

        val uri = fakeUri("http://server/api/photos/v3/stream")
        dataSource.open(DataSpec(uri, 0L, C.LENGTH_UNSET.toLong()))

        val output = ByteArray(totalSize)
        var totalRead = 0
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = dataSource.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            System.arraycopy(buf, 0, output, totalRead, n)
            totalRead += n
        }
        dataSource.close()

        assertEquals(totalSize, totalRead)
        assertArrayEquals(fileBytes, output)
        assertTrue(callCount.get() >= 3)
    }
}

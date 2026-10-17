package org.iphoto.mobile.network

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.math.min
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response

private const val TAG = "ChunkedVideoDataSource"

/**
 * Bounded HTTP Range [DataSource] with an in-memory aligned block cache for ExoPlayer video
 * streaming (`GET /api/photos/{id}/stream`).
 *
 * Solves three problems with standard [androidx.media3.datasource.okhttp.OkHttpDataSource] against
 * self-hosted/remote servers:
 * 1. **Premature EOF on server-capped 206 responses:** `ProgressiveMediaPeriod` opens streams with
 *    `length = C.LENGTH_UNSET`. If the server or reverse proxy returns a partial `ResourceRegion`
 *    (e.g. 1 MB chunk with `Content-Range: bytes 0-1048575/50000000`), standard `OkHttpDataSource`
 *    treats the chunk's `Content-Length` as the entire resource length and returns
 *    `RESULT_END_OF_INPUT` after 1 MB, causing ExoPlayer to stop loading permanently. This
 *    DataSource parses the total resource length from `Content-Range` and seamlessly chains
 *    subsequent bounded `Range` requests inside [read] until the true end of the file.
 * 2. **Connection pool destruction on MP4 seeks:** Smartphone videos frequently place the `moov`
 *    atom at the end of the file or interleave audio/video tracks > 256 KB apart, causing
 *    `Mp4Extractor` to issue frequent seeks (`close()` + `open()`). With unbounded HTTP responses,
 *    every seek aborts a multi-hundred-MB response body, forcing OkHttp to close the TCP/TLS
 *    socket and perform a new TLS handshake. By fetching bounded chunks (256 KB initial probe,
 *    scaling up to 1 MB / 2 MB for sequential streaming) and draining small remaining tails into
 *    the block cache on [close], HTTP responses finish cleanly and reuse warm keep-alive sockets.
 * 3. **Zero-network seeks via 128 KB block cache:** Aligned 128 KB blocks (32 MB LRU cache) keep
 *    the initial header/`mdat` start, `moov` atom, and active audio/video track windows in RAM, so
 *    seeking back from `moov` to `mdat` and alternating between video and audio tracks are served
 *    from memory with zero network round-trips.
 */
@UnstableApi
class ChunkedVideoDataSource(
    private val callFactory: Call.Factory,
    private val initialChunkSize: Int = DEFAULT_INITIAL_CHUNK_SIZE,
    private val streamChunkSize: Int = DEFAULT_STREAM_CHUNK_SIZE,
    private val sharedCache: VideoBlockCache = defaultCache
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(
        private val callFactory: Call.Factory,
        private val initialChunkSize: Int = DEFAULT_INITIAL_CHUNK_SIZE,
        private val streamChunkSize: Int = DEFAULT_STREAM_CHUNK_SIZE,
        private val sharedCache: VideoBlockCache = defaultCache
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedVideoDataSource(callFactory, initialChunkSize, streamChunkSize, sharedCache)
    }

    class VideoBlockCache(private val maxSizeBytes: Int = DEFAULT_MAX_CACHE_BYTES) {
        private data class BlockKey(val uri: String, val blockIndex: Long)

        private val totalLengths = ConcurrentHashMap<String, Long>()
        private val lru = LinkedHashMap<BlockKey, ByteArray>(64, 0.75f, true)
        private var currentBytes: Int = 0

        fun getTotalLength(uri: String): Long? = totalLengths[uri]

        fun putTotalLength(uri: String, length: Long) {
            if (length > 0) {
                totalLengths[uri] = length
            }
        }

        @Synchronized
        fun getBlock(uri: String, blockIndex: Long): ByteArray? = lru[BlockKey(uri, blockIndex)]

        @Synchronized
        fun putBlock(uri: String, blockIndex: Long, data: ByteArray) {
            if (data.isEmpty()) return
            val key = BlockKey(uri, blockIndex)
            val previous = lru.put(key, data)
            if (previous != null) {
                currentBytes -= previous.size
            }
            currentBytes += data.size
            while (currentBytes > maxSizeBytes && lru.isNotEmpty()) {
                val eldestKey = lru.keys.iterator().next()
                val removed = lru.remove(eldestKey)
                if (removed != null) {
                    currentBytes -= removed.size
                }
            }
        }

        @Synchronized
        fun clear() {
            lru.clear()
            currentBytes = 0
            totalLengths.clear()
        }
    }

    private var dataSpec: DataSpec? = null
    private var currentUri: String? = null
    private var currentPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    private var currentResponse: Response? = null
    private var currentStream: InputStream? = null
    private var currentChunkRemaining: Long = 0L
    private var consecutiveChunks: Int = 0

    // Staging buffer for the currently streaming 128 KB aligned block
    private val stagingBuffer = ByteArray(BLOCK_SIZE)
    private var stagingBlockIndex: Long = -1L
    private var stagingBlockFilled: Int = 0

    override fun getUri(): Uri? = dataSpec?.uri

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        val uriString = dataSpec.uri.toString()
        this.currentUri = uriString
        this.currentPosition = dataSpec.position
        this.consecutiveChunks = 0
        this.stagingBlockIndex = -1L
        this.stagingBlockFilled = 0

        transferInitializing(dataSpec)

        try {
            val knownTotal = sharedCache.getTotalLength(uriString)
            if (knownTotal != null && knownTotal > 0L) {
                bytesRemaining = if (currentPosition >= knownTotal) {
                    0L
                } else if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    min(dataSpec.length, knownTotal - currentPosition)
                } else {
                    knownTotal - currentPosition
                }
                opened = true
                transferStarted(dataSpec)
                return bytesRemaining
            }

            // First time probing this URI: open the initial bounded chunk so we learn the total
            // resource length from Content-Range right away and warm the first block(s).
            openNextHttpChunk(uriString)

            val resolvedTotal = sharedCache.getTotalLength(uriString) ?: C.LENGTH_UNSET.toLong()
            bytesRemaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> {
                    if (resolvedTotal != C.LENGTH_UNSET.toLong()) {
                        min(dataSpec.length, (resolvedTotal - currentPosition).coerceAtLeast(0L))
                    } else {
                        dataSpec.length
                    }
                }
                resolvedTotal != C.LENGTH_UNSET.toLong() -> {
                    (resolvedTotal - currentPosition).coerceAtLeast(0L)
                }
                else -> currentChunkRemaining
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: IOException) {
            closeHttpStreamQuietly()
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val spec = dataSpec ?: return C.RESULT_END_OF_INPUT
        val uriString = currentUri ?: return C.RESULT_END_OF_INPUT

        val blockIndex = currentPosition / BLOCK_SIZE
        val blockOffset = (currentPosition % BLOCK_SIZE).toInt()

        // 1. Serve from in-memory 128 KB block cache if present
        val cachedBlock = sharedCache.getBlock(uriString, blockIndex)
        if (cachedBlock != null && blockOffset < cachedBlock.size) {
            if (currentStream != null) {
                drainAndCloseHttpStream(uriString)
            }
            val availableInBlock = cachedBlock.size - blockOffset
            val maxToRead = if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                min(length.toLong(), bytesRemaining)
            } else {
                length.toLong()
            }
            val toCopy = min(maxToRead, availableInBlock.toLong()).toInt()
            System.arraycopy(cachedBlock, blockOffset, buffer, offset, toCopy)
            currentPosition += toCopy
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= toCopy
            }
            bytesTransferred(toCopy)
            return toCopy
        }

        // 2. Cache miss: read from active HTTP chunk (or open the next bounded Range chunk)
        var attempt = 0
        while (true) {
            try {
                if (currentStream == null || currentChunkRemaining <= 0L) {
                    closeHttpStreamQuietly()
                    openNextHttpChunk(uriString)
                    if (currentChunkRemaining <= 0L || currentStream == null) {
                        return C.RESULT_END_OF_INPUT
                    }
                }

                // Cap single read at the next 128 KB block boundary so block staging stays aligned
                val bytesToBlockBoundary = BLOCK_SIZE - blockOffset
                val maxByRemaining = if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    min(length.toLong(), bytesRemaining)
                } else {
                    length.toLong()
                }
                val toRead = min(
                    min(maxByRemaining, bytesToBlockBoundary.toLong()),
                    currentChunkRemaining
                ).toInt()

                val stream = currentStream ?: return C.RESULT_END_OF_INPUT
                val readBytes = stream.read(buffer, offset, toRead)
                if (readBytes == -1) {
                    // Server closed chunk earlier than expected Content-Length; close and chain next
                    // range request from currentPosition unless we've reached totalLength.
                    closeHttpStreamQuietly()
                    val knownTotal = sharedCache.getTotalLength(uriString)
                    if (knownTotal != null && currentPosition >= knownTotal) {
                        bytesRemaining = 0L
                        return C.RESULT_END_OF_INPUT
                    }
                    attempt++
                    if (attempt > MAX_RETRIES) {
                        throw EOFException("Unexpected EOF at byte $currentPosition")
                    }
                    continue
                }

                // Stage into 128 KB block buffer if aligned
                stageReadBytes(uriString, blockIndex, blockOffset, buffer, offset, readBytes)

                currentPosition += readBytes
                currentChunkRemaining -= readBytes
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    bytesRemaining -= readBytes
                }
                if (currentChunkRemaining <= 0L) {
                    // Chunk completed cleanly -> close ResponseBody immediately so OkHttp returns
                    // the warm socket to the connection pool.
                    closeHttpStreamQuietly()
                }
                bytesTransferred(readBytes)
                return readBytes
            } catch (e: IOException) {
                closeHttpStreamQuietly()
                attempt++
                if (attempt > MAX_RETRIES || Thread.currentThread().isInterrupted) {
                    throw HttpDataSource.HttpDataSourceException.createForIOException(
                        e,
                        spec,
                        HttpDataSource.HttpDataSourceException.TYPE_READ
                    )
                }
                Log.w(TAG, "Transient read error at pos=$currentPosition (attempt $attempt/$MAX_RETRIES), retrying: ${e.message}")
                try {
                    Thread.sleep(100L * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw HttpDataSource.HttpDataSourceException.createForIOException(
                        e,
                        spec,
                        HttpDataSource.HttpDataSourceException.TYPE_READ
                    )
                }
            }
        }
    }

    override fun close() {
        val uriString = currentUri
        try {
            if (uriString != null) {
                drainAndCloseHttpStream(uriString)
            } else {
                closeHttpStreamQuietly()
            }
        } finally {
            stagingBlockIndex = -1L
            stagingBlockFilled = 0
            dataSpec = null
            currentUri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun stageReadBytes(
        uriString: String,
        blockIndex: Long,
        blockOffset: Int,
        source: ByteArray,
        sourceOffset: Int,
        count: Int
    ) {
        if (blockOffset == 0 && stagingBlockIndex != blockIndex) {
            stagingBlockIndex = blockIndex
            stagingBlockFilled = 0
        }
        if (stagingBlockIndex == blockIndex && stagingBlockFilled == blockOffset && blockOffset + count <= BLOCK_SIZE) {
            System.arraycopy(source, sourceOffset, stagingBuffer, stagingBlockFilled, count)
            stagingBlockFilled += count
            val expectedSize = expectedBlockSize(uriString, blockIndex)
            if (stagingBlockFilled >= expectedSize) {
                sharedCache.putBlock(uriString, blockIndex, stagingBuffer.copyOf(expectedSize))
                stagingBlockIndex = -1L
                stagingBlockFilled = 0
            }
        } else {
            stagingBlockIndex = -1L
            stagingBlockFilled = 0
        }
    }

    private fun expectedBlockSize(uriString: String, blockIndex: Long): Int {
        val total = sharedCache.getTotalLength(uriString) ?: return BLOCK_SIZE
        val blockStart = blockIndex * BLOCK_SIZE
        if (blockStart >= total) return BLOCK_SIZE
        return min(BLOCK_SIZE.toLong(), total - blockStart).toInt()
    }

    @Throws(IOException::class)
    private fun openNextHttpChunk(uriString: String) {
        val knownTotal = sharedCache.getTotalLength(uriString)
        if (knownTotal != null && currentPosition >= knownTotal) {
            currentChunkRemaining = 0L
            return
        }

        // Align the request start to the 128 KB block boundary so that mid-block seeks (like MP4
        // audio track reads) still populate the full 128 KB block into the cache.
        val blockIndex = currentPosition / BLOCK_SIZE
        val blockStart = blockIndex * BLOCK_SIZE
        val prefixToSkip = (currentPosition - blockStart).toInt()

        val targetChunkSize = when (consecutiveChunks) {
            0 -> initialChunkSize
            1 -> min(streamChunkSize, 1024 * 1024)
            else -> streamChunkSize
        }.coerceAtLeast(BLOCK_SIZE)

        val requestEnd = if (knownTotal != null && knownTotal > 0L) {
            min(blockStart + targetChunkSize - 1L, knownTotal - 1L)
        } else {
            blockStart + targetChunkSize - 1L
        }

        val request = Request.Builder()
            .url(uriString)
            .header("Range", "bytes=$blockStart-$requestEnd")
            .build()

        var lastException: IOException? = null
        var response: Response? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val res = callFactory.newCall(request).execute()
                response = res
                break
            } catch (e: IOException) {
                lastException = e
                if (attempt == MAX_RETRIES || Thread.currentThread().isInterrupted) {
                    throw e
                }
                try {
                    Thread.sleep(100L * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }

        val res = response ?: throw (lastException ?: IOException("Failed to open chunk"))
        if (!res.isSuccessful) {
            val code = res.code
            res.close()
            if (code == 416) {
                currentChunkRemaining = 0L
                return
            }
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                res.message,
                null,
                res.headers.toMultimap(),
                dataSpec ?: DataSpec(Uri.parse(uriString)),
                ByteArray(0)
            )
        }

        val body = res.body

        val contentRange = res.header("Content-Range")
        val contentLengthHeader = res.header("Content-Length")?.toLongOrNull()

        var actualRangeStart = blockStart
        var rangeLength = C.LENGTH_UNSET.toLong()

        if (!contentRange.isNullOrBlank()) {
            val matcher = CONTENT_RANGE_PATTERN.matcher(contentRange)
            if (matcher.matches()) {
                actualRangeStart = matcher.group(1)?.toLongOrNull() ?: blockStart
                val rangeEnd = matcher.group(2)?.toLongOrNull()
                val totalStr = matcher.group(3)
                if (totalStr != null && totalStr != "*") {
                    totalStr.toLongOrNull()?.let { total ->
                        sharedCache.putTotalLength(uriString, total)
                    }
                }
                if (rangeEnd != null && rangeEnd >= actualRangeStart) {
                    rangeLength = rangeEnd - actualRangeStart + 1L
                }
            }
        } else if (res.code == 200) {
            actualRangeStart = 0L
            if (contentLengthHeader != null && contentLengthHeader > 0L) {
                sharedCache.putTotalLength(uriString, contentLengthHeader)
            }
        }

        val stream = body.byteStream()
        var streamRemaining = when {
            contentLengthHeader != null && contentLengthHeader >= 0L -> contentLengthHeader
            rangeLength != C.LENGTH_UNSET.toLong() -> rangeLength
            else -> {
                val total = sharedCache.getTotalLength(uriString)
                if (total != null) (total - actualRangeStart).coerceAtLeast(0L) else C.LENGTH_UNSET.toLong()
            }
        }

        // Advance from actualRangeStart to currentPosition, staging into blockIndex if aligned
        val bytesToAdvance = currentPosition - actualRangeStart
        if (bytesToAdvance < 0L) {
            res.close()
            throw IOException("Server returned range starting at $actualRangeStart after requested $currentPosition")
        }

        if (actualRangeStart == blockStart && prefixToSkip > 0) {
            stagingBlockIndex = blockIndex
            stagingBlockFilled = 0
            var remainingPrefix = prefixToSkip
            while (remainingPrefix > 0) {
                val n = stream.read(stagingBuffer, stagingBlockFilled, remainingPrefix)
                if (n == -1) {
                    res.close()
                    throw EOFException("EOF while reading block prefix ($prefixToSkip bytes) at $blockStart")
                }
                stagingBlockFilled += n
                remainingPrefix -= n
                if (streamRemaining != C.LENGTH_UNSET.toLong()) {
                    streamRemaining -= n
                }
            }
        } else if (bytesToAdvance > 0L) {
            stagingBlockIndex = -1L
            stagingBlockFilled = 0
            var toSkip = bytesToAdvance
            val skipBuf = ByteArray(8192)
            while (toSkip > 0L) {
                val n = stream.read(skipBuf, 0, min(toSkip, skipBuf.size.toLong()).toInt())
                if (n == -1) {
                    res.close()
                    throw EOFException("EOF while skipping $bytesToAdvance bytes")
                }
                toSkip -= n
                if (streamRemaining != C.LENGTH_UNSET.toLong()) {
                    streamRemaining -= n
                }
            }
        } else {
            stagingBlockIndex = blockIndex
            stagingBlockFilled = 0
        }

        currentResponse = res
        currentStream = stream
        currentChunkRemaining = streamRemaining
        consecutiveChunks++
    }

    /**
     * When `Mp4Extractor` seeks after reading only part of a small chunk (e.g. reading the 32-byte
     * `ftyp` header at offset 0 before jumping to `moov`, or reading 8 KB of audio before jumping
     * back to video), draining the remaining bytes of the current chunk (up to [initialChunkSize])
     * into [sharedCache] both populates the block cache for the return seek AND finishes the HTTP
     * response cleanly so OkHttp keeps the TCP/TLS connection warm in its pool.
     */
    private fun drainAndCloseHttpStream(uriString: String) {
        val stream = currentStream
        val res = currentResponse
        if (stream != null && currentChunkRemaining in 1..initialChunkSize.toLong()) {
            try {
                val deadlineNs = System.nanoTime() + DRAIN_TIMEOUT_NS
                val drainBuf = ByteArray(16 * 1024)
                var pos = currentPosition
                var rem = currentChunkRemaining
                while (rem > 0L && System.nanoTime() < deadlineNs) {
                    val bIndex = pos / BLOCK_SIZE
                    val bOffset = (pos % BLOCK_SIZE).toInt()
                    val toBoundary = BLOCK_SIZE - bOffset
                    val toRead = min(min(rem, toBoundary.toLong()), drainBuf.size.toLong()).toInt()
                    val n = stream.read(drainBuf, 0, toRead)
                    if (n <= 0) break
                    stageReadBytes(uriString, bIndex, bOffset, drainBuf, 0, n)
                    pos += n
                    rem -= n
                }
            } catch (_: IOException) {
                // Ignore errors while draining on close; the stream is being closed anyway.
            }
        }
        currentStream = null
        currentResponse = null
        currentChunkRemaining = 0L
        try {
            res?.close()
        } catch (_: Exception) {
        }
    }

    private fun closeHttpStreamQuietly() {
        val res = currentResponse
        currentStream = null
        currentResponse = null
        currentChunkRemaining = 0L
        try {
            res?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val BLOCK_SIZE: Int = 128 * 1024 // 128 KB aligned cache blocks
        const val DEFAULT_INITIAL_CHUNK_SIZE: Int = 256 * 1024 // 256 KB (2 blocks)
        const val DEFAULT_STREAM_CHUNK_SIZE: Int = 2 * 1024 * 1024 // 2 MB (16 blocks)
        const val DEFAULT_MAX_CACHE_BYTES: Int = 32 * 1024 * 1024 // 32 MB LRU block cache
        private const val MAX_RETRIES: Int = 3
        private const val DRAIN_TIMEOUT_NS: Long = 80_000_000L // 80ms max drain budget on close

        private val CONTENT_RANGE_PATTERN: Pattern =
            Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)")

        val defaultCache = VideoBlockCache()
    }
}

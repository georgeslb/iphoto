package org.iphoto.mobile.sync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import org.iphoto.mobile.model.TaskDTO
import org.iphoto.mobile.model.UploadChunkResponse
import org.iphoto.mobile.network.PhotoApi
import org.iphoto.mobile.network.TaskApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

// Must track SyncRepository's own private CHUNK_SIZE constant (1 MiB) - there's no way to
// reference it directly from here, so a chunking-boundary test failing after that constant
// changes is the signal to update this too.
private const val CHUNK_SIZE = 1 * 1024 * 1024

/**
 * Exercises SyncRepository.uploadAsset's chunking/multipart-building logic and waitForProcessing's
 * polling loop. `readFully` (the reason this file exists) matters because a content provider's
 * InputStream isn't guaranteed to fill a caller's buffer in one read() call even mid-stream -
 * StingyInputStream below deliberately returns small reads to prove the chunk boundaries and byte
 * content still come out correct despite that.
 */
class SyncRepositoryUploadTest {

    private val context = mockk<Context>()
    private val contentResolver = mockk<ContentResolver>()
    private val photoApi = mockk<PhotoApi>()
    private val taskApi = mockk<TaskApi>()
    private val hashCache = mockk<HashCache>(relaxed = true)
    private val assetUri = mockk<Uri>(relaxed = true)

    private val repository = SyncRepository(context, photoApi, taskApi, hashCache)

    init {
        every { context.contentResolver } returns contentResolver
    }

    private fun RequestBody.toByteArray(): ByteArray {
        val buffer = Buffer()
        writeTo(buffer)
        return buffer.readByteArray()
    }

    private fun RequestBody.toText(): String = toByteArray().toString(Charsets.UTF_8)

    // Never returns more than [maxRead] bytes per call regardless of the caller's requested
    // length, so a 1 MiB chunk is only ever assembled from many small reads - the exact situation
    // SyncRepository.readFully exists to handle correctly.
    private class StingyInputStream(private val data: ByteArray, private val maxRead: Int = 4096) : InputStream() {
        private var position = 0
        override fun read(): Int = throw UnsupportedOperationException("not used by SyncRepository")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(maxRead, len, data.size - position)
            System.arraycopy(data, position, b, off, n)
            position += n
            return n
        }
    }

    @Test
    fun `throws when the content resolver can't open the asset`() = runTest {
        every { contentResolver.openInputStream(assetUri) } returns null
        val asset = LocalAsset(assetUri, "photo.jpg", 10L, 1000L, "image/jpeg")

        val error = try {
            repository.uploadAsset(asset, "space-1")
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue(error != null)
        coVerify(exactly = 0) { photoApi.uploadChunk(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `uploads a small asset as a single chunk with the right multipart fields`() = runTest {
        val bytes = ByteArray(10) { it.toByte() }
        every { contentResolver.openInputStream(assetUri) } returns StingyInputStream(bytes)
        val asset = LocalAsset(assetUri, "photo.jpg", bytes.size.toLong(), 1000L, "image/jpeg")
        var capturedFileName: String? = null
        var capturedFileSize: String? = null
        var capturedChunkIndex: String? = null
        var capturedTotalChunks: String? = null
        var capturedContentType: String? = null
        var capturedSpaceId: String? = null
        var capturedBytes: ByteArray? = null
        coEvery {
            photoApi.uploadChunk(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            capturedFileName = secondArg<RequestBody>().toText()
            capturedFileSize = thirdArg<RequestBody>().toText()
            capturedChunkIndex = arg<RequestBody>(3).toText()
            capturedTotalChunks = arg<RequestBody>(4).toText()
            capturedContentType = arg<RequestBody>(5).toText()
            capturedSpaceId = arg<RequestBody>(6).toText()
            capturedBytes = arg<MultipartBody.Part>(7).body.toByteArray()
            UploadChunkResponse(status = "ok", chunkIndex = "0", task = null)
        }

        repository.uploadAsset(asset, "space-1")

        coVerify(exactly = 1) { photoApi.uploadChunk(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals("photo.jpg", capturedFileName)
        assertEquals("10", capturedFileSize)
        assertEquals("0", capturedChunkIndex)
        assertEquals("1", capturedTotalChunks)
        assertEquals("image/jpeg", capturedContentType)
        assertEquals("space-1", capturedSpaceId)
        assertTrue(bytes.contentEquals(capturedBytes))
    }

    @Test
    fun `splits an asset larger than the chunk size into multiple correctly-sized chunks`() = runTest {
        val bytes = ByteArray(CHUNK_SIZE + 500) { (it % 251).toByte() }
        every { contentResolver.openInputStream(assetUri) } returns StingyInputStream(bytes)
        val asset = LocalAsset(assetUri, "video.mp4", bytes.size.toLong(), 1000L, "video/mp4")
        val uploadedChunkIndexes = mutableListOf<String>()
        val uploadedChunkBytes = mutableListOf<ByteArray>()
        coEvery {
            photoApi.uploadChunk(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val chunkIndex = arg<RequestBody>(3).toText()
            uploadedChunkIndexes.add(chunkIndex)
            uploadedChunkBytes.add(arg<MultipartBody.Part>(7).body.toByteArray())
            UploadChunkResponse(status = "ok", chunkIndex = chunkIndex, task = null)
        }

        repository.uploadAsset(asset, "space-1")

        assertEquals(listOf("0", "1"), uploadedChunkIndexes)
        assertTrue(bytes.copyOfRange(0, CHUNK_SIZE).contentEquals(uploadedChunkBytes[0]))
        assertTrue(bytes.copyOfRange(CHUNK_SIZE, bytes.size).contentEquals(uploadedChunkBytes[1]))
    }

    @Test
    fun `waits for the task from the final chunk to complete`() = runTest {
        val bytes = ByteArray(10) { it.toByte() }
        every { contentResolver.openInputStream(assetUri) } returns StingyInputStream(bytes)
        val asset = LocalAsset(assetUri, "photo.jpg", bytes.size.toLong(), 1000L, "image/jpeg")
        coEvery {
            photoApi.uploadChunk(any(), any(), any(), any(), any(), any(), any(), any())
        } returns UploadChunkResponse(status = "ok", chunkIndex = "0", task = TaskDTO("task-1", "PENDING", null))
        coEvery { taskApi.get("task-1") } returnsMany listOf(
            TaskDTO("task-1", "PENDING", null),
            TaskDTO("task-1", "PROCESSING", null),
            TaskDTO("task-1", "COMPLETED", "photo-1")
        )

        repository.uploadAsset(asset, "space-1")

        coVerify(exactly = 3) { taskApi.get("task-1") }
    }

    @Test
    fun `throws when the task fails processing`() = runTest {
        val bytes = ByteArray(10) { it.toByte() }
        every { contentResolver.openInputStream(assetUri) } returns StingyInputStream(bytes)
        val asset = LocalAsset(assetUri, "photo.jpg", bytes.size.toLong(), 1000L, "image/jpeg")
        coEvery {
            photoApi.uploadChunk(any(), any(), any(), any(), any(), any(), any(), any())
        } returns UploadChunkResponse(status = "ok", chunkIndex = "0", task = TaskDTO("task-1", "PENDING", null))
        coEvery { taskApi.get("task-1") } returns TaskDTO("task-1", "FAILED", null)

        val error = try {
            repository.uploadAsset(asset, "space-1")
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue(error != null)
        assertTrue(error?.message?.contains("task-1") == true)
    }

    @Test
    fun `never polls for a task when the final chunk response carries none`() = runTest {
        val bytes = ByteArray(10) { it.toByte() }
        every { contentResolver.openInputStream(assetUri) } returns StingyInputStream(bytes)
        val asset = LocalAsset(assetUri, "photo.jpg", bytes.size.toLong(), 1000L, "image/jpeg")
        coEvery {
            photoApi.uploadChunk(any(), any(), any(), any(), any(), any(), any(), any())
        } returns UploadChunkResponse(status = "ok", chunkIndex = "0", task = null)

        repository.uploadAsset(asset, "space-1")

        coVerify(exactly = 0) { taskApi.get(any()) }
    }

    // Not covered: waitForProcessing's timeout path (deadline exceeded). Its deadline check uses
    // System.currentTimeMillis() (real wall-clock time) while its retry backoff uses
    // kotlinx.coroutines.delay() (virtual time under runTest) - those two don't advance together,
    // so a test that actually reaches the 5-minute deadline would need ~5 real minutes to run, not
    // a few virtual-time-skipped milliseconds. Would need either a real Clock abstraction injected
    // into SyncRepository or genuinely waiting out the timeout; neither is worth it for this path
    // alone.
}

package org.iphoto.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.iphoto.AbstractIntegrationTest;
import org.iphoto.repository.PhotoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Found while investigating a flaky PhotoServiceTest.
// downloadSingleAndMultiplePhotosSetContentDispositionAndZipEntries failure (see TODO.md):
// TaskWorkerManagerImpl.queuedTasks used to guard against submitting the same task to an executor
// twice with a non-atomic "if (!contains) add" pair, not one atomic operation. Two threads calling
// pollLightPendingTasks() at once - the real @Scheduled(fixedDelay=5000) timer thread racing a
// caller's own direct call, exactly what AbstractIntegrationTest.waitForTaskCompletion does in
// every other test in this suite - could both see "not yet queued" before either had added it, and
// both submit the SAME still-PENDING task, running it twice concurrently on two different virtual
// threads. For a PROCESS_UPLOAD task, both executions would build a PhotoEntity with the exact same
// id (task.getPhotoId(), pre-allocated at upload-chunk time - see PhotoUploadServiceImpl.
// processTheAsset) and both race to Files.move() the same temp file: whichever loses gets a
// NoSuchFileException and fails the task, and depending on which of the two transactions commits
// its own task-status update LAST, the task's final persisted status would be unpredictably
// COMPLETED or FAILED even though the photo itself was actually created successfully by the
// winner - a real, DB-transaction-boundary race (the window is between findExecutableTasks' SELECT
// and markQueued's commit), architecturally identical to this class's own pre-existing comment
// documenting an already-observed instance of the same underlying bug for UPLOAD_FROM_IMMICH.
// Fixed regardless by making the check-and-mark one atomic Set.add() call
// (ConcurrentHashMap.newKeySet(), not a CopyOnWriteArrayList) - see that field's own comment.
//
// NOT a proven red-before/green-after regression test: hammering pollLightPendingTasks() from many
// threads at once here (below) did not reproduce the pre-fix race even against the original,
// unfixed code (tried up to 64 concurrent callers, several times) - the real window is apparently
// narrower than plain JVM thread-scheduling jitter reliably hits against an in-memory H2 DB. Kept
// anyway as a genuine concurrent-load correctness test (many threads polling at once around a real
// upload must never corrupt or lose it) and as documentation of the mechanism, not as confirmed
// proof this exact bug caused the reported symptom.
class TaskWorkerManagerConcurrentPollTest extends AbstractIntegrationTest {

    @Autowired
    private PhotoRepository photoRepository;

    @Test
    void concurrentPollLightPendingTasksNeverDoubleProcessesTheSameUploadTask() throws Exception {
        MockHttpSession session = loginAsAdmin();
        UUID spaceId = createSpace(session, "ConcurrentPoll-" + UUID.randomUUID());

        byte[] jpegBytes = generateTestJpeg();
        String uploadId = UUID.randomUUID().toString();
        MvcResult uploadResult = mockMvc.perform(multipart("/api/upload/chunk")
                        .file(new MockMultipartFile("chunk", "test-photo.jpg", "image/jpeg", jpegBytes))
                        .param("uploadId", uploadId)
                        .param("fileName", "test-photo.jpg")
                        .param("fileSize", String.valueOf(jpegBytes.length))
                        .param("chunkIndex", "0")
                        .param("totalChunks", "1")
                        .param("contentType", "image/jpeg")
                        .param("spaceId", spaceId.toString())
                        .session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        UUID taskId = UUID.fromString(response.get("task").get("id").asText());
        UUID photoId = UUID.fromString(response.get("task").get("photoId").asText());

        // Hammer pollLightPendingTasks() from many threads at once, right while the task is still
        // PENDING - reproduces the exact race window between findExecutableTasks' SELECT and
        // markQueued's status update that used to let more than one thread decide to submit the
        // same task before the fix.
        int threadCount = 64;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    taskWorkerManager.pollLightPendingTasks();
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        // waitForTaskCompletion throws if the task ends up FAILED, or times out if it's stuck on a
        // non-terminal status - either way this fails loudly if double-processing happened and lost
        // the race described above.
        waitForTaskCompletion(session, taskId);

        assertThat(photoRepository.findById(photoId)).isPresent();
    }
}

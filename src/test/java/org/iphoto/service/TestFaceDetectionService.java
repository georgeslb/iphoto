package org.iphoto.service;

import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

// Stand-in for the real FaceDetectionServiceImpl under the "test" profile (see its own
// @Profile("!test"), and FaceDetectionConfiguration's matching annotation on the two ONNX-backed
// beans it would otherwise need). Exists purely so TaskWorkerManagerImpl's mandatory
// FaceDetectionService constructor dependency still resolves - no test in this suite exercises
// real face detection (faces are inserted directly via FaceRepository, see FaceServiceTest's own
// comment), so a no-op is safe. detectAndSaveFaces still has to set a terminal status itself.
// exactly like the real implementation does - TaskWorkerManagerImpl.processTask's ISOLATE_FACES
// branch relies on the callee for that (unlike most other task types) rather than setting it
// afterward, so leaving it unset here would strand a task in PROCESSING forever if anything ever
// dispatches one against this stub (e.g. a scheduled pollHeavyTasks tick after some other test in
// the shared context activates heavy tasks).
@Service
@Profile("test")
class TestFaceDetectionService implements FaceDetectionService {

    @Override
    public void deleteFaces(PhotoEntity photo) {
        // no-op - not called by any production code path today (see FaceDetectionService's own
        // javadoc), and not exercised by any test either way.
    }

    @Override
    public void detectAndSaveFaces(TaskEntity task) {
        task.setStatus(TaskStatus.COMPLETED);
    }
}

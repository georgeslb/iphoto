package org.iphoto.configuration;

import ai.djl.MalformedModelException;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import org.iphoto.properties.IPhotoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.ResourceUtils;

import java.io.IOException;

// Models: InsightFace's `buffalo_l` pack (SCRFD detector, det_10g.onnx + ArcFace R50 embedding,
// w600k_r50.onnx) - see src/main/resources/models/ and the migration plan for why these replace
// the previous RetinaFace/face_feature models (both ONNX-only, hence the OnnxRuntime engine below
// instead of PyTorch). Vendored directly in the repo rather than downloaded at build time - the
// old models were pulled from an unversioned "test-models" demo bucket with no production
// guarantee; this is the actual fix for that, not just a different model.
//
// @Profile("!test") - these files are checked in via git-lfs (see .gitattributes), and GitHub
// Actions' actions/checkout doesn't fetch LFS content by default, so in CI they're just ~130-byte
// pointer text, not real ONNX. Criteria.loadModel() would fail fast trying to parse that. Nothing
// in the test suite exercises real face detection (see FaceServiceTest's own comment - faces are
// inserted directly via FaceRepository), so these beans simply aren't created under the "test"
// profile (see AbstractIntegrationTest) rather than trying to make them work against fake models.
// FaceDetectionServiceImpl (the only consumer of either bean) carries the same annotation; see
// TestFaceDetectionService for the test-profile stand-in that keeps TaskWorkerManagerImpl's
// FaceDetectionService dependency satisfied.
@Configuration
@Profile("!test")
public class FaceDetectionConfiguration {

    @Bean
    public ZooModel<Image, DetectedObjects> faceDetectionModel(IPhotoProperties properties)
            throws IOException, ModelNotFoundException, MalformedModelException {
        double confThresh = 0.5;
        double nmsThresh = 0.4;
        int inputSize = 640; // SCRFD's own reference det_size, see ScrfdDetectionTranslator
        ScrfdDetectionTranslator translator = new ScrfdDetectionTranslator(confThresh, nmsThresh, inputSize);

        // See IPhotoProperties.HeavyTasks#middleTaskThreads - at most this many detection calls can
        // run concurrently (TaskWorkerManager's shared middle pool, also used by HASH_IMAGE), so
        // ONNX Runtime's own intra-op thread count is divided down the same way IPhotoApplication
        // used to do for PyTorch, just as a plain per-model Criteria option now instead of a
        // JVM-wide system property that had to be set before Spring context refresh. Using the
        // pool's full size as the divisor even though HASH_IMAGE tasks can also occupy some of its
        // threads is a conservative but always-safe upper bound - worst case (the whole pool
        // running ISOLATE_FACES at once) is exactly what this guards against.
        int faceDetectionThreads = Math.max(1, properties.getHeavyTasks().getMiddleTaskThreads());
        int intraOpThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / faceDetectionThreads);

        Criteria<Image, DetectedObjects> criteria = Criteria.builder()
                .setTypes(Image.class, DetectedObjects.class)
                .optModelPath(ResourceUtils.getFile("classpath:models/det_10g.onnx").toPath())
                .optTranslator(translator)
                .optProgress(new ProgressBar())
                .optEngine("OnnxRuntime")
                .optOption("interOpNumThreads", "1")
                .optOption("intraOpNumThreads", String.valueOf(intraOpThreads))
                .build();
        return
                criteria.loadModel();
    }

    @Bean
    public ZooModel<Image,float[]> faceEmbeddedModel() throws ModelNotFoundException, MalformedModelException, IOException {
        // Hand-written translator, not DJL's built-in ImageFeatureExtractorFactory - see
        // ArcFaceEmbeddingTranslator's javadoc for why (its ToTensor/Normalize pipeline uses
        // NDArray arithmetic the ONNX Runtime engine's NDArray implementation doesn't support).
        Criteria<Image, float[]> criteria = Criteria.builder()
                .setTypes(Image.class, float[].class)
                .optModelPath(ResourceUtils.getFile("classpath:models/w600k_r50.onnx").toPath())
                .optTranslator(new ArcFaceEmbeddingTranslator())
                .optProgress(new ProgressBar())
                .optEngine("OnnxRuntime")
                .optOption("interOpNumThreads", "1")
                .optOption("intraOpNumThreads", "1")
                .build();
        return
                criteria.loadModel();
    }
}

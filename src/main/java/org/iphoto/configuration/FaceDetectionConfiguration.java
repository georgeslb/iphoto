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
import org.springframework.util.ResourceUtils;

import java.io.IOException;

// Models: InsightFace's `buffalo_l` pack (SCRFD detector, det_10g.onnx + ArcFace R50 embedding,
// w600k_r50.onnx) - see src/main/resources/models/ and the migration plan for why these replace
// the previous RetinaFace/face_feature models (both ONNX-only, hence the OnnxRuntime engine below
// instead of PyTorch). Vendored directly in the repo rather than downloaded at build time - the
// old models were pulled from an unversioned "test-models" demo bucket with no production
// guarantee; this is the actual fix for that, not just a different model.
@Configuration
public class FaceDetectionConfiguration {

    @Bean
    public ZooModel<Image, DetectedObjects> faceDetectionModel(IPhotoProperties properties)
            throws IOException, ModelNotFoundException, MalformedModelException {
        double confThresh = 0.5;
        double nmsThresh = 0.4;
        int inputSize = 640; // SCRFD's own reference det_size, see ScrfdDetectionTranslator
        ScrfdDetectionTranslator translator = new ScrfdDetectionTranslator(confThresh, nmsThresh, inputSize);

        // See IPhotoProperties.HeavyTasks#faceDetectionThreads - this many detection calls can run
        // concurrently (TaskWorkerManager's dedicated pool), so ONNX Runtime's own intra-op thread
        // count is divided down the same way IPhotoApplication used to do for PyTorch, just as a
        // plain per-model Criteria option now instead of a JVM-wide system property that had to be
        // set before Spring context refresh.
        int faceDetectionThreads = Math.max(1, properties.getHeavyTasks().getFaceDetectionThreads());
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

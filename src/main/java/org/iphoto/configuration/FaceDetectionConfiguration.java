package org.iphoto.configuration;

import ai.djl.MalformedModelException;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.translator.ImageFeatureExtractorFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ResourceUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class FaceDetectionConfiguration {

    @Bean
    public ZooModel<Image, DetectedObjects> faceDetectionModel() throws IOException, ModelNotFoundException, MalformedModelException {
        double confThresh = 0.85f;
        double nmsThresh = 0.45f;
        double[] variance = {0.1f, 0.2f};
        int topK = 5000;
        int[][] scales = {{16, 32}, {64, 128}, {256, 512}};
        int[] steps = {8, 16, 32};
        FaceDetectionTranslator translator =
                new FaceDetectionTranslator(confThresh, nmsThresh, variance, topK, scales, steps);

        Criteria<Image, DetectedObjects> criteria = Criteria.builder()
                .setTypes(Image.class, DetectedObjects.class)
                .optModelPath(ResourceUtils.getFile("classpath:retinaface.zip").toPath())
                // Load model from local file, e.g:
                .optModelName("retinaface") // specify model file prefix
                .optTranslator(translator)
                .optProgress(new ProgressBar())
                .optEngine("PyTorch") // Use PyTorch engine
                .build();
        return
                criteria.loadModel();
    }

    @Bean
    public ZooModel<Image,float[]> faceEmbeddedModel() throws ModelNotFoundException, MalformedModelException, IOException {
        List<Float> mean =
                Arrays.asList(
                        127.5f / 255.0f,
                        127.5f / 255.0f,
                        127.5f / 255.0f,
                        128.0f / 255.0f,
                        128.0f / 255.0f,
                        128.0f / 255.0f);
        String normalize = mean.stream().map(Object::toString).collect(Collectors.joining(","));

        Criteria<Image, float[]> criteria = Criteria.builder()
                .setTypes(Image.class, float[].class)
                .optModelUrls(
                        "https://resources.djl.ai/test-models/pytorch/face_feature.zip")
                .optModelName("face_feature") // specify model file prefix
                .optArgument("normalize", normalize)
                .optTranslatorFactory(new ImageFeatureExtractorFactory())
                .optProgress(new ProgressBar())
                .optEngine("PyTorch") // Use PyTorch engine
                .build();
        return
                criteria.loadModel();
    }
}

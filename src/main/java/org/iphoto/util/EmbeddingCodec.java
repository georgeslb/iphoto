package org.iphoto.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

// Shared float[] <-> byte[] codec for face embeddings - previously duplicated (BIG_ENDIAN in both
// cases) between FaceDetectionService (encode, at detection time) and FaceRecognitionService
// (decode, at matching time). Extracted here since PersonRecognitionService now also needs to
// decode/encode embeddings, to maintain PersonEmbeddingBucketEntity's running-average vector.
public final class EmbeddingCodec {

    private EmbeddingCodec() {
    }

    public static byte[] toBytes(float[] floatArray) {
        ByteBuffer buffer = ByteBuffer.allocate(4 * floatArray.length);
        buffer.order(ByteOrder.BIG_ENDIAN);
        for (float v : floatArray) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    public static float[] toFloats(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new float[0];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.BIG_ENDIAN);
        FloatBuffer floatBuffer = buffer.asFloatBuffer();
        float[] floatArray = new float[floatBuffer.remaining()];
        floatBuffer.get(floatArray);
        return floatArray;
    }
}

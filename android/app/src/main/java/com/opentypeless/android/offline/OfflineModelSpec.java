package com.opentypeless.android.offline;

import java.net.URI;

public record OfflineModelSpec(
        String id,
        String displayName,
        String revision,
        Artifact model,
        Artifact tokens) {

    public record Artifact(String fileName, URI uri, long bytes, String sha256) {
        public Artifact {
            if (fileName == null || !fileName.matches("[a-zA-Z0-9._-]+")) {
                throw new IllegalArgumentException("Unsafe model filename");
            }
            if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Model downloads require HTTPS");
            }
            if (bytes <= 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Model size and SHA-256 are required");
            }
        }
    }

    public static final String SENSEVOICE_REVISION =
            "2365baeacb507f821a0c8120fcee3d484dba7a07";

    public static final OfflineModelSpec QUALITY = new OfflineModelSpec(
            "sensevoice-small-int8-2024-07-17",
            "SenseVoice Small INT8",
            SENSEVOICE_REVISION,
            new Artifact(
                    "model.int8.onnx",
                    URI.create("https://huggingface.co/csukuangfj/"
                            + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/"
                            + SENSEVOICE_REVISION + "/model.int8.onnx"),
                    239_233_841L,
                    "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
            new Artifact(
                    "tokens.txt",
                    URI.create("https://huggingface.co/csukuangfj/"
                            + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/"
                            + SENSEVOICE_REVISION + "/tokens.txt"),
                    315_894L,
                    "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"));

    public long downloadBytes() {
        return model.bytes() + tokens.bytes();
    }
}

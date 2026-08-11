package com.opentypeless.android.offline;

import java.net.URI;

/** Revision-pinned semantic punctuation model for Chinese and English transcripts. */
public record OfflinePunctuationModelSpec(
        String id,
        String displayName,
        String revision,
        OfflineModelSpec.Artifact model) {

    /**
     * The transport mirror contains the exact model bytes published in the upstream sherpa-onnx
     * punctuation-models release. The pinned SHA-256 below was independently verified against the
     * official k2-fsa tarball, so a mirror replacement cannot change installed weights.
     */
    public static final String MIRROR_REVISION =
            "fc2be466e3c11927b306a31cdee23c9c38da44cc";

    public static final OfflinePunctuationModelSpec ZH_EN = new OfflinePunctuationModelSpec(
            "ct-transformer-punctuation-zh-en-int8-2024-04-12",
            "CT-Transformer zh/en punctuation INT8",
            MIRROR_REVISION,
            new OfflineModelSpec.Artifact(
                    "model.int8.onnx",
                    URI.create("https://huggingface.co/lorneluo/"
                            + "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-"
                            + "2024-04-12-int8/resolve/"
                            + MIRROR_REVISION + "/model.int8.onnx"),
                    75_519_198L,
                    "65a3fb9f5ad7bfb96bf69e0dc4481df97f6ee60513c1d94ce981ba6effd524b1"));

    public long downloadBytes() {
        return model.bytes();
    }
}

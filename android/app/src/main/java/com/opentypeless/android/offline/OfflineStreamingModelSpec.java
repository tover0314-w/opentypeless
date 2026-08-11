package com.opentypeless.android.offline;

import java.net.URI;

/** Revision-pinned first-pass model used only for live, replaceable transcripts. */
public record OfflineStreamingModelSpec(
        String id,
        String displayName,
        String revision,
        OfflineModelSpec.Artifact encoder,
        OfflineModelSpec.Artifact decoder,
        OfflineModelSpec.Artifact tokens) {

    public static final String PARAFORMER_REVISION =
            "8e40c43232a1c5c66c82111efc5820d3accca11b";
    private static final String REPOSITORY =
            "https://huggingface.co/csukuangfj/"
                    + "sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/";

    public static final OfflineStreamingModelSpec REALTIME = new OfflineStreamingModelSpec(
            "streaming-paraformer-bilingual-zh-en-int8-2023-08-14",
            "Streaming Paraformer zh/en INT8",
            PARAFORMER_REVISION,
            new OfflineModelSpec.Artifact(
                    "encoder.int8.onnx",
                    URI.create(REPOSITORY + PARAFORMER_REVISION + "/encoder.int8.onnx"),
                    165_462_184L,
                    "81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a"),
            new OfflineModelSpec.Artifact(
                    "decoder.int8.onnx",
                    URI.create(REPOSITORY + PARAFORMER_REVISION + "/decoder.int8.onnx"),
                    71_664_561L,
                    "f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f"),
            new OfflineModelSpec.Artifact(
                    "tokens.txt",
                    URI.create(REPOSITORY + PARAFORMER_REVISION + "/tokens.txt"),
                    75_756L,
                    "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6"));

    public long downloadBytes() {
        return encoder.bytes() + decoder.bytes() + tokens.bytes();
    }
}

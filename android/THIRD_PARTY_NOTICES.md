# Android third-party notices

OpenTypeless Android 0.3 is an MIT-licensed clean-room implementation. It does not copy code or
bundle model weights from Typeless, Gboard, FUTO Voice Input, HeliBoard, Sayboard, whisperIME, or
Offline Voice Input.

The production APK contains the following revision-pinned runtime components:

- An OpenTypeless ASR-only build of sherpa-onnx 1.13.4 at commit
  `142807252687d81b40d6315f23470a1512a00de3` — Apache License 2.0. It is built
  with Android NDK r27d for `arm64-v8a` and `x86_64`; TTS, speaker diarization, the C API, and
  WebSocket support are disabled. Only `libsherpa-onnx-jni.so` and `libonnxruntime.so` are
  packaged. The deterministic AAR SHA-256 is
  `35af2790bfcb39a1bfe6d0d495193b7fadc367c5c6f07e5e95996ba210cb9196`.
- ONNX Runtime 1.27.0 native libraries — MIT License, Microsoft. The pinned input archive SHA-256
  is `a78f303a26b5e75c84c8b2a97fa2ddb400b2d1b5e069bec19aa229ccd3597fdb`.
- Native build dependencies: kaldi-native-fbank 1.22.3, kaldi-decoder 0.3.0, kaldifst 1.8.0,
  OpenFST 1.8.5-2026-04-11, simple-sentencepiece 0.7 (Apache License 2.0); Eigen 5.0.1
  (primarily MPL 2.0 with the bundled compatible third-party notices); KISS FFT `febd4cae`
  (BSD-3-Clause); and nlohmann/json 3.12.0 (MIT).
- Kotlin standard library 1.7.20 — Apache License 2.0, JetBrains and Kotlin contributors.
- The KBD-010 picker includes a manually curated 168-sequence subset of Unicode Emoji 15.1 data —
  Unicode License v3 (`Unicode-3.0`), Unicode, Inc. No Unicode font or glyph artwork is bundled.

The Apache, MIT, MPL, BSD, MINPACK, model-license, copyright, attribution, source, and revision
texts are bundled in `res/raw/legal_notices.txt` and `res/raw/offline_asr_runtime_licenses.txt`;
both are reachable together from the app's settings screen. The runtime builder verifies fixed
input hashes, refuses the wrong NDK revision, records per-ABI native hashes inside the AAR, and
rejects eSpeak/Piper/TTS symbols before packaging. File-prefix maps and a disabled nondeterministic
ELF Build ID make clean builds byte-reproducible; the recorded SHA-256 identifies each native
artifact. No eSpeak-NG or Piper code is shipped.

Speech selected as **Android on-device** or **Android system service** is provided by the
recognition service installed on the user's device; that service and any language models it
downloads have their own terms and privacy behavior.

Build and test dependencies are not embedded as application runtime code:

- Android Gradle Plugin and AndroidX Test — Apache License 2.0.
- JUnit 4 — Eclipse Public License 1.0.
- OkHttp MockWebServer — Apache License 2.0, test scope only.
- JSON-java — public domain, test scope only.

No speech or language model is bundled in the APK. If the user explicitly downloads the optional
quality model, OpenTypeless retrieves the fixed SenseVoice Small INT8 conversion revision
`2365baeacb507f821a0c8120fcee3d484dba7a07`, verifies its model/token sizes and SHA-256, preserves
the SenseVoice/FunAudioLLM/FunASR/Alibaba model names and attribution, and presents the FunASR Model
Open Source License Agreement before consent. The exact model source and license snapshot are also
included in the in-app legal notices. Release remains gated on final legal review of the conversion
artifact's model-card/license history.

The optional offline live-text package is independently downloaded from the Apache-2.0
`csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en` revision
`8e40c43232a1c5c66c82111efc5820d3accca11b`. OpenTypeless pins and verifies the INT8 encoder
(`81a70226…90e9a`), decoder (`f3cca9f7…594f`), and tokens (`59aba887…6e6`) before installation.
The model card attributes the converted source to ModelScope
`damo/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8404-online`. It is used only as the
replaceable first pass; SenseVoice remains the quality final pass.

The optional semantic-punctuation package is the 75,519,198-byte INT8 CT-Transformer model from
the official sherpa-onnx punctuation-models release. Its source is ModelScope
`iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch`, which declares Apache License 2.0.
OpenTypeless uses the revision-pinned Hugging Face transport mirror
`fc2be466e3c11927b306a31cdee23c9c38da44cc` only after verifying the model against the official
release digest `65a3fb9f…24b1`. The model runs in a private text-only process; its candidate is
discarded if a case-sensitive lexical, paragraph, number, URL, email, or code-literal gate detects
anything beyond punctuation changes.

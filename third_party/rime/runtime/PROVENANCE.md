# Route A native provenance seam

This directory freezes the source identity, selected license branch, Android build configuration, and actual static-link closure for the Route A Rime runtime used by RIM-002. It is not a substitute for the release `THIRD_PARTY_NOTICES`, SBOM, source bundle, or `NATIVE_LINK_MANIFEST` required by later release tasks.

## Top-level runtime

- librime 1.17.0
  - repository: `https://github.com/rime/librime.git`
  - commit: `33e78140250125871856cdc5b42ddc6a5fcd3cd4`
  - Git archive SHA-256: `afdafcd9322dd184123f7b985030183eadcc1a8a01b60e362a4fc22dd7be45be`
  - license: BSD-3-Clause
  - license SHA-256: `f67d27a6d2d586fcfed4b4c886a83747095396a39b6641e18e855086be2ec400`
  - `ENABLE_EXTERNAL_PLUGINS=OFF`; selected modules are only core, default, deployer, dict, gears, levers, and levers_stdbool

## Static dependency closure

- Boost 1.89.0: official `boost-1.89.0-cmake.tar.xz`, 102,078,704 bytes, SHA-256 `67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74`; BSL-1.0 license SHA-256 `c9bff75738922193e67fa726fa225535870d2aa1059f91452c411736284ad566`.
- yaml-cpp at `2f86d13775d119edbb69af52e5f566fd65c6953b`, archive SHA-256 `062ee35711b24bc4ff6d6d36b61a9ba189dd03202c4687fcd614763e9b65fd1e`; MIT license SHA-256 `aa6fcc27be034e41e21dd832f9175bfe694a48491d9e14ff0fa278e19ad14f1b`.
- LevelDB 1.23 at `99b3c03b3284f5886f9ef9a4ef703d57373e61be`, archive SHA-256 `1ab29b567293da2be4c24f09e9fc7028af48e854fce70b90bcb9768f3fb67fa9`; BSD-3-Clause license SHA-256 `ccc19f1da0798ed666609b65a5b44dd8b3abe6fc08b9c0592eb76e82e174db19`. External crc32c and snappy are not selected.
- marisa-trie v0.3.1 at `3e87d53b78e15f2f43783d5e376561a8c9722051`, archive SHA-256 `7173829621ae9685f9fd83c16ac9a70e31531f93dddf31916797eb563b087944`; COPYING SHA-256 `edf58dab34c3dc239ba4ba2d5d3d844d8c5b442aa4dd1149fac81b2d8c6cb8d1`. The product explicitly selects the BSD-2-Clause alternative, not LGPL-2.1-or-later.
- OpenCC 1.1.9 at `556ed22496d650bd0b13b6c163be9814637970ae`, upstream archive SHA-256 `6ae5e77fd9091fb55643f5bb8dba4e0e623adbdd6ee96b9d589baa13fc8ae10a`; Apache-2.0 license SHA-256 `b534e465949558eec2597b04f5092b5e161236a68dfbfd04d547592ac3964308`. The pinned `opencc-android.patch` uses the audited external marisa archive and omits host-only data and tools. No OpenCC `.ocd`, dictionary, or conversion data is packaged.
  - `opencc-android.patch` SHA-256: `323111c408c97c51e88b401eca8902cacfd8f5293efd418472eb0938f15b7c5b`
  - patched root `CMakeLists.txt` SHA-256: `7071f52c4a32c1ecefa226853cc016113cd6e85a9063bac4a6627a1ad1756277`
  - patched `src/CMakeLists.txt` SHA-256: `0480207b10e9e283f4a4dfa3dab443043c0bb5fe2caaa1e0fd9606f0f18b3f9d`
- RapidJSON 1.1.0 is compiled from OpenCC's vendored subtree at `a2f02f13d765d364e996c3558c49260775202067`, subtree archive SHA-256 `69c20a2bee9ce27f7f955aa2fd6864133f24a288fdaabbbcfb6ca7aaef4bbb5c`; MIT inline license.
- darts-clone 0.32 is compiled from librime's vendored `include/darts.h`, SHA-256 `09de3a97908f05a4ab40ef3aba50e1686f84cbcb4c82942c02201c6dd9fb0fc2`; BSD-3-Clause license SHA-256 `11bf6ca180d01e60656cd3cbe65cff95b738afe4ca5a8a66b9dd1ec594a41d2f`.
- utf8cpp 3.2.5 is compiled from librime's fixed vendored subtree, archive SHA-256 `26d35a2ca013a9d8837fb9513a7837c82ae4dd442f31edf3f833b2ad11f15776`; BSL-1.0 inline license.

No GPL/LGPL alternative is selected. Symbol/string scans of both ABI outputs find no octagram, Lua, `luaopen`, `rime-lua`, or GPL/LGPL runtime marker.

## Android toolchain runtime

- Android NDK 26.1.10909125 / r26b, official tag commit `51112d4334818cfb9ee0c4dcc55355eb5ebd158e`, Clang 17.0.2 build 10552028.
- `source.properties` SHA-256: `96cddd3dea11a24dc4f563280e350fe566f1477d64cc358967838a90c66a23d1`.
- `NOTICE` SHA-256: `8ef75b1329afc86cc26b44fd38a4ece8c78266581a32c5e57d6e7b11721183e1`.
- `NOTICE.toolchain` SHA-256: `d503f647f76ba8dc538eeeb5b3851f61d46a7d1f7651fe580dab8f2fbeecbfe5`.
- selected license: Apache-2.0 WITH LLVM-exception; the complete toolchain notice must be carried by release notices.
- `libc++.a` is a linker script selecting both `libc++_static.a` and `libc++abi.a`. compiler-rt builtins and libunwind are also actual link inputs.

Pinned static archive SHA-256 values:

| ABI | libc++_static | libc++abi | compiler-rt builtins | libunwind |
| --- | --- | --- | --- | --- |
| arm64-v8a | `6ca7a759b2742bba79654923b0f410c946e1967e744e1442d1e72ab1045534e8` | `5e1a821b418e1e58c1d1cab03da08b63fcb30fb60341a5576faef5d3cf775358` | `64609865a12848622f56d813228556a0cbebbddd1763d1c097194176b1141ad0` | `df0a38dace2c0b1b4cec84fb8c9300e03d950be501a1773932baed777f25e3be` |
| x86_64 | `0d081151a9d3b58458bb2926a56ee34f0eb62694f90f09fda9043767efa13798` | `e4bbceb71eb94ed36f18d7337d0aeeaa3976a737345bc7d1b934dd9602dbe81b` | `f79a7202f16249436f3d95382a780da292a18bc9e6fe990c48507d54bc214e6a` | `c8f3b6495002e0952c8900df6c67f314aa5e737cdd08dec4db3398e5d1982206` |

## Build and output identity

`build-rime-android.sh` verifies the pinned revisions, rejects unaudited dependency worktree changes, applies and verifies the exact OpenCC patch, builds both `librime.so` and the JNI adapter, strips them, rejects host-path leakage, and records the fixed API 26, CMake 4.0.2, NDK r26b two-ABI output. Its RIM-006 SHA-256 is `aff731dcedca7f0d9dddde920bd3df07758ff6c0aa7a2ead92da07864f5969ec`. It intentionally performs no download: KSP-011 supplies the separately verified source acquisition and maintained upstream queue.

RIM-006 rebuilt both ABIs source-first from the pinned trees with the exact script above. The JNI adapter adds only the closed `simplification`, `ascii_punct`, and `full_shape` option vocabulary; every option write is read back from librime and restored after a bounded session reset. It does not add assets, a Schema, vocabulary, network access, Android `Context`, `InputConnection`, or editor authority.

| ABI | Artifact | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| arm64-v8a | `librime.so` | 4,381,752 | `1e94fd8ae942bc6b146ec5febe4c26913c9a5feefd761cd496defd55873e6394` |
| arm64-v8a | `libopentypeless_rime.so` | 39,736 | `eb68314bbd07a10cdcdb6fcbb158beaec71d24d392f7a6d75c221ac4eed416a3` |
| x86_64 | `librime.so` | 4,384,720 | `e7252fb56d346a30aee76800b166987bb83e4c36b77d8a1afa44019774a9a7c8` |
| x86_64 | `libopentypeless_rime.so` | 38,744 | `7718849a0ac5146f63ed4219ca71c82de8122c0a0fbd808490a3ff70b06ac3e2` |

`build-runtime-aar.sh` SHA-256 `5467fef2b188a431f0b2f05d605b188abdf2b23810b37686a63e4ce6a7d566ad` packages the four exact libraries, the editor-capability-free `RimeAdapter` source (`662dadec1c1fb85552f38be7e272243eb4cd97a46ddd90a14dcbf313ceaed386`), and the fixed notice (`48e9a2f2e0d6da72534275c0ea44605e4c25bcf5ff1349702f8f18807210a707`) into `android/app/libs/opentypeless-rime-runtime-1.17.0.aar`: 8,856,597 bytes, SHA-256 `5fce6f0e5356d1f80cc080d8ca7f55e8177caa8cbc28538ebde7e69bd1665d2d`. The RIM-008 adapter adds a prepared-session entry point and consumes the bounded commit that a table Schema may emit during ordinary key processing; this preserves native fixed-length auto-selection instead of mistaking the now-empty composition for cancellation. The app only uses the prepared entry point after an exact imported-manifest digest matches the app-private deployment marker, and falls back to one full deployment if the prepared cache fails. The adapter still contains no assets, Schema, vocabulary, conversion data, learned database, network code, Android `Context`, `InputConnection`, or editor writer.

The product build verifies each packaged APK entry against this table. The current APK native inventory also includes the existing Sherpa ASR pair for both selected ABIs; KSP-012's exact artifact policy covers the complete eight-entry set.

The 2026-08-16 RIM-002 strict offline clean build produced debug APK SHA-256 `151d3357d4257fe6fd2031d800e7b37717aeb28d3fe237b0d4d04c6440165fd9` (65,386,500 bytes), unsigned release APK SHA-256 `a475b525e5109a457b1ff33fa648eab46ceafe5e4a4edf122592c5a24f576540` (63,541,645 bytes), and AndroidTest APK SHA-256 `c3b736214560b0ff77a5f048958a3dc078fbc98065aafc896e4c95669546d3b5` (1,076,899 bytes). Exact resource scans found all eight expected product native entries, zero language assets, zero real Xiaohè resources, and zero violations.

The exact debug/test pair installed successfully on Xiaomi 10 Ultra arm64-v8a/API 33 and Android Emulator arm64-v8a/API 35. `RimeNativeRuntimeInstrumentedTest` loaded both libraries, initialized the engine with bounded no-backup directories, read back version `1.17.0`, finalized it, and passed `OK (1 test)` on both devices. The x86_64 bytes are byte-identical to the KSP-009 safety artifact that previously passed the API 26 x86_64 dynamic matrix; RIM-002 did not rerun that slow TCG guest.

KSP-011 must preserve this build recipe and patch in the maintained source-first build queue. SEC/REL tasks must assemble all exact license texts, source offer/bundle, SBOM, and native link manifest; they must not rediscover or guess these pinned inputs.

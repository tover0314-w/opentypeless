#!/bin/sh
# SPDX-License-Identifier: MIT
# Copyright (c) 2025 OpenTypeless Contributors

set -eu

ABI=${1:?usage: build-rime-android.sh ABI}
SOURCE_ROOT=${SOURCE_ROOT:?SOURCE_ROOT must contain the pinned source trees}
OUTPUT_ROOT=${OUTPUT_ROOT:?OUTPUT_ROOT must be a task-specific build directory}
ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JNI_SOURCE_DIR="$SCRIPT_DIR/jni"

case "$ABI" in
  arm64-v8a|x86_64) ;;
  *) echo "unsupported ABI" >&2; exit 2 ;;
esac

CMAKE="$ANDROID_SDK_ROOT/cmake/4.0.2/bin/cmake"
NINJA="$ANDROID_SDK_ROOT/cmake/4.0.2/bin/ninja"
TOOLCHAIN="$ANDROID_SDK_ROOT/ndk/26.1.10909125/build/cmake/android.toolchain.cmake"
LLVM_BIN="$ANDROID_SDK_ROOT/ndk/26.1.10909125/toolchains/llvm/prebuilt/darwin-x86_64/bin"
LLVM_STRIP="$LLVM_BIN/llvm-strip"
LLVM_READELF="$LLVM_BIN/llvm-readelf"
PREFIX="$OUTPUT_ROOT/prefix/$ABI"
COMMON="-G Ninja -DCMAKE_MAKE_PROGRAM=$NINJA -DCMAKE_TOOLCHAIN_FILE=$TOOLCHAIN -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_INSTALL_PREFIX=$PREFIX"
REPRO_FLAGS="-ffile-prefix-map=$SOURCE_ROOT=/opentypeless/source -fmacro-prefix-map=$SOURCE_ROOT=/opentypeless/source -fdebug-prefix-map=$SOURCE_ROOT=/opentypeless/source -ffile-prefix-map=$OUTPUT_ROOT=/opentypeless/build -fmacro-prefix-map=$OUTPUT_ROOT=/opentypeless/build -fdebug-prefix-map=$OUTPUT_ROOT=/opentypeless/build -ffile-prefix-map=$SCRIPT_DIR=/opentypeless/runtime -fmacro-prefix-map=$SCRIPT_DIR=/opentypeless/runtime -fdebug-prefix-map=$SCRIPT_DIR=/opentypeless/runtime"
export CFLAGS="$REPRO_FLAGS"
export CXXFLAGS="$REPRO_FLAGS"

require_git_head() {
  path=$1
  expected=$2
  actual=$(git -C "$path" rev-parse HEAD)
  if [ "$actual" != "$expected" ]; then
    echo "pinned source revision mismatch: $path" >&2
    exit 3
  fi
}

require_clean_git_worktree() {
  path=$1
  if ! git -C "$path" diff --quiet ||
     ! git -C "$path" diff --cached --quiet ||
     [ -n "$(git -C "$path" ls-files --others --exclude-standard)" ]; then
    echo "pinned source worktree is not clean: $path" >&2
    exit 3
  fi
}

require_sha256() {
  path=$1
  expected=$2
  actual=$(shasum -a 256 "$path" | awk '{print $1}')
  if [ "$actual" != "$expected" ]; then
    echo "pinned source digest mismatch: $path" >&2
    exit 3
  fi
}

require_git_head "$SOURCE_ROOT/librime" 33e78140250125871856cdc5b42ddc6a5fcd3cd4
require_git_head "$SOURCE_ROOT/librime/deps/yaml-cpp" 2f86d13775d119edbb69af52e5f566fd65c6953b
require_git_head "$SOURCE_ROOT/librime/deps/leveldb" 99b3c03b3284f5886f9ef9a4ef703d57373e61be
require_git_head "$SOURCE_ROOT/librime/deps/marisa-trie" 3e87d53b78e15f2f43783d5e376561a8c9722051
require_git_head "$SOURCE_ROOT/librime/deps/opencc" 556ed22496d650bd0b13b6c163be9814637970ae
if ! git -C "$SOURCE_ROOT/librime" diff --quiet --ignore-submodules=all ||
   ! git -C "$SOURCE_ROOT/librime" diff --cached --quiet --ignore-submodules=all ||
   [ -n "$(git -C "$SOURCE_ROOT/librime" ls-files --others --exclude-standard)" ]; then
  echo "pinned librime worktree has unaudited changes" >&2
  exit 3
fi
require_clean_git_worktree "$SOURCE_ROOT/librime/deps/yaml-cpp"
require_clean_git_worktree "$SOURCE_ROOT/librime/deps/leveldb"
require_clean_git_worktree "$SOURCE_ROOT/librime/deps/marisa-trie"
require_sha256 "$SOURCE_ROOT/boost-1.89.0/LICENSE_1_0.txt" c9bff75738922193e67fa726fa225535870d2aa1059f91452c411736284ad566
require_sha256 "$SOURCE_ROOT/boost-1.89.0/libs/config/include/boost/version.hpp" 24fc8cb1636e0fa167c8f7e1cfef99f3642e5928918e1671fe5585bbffe02b07
require_sha256 "$ANDROID_SDK_ROOT/ndk/26.1.10909125/source.properties" 96cddd3dea11a24dc4f563280e350fe566f1477d64cc358967838a90c66a23d1

expected_opencc_patch=323111c408c97c51e88b401eca8902cacfd8f5293efd418472eb0938f15b7c5b
actual_opencc_patch=$(shasum -a 256 "$SCRIPT_DIR/opencc-android.patch" | awk '{print $1}')
if [ "$actual_opencc_patch" != "$expected_opencc_patch" ]; then
  echo "OpenCC patch digest mismatch" >&2
  exit 3
fi
if git -C "$SOURCE_ROOT/librime/deps/opencc" apply --reverse --check "$SCRIPT_DIR/opencc-android.patch" >/dev/null 2>&1; then
  : # Already at the exact audited patched state.
else
  git -C "$SOURCE_ROOT/librime/deps/opencc" apply --check "$SCRIPT_DIR/opencc-android.patch"
  git -C "$SOURCE_ROOT/librime/deps/opencc" apply "$SCRIPT_DIR/opencc-android.patch"
fi
require_sha256 "$SOURCE_ROOT/librime/deps/opencc/CMakeLists.txt" 7071f52c4a32c1ecefa226853cc016113cd6e85a9063bac4a6627a1ad1756277
require_sha256 "$SOURCE_ROOT/librime/deps/opencc/src/CMakeLists.txt" 0480207b10e9e283f4a4dfa3dab443043c0bb5fe2caaa1e0fd9606f0f18b3f9d
opencc_changed=$(
  {
    git -C "$SOURCE_ROOT/librime/deps/opencc" diff --name-only
    git -C "$SOURCE_ROOT/librime/deps/opencc" diff --cached --name-only
    git -C "$SOURCE_ROOT/librime/deps/opencc" ls-files --others --exclude-standard
  } | LC_ALL=C sort -u
)
expected_opencc_changed=$(printf '%s\n%s' CMakeLists.txt src/CMakeLists.txt)
if [ "$opencc_changed" != "$expected_opencc_changed" ]; then
  echo "OpenCC worktree contains unaudited changes" >&2
  exit 3
fi

configure_build_install() {
  name=$1
  source=$2
  shift 2
  # shellcheck disable=SC2086
  LC_ALL=C "$CMAKE" -S "$source" -B "$OUTPUT_ROOT/build/$name-$ABI" $COMMON \
    -DCMAKE_C_FLAGS="$REPRO_FLAGS" \
    -DCMAKE_CXX_FLAGS="$REPRO_FLAGS" \
    "$@"
  LC_ALL=C "$CMAKE" --build "$OUTPUT_ROOT/build/$name-$ABI" --target install -j 8
}

configure_build_install boost "$SOURCE_ROOT/boost-1.89.0" \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF \
  -DBOOST_INCLUDE_LIBRARIES="regex;signals2;algorithm;scope;scope_exit;interprocess;unordered;uuid;range;crc"
configure_build_install yaml "$SOURCE_ROOT/librime/deps/yaml-cpp" \
  -DBUILD_SHARED_LIBS=OFF -DYAML_BUILD_SHARED_LIBS=OFF \
  -DYAML_CPP_BUILD_CONTRIB=OFF -DYAML_CPP_BUILD_TESTS=OFF \
  -DYAML_CPP_BUILD_TOOLS=OFF -DYAML_CPP_INSTALL=ON
configure_build_install leveldb "$SOURCE_ROOT/librime/deps/leveldb" \
  -DBUILD_SHARED_LIBS=OFF -DLEVELDB_BUILD_TESTS=OFF \
  -DLEVELDB_BUILD_BENCHMARKS=OFF -DLEVELDB_INSTALL=ON
configure_build_install marisa "$SOURCE_ROOT/librime/deps/marisa-trie" \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF -DENABLE_TOOLS=OFF \
  -DENABLE_NATIVE_CODE=OFF
configure_build_install opencc "$SOURCE_ROOT/librime/deps/opencc" \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_DOCUMENTATION=OFF \
  -DENABLE_GTEST=OFF -DENABLE_BENCHMARK=OFF -DBUILD_PYTHON=OFF \
  -DENABLE_DARTS=OFF -DUSE_SYSTEM_MARISA=ON \
  -DSHARE_INSTALL_PREFIX=share \
  -DCMAKE_PREFIX_PATH="$PREFIX" \
  -DCMAKE_INCLUDE_PATH="$PREFIX/include" \
  -DCMAKE_LIBRARY_PATH="$PREFIX/lib" \
  -DLIBMARISA="$PREFIX/lib/libmarisa.a" \
  -DMARISA_INCLUDE_DIR="$PREFIX/include" \
  -DCMAKE_CXX_FLAGS="-I$PREFIX/include $REPRO_FLAGS"

# shellcheck disable=SC2086
LC_ALL=C "$CMAKE" -S "$SOURCE_ROOT/librime" -B "$OUTPUT_ROOT/build/rime-shared-$ABI" $COMMON \
  -DCMAKE_C_FLAGS="$REPRO_FLAGS" \
  -DCMAKE_CXX_FLAGS="$REPRO_FLAGS" \
  -DCMAKE_PREFIX_PATH="$PREFIX" \
  -DCMAKE_INCLUDE_PATH="$PREFIX/include" \
  -DCMAKE_LIBRARY_PATH="$PREFIX/lib" \
  -DBoost_DIR="$PREFIX/lib/cmake/Boost-1.89.0" \
  -DBoost_INCLUDE_DIR="$PREFIX/include" \
  -DYamlCpp_INCLUDE_PATH="$PREFIX/include" \
  -DYamlCpp_NEW_API="$PREFIX/include" \
  -DYamlCpp_LIBRARY="$PREFIX/lib/libyaml-cpp.a" \
  -DLevelDb_INCLUDE_PATH="$PREFIX/include" \
  -DLevelDb_LIBRARY="$PREFIX/lib/libleveldb.a" \
  -DMarisa_INCLUDE_PATH="$PREFIX/include" \
  -DMarisa_LIBRARY="$PREFIX/lib/libmarisa.a" \
  -DOpencc_INCLUDE_PATH="$PREFIX/include" \
  -DOpencc_LIBRARY="$PREFIX/lib/libopencc.a" \
  -DBUILD_SHARED_LIBS=ON -DBUILD_STATIC=ON \
  -DBUILD_TEST=OFF -DBUILD_DATA=OFF -DBUILD_SAMPLE=OFF \
  -DBUILD_SEPARATE_LIBS=OFF -DENABLE_LOGGING=OFF \
  -DENABLE_EXTERNAL_PLUGINS=OFF -DENABLE_TIMESTAMP=OFF
LC_ALL=C "$CMAKE" --build "$OUTPUT_ROOT/build/rime-shared-$ABI" --target rime -j 8

RIME_SHARED_LIBRARY="$OUTPUT_ROOT/build/rime-shared-$ABI/lib/librime.so"
JNI_BUILD="$OUTPUT_ROOT/build/opentypeless-rime-$ABI"
# shellcheck disable=SC2086
LC_ALL=C "$CMAKE" -S "$JNI_SOURCE_DIR" -B "$JNI_BUILD" $COMMON \
  -DCMAKE_C_FLAGS="$REPRO_FLAGS" \
  -DCMAKE_CXX_FLAGS="$REPRO_FLAGS" \
  -DRIME_SHARED_LIBRARY="$RIME_SHARED_LIBRARY" \
  -DRIME_SOURCE_DIR="$SOURCE_ROOT/librime"
LC_ALL=C "$CMAKE" --build "$JNI_BUILD" --target opentypeless_rime -j 8

ARTIFACT_DIR="$OUTPUT_ROOT/artifacts/$ABI"
mkdir -p "$ARTIFACT_DIR"
"$LLVM_STRIP" --strip-unneeded "$RIME_SHARED_LIBRARY" -o "$ARTIFACT_DIR/librime.so"
"$LLVM_STRIP" --strip-unneeded "$JNI_BUILD/libopentypeless_rime.so" -o "$ARTIFACT_DIR/libopentypeless_rime.so"

"$LLVM_READELF" -d "$ARTIFACT_DIR/librime.so" | grep -q 'Shared library: \[libc.so\]'
"$LLVM_READELF" -d "$ARTIFACT_DIR/libopentypeless_rime.so" | grep -q 'Shared library: \[librime.so\]'
for library in "$ARTIFACT_DIR/librime.so" "$ARTIFACT_DIR/libopentypeless_rime.so"; do
  if strings "$library" | grep -F -e "$SOURCE_ROOT" -e "$OUTPUT_ROOT" -e "$SCRIPT_DIR" >/dev/null; then
    echo "host build path leaked into native artifact: $library" >&2
    exit 4
  fi
done

case "$ABI" in
  arm64-v8a)
    expected_rime=1e94fd8ae942bc6b146ec5febe4c26913c9a5feefd761cd496defd55873e6394
    expected_adapter=eb68314bbd07a10cdcdb6fcbb158beaec71d24d392f7a6d75c221ac4eed416a3
    ;;
  x86_64)
    expected_rime=e7252fb56d346a30aee76800b166987bb83e4c36b77d8a1afa44019774a9a7c8
    expected_adapter=7718849a0ac5146f63ed4219ca71c82de8122c0a0fbd808490a3ff70b06ac3e2
    ;;
esac
require_sha256 "$ARTIFACT_DIR/librime.so" "$expected_rime"
require_sha256 "$ARTIFACT_DIR/libopentypeless_rime.so" "$expected_adapter"
shasum -a 256 "$ARTIFACT_DIR/librime.so" "$ARTIFACT_DIR/libopentypeless_rime.so"

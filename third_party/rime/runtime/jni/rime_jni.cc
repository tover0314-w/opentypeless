// SPDX-License-Identifier: MIT
// Copyright (c) 2025 OpenTypeless Contributors

#include <jni.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>

#include "rime_api.h"

namespace {

constexpr std::size_t kMaxInputBytes = 128;
constexpr std::size_t kMaxPathBytes = 4096;
constexpr std::size_t kMaxRimeTextBytes = 1024;
constexpr std::size_t kMaxRimeTextCodePoints = 256;
constexpr std::size_t kMaxVersionBytes = 128;
constexpr std::size_t kMaxSchemaIdBytes = 128;
constexpr std::size_t kMaxOptionNameBytes = 32;
constexpr int kMaxCandidates = 16;

std::mutex g_mutex;
RimeApi* g_api = nullptr;
bool g_initialized = false;

void ThrowState(JNIEnv* env, const char* message) {
  jclass type = env->FindClass("java/lang/IllegalStateException");
  if (type != nullptr) {
    env->ThrowNew(type, message);
    env->DeleteLocalRef(type);
  }
}

std::string ToUtf8(JNIEnv* env, jstring value, std::size_t max_bytes) {
  if (value == nullptr) {
    return {};
  }
  const jsize byte_count = env->GetStringUTFLength(value);
  if (byte_count <= 0 || static_cast<std::size_t>(byte_count) > max_bytes) {
    return {};
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) {
    return {};
  }
  std::string result(chars, static_cast<std::size_t>(byte_count));
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

bool IsContinuation(unsigned char value) {
  return (value & 0xc0U) == 0x80U;
}

// NewStringUTF consumes Modified UTF-8. Rime emits ordinary UTF-8, so this
// boundary accepts only the common canonical BMP subset shared by both
// encodings. Four-byte scalar values fail closed instead of being passed to
// NewStringUTF with implementation-defined decoding.
bool IsBoundedJniUtf8(const char* value,
                      std::size_t max_bytes,
                      std::size_t max_code_points) {
  const char* text = value == nullptr ? "" : value;
  const std::size_t byte_count = strnlen(text, max_bytes + 1);
  if (byte_count > max_bytes) {
    return false;
  }

  std::size_t index = 0;
  std::size_t code_points = 0;
  while (index < byte_count) {
    const unsigned char first = static_cast<unsigned char>(text[index]);
    std::size_t sequence_bytes = 0;
    if (first >= 0x01U && first <= 0x7fU) {
      sequence_bytes = 1;
    } else if (first >= 0xc2U && first <= 0xdfU) {
      sequence_bytes = 2;
      if (index + sequence_bytes > byte_count ||
          !IsContinuation(static_cast<unsigned char>(text[index + 1]))) {
        return false;
      }
    } else if (first >= 0xe0U && first <= 0xefU) {
      sequence_bytes = 3;
      if (index + sequence_bytes > byte_count) {
        return false;
      }
      const unsigned char second = static_cast<unsigned char>(text[index + 1]);
      const unsigned char third = static_cast<unsigned char>(text[index + 2]);
      if (!IsContinuation(second) || !IsContinuation(third) ||
          (first == 0xe0U && second < 0xa0U) ||
          (first == 0xedU && second >= 0xa0U)) {
        return false;
      }
    } else {
      return false;
    }

    ++code_points;
    if (code_points > max_code_points) {
      return false;
    }
    index += sequence_bytes;
  }
  return true;
}

jstring NewBoundedStringUtf(JNIEnv* env,
                            const char* value,
                            std::size_t max_bytes,
                            std::size_t max_code_points,
                            const char* failure_message) {
  if (!IsBoundedJniUtf8(value, max_bytes, max_code_points)) {
    ThrowState(env, failure_message);
    return nullptr;
  }
  return env->NewStringUTF(value == nullptr ? "" : value);
}

bool RequireEngine(JNIEnv* env) {
  if (!g_initialized || g_api == nullptr) {
    ThrowState(env, "Rime engine is not initialized");
    return false;
  }
  return true;
}

bool IsSchemaId(const std::string& value) {
  if (value.empty() || value.size() > kMaxSchemaIdBytes) {
    return false;
  }
  for (const unsigned char character : value) {
    if (!((character >= 'a' && character <= 'z') ||
          (character >= 'A' && character <= 'Z') ||
          (character >= '0' && character <= '9') || character == '.' ||
          character == '_' || character == '-')) {
      return false;
    }
  }
  return true;
}

bool IsSupportedOption(const std::string& value) {
  return value == "simplification" || value == "ascii_punct" ||
         value == "full_shape";
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_opentypeless_ksp004_RimeAdapter_nativeInitialize(
    JNIEnv* env,
    jclass,
    jstring shared_data_dir,
    jstring user_data_dir) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_initialized) {
    ThrowState(env, "Rime engine is already initialized");
    return JNI_FALSE;
  }

  const std::string shared = ToUtf8(env, shared_data_dir, kMaxPathBytes);
  const std::string user = ToUtf8(env, user_data_dir, kMaxPathBytes);
  if (env->ExceptionCheck() || shared.empty() || user.empty()) {
    return JNI_FALSE;
  }

  g_api = rime_get_api();
  if (g_api == nullptr) {
    ThrowState(env, "Rime API is unavailable");
    return JNI_FALSE;
  }

  RimeTraits traits{};
  RIME_STRUCT_INIT(RimeTraits, traits);
  traits.shared_data_dir = shared.c_str();
  traits.user_data_dir = user.c_str();
  traits.distribution_name = "OpenTypeless KSP-004";
  traits.distribution_code_name = "opentypeless-ksp004";
  traits.distribution_version = "1";
  traits.app_name = "rime.opentypeless.ksp004";
  traits.min_log_level = 3;
  traits.log_dir = "";

  g_api->setup(&traits);
  g_api->initialize(&traits);
  g_initialized = true;
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_opentypeless_ksp004_RimeAdapter_nativeDeploy(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!RequireEngine(env)) {
    return JNI_FALSE;
  }
  if (!g_api->start_maintenance(True)) {
    return JNI_FALSE;
  }
  g_api->join_maintenance_thread();
  return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_opentypeless_ksp004_RimeAdapter_nativeVersion(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!RequireEngine(env)) {
    return nullptr;
  }
  return NewBoundedStringUtf(env, g_api->get_version(), kMaxVersionBytes,
                             kMaxVersionBytes,
                             "Rime version exceeded the JNI limit");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_opentypeless_ksp004_RimeAdapter_nativeCreateSession(JNIEnv* env,
                                                              jclass,
                                                              jstring schema_id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!RequireEngine(env) || schema_id == nullptr) {
    return 0;
  }
  const std::string schema = ToUtf8(env, schema_id, kMaxSchemaIdBytes);
  if (env->ExceptionCheck() || !IsSchemaId(schema)) {
    return 0;
  }
  const RimeSessionId session = g_api->create_session();
  if (session == 0 || !g_api->select_schema(session, schema.c_str())) {
    if (session != 0) {
      g_api->destroy_session(session);
    }
    return 0;
  }
  return static_cast<jlong>(session);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_opentypeless_ksp004_RimeAdapter_nativeDestroySession(JNIEnv* env,
                                                               jclass,
                                                               jlong session) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!RequireEngine(env) || session == 0) {
    return JNI_FALSE;
  }
  const RimeSessionId session_id = static_cast<RimeSessionId>(session);
  if (!g_api->find_session(session_id)) {
    return JNI_FALSE;
  }
  g_api->destroy_session(session_id);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_opentypeless_ksp004_RimeAdapter_nativeProcessAscii(JNIEnv* env,
                                                            jclass,
                                                            jlong session,
                                                            jstring input) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!RequireEngine(env) || session == 0 || input == nullptr) {
    return JNI_FALSE;
  }
  const std::string text = ToUtf8(env, input, kMaxInputBytes);
  if (env->ExceptionCheck() || text.empty()) {
    return JNI_FALSE;
  }
  const RimeSessionId session_id = static_cast<RimeSessionId>(session);
  g_api->clear_composition(session_id);
  for (const unsigned char ch : text) {
    if (ch < 'a' || ch > 'z') {
      return JNI_FALSE;
    }
  }
  return g_api->simulate_key_sequence(session_id, text.c_str()) ? JNI_TRUE
                                                                : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_opentypeless_ksp004_RimeAdapter_nativeSetOption(JNIEnv* env,
                                                         jclass,
                                                         jlong session,
                                                         jstring option_name,
                                                         jboolean enabled) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!RequireEngine(env) || session == 0 || option_name == nullptr) {
    return JNI_FALSE;
  }
  const std::string option = ToUtf8(env, option_name, kMaxOptionNameBytes);
  if (env->ExceptionCheck() || !IsSupportedOption(option)) {
    return JNI_FALSE;
  }
  const RimeSessionId session_id = static_cast<RimeSessionId>(session);
  if (!g_api->find_session(session_id)) {
    return JNI_FALSE;
  }
  const Bool value = enabled == JNI_TRUE ? True : False;
  g_api->set_option(session_id, option.c_str(), value);
  return g_api->get_option(session_id, option.c_str()) == value ? JNI_TRUE
                                                                : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_opentypeless_ksp004_RimeAdapter_nativeSnapshot(JNIEnv* env,
                                                        jclass,
                                                        jlong session) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!RequireEngine(env) || session == 0) {
    return nullptr;
  }

  RimeContext context{};
  RIME_STRUCT_INIT(RimeContext, context);
  if (!g_api->get_context(static_cast<RimeSessionId>(session), &context)) {
    return nullptr;
  }

  const int candidate_count =
      std::max(0, std::min(context.menu.num_candidates, kMaxCandidates));
  const char* preedit = context.composition.preedit;
  bool bounded = IsBoundedJniUtf8(preedit, kMaxRimeTextBytes,
                                  kMaxRimeTextCodePoints);
  if (bounded && candidate_count > 0 && context.menu.candidates == nullptr) {
    bounded = false;
  }
  for (int index = 0; bounded && index < candidate_count; ++index) {
    bounded = IsBoundedJniUtf8(context.menu.candidates[index].text,
                               kMaxRimeTextBytes, kMaxRimeTextCodePoints);
  }
  if (!bounded) {
    g_api->free_context(&context);
    ThrowState(env, "Rime snapshot exceeded the JNI limit");
    return nullptr;
  }

  jclass string_class = env->FindClass("java/lang/String");
  if (string_class == nullptr) {
    g_api->free_context(&context);
    return nullptr;
  }
  jobjectArray result =
      env->NewObjectArray(candidate_count + 1, string_class, nullptr);
  env->DeleteLocalRef(string_class);
  if (result != nullptr) {
    jstring preedit_string = NewBoundedStringUtf(
        env, preedit, kMaxRimeTextBytes, kMaxRimeTextCodePoints,
        "Rime preedit exceeded the JNI limit");
    if (preedit_string != nullptr) {
      env->SetObjectArrayElement(result, 0, preedit_string);
      env->DeleteLocalRef(preedit_string);
    }
    for (int index = 0; !env->ExceptionCheck() && index < candidate_count;
         ++index) {
      jstring candidate = NewBoundedStringUtf(
          env, context.menu.candidates[index].text, kMaxRimeTextBytes,
          kMaxRimeTextCodePoints, "Rime candidate exceeded the JNI limit");
      if (candidate == nullptr) {
        break;
      }
      env->SetObjectArrayElement(result, index + 1, candidate);
      env->DeleteLocalRef(candidate);
    }
  }
  g_api->free_context(&context);
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_opentypeless_ksp004_RimeAdapter_nativeSelectCandidate(JNIEnv* env,
                                                               jclass,
                                                               jlong session,
                                                               jint index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!RequireEngine(env) || session == 0 || index < 0 ||
      index >= kMaxCandidates) {
    return JNI_FALSE;
  }
  return g_api->select_candidate(static_cast<RimeSessionId>(session),
                                 static_cast<std::size_t>(index))
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_opentypeless_ksp004_RimeAdapter_nativeTakeCommit(JNIEnv* env,
                                                          jclass,
                                                          jlong session) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!RequireEngine(env) || session == 0) {
    return nullptr;
  }
  RimeCommit commit{};
  RIME_STRUCT_INIT(RimeCommit, commit);
  if (!g_api->get_commit(static_cast<RimeSessionId>(session), &commit)) {
    return nullptr;
  }
  jstring result = NewBoundedStringUtf(
      env, commit.text, kMaxRimeTextBytes, kMaxRimeTextCodePoints,
      "Rime commit exceeded the JNI limit");
  g_api->free_commit(&commit);
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_opentypeless_ksp004_RimeAdapter_nativeSyncUserData(JNIEnv* env,
                                                            jclass) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!RequireEngine(env)) {
    return JNI_FALSE;
  }
  return g_api->sync_user_data() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_opentypeless_ksp004_RimeAdapter_nativeFinalizeEngine(JNIEnv*,
                                                              jclass) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_initialized && g_api != nullptr) {
    g_api->cleanup_all_sessions();
    g_api->finalize();
  }
  g_api = nullptr;
  g_initialized = false;
}

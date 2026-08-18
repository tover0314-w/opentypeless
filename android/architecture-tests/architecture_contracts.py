#!/usr/bin/env python3
"""Fail-closed source boundaries for the incremental Android architecture migration."""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import json
from pathlib import Path
import re
import sys
import unicodedata


PACKAGE_PATTERN = re.compile(r"\bpackage\s+([\w.]+)\s*(?:;|$)", re.MULTILINE)
IMPORT_PATTERN = re.compile(
    r"\bimport\s+(?:static\s+)?([\w.*]+)(?:\s+as\s+\w+)?\s*(?:;|$)",
    re.MULTILINE,
)

# Java permits whitespace and comments around the dots in a qualified name. Comments have already
# been replaced with spaces when this is applied. Canonicalizing the code view (but not arbitrary
# string contents) prevents those legal spellings from bypassing exact capability checks.
QUALIFIED_DOT_PATTERN = re.compile(
    r"(?<=[\w$*])[ \t\f\r\n]*\.[ \t\f\r\n]*(?=[\w$*])"
)

INPUT_CONNECTION_TYPE = "android.view.inputmethod.InputConnection"
INPUT_CONNECTION_CAPABILITY_TYPES = (
    INPUT_CONNECTION_TYPE,
    "android.view.inputmethod.BaseInputConnection",
    "android.view.inputmethod.InputConnectionWrapper",
    "android.inputmethodservice.InputMethodService",
)
INPUT_CONNECTION_TYPE_USE_PATTERN = re.compile(
    r"\bandroid\.view\.inputmethod\."
    r"(?:\s*@[\s\S]{0,512}?\s+)*"
    r"(?:InputConnection|BaseInputConnection|InputConnectionWrapper)\b"
)
FORBIDDEN_TYPE_USE_ANNOTATION_PREFIXES = (
    "android.view.inputmethod",
    "android.inputmethodservice",
    "com.opentypeless.android.editor.host",
    "com.opentypeless.android.ime",
    "com.opentypeless.android.speech.delivery",
)
REFLECTION_CAPABILITY_IMPORT_PREFIXES = (
    "java.lang.reflect",
    "java.lang.invoke",
)
REFLECTIVE_TYPE_LOAD_PATTERN = re.compile(
    r"(?:"
    r"\bClass\s*\.\s*(?:<[^<>;{}()]+>\s*)?forName\s*\("
    r"|(?<![\w$.])(?:forName|loadClass|findClass|findSystemClass)\s*\("
    r"|\.\s*(?:<[^<>;{}()]+>\s*)?(?:loadClass|findClass|findSystemClass)\s*\("
    r"|::\s*(?:<[^<>;{}()]+>\s*)?(?:forName|loadClass|findClass|findSystemClass)\b"
    r"|\bMethodHandles\s*\."
    r")"
)
EDITOR_WRITE_METHODS = (
    "beginBatchEdit",
    "clearMetaKeyStates",
    "closeConnection",
    "commitCompletion",
    "commitContent",
    "commitCorrection",
    "commitText",
    "deleteSurroundingTextInCodePoints",
    "deleteSurroundingText",
    "endBatchEdit",
    "finishComposingText",
    "finishConnectionlessStylusHandwriting",
    "finishStylusHandwriting",
    "onExtractedCursorMovement",
    "onExtractedSelectionChanged",
    "onExtractTextContextMenuItem",
    "performContextMenuAction",
    "performEditorAction",
    "performHandwritingGesture",
    "performPrivateCommand",
    "performSpellCheck",
    "previewHandwritingGesture",
    "replaceText",
    "reportFullscreenMode",
    "requestCursorUpdates",
    "sendDefaultEditorAction",
    "sendDownUpKeyEvents",
    "sendKeyChar",
    "sendKeyEvent",
    "setComposingRegion",
    "setComposingText",
    "setImeConsumesInput",
)
JAVA_EXPLICIT_TYPE_ARGUMENTS = r"(?:<[^;{}()]*>\s*)?"
EDITOR_WRITE_PATTERN = re.compile(
    r"\.\s*"
    + JAVA_EXPLICIT_TYPE_ARGUMENTS
    + r"("
    + "|".join(map(re.escape, EDITOR_WRITE_METHODS))
    + r")\s*\("
)
EDITOR_WRITE_METHOD_REFERENCE_PATTERN = re.compile(
    r"::\s*"
    + JAVA_EXPLICIT_TYPE_ARGUMENTS
    + r"("
    + "|".join(map(re.escape, EDITOR_WRITE_METHODS))
    + r")\b"
)
REFLECTIVE_METHOD_ACCESS_PATTERN = re.compile(
    r"(?:\.\s*(?:getMethod|getDeclaredMethod|getMethods|getDeclaredMethods|"
    r"findVirtual|findSpecial|findStatic|unreflect|invoke|newInstance)\s*\()"
)

# Existing writers are an exact, shrinking migration inventory. A new call in an allowed file, a
# new writer, or deleting a legacy writer without updating this inventory all fail the contract.
LEGACY_EDITOR_WRITES = {
    "com/opentypeless/android/ime/OpenTypelessImeService.java": Counter(
        {
            "beginBatchEdit": 4,
            "commitText": 3,
            "deleteSurroundingTextInCodePoints": 1,
            "endBatchEdit": 4,
            "finishComposingText": 1,
            "setComposingText": 2,
        }
    ),
    "com/opentypeless/android/ime/VoiceCompositionSession.java": Counter(
        {
            "beginBatchEdit": 1,
            "commitText": 6,
            "deleteSurroundingTextInCodePoints": 2,
            "endBatchEdit": 1,
            "finishComposingText": 2,
            "setComposingText": 2,
        }
    ),
    "com/opentypeless/android/speech/delivery/AndroidInputConnectionAdapter.java": Counter(
        {
            "beginBatchEdit": 1,
            "deleteSurroundingTextInCodePoints": 1,
            "endBatchEdit": 1,
            "finishComposingText": 1,
            "setComposingText": 1,
        }
    ),
    "com/opentypeless/android/speech/delivery/EditorProjection.java": Counter(
        {
            "beginBatchEdit": 2,
            "deleteSurroundingTextInCodePoints": 1,
            "endBatchEdit": 2,
            "finishComposingText": 2,
            "setComposingText": 1,
        }
    ),
    "com/opentypeless/android/speech/delivery/SessionUndoLedger.java": Counter(
        {
            "beginBatchEdit": 1,
            "deleteSurroundingTextInCodePoints": 1,
            "endBatchEdit": 1,
        }
    ),
}

EDITOR_TRANSACTION_MANAGER_PATH = (
    "com/opentypeless/android/editor/host/EditorTransactionManager.java"
)
EDITOR_SESSION_MANAGER_PATH = (
    "com/opentypeless/android/editor/host/EditorSessionManager.java"
)
EDITOR_OPERATION_PATH = "com/opentypeless/android/editor/EditorOperation.java"
EDITOR_OPERATION_KIND_PATH = (
    "com/opentypeless/android/editor/EditorOperationKind.java"
)
EDITOR_TRANSACTION_AUDIT_PATH = (
    "com/opentypeless/android/editor/EditorTransactionAudit.java"
)
COMMIT_LEDGER_PATH = "com/opentypeless/android/editor/host/CommitLedger.java"
COMMIT_RECORD_PATH = "com/opentypeless/android/editor/CommitRecord.java"
COMMIT_RECORD_REQUEST_PATH = (
    "com/opentypeless/android/editor/CommitRecordRequest.java"
)
TRANSACTION_RECEIPT_PATH = (
    "com/opentypeless/android/editor/TransactionReceipt.java"
)
PROVIDER_CONFIG_PATH = "com/opentypeless/android/config/ProviderConfig.java"
SECRET_REF_PATH = "com/opentypeless/android/config/SecretRef.java"
RECOGNITION_ROUTE_PATH = "com/opentypeless/android/config/RecognitionRoute.java"
OVERRIDE_VALUE_PATH = "com/opentypeless/android/config/OverrideValue.java"
OVERRIDE_VALUE_CODEC_PATH = "com/opentypeless/android/config/OverrideValueCodec.java"
GLOBAL_CONFIG_PATH = "com/opentypeless/android/config/GlobalConfig.java"
APP_RULE_PATH = "com/opentypeless/android/config/AppRule.java"
FIELD_RULE_PATH = "com/opentypeless/android/config/FieldRule.java"
RULE_OVERRIDES_PATH = "com/opentypeless/android/config/RuleOverrides.java"
PROCESSING_MODE_PATH = "com/opentypeless/android/config/ProcessingMode.java"
EFFECTIVE_PROFILE_PATH = "com/opentypeless/android/config/EffectiveProfile.java"
EFFECTIVE_PROFILE_RESOLVER_PATH = (
    "com/opentypeless/android/config/EffectiveProfileResolver.java"
)
PROVIDER_CAPABILITIES_PATH = (
    "com/opentypeless/android/recognition/ProviderCapabilities.java"
)
PROVIDER_DESCRIPTOR_PATH = (
    "com/opentypeless/android/recognition/ProviderDescriptor.java"
)
RECOGNITION_EVENT_PATH = (
    "com/opentypeless/android/recognition/RecognitionEvent.java"
)
RECOGNITION_METADATA_PATH = (
    "com/opentypeless/android/recognition/RecognitionMetadata.java"
)
RECOGNITION_EVENT_VALIDATOR_PATH = (
    "com/opentypeless/android/recognition/RecognitionEventValidator.java"
)
PROVIDER_REGISTRY_PATH = (
    "com/opentypeless/android/recognition/ProviderRegistry.java"
)
RECOGNITION_PROVIDER_PATH = (
    "com/opentypeless/android/recognition/RecognitionProvider.java"
)
ANDROID_SYSTEM_PROVIDER_PATH = (
    "com/opentypeless/android/recognition/AndroidSystemRecognitionProvider.java"
)
OPENAI_UPLOAD_PROVIDER_PATH = (
    "com/opentypeless/android/recognition/OpenAiCompatibleUploadProvider.java"
)
OPENAI_COMPATIBLE_CLIENT_PATH = (
    "com/opentypeless/android/net/OpenAiCompatibleClient.java"
)
SENSEVOICE_FINAL_PROVIDER_PATH = (
    "com/opentypeless/android/recognition/SenseVoiceFinalProvider.java"
)
PREFIX_REPLAY_PREVIEW_PROVIDER_PATH = (
    "com/opentypeless/android/recognition/PrefixReplayPreviewProvider.java"
)
RECOGNITION_FAILURE_MAPPER_PATH = (
    "com/opentypeless/android/recognition/RecognitionFailureMapper.java"
)
RECOGNITION_ROUTER_PATH = (
    "com/opentypeless/android/recognition/RecognitionRouter.java"
)
PROVIDER_CIRCUIT_BREAKER_PATH = (
    "com/opentypeless/android/recognition/ProviderCircuitBreaker.java"
)
RECOGNITION_ROUTER_VOICE_CONTROLLER_PATH = (
    "com/opentypeless/android/recognition/RecognitionRouterVoiceController.java"
)
RECOGNITION_ROUTER_VOICE_CONFIG_PATH = (
    "com/opentypeless/android/recognition/RecognitionRouterVoiceConfig.java"
)
RECOGNITION_FAILURE_PATH = (
    "com/opentypeless/android/recognition/RecognitionFailure.java"
)
RECOGNITION_ERRORS_PATH = (
    "com/opentypeless/android/recognition/RecognitionErrors.java"
)
LOCAL_REALTIME_PREVIEW_PATH = (
    "com/opentypeless/android/offline/LocalRealtimePreview.java"
)
LOCAL_OFFLINE_RECOGNIZER_PATH = (
    "com/opentypeless/android/offline/LocalOfflineRecognizer.java"
)
LOCAL_OFFLINE_RECOGNITION_CLIENT_PATH = (
    "com/opentypeless/android/offline/LocalOfflineRecognitionClient.java"
)
SYSTEM_SPEECH_RECOGNIZER_PATH = (
    "com/opentypeless/android/recognition/SystemSpeechRecognizer.java"
)
SYSTEM_RECOGNITION_INTENT_FACTORY_PATH = (
    "com/opentypeless/android/recognition/SystemRecognitionIntentFactory.java"
)
SYSTEM_RECOGNITION_SUPPORT_PATH = (
    "com/opentypeless/android/recognition/SystemRecognitionSupport.java"
)
SYSTEM_RECOGNITION_SUPPORT_API33_PATH = (
    "com/opentypeless/android/recognition/SystemRecognitionSupportApi33.java"
)
SYSTEM_RECOGNITION_SUPPORT_API34_PATH = (
    "com/opentypeless/android/recognition/SystemRecognitionSupportApi34.java"
)
SYSTEM_MODEL_DOWNLOAD_COORDINATOR_PATH = (
    "com/opentypeless/android/recognition/SystemModelDownloadCoordinator.java"
)
RECOGNITION_LANGUAGE_SUPPORT_EVALUATOR_PATH = (
    "com/opentypeless/android/recognition/RecognitionLanguageSupportEvaluator.java"
)
STREAMING_RECOGNITION_WIRE_EVENT_PATH = (
    "com/opentypeless/android/net/streaming/StreamingRecognitionWireEvent.java"
)
STREAMING_RECOGNITION_WEBSOCKET_CLIENT_PATH = (
    "com/opentypeless/android/net/streaming/StreamingRecognitionWebSocketClient.java"
)
WEBSOCKET_STREAMING_PROVIDER_PATH = (
    "com/opentypeless/android/recognition/WebSocketStreamingProvider.java"
)
QWEN3_ASR_VLLM_CLIENT_PATH = (
    "com/opentypeless/android/net/streaming/Qwen3AsrVllmClient.java"
)
QWEN3_ASR_VLLM_PROVIDER_PATH = (
    "com/opentypeless/android/recognition/Qwen3AsrVllmProvider.java"
)
LOCAL_STREAMING_PROVIDER_PATH = (
    "com/opentypeless/android/recognition/LocalStreamingProvider.java"
)
TWO_STAGE_STREAMING_PROVIDER_PATH = (
    "com/opentypeless/android/recognition/TwoStageStreamingProvider.java"
)
LOCAL_REALTIME_RECOGNITION_CLIENT_PATH = (
    "com/opentypeless/android/offline/LocalRealtimeRecognitionClient.java"
)
OFFLINE_STREAMING_MODEL_SPEC_PATH = (
    "com/opentypeless/android/offline/OfflineStreamingModelSpec.java"
)
OFFLINE_STREAMING_MODEL_STORE_PATH = (
    "com/opentypeless/android/offline/OfflineStreamingModelStore.java"
)
OFFLINE_MODEL_DOWNLOADER_PATH = (
    "com/opentypeless/android/offline/OfflineModelDownloader.java"
)
STREAMING_RECOGNITION_SCHEMA_PATH = Path(
    "app/src/main/resources/schemas/"
    "opentypeless-streaming-recognition-event-v1.schema.json"
)
MAIN_ACTIVITY_PATH = "com/opentypeless/android/MainActivity.java"
CFG001_REQUIRED_SOURCE_PATHS = frozenset({PROVIDER_CONFIG_PATH, SECRET_REF_PATH})
CFG002_REQUIRED_SOURCE_PATHS = frozenset({RECOGNITION_ROUTE_PATH})
CFG003_REQUIRED_SOURCE_PATHS = frozenset({OVERRIDE_VALUE_PATH, OVERRIDE_VALUE_CODEC_PATH})
CFG004_REQUIRED_SOURCE_PATHS = frozenset(
    {
        GLOBAL_CONFIG_PATH,
        APP_RULE_PATH,
        FIELD_RULE_PATH,
        RULE_OVERRIDES_PATH,
        PROCESSING_MODE_PATH,
    }
)
CFG005_REQUIRED_SOURCE_PATHS = frozenset(
    {EFFECTIVE_PROFILE_PATH, EFFECTIVE_PROFILE_RESOLVER_PATH}
)
REC001_REQUIRED_SOURCE_PATHS = frozenset(
    {PROVIDER_CAPABILITIES_PATH, PROVIDER_DESCRIPTOR_PATH}
)
REC002_REQUIRED_SOURCE_PATHS = frozenset(
    {
        RECOGNITION_EVENT_PATH,
        RECOGNITION_METADATA_PATH,
        RECOGNITION_EVENT_VALIDATOR_PATH,
    }
)
STR001_REQUIRED_SOURCE_PATHS = frozenset({STREAMING_RECOGNITION_WIRE_EVENT_PATH})
STR002_REQUIRED_SOURCE_PATHS = frozenset(
    {STREAMING_RECOGNITION_WEBSOCKET_CLIENT_PATH, WEBSOCKET_STREAMING_PROVIDER_PATH}
)
STR003_REQUIRED_SOURCE_PATHS = frozenset(
    {QWEN3_ASR_VLLM_CLIENT_PATH, QWEN3_ASR_VLLM_PROVIDER_PATH}
)
STR005_REQUIRED_SOURCE_PATHS = frozenset(
    {
        LOCAL_STREAMING_PROVIDER_PATH,
        LOCAL_REALTIME_RECOGNITION_CLIENT_PATH,
        OFFLINE_STREAMING_MODEL_SPEC_PATH,
        OFFLINE_STREAMING_MODEL_STORE_PATH,
        OFFLINE_MODEL_DOWNLOADER_PATH,
    }
)
STR006_REQUIRED_SOURCE_PATHS = frozenset({TWO_STAGE_STREAMING_PROVIDER_PATH})
STR010_REQUIRED_SOURCE_PATHS = frozenset(
    {
        RECOGNITION_ROUTER_VOICE_CONTROLLER_PATH,
        RECOGNITION_ROUTER_VOICE_CONFIG_PATH,
    }
)
STR010_PRODUCTION_CONSUMERS = frozenset(
    {
        "com/opentypeless/android/ime/OpenTypelessImeService.java",
        "com/opentypeless/android/VoiceLabActivity.java",
        "com/opentypeless/android/recognition/VoicePipelineRecognitionEngine.java",
    }
)
STR010_CONTROLLER_ALLOWED_IMPORTS = frozenset(
    {
        "android.content.Context",
        "android.os.SystemClock",
        "com.opentypeless.android.config.EffectiveProfile",
        "com.opentypeless.android.config.EffectiveProfileResolver",
        "com.opentypeless.android.config.GlobalConfig",
        "com.opentypeless.android.config.OverrideValue",
        "com.opentypeless.android.config.RecognitionRoute",
        "com.opentypeless.android.config.RecognitionRoute.ConfirmationPolicy",
        "com.opentypeless.android.config.RecognitionRoute.FailureClass",
        "com.opentypeless.android.config.RecognitionRoute.ProviderCapability",
        "com.opentypeless.android.config.RecognitionRoute.RetryPolicy",
        "com.opentypeless.android.config.RecognitionRoute.RouteStep",
        "com.opentypeless.android.config.RuleOverrides",
        "com.opentypeless.android.context.InputContext",
        "com.opentypeless.android.ime.DictationRequest",
        "com.opentypeless.android.ime.VoiceController",
        "com.opentypeless.android.ime.VoicePipelineAdapter",
        "com.opentypeless.android.settings.RecognitionBackend",
        "java.util.EnumMap",
        "java.util.EnumSet",
        "java.util.List",
        "java.util.Map",
        "java.util.Objects",
        "java.util.Set",
    }
)
STR010_CONFIG_ALLOWED_IMPORTS = frozenset(
    {
        "android.content.Context",
        "android.content.SharedPreferences",
        "com.opentypeless.android.ime.VoiceController",
        "com.opentypeless.android.ime.VoicePipelineAdapter",
        "java.util.Objects",
    }
)
REC003_REQUIRED_SOURCE_PATHS = frozenset({PROVIDER_REGISTRY_PATH})
REC004_REQUIRED_SOURCE_PATHS = frozenset(
    {
        RECOGNITION_PROVIDER_PATH,
        ANDROID_SYSTEM_PROVIDER_PATH,
        SYSTEM_SPEECH_RECOGNIZER_PATH,
        SYSTEM_RECOGNITION_INTENT_FACTORY_PATH,
    }
)
REC005_REQUIRED_SOURCE_PATHS = frozenset(
    {OPENAI_UPLOAD_PROVIDER_PATH, OPENAI_COMPATIBLE_CLIENT_PATH}
)
REC006_REQUIRED_SOURCE_PATHS = frozenset(
    {
        SENSEVOICE_FINAL_PROVIDER_PATH,
        LOCAL_OFFLINE_RECOGNIZER_PATH,
        LOCAL_OFFLINE_RECOGNITION_CLIENT_PATH,
    }
)
REC007_REQUIRED_SOURCE_PATHS = frozenset(
    {PREFIX_REPLAY_PREVIEW_PROVIDER_PATH, LOCAL_REALTIME_PREVIEW_PATH}
)
REC008_REQUIRED_SOURCE_PATHS = frozenset(
    {
        RECOGNITION_FAILURE_MAPPER_PATH,
        RECOGNITION_FAILURE_PATH,
        RECOGNITION_ERRORS_PATH,
        ANDROID_SYSTEM_PROVIDER_PATH,
        OPENAI_UPLOAD_PROVIDER_PATH,
        SENSEVOICE_FINAL_PROVIDER_PATH,
        PREFIX_REPLAY_PREVIEW_PROVIDER_PATH,
    }
)
REC009_REQUIRED_SOURCE_PATHS = frozenset({RECOGNITION_ROUTER_PATH})
REC010_REQUIRED_SOURCE_PATHS = frozenset({RECOGNITION_ROUTER_PATH})
REC011_REQUIRED_SOURCE_PATHS = frozenset(
    {PROVIDER_CIRCUIT_BREAKER_PATH, RECOGNITION_ROUTER_PATH}
)
REC012_REQUIRED_SOURCE_PATHS = frozenset(
    {
        SYSTEM_RECOGNITION_SUPPORT_PATH,
        SYSTEM_RECOGNITION_SUPPORT_API33_PATH,
        SYSTEM_RECOGNITION_SUPPORT_API34_PATH,
        SYSTEM_MODEL_DOWNLOAD_COORDINATOR_PATH,
        RECOGNITION_LANGUAGE_SUPPORT_EVALUATOR_PATH,
        SYSTEM_RECOGNITION_INTENT_FACTORY_PATH,
        SYSTEM_SPEECH_RECOGNIZER_PATH,
        MAIN_ACTIVITY_PATH,
    }
)
CFG006_MIGRATION_PATH = (
    "com/opentypeless/android/settings/LegacyAppSettingsMigration.java"
)
CFG006_REPOSITORY_PATH = "com/opentypeless/android/settings/SettingsRepository.java"
CFG007_MIGRATION_PATH = (
    "com/opentypeless/android/settings/LegacyAppProfileMigration.java"
)
CFG007_REPOSITORY_PATH = "com/opentypeless/android/settings/AppProfileRepository.java"
CFG008_STORE_PATH = "com/opentypeless/android/security/SecretStore.java"
CFG008_SECURE_PREFERENCES_PATH = (
    "com/opentypeless/android/security/SecurePreferences.java"
)
CFG011_TRANSACTION_PATH = (
    "com/opentypeless/android/settings/SettingsSaveTransaction.java"
)
CFG009_MODEL_PATH = "com/opentypeless/android/config/AppPickerModel.java"
CFG009_CATALOG_PATH = "com/opentypeless/android/InstalledAppCatalog.java"
CFG009_DIALOG_PATH = "com/opentypeless/android/AppPickerDialog.java"
CFG009_ACTIVITY_PATH = "com/opentypeless/android/AppProfileActivity.java"
CFG009_REQUIRED_SOURCE_PATHS = frozenset(
    {CFG009_MODEL_PATH, CFG009_CATALOG_PATH, CFG009_DIALOG_PATH, CFG009_ACTIVITY_PATH}
)
CFG010_MODEL_PATH = "com/opentypeless/android/config/RuleExplanationModel.java"
CFG010_REQUIRED_SOURCE_PATHS = frozenset({CFG010_MODEL_PATH})
CFG001_ALLOWED_IMPORTS = {
    PROVIDER_CONFIG_PATH: frozenset(
        {
            "java.net.URI",
            "java.net.URISyntaxException",
            "java.util.Locale",
            "java.util.Objects",
            "java.util.Optional",
        }
    ),
    SECRET_REF_PATH: frozenset({"java.util.Objects"}),
}
CFG002_ALLOWED_IMPORTS = frozenset(
    {
        "java.util.ArrayList",
        "java.util.Collections",
        "java.util.EnumSet",
        "java.util.HashSet",
        "java.util.List",
        "java.util.Objects",
        "java.util.Set",
    }
)
CFG003_ALLOWED_IMPORTS = {
    OVERRIDE_VALUE_PATH: frozenset({"java.util.Objects"}),
    OVERRIDE_VALUE_CODEC_PATH: frozenset(
        {
            "java.util.Objects",
            "org.json.JSONArray",
            "org.json.JSONException",
            "org.json.JSONTokener",
        }
    ),
}
CFG004_ALLOWED_IMPORTS = {
    GLOBAL_CONFIG_PATH: frozenset({"java.util.Objects"}),
    APP_RULE_PATH: frozenset(),
    FIELD_RULE_PATH: frozenset(
        {"com.opentypeless.android.context.FieldKind", "java.util.Objects"}
    ),
    RULE_OVERRIDES_PATH: frozenset({"java.util.Objects"}),
    PROCESSING_MODE_PATH: frozenset(),
}
CFG005_ALLOWED_IMPORTS = {
    EFFECTIVE_PROFILE_PATH: frozenset({"java.util.Objects"}),
    EFFECTIVE_PROFILE_RESOLVER_PATH: frozenset(
        {
            "com.opentypeless.android.config.EffectiveProfile.ResolutionExplanation",
            "com.opentypeless.android.config.EffectiveProfile.ResolvedValue",
            "com.opentypeless.android.config.EffectiveProfile.RuleSource",
            "com.opentypeless.android.context.FieldKind",
            "java.util.ArrayList",
            "java.util.HashSet",
            "java.util.Iterator",
            "java.util.List",
            "java.util.Objects",
            "java.util.Set",
        }
    ),
}
CFG010_ALLOWED_IMPORTS = frozenset(
    {
        "com.opentypeless.android.config.EffectiveProfile.ResolutionExplanation",
        "com.opentypeless.android.config.EffectiveProfile.ResolvedValue",
        "com.opentypeless.android.config.EffectiveProfile.RuleSource",
        "java.util.EnumMap",
        "java.util.List",
        "java.util.Map",
        "java.util.Objects",
    }
)
REC001_ALLOWED_IMPORTS = {
    PROVIDER_CAPABILITIES_PATH: frozenset(
        {
            "com.opentypeless.android.config.RecognitionRoute",
            "com.opentypeless.android.settings.RecognitionBackend",
            "java.util.Collections",
            "java.util.EnumSet",
            "java.util.Objects",
            "java.util.Set",
        }
    ),
    PROVIDER_DESCRIPTOR_PATH: frozenset(
        {
            "com.opentypeless.android.settings.RecognitionBackend",
            "java.util.Objects",
            "java.util.regex.Pattern",
        }
    ),
}
REC002_ALLOWED_IMPORTS = {
    RECOGNITION_EVENT_PATH: frozenset(
        {
            "com.opentypeless.android.config.RecognitionRoute",
            "com.opentypeless.android.speech.core.SessionId",
            "java.util.Objects",
        }
    ),
    RECOGNITION_METADATA_PATH: frozenset(
        {"java.util.IllformedLocaleException", "java.util.Locale"}
    ),
    RECOGNITION_EVENT_VALIDATOR_PATH: frozenset(
        {
            "com.opentypeless.android.speech.core.SessionId",
            "java.util.Objects",
        }
    ),
}
STR001_ALLOWED_IMPORTS = frozenset(
    {
        "com.opentypeless.android.config.RecognitionRoute",
        "com.opentypeless.android.recognition.RecognitionEvent",
        "com.opentypeless.android.recognition.RecognitionEventValidator",
        "com.opentypeless.android.recognition.RecognitionMetadata",
        "com.opentypeless.android.speech.core.SessionId",
        "java.util.Iterator",
        "java.util.Objects",
        "java.util.Set",
        "org.json.JSONException",
        "org.json.JSONObject",
        "org.json.JSONTokener",
    }
)
STR002_ALLOWED_IMPORTS = {
    STREAMING_RECOGNITION_WEBSOCKET_CLIENT_PATH: frozenset(
        {
            "com.opentypeless.android.config.ProviderConfig",
            "com.opentypeless.android.recognition.RecognitionEvent",
            "com.opentypeless.android.speech.core.SessionId",
            "java.net.SocketTimeoutException",
            "java.net.URI",
            "java.util.Arrays",
            "java.util.Objects",
            "java.util.concurrent.TimeUnit",
            "okhttp3.OkHttpClient",
            "okhttp3.Request",
            "okhttp3.Response",
            "okhttp3.WebSocket",
            "okhttp3.WebSocketListener",
            "okio.ByteString",
            "org.json.JSONException",
            "org.json.JSONObject",
        }
    ),
    WEBSOCKET_STREAMING_PROVIDER_PATH: frozenset(
        {
            "com.opentypeless.android.config.ProviderConfig",
            "com.opentypeless.android.config.RecognitionRoute",
            "com.opentypeless.android.config.SecretRef",
            "com.opentypeless.android.net.streaming.StreamingRecognitionWebSocketClient",
            "com.opentypeless.android.settings.RecognitionBackend",
            "com.opentypeless.android.speech.core.SessionId",
            "java.util.Arrays",
            "java.util.IllformedLocaleException",
            "java.util.Locale",
            "java.util.Objects",
            "java.util.Optional",
            "java.util.concurrent.ScheduledFuture",
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            "java.util.concurrent.TimeUnit",
        }
    ),
}
STR003_ALLOWED_IMPORTS = {
    QWEN3_ASR_VLLM_CLIENT_PATH: frozenset(
        {
            "com.opentypeless.android.config.ProviderConfig",
            "com.opentypeless.android.config.RecognitionRoute",
            "com.opentypeless.android.recognition.RecognitionEvent",
            "com.opentypeless.android.recognition.RecognitionMetadata",
            "com.opentypeless.android.speech.core.SessionId",
            "java.io.IOException",
            "java.net.SocketTimeoutException",
            "java.nio.charset.StandardCharsets",
            "java.util.Iterator",
            "java.util.Objects",
            "java.util.Set",
            "java.util.concurrent.TimeUnit",
            "okhttp3.HttpUrl",
            "okhttp3.OkHttpClient",
            "okhttp3.Request",
            "okhttp3.Response",
            "okhttp3.ResponseBody",
            "okhttp3.WebSocket",
            "okhttp3.WebSocketListener",
            "okio.BufferedSource",
            "okio.ByteString",
            "org.json.JSONArray",
            "org.json.JSONException",
            "org.json.JSONObject",
            "org.json.JSONTokener",
        }
    ),
    QWEN3_ASR_VLLM_PROVIDER_PATH: frozenset(
        {
            "com.opentypeless.android.config.ProviderConfig",
            "com.opentypeless.android.config.RecognitionRoute",
            "com.opentypeless.android.config.SecretRef",
            "com.opentypeless.android.net.streaming.Qwen3AsrVllmClient",
            "com.opentypeless.android.speech.core.SessionId",
            "java.net.URI",
            "java.net.URISyntaxException",
            "java.util.Locale",
            "java.util.Objects",
            "java.util.Optional",
            "java.util.concurrent.ArrayBlockingQueue",
            "java.util.concurrent.ThreadPoolExecutor",
            "java.util.concurrent.TimeUnit",
        }
    ),
}
STR005_PROVIDER_ALLOWED_IMPORTS = frozenset(
    {
        "android.content.Context",
        "com.opentypeless.android.config.RecognitionRoute",
        "com.opentypeless.android.offline.LocalOfflineRecognizer",
        "com.opentypeless.android.offline.LocalRealtimeRecognitionClient",
        "com.opentypeless.android.offline.OfflineStreamingModelSpec",
        "com.opentypeless.android.offline.OfflineStreamingModelStore",
        "com.opentypeless.android.recognition.RecognitionFailureMapper.LocalAvailability",
        "com.opentypeless.android.speech.core.SessionId",
        "java.util.Arrays",
        "java.util.Objects",
        "java.util.concurrent.ExecutorService",
        "java.util.concurrent.Executors",
        "java.util.concurrent.ScheduledFuture",
        "java.util.concurrent.ScheduledThreadPoolExecutor",
        "java.util.concurrent.TimeUnit",
    }
)
STR006_PROVIDER_ALLOWED_IMPORTS = frozenset(
    {
        "android.content.Context",
        "com.opentypeless.android.audio.WavEncoder",
        "com.opentypeless.android.config.RecognitionRoute",
        "com.opentypeless.android.data.PersonalizationSnapshot",
        "com.opentypeless.android.settings.ProcessingMode",
        "com.opentypeless.android.speech.core.SessionId",
        "com.opentypeless.android.transform.TranscriptIntegrityGuard",
        "java.util.Arrays",
        "java.util.IllformedLocaleException",
        "java.util.Locale",
        "java.util.Objects",
        "java.util.concurrent.ExecutorService",
        "java.util.concurrent.Executors",
    }
)
STR005_REALTIME_CLIENT_ALLOWED_CONSUMERS = frozenset(
    {
        LOCAL_REALTIME_RECOGNITION_CLIENT_PATH,
        LOCAL_STREAMING_PROVIDER_PATH,
        "com/opentypeless/android/ime/VoicePipelineRuntime.java",
        "com/opentypeless/android/ime/LocalSpeechCoreV2Session.java",
    }
)
REC003_ALLOWED_IMPORTS = frozenset(
    {
        "com.opentypeless.android.config.RecognitionRoute",
        "java.util.LinkedHashMap",
        "java.util.Map",
        "java.util.Objects",
        "java.util.regex.Pattern",
    }
)
REC009_ALLOWED_IMPORTS = frozenset(
    {
        "com.opentypeless.android.config.EffectiveProfile",
        "com.opentypeless.android.config.OverrideValue",
        "com.opentypeless.android.config.RecognitionRoute",
        "com.opentypeless.android.config.RecognitionRoute.ConfirmationPolicy",
        "com.opentypeless.android.config.RecognitionRoute.FailureClass",
        "com.opentypeless.android.config.RecognitionRoute.PrivacyClass",
        "com.opentypeless.android.config.RecognitionRoute.ProviderCapability",
        "com.opentypeless.android.config.RecognitionRoute.RouteStep",
        "com.opentypeless.android.recognition.ProviderRegistry.RouteLease",
        "com.opentypeless.android.recognition.ProviderRegistry.RouteLeaseFound",
        "java.util.Objects",
    }
)
REC011_BREAKER_ALLOWED_IMPORTS = frozenset(
    {
        "com.opentypeless.android.config.RecognitionRoute.FailureClass",
        "java.util.IdentityHashMap",
        "java.util.Map",
        "java.util.Objects",
    }
)
REC004_ALLOWED_IMPORTS = {
    RECOGNITION_PROVIDER_PATH: frozenset(
        {
            "com.opentypeless.android.config.RecognitionRoute",
            "com.opentypeless.android.speech.core.SessionId",
            "java.util.Objects",
        }
    ),
    ANDROID_SYSTEM_PROVIDER_PATH: frozenset(
        {
            "android.content.Context",
            "android.os.Handler",
            "android.os.Looper",
            "com.opentypeless.android.config.RecognitionRoute",
            "com.opentypeless.android.data.PersonalizationSnapshot",
            "com.opentypeless.android.settings.RecognitionBackend",
            "com.opentypeless.android.speech.core.SessionId",
            "java.util.LinkedHashSet",
            "java.util.List",
            "java.util.Objects",
            "java.util.Set",
        }
    ),
}
REC005_ALLOWED_IMPORTS = {
    OPENAI_UPLOAD_PROVIDER_PATH: frozenset(
        {
            "com.opentypeless.android.config.ProviderConfig",
            "com.opentypeless.android.config.RecognitionRoute",
            "com.opentypeless.android.config.SecretRef",
            "com.opentypeless.android.net.OpenAiCompatibleClient",
            "com.opentypeless.android.settings.RecognitionBackend",
            "com.opentypeless.android.speech.core.SessionId",
            "java.util.Arrays",
            "java.util.IllformedLocaleException",
            "java.util.Locale",
            "java.util.Objects",
            "java.util.Optional",
            "java.util.concurrent.CancellationException",
            "java.util.concurrent.ExecutorService",
            "java.util.concurrent.Executors",
            "java.util.function.BooleanSupplier",
        }
    ),
    OPENAI_COMPATIBLE_CLIENT_PATH: frozenset(
        {
            "com.opentypeless.android.settings.AppSettings",
            "org.json.JSONArray",
            "org.json.JSONObject",
            "java.io.ByteArrayOutputStream",
            "java.io.IOException",
            "java.io.InputStream",
            "java.io.OutputStream",
            "java.net.HttpURLConnection",
            "java.net.URL",
            "java.nio.charset.StandardCharsets",
            "java.util.Arrays",
            "java.util.Objects",
            "java.util.UUID",
            "java.util.concurrent.CancellationException",
            "java.util.concurrent.atomic.AtomicReference",
            "java.util.function.BooleanSupplier",
        }
    ),
}
REC006_ALLOWED_IMPORTS = {
    SENSEVOICE_FINAL_PROVIDER_PATH: frozenset(
        {
            "android.content.Context",
            "com.opentypeless.android.config.RecognitionRoute",
            "com.opentypeless.android.offline.LocalOfflineRecognitionClient",
            "com.opentypeless.android.offline.LocalOfflineRecognitionService",
            "com.opentypeless.android.offline.LocalOfflineRecognizer",
            "com.opentypeless.android.offline.OfflineModelStore",
            "com.opentypeless.android.recognition.RecognitionFailureMapper.LocalAvailability",
            "com.opentypeless.android.settings.RecognitionBackend",
            "com.opentypeless.android.speech.core.SessionId",
            "java.util.Arrays",
            "java.util.IllformedLocaleException",
            "java.util.Locale",
            "java.util.Objects",
            "java.util.concurrent.CancellationException",
            "java.util.concurrent.ExecutorService",
            "java.util.concurrent.Executors",
            "java.util.function.BooleanSupplier",
        }
    ),
    LOCAL_OFFLINE_RECOGNIZER_PATH: frozenset(
        {
            "android.app.ActivityManager",
            "android.content.Context",
            "android.os.Build",
            "com.k2fsa.sherpa.onnx.OfflineModelConfig",
            "com.k2fsa.sherpa.onnx.OfflineRecognizer",
            "com.k2fsa.sherpa.onnx.OfflineRecognizerConfig",
            "com.k2fsa.sherpa.onnx.OfflineRecognizerResult",
            "com.k2fsa.sherpa.onnx.OfflineStream",
            "com.opentypeless.android.audio.Pcm16WaveDecoder",
            "java.util.Locale",
        }
    ),
    LOCAL_OFFLINE_RECOGNITION_CLIENT_PATH: frozenset(
        {
            "android.content.ComponentName",
            "android.content.Context",
            "android.content.Intent",
            "android.content.ServiceConnection",
            "android.os.Bundle",
            "android.os.IBinder",
            "android.os.ParcelFileDescriptor",
            "android.os.RemoteException",
            "java.io.FileOutputStream",
            "java.io.IOException",
            "java.util.concurrent.CancellationException",
            "java.util.concurrent.CountDownLatch",
            "java.util.concurrent.ExecutorService",
            "java.util.concurrent.Executors",
            "java.util.concurrent.Future",
            "java.util.concurrent.TimeUnit",
            "java.util.concurrent.TimeoutException",
            "java.util.concurrent.atomic.AtomicLong",
        }
    ),
}
REC007_ALLOWED_IMPORTS = {
    PREFIX_REPLAY_PREVIEW_PROVIDER_PATH: frozenset(
        {
            "android.content.Context",
            "com.opentypeless.android.config.RecognitionRoute",
            "com.opentypeless.android.offline.LocalOfflineRecognizer",
            "com.opentypeless.android.offline.LocalRealtimePreview",
            "com.opentypeless.android.offline.OfflineModelStore",
            "com.opentypeless.android.recognition.RecognitionFailureMapper.LocalAvailability",
            "com.opentypeless.android.speech.core.SessionId",
            "java.util.Arrays",
            "java.util.IllformedLocaleException",
            "java.util.Locale",
            "java.util.Objects",
        }
    ),
    LOCAL_REALTIME_PREVIEW_PATH: frozenset(
        {
            "android.content.Context",
            "com.opentypeless.android.audio.AudioCapture",
            "com.opentypeless.android.audio.WavEncoder",
            "java.util.Arrays",
            "java.util.List",
            "java.util.Objects",
            "java.util.concurrent.ExecutorService",
            "java.util.concurrent.Executors",
            "java.util.concurrent.TimeUnit",
        }
    ),
}
REC008_ALLOWED_IMPORTS = {
    RECOGNITION_FAILURE_MAPPER_PATH: frozenset(
        {
            "android.speech.SpeechRecognizer",
            "com.opentypeless.android.config.RecognitionRoute",
            "com.opentypeless.android.net.OpenAiCompatibleClient",
            "java.io.IOException",
            "java.net.ConnectException",
            "java.net.NoRouteToHostException",
            "java.net.SocketTimeoutException",
            "java.net.UnknownHostException",
            "java.util.Locale",
            "java.util.Objects",
            "java.util.concurrent.CancellationException",
        }
    ),
    RECOGNITION_FAILURE_PATH: frozenset(
        {
            "com.opentypeless.android.config.RecognitionRoute",
            "java.util.Objects",
        }
    ),
    RECOGNITION_ERRORS_PATH: frozenset(
        {
            "android.os.Build",
            "android.speech.SpeechRecognizer",
            "com.opentypeless.android.config.RecognitionRoute",
            "com.opentypeless.android.settings.RecognitionBackend",
        }
    ),
}
REC008_MAPPER_ALLOWED_CONSUMERS = frozenset(
    {
        RECOGNITION_FAILURE_MAPPER_PATH,
        RECOGNITION_FAILURE_PATH,
        RECOGNITION_ERRORS_PATH,
        ANDROID_SYSTEM_PROVIDER_PATH,
        SYSTEM_RECOGNITION_SUPPORT_API33_PATH,
        SYSTEM_RECOGNITION_SUPPORT_API34_PATH,
        OPENAI_UPLOAD_PROVIDER_PATH,
        SENSEVOICE_FINAL_PROVIDER_PATH,
        PREFIX_REPLAY_PREVIEW_PROVIDER_PATH,
        LOCAL_STREAMING_PROVIDER_PATH,
        RECOGNITION_ROUTER_VOICE_CONTROLLER_PATH,
    }
)
CFG001_SECRET_IDENTIFIER_PATTERN = re.compile(
    r"(?i)\b(?:api_?key|password|bearer_?token|auth_?token|credential_?value|"
    r"secret_?(?:value|text|string|bytes))\b"
)
CFG001_FORBIDDEN_TYPE_PATTERN = re.compile(
    r"\b(?:Serializable|Externalizable|Parcelable|Parcel|Bundle|Intent|"
    r"URL|HttpURLConnection|URLConnection|Socket|OkHttpClient|Retrofit|"
    r"SharedPreferences|DataStore|RoomDatabase)\b"
    r"|(?<![\w$.])(?:android|androidx|kotlinx\.serialization|"
    r"com\.fasterxml\.jackson|com\.google\.gson|com\.squareup\.moshi)\."
)
COMMIT_ENVELOPE_PATHS = frozenset(
    {
        COMMIT_RECORD_PATH,
        COMMIT_RECORD_REQUEST_PATH,
        TRANSACTION_RECEIPT_PATH,
    }
)
EDITOR_TRANSACTION_WRITE_METHODS = frozenset(
    {
        "beginBatchEdit",
        "commitText",
        "deleteSurroundingTextInCodePoints",
        "endBatchEdit",
        "finishComposingText",
        "performEditorAction",
        "setComposingText",
    }
)
EDITOR_TRANSACTION_EXACT_WRITES = Counter(
    {
        "beginBatchEdit": 1,
        "commitText": 1,
        "deleteSurroundingTextInCodePoints": 1,
        "endBatchEdit": 1,
        "finishComposingText": 1,
        "performEditorAction": 1,
        "setComposingText": 1,
    }
)
EDITOR_TRANSACTION_WRITE_OWNERS = {
    "beginBatchEdit": "beginBatch",
    "endBatchEdit": "finishBatch",
    "commitText": "invokeMutator",
    "deleteSurroundingTextInCodePoints": "invokeMutator",
    "finishComposingText": "invokeMutator",
    "performEditorAction": "invokeMutator",
    "setComposingText": "invokeMutator",
}
EDITOR_TRANSACTION_MANAGER_FQCN = (
    "com.opentypeless.android.editor.host.EditorTransactionManager"
)
EDITOR_TRANSACTION_ALLOWED_SOURCE_CONSUMERS = frozenset(
    {
        EDITOR_TRANSACTION_MANAGER_PATH,
        EDITOR_SESSION_MANAGER_PATH,
    }
)
EDITOR_TRANSACTION_AUDIT_ALLOWED_SOURCE_CONSUMERS = frozenset(
    {
        EDITOR_OPERATION_KIND_PATH,
        EDITOR_TRANSACTION_AUDIT_PATH,
        EDITOR_TRANSACTION_MANAGER_PATH,
        EDITOR_SESSION_MANAGER_PATH,
    }
)
EDITOR_TRANSACTION_AUDIT_SCOPE_TOKENS = frozenset(
    {"EditorOperationKind", "EditorTransactionAudit", "AuditSink"}
)
EDITOR_OPERATION_KIND_VALUES = (
    "SET_COMPOSITION",
    "COMMIT_COMPOSITION",
    "INSERT_TEXT",
    "REPLACE_SELECTION",
    "REPLACE_LAST_COMMIT",
    "DELETE_BEFORE_CURSOR",
    "PERFORM_EDITOR_ACTION",
)
EDITOR_TRANSACTION_AUDIT_FORBIDDEN_FIELD_PATTERN = re.compile(
    r"(?m)^[ \t]*(?:(?:public|protected|private|static|final|transient|volatile)\s+)*"
    r"(?:String|CharSequence|EditorOperation|EditorSessionSnapshot|TextRange|"
    r"TextFingerprint|CommitRecord|CommitRecordRequest|TransactionReceipt|Throwable|"
    r"[A-Za-z_$][\w$]*(?:Exception|Error)|Runnable|Thread|ClassLoader|InputConnection)"
    r"(?:\[\])?\s+[A-Za-z_$][\w$]*\s*(?:=|;)"
)
EDITOR_TRANSACTION_INDIRECT_IME_METHOD_PATTERN = re.compile(
    r"\.\s*(?:getCurrentInputBinding|getCurrentInputConnection|getConnection|"
    r"finishConnectionlessStylusHandwriting|finishStylusHandwriting|"
    r"onExtractedCursorMovement|onExtractedSelectionChanged|onExtractTextContextMenuItem|"
    r"sendDefaultEditorAction|sendDownUpKeyEvents|sendKeyChar)\s*\("
)

TRANSITIONAL_INPUT_CONNECTION_OWNERS = frozenset(
    {
        "com/opentypeless/android/ime/EditorEvidenceReader.java",
        "com/opentypeless/android/ime/OpenTypelessImeService.java",
        "com/opentypeless/android/ime/VoiceCompositionSession.java",
        "com/opentypeless/android/speech/delivery/AndroidInputConnectionAdapter.java",
    }
)
PERMANENT_INPUT_CONNECTION_OWNERS = frozenset(
    {
        EDITOR_TRANSACTION_MANAGER_PATH,
        "com/opentypeless/android/editor/host/EditorSessionManager.java",
        "com/opentypeless/android/editor/host/InputConnectionRegistry.java",
        "com/opentypeless/android/editor/host/ProcessInputConnectionRegistry.java",
    }
)
INPUT_CONNECTION_OWNERS = (
    TRANSITIONAL_INPUT_CONNECTION_OWNERS | PERMANENT_INPUT_CONNECTION_OWNERS
)

PROVIDER_PACKAGE_PREFIXES = (
    "com.opentypeless.android.action",
    "com.opentypeless.android.actions",
    "com.opentypeless.android.audio",
    "com.opentypeless.android.llm",
    "com.opentypeless.android.net",
    "com.opentypeless.android.offline",
    "com.opentypeless.android.personalization",
    "com.opentypeless.android.recognition",
    "com.opentypeless.android.rime",
    "com.opentypeless.android.transform",
)
PURE_DOMAIN_PACKAGE_PREFIXES = (
    "com.opentypeless.android.config",
    "com.opentypeless.android.speech.audio",
    "com.opentypeless.android.speech.core",
    "com.opentypeless.android.speech.engine",
    "com.opentypeless.android.speech.transform",
)
EDITOR_DOMAIN_PACKAGE = "com.opentypeless.android.editor"
EDITOR_HOST_PACKAGE = "com.opentypeless.android.editor.host"
# The composition root consumers will be added one-by-one by EDT migration tasks. EDT-002 exposes
# no host capability outside editor.host.
EDITOR_HOST_CAPABILITY_CONSUMERS = frozenset(
    {
        "com/opentypeless/android/ime/OpenTypelessImeService.java",
    }
)
FORBIDDEN_EXECUTION_TYPES = (
    INPUT_CONNECTION_TYPE,
    "android.inputmethodservice.InputMethodService",
    EDITOR_TRANSACTION_MANAGER_FQCN,
    "com.opentypeless.android.editor.InputConnectionRegistry",
    "com.opentypeless.android.editor.host",
    "com.opentypeless.android.ime.VoiceCompositionSession",
    "com.opentypeless.android.speech.delivery.AndroidInputConnectionAdapter",
    "com.opentypeless.android.speech.delivery.ProjectionConnection",
)

# Pure editor contracts are process-memory values, not persistence/wire formats. Keep the list
# intentionally broad at the source feedback layer; the compiled gate repeats this over actual
# descriptors, signatures and annotations so aliases/generated code cannot bypass it.
EDITOR_SERIALIZATION_TYPE_PREFIXES = (
    "java.io.Serializable",
    "java.io.Externalizable",
    "java.io.ObjectInput",
    "java.io.ObjectOutput",
    "android.os.Parcel",
    "android.os.Parcelable",
    "kotlinx.serialization",
    "com.fasterxml.jackson",
    "com.google.gson",
    "com.squareup.moshi",
    "org.json",
)
COMMIT_ENVELOPE_EXECUTION_TYPE_PREFIXES = (
    "java.lang.Runnable",
    "java.lang.Thread",
    "java.lang.ClassLoader",
    "java.lang.reflect",
    "java.lang.invoke",
    "java.util.concurrent.Callable",
    "java.util.concurrent.Executor",
    "java.util.concurrent.ExecutorService",
    "java.util.concurrent.ScheduledExecutorService",
    "java.util.function",
    "kotlin.jvm.functions",
)
COMMIT_LOOKUP_RECENCY_TOKENS = frozenset(
    {"latest", "last", "peek", "take", "poll", "current"}
)
UNDO_FACADE_SIGNATURE_PATTERN = re.compile(
    r"(?m)^[ \t]*EditorTransactionResult\s+undoCommit\s*\(\s*"
    r"String\s+commitId\s*,\s*EditorSessionSnapshot\s+expectedCurrent\s*,\s*"
    r"(?:EditorSessionManager\s*\.\s*)?LiveAuthoritySupplier\s+authoritySupplier\s*,\s*"
    r"(?:EditorSessionManager\s*\.\s*)?UndoEvidenceReader\s+evidenceReader\s*\)"
)
RAW_RESTORE_FACADE_SIGNATURE_PATTERN = re.compile(
    r"(?m)^[ \t]*EditorTransactionResult\s+restoreRawCommit\s*\(\s*"
    r"String\s+commitId\s*,\s*EditorSessionSnapshot\s+expectedCurrent\s*,\s*"
    r"(?:EditorSessionManager\s*\.\s*)?LiveAuthoritySupplier\s+authoritySupplier\s*,\s*"
    r"(?:EditorSessionManager\s*\.\s*)?UndoEvidenceReader\s+evidenceReader\s*\)"
)
UNDO_EVIDENCE_SCOPE_TOKENS = frozenset(
    {
        "UndoEvidenceReader",
        "UndoEvidenceRequest",
        "UndoEvidenceReadResult",
        "UndoEvidence",
        "UndoEvidenceUnavailable",
    }
)
RAW_RESTORE_SCOPE_TOKENS = frozenset({"RawTransition", "RawProofState"})
CURRENT_EVIDENCE_SCOPE_TOKENS = frozenset(
    {
        "CurrentEvidenceReader",
        "CurrentEvidenceRequest",
        "EvidenceReadResult",
        "CurrentEvidence",
        "EvidenceUnavailable",
        "ValidatedEvidence",
        "MaterializedEvidence",
        "EvidenceAttempt",
        "ReplaceTransition",
        "ReplaceProofState",
        "ReplaceValidationResult",
        "ReplaceValidated",
        "ReplaceValidationInvalid",
    }
)
KEYBOARD_HOST_ALLOWED_SOURCE_CONSUMERS = frozenset(
    {
        EDITOR_SESSION_MANAGER_PATH,
        "com/opentypeless/android/ime/OpenTypelessImeService.java",
    }
)
KEYBOARD_FACADE_PUBLIC_SIGNATURES = (
    re.compile(
        r"(?m)^[ \t]*public\s+EditorTransactionResult\s+insertKeyboardText\s*\(\s*"
        r"KeyboardHost\s+host\s*,\s*EditorSessionSnapshot\s+expected\s*,\s*"
        r"String\s+text\s*\)"
    ),
    re.compile(
        r"(?m)^[ \t]*public\s+EditorTransactionResult\s+deleteKeyboardBackward\s*\(\s*"
        r"KeyboardHost\s+host\s*,\s*EditorSessionSnapshot\s+expected\s*\)"
    ),
    re.compile(
        r"(?m)^[ \t]*public\s+EditorTransactionResult\s+performKeyboardEnter\s*\(\s*"
        r"KeyboardHost\s+host\s*,\s*EditorSessionSnapshot\s+expected\s*\)"
    ),
)
VOICE_FACADE_PUBLIC_SIGNATURES = (
    re.compile(
        r"(?m)^[ \t]*public\s+EditorTransactionResult\s+setVoiceComposition\s*\(\s*"
        r"KeyboardHost\s+host\s*,\s*EditorSessionSnapshot\s+expected\s*,\s*"
        r"String\s+text\s*,\s*long\s+revision\s*\)"
    ),
    re.compile(
        r"(?m)^[ \t]*public\s+TransactionReceipt\s+commitVoiceComposition\s*\(\s*"
        r"KeyboardHost\s+host\s*,\s*EditorSessionSnapshot\s+expected\s*,\s*"
        r"long\s+expectedRevision\s*,\s*CommitRecord\.RawTranscript\s+rawTranscript\s*\)"
    ),
    re.compile(
        r"(?m)^[ \t]*public\s+EditorTransactionResult\s+finishVoiceComposition\s*\(\s*"
        r"KeyboardHost\s+host\s*,\s*EditorSessionSnapshot\s+expected\s*,\s*"
        r"long\s+expectedRevision\s*\)"
    ),
    re.compile(
        r"(?m)^[ \t]*public\s+TransactionReceipt\s+commitVoiceText\s*\(\s*"
        r"KeyboardHost\s+host\s*,\s*EditorSessionSnapshot\s+expected\s*,\s*"
        r"String\s+text\s*,\s*CommitRecord\.RawTranscript\s+rawTranscript\s*\)"
    ),
    re.compile(
        r"(?m)^[ \t]*public\s+EditorTransactionResult\s+undoVoiceCommit\s*\(\s*"
        r"KeyboardHost\s+host\s*,\s*EditorSessionSnapshot\s+expectedCurrent\s*,\s*"
        r"String\s+commitId\s*\)"
    ),
    re.compile(
        r"(?m)^[ \t]*public\s+EditorTransactionResult\s+restoreRawVoiceCommit\s*\(\s*"
        r"KeyboardHost\s+host\s*,\s*EditorSessionSnapshot\s+expectedCurrent\s*,\s*"
        r"String\s+commitId\s*\)"
    ),
)
VOICE_TRANSACTION_CONFIG_PATH = (
    "com/opentypeless/android/speech/runtime/VoiceEditorTransactionConfig.java"
)
VOICE_CONTROLLER_PATH = "com/opentypeless/android/ime/VoiceController.java"
VOICE_PIPELINE_PATH = "com/opentypeless/android/ime/VoicePipeline.java"
VOICE_PIPELINE_RUNTIME_PATH = (
    "com/opentypeless/android/ime/VoicePipelineRuntime.java"
)
VOICE_PIPELINE_ADAPTER_PATH = (
    "com/opentypeless/android/ime/VoicePipelineAdapter.java"
)
AUDIO_CAPTURE_PATH = "com/opentypeless/android/audio/AudioCapture.java"
ANDROID_AUDIO_CAPTURE_PATH = (
    "com/opentypeless/android/audio/AndroidAudioCapture.java"
)
AUDIO_RECORDER_PATH = "com/opentypeless/android/audio/AudioRecorder.java"
RECORDING_SESSION_PATH = "com/opentypeless/android/audio/RecordingSession.java"
LOCAL_SPEECH_CORE_V2_SESSION_PATH = (
    "com/opentypeless/android/ime/LocalSpeechCoreV2Session.java"
)
STREAMING_RECOGNITION_ENGINE_PATH = (
    "com/opentypeless/android/net/streaming/StreamingRecognitionEngine.java"
)
PARAFORMER_STREAMING_RECOGNIZER_PATH = (
    "com/opentypeless/android/net/streaming/ParaformerStreamingRecognizer.java"
)
AUDIO_CAPTURE_ALLOWED_SOURCE_CONSUMERS = frozenset(
    {
        AUDIO_CAPTURE_PATH,
        ANDROID_AUDIO_CAPTURE_PATH,
        AUDIO_RECORDER_PATH,
        VOICE_PIPELINE_RUNTIME_PATH,
        LOCAL_SPEECH_CORE_V2_SESSION_PATH,
        STREAMING_RECOGNITION_ENGINE_PATH,
        PARAFORMER_STREAMING_RECOGNIZER_PATH,
        "com/opentypeless/android/offline/LocalRealtimePreview.java",
        "com/opentypeless/android/audio/Pcm16WaveDecoder.java",
    }
)
RAW_AUDIO_CAPTURE_ALLOWED_SOURCE_CONSUMERS = frozenset(
    {AUDIO_RECORDER_PATH, RECORDING_SESSION_PATH, ANDROID_AUDIO_CAPTURE_PATH}
)
TEXT_PROCESSING_PIPELINE_PATH = (
    "com/opentypeless/android/ime/TextProcessingPipeline.java"
)
STAGED_TEXT_PROCESSING_PIPELINE_PATH = (
    "com/opentypeless/android/ime/StagedTextProcessingPipeline.java"
)
DETERMINISTIC_PERSONALIZATION_STAGE_PATH = (
    "com/opentypeless/android/ime/DeterministicPersonalizationStage.java"
)
OPENAI_OPTIONAL_LLM_STAGE_PATH = (
    "com/opentypeless/android/ime/OpenAiOptionalLlmStage.java"
)
TRANSCRIPT_INTEGRITY_GUARD_STAGE_PATH = (
    "com/opentypeless/android/ime/TranscriptIntegrityGuardStage.java"
)
VOICE_RESULT_PATH = "com/opentypeless/android/ime/VoiceResult.java"
STAGE_PROVENANCE_PATH = "com/opentypeless/android/ime/StageProvenance.java"
DICTATION_RESULT_PATH = "com/opentypeless/android/ime/DictationResult.java"
IME_SERVICE_PATH = "com/opentypeless/android/ime/OpenTypelessImeService.java"
HISTORY_ACTIVITY_PATH = "com/opentypeless/android/HistoryActivity.java"
TEACH_CORRECTION_RESOLVER_PATH = (
    "com/opentypeless/android/personalization/TeachCorrectionResolver.java"
)
VOC008_TEACH_FACTORY_ALLOWED_SOURCE_CONSUMERS = frozenset(
    {IME_SERVICE_PATH, HISTORY_ACTIVITY_PATH}
)
VOICE_RESULT_DIRECT_CONSUMERS = frozenset(
    {
        "com/opentypeless/android/ime/OpenTypelessImeService.java",
        "com/opentypeless/android/VoiceLabActivity.java",
        "com/opentypeless/android/recognition/VoicePipelineRecognitionEngine.java",
    }
)
TEXT_PROCESSING_ALLOWED_SOURCE_CONSUMERS = frozenset(
    {
        VOICE_PIPELINE_RUNTIME_PATH,
        TEXT_PROCESSING_PIPELINE_PATH,
        STAGED_TEXT_PROCESSING_PIPELINE_PATH,
        DETERMINISTIC_PERSONALIZATION_STAGE_PATH,
        OPENAI_OPTIONAL_LLM_STAGE_PATH,
        TRANSCRIPT_INTEGRITY_GUARD_STAGE_PATH,
    }
)
VOICE_CONTROLLER_CONSUMERS = {
    "com/opentypeless/android/ime/OpenTypelessImeService.java": (
        "private VoiceController voiceController",
        "voiceController = RecognitionRouterVoiceConfig.select(",
        "new VoicePipelineAdapter(pipeline)",
        "voiceController.start(",
        "voiceController.stop()",
        "voiceController.cancel()",
        "voiceController.state()",
    ),
    "com/opentypeless/android/VoiceLabActivity.java": (
        "private VoiceController voiceController",
        "voiceController = RecognitionRouterVoiceConfig.select(",
        "new VoicePipelineAdapter(pipeline)",
        "voiceController.start(",
        "voiceController.stop()",
    ),
    "com/opentypeless/android/recognition/VoicePipelineRecognitionEngine.java": (
        "private final VoiceController voiceController",
        "voiceController = RecognitionRouterVoiceConfig.select(",
        "new VoicePipelineAdapter(pipeline)",
        "voiceController.start(",
        "voiceController.stop()",
        "voiceController.cancel()",
    ),
}
VOICE_PIPELINE_CORE_CALL_PATTERN = re.compile(
    r"\bpipeline\s*\.\s*(?:start|stopRecording|cancel|state)\s*\("
)
UNDO_EVIDENCE_ALLOWED_SOURCE_CONSUMERS = frozenset(
    {EDITOR_SESSION_MANAGER_PATH, EDITOR_TRANSACTION_MANAGER_PATH}
)
METHOD_DECLARATION_NAME_PATTERN = re.compile(
    r"(?m)^[ \t]*(?:(?:public|protected|private|static|final|synchronized|"
    r"abstract|default|native|strictfp)\s+)*"
    r"(?:<[^;{}()]+>\s+)?[\w$.,?@<>\[\] \t]+\s+([A-Za-z_$][\w$]*)\s*\("
)
STRICT_METHOD_DECLARATION_START_PATTERN = re.compile(
    r"(?m)^[ \t]*(?!(?:return|throw|new|if|else|for|while|do|switch|case|catch|"
    r"try|finally|synchronized|assert)\b)"
    r"(?:(?:public|protected|private|static|final|synchronized|abstract|default|"
    r"native|strictfp)\s+)*(?:<[^;{}()]+>\s+)?"
    r"[\w$.,?@<>\[\]]+\s+([A-Za-z_$][\w$]*)\s*\("
)
COMMIT_ENVELOPE_FORBIDDEN_DECLARATION_PATTERN = re.compile(
    r"\b(Throwable|[A-Za-z_$][\w$]*(?:Exception|Error)|Runnable|Thread|"
    r"ClassLoader|Callable|Executor|ExecutorService|ScheduledExecutorService|"
    r"Consumer|Supplier|Function|BiFunction|Predicate)\s+"
    r"[A-Za-z_$][\w$]*\s*(?=[,);=])"
)


@dataclass(frozen=True, order=True)
class ArchitectureViolation:
    relative_path: str
    rule: str
    detail: str

    def __str__(self) -> str:
        return f"{self.relative_path}: {self.rule}: {self.detail}"


class JavaUnicodeEscapeError(ValueError):
    """Raised when a compiler-eligible Java Unicode escape is malformed."""


def _translate_java_unicode_escapes(source: str) -> str:
    """Apply the JLS 17 section 3.3 translation that precedes Java tokenization.

    This must run before comment and string stripping: a Unicode escape may legally create a
    keyword, quote, comment delimiter, line terminator, qualified type name, or editor method name.
    A translated backslash is eligible to introduce the next raw escape but translation is not
    recursive within the characters produced by one escape.
    """

    output: list[str] = []
    index = 0
    raw_backslash_run = 0
    previous_from_escape = False
    while index < len(source):
        current = source[index]
        if current != "\\":
            output.append(current)
            index += 1
            raw_backslash_run = 0
            previous_from_escape = False
            continue

        eligible = previous_from_escape or raw_backslash_run % 2 == 0
        u_index = index + 1
        if eligible and u_index < len(source) and source[u_index] == "u":
            while u_index < len(source) and source[u_index] == "u":
                u_index += 1
            hex_end = u_index + 4
            digits = source[u_index:hex_end]
            if len(digits) != 4 or any(character not in "0123456789abcdefABCDEF" for character in digits):
                raise JavaUnicodeEscapeError(
                    f"malformed compiler-eligible Unicode escape at source offset {index}"
                )
            translated = chr(int(digits, 16))
            output.append(translated)
            index = hex_end
            previous_from_escape = True
            raw_backslash_run = raw_backslash_run + 1 if translated == "\\" else 0
            continue

        output.append(current)
        index += 1
        raw_backslash_run += 1
        previous_from_escape = False

    # JLS line terminators include CR and CRLF. Normalizing here lets the lexical stripper handle a
    # line terminator produced by an escape exactly like a physical newline.
    return "".join(output).replace("\r\n", "\n").replace("\r", "\n")


def _canonicalize_qualified_names(code: str) -> str:
    return QUALIFIED_DOT_PATTERN.sub(".", code)


def _is_java_identifier_ignorable(character: str) -> bool:
    value = ord(character)
    return (
        0x0000 <= value <= 0x0008
        or 0x000E <= value <= 0x001B
        or 0x007F <= value <= 0x009F
        or unicodedata.category(character) == "Cf"
    )


def _normalize_code_identifiers(code: str) -> str:
    """Match javac's identifier equality after strings and comments have been removed."""

    return "".join(
        character for character in code if not _is_java_identifier_ignorable(character)
    )


def _collapse_adjacent_string_literals(source_without_comments: str) -> str:
    """Join only the quote boundary in simple constant string concatenations.

    The result is used solely to spot reflective construction of an InputConnection class name.
    It is deliberately not used for package/import parsing or general source matching.
    """

    collapsed = source_without_comments
    boundary = re.compile(r'"\s*\+\s*"')
    while True:
        updated = boundary.sub("", collapsed)
        if updated == collapsed:
            return collapsed
        collapsed = updated


def _strip_lexical(source: str, *, strings: bool) -> str:
    """Remove comments and optionally literals while preserving lines and token boundaries."""

    output: list[str] = []
    index = 0
    state = "code"
    quote = ""
    while index < len(source):
        current = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""
        triple = source[index : index + 3]

        if state == "code":
            if current == "/" and following == "/":
                output.extend("  ")
                index += 2
                state = "line_comment"
                continue
            if current == "/" and following == "*":
                output.extend("  ")
                index += 2
                state = "block_comment"
                continue
            if triple == '\"\"\"':
                output.extend("   " if strings else triple)
                index += 3
                state = "triple_string"
                continue
            if current in ('\"', "'"):
                quote = current
                output.append(" " if strings else current)
                index += 1
                state = "string"
                continue
            output.append(current)
            index += 1
            continue

        if state == "line_comment":
            output.append("\n" if current == "\n" else " ")
            index += 1
            if current == "\n":
                state = "code"
            continue

        if state == "block_comment":
            if current == "*" and following == "/":
                output.extend("  ")
                index += 2
                state = "code"
            else:
                output.append("\n" if current == "\n" else " ")
                index += 1
            continue

        if state == "triple_string":
            if triple == '\"\"\"':
                preceding_backslashes = 0
                cursor = index - 1
                while cursor >= 0 and source[cursor] == "\\":
                    preceding_backslashes += 1
                    cursor -= 1
                if preceding_backslashes % 2 == 0:
                    output.extend("   " if strings else triple)
                    index += 3
                    state = "code"
                else:
                    output.extend("   " if strings else triple)
                    index += 3
            else:
                output.append("\n" if strings and current == "\n" else (" " if strings else current))
                index += 1
            continue

        if state == "string":
            if current == "\\" and following:
                if strings:
                    output.extend("  ")
                else:
                    output.extend((current, following))
                index += 2
                continue
            output.append(" " if strings and current != "\n" else current)
            index += 1
            if current == quote:
                state = "code"
            continue

    return "".join(output)


def _has_kotlin_escaped_identifier(source: str) -> bool:
    """Kotlin backtick identifiers can hide every capability and method name from text rules."""

    return bool(re.search(r"`[^`\r\n]+`", _strip_lexical(source, strings=True)))


def _package_name(code: str) -> str:
    match = PACKAGE_PATTERN.search(code)
    return match.group(1) if match else ""


def _imports(code: str) -> tuple[str, ...]:
    return tuple(IMPORT_PATTERN.findall(code))


def _under(package_name: str, prefixes: tuple[str, ...]) -> bool:
    return any(
        package_name == prefix or package_name.startswith(prefix + ".")
        for prefix in prefixes
    )


def _is_ui(relative_path: str, package_name: str) -> bool:
    filename = Path(relative_path).name
    return (
        filename.endswith("Activity.java")
        or filename.endswith("Activity.kt")
        or filename.endswith("View.java")
        or filename.endswith("View.kt")
        or filename.endswith("Fragment.java")
        or filename.endswith("Fragment.kt")
        or filename.endswith("Screen.java")
        or filename.endswith("Screen.kt")
        or ".ui" in package_name
        or ".presentation" in package_name
    )


def _has_type_prefix(value: str, prefixes: tuple[str, ...]) -> bool:
    return any(value == prefix or value.startswith(prefix + ".") for prefix in prefixes)


def _identifier_tokens(value: str) -> frozenset[str]:
    separated = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", value)
    return frozenset(token for token in separated.lower().split("_") if token)


def _is_forbidden_commit_lookup_name(value: str) -> bool:
    tokens = _identifier_tokens(value)
    # These protected owners have no legitimate recency API. Reject the semantic token itself so
    # resolveLatest()/fetchCurrent() cannot evade the rule by erasing its return to Object.
    return bool(tokens.intersection(COMMIT_LOOKUP_RECENCY_TOKENS))


def _enclosing_method_declaration(code: str, offset: int) -> re.Match[str] | None:
    """Best-effort source feedback; the compiled gate repeats this over exact bytecode."""

    declaration = None
    for match in STRICT_METHOD_DECLARATION_START_PATTERN.finditer(code, 0, offset):
        declaration = match
    return declaration


def _format_counter(counter: Counter[str]) -> str:
    return ", ".join(f"{key}={counter[key]}" for key in sorted(counter)) or "none"


def _inspect_cfg001_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in CFG001_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    expected_imports = CFG001_ALLOWED_IMPORTS[relative_path]
    if set(imports) != set(expected_imports) or CFG001_FORBIDDEN_TYPE_PATTERN.search(code):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG001_DOMAIN_DEPENDENCY",
                "provider config may use only the audited Java value/URI dependencies",
            )
        )
    if CFG001_SECRET_IDENTIFIER_PATTERN.search(code):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG001_SECRET_BOUNDARY",
                "provider config may retain only opaque SecretRef identity, never secret material",
            )
        )

    if relative_path == PROVIDER_CONFIG_PATH:
        exact_interface = re.search(
            r"(?ms)\bpublic\s+sealed\s+interface\s+ProviderConfig\s+permits\s+"
            r"ProviderConfig\.Asr\s*,\s*ProviderConfig\.Llm\s*,\s*"
            r"ProviderConfig\.Connector\s*\{",
            code,
        )
        exact_asr = re.search(
            r"(?ms)\brecord\s+Asr\s*\(\s*String\s+id\s*,\s*String\s+displayName\s*,\s*"
            r"Optional\s*<\s*Endpoint\s*>\s+endpoint\s*,\s*"
            r"Optional\s*<\s*String\s*>\s+modelId\s*,\s*"
            r"Optional\s*<\s*SecretRef\s*>\s+secretRef\s*,\s*boolean\s+enabled\s*\)"
            r"\s*implements\s+ProviderConfig\s*\{",
            code,
        )
        exact_llm = re.search(
            r"(?ms)\brecord\s+Llm\s*\(\s*String\s+id\s*,\s*String\s+displayName\s*,\s*"
            r"Optional\s*<\s*Endpoint\s*>\s+endpoint\s*,\s*"
            r"Optional\s*<\s*String\s*>\s+modelId\s*,\s*"
            r"Optional\s*<\s*SecretRef\s*>\s+secretRef\s*,\s*boolean\s+enabled\s*\)"
            r"\s*implements\s+ProviderConfig\s*\{",
            code,
        )
        exact_connector = re.search(
            r"(?ms)\brecord\s+Connector\s*\(\s*String\s+id\s*,\s*"
            r"String\s+displayName\s*,\s*Optional\s*<\s*Endpoint\s*>\s+endpoint\s*,\s*"
            r"Optional\s*<\s*SecretRef\s*>\s+secretRef\s*,\s*boolean\s+enabled\s*\)"
            r"\s*implements\s+ProviderConfig\s*\{",
            code,
        )
        exact_endpoint = re.search(
            r"(?ms)\brecord\s+Endpoint\s*\(\s*String\s+value\s*\)\s*\{",
            code,
        )
        constants = (
            "MAX_ID_CODE_POINTS = 128",
            "MAX_DISPLAY_NAME_CODE_POINTS = 80",
            "MAX_MODEL_ID_CODE_POINTS = 256",
            "MAX_ENDPOINT_CODE_POINTS = 2_048",
        )
        if (
            exact_interface is None
            or exact_asr is None
            or exact_llm is None
            or exact_connector is None
            or exact_endpoint is None
            or any(value not in code for value in constants)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG001_PROVIDER_MODEL_SHAPE",
                    "expected exact sealed ASR/LLM/Connector records and bounded Endpoint",
                )
            )

        required_validation_tokens = (
            "requireProviderId",
            "requireDisplayName",
            "requireModelId",
            "requireSecretRef",
            "requireEndpoint",
            "requireWellFormedUtf16",
            "getUserInfo",
            "getQuery",
            "getFragment",
            "rejectDotSegments",
            "rejectDecodedPathControls",
            "isLocalHost",
            "isLoopback",
            "Character.isISOControl",
        )
        exact_kind_bindings = (
            code.count("SecretRef.Kind.ASR") == 1
            and code.count("SecretRef.Kind.LLM") == 1
            and code.count("SecretRef.Kind.CONNECTOR") == 1
        )
        if (
            any(token not in code for token in required_validation_tokens)
            or not exact_kind_bindings
            or no_comments.count("details=<redacted>") != 1
            or no_comments.count("value=<redacted>") != 1
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG001_PROVIDER_VALIDATION",
                    "provider construction must enforce ID/text/URI/kind/transport and redaction",
                )
            )
    else:
        exact_record = re.search(
            r"(?ms)\bpublic\s+record\s+SecretRef\s*\(\s*Kind\s+kind\s*,\s*"
            r"String\s+opaqueId\s*\)\s*\{",
            code,
        )
        exact_kind = re.search(
            r"(?ms)\bpublic\s+enum\s+Kind\s*\{\s*ASR\s*,\s*LLM\s*,\s*CONNECTOR\s*\}",
            code,
        )
        if (
            exact_record is None
            or exact_kind is None
            or "MIN_OPAQUE_ID_CODE_POINTS = 20" not in code
            or "MAX_OPAQUE_ID_CODE_POINTS = 128" not in code
            or "private static final String PREFIX" not in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG001_SECRET_REF_SHAPE",
                    "expected exact kind-bound opaque SecretRef record",
                )
            )
        if (
            "requireOpaqueId" not in code
            or "opaqueId=<redacted>" not in no_comments
            or "startsWith(PREFIX)" not in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG001_SECRET_BOUNDARY",
                    "SecretRef must validate and redact its opaque identifier",
                )
            )
    return tuple(findings)


def _inspect_cfg002_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path != RECOGNITION_ROUTE_PATH:
        return ()

    findings: list[ArchitectureViolation] = []
    if (
        set(imports) != set(CFG002_ALLOWED_IMPORTS)
        or CFG001_FORBIDDEN_TYPE_PATTERN.search(code)
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG002_DOMAIN_DEPENDENCY",
                "recognition route may use only the audited Java collection value types",
            )
        )
    if (
        CFG001_SECRET_IDENTIFIER_PATTERN.search(code)
        or "com.opentypeless.android.diagnostics" in code
        or re.search(r"\b(?:ProviderConfig|SecretRef|Endpoint|RecognitionBackend)\b", code)
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG002_AUTHORITY_BOUNDARY",
                "route config may not carry legacy routing, provider instances, endpoints, or secrets",
            )
        )

    exact_route = re.search(
        r"(?ms)\bpublic\s+record\s+RecognitionRoute\s*\(\s*String\s+id\s*,\s*"
        r"List\s*<\s*RouteStep\s*>\s+steps\s*,\s*PrivacyClass\s+privacyFloor\s*,\s*"
        r"boolean\s+allowPrivacyDowngrade\s*\)\s*\{",
        code,
    )
    exact_step = re.search(
        r"(?ms)\bpublic\s+record\s+RouteStep\s*\(\s*String\s+providerId\s*,\s*"
        r"PrivacyClass\s+privacyClass\s*,\s*RetryPolicy\s+retryPolicy\s*,\s*"
        r"Set\s*<\s*FailureClass\s*>\s+fallbackOn\s*,\s*"
        r"Set\s*<\s*ProviderCapability\s*>\s+requiredCapabilities\s*,\s*"
        r"ConfirmationPolicy\s+confirmationPolicy\s*\)\s*\{",
        code,
    )
    exact_retry = re.search(
        r"(?ms)\bpublic\s+record\s+RetryPolicy\s*\(\s*int\s+maximumAttempts\s*,\s*"
        r"Set\s*<\s*FailureClass\s*>\s+retryOn\s*\)\s*\{",
        code,
    )
    exact_privacy = re.search(
        r"(?ms)\bpublic\s+enum\s+PrivacyClass\s*\{\s*ON_DEVICE\s*,\s*"
        r"LOCAL_NETWORK\s*,\s*PUBLIC_NETWORK\s*\}",
        code,
    )
    exact_capabilities = re.search(
        r"(?ms)\bpublic\s+enum\s+ProviderCapability\s*\{\s*STREAMING\s*,\s*"
        r"PARTIAL_REVISION\s*,\s*ENDPOINTING\s*,\s*ON_DEVICE\s*,\s*PROMPT\s*,\s*"
        r"BIASING_TERMS\s*,\s*DYNAMIC_KEYTERMS\s*,\s*LANGUAGE_DETECTION\s*,\s*"
        r"TIMESTAMPS\s*,\s*AUDIO_UPLOAD\s*\}",
        code,
    )
    exact_failures = re.search(
        r"(?ms)\bpublic\s+enum\s+FailureClass\s*\{\s*UNAVAILABLE\s*,\s*"
        r"MODEL_MISSING\s*,\s*PERMISSION_DENIED\s*,\s*OEM_MIC_BLOCKED\s*,\s*"
        r"AUDIO_ERROR\s*,\s*NETWORK_UNAVAILABLE\s*,\s*NETWORK_TIMEOUT\s*,\s*"
        r"AUTHENTICATION\s*,\s*QUOTA_EXCEEDED\s*,\s*RATE_LIMITED\s*,\s*"
        r"SERVER_ERROR\s*,\s*PROTOCOL_ERROR\s*,\s*RECOGNIZER_BUSY\s*,\s*"
        r"NO_MATCH\s*,\s*SPEECH_TIMEOUT\s*,\s*UNSUPPORTED_LANGUAGE\s*,\s*"
        r"CANCELLED\s*,\s*TARGET_CHANGED\s*,\s*INTERNAL_ERROR\s*\}",
        code,
    )
    exact_confirmation = re.search(
        r"(?ms)\bpublic\s+enum\s+ConfirmationPolicy\s*\{\s*NOT_REQUIRED\s*,\s*"
        r"REQUIRE_ON_PRIVACY_DOWNGRADE\s*,\s*REQUIRE_BEFORE_USE\s*\}",
        code,
    )
    if (
        exact_route is None
        or exact_step is None
        or exact_retry is None
        or exact_privacy is None
        or exact_capabilities is None
        or exact_failures is None
        or exact_confirmation is None
        or "MAX_ID_CODE_POINTS = 128" not in code
        or "MAX_STEPS = 8" not in code
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG002_ROUTE_MODEL_SHAPE",
                "expected exact bounded route/step/retry records and closed vocabularies",
            )
        )

    validation_tokens = (
        "List.copyOf",
        "boundedSteps",
        "immutableEnumSet",
        "providerIds.add",
        "NON_ROUTABLE_FAILURES",
        "Collections.disjoint",
        "maximumAttempts < 1",
        "maximumAttempts > 2",
        "privacyClass.ordinal()",
        "(privacyClass == PrivacyClass.ON_DEVICE) != claimsOnDevice",
        "ProviderCapability.AUDIO_UPLOAD",
        "step.confirmationPolicy() == ConfirmationPolicy.NOT_REQUIRED",
        "step.fallbackOn().isEmpty()",
        "FailureClass.AUTHENTICATION",
        "ConfirmationPolicy.REQUIRE_BEFORE_USE",
        "steps.size() > MAX_STEPS",
    )
    if any(token not in code for token in validation_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG002_ROUTE_VALIDATION",
                "route construction must bound and copy inputs and reject fallback/privacy contradictions",
            )
        )
    if (
        no_comments.count("id=<redacted>") != 1
        or no_comments.count("providerId=<redacted>") != 1
        or "steps=" in no_comments
        or 'providerId=" + providerId' in no_comments
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG002_ROUTE_REDACTION",
                "route diagnostics must redact route/provider identity and never print the step list",
            )
        )
    return tuple(findings)


def _inspect_rec001_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in REC001_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    if (
        set(imports) != set(REC001_ALLOWED_IMPORTS[relative_path])
        or CFG001_FORBIDDEN_TYPE_PATTERN.search(code)
        or CFG001_SECRET_IDENTIFIER_PATTERN.search(code)
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC001_DOMAIN_DEPENDENCY",
                "provider descriptors may use only audited value/config types and no Android, "
                "execution, persistence, serialization, endpoint, or secret capability",
            )
        )

    backend_cases = (
        "case OPENAI_COMPATIBLE",
        "case LOCAL_OFFLINE",
        "case DASHSCOPE_STREAMING",
        "case SYSTEM_ON_DEVICE",
        "case SYSTEM_DEFAULT",
    )
    exact_factory = re.search(
        r"(?m)^\s*public\s+static\s+(?:ProviderCapabilities|ProviderDescriptor)\s+"
        r"declaredForBackend\s*\(\s*RecognitionBackend\s+backend\s*\)",
        code,
    )
    if (
        exact_factory is None
        or "switch (backend)" not in code
        or any(code.count(case) != 1 for case in backend_cases)
        or re.search(
            r"declaredForBackend\s*\(\s*(?:String|CharSequence|Object)\b",
            code,
        )
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC001_EXPLICIT_DECLARATION",
                "built-in descriptors/capabilities require one exhaustive RecognitionBackend "
                "switch and may not infer behavior from names",
            )
        )

    if relative_path == PROVIDER_CAPABILITIES_PATH:
        exact_record = re.search(
            r"(?ms)\bpublic\s+record\s+ProviderCapabilities\s*\(\s*"
            r"boolean\s+supportsStreaming\s*,\s*"
            r"boolean\s+supportsPartialRevision\s*,\s*"
            r"boolean\s+supportsEndpointing\s*,\s*"
            r"boolean\s+supportsOnDevice\s*,\s*"
            r"boolean\s+supportsPrompt\s*,\s*"
            r"boolean\s+supportsBiasingTerms\s*,\s*"
            r"boolean\s+supportsDynamicKeyterms\s*,\s*"
            r"boolean\s+supportsLanguageDetection\s*,\s*"
            r"boolean\s+supportsTimestamps\s*,\s*"
            r"boolean\s+supportsAudioUpload\s*,\s*"
            r"ImplementationKind\s+implementationKind\s*,\s*"
            r"RecognitionRoute\.PrivacyClass\s+privacyClass\s*,\s*"
            r"Long\s+maxAudioDurationMs\s*,\s*"
            r"Set\s*<\s*AudioFormat\s*>\s+supportedAudioFormats\s*\)",
            code,
        )
        exact_audio_format = re.search(
            r"(?ms)\bpublic\s+enum\s+AudioFormat\s*\{\s*"
            r"PCM_16_MONO_16000_HZ\s*\}",
            code,
        )
        exact_implementation_kind = re.search(
            r"(?ms)\bpublic\s+enum\s+ImplementationKind\s*\{\s*"
            r"BATCH_FINAL\s*,\s*NATIVE_STREAMING\s*,\s*PREFIX_REPLAY\s*\}",
            code,
        )
        exact_prefix_factory = re.search(
            r"(?ms)static\s+ProviderCapabilities\s+prefixReplayPreview\s*\(\s*\)\s*\{\s*"
            r"return\s+new\s+ProviderCapabilities\s*\(\s*"
            r"false\s*,\s*true\s*,\s*false\s*,\s*true\s*,\s*"
            r"false\s*,\s*false\s*,\s*false\s*,\s*true\s*,\s*"
            r"false\s*,\s*false\s*,\s*ImplementationKind\.PREFIX_REPLAY\s*,\s*"
            r"RecognitionRoute\.PrivacyClass\.ON_DEVICE\s*,\s*30_000L\s*,\s*"
            r"PCM_16_MONO_16_KHZ\s*\)\s*;\s*\}",
            code,
        )
        if (
            exact_record is None
            or exact_audio_format is None
            or exact_implementation_kind is None
            or exact_prefix_factory is None
            or "MAX_DECLARED_AUDIO_DURATION_MS = 86_400_000L" not in code
            or "APP_CAPTURE_LIMIT_MS = 540_000L" not in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC001_CAPABILITY_SHAPE",
                    "ProviderCapabilities must retain the exact ten capability flags, closed "
                    "implementation kind, privacy, bounded duration, and audio-format set",
                )
            )
        invariant_tokens = (
            "immutableFormats",
            "implementationKind != ImplementationKind.PREFIX_REPLAY",
            "supportsEndpointing && !supportsStreaming",
            "supportsDynamicKeyterms",
            "supportsOnDevice != declaresOnDevicePrivacy",
            "supportsOnDevice && supportsAudioUpload",
            "case BATCH_FINAL ->",
            "case NATIVE_STREAMING ->",
            "case PREFIX_REPLAY ->",
            "static ProviderCapabilities prefixReplayPreview()",
            "Collections.unmodifiableSet",
        )
        if (
            any(token not in code for token in invariant_tokens)
            or "audioFormatCount=" not in no_comments
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC001_CAPABILITY_INVARIANTS",
                    "capability construction must reject contradictory privacy/streaming claims, "
                    "bound duration, copy formats, and redact diagnostics",
                )
            )
    else:
        exact_descriptor = re.search(
            r"(?ms)\bpublic\s+record\s+ProviderDescriptor\s*\(\s*"
            r"String\s+id\s*,\s*String\s+displayName\s*,\s*"
            r"ProviderCapabilities\s+capabilities\s*\)",
            code,
        )
        if (
            exact_descriptor is None
            or "MAX_ID_CODE_POINTS = 128" not in code
            or "MAX_DISPLAY_NAME_CODE_POINTS = 80" not in code
            or "requireId" not in code
            or "requireDisplayName" not in code
            or "requireText" not in code
            or "Character.isISOControl" not in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC001_DESCRIPTOR_SHAPE",
                    "ProviderDescriptor must remain an exact bounded id/display/capabilities record",
                )
            )
        if (
            "id=<redacted>" not in no_comments
            or "displayName=<redacted>" not in no_comments
            or "ProviderCapabilities.declaredForBackend(backend)" not in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC001_DESCRIPTOR_REDACTION",
                    "descriptor diagnostics must redact identity and bind the explicit capability "
                    "declaration",
                )
            )
    return tuple(findings)


def _inspect_rec002_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in REC002_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    forbidden_domain = re.compile(
        r"\b(?:Serializable|Externalizable|Parcelable|Parcel|Bundle|Intent|Context|"
        r"InputConnection|EditorOperation|Executor|Thread|Future|File|Path|Socket|URL|"
        r"SharedPreferences|DataStore|RoomDatabase|SecretRef|ProviderConfig)\b"
        r"|(?<![\w$.])(?:android|androidx|java\.io|java\.net|java\.nio\.file|"
        r"java\.util\.concurrent|kotlinx\.serialization|com\.google\.gson|"
        r"com\.squareup\.moshi|com\.fasterxml\.jackson)\.",
    )
    if (
        set(imports) != set(REC002_ALLOWED_IMPORTS[relative_path])
        or forbidden_domain.search(code)
        or CFG001_SECRET_IDENTIFIER_PATTERN.search(code)
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC002_DOMAIN_DEPENDENCY",
                "recognition events and their sequence gate must remain immutable pure data with "
                "no Android, execution, persistence, serialization, endpoint, or secret authority",
            )
        )

    if relative_path == RECOGNITION_EVENT_PATH:
        exact_permits = re.search(
            r"(?ms)\bpublic\s+sealed\s+interface\s+RecognitionEvent\s+permits\s+"
            r"RecognitionEvent\.Preparing\s*,\s*RecognitionEvent\.Ready\s*,\s*"
            r"RecognitionEvent\.SpeechStarted\s*,\s*RecognitionEvent\.Partial\s*,\s*"
            r"RecognitionEvent\.Endpoint\s*,\s*RecognitionEvent\.Final\s*,\s*"
            r"RecognitionEvent\.Failure\s*,\s*RecognitionEvent\.Cancelled\s*\{",
            code,
        )
        exact_variants = (
            re.search(
                r"\brecord\s+Preparing\s*\(\s*SessionId\s+sessionId\s*,\s*"
                r"long\s+sequence\s*\)\s+implements\s+RecognitionEvent",
                code,
            ),
            re.search(
                r"\brecord\s+Ready\s*\(\s*SessionId\s+sessionId\s*,\s*"
                r"long\s+sequence\s*\)\s+implements\s+RecognitionEvent",
                code,
            ),
            re.search(
                r"\brecord\s+SpeechStarted\s*\(\s*SessionId\s+sessionId\s*,\s*"
                r"long\s+sequence\s*\)\s+implements\s+RecognitionEvent",
                code,
            ),
            re.search(
                r"(?ms)\brecord\s+Partial\s*\(\s*SessionId\s+sessionId\s*,\s*"
                r"long\s+sequence\s*,\s*String\s+text\s*,\s*"
                r"Integer\s+stablePrefixLength\s*,\s*Long\s+revisionOf\s*\)\s*"
                r"implements\s+RecognitionEvent",
                code,
            ),
            re.search(
                r"\brecord\s+Endpoint\s*\(\s*SessionId\s+sessionId\s*,\s*"
                r"long\s+sequence\s*\)\s+implements\s+RecognitionEvent",
                code,
            ),
            re.search(
                r"(?ms)\brecord\s+Final\s*\(\s*SessionId\s+sessionId\s*,\s*"
                r"long\s+sequence\s*,\s*String\s+text\s*,\s*"
                r"RecognitionMetadata\s+metadata\s*\)\s*implements\s+RecognitionEvent",
                code,
            ),
            re.search(
                r"(?ms)\brecord\s+Failure\s*\(\s*SessionId\s+sessionId\s*,\s*"
                r"long\s+sequence\s*,\s*RecognitionRoute\.FailureClass\s+failureClass\s*\)\s*"
                r"implements\s+RecognitionEvent",
                code,
            ),
            re.search(
                r"\brecord\s+Cancelled\s*\(\s*SessionId\s+sessionId\s*,\s*"
                r"long\s+sequence\s*\)\s+implements\s+RecognitionEvent",
                code,
            ),
        )
        if (
            exact_permits is None
            or any(variant is None for variant in exact_variants)
            or "int MAX_TEXT_CODE_POINTS = 20_000" not in code
            or "SessionId sessionId()" not in code
            or "long sequence()" not in code
            or "default boolean terminal()" not in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC002_EVENT_SHAPE",
                    "RecognitionEvent must retain the exact eight sealed immutable variants and "
                    "common session/sequence/terminal surface",
                )
            )

        invariant_tokens = (
            "sequence <= 0L",
            "text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS",
            "Character.isHighSurrogate",
            "Character.isLowSurrogate",
            "stablePrefixLength < 0",
            "stablePrefixLength > text.length()",
            "splitsSurrogate(text, stablePrefixLength)",
            "revisionOf <= 0L",
            "revisionOf >= sequence",
            "text.isBlank()",
            "failureClass == RecognitionRoute.FailureClass.CANCELLED",
        )
        if any(token not in code for token in invariant_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC002_EVENT_BOUNDS",
                    "event text, UTF-16 boundaries, sequences, revisions, and terminal failure "
                    "classification must remain bounded and explicit",
                )
            )
        if (
            no_comments.count("return redacted(") != 8
            or "content=<redacted>" not in no_comments
            or re.search(r"toString\s*\([^)]*\)[^{]*\{[^}]*\+\s*(?:text|sessionId)", no_comments)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC002_EVENT_REDACTION",
                    "every recognition event diagnostic must redact session and recognized text",
                )
            )
    elif relative_path == RECOGNITION_METADATA_PATH:
        exact_record = re.search(
            r"(?ms)\bpublic\s+record\s+RecognitionMetadata\s*\(\s*"
            r"String\s+detectedLanguageTag\s*,\s*Float\s+confidence\s*,\s*"
            r"Long\s+audioDurationMs\s*\)",
            code,
        )
        if (
            exact_record is None
            or "MAX_LANGUAGE_TAG_CODE_POINTS = 63" not in code
            or "public static RecognitionMetadata empty()" not in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC002_METADATA_SHAPE",
                    "final metadata must remain the exact optional language/confidence/duration record",
                )
            )
        metadata_tokens = (
            "value.codePointCount(0, value.length()) > MAX_LANGUAGE_TAG_CODE_POINTS",
            "new Locale.Builder().setLanguageTag(value).build().toLanguageTag()",
            "Float.isFinite(confidence)",
            "confidence < 0f",
            "confidence > 1f",
            "audioDurationMs <= 0L",
            "audioDurationMs > ProviderCapabilities.APP_CAPTURE_LIMIT_MS",
            "languageDeclared=",
            "confidenceDeclared=",
            "durationDeclared=",
        )
        if (
            any(token not in no_comments for token in metadata_tokens)
            or re.search(
                r"toString\s*\([^)]*\)[^{]*\{[^}]*(?:detectedLanguageTag\s*\+|"
                r"\+\s*detectedLanguageTag)",
                no_comments,
            )
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC002_METADATA_BOUNDS",
                    "recognition metadata must validate its optional values and expose only presence diagnostics",
                )
            )
    else:
        exact_class = re.search(
            r"(?ms)\bpublic\s+final\s+class\s+RecognitionEventValidator\s*\{.*?"
            r"private\s+final\s+SessionId\s+sessionId\s*;\s*"
            r"private\s+long\s+lastSequence\s*;\s*"
            r"private\s+long\s+lastPartialSequence\s*;\s*"
            r"private\s+boolean\s+terminal\s*;",
            code,
        )
        exact_disposition = re.search(
            r"(?ms)\bpublic\s+enum\s+Disposition\s*\{\s*ACCEPTED\s*,\s*"
            r"REJECTED_SESSION\s*,\s*REJECTED_SEQUENCE\s*,\s*"
            r"REJECTED_REVISION\s*,\s*DROPPED_AFTER_TERMINAL\s*\}",
            code,
        )
        if (
            exact_class is None
            or exact_disposition is None
            or re.search(
                r"public\s+synchronized\s+Disposition\s+accept\s*\(\s*"
                r"RecognitionEvent\s+event\s*\)",
                code,
            )
            is None
            or re.search(r"public\s+synchronized\s+String\s+toString\s*\(", code) is None
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC002_VALIDATOR_SHAPE",
                    "the validator must remain a synchronized O(1) session/sequence/terminal gate",
                )
            )
        sequencing_tokens = (
            "!sessionId.equals(candidate.sessionId())",
            "if (terminal)",
            "candidate.sequence() <= lastSequence",
            "partial.revisionOf() != lastPartialSequence",
            "lastSequence = candidate.sequence()",
            "lastPartialSequence = candidate.sequence()",
            "terminal = candidate.terminal()",
            "session=<redacted>",
        )
        if any(token not in no_comments for token in sequencing_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC002_SEQUENCE_TERMINAL",
                    "foreign, stale, revision, and post-terminal events must fail closed without "
                    "storing event content",
                )
            )
    return tuple(findings)


def _inspect_str001_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    if relative_path != STREAMING_RECOGNITION_WIRE_EVENT_PATH:
        if "StreamingRecognitionWireEvent.decode(" in no_comments:
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR001_RAW_DECODE_CALLER",
                    "production callers must enter the session-bound Stream gate rather than "
                    "decode a wire event directly",
                )
            )
        return tuple(findings)

    forbidden_authority = re.compile(
        r"\b(?:Serializable|Externalizable|Parcelable|Parcel|Bundle|Intent|Context|"
        r"InputConnection|EditorOperation|AudioRecord|AudioCapture|Executor|Thread|Future|"
        r"File|Path|Socket|URL|WebSocket|EventSource|OkHttpClient|SharedPreferences|"
        r"DataStore|RoomDatabase|SecretRef|ProviderConfig|Log)\b"
        r"|(?<![\w$.])(?:android|androidx|java\.io|java\.net|java\.nio\.file|"
        r"java\.util\.concurrent|okhttp3|retrofit2|kotlinx\.serialization|"
        r"com\.google\.gson|com\.squareup\.moshi|com\.fasterxml\.jackson)\.",
    )
    if (
        set(imports) != set(STR001_ALLOWED_IMPORTS)
        or forbidden_authority.search(code)
        or CFG001_SECRET_IDENTIFIER_PATTERN.search(code)
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR001_DOMAIN_DEPENDENCY",
                "the wire contract may parse bounded JSON but must not acquire Android, network, "
                "editor, audio, execution, persistence, serialization, or secret authority",
            )
        )

    exact_surface = (
        re.search(
            r"(?m)^final\s+class\s+StreamingRecognitionWireEvent\s*\{",
            code,
        )
        is not None
        and "static final String PROTOCOL = \"opentypeless.streaming.v1\"" in no_comments
        and "static final int MAX_JSON_UTF16_UNITS = 524_288" in no_comments
        and re.search(
            r"(?ms)static\s+final\s+class\s+Stream\s*\{\s*"
            r"private\s+final\s+RecognitionEventValidator\s+validator\s*;",
            code,
        )
        is not None
        and re.search(
            r"sealed\s+interface\s+Result\s+permits\s+Accepted\s*,\s*Rejected",
            code,
        )
        is not None
        and re.search(
            r"record\s+Accepted\s*\(\s*RecognitionEvent\s+event\s*\)\s+implements\s+Result",
            code,
        )
        is not None
        and re.search(
            r"record\s+Rejected\s*\(\s*Rejection\s+reason\s*\)\s+implements\s+Result",
            code,
        )
        is not None
        and re.search(
            r"(?ms)enum\s+Rejection\s*\{\s*MALFORMED\s*,\s*FOREIGN_SESSION\s*,\s*"
            r"NON_MONOTONIC_SEQUENCE\s*,\s*INVALID_REVISION\s*,\s*AFTER_TERMINAL\s*\}",
            code,
        )
        is not None
    )
    if not exact_surface:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR001_WIRE_SHAPE",
                "the v1 wire contract must remain package-confined with one session-bound Stream "
                "and a closed redacted result/rejection surface",
            )
        )

    wire_tokens = (
        '"protocol", "session_id", "sequence", "type"',
        'root.put("type", "preparing")',
        'root.put("type", "ready")',
        'root.put("type", "speech_started")',
        'root.put("type", "partial")',
        'root.put("type", "endpoint")',
        'root.put("type", "final")',
        'root.put("type", "failure")',
        'root.put("type", "cancelled")',
        "new JSONTokener(json)",
        "tokener.nextClean() != 0",
        "json.length() > MAX_JSON_UTF16_UNITS",
        "requireAllowedAndRequiredKeys",
        "new RecognitionEvent.Preparing(",
        "new RecognitionEvent.Ready(",
        "new RecognitionEvent.SpeechStarted(",
        "new RecognitionEvent.Partial(",
        "new RecognitionEvent.Endpoint(",
        "new RecognitionEvent.Final(",
        "new RecognitionEvent.Failure(",
        "new RecognitionEvent.Cancelled(",
    )
    if any(token not in no_comments for token in wire_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR001_BOUNDS_VERSION",
                "v1 must use an exact eight-event, no-trailing-data, bounded JSON mapping into "
                "the REC-002 domain vocabulary",
            )
        )

    sequencing_tokens = (
        "validator.accept(event)",
        "case ACCEPTED -> new Accepted(event)",
        "case REJECTED_SESSION -> new Rejected(Rejection.FOREIGN_SESSION)",
        "case REJECTED_SEQUENCE -> new Rejected(Rejection.NON_MONOTONIC_SEQUENCE)",
        "case REJECTED_REVISION -> new Rejected(Rejection.INVALID_REVISION)",
        "case DROPPED_AFTER_TERMINAL -> new Rejected(Rejection.AFTER_TERMINAL)",
    )
    if (
        any(token not in no_comments for token in sequencing_tokens)
        or re.search(
            r"private\s+(?:final\s+)?(?:SessionId|RecognitionEvent|String)\s+\w+\s*;",
            code,
        )
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR001_SEQUENCE_TERMINAL",
                "one content-free REC-002 validator must own foreign-session, sequence, revision, "
                "and terminal decisions without caching wire JSON or events",
            )
        )

    if (
        "invalid streaming recognition event" not in no_comments
        or "state=<redacted>" not in no_comments
        or "event=<redacted>" not in no_comments
        or re.search(
            r"toString\s*\([^)]*\)[^{]*\{[^}]*(?:json|text|session|event)\s*\+",
            no_comments,
            re.I,
        )
        or re.search(r"throw\s+new\s+IllegalArgumentException\s*\([^)]*\+", no_comments)
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR001_REDACTION",
                "wire parse failures and result diagnostics must not expose session, transcript, "
                "metadata, failure detail, or raw JSON",
            )
        )
    return tuple(findings)


def _inspect_str002_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    if relative_path not in STR002_REQUIRED_SOURCE_PATHS:
        if "StreamingRecognitionWebSocketClient" in no_comments:
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR002_CLIENT_CALLER",
                    "the STR-002 transport client is restricted to the exact streaming Provider",
                )
            )
        if relative_path != QWEN3_ASR_VLLM_PROVIDER_PATH and re.search(
            r"\bWebSocketStreamingProvider\s*\.\s*(?:StartRequest|StreamingSession|"
            r"Backend|Connection|AttemptListener|ClientFailure|Timer|Ticket|CredentialAccess|"
            r"CredentialOperation|BackendException)\b",
            no_comments,
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR002_ADAPTER_SCOPE",
                    "streaming Provider internals are package-confined to the reviewed adapter",
                )
            )
        return tuple(findings)

    if set(imports) != set(STR002_ALLOWED_IMPORTS[relative_path]):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR002_ADAPTER_DEPENDENCY",
                "streaming Provider/client dependencies must remain the exact reviewed bounded network surface",
            )
        )

    forbidden = re.compile(
        r"\b(?:Activity|Service|InputConnection|EditorOperation|AudioRecord|AudioCapture|"
        r"SharedPreferences|SQLiteDatabase|RoomDatabase|Serializable|Externalizable|Parcelable|"
        r"Parcel|Bundle|Intent|File|Path|DataStore|Log)\b"
        r"|(?<![\w$.])(?:android|androidx|java\.nio\.file|kotlinx\.serialization|"
        r"com\.google\.gson|com\.squareup\.moshi|com\.fasterxml\.jackson)\.",
    )
    if forbidden.search(code):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR002_ADAPTER_DEPENDENCY",
                "streaming transport must not own Android/editor/capture/persistence/serialization authority",
            )
        )

    if relative_path == STREAMING_RECOGNITION_WEBSOCKET_CLIENT_PATH:
        shape_tokens = (
            "public final class StreamingRecognitionWebSocketClient implements AutoCloseable",
            "public interface Listener",
            "public interface Session extends AutoCloseable",
            "private final class SessionImpl extends WebSocketListener implements Session",
            "private final StreamingRecognitionWireEvent.Stream stream",
            "stream = new StreamingRecognitionWireEvent.Stream(config.sessionId())",
            "result = stream.accept(text)",
            "result instanceof StreamingRecognitionWireEvent.Accepted accepted",
            "RecognitionEvent event = accepted.event()",
        )
        client_tokens = (
            "public static final int MAX_PCM_FRAME_BYTES = 64 * 1_024",
            "public static final long MAX_OUTGOING_QUEUE_BYTES = 256L * 1_024L",
            "private static final int MAX_CREDENTIAL_CODE_POINTS = 4_096",
            ".connectTimeout(10L, TimeUnit.SECONDS)",
            ".callTimeout(0L, TimeUnit.MILLISECONDS)",
            ".readTimeout(0L, TimeUnit.MILLISECONDS)",
            ".pingInterval(15L, TimeUnit.SECONDS)",
            ".followRedirects(false)",
            ".followSslRedirects(false)",
            ".retryOnConnectionFailure(false)",
            "webSocket.queueSize() > MAX_OUTGOING_QUEUE_BYTES - length",
            "ByteString frame = ByteString.of(pcm, offset, length)",
            "socket.cancel()",
            "releaseContentLocked()",
        )
        credential_tokens = (
            "public Session open(Config config, char[] credential, Listener listener)",
            "String token = credential(credential)",
            "Arrays.copyOf(Objects.requireNonNull(value, \"credential\"), value.length)",
            "Arrays.fill(copy, '\\0')",
            "MAX_CREDENTIAL_CODE_POINTS * 2",
        )
        if (
            any(token not in no_comments for token in shape_tokens)
            or "public class StreamingRecognitionWebSocketClient" in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR002_CLIENT_SHAPE",
                    "client must remain one final narrow WebSocket-to-STR-001 bridge",
                )
            )
        if (
            any(token not in no_comments for token in client_tokens)
            or "followRedirects(true)" in no_comments
            or "followSslRedirects(true)" in no_comments
            or "retryOnConnectionFailure(true)" in no_comments
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR002_CLIENT_CONTRACT",
                    "client must keep fixed timeouts, no redirects/retry, bounded frames/queue, cancellation and STR-001 decoding",
                )
            )
        if any(token not in no_comments for token in credential_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR002_CREDENTIAL_BOUNDARY",
                    "raw credentials may cross only one bounded synchronous char-array client call",
                )
            )
    else:
        shape_tokens = (
            "final class WebSocketStreamingProvider",
            "implements RecognitionProvider<WebSocketStreamingProvider.StartRequest>",
            "private final Object lifecycleLock",
            "private final ProviderConfig.Asr config",
            "private final ProviderDescriptor descriptor",
            "private final Backend backend",
            "private final Timer timer",
            "private SessionState active",
            "private boolean closed",
            "static final class StartRequest implements AutoCloseable",
            "private final class SessionState implements StreamingSession",
            "static final class ClientBackend implements Backend",
            "private static final class ScheduledTimer implements Timer",
        )
        bounds_tokens = (
            "static final int MAX_PCM_FRAME_BYTES = StreamingRecognitionWebSocketClient.MAX_PCM_FRAME_BYTES",
            "static final int MAX_TOTAL_PCM_BYTES = 17_280_000",
            "static final int MAX_RECONNECTS = 1",
            "static final long READY_TIMEOUT_MS = 10_000L",
            "static final long FINISH_TIMEOUT_MS = 15_000L",
            "StreamingRecognitionWebSocketClient.MAX_OUTGOING_QUEUE_BYTES - length",
            "session.acceptedPcmBytes > MAX_TOTAL_PCM_BYTES - length",
            "Arrays.copyOf(pcm, length)",
            "Arrays.fill(copied, (byte) 0)",
        )
        retry_tokens = (
            "!session.serverEventSeen",
            "session.acceptedPcmBytes == 0",
            "!session.stopping",
            "session.reconnects < MAX_RECONNECTS",
            "session.reconnects++",
            "retryable(failure)",
            "isCurrentAttemptLocked(session, attempt)",
        )
        lifecycle_tokens = (
            "if (active != null)",
            "RecognitionRoute.FailureClass.RECOGNIZER_BUSY",
            "markTerminalLocked(session)",
            "if (active == session) active = null",
            "cancelTicket(session.readyTimeout)",
            "cancelTicket(session.finishTimeout)",
            "session.releaseReferences()",
            "executor.setRemoveOnCancelPolicy(true)",
            "executor.shutdownNow()",
        )
        credential_tokens = (
            "interface CredentialAccess",
            "Connection use(SecretRef reference, CredentialOperation operation) throws Exception",
            "interface CredentialOperation",
            "Connection apply(char[] credential) throws Exception",
            "return credentialAccess.use(",
            "client.open(",
        )
        if (
            any(token not in no_comments for token in shape_tokens)
            or "public final class WebSocketStreamingProvider" in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR002_PROVIDER_SHAPE",
                    "Provider must remain package-confined, final, single-session and own one reviewed backend/timer",
                )
            )
        if any(token not in no_comments for token in bounds_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR002_FRAME_BOUND",
                    "PCM frames, total audio, outgoing queue and timeout tasks must remain explicitly bounded",
                )
            )
        if any(token not in no_comments for token in retry_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR002_RECONNECT_BOUND",
                    "at most one retry is allowed and only before any server event, accepted audio or stop",
                )
            )
        if any(token not in no_comments for token in lifecycle_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR002_EVENT_TERMINAL",
                    "one active session, exact attempt identity, terminal cleanup and cancelled timers must stay authoritative",
                )
            )
        if any(token not in no_comments for token in credential_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR002_CREDENTIAL_BOUNDARY",
                    "SecretRef resolution must remain one synchronous char-array lease at the exact client call",
                )
            )

    if re.search(
        r"(?:Log\.|System\.out|System\.err|printStackTrace|\.getMessage\s*\(|"
        r"Throwable\.toString|error\s*\+|\+\s*error)",
        no_comments,
    ) or re.search(r"toString\s*\([^)]*\)[^{]*\{[^}]*(?:token|credential|text|sessionId)\s*\+", no_comments):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR002_FAILURE_REDACTION",
                "transport failures and diagnostics must expose only stable content-free state",
            )
        )
    return tuple(findings)


def _inspect_str003_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    if relative_path not in STR003_REQUIRED_SOURCE_PATHS:
        if "Qwen3AsrVllmClient" in no_comments:
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR003_CLIENT_CALLER",
                    "the Qwen3-ASR vLLM client is restricted to the exact reviewed adapter",
                )
            )
        if re.search(
            r"\bQwen3AsrVllmProvider\s*\.\s*(?:ProbeListener|ProbeWorker|CredentialAccess|"
            r"CredentialOperation|ProbeRequest|SingleProbeWorker|ClientBackend|RejectedSession)\b",
            no_comments,
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR003_ADAPTER_SCOPE",
                    "Qwen3-ASR adapter internals are confined to the reviewed provider",
                )
            )
        return tuple(findings)

    if set(imports) != set(STR003_ALLOWED_IMPORTS[relative_path]):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR003_ADAPTER_DEPENDENCY",
                "Qwen3-ASR/vLLM dependencies must remain the exact reviewed bounded surface",
            )
        )

    forbidden = re.compile(
        r"\b(?:Activity|Service|InputConnection|EditorOperation|AudioRecord|AudioCapture|"
        r"SharedPreferences|SQLiteDatabase|RoomDatabase|Serializable|Externalizable|Parcelable|"
        r"Parcel|Bundle|Intent|File|Path|DataStore|Log)\b"
        r"|(?<![\w$.])(?:android|androidx|java\.nio\.file|kotlinx\.serialization|"
        r"com\.google\.gson|com\.squareup\.moshi|com\.fasterxml\.jackson)\.",
    )
    if forbidden.search(code):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR003_ADAPTER_DEPENDENCY",
                "Qwen3-ASR transport must not own Android/editor/capture/persistence/serialization authority",
            )
        )

    if relative_path == QWEN3_ASR_VLLM_CLIENT_PATH:
        shape_tokens = (
            "public final class Qwen3AsrVllmClient implements AutoCloseable",
            "public record Config(",
            "public interface Listener",
            "public interface Session extends AutoCloseable",
            "private final class SessionImpl extends WebSocketListener implements Session",
            "private sealed interface ServerEvent",
            "permits SessionCreated, TranscriptionDelta, TranscriptionDone, ServerFailure",
        )
        transport_tokens = (
            "public static final int MAX_PCM_FRAME_BYTES = 64 * 1_024",
            "public static final long MAX_OUTGOING_QUEUE_BYTES = 256L * 1_024L",
            "public static final int MAX_JSON_UTF16_UNITS = 524_288",
            "public static final int MAX_PROBE_BYTES = 256 * 1_024",
            "public static final int MAX_MODELS = 128",
            "private static final int MAX_JSON_DEPTH = 16",
            ".followRedirects(false)",
            ".followSslRedirects(false)",
            ".retryOnConnectionFailure(false)",
            "serviceEndpoint(safeConfig.endpoint(), ServicePath.MODELS)",
            "serviceEndpoint(safeConfig.endpoint(), ServicePath.REALTIME)",
            "webSocket.queueSize() > MAX_OUTGOING_QUEUE_BYTES - frame.length()",
            "MAX_PROBE_BYTES + 1L",
            "data.length() > MAX_MODELS",
            "requireJsonDepth(json)",
            "RecognitionEvent.MAX_TEXT_CODE_POINTS",
        )
        protocol_tokens = (
            'case "session.created"',
            'case "transcription.delta"',
            'case "transcription.done"',
            'case "error"',
            '.put("type", "session.update")',
            '.put("type", "input_audio_buffer.append")',
            '.put("type", "input_audio_buffer.commit")',
            '.put("final", true)',
            "new RecognitionEvent.Preparing(",
            "new RecognitionEvent.Ready(",
            "new RecognitionEvent.Partial(",
            "new RecognitionEvent.Endpoint(",
            "new RecognitionEvent.Final(",
        )
        credential_tokens = (
            "public ProbeResult probe(Config config, char[] credential)",
            "public Session open(Config config, char[] credential, Listener listener)",
            "private static final int MAX_CREDENTIAL_CODE_POINTS = 4_096",
            "MAX_CREDENTIAL_CODE_POINTS * 2",
        )
        if any(token not in no_comments for token in shape_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR003_CLIENT_SHAPE",
                    "client must remain one final bounded probe/realtime vLLM bridge",
                )
            )
        if (
            any(token not in no_comments for token in transport_tokens)
            or "followRedirects(true)" in no_comments
            or "followSslRedirects(true)" in no_comments
            or "retryOnConnectionFailure(true)" in no_comments
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR003_TRANSPORT_BOUND",
                    "probe, JSON, transcript, PCM, queue, redirect and retry bounds must remain fixed",
                )
            )
        if any(token not in no_comments for token in protocol_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR003_PROTOCOL_CONTRACT",
                    "client must retain the exact vLLM session/update/audio/delta/done protocol mapping",
                )
            )
        if any(token not in no_comments for token in credential_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR003_CREDENTIAL_BOUNDARY",
                    "credential material may cross only bounded synchronous char-array client calls",
                )
            )
    else:
        shape_tokens = (
            "final class Qwen3AsrVllmProvider",
            "implements RecognitionProvider<WebSocketStreamingProvider.StartRequest>",
            "private final Qwen3AsrVllmClient client",
            "private final CredentialAccess credentialAccess",
            "private final ProbeWorker probeWorker",
            "private final WebSocketStreamingProvider delegate",
            "private ProbeRequest activeProbe",
            "private long probeGeneration",
            "private boolean closed",
            "private static final class SingleProbeWorker implements ProbeWorker",
            "private static final class ClientBackend implements WebSocketStreamingProvider.Backend",
            "private final class RejectedSession implements WebSocketStreamingProvider.StreamingSession",
        )
        probe_tokens = (
            "ProviderRegistry.ProbeObservation probe()",
            "boolean refreshCapabilities(ProbeListener listener)",
            "if (closed || activeProbe != null) return false",
            "probeGeneration == Long.MAX_VALUE",
            "probeWorker.execute(() -> executeProbe(request))",
            "client.probe(clientConfig, credential)",
            "activeProbe != request || request.generation != probeGeneration",
            "new ArrayBlockingQueue<>(1)",
            "executor.shutdownNow()",
        )
        authority_tokens = (
            "ProviderCapabilities.qwen3AsrVllm(privacyClass(this.config))",
            "WebSocketStreamingProvider.create(",
            "if (current instanceof ProviderRegistry.ObservedUnavailable unavailable)",
            "delegate.prepare(request)",
            "delegate.start(safeRequest, safeSink)",
            "RecognitionRoute.PrivacyClass.LOCAL_NETWORK",
            "RecognitionRoute.PrivacyClass.PUBLIC_NETWORK",
            "return credentialAccess.use(",
            "client.open(",
        )
        if any(token not in no_comments for token in shape_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR003_PROVIDER_SHAPE",
                    "provider must remain package-final, cache-only on probe(), single-probe and delegate-bound",
                )
            )
        if (
            any(token not in no_comments for token in probe_tokens)
            or no_comments.count("client.probe(") != 1
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR003_PROBE_BOUND",
                    "capability probing must be one cached generation-bound request on one bounded worker",
                )
            )
        if (
            any(token not in no_comments for token in authority_tokens)
            or no_comments.count("client.open(") != 1
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR003_PROVIDER_AUTHORITY",
                    "only the exact probed Qwen adapter may bind credentials, capabilities and the shared streaming delegate",
                )
            )

    if re.search(
        r"(?:Log\.|System\.out|System\.err|printStackTrace|\.getMessage\s*\(|"
        r"Throwable\.toString|error\s*\+|\+\s*error)",
        no_comments,
    ) or re.search(
        r"toString\s*\([^)]*\)[^{]*\{[^}]*(?:token|credential|text|sessionId|endpoint|model)\s*\+",
        no_comments,
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR003_FAILURE_REDACTION",
                "probe/session failures and diagnostics must expose only stable content-free state",
            )
        )
    return tuple(findings)


def _inspect_str005_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    if relative_path not in STR005_REQUIRED_SOURCE_PATHS:
        if (
            "LocalStreamingProvider" in no_comments
            and relative_path != TWO_STAGE_STREAMING_PROVIDER_PATH
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_PRODUCTION_WIRING",
                    "the local streaming Provider remains unregistered until the reviewed production routing task",
                )
            )
        if (
            "LocalRealtimeRecognitionClient" in no_comments
            and relative_path not in STR005_REALTIME_CLIENT_ALLOWED_CONSUMERS
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_CLIENT_CALLER",
                    "the private-process realtime client is restricted to legacy owners and the exact reviewed Provider backend",
                )
            )
        return tuple(findings)

    if relative_path == LOCAL_STREAMING_PROVIDER_PATH:
        forbidden = re.compile(
            r"\b(?:Activity|Service|InputConnection|EditorOperation|AudioRecord|AudioCapture|"
            r"MediaRecorder|SecretRef|ProviderConfig|SharedPreferences|SQLiteDatabase|"
            r"RoomDatabase|Serializable|Externalizable|Parcelable|Parcel|Bundle|Intent|"
            r"URL|URI|HttpURLConnection|Socket|OkHttpClient|WebSocket|DataStore|Log)\b"
            r"|(?<![\w$.])(?:androidx|java\.net|java\.nio\.file|okhttp3|retrofit2|"
            r"kotlinx\.serialization|com\.google\.gson|com\.squareup\.moshi)\.",
        )
        if set(imports) != set(STR005_PROVIDER_ALLOWED_IMPORTS) or forbidden.search(code):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_ADAPTER_DEPENDENCY",
                    "the on-device Provider may own only the reviewed private-process, model probe, bounded worker/timer, and recognition-domain surface",
                )
            )

        shape_tokens = (
            "final class LocalStreamingProvider",
            "implements RecognitionProvider<LocalStreamingProvider.StartRequest>",
            "private final Object lifecycleLock",
            "private final ProviderDescriptor descriptor",
            "private final Backend backend",
            "private final Worker worker",
            "private final Timer timer",
            "private SessionState active",
            "private boolean closed",
            "static final class StartRequest implements AutoCloseable",
            "static final class ClientBackend implements Backend",
            "private static final class ClientConnection implements Connection",
            "private static final class SingleWorker implements Worker",
            "private static final class ScheduledTimer implements Timer",
            "private final class SessionState implements StreamingSession",
        )
        if (
            any(token not in no_comments for token in shape_tokens)
            or "public final class LocalStreamingProvider" in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_PROVIDER_SHAPE",
                    "the Provider must remain package-confined, final, single-session, and own one exact backend, worker, and timer",
                )
            )

        capability_tokens = (
            'private static final String PROVIDER_ID = "builtin.local-streaming-paraformer"',
            "OfflineStreamingModelSpec.REALTIME.displayName()",
            "ProviderCapabilities.localStreamingParaformer()",
        )
        bounds_tokens = (
            "static final int MAX_PCM_FRAME_BYTES = 64 * 1024",
            "static final int MAX_QUEUED_PCM_BYTES = 256 * 1024",
            "static final int MAX_TOTAL_PCM_BYTES = 17_280_000",
            "static final long READY_TIMEOUT_MS = 30_000L",
            "static final long FINISH_TIMEOUT_MS = 35_000L",
            "session.acceptedPcmBytes > MAX_TOTAL_PCM_BYTES - length",
            "session.queuedPcmBytes > MAX_QUEUED_PCM_BYTES - length",
            "Arrays.copyOf(pcm, length)",
            "Arrays.fill(copied, (byte) 0)",
            "RecognitionEvent.MAX_TEXT_CODE_POINTS",
        )
        if any(token not in no_comments for token in capability_tokens + bounds_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_BOUNDED_CAPABILITY",
                    "the exact selected on-device capability, PCM queue/session/text bounds, defensive copy, and zeroing must remain fixed",
                )
            )

        lifecycle_tokens = (
            "worker.execute(() -> openOnWorker(session))",
            "worker.execute(() -> deliverPcmOnWorker(session, copied))",
            "worker.execute(() -> finishOnWorker(session))",
            "Executors.newSingleThreadExecutor",
            "if (active != null)",
            "RecognitionRoute.FailureClass.RECOGNIZER_BUSY",
            "markTerminalLocked(session)",
            "if (active == session) active = null",
            "cancelTicket(session.readyTimeout)",
            "cancelTicket(session.finishTimeout)",
            "session.releaseReferences()",
            "executor.setRemoveOnCancelPolicy(true)",
            "executor.shutdownNow()",
        )
        if any(token not in no_comments for token in lifecycle_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_LIFECYCLE",
                    "open, PCM, and finish must stay on one worker with one active lease, bounded timers, terminal revocation, and deterministic cleanup",
                )
            )

        event_counts = {
            "new RecognitionEvent.Preparing(": 1,
            "new RecognitionEvent.Ready(": 1,
            "new RecognitionEvent.Partial(": 1,
            "new RecognitionEvent.Final(": 1,
            "new RecognitionEvent.Failure(": 2,
            "new RecognitionEvent.Cancelled(": 1,
        }
        if (
            any(no_comments.count(token) != count for token, count in event_counts.items())
            or "new RecognitionEvent.SpeechStarted(" in no_comments
            or "new RecognitionEvent.Endpoint(" in no_comments
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_EVENT_CONTRACT",
                    "the local first pass must emit only the reviewed Preparing/Ready/revisable Partial/Final/stable terminal vocabulary",
                )
            )

        backend_tokens = (
            "new ClientBackend(application, new LocalRealtimeRecognitionClient(application))",
            "LocalOfflineRecognizer.deviceSupport(context)",
            "OfflineStreamingModelStore.status(context)",
            "LocalRealtimeRecognitionClient.Session session = client.start(",
            "safeListener.onReady()",
            "safeListener.onPartial(text)",
            "client.close()",
        )
        if (
            any(token not in no_comments for token in backend_tokens)
            or no_comments.count("client.start(") != 1
            or no_comments.count("RecognitionFailureMapper.fromLocalAvailability(") != 1
            or no_comments.count("RecognitionFailureMapper.fromLocalRuntime(") != 1
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_BACKEND_BINDING",
                    "the exact Provider backend must bind one private-process client, selected model/device probe, ready/partial callbacks, and unified failure mapper",
                )
            )

        if re.search(
            r"(?:Log\.|System\.out|System\.err|printStackTrace|\.getMessage\s*\(|"
            r"Throwable\.toString|error\s*\+|\+\s*error)",
            no_comments,
        ) or re.search(
            r"toString\s*\([^)]*\)[^{]*\{[^}]*(?:text|sessionId|providerId|model|path)\s*\+",
            no_comments,
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_FAILURE_REDACTION",
                    "local streaming failures and diagnostics must discard model, path, session, transcript, audio, and throwable detail",
                )
            )

    elif relative_path == LOCAL_REALTIME_RECOGNITION_CLIENT_PATH:
        callback_tokens = (
            "default void onReady() {}",
            "void onPartial(String text)",
            "ready.countDown()",
            "listener.onReady()",
            "listener.onPartial(clean)",
            "private static final int MAX_QUEUED_FRAMES = 100",
            "new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES)",
        )
        ready_countdown = no_comments.find("ready.countDown()")
        ready_callback = no_comments.find("listener.onReady()")
        if (
            any(token not in no_comments for token in callback_tokens)
            or no_comments.count("listener.onReady()") != 1
            or ready_countdown < 0
            or ready_callback < ready_countdown
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_CLIENT_CONTRACT",
                    "the private-process client must expose one actual ready callback, bounded frame queue, and existing bounded partial callback",
                )
            )

    elif relative_path == OFFLINE_STREAMING_MODEL_SPEC_PATH:
        model_tokens = (
            '"streaming-paraformer-bilingual-zh-en-int8-2023-08-14"',
            '"8e40c43232a1c5c66c82111efc5820d3accca11b"',
            "165_462_184L",
            '"81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a"',
            "71_664_561L",
            '"f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f"',
            "75_756L",
            '"59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6"',
        )
        if any(token not in no_comments for token in model_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_MODEL_PIN",
                    "the selected model revision, three artifact sizes, and SHA-256 values must remain exact",
                )
            )

    elif relative_path == OFFLINE_STREAMING_MODEL_STORE_PATH:
        store_tokens = (
            'private static final String MARKER = "installed-v1.txt"',
            "requireHash(encoder, spec.encoder())",
            "requireHash(decoder, spec.decoder())",
            "requireHash(tokens, spec.tokens())",
            '"revision=" + spec.revision()',
            '"encoder_sha256=" + spec.encoder().sha256()',
            '"decoder_sha256=" + spec.decoder().sha256()',
            '"tokens_sha256=" + spec.tokens().sha256()',
        )
        if any(token not in no_comments for token in store_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_MODEL_STORE",
                    "private model installation must retain exact marker identity, revision, and all three verified artifact hashes",
                )
            )

    elif relative_path == OFFLINE_MODEL_DOWNLOADER_PATH:
        downloader_tokens = (
            "OfflineStreamingModelSpec streaming = OfflineStreamingModelSpec.REALTIME",
            "OfflineStreamingModelStore.status(context)",
            "streamingStaging = OfflineStreamingModelStore.newStagingDirectory(context)",
            "streaming.encoder()",
            "streaming.decoder()",
            "streaming.tokens()",
            "OfflineStreamingModelStore.commitVerifiedStaging(context, streamingStaging)",
            "OfflineStreamingModelStore.discardStaging(context, streamingStaging)",
        )
        if any(token not in no_comments for token in downloader_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR005_MODEL_DOWNLOAD",
                    "the existing private downloader must atomically stage, verify, commit, and clean all selected streaming artifacts",
                )
            )

    return tuple(findings)


def _inspect_str006_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    if relative_path != TWO_STAGE_STREAMING_PROVIDER_PATH:
        if "TwoStageStreamingProvider" in no_comments:
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR006_PRODUCTION_WIRING",
                    "the two-stage Provider remains unregistered until STR-010 binds the reviewed production route",
                )
            )
        return tuple(findings)

    forbidden = re.compile(
        r"\b(?:Activity|Service|InputConnection|EditorOperation|AudioRecord|AudioCapture|"
        r"MediaRecorder|SecretRef|ProviderConfig|SharedPreferences|SQLiteDatabase|"
        r"RoomDatabase|Serializable|Externalizable|Parcelable|Parcel|Bundle|Intent|"
        r"URL|URI|HttpURLConnection|Socket|OkHttpClient|WebSocket|DataStore|Log)\b"
        r"|(?<![\w$.])(?:androidx|java\.net|java\.nio\.file|okhttp3|retrofit2|"
        r"kotlinx\.serialization|com\.google\.gson|com\.squareup\.moshi)\.",
    )
    if set(imports) != set(STR006_PROVIDER_ALLOWED_IMPORTS) or forbidden.search(code):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR006_ADAPTER_DEPENDENCY",
                "the composite may own only the exact local streaming/final providers, bounded PCM/WAV worker, recognition domain, and fact guard",
            )
        )

    shape_tokens = (
        "final class TwoStageStreamingProvider",
        "implements RecognitionProvider<TwoStageStreamingProvider.StartRequest>",
        "private final Object lifecycleLock",
        "private final RecognitionProvider<LocalStreamingProvider.StartRequest> streaming",
        "private final RecognitionProvider<SenseVoiceFinalProvider.StartRequest> finalizer",
        "private final Worker worker",
        "private SessionState active",
        "private boolean closed",
        "static final class StartRequest implements AutoCloseable",
        "private final class SessionState implements StreamingSession",
        "private static final class PcmBuffer implements AutoCloseable",
        "private static final class AudioClaim implements AutoCloseable",
        "private static final class SingleWorker implements Worker",
    )
    binding_tokens = (
        "LocalStreamingProvider.create(application)",
        "SenseVoiceFinalProvider.create(application)",
        "new LocalStreamingProvider.StartRequest(session.sessionId)",
        "new SenseVoiceFinalProvider.StartRequest(",
        "WavEncoder.pcm16Mono(pcm, SAMPLE_RATE_HZ)",
        "streaming.start(request, event -> onStreamingEvent(session, event))",
        "finalizer.start(request, event -> onFinalizerEvent(session, event))",
    )
    if (
        any(token not in no_comments for token in shape_tokens + binding_tokens)
        or "public final class TwoStageStreamingProvider" in code
        or no_comments.count("LocalStreamingProvider.create(application)") != 1
        or no_comments.count("SenseVoiceFinalProvider.create(application)") != 1
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR006_PROVIDER_SHAPE",
                "the package-final composite must bind exactly one reviewed streaming child, final child, worker, and single active session",
            )
        )

    capability_tokens = (
        'private static final String PROVIDER_ID = "builtin.local-two-stage"',
        "ProviderCapabilities.localTwoStage()",
        "static final int SAMPLE_RATE_HZ = 16_000",
        "static final int MAX_PCM_FRAME_BYTES = LocalStreamingProvider.MAX_PCM_FRAME_BYTES",
        "static final int MAX_TOTAL_PCM_BYTES = LocalStreamingProvider.MAX_TOTAL_PCM_BYTES",
    )
    bounded_tokens = (
        "count > MAX_TOTAL_PCM_BYTES - length",
        "Arrays.copyOf(bytes, length)",
        "Arrays.fill(bytes, (byte) 0)",
        "Arrays.fill(pcm, (byte) 0)",
        "Arrays.fill(wav, (byte) 0)",
        "length > pcm.length",
        "if (!session.audio.append(pcm, length))",
    )
    if any(token not in no_comments for token in capability_tokens + bounded_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR006_BOUNDED_AUDIO",
                "PCM frame/session bounds, exact WAV rate, defensive ownership transfer, and zeroing must remain fixed",
            )
        )

    lifecycle_tokens = (
        "if (active != null)",
        "RecognitionRoute.FailureClass.RECOGNIZER_BUSY",
        "session.stopping = true",
        "worker.execute(() -> finalizeOnWorker(session, finalClaim))",
        "if (!isCurrentLocked(session) || !session.stopping)",
        "markTerminalLocked(session)",
        "if (active == session) active = null",
        "session.releaseReferences()",
        "executor.shutdownNow()",
        "detachChildrenLocked(session)",
        "cancelQuietly(cancel)",
    )
    if any(token not in no_comments for token in lifecycle_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR006_LIFECYCLE",
                "one active lease, asynchronous finalization, post-encode revocation check, one terminal, and deterministic child/audio cleanup are mandatory",
            )
        )

    final_tokens = (
        "if (event instanceof RecognitionEvent.Final terminal)",
        "TranscriptIntegrityGuard.validate(",
        "ProcessingMode.SMART",
        "PersonalizationSnapshot.empty()",
        "if (!TranscriptIntegrityGuard.validate(",
        "text = preview",
        "new RecognitionEvent.Final(",
    )
    if (
        any(token not in no_comments for token in final_tokens)
        or no_comments.count("new RecognitionEvent.Final(") != 1
        or no_comments.count("TranscriptIntegrityGuard.validate(") != 1
        or no_comments.count("new RecognitionEvent.Partial(") != 1
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR006_FINAL_AUTHORITY",
                "streaming may publish revisable Partial only; one SenseVoice Final must pass the exact fact guard or preserve the latest safe preview",
            )
        )

    if (
        "session.streamingActive = false" not in no_comments
        or "emitReadyIfNeededLocked(session)" not in no_comments
        or "finishFailureLocked(session, failure.failureClass())" not in no_comments
        or "finishCancelledLocked(session)" not in no_comments
        or "event.sequence() <= session.lastStreamingSequence" not in no_comments
        or "event.sequence() <= session.lastFinalizerSequence" not in no_comments
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR006_EVENT_CONTRACT",
                "child events must be session/sequence bound, streaming failure may degrade without exposing a child terminal, and finalizer owns the terminal",
            )
        )

    if re.search(
        r"(?:Log\.|System\.out|System\.err|printStackTrace|\.getMessage\s*\(|"
        r"Throwable\.toString|error\s*\+|\+\s*error)",
        no_comments,
    ) or re.search(
        r"toString\s*\([^)]*\)[^{]*\{[^}]*(?:text|sessionId|providerId|model|path|language)\s*\+",
        no_comments,
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR006_FAILURE_REDACTION",
                "two-stage diagnostics must discard audio, transcript, model/path, language, session, and throwable detail",
            )
        )

    return tuple(findings)


def _inspect_str010_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    if relative_path == RECOGNITION_ROUTER_VOICE_CONTROLLER_PATH:
        forbidden = re.compile(
            r"\b(?:InputConnection|EditorOperation|AudioCapture|AudioRecord|MediaRecorder|"
            r"RecognitionProvider|SharedPreferences|Repository|Store|SQLiteDatabase|"
            r"RoomDatabase|SecretRef|SecretStore|URL|URI|Socket|OkHttpClient|WebSocket|"
            r"Serializable|Externalizable|Parcelable|Parcel|Bundle|Intent|Log)\b"
        )
        if set(imports) != set(STR010_CONTROLLER_ALLOWED_IMPORTS) or forbidden.search(code):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR010_CONTROLLER_DEPENDENCY",
                    "the production router controller may own only content-free routing policy and one exact compatibility executor",
                )
            )

        shape_patterns = (
            r"public\s+final\s+class\s+RecognitionRouterVoiceController\s+implements\s+VoiceController",
            r"public\s+RecognitionRouterVoiceController\s*\(\s*Context\s+context\s*,\s*VoicePipelineAdapter\s+delegate\s*\)",
            r"private\s+final\s+VoiceController\s+delegate\s*;",
            r"private\s+final\s+Environment\s+environment\s*;",
            r"private\s+final\s+ProviderCircuitBreaker\s+circuitBreaker\s*;",
            r"private\s+final\s+Map<RecognitionBackend,\s*ProviderDescriptor>\s+descriptors\s*;",
            r"private\s+Preparation\s+preparing\s*;",
            r"private\s+ActiveRun\s+active\s*;",
            r"private\s+long\s+generation\s*;",
            r"interface\s+Environment\s*\{\s*PreparedRoute\s+prepare\s*\(",
        )
        if any(re.search(pattern, code, re.DOTALL) is None for pattern in shape_patterns):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR010_CONTROLLER_SHAPE",
                    "the Router bridge must be final, bind the exact VoicePipelineAdapter publicly, and keep preparation/attempt state private",
                )
            )

        policy_tokens = (
            "EffectiveProfileResolver.resolve(",
            "new ProviderRegistry()",
            "registry.register(descriptor",
            "new RecognitionRouter(",
            "RecognitionRouter.PrivacyAuthorization.preauthorized(",
            "new RetryPolicy(1, Set.of())",
            "List.of(new RouteStep(",
            "prepared.backend != requestedBackend",
            "!prepared.routeId.equals(routeId(requestedBackend))",
            "ready.attempt().descriptor() != descriptor",
            "active == run && run.router.isCurrent(run.attempt)",
            "prepared.profile, descriptor.capabilities().privacyClass()",
        )
        route_tokens = (
            '"legacy.openai-compatible"',
            '"legacy.local-offline"',
            '"legacy.dashscope-streaming"',
            '"legacy.system-on-device"',
            '"legacy.system-default"',
            '"builtin.local-two-stage"',
            "ProviderCapabilities.localTwoStage()",
        )
        exact_calls = {
            "delegate.start(": 1,
            "delegate.stop(": 2,
            "delegate.cancel(": 1,
            "new RecognitionRouter(": 1,
            "new ProviderRegistry()": 1,
            "EffectiveProfileResolver.resolve(": 1,
            "run.router.onSuccess(": 1,
        }
        if (
            any(token not in no_comments for token in policy_tokens + route_tokens)
            or any(no_comments.count(token) != count for token, count in exact_calls.items())
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR010_ROUTE_BINDING",
                    "each legacy backend must cross one exact EffectiveProfile/registry/router attempt before the single compatibility executor can start",
                )
            )

        lifecycle_tokens = (
            "if (preparing != null || active != null || delegate.state() != State.IDLE)",
            "reservation = new Preparation(++generation)",
            "PreparationResult prepared = prepareRun(",
            "stale = preparing != reservation",
            "prepared.discard()",
            "preparing.stopRequested = true",
            "run.router.onFailure(run.attempt, FailureClass.CANCELLED)",
            "RecognitionFailureMapper.fromLegacyPipelineMessage(message)",
            "stableFailure(failure)",
        )
        if any(token not in no_comments for token in lifecycle_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR010_LIFECYCLE",
                    "route preparation must be reservation-bound, cancelable, single-session, generation-safe, and terminal-message redacted",
                )
            )

        if re.search(
            r"(?:Log\.|System\.out|System\.err|printStackTrace|\.getMessage\s*\(|"
            r"Throwable\.toString|onError\s*\(\s*(?:message|rawMessage)\s*\))",
            no_comments,
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR010_REDACTION",
                    "legacy/provider detail must be classified and replaced by a stable content-free error",
                )
            )
        return tuple(findings)

    if relative_path == RECOGNITION_ROUTER_VOICE_CONFIG_PATH:
        if set(imports) != set(STR010_CONFIG_ALLOWED_IMPORTS):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR010_FLAG_DEPENDENCY",
                    "the whole-controller rollback flag may depend only on private preferences and exact controller types",
                )
            )
        flag_tokens = (
            'private static final String STORE = "recognition_router_voice_runtime"',
            'private static final String ENABLED = "recognition_router_v1"',
            "getBoolean(ENABLED, true)",
            "public static VoiceController select(",
            "VoicePipelineAdapter compatibilityDelegate",
            "? new RecognitionRouterVoiceController(application, delegate)",
            ": delegate",
            "putBoolean(ENABLED, enabled).commit()",
        )
        if (
            any(token not in no_comments for token in flag_tokens)
            or no_comments.count("new RecognitionRouterVoiceController(") != 1
            or re.search(r"\b(?:start|stop|cancel)\s*\(", no_comments)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR010_FEATURE_FLAG",
                    "the rollback flag must choose one whole controller exactly once and must never execute either path",
                )
            )
        return tuple(findings)

    if relative_path in STR010_PRODUCTION_CONSUMERS:
        if (
            no_comments.count("RecognitionRouterVoiceConfig.select(") != 1
            or no_comments.count("new VoicePipelineAdapter(pipeline)") != 1
            or "new RecognitionRouterVoiceController(" in no_comments
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "STR010_PRODUCTION_CALLER",
                    "each production voice surface must freeze exactly one flag-selected Router or compatibility controller",
                )
            )
        return tuple(findings)

    if "RecognitionRouterVoiceController" in no_comments:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR010_CONTROLLER_SCOPE",
                "only the exact rollback selector may construct the production Router controller",
            )
        )
    if "RecognitionRouterVoiceConfig" in no_comments:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "STR010_FLAG_SCOPE",
                "only the three reviewed production voice surfaces may consume the Router feature flag",
            )
        )
    return tuple(findings)


def _inspect_rec003_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in REC003_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    forbidden_domain = re.compile(
        r"\b(?:Serializable|Externalizable|Parcelable|Parcel|Bundle|Intent|Context|"
        r"InputConnection|EditorOperation|Executor|Thread|Future|File|Path|Socket|URL|"
        r"SharedPreferences|DataStore|RoomDatabase|SecretRef|ProviderConfig|HttpClient)\b"
        r"|(?<![\w$.])(?:android|androidx|java\.io|java\.net|java\.nio\.file|"
        r"java\.util\.concurrent|kotlinx\.serialization|com\.google\.gson|"
        r"com\.squareup\.moshi|com\.fasterxml\.jackson)\.",
    )
    if (
        set(imports) != set(REC003_ALLOWED_IMPORTS)
        or forbidden_domain.search(code)
        or CFG001_SECRET_IDENTIFIER_PATTERN.search(code)
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC003_DOMAIN_DEPENDENCY",
                "provider registry must remain process-local bounded coordination data with no "
                "Android, execution, persistence, serialization, endpoint, or secret authority",
            )
        )

    exact_api = (
        r"(?m)^final\s+class\s+ProviderRegistry\s*\{",
        r"synchronized\s+RegistrationResult\s+register\s*\(\s*"
        r"ProviderDescriptor\s+descriptor\s*,\s*ProviderProbe\s+probe\s*,\s*"
        r"boolean\s+enabled\s*\)",
        r"synchronized\s+EnableResult\s+setEnabled\s*\(\s*String\s+providerId\s*,\s*"
        r"boolean\s+enabled\s*\)",
        r"synchronized\s+LookupResult\s+lookup\s*\(\s*String\s+providerId\s*\)",
        r"(?<!synchronized\s)ProbeResult\s+probe\s*\(\s*String\s+providerId\s*\)",
        r"synchronized\s+int\s+size\s*\(\s*\)",
        r"synchronized\s+int\s+enabledCount\s*\(\s*\)",
    )
    shape_tokens = (
        "static final int MAX_PROVIDERS = 32",
        "private static final Pattern LOOKUP_ID_PATTERN",
        "private final Map<String, Entry> entries = new LinkedHashMap<>()",
        "private long generation",
        "private static final class Entry",
        "private record ProbeLease(Entry entry, long generation)",
    )
    if (
        any(re.search(pattern, code) is None for pattern in exact_api)
        or any(token not in code for token in shape_tokens)
        or "public class ProviderRegistry" in code
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC003_REGISTRY_SHAPE",
                "registry must remain package-confined, final, bounded, single-map state with "
                "synchronized mutation and lookup but an unlocked probe callback",
            )
        )

    registration_tokens = (
        "entries.containsKey(safeDescriptor.id())",
        "entries.size() >= MAX_PROVIDERS",
        "long entryGeneration = nextGeneration()",
        "entries.put(",
        "if (generation == Long.MAX_VALUE)",
        "return ++generation",
        "RegistrationResult.DUPLICATE_ID",
        "RegistrationResult.CAPACITY_EXCEEDED",
        "EnableResult.UNKNOWN_PROVIDER",
        "EnableResult.UNCHANGED",
    )
    register_index = code.find("long entryGeneration = nextGeneration()")
    put_index = code.find("entries.put(")
    if (
        any(token not in code for token in registration_tokens)
        or register_index < 0
        or put_index < 0
        or register_index >= put_index
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC003_REGISTRATION_BOUND",
                "registration must reject duplicates/capacity and allocate a non-wrapping "
                "generation before the single insertion",
            )
        )

    probe_start = code.find("ProbeResult probe(String providerId)")
    callback_index = code.find("lease.entry.probe.probe()", probe_start)
    generation_check = code.find(
        "current.generation != lease.generation", callback_index
    )
    probe_tokens = (
        "synchronized (this)",
        "current != lease.entry",
        "!current.enabled",
        "AccessFailure.PROVIDER_CHANGED",
        "AccessFailure.PROVIDER_DISABLED",
        "AccessFailure.PROBE_FAILED",
        "AccessFailure.CAPABILITY_MISMATCH",
        "current.descriptor.capabilities().equals(available.capabilities())",
        "catch (RuntimeException ignored)",
    )
    if (
        probe_start < 0
        or callback_index < 0
        or generation_check < 0
        or not (probe_start < callback_index < generation_check)
        or code.count("synchronized (this)") != 2
        or any(token not in code for token in probe_tokens)
        or re.search(r"synchronized\s+ProbeResult\s+probe", code)
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC003_PROBE_LEASE",
                "probe callback must execute outside the monitor and be accepted only for the "
                "same enabled registration generation and exact declared capabilities",
            )
        )

    result_patterns = (
        r"@FunctionalInterface\s+interface\s+ProviderProbe",
        r"sealed\s+interface\s+ProbeObservation\s+permits\s+ObservedAvailable\s*,\s*ObservedUnavailable",
        r"sealed\s+interface\s+LookupResult\s+permits\s+LookupFound\s*,\s*LookupRejected",
        r"sealed\s+interface\s+ProbeResult\s+permits\s+ProbeAvailable\s*,\s*ProbeUnavailable\s*,\s*ProbeRejected",
        r"record\s+ObservedAvailable\s*\(\s*ProviderCapabilities\s+capabilities\s*\)",
        r"record\s+ObservedUnavailable\s*\(\s*RecognitionRoute\.FailureClass\s+failureClass\s*\)",
        r"record\s+LookupFound\s*\(\s*ProviderDescriptor\s+descriptor\s*\)",
        r"record\s+LookupRejected\s*\(\s*AccessFailure\s+failure\s*\)",
        r"record\s+ProbeAvailable\s*\(\s*ProviderDescriptor\s+descriptor\s*\)",
        r"record\s+ProbeUnavailable\s*\(\s*RecognitionRoute\.FailureClass\s+failureClass\s*\)",
        r"record\s+ProbeRejected\s*\(\s*AccessFailure\s+failure\s*\)",
        r"enum\s+AccessFailure\s*\{\s*UNKNOWN_PROVIDER\s*,\s*PROVIDER_DISABLED\s*,\s*"
        r"PROVIDER_CHANGED\s*,\s*CAPABILITY_MISMATCH\s*,\s*PROBE_FAILED\s*\}",
        r"enum\s+RegistrationResult\s*\{\s*REGISTERED\s*,\s*DUPLICATE_ID\s*,\s*"
        r"CAPACITY_EXCEEDED\s*\}",
        r"enum\s+EnableResult\s*\{\s*UPDATED\s*,\s*UNCHANGED\s*,\s*UNKNOWN_PROVIDER\s*\}",
    )
    session_only = (
        "RecognitionRoute.FailureClass.NO_MATCH",
        "RecognitionRoute.FailureClass.SPEECH_TIMEOUT",
        "RecognitionRoute.FailureClass.CANCELLED",
        "RecognitionRoute.FailureClass.TARGET_CHANGED",
    )
    if (
        any(re.search(pattern, code, re.DOTALL) is None for pattern in result_patterns)
        or any(token not in code for token in session_only)
        or "session-only failure cannot describe provider availability" not in no_comments
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC003_RESULT_SHAPE",
                "registry probe/lookup results and failures must remain closed, immutable, and "
                "exclude session-only failures from provider availability",
            )
        )

    if (
        "ObservedAvailable{capabilities=<redacted>}" not in no_comments
        or "LookupFound{descriptor=<redacted>}" not in no_comments
        or "ProbeAvailable{descriptor=<redacted>}" not in no_comments
        or "identities=<redacted>" not in no_comments
        or re.search(
            r"toString\s*\([^)]*\)[^{]*\{[^}]*(?:descriptor\s*\+|probe\s*\+|providerId\s*\+)",
            no_comments,
        )
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC003_REDACTION",
                "registry diagnostics must expose only counts/classifications and redact provider "
                "identity, capability, callback, and exception detail",
            )
        )
    return tuple(findings)


def _inspect_rec004_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in REC004_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    if relative_path in REC004_ALLOWED_IMPORTS:
        forbidden = re.compile(
            r"\b(?:Serializable|Externalizable|Parcelable|Parcel|Bundle|Intent|"
            r"InputConnection|EditorOperation|SecretRef|ProviderConfig|AppSettings|"
            r"HttpClient|Socket|URL|File|Path|SharedPreferences|RoomDatabase)\b"
            r"|(?<![\w$.])(?:java\.io|java\.net|java\.nio\.file|okhttp3|retrofit2|"
            r"kotlinx\.serialization|com\.google\.gson|com\.squareup\.moshi)\.",
        )
        if set(imports) != set(REC004_ALLOWED_IMPORTS[relative_path]) or forbidden.search(code):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC004_ADAPTER_DEPENDENCY",
                    "system provider contract may own only the reviewed Android speech bridge "
                    "and bounded recognition-domain values",
                )
            )

    if relative_path == RECOGNITION_PROVIDER_PATH:
        patterns = (
            r"(?m)^interface\s+RecognitionProvider<R>\s+extends\s+AutoCloseable\s*\{",
            r"ProviderDescriptor\s+descriptor\s*\(\s*\)",
            r"ProviderRegistry\.ProbeObservation\s+probe\s*\(\s*\)",
            r"PreparationResult\s+prepare\s*\(\s*R\s+request\s*\)",
            r"Session\s+start\s*\(\s*R\s+request\s*,\s*EventSink\s+sink\s*\)",
            r"@FunctionalInterface\s+interface\s+EventSink",
            r"interface\s+Session\s+extends\s+AutoCloseable",
            r"sealed\s+interface\s+PreparationResult\s+permits\s+Prepared\s*,\s*NotPrepared",
            r"record\s+Prepared\s*\(\s*ProviderDescriptor\s+descriptor\s*\)",
            r"record\s+NotPrepared\s*\(\s*RecognitionRoute\.FailureClass\s+failureClass\s*\)",
        )
        if (
            any(re.search(pattern, code) is None for pattern in patterns)
            or re.search(r"(?m)^public\s+interface\s+RecognitionProvider", code)
            or "void stop()" not in code
            or "void cancel()" not in code
            or code.count("void close()") != 2
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC004_PROVIDER_CONTRACT",
                    "provider lifecycle must remain package-confined, generic, closed-result, "
                    "and expose only descriptor/probe/prepare/start plus session stop/cancel/close",
                )
            )
        if (
            "Prepared{descriptor=<redacted>}" not in no_comments
            or re.search(r"toString\s*\([^)]*\)[^{]*\{[^}]*(?:descriptor\s*\+|sessionId\s*\+)", no_comments)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC004_FAILURE_REDACTION",
                    "provider contract diagnostics must not expose provider or session identity",
                )
            )

    elif relative_path == ANDROID_SYSTEM_PROVIDER_PATH:
        shape_patterns = (
            r"(?m)^final\s+class\s+AndroidSystemRecognitionProvider\s+implements\s+"
            r"RecognitionProvider<AndroidSystemRecognitionProvider\.StartRequest>",
            r"private\s+final\s+RecognitionBackend\s+recognitionBackend",
            r"private\s+final\s+ProviderDescriptor\s+descriptor",
            r"private\s+final\s+Backend\s+backend",
            r"private\s+final\s+MainThread\s+mainThread",
            r"private\s+SessionState\s+active",
            r"private\s+boolean\s+closed",
            r"private\s+static\s+final\s+class\s+SystemBackend\s+implements\s+Backend",
            r"private\s+static\s+final\s+class\s+HandlerMainThread\s+implements\s+MainThread",
        )
        if (
            any(re.search(pattern, code) is None for pattern in shape_patterns)
            or "public final class AndroidSystemRecognitionProvider" in code
            or code.count("new SystemSpeechRecognizer(") != 1
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC004_ADAPTER_SHAPE",
                    "Android System adapter must remain one package-confined final provider with "
                    "one backend, one main-thread dispatcher, and one active session",
                )
            )

        request_pattern = re.compile(
            r"record\s+StartRequest\s*\(\s*SessionId\s+sessionId\s*,\s*String\s+language\s*,\s*"
            r"int\s+maxResults\s*,\s*boolean\s+partialResults\s*,\s*List<String>\s+biasingTerms\s*,\s*"
            r"long\s+timeoutMillis\s*\)",
            re.DOTALL,
        )
        request_tokens = (
            "static final int MAX_BIASING_TERMS = 50",
            "static final int MAX_BIASING_TERM_CODE_POINTS = 80",
            "timeoutMillis > ProviderCapabilities.APP_CAPTURE_LIMIT_MS",
            "List.copyOf(result)",
            "language=<redacted>",
            "biasingTerms=<redacted>",
            "SystemRecognitionIntentFactory.biasingStrings(personalization)",
        )
        if (
            request_pattern.search(code) is None
            or any(token not in no_comments for token in request_tokens)
            or any(token in code for token in ("String prompt", "String callingPackage", "AppSettings"))
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC004_LEAST_AUTHORITY_REQUEST",
                    "system start request must carry only bounded language/result/partial/bias/timeout "
                    "data and redact session and recognition content",
                )
            )

        lifecycle_tokens = (
            "if (!mainThread.isMainThread())",
            "backend.start(",
            "backend.stop()",
            "backend.cancel()",
            "backend.destroy()",
            "if (active != null)",
            "session.stopRequested",
            "if (closed)",
        )
        if code.count("mainThread.execute(") != 10 or any(
            token not in code for token in lifecycle_tokens
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC004_MAIN_THREAD_LIFECYCLE",
                    "start/callback/stop/cancel/close/destroy must marshal through the exact main "
                    "thread owner and preserve one active session",
                )
            )

        event_tokens = (
            "new RecognitionEvent.Preparing(",
            "new RecognitionEvent.Ready(",
            "new RecognitionEvent.SpeechStarted(",
            "new RecognitionEvent.Partial(",
            "new RecognitionEvent.Endpoint(",
            "new RecognitionEvent.Final(",
            "new RecognitionEvent.Failure(",
            "new RecognitionEvent.Cancelled(",
            "session.lastPartialSequence",
            "markTerminal(session)",
            "if (active == session) active = null",
            "request = null",
            "sink = null",
        )
        if (
            any(token not in code for token in event_tokens)
            or code.count("new RecognitionEvent.Final(") != 1
            or code.count("new RecognitionEvent.Cancelled(") != 1
            or code.count("session.releaseReferences()") != 2
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC004_EVENT_TERMINAL",
                    "adapter must emit bounded monotonic events, detach before one terminal, drop "
                    "late callbacks, and release request/sink references",
                )
            )

        if (
            code.count(
                "return RecognitionFailureMapper.fromAndroidSystem(errorCode, message);"
            )
            != 1
            or re.search(r"(?:Log\.|System\.out|System\.err|printStackTrace|message\s*\+|\+\s*message)", no_comments)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC004_FAILURE_REDACTION",
                    "raw Android/OEM error text must map to the stable FailureClass vocabulary and "
                    "never enter events or diagnostics",
                )
            )

    elif relative_path == SYSTEM_SPEECH_RECOGNIZER_PATH:
        bridge_patterns = (
            r"default\s+void\s+onEndOfSpeech\s*\(\s*\)\s*\{\s*\}",
            r"void\s+start\s*\(\s*RecognitionBackend\s+recognitionBackend\s*,\s*String\s+language\s*,\s*"
            r"int\s+maxResults\s*,\s*boolean\s+partialResults\s*,\s*List<String>\s+biasingTerms\s*,\s*"
            r"Callback\s+callback\s*,\s*long\s+timeoutMillis\s*\)",
        )
        callback_index = code.find("callback.onEndOfSpeech()")
        awaiting_index = code.find("awaitingTerminalRun = run", callback_index)
        if (
            any(re.search(pattern, code, re.DOTALL) is None for pattern in bridge_patterns)
            or code.count("callback.onEndOfSpeech()") != 1
            or callback_index < 0
            or awaiting_index < callback_index
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC004_SYSTEM_BRIDGE",
                    "legacy SystemSpeechRecognizer must expose one bounded package-only adapter "
                    "start overload and one endpoint callback before terminal waiting",
                )
            )

    elif relative_path == SYSTEM_RECOGNITION_INTENT_FACTORY_PATH:
        intent_tokens = (
            "List<String> biasingTerms",
            "EXTRA_PARTIAL_RESULTS, partialResults",
            "EXTRA_MAX_RESULTS, Math.max(1, Math.min(maxResults, 5))",
            "static ArrayList<String> biasingStrings(PersonalizationSnapshot snapshot)",
            "Math.min(terms.size(), 512)",
            "Math.min(corrections.size(), 512)",
            "term.aliases().length() <= 4_096",
            "value.length() > 320",
            "wellFormedUtf16(clean)",
        )
        if any(token not in no_comments for token in intent_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC004_SYSTEM_BRIDGE",
                    "system intent bridge must accept only bounded adapter fields and sanitize "
                    "legacy personalization before Android extras",
                )
            )
    return tuple(findings)


def _inspect_rec005_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in REC005_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    if set(imports) != set(REC005_ALLOWED_IMPORTS[relative_path]):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC005_ADAPTER_DEPENDENCY",
                "upload provider/client dependencies must remain the exact reviewed network, "
                "config, bounded-worker, and value surfaces",
            )
        )

    if relative_path == OPENAI_UPLOAD_PROVIDER_PATH:
        forbidden = re.compile(
            r"\b(?:Context|Activity|Service|InputConnection|EditorOperation|AppSettings|"
            r"SecretStore|SharedPreferences|SQLiteDatabase|RoomDatabase|Serializable|"
            r"Externalizable|Parcelable|Parcel|Bundle|Intent|File|Path)\b"
            r"|(?<![\w$.])(?:android|androidx|java\.nio\.file|kotlinx\.serialization|"
            r"com\.google\.gson|com\.squareup\.moshi)\.",
        )
        if forbidden.search(code):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC005_ADAPTER_DEPENDENCY",
                    "upload provider must not own Android/editor/persistence/serialization or "
                    "SecretStore authority",
                )
            )

        shape_tokens = (
            "final class OpenAiCompatibleUploadProvider",
            "implements RecognitionProvider<OpenAiCompatibleUploadProvider.StartRequest>",
            "private final Object lifecycleLock",
            "private final ProviderDescriptor descriptor",
            "private final ProviderConfig.Asr config",
            "private final UploadBackend backend",
            "private final Worker worker",
            "private SessionState active",
            "private boolean closed",
            "static final class ClientUploadBackend implements UploadBackend",
            "private static final class SingleWorker implements Worker",
            "private final class SessionState implements Session",
        )
        if (
            any(token not in no_comments for token in shape_tokens)
            or "public final class OpenAiCompatibleUploadProvider" in code
            or "Executors.newCachedThreadPool" in code
            or "Executors.newWorkStealingPool" in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC005_ADAPTER_SHAPE",
                    "upload adapter must remain package-confined, final, single-session, and own "
                    "one bounded worker plus one reviewed client backend",
                )
            )

        request_tokens = (
            "static final int MAX_PROMPT_CODE_POINTS = 2_000",
            "static final class StartRequest implements AutoCloseable",
            "private final SessionId sessionId",
            "private byte[] wav",
            "private final String language",
            "private final String prompt",
            "private final long durationMs",
            "source.length > OpenAiCompatibleClient.MAX_AUDIO_BYTES",
            "Arrays.copyOf(source, source.length)",
            "ProviderCapabilities.APP_CAPTURE_LIMIT_MS",
            "private synchronized AudioClaim claim()",
            "Arrays.fill(wav, (byte) 0)",
            "session=<redacted>",
            "audio=<redacted>",
            "language=<redacted>",
            "prompt=<redacted>",
        )
        if (
            any(token not in no_comments for token in request_tokens)
            or re.search(r"(?m)\bpublic\s+byte\s*\[\s*]\s+\w+\s*\(", code)
            or any(
                token in code
                for token in (
                    "AppSettings settings",
                    "String apiKey",
                    "String bearerToken",
                    "PersonalizationSnapshot",
                )
            )
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC005_REQUEST_BOUND",
                    "upload request must be one-use, defensive-copy audio, enforce app bounds, "
                    "and redact session/language/prompt/audio",
                )
            )

        credential_tokens = (
            "interface CredentialAccess",
            "String use(SecretRef reference, CredentialOperation operation) throws Exception",
            "interface CredentialOperation",
            "String apply(char[] credential) throws Exception",
            "private final CredentialAccess credentialAccess",
            "return credentialAccess.use(",
            "private String transcribeWithCredential(",
            "return client.transcribe(",
            "CredentialUnavailableException{content=<redacted>}",
        )
        raw_credential_field = re.search(
            r"(?m)^\s*(?:private|protected|public)\s+(?:final\s+)?(?:String|char\s*\[\s*])\s+"
            r"(?:apiKey|credential|secret|token)\b",
            code,
            re.IGNORECASE,
        )
        if (
            any(token not in no_comments for token in credential_tokens)
            or raw_credential_field is not None
            or no_comments.count("return client.transcribe(") != 1
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC005_CREDENTIAL_BOUNDARY",
                    "raw credentials may exist only as a synchronous char-array lease at the "
                    "single reviewed client call and must never become provider state",
                )
            )

        lifecycle_tokens = (
            "if (active != null)",
            "RecognitionRoute.FailureClass.RECOGNIZER_BUSY",
            "worker.execute(() -> runUpload(session))",
            "Executors.newSingleThreadExecutor",
            "executor.shutdownNow()",
            "backend.cancel()",
            "markTerminalLocked(session)",
            "if (active == session) active = null",
            "Thread.currentThread().isInterrupted()",
        )
        if any(token not in no_comments for token in lifecycle_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC005_LIFECYCLE",
                    "upload start/cancel/close must use one active lease, one bounded worker, "
                    "disconnect cancellation, and terminal revocation",
                )
            )

        event_counts = {
            "new RecognitionEvent.Preparing(": 1,
            "new RecognitionEvent.Ready(": 1,
            "new RecognitionEvent.Endpoint(": 1,
            "new RecognitionEvent.Final(": 1,
            "new RecognitionEvent.Failure(": 2,
            "new RecognitionEvent.Cancelled(": 1,
        }
        terminal_tokens = (
            "session.releaseReferences()",
            "Arrays.fill(audio, (byte) 0)",
            "audio = null",
            "language = null",
            "prompt = null",
            "sink = null",
        )
        if (
            any(no_comments.count(token) != count for token, count in event_counts.items())
            or any(token not in no_comments for token in terminal_tokens)
            or no_comments.count("session.releaseReferences()") != 2
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC005_EVENT_TERMINAL",
                    "batch upload must emit one monotonic Preparing/Ready/Endpoint/terminal path, "
                    "drop late results, zero audio, and clear content/sink references",
                )
            )

        if (
            no_comments.count("return RecognitionFailureMapper.fromUpload(error);") != 1
            or re.search(
                r"(?:Log\.|System\.out|System\.err|printStackTrace|error\.getMessage|"
                r"error\.toString)",
                no_comments,
            )
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC005_FAILURE_REDACTION",
                    "network/provider exceptions must map by stable type only and never expose "
                    "provider body, endpoint, credential, session, or throwable detail",
                )
            )

    else:
        client_tokens = (
            "public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024",
            "public static final int MAX_AUDIO_BYTES = 32 * 1024 * 1024",
            "char[] apiKey",
            "Arrays.fill(credential, '\\0')",
            "connection.setInstanceFollowRedirects(false)",
            "connection.setChunkedStreamingMode(8_192)",
            "writeAudio(output, wav, cancelled)",
            "throwIfCancelled(cancelled)",
            "RequestFailure.REDIRECT_REJECTED",
            "RequestFailure.RESPONSE_TOO_LARGE",
            "response was invalid",
            "public static final class RequestException extends IOException",
            "OpenAiCompatibleRequestException{failure=",
        )
        if (
            any(token not in no_comments for token in client_tokens)
            or no_comments.count("setInstanceFollowRedirects(false)") != 1
            or "setInstanceFollowRedirects(true)" in code
            or re.search(
                r"(?:Log\.|System\.out|System\.err|printStackTrace|body\s*\+|\+\s*body)",
                no_comments,
            )
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC005_CLIENT_CONTRACT",
                    "client upload seam must retain strict audio/response bounds, borrowed "
                    "credential cleanup, cancellation polling, redirect rejection, typed failures, "
                    "and body-redacted diagnostics",
                )
            )
    return tuple(findings)


def _inspect_rec006_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in REC006_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    if set(imports) != set(REC006_ALLOWED_IMPORTS[relative_path]):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC006_ADAPTER_DEPENDENCY",
                "SenseVoice adapter, device probe, and private-process client dependencies must "
                "remain the exact reviewed Android/native/bounded-worker surfaces",
            )
        )

    if relative_path == SENSEVOICE_FINAL_PROVIDER_PATH:
        forbidden = re.compile(
            r"\b(?:Activity|Service|InputConnection|EditorOperation|ProviderConfig|SecretRef|"
            r"AppSettings|SharedPreferences|SQLiteDatabase|RoomDatabase|Serializable|"
            r"Externalizable|Parcelable|Parcel|Bundle|Intent|File|Path|URL|HttpURLConnection|"
            r"Socket)\b"
            r"|(?<![\w$.])(?:androidx|java\.net|java\.nio\.file|kotlinx\.serialization|"
            r"com\.google\.gson|com\.squareup\.moshi)\.",
        )
        if forbidden.search(code):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC006_ADAPTER_DEPENDENCY",
                    "local final provider must not own editor, UI, network, persistence, secret, "
                    "filesystem, or serialization authority",
                )
            )

        shape_tokens = (
            "final class SenseVoiceFinalProvider",
            "implements RecognitionProvider<SenseVoiceFinalProvider.StartRequest>",
            "private final Object lifecycleLock",
            "private final ProviderDescriptor descriptor",
            "private final Backend backend",
            "private final Worker worker",
            "private SessionState active",
            "private boolean closed",
            "static final class ClientBackend implements Backend",
            "private static final class SingleWorker implements Worker",
            "private final class SessionState implements Session",
        )
        if (
            any(token not in no_comments for token in shape_tokens)
            or "public final class SenseVoiceFinalProvider" in code
            or "Executors.newCachedThreadPool" in code
            or "Executors.newWorkStealingPool" in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC006_ADAPTER_SHAPE",
                    "SenseVoice adapter must remain package-confined, final, single-session, "
                    "and own one reviewed client backend plus one bounded worker",
                )
            )

        request_tokens = (
            "static final class StartRequest implements AutoCloseable",
            "private final SessionId sessionId",
            "private byte[] wav",
            "private final String language",
            "private final boolean useInverseTextNormalization",
            "private final long durationMs",
            "source.length < 44",
            "source.length > LocalOfflineRecognitionService.MAX_WAV_BYTES",
            "Arrays.copyOf(source, source.length)",
            "ProviderCapabilities.APP_CAPTURE_LIMIT_MS",
            "private synchronized AudioClaim claim()",
            "Arrays.fill(wav, (byte) 0)",
            "session=<redacted>",
            "audio=<redacted>",
            "language=<redacted>",
        )
        if (
            any(token not in no_comments for token in request_tokens)
            or re.search(r"(?m)\bpublic\s+byte\s*\[\s*]\s+\w+\s*\(", code)
            or any(token in code for token in ("String modelPath", "File model", "String prompt"))
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC006_REQUEST_BOUND",
                    "local request must be one-use, defensive-copy bounded WAV/language/duration, "
                    "zero owned bytes, and expose no model path or content",
                )
            )

        lifecycle_tokens = (
            "if (active != null)",
            "RecognitionRoute.FailureClass.RECOGNIZER_BUSY",
            "worker.execute(() -> runRecognition(session))",
            "Executors.newSingleThreadExecutor",
            "executor.shutdownNow()",
            "backend.cancel()",
            "markTerminalLocked(session)",
            "if (active == session) active = null",
            "Thread.currentThread().isInterrupted()",
        )
        if any(token not in no_comments for token in lifecycle_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC006_LIFECYCLE",
                    "SenseVoice start/cancel/close must use one active lease, one bounded worker, "
                    "private-process cancellation, and terminal revocation",
                )
            )

        event_counts = {
            "new RecognitionEvent.Preparing(": 1,
            "new RecognitionEvent.Ready(": 1,
            "new RecognitionEvent.Final(": 1,
            "new RecognitionEvent.Failure(": 2,
            "new RecognitionEvent.Cancelled(": 1,
        }
        terminal_tokens = (
            "session.releaseReferences()",
            "Arrays.fill(audio, (byte) 0)",
            "audio = null",
            "language = null",
            "sink = null",
        )
        forbidden_events = (
            "new RecognitionEvent.Partial(",
            "new RecognitionEvent.SpeechStarted(",
            "new RecognitionEvent.Endpoint(",
        )
        if (
            any(no_comments.count(token) != count for token, count in event_counts.items())
            or any(token not in no_comments for token in terminal_tokens)
            or any(token in no_comments for token in forbidden_events)
            or no_comments.count("session.releaseReferences()") != 2
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC006_EVENT_TERMINAL",
                    "local quality provider must emit final-only Preparing/Ready/terminal events, "
                    "drop late results, zero audio, and clear content/sink references",
                )
            )

        availability_tokens = (
            "LocalAvailability availability",
            "LocalAvailability.READY",
            "RecognitionFailureMapper.fromLocalAvailability(availability)",
            "RecognitionFailureMapper.fromLocalRuntime(availability, error)",
            "RecognitionRoute.FailureClass.NO_MATCH",
            "RecognitionRoute.FailureClass.RECOGNIZER_BUSY",
            "RecognitionRoute.FailureClass.INTERNAL_ERROR",
        )
        if any(token not in no_comments for token in availability_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC006_AVAILABILITY_MAPPING",
                    "model/device/runtime outcomes must map only to the reviewed stable failure "
                    "classification",
                )
            )

        client_tokens = (
            "new ClientBackend(application, new LocalOfflineRecognitionClient(application))",
            "LocalOfflineRecognizer.deviceSupport(context)",
            "OfflineModelStore.status(context)",
            "LocalOfflineRecognitionClient.Result result = client.recognize(",
            "client.cancelActive()",
            "client.close()",
            "result.punctuatedText()",
            "result.exactText()",
        )
        if (
            any(token not in no_comments for token in client_tokens)
            or no_comments.count("client.recognize(") != 1
            or no_comments.count("new LocalOfflineRecognitionClient(") != 1
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC006_CLIENT_BINDING",
                    "production backend must use the one private-process client, exact device/model "
                    "probes, one recognition call, and direct cancellation/close",
                )
            )

        if re.search(
            r"(?:Log\.|System\.out|System\.err|printStackTrace|error\.getMessage|"
            r"error\.toString|throwable\.getMessage|throwable\.toString)",
            no_comments,
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC006_FAILURE_REDACTION",
                    "local provider failures must discard throwable/native/model-path/audio detail",
                )
            )

    elif relative_path == LOCAL_OFFLINE_RECOGNIZER_PATH:
        support_tokens = (
            "public enum DeviceSupport { SUPPORTED, LOW_MEMORY, UNSUPPORTED_ABI, SYSTEM_UNAVAILABLE }",
            "public static DeviceSupport deviceSupport(Context context)",
            "manager != null && manager.isLowRamDevice()",
            "Build.SUPPORTED_ABIS",
            "if (!activityManagerAvailable) return DeviceSupport.SYSTEM_UNAVAILABLE",
            "if (lowRam) return DeviceSupport.LOW_MEMORY",
            "if (!supportsAbi(abis)) return DeviceSupport.UNSUPPORTED_ABI",
            "return DeviceSupport.SUPPORTED",
            '"arm64-v8a".equals(abi)',
            '"x86_64".equals(abi)',
        )
        if any(token not in no_comments for token in support_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC006_DEVICE_SUPPORT",
                    "device support must remain the closed low-RAM/system/ABI probe used by the "
                    "local provider",
                )
            )

    else:
        result_tokens = (
            "public record Result(String exactText, String punctuatedText)",
            "exactText = requireText(exactText)",
            "punctuatedText = requireText(punctuatedText)",
            "requireWellFormedUtf16(text)",
            "text.codePointCount(0, text.length()) > 20_000",
            "LocalOfflineRecognitionResult{content=<redacted>}",
        )
        if (
            any(token not in no_comments for token in result_tokens)
            or re.search(
                r"toString\s*\([^)]*\)[^{]*\{[^}]*(?:exactText|punctuatedText)\s*\+",
                no_comments,
            )
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC006_CLIENT_RESULT",
                    "private-process results must validate bounded well-formed text and keep "
                    "diagnostics content-free",
                )
            )
    return tuple(findings)


def _inspect_rec006_scope_source(
    relative_path: str,
    no_comments: str,
) -> tuple[ArchitectureViolation, ...]:
    if relative_path in {
        SENSEVOICE_FINAL_PROVIDER_PATH,
        TWO_STAGE_STREAMING_PROVIDER_PATH,
    }:
        return ()
    internal_reference = re.search(
        r"\bSenseVoiceFinalProvider\s*\.\s*(?:StartRequest|Availability|Backend|Worker|"
        r"ClientBackend|SingleWorker|AudioClaim|SessionState)\b",
        no_comments,
    )
    if internal_reference is None:
        return ()
    return (
        ArchitectureViolation(
            relative_path,
            "REC006_ADAPTER_SCOPE",
            "SenseVoice request, backend, worker, claim, availability, and session internals may "
            "not escape the reviewed provider family",
        ),
    )


def _inspect_rec007_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in REC007_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    if set(imports) != set(REC007_ALLOWED_IMPORTS[relative_path]):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC007_ADAPTER_DEPENDENCY",
                "prefix replay may depend only on the reviewed local model, bounded audio, "
                "provider event, and one-worker surfaces",
            )
        )

    forbidden = re.compile(
        r"\b(?:Activity|Service|InputConnection|EditorOperation|ProviderConfig|SecretRef|"
        r"AppSettings|SharedPreferences|SQLiteDatabase|RoomDatabase|Serializable|Externalizable|"
        r"Parcelable|Parcel|Bundle|Intent|File|Path|URL|HttpURLConnection|Socket)\b"
        r"|(?<![\w$.])(?:androidx|java\.net|java\.nio\.file|kotlinx\.serialization|"
        r"com\.google\.gson|com\.squareup\.moshi)\.",
    )
    if forbidden.search(code):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC007_ADAPTER_DEPENDENCY",
                "prefix replay must not own editor, UI, network, persistence, secret, filesystem, "
                "or serialization authority",
            )
        )

    if relative_path == PREFIX_REPLAY_PREVIEW_PROVIDER_PATH:
        shape_tokens = (
            "final class PrefixReplayPreviewProvider",
            "implements RecognitionProvider<PrefixReplayPreviewProvider.StartRequest>",
            "private final Object lifecycleLock",
            "private final ProviderDescriptor descriptor",
            "private final Backend backend",
            "private SessionState active",
            "private boolean closed",
            "interface PreviewSession extends RecognitionProvider.Session",
            "private final class SessionState implements PreviewSession",
            "private static final class LocalPreviewBackend implements Backend",
        )
        if (
            any(token not in no_comments for token in shape_tokens)
            or "public final class PrefixReplayPreviewProvider" in code
            or "Executors.newCachedThreadPool" in code
            or "Executors.newWorkStealingPool" in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC007_ADAPTER_SHAPE",
                    "preview adapter must remain package-confined, final, one-session, and expose "
                    "only its bounded PCM session seam",
                )
            )

        capability_tokens = (
            '"builtin.local-prefix-replay"',
            "ProviderCapabilities.prefixReplayPreview()",
        )
        if any(token not in no_comments for token in capability_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC007_CAPABILITY_DECLARATION",
                    "prefix replay must use its exact canonical identity and explicitly declare "
                    "revisable, on-device, non-streaming PREFIX_REPLAY semantics",
                )
            )

        request_tokens = (
            "static final class StartRequest implements AutoCloseable",
            "private final SessionId sessionId",
            "private final String language",
            "private boolean claimed",
            "private synchronized RequestClaim claim()",
            "language is outside its bound",
            "language must be well-formed UTF-16",
            "session=<redacted>",
            "language=<redacted>",
        )
        if (
            any(token not in no_comments for token in request_tokens)
            or re.search(r"(?m)\bpublic\s+byte\s*\[\s*]\s+\w+\s*\(", code)
            or any(token in code for token in ("String modelPath", "String transcript", "File model"))
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC007_REQUEST_BOUND",
                    "preview start request must be one-use, bounded, content-free, and expose no "
                    "audio, model, transcript, or session identity",
                )
            )

        lifecycle_tokens = (
            "if (active != null)",
            "RecognitionRoute.FailureClass.RECOGNIZER_BUSY",
            "session.engine = backend.open(",
            "session.ready = true",
            "engine.cancel()",
            "markTerminalLocked(session)",
            "if (active == session) active = null",
            "session.releaseReferences()",
        )
        if any(token not in no_comments for token in lifecycle_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC007_LIFECYCLE",
                    "preview start/cancel/close must retain one active lease, revoke before engine "
                    "teardown, and release sink/engine references",
                )
            )

        event_counts = {
            "new RecognitionEvent.Preparing(": 1,
            "new RecognitionEvent.Ready(": 1,
            "new RecognitionEvent.Partial(": 1,
            "new RecognitionEvent.Failure(": 2,
            "new RecognitionEvent.Cancelled(": 1,
        }
        event_tokens = (
            "session.lastPartialSequence",
            "revisionOf",
            "value.strip()",
        )
        forbidden_events = (
            "new RecognitionEvent.Final(",
            "new RecognitionEvent.Endpoint(",
            "new RecognitionEvent.SpeechStarted(",
        )
        if (
            any(no_comments.count(token) != count for token, count in event_counts.items())
            or any(token not in no_comments for token in event_tokens)
            or re.search(
                r"new\s+RecognitionEvent\.Partial\s*\([^;]+?value\.strip\(\)\s*,\s*"
                r"0\s*,\s*revisionOf\s*\)",
                no_comments,
                re.DOTALL,
            )
            is None
            or any(token in no_comments for token in forbidden_events)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC007_EVENT_CONTRACT",
                    "preview must emit only Preparing/Ready/fully-revisable Partial and one stable "
                    "Failure/Cancelled terminal, never Final/Endpoint/true-streaming events",
                )
            )

        bound_tokens = (
            "MAX_PCM_BYTES = LocalRealtimePreview.MAX_PCM_BYTES",
            "session.acceptedPcmBytes >= MAX_PCM_BYTES",
            "int safeLength = Math.min(length, data.length) & ~1",
            "int remaining = MAX_PCM_BYTES - session.acceptedPcmBytes",
            "copied = Arrays.copyOf(data, accepted)",
            "Arrays.fill(copied, (byte) 0)",
        )
        if any(token not in no_comments for token in bound_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC007_PCM_BOUND",
                    "preview PCM must be defensive-copy, even-aligned, zeroed, and capped at the "
                    "reviewed 30-second prefix",
                )
            )

        backend_tokens = (
            "LocalOfflineRecognizer.deviceSupport(context)",
            "OfflineModelStore.status(context)",
            "new LocalRealtimePreview(",
            "preview.accept(pcm, length)",
            "preview.cancel()",
            "RecognitionFailureMapper.fromLocalAvailability(availability)",
            "RecognitionFailureMapper.fromLocalRuntime(availability, null)",
            "LocalAvailability.MODEL_MISSING",
            "LocalAvailability.MODEL_CORRUPT",
        )
        if (
            any(token not in no_comments for token in backend_tokens)
            or no_comments.count("new LocalRealtimePreview(") != 1
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC007_BACKEND_BINDING",
                    "production preview must bind one lazy LocalRealtimePreview to exact device/"
                    "model classification and direct cancellation",
                )
            )

    else:
        bound_tokens = (
            "INITIAL_PCM_BYTES = AudioCapture.SAMPLE_RATE * 2 * 3 / 4",
            "STEP_PCM_BYTES = AudioCapture.SAMPLE_RATE * 2 * 3 / 4",
            "MAX_PCM_BYTES = AudioCapture.SAMPLE_RATE * 2 * 30",
            "private final ExecutorService executor = Executors.newSingleThreadExecutor()",
            "if (closed || decoding",
            "byte[] snapshot = Arrays.copyOf(pcm, size)",
            "decoding = true",
            "executor.shutdownNow()",
            "Arrays.fill(snapshot, (byte) 0)",
            "Arrays.fill(wav, (byte) 0)",
            "Arrays.fill(pcm, (byte) 0)",
            "private static final class LazySessionDecoder implements Decoder",
            "LocalOfflineRecognizer.openSession(context, configuredLanguage)",
        )
        if (
            any(token not in no_comments for token in bound_tokens)
            or no_comments.count("Executors.newSingleThreadExecutor()") != 1
            or any(token in no_comments for token in (
                "Executors.newCachedThreadPool",
                "Executors.newWorkStealingPool",
            ))
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC007_LEGACY_PREVIEW_BOUND",
                    "LocalRealtimePreview must keep 750 ms thresholds, one coalescing worker, a "
                    "30-second cap, lazy model work, cancellation, and audio zeroing",
                )
            )

    if re.search(
        r"(?:Log\.|System\.out|System\.err|printStackTrace|error\.getMessage|"
        r"error\.toString|throwable\.getMessage|throwable\.toString)",
        no_comments,
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC007_FAILURE_REDACTION",
                "prefix replay failures must discard model/audio/transcript/throwable details",
            )
        )
    return tuple(findings)


def _inspect_rec007_scope_source(
    relative_path: str,
    no_comments: str,
) -> tuple[ArchitectureViolation, ...]:
    if relative_path == PREFIX_REPLAY_PREVIEW_PROVIDER_PATH:
        return ()
    internal_reference = re.search(
        r"\bPrefixReplayPreviewProvider\s*\.\s*(?:StartRequest|PreviewSession|Availability|"
        r"PartialSink|PreviewEngine|Backend|SessionState|RequestClaim|LocalPreviewBackend)\b",
        no_comments,
    )
    if internal_reference is None:
        return ()
    return (
        ArchitectureViolation(
            relative_path,
            "REC007_ADAPTER_SCOPE",
            "prefix request/session/backend/engine/availability internals may not escape the "
            "reviewed provider family",
        ),
    )


def _inspect_rec008_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in REC008_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    if relative_path in REC008_ALLOWED_IMPORTS and set(imports) != set(
        REC008_ALLOWED_IMPORTS[relative_path]
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC008_FAILURE_DEPENDENCY",
                "failure mapping may depend only on the reviewed Android error constants, "
                "content-free provider types, and bounded legacy view",
            )
        )

    if relative_path == RECOGNITION_FAILURE_MAPPER_PATH:
        local_shape = re.search(
            r"enum\s+LocalAvailability\s*\{\s*READY\s*,\s*MODEL_MISSING\s*,\s*"
            r"MODEL_CORRUPT\s*,\s*LOW_MEMORY\s*,\s*UNSUPPORTED_ABI\s*,\s*"
            r"SYSTEM_UNAVAILABLE\s*\}",
            no_comments,
            re.DOTALL,
        )
        method_tokens = (
            "final class RecognitionFailureMapper",
            "private RecognitionFailureMapper()",
            "fromAndroidSystem(",
            "fromUpload(",
            "fromUploadFailure(",
            "fromLocalAvailability(",
            "fromLocalRuntime(",
            "fromLegacyAndroidError(",
            "fromLegacyPipelineMessage(",
            "toAndroidErrorCode(",
            "stableMessage(",
            "SystemSpeechRecognizer.MICROPHONE_ACCESS_BLOCKED.equals(internalMessage)",
            "return RecognitionRoute.FailureClass.INTERNAL_ERROR",
        )
        stable_start = no_comments.find("static String stableMessage(")
        stable_end = no_comments.find("private static boolean containsAny", stable_start)
        stable_body = no_comments[stable_start:stable_end] if stable_start >= 0 else ""
        failure_names = (
            "UNAVAILABLE",
            "MODEL_MISSING",
            "PERMISSION_DENIED",
            "OEM_MIC_BLOCKED",
            "AUDIO_ERROR",
            "NETWORK_UNAVAILABLE",
            "NETWORK_TIMEOUT",
            "AUTHENTICATION",
            "QUOTA_EXCEEDED",
            "RATE_LIMITED",
            "SERVER_ERROR",
            "PROTOCOL_ERROR",
            "RECOGNIZER_BUSY",
            "NO_MATCH",
            "SPEECH_TIMEOUT",
            "UNSUPPORTED_LANGUAGE",
            "CANCELLED",
            "TARGET_CHANGED",
            "INTERNAL_ERROR",
        )
        if (
            local_shape is None
            or any(token not in no_comments for token in method_tokens)
            or any(f"case {name} ->" not in stable_body for name in failure_names)
            or "public final class RecognitionFailureMapper" in no_comments
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC008_MAPPER_SHAPE",
                    "one package-confined mapper must exhaustively own Android, upload, local, "
                    "legacy, Android-code, and stable-message mappings for all 19 failures",
                )
            )

        redaction_pattern = re.compile(
            r"(?:Log\.|System\.out|System\.err|printStackTrace|\.getMessage\s*\(|"
            r"\.toString\s*\(\)|return\s+(?:rawMessage|internalMessage)\b|"
            r"throw\s+new\s+\w+Exception\s*\(\s*(?:rawMessage|internalMessage))"
        )
        if (
            redaction_pattern.search(no_comments)
            or re.search(r"(?m)^\s*(?:private|protected|public)\s+.*\b(?:message|error|"
                         r"throwable|cause)\s*;\s*$", no_comments)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC008_FAILURE_REDACTION",
                    "raw OEM/provider/transport/pipeline detail may be inspected transiently but "
                    "must never be retained, returned, thrown, printed, or logged",
                )
            )

    elif relative_path == RECOGNITION_FAILURE_PATH:
        shape = re.search(
            r"public\s+record\s+RecognitionFailure\s*\(\s*"
            r"RecognitionRoute\.FailureClass\s+failureClass\s*,\s*int\s+errorCode\s*,\s*"
            r"String\s+message\s*\)",
            no_comments,
            re.DOTALL,
        )
        tokens = (
            "MAX_MESSAGE_CODE_POINTS = 300",
            "RecognitionFailureMapper.fromLegacyAndroidError(errorCode)",
            "RecognitionFailureMapper.toAndroidErrorCode(failureClass)",
            "RecognitionFailureMapper.stableMessage(failureClass)",
            "message=<redacted>",
            "must be well-formed",
        )
        # The implementation returns the stable fallback directly rather than throwing a text-
        # bearing error; accept its explicit surrogate checks as the well-formed proof token.
        if "must be well-formed" not in no_comments:
            tokens = tuple(token for token in tokens if token != "must be well-formed") + (
                "Character.isHighSurrogate(unit)",
                "Character.isLowSurrogate",
            )
        if (
            shape is None
            or any(token not in no_comments for token in tokens)
            or re.search(r"return\s+.*\+\s*message\b", no_comments)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC008_LEGACY_FAILURE_SHAPE",
                    "legacy Android failures must carry the stable class plus bounded local "
                    "display text and redact that text from diagnostics",
                )
            )

    elif relative_path == RECOGNITION_ERRORS_PATH:
        tokens = (
            "RecognitionFailureMapper.fromLegacyPipelineMessage(message)",
            "RecognitionFailureMapper.stableMessage(failureClass)",
            "RecognitionRoute.FailureClass.RECOGNIZER_BUSY",
            "RecognitionRoute.FailureClass.NO_MATCH",
            "RecognitionRoute.FailureClass.UNAVAILABLE",
            "RecognitionRoute.FailureClass.AUTHENTICATION",
        )
        if (
            any(token not in no_comments for token in tokens)
            or "message.trim()" in no_comments
            or re.search(r"new\s+RecognitionFailure\s*\([^;]*\bmessage\s*\)", no_comments)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC008_LEGACY_MAPPING",
                    "legacy failures must classify through the unified mapper and replace raw "
                    "pipeline detail with stable local text",
                )
            )

    elif relative_path == ANDROID_SYSTEM_PROVIDER_PATH:
        if no_comments.count(
            "return RecognitionFailureMapper.fromAndroidSystem(errorCode, message);"
        ) != 1:
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC008_PROVIDER_DELEGATION",
                    "Android system errors must delegate exactly once to the unified mapper",
                )
            )
    elif relative_path == OPENAI_UPLOAD_PROVIDER_PATH:
        if (
            no_comments.count("return RecognitionFailureMapper.fromUpload(error);") != 1
            or re.search(r"instanceof\s+(?:SocketTimeoutException|UnknownHostException|"
                         r"ConnectException|NoRouteToHostException|IOException)", no_comments)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC008_PROVIDER_DELEGATION",
                    "upload errors must delegate exactly once to the unified mapper",
                )
            )
    elif relative_path in {
        SENSEVOICE_FINAL_PROVIDER_PATH,
        PREFIX_REPLAY_PREVIEW_PROVIDER_PATH,
        LOCAL_STREAMING_PROVIDER_PATH,
    }:
        expected_runtime = (
            "null"
            if relative_path == PREFIX_REPLAY_PREVIEW_PROVIDER_PATH
            else "error"
        )
        if (
            no_comments.count(
                "RecognitionFailureMapper.fromLocalAvailability(availability)"
            )
            != 1
            or no_comments.count(
                f"RecognitionFailureMapper.fromLocalRuntime(availability, {expected_runtime})"
            )
            != 1
            or "enum Availability" in no_comments
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC008_PROVIDER_DELEGATION",
                    "both local providers must share the exact local availability/runtime mapper",
                )
            )

    return tuple(findings)


def _inspect_rec008_scope_source(
    relative_path: str,
    no_comments: str,
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    if (
        "RecognitionFailureMapper" in no_comments
        and relative_path not in REC008_MAPPER_ALLOWED_CONSUMERS
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC008_MAPPER_SCOPE",
                "only the reviewed providers and legacy failure boundary may use the mapper",
            )
        )
    if "new RecognitionFailure(" in no_comments and relative_path not in {
        RECOGNITION_ERRORS_PATH,
        "com/opentypeless/android/recognition/OpenTypelessRecognizerActivity.java",
        "com/opentypeless/android/recognition/VoicePipelineRecognitionEngine.java",
        "com/opentypeless/android/recognition/RecognitionSessionController.java",
    }:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC008_LEGACY_FAILURE_SCOPE",
                "legacy failure construction is default-deny outside reviewed Android surfaces",
            )
        )
    return tuple(findings)


def _inspect_rec009_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in {RECOGNITION_ROUTER_PATH, PROVIDER_REGISTRY_PATH}:
        return ()

    findings: list[ArchitectureViolation] = []
    if relative_path == RECOGNITION_ROUTER_PATH:
        forbidden = re.compile(
            r"\b(?:Serializable|Externalizable|Parcelable|Parcel|Bundle|Intent|Context|"
            r"InputConnection|EditorOperation|RecognitionProvider|Executor|Thread|Future|"
            r"File|Path|Socket|URL|HttpClient|SecretRef|ProviderConfig|SharedPreferences|"
            r"DataStore|RoomDatabase|AudioRecord|AudioCapture)\b"
            r"|(?<![\w$.])(?:android|androidx|java\.io|java\.net|java\.nio\.file|"
            r"java\.util\.concurrent|kotlinx\.serialization|com\.google\.gson|"
            r"com\.squareup\.moshi|com\.fasterxml\.jackson)\.",
        )
        content_field = re.compile(
            r"(?m)^[ \t]*(?:(?:private|protected|public|static|final|volatile|transient)\s+)*"
            r"(?:String|CharSequence|byte\[\]|char\[\]|Throwable|Exception|"
            r"RecognitionEvent|RecognitionFailure|ProviderDescriptor)\s+\w+\s*(?:=|;)"
        )
        if (
            set(imports) != set(REC009_ALLOWED_IMPORTS)
            or forbidden.search(code)
            or content_field.search(code)
            or CFG001_SECRET_IDENTIFIER_PATTERN.search(code)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC009_ROUTER_DEPENDENCY",
                    "router must remain a content-free finite decision machine with no Android, "
                    "provider execution, persistence, endpoint, secret, or transcript authority",
                )
            )

        shape_patterns = (
            r"(?m)^final\s+class\s+RecognitionRouter\s*\{",
            r"private\s+final\s+RecognitionRoute\s+route",
            r"private\s+final\s+ProviderRegistry\s+registry",
            r"private\s+final\s+EffectiveProfile\s+effectiveProfile",
            r"private\s+final\s+PrivacyAuthorization\s+privacyAuthorization",
            r"private\s+Status\s+status\s*=\s*Status\.NEW",
            r"private\s+Attempt\s+activeAttempt",
            r"private\s+ConfirmationRequest\s+pendingConfirmation",
            r"private\s+long\s+attemptGeneration",
            r"synchronized\s+Decision\s+start\s*\(\s*\)",
            r"synchronized\s+Decision\s+onFailure\s*\(\s*Attempt\s+expected\s*,\s*"
            r"FailureClass\s+failureClass\s*\)",
            r"synchronized\s+Decision\s+onSuccess\s*\(\s*Attempt\s+expected\s*\)",
            r"synchronized\s+Decision\s+onConfirmation\s*\(\s*ConfirmationRequest\s+expected\s*,\s*"
            r"ConfirmationDecision\s+decision\s*\)",
            r"synchronized\s+boolean\s+isCurrent\s*\(\s*Attempt\s+expected\s*\)",
            r"private\s+enum\s+Status\s*\{\s*NEW\s*,\s*ACTIVE\s*,\s*"
            r"AWAITING_CONFIRMATION\s*,\s*COMPLETED\s*,\s*FAILED\s*\}",
        )
        decision_patterns = (
            r"sealed\s+interface\s+Decision\s+permits\s+AttemptReady\s*,\s*"
            r"ConfirmationRequired\s*,\s*RouteFailed\s*,\s*Completed\s*,\s*Ignored",
            r"record\s+AttemptReady\s*\(\s*Attempt\s+attempt\s*\)\s+implements\s+Decision",
            r"record\s+ConfirmationRequired\s*\(\s*ConfirmationRequest\s+request\s*\)"
            r"\s+implements\s+Decision",
            r"record\s+RouteFailed\s*\(\s*FailureClass\s+failureClass\s*,\s*"
            r"FailureReason\s+reason\s*\)\s+implements\s+Decision",
            r"record\s+Completed\s*\(\s*\)\s+implements\s+Decision",
            r"record\s+Ignored\s*\(\s*IgnoreReason\s+reason\s*\)\s+implements\s+Decision",
            r"static\s+final\s+class\s+Attempt\s*\{",
            r"private\s+Attempt\s*\(",
            r"static\s+final\s+class\s+ConfirmationRequest\s*\{",
            r"private\s+ConfirmationRequest\s*\(",
        )
        if (
            any(re.search(pattern, code, re.DOTALL) is None for pattern in shape_patterns)
            or any(re.search(pattern, code, re.DOTALL) is None for pattern in decision_patterns)
            or "public class RecognitionRouter" in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC009_ROUTER_SHAPE",
                    "router must remain package-confined, final, synchronized, bounded and expose "
                    "only closed decisions plus privately constructed identity tokens",
                )
            )

        capability_tokens = (
            "case STREAMING -> capabilities.supportsStreaming()",
            "case PARTIAL_REVISION -> capabilities.supportsPartialRevision()",
            "case ENDPOINTING -> capabilities.supportsEndpointing()",
            "case ON_DEVICE -> capabilities.supportsOnDevice()",
            "case PROMPT -> capabilities.supportsPrompt()",
            "case BIASING_TERMS -> capabilities.supportsBiasingTerms()",
            "case DYNAMIC_KEYTERMS -> capabilities.supportsDynamicKeyterms()",
            "case LANGUAGE_DETECTION -> capabilities.supportsLanguageDetection()",
            "case TIMESTAMPS -> capabilities.supportsTimestamps()",
            "case AUDIO_UPLOAD -> capabilities.supportsAudioUpload()",
        )
        route_tokens = (
            "registry.routeLease(step.providerId())",
            "capabilities.privacyClass() != step.privacyClass()",
            "registry.isCurrent(lease)",
            "step.retryPolicy().maximumAttempts()",
            "step.retryPolicy().retryOn().contains(failure)",
            "step.fallbackOn().contains(failure)",
            "attempt.stepIndex + 1 < route.steps().size()",
            "FailureClass.CANCELLED",
            "FailureClass.PERMISSION_DENIED",
            "FailureClass.TARGET_CHANGED",
            "policy == ConfirmationPolicy.REQUIRE_BEFORE_USE",
            "policy == ConfirmationPolicy.REQUIRE_ON_PRIVACY_DOWNGRADE",
            "if (attemptGeneration == Long.MAX_VALUE)",
        )
        if (
            any(token not in no_comments for token in capability_tokens)
            or any(token not in no_comments for token in route_tokens)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC009_ROUTE_POLICY",
                    "every step must use an exact current registry lease, exhaustively verify "
                    "capability/privacy, bound retry/fallback, stop terminal failures, and block "
                    "confirmation or generation exhaustion",
                )
            )

        if (
            "identities=<redacted>" not in no_comments
            or "provider=<redacted>" not in no_comments
            or "generation=<redacted>" not in no_comments
            or re.search(
                r"toString\s*\([^)]*\)[^{]*\{[^}]*(?:providerId\s*\+|\.descriptor\s*\(\)|"
                r"generation\s*\+|route\.id\s*\(\))",
                no_comments,
            )
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC009_ROUTER_REDACTION",
                    "router diagnostics must redact route/provider/generation identity and never "
                    "render descriptors or execution data",
                )
            )

    else:
        lease_patterns = (
            r"synchronized\s+RouteLookupResult\s+routeLease\s*\(\s*String\s+providerId\s*\)",
            r"synchronized\s+boolean\s+isCurrent\s*\(\s*RouteLease\s+lease\s*\)",
            r"sealed\s+interface\s+RouteLookupResult\s+permits\s+RouteLeaseFound\s*,\s*"
            r"RouteLeaseRejected",
            r"record\s+RouteLeaseFound\s*\(\s*RouteLease\s+lease\s*\)\s+implements\s+"
            r"RouteLookupResult",
            r"record\s+RouteLeaseRejected\s*\(\s*AccessFailure\s+failure\s*\)\s+implements\s+"
            r"RouteLookupResult",
            r"static\s+final\s+class\s+RouteLease\s*\{",
            r"private\s+final\s+ProviderRegistry\s+owner",
            r"private\s+final\s+Entry\s+entry",
            r"private\s+final\s+long\s+generation",
            r"private\s+RouteLease\s*\(",
        )
        lease_tokens = (
            "new RouteLease(this, entry, entry.generation)",
            "lease.owner != this",
            "current == lease.entry",
            "current.enabled",
            "current.generation == lease.generation",
            "RouteLease{provider=<redacted>, generation=<redacted>}",
        )
        if (
            any(re.search(pattern, code, re.DOTALL) is None for pattern in lease_patterns)
            or any(token not in no_comments for token in lease_tokens)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC009_REGISTRY_LEASE",
                    "route selection must receive a privately constructed owner/entry/generation "
                    "lease and revalidate exact enabled identity to prevent registry ABA",
                )
            )
    return tuple(findings)


def _inspect_rec009_scope_source(
    relative_path: str,
    no_comments: str,
) -> tuple[ArchitectureViolation, ...]:
    allowed = {
        RECOGNITION_ROUTER_PATH,
        PROVIDER_REGISTRY_PATH,
        RECOGNITION_ROUTER_VOICE_CONTROLLER_PATH,
    }
    findings: list[ArchitectureViolation] = []
    if re.search(r"\bRecognitionRouter\b", no_comments) and relative_path not in allowed:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC009_ROUTER_SCOPE",
                "router remains an unwired package-confined primitive until the reviewed routing "
                "integration task",
            )
        )
    if (
        re.search(r"\b(?:RouteLease|RouteLookupResult|RouteLeaseFound|RouteLeaseRejected)\b", no_comments)
        and relative_path not in allowed
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC009_LEASE_SCOPE",
                "registry route leases may be consumed only by the exact RecognitionRouter",
            )
        )
    if ".routeLease(" in no_comments and relative_path != RECOGNITION_ROUTER_PATH:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC009_LEASE_CALLER",
                "only the exact RecognitionRouter may acquire a registry route lease",
            )
        )
    return tuple(findings)


def _inspect_rec010_source(
    relative_path: str,
    code: str,
    no_comments: str,
) -> tuple[ArchitectureViolation, ...]:
    if relative_path != RECOGNITION_ROUTER_PATH:
        return ()

    findings: list[ArchitectureViolation] = []
    shape_patterns = (
        r"private\s+final\s+EffectiveProfile\s+effectiveProfile",
        r"private\s+final\s+PrivacyAuthorization\s+privacyAuthorization",
        r"synchronized\s+Decision\s+onConfirmation\s*\(\s*ConfirmationRequest\s+expected\s*,\s*"
        r"ConfirmationDecision\s+decision\s*\)",
        r"static\s+final\s+class\s+PrivacyAuthorization\s*\{",
        r"private\s+final\s+EffectiveProfile\s+ownerProfile",
        r"private\s+final\s+AuthorizationMode\s+mode",
        r"private\s+final\s+PrivacyClass\s+maximumPrivacy",
        r"private\s+PrivacyAuthorization\s*\(",
        r"static\s+PrivacyAuthorization\s+requireConfirmation\s*\(\s*EffectiveProfile\s+profile\s*\)",
        r"static\s+PrivacyAuthorization\s+preauthorized\s*\(\s*EffectiveProfile\s+profile\s*,\s*"
        r"PrivacyClass\s+maximumPrivacy\s*\)",
        r"enum\s+ConfirmationDecision\s*\{\s*APPROVE_ONCE\s*,\s*CANCEL\s*\}",
        r"private\s+enum\s+AuthorizationMode\s*\{\s*REQUIRE_CONFIRMATION\s*,\s*PREAUTHORIZED\s*\}",
        r"static\s+final\s+class\s+ConfirmationRequest\s*\{[^}]*"
        r"private\s+final\s+RouteLease\s+lease[^}]*"
        r"private\s+final\s+int\s+attemptNumber",
    )
    if any(re.search(pattern, code, re.DOTALL) is None for pattern in shape_patterns):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC010_CONFIRMATION_SHAPE",
                "privacy authorization and confirmation must remain profile-bound, closed, "
                "opaque, one-shot and package-confined",
            )
        )

    policy_tokens = (
        "privacyAuthorization.ownerProfile != effectiveProfile",
        "effectiveProfile.voiceRouteId().value()",
        "resolved instanceof OverrideValue.Disabled<?>",
        "route.id().equals(routeId)",
        "profileFailure == FailureReason.EFFECTIVE_ROUTE_DISABLED",
        "request != pendingConfirmation",
        "request.owner != this",
        "choice == ConfirmationDecision.CANCEL",
        "FailureClass.CANCELLED",
        "FailureReason.CONFIRMATION_REJECTED",
        "registry.isCurrent(request.lease)",
        "activeAttempt = new Attempt(",
        "policy == ConfirmationPolicy.REQUIRE_BEFORE_USE",
        "policy == ConfirmationPolicy.REQUIRE_ON_PRIVACY_DOWNGRADE",
        "!privacyAuthorization.allows(targetPrivacy)",
        "mode == AuthorizationMode.PREAUTHORIZED",
        "exposure(targetPrivacy) <= exposure(maximumPrivacy)",
    )
    if any(token not in no_comments for token in policy_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC010_CONFIRMATION_POLICY",
                "the exact EffectiveProfile must authorize the route; bounded preauthorization "
                "or one-time identity confirmation must never bypass sensitive, registry, "
                "cancellation, privacy or replay checks",
            )
        )

    if (
        "profile=<redacted>" not in no_comments
        or "identities=<redacted>" not in no_comments
        or re.search(
            r"toString\s*\([^)]*\)[^{]*\{[^}]*(?:effectiveProfile\s*\+|ownerProfile\s*\+|"
            r"route\.id\s*\(\)|\.descriptor\s*\(\))",
            no_comments,
        )
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC010_CONFIRMATION_REDACTION",
                "privacy authorization and confirmation diagnostics must not reveal profile, "
                "route, provider or registry identity",
            )
        )
    return tuple(findings)


def _inspect_rec010_scope_source(
    relative_path: str,
    no_comments: str,
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    if re.search(r"\bPrivacyAuthorization\b", no_comments) and relative_path not in {
        RECOGNITION_ROUTER_PATH,
        RECOGNITION_ROUTER_VOICE_CONTROLLER_PATH,
    }:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC010_CONFIRMATION_SCOPE",
                "privacy authorization and confirmation choices remain confined to the exact "
                "Router until the reviewed production bridge is implemented",
            )
        )
    if (
        re.search(r"\bConfirmationDecision\b", no_comments)
        and relative_path != RECOGNITION_ROUTER_PATH
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC010_CONFIRMATION_SCOPE",
                "confirmation decisions remain confined to the exact Router",
            )
        )
    if ".onConfirmation(" in no_comments and relative_path != RECOGNITION_ROUTER_PATH:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC010_CONFIRMATION_CALLER",
                "no production caller may resume a pending route before the reviewed bridge",
            )
        )
    return tuple(findings)


def _inspect_rec011_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in REC011_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    if relative_path == PROVIDER_CIRCUIT_BREAKER_PATH:
        forbidden = re.compile(
            r"\b(?:Serializable|Externalizable|Parcelable|Parcel|Bundle|Intent|Context|"
            r"InputConnection|EditorOperation|RecognitionProvider|ProviderConfig|SecretRef|"
            r"Executor|Thread|Future|File|Path|Socket|URL|HttpClient|SharedPreferences|"
            r"DataStore|RoomDatabase|AudioRecord|AudioCapture|RecognitionEvent|Throwable)\b"
            r"|(?<![\w$.])(?:android|androidx|java\.io|java\.net|java\.nio\.file|"
            r"java\.util\.concurrent|kotlinx\.serialization|com\.google\.gson|"
            r"com\.squareup\.moshi|com\.fasterxml\.jackson)\.",
        )
        shape_patterns = (
            r"(?m)^final\s+class\s+ProviderCircuitBreaker\s*\{",
            r"static\s+final\s+int\s+FAILURE_THRESHOLD\s*=\s*3",
            r"static\s+final\s+long\s+OPEN_INTERVAL_MILLIS\s*=\s*30_000L",
            r"static\s+final\s+int\s+MAX_PROVIDERS\s*=\s*ProviderRegistry\.MAX_PROVIDERS",
            r"private\s+final\s+MonotonicClock\s+clock",
            r"private\s+final\s+Map<ProviderDescriptor,\s*Entry>\s+entries\s*=\s*"
            r"new\s+IdentityHashMap<>\(\)",
            r"private\s+long\s+lastNowMillis\s*=\s*-1L",
            r"synchronized\s+AcquireResult\s+acquire\s*\(\s*ProviderDescriptor\s+descriptor\s*\)",
            r"synchronized\s+Disposition\s+onSuccess\s*\(\s*Permit\s+expected\s*\)",
            r"synchronized\s+Disposition\s+onFailure\s*\(\s*Permit\s+expected\s*,\s*"
            r"FailureClass\s+failureClass\s*\)",
            r"synchronized\s+Disposition\s+abandon\s*\(\s*Permit\s+expected\s*\)",
            r"static\s+final\s+class\s+Permit\s*\{[^}]*private\s+final\s+"
            r"ProviderCircuitBreaker\s+owner[^}]*private\s+final\s+Entry\s+entry[^}]*"
            r"private\s+final\s+long\s+epoch[^}]*private\s+final\s+boolean\s+halfOpen[^}]*"
            r"private\s+boolean\s+consumed",
            r"private\s+Permit\s*\(",
            r"private\s+enum\s+State\s*\{\s*CLOSED\s*,\s*OPEN\s*,\s*HALF_OPEN\s*\}",
            r"enum\s+RejectionReason\s*\{\s*OPEN\s*,\s*HALF_OPEN_BUSY\s*,\s*"
            r"CAPACITY_EXCEEDED\s*,\s*CLOCK_INVALID\s*,\s*GENERATION_EXHAUSTED\s*\}",
        )
        if (
            set(imports) != set(REC011_BREAKER_ALLOWED_IMPORTS)
            or forbidden.search(code)
            or any(re.search(pattern, code, re.DOTALL) is None for pattern in shape_patterns)
            or "public class ProviderCircuitBreaker" in code
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC011_BREAKER_SHAPE",
                    "breaker must remain a package-confined bounded process-local identity state machine",
                )
            )

        policy_tokens = (
            "entries.size() >= MAX_PROVIDERS",
            "now < entry.reopenAtMillis",
            "entry.state = State.HALF_OPEN",
            "entry.halfOpenPermit = permit",
            "permit.consumed = true",
            "permit.owner != this",
            "permit.epoch != entry.epoch",
            "entry.epoch == Long.MAX_VALUE",
            "now > Long.MAX_VALUE - OPEN_INTERVAL_MILLIS",
            "observed < lastNowMillis",
            "entry.consecutiveFailures < FAILURE_THRESHOLD",
            "FailureClass.NO_MATCH || failure == FailureClass.SPEECH_TIMEOUT",
            "case PERMISSION_DENIED",
            "CANCELLED",
            "TARGET_CHANGED -> false",
        )
        if any(token not in no_comments for token in policy_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC011_BREAKER_POLICY",
                    "health failures must open after three, gate one timed half-open probe, recover exactly, and ignore stale/non-health observations",
                )
            )
        if (
            "identities=<redacted>" not in no_comments
            or "identity=<redacted>" not in no_comments
            or re.search(
                r"toString\s*\([^)]*\)[^{]*\{[^}]*(?:descriptor\s*\+|provider\s*\+|"
                r"\.id\s*\(\)|\.displayName\s*\(\)|entry\s*\+|epoch\s*\+)",
                no_comments,
            )
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC011_BREAKER_REDACTION",
                    "breaker diagnostics must not expose provider, permit, epoch, clock error, or execution identity",
                )
            )
    else:
        shape_patterns = (
            r"private\s+final\s+ProviderCircuitBreaker\s+circuitBreaker",
            r"RecognitionRouter\s*\(\s*RecognitionRoute\s+route\s*,\s*"
            r"ProviderRegistry\s+registry\s*,\s*EffectiveProfile\s+effectiveProfile\s*,\s*"
            r"PrivacyAuthorization\s+privacyAuthorization\s*,\s*"
            r"ProviderCircuitBreaker\s+circuitBreaker\s*\)",
            r"static\s+final\s+class\s+Attempt\s*\{[^}]*private\s+final\s+"
            r"ProviderCircuitBreaker\.Permit\s+permit",
            r"CIRCUIT_OPEN\s*,\s*CIRCUIT_UNAVAILABLE",
        )
        binding_tokens = (
            "circuitBreaker.acquire(request.lease.descriptor())",
            "circuitBreaker.acquire(lease.descriptor())",
            "circuitBreaker.onFailure(attempt.permit, failure)",
            "circuitBreaker.onSuccess(attempt.permit)",
            "circuitBreaker.abandon(attempt.permit)",
            "circuitBreaker.abandon(permit)",
            "ProviderCircuitBreaker.RejectionReason.OPEN",
            "ProviderCircuitBreaker.RejectionReason.HALF_OPEN_BUSY",
            "FailureReason.CIRCUIT_OPEN",
            "FailureReason.CIRCUIT_UNAVAILABLE",
        )
        if any(re.search(pattern, code, re.DOTALL) is None for pattern in shape_patterns):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC011_ROUTER_SHAPE",
                    "router must retain one exact breaker and bind its opaque permit into every attempt",
                )
            )
        if (
            any(token not in no_comments for token in binding_tokens)
            or no_comments.count("circuitBreaker.acquire(") != 2
            or no_comments.count("circuitBreaker.onFailure(") != 1
            or no_comments.count("circuitBreaker.onSuccess(") != 1
            or no_comments.count("circuitBreaker.abandon(") != 4
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "REC011_ROUTER_BINDING",
                    "only exact current registry descriptors may acquire permits and every terminal, ABA, or generation path must resolve them",
                )
            )
    return tuple(findings)


def _inspect_rec011_scope_source(
    relative_path: str,
    no_comments: str,
) -> tuple[ArchitectureViolation, ...]:
    allowed = {
        PROVIDER_CIRCUIT_BREAKER_PATH,
        RECOGNITION_ROUTER_PATH,
        RECOGNITION_ROUTER_VOICE_CONTROLLER_PATH,
    }
    findings: list[ArchitectureViolation] = []
    if "ProviderCircuitBreaker" in no_comments and relative_path not in allowed:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC011_BREAKER_SCOPE",
                "breaker state and permits may be consumed only by the exact RecognitionRouter",
            )
        )
    if re.search(r"\bcircuitBreaker\.(?:acquire|onSuccess|onFailure|abandon)\s*\(", no_comments) \
            and relative_path != RECOGNITION_ROUTER_PATH:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "REC011_BREAKER_CALLER",
                "only the exact RecognitionRouter may acquire or resolve breaker permits",
            )
        )
    return tuple(findings)


def _inspect_rec012_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in REC012_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    if relative_path == SYSTEM_RECOGNITION_SUPPORT_PATH:
        exact_shapes = (
            r"public\s+record\s+Result\s*\(\s*Status\s+status\s*,\s*String\s+language\s*,"
            r"\s*boolean\s+canDownload\s*,\s*RecognitionRoute\.FailureClass\s+failureClass\s*\)",
            r"public\s+record\s+DownloadResult\s*\(\s*DownloadStatus\s+status\s*,"
            r"\s*RecognitionRoute\.FailureClass\s+failureClass\s*\)",
            r"interface\s+Scheduler\s*\{[^}]*void\s+post\s*\(\s*Runnable\s+action\s*\)"
            r"[^}]*void\s+postDelayed\s*\(\s*Runnable\s+action\s*,\s*long\s+delayMillis\s*\)"
            r"[^}]*void\s+removeCallbacks\s*\(\s*Runnable\s+action\s*\)",
            r"static\s+final\s+class\s+OneShotOperation\s+implements\s+Operation",
        )
        if (
            any(re.search(pattern, code, re.DOTALL) is None for pattern in exact_shapes)
            or re.search(r"\b(?:message|errorCode)\s*[),;]", no_comments)
            or ".getMessage(" in no_comments
        ):
            findings.append(ArchitectureViolation(
                relative_path,
                "REC012_RESULT_SHAPE",
                "support and download terminals must be closed, content-free and stably classified",
            ))
        policy_tokens = (
            "resultDelivered.compareAndSet(false, true)",
            "active.compareAndSet(true, false)",
            "lastProgress.compareAndSet(previous, bounded)",
            "bounded <= previous",
            "operation.reportProgress(callback, percent)",
            "language=<redacted>",
            "Failure classification does not match status",
        )
        if any(token not in no_comments for token in policy_tokens):
            findings.append(ArchitectureViolation(
                relative_path,
                "REC012_OPERATION_POLICY",
                "one-shot operations must reject late terminals and expose bounded monotonic progress",
            ))
    elif relative_path == SYSTEM_RECOGNITION_SUPPORT_API33_PATH:
        if (
            no_comments.count("SystemRecognitionIntentFactory.createCapabilityRequest(settings)") != 2
            or "PersonalizationSnapshot" in no_comments
            or ".getMessage(" in no_comments
            or "SystemRecognitionSupport.message(" in no_comments
            or "RecognitionFailureMapper.fromAndroidSystem(error, \"\")" not in no_comments
        ):
            findings.append(ArchitectureViolation(
                relative_path,
                "REC012_API33_BINDING",
                "API 33 support/download must use the least-data request and stable error mapping",
            ))
    elif relative_path == SYSTEM_RECOGNITION_SUPPORT_API34_PATH:
        required = (
            "SystemRecognitionSupport.reportDownloadProgress(",
            "RecognitionModelDownloadPolicy.shouldFallbackWithoutEvents(error)",
            "SystemRecognitionSupportApi33.dispatchUnobservedDownload(",
            "RecognitionFailureMapper.fromAndroidSystem(error, \"\")",
        )
        if any(token not in no_comments for token in required) or ".getMessage(" in no_comments:
            findings.append(ArchitectureViolation(
                relative_path,
                "REC012_API34_BINDING",
                "API 34 progress/terminal/fallback callbacks must share the generation-safe operation",
            ))
    elif relative_path == SYSTEM_MODEL_DOWNLOAD_COORDINATOR_PATH:
        required = (
            "private static final int MAX_SUBSCRIPTIONS = 16",
            "public interface Subscription extends AutoCloseable",
            "private static final class ProductionHolder",
            "private Request activeRequest",
            "if (generation == Long.MAX_VALUE)",
            "activeRequest != request",
            "bounded <= state.progress()",
            "state == expected",
            "if (!active || !owner.current(value)) return",
            "language=<redacted>",
        )
        forbidden = (
            "WeakHashMap",
            "addListener(",
            "removeListener(",
            ".getMessage(",
            "safeMessage(",
        )
        if any(token not in no_comments for token in required) \
                or any(token in no_comments for token in forbidden):
            findings.append(ArchitectureViolation(
                relative_path,
                "REC012_COORDINATOR_POLICY",
                "download state must use opaque request identity, bounded subscriptions and stale-delivery suppression",
            ))
    elif relative_path == RECOGNITION_LANGUAGE_SUPPORT_EVALUATOR_PATH:
        required = (
            "MAX_LANGUAGE_ENTRIES = 256",
            "MAX_LANGUAGE_UTF16_UNITS = 128",
            "MAX_LANGUAGE_CODE_POINTS = 64",
            "INVALID_RESPONSE",
            "size > MAX_LANGUAGE_ENTRIES",
            "catch (RuntimeException ignored)",
            "language=<redacted>",
        )
        if any(token not in no_comments for token in required):
            findings.append(ArchitectureViolation(
                relative_path,
                "REC012_EVALUATOR_BOUNDS",
                "OEM language collections and tags must remain bounded, validated and redacted",
            ))
    elif relative_path == SYSTEM_RECOGNITION_INTENT_FACTORY_PATH:
        capability = re.search(
            r"static\s+Intent\s+createCapabilityRequest\s*\(\s*AppSettings\s+settings\s*\)"
            r"\s*\{([\s\S]*?)\n\s*\}",
            no_comments,
        )
        body = capability.group(1) if capability else ""
        if (
            capability is None
            or "new ArrayList<>()" not in body
            or "PersonalizationSnapshot" in body
            or "biasingStrings(" in body
            or "true," in body
            or "false," not in body
        ):
            findings.append(ArchitectureViolation(
                relative_path,
                "REC012_CAPABILITY_REQUEST",
                "support and model download requests must omit prompts, learned terms and recognition-only extras",
            ))
    elif relative_path == MAIN_ACTIVITY_PATH:
        required = (
            "SystemModelDownloadCoordinator.Subscription systemModelSubscription",
            "supportOperation != request[0]",
            "SystemModelDownloadCoordinator.subscribe(systemModelListener)",
            "systemModelSubscription.close()",
        )
        if (
            any(token not in no_comments for token in required)
            or "supportGeneration" in no_comments
            or "SystemModelDownloadCoordinator.addListener(" in no_comments
            or "SystemModelDownloadCoordinator.removeListener(" in no_comments
        ):
            findings.append(ArchitectureViolation(
                relative_path,
                "REC012_ACTIVITY_LIFECYCLE",
                "Activity callbacks must bind by operation identity and close exact subscriptions",
            ))
    return tuple(findings)


def _inspect_rec012_scope_source(
    relative_path: str,
    no_comments: str,
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    if (
        "SystemModelDownloadCoordinator" in no_comments
        and relative_path not in {SYSTEM_MODEL_DOWNLOAD_COORDINATOR_PATH, MAIN_ACTIVITY_PATH}
    ):
        findings.append(ArchitectureViolation(
            relative_path,
            "REC012_COORDINATOR_SCOPE",
            "only MainActivity may observe or start the process-local system model coordinator",
        ))
    if (
        ".createCapabilityRequest(" in no_comments
        and relative_path not in {
            SYSTEM_RECOGNITION_INTENT_FACTORY_PATH,
            SYSTEM_RECOGNITION_SUPPORT_API33_PATH,
        }
    ):
        findings.append(ArchitectureViolation(
            relative_path,
            "REC012_CAPABILITY_CALLER",
            "only the exact API 33 bridge may build a system capability request",
        ))
    return tuple(findings)


def _inspect_cfg003_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in CFG003_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    forbidden_authority = re.compile(
        r"\b(?:Context|SharedPreferences|SQLiteDatabase|SQLiteOpenHelper|Cursor|ContentValues|"
        r"File|Path|InputStream|OutputStream|Reader|Writer|Socket|URL|HttpURLConnection|"
        r"ProviderConfig|SecretRef|RecognitionRoute|ClassLoader|Method|Constructor|Field)\b"
        r"|(?<![\w$.])(?:android|androidx|java\.io|java\.net|java\.nio\.file|"
        r"java\.lang\.reflect|kotlinx\.serialization|com\.google\.gson|"
        r"com\.squareup\.moshi|com\.fasterxml\.jackson)\.",
    )
    allowed_imports = CFG003_ALLOWED_IMPORTS[relative_path]
    if set(imports) != set(allowed_imports) or forbidden_authority.search(code):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG003_DOMAIN_DEPENDENCY",
                "override values/codecs may use only audited Java and exact org.json value types",
            )
        )

    if relative_path == OVERRIDE_VALUE_PATH:
        exact_interface = re.search(
            r"(?ms)\bpublic\s+sealed\s+interface\s+OverrideValue\s*<\s*T\s*>\s*"
            r"permits\s+OverrideValue\.Inherit\s*,\s*OverrideValue\.Disabled\s*,\s*"
            r"OverrideValue\.Value\s*\{",
            code,
        )
        exact_inherit = re.search(
            r"(?ms)\bfinal\s+class\s+Inherit\s*<\s*T\s*>\s+implements\s+"
            r"OverrideValue\s*<\s*T\s*>\s*\{",
            code,
        )
        exact_disabled = re.search(
            r"(?ms)\bfinal\s+class\s+Disabled\s*<\s*T\s*>\s+implements\s+"
            r"OverrideValue\s*<\s*T\s*>\s*\{",
            code,
        )
        exact_value = re.search(
            r"(?ms)\brecord\s+Value\s*<\s*T\s*>\s*\(\s*T\s+value\s*\)\s+"
            r"implements\s+OverrideValue\s*<\s*T\s*>\s*\{",
            code,
        )
        required_tokens = (
            "static <T> OverrideValue<T> inherit()",
            "static <T> OverrideValue<T> disabled()",
            "static <T> OverrideValue<T> value(T value)",
            "private static final Inherit<?> INSTANCE",
            "private static final Disabled<?> INSTANCE",
            "private Inherit()",
            "private Disabled()",
            "Objects.requireNonNull(value",
        )
        if (
            exact_interface is None
            or exact_inherit is None
            or exact_disabled is None
            or exact_value is None
            or any(token not in code for token in required_tokens)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG003_OVERRIDE_VALUE_SHAPE",
                    "expected exact singleton Inherit/Disabled and non-null Value sealed family",
                )
            )
        if (
            no_comments.count("OverrideValue.Value{value=<redacted>}") != 1
            or '" + value' in no_comments
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG003_OVERRIDE_REDACTION",
                    "explicit override values must remain redacted in diagnostics",
                )
            )
        return tuple(findings)

    exact_codec = re.search(
        r"(?ms)\bpublic\s+final\s+class\s+OverrideValueCodec\s*<\s*T\s*>\s*\{",
        code,
    )
    exact_scalar = re.search(
        r"(?ms)\bpublic\s+interface\s+ScalarCodec\s*<\s*T\s*>\s*\{\s*"
        r"String\s+encode\s*\(\s*T\s+value\s*\)\s*;\s*"
        r"T\s+decode\s*\(\s*String\s+encodedValue\s*\)\s*;\s*\}",
        code,
    )
    exact_row = re.search(
        r"(?ms)\bpublic\s+record\s+DbRow\s*\(\s*int\s+formatVersion\s*,\s*"
        r"String\s+state\s*,\s*boolean\s+valuePresent\s*,\s*"
        r"String\s+encodedValue\s*\)\s*\{",
        code,
    )
    exact_error = re.search(
        r"(?ms)\bpublic\s+static\s+final\s+class\s+FormatException\s+extends\s+"
        r"IllegalArgumentException\s*\{",
        code,
    )
    validation_tokens = (
        "FORMAT_VERSION = 1",
        "MAX_JSON_UTF16_UNITS = 32_768",
        "MAX_ENCODED_VALUE_UTF16_UNITS = 4_096",
        "new JSONTokener(safe)",
        "tokener.nextValue()",
        "tokener.nextClean() != 0",
        "array.length() != 3",
        "array.length() != 4",
        "version instanceof Integer",
        "state instanceof String",
        "present instanceof Boolean",
        "encoded instanceof String",
        "requireWellFormedBounded",
        "valuePresent != valueState",
        "scalarCodec.encode",
        "scalarCodec.decode",
        "catch (RuntimeException error)",
    )
    state_literals = (
        'STATE_INHERIT = "inherit"',
        'STATE_DISABLED = "disabled"',
        'STATE_VALUE = "value"',
    )
    if (
        exact_codec is None
        or exact_scalar is None
        or exact_row is None
        or exact_error is None
        or any(token not in code for token in validation_tokens)
        or any(token not in no_comments for token in state_literals)
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG003_CODEC_SHAPE",
                "expected exact versioned bounded JSON/DB override codec",
            )
        )
    if (
        no_comments.count("scalarCodec=<redacted>") != 1
        or no_comments.count("encodedValue=<redacted>") != 1
        or '" + encodedValue' in no_comments
        or '" + scalarCodec' in no_comments
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG003_CODEC_REDACTION",
                "codec, row, and errors must not expose scalar or adapter contents",
            )
        )
    return tuple(findings)


def _inspect_cfg004_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    if relative_path not in CFG004_REQUIRED_SOURCE_PATHS:
        return ()

    findings: list[ArchitectureViolation] = []
    forbidden = re.compile(
        r"\b(?:Context|SharedPreferences|SQLiteDatabase|SQLiteOpenHelper|Cursor|ContentValues|"
        r"File|Path|InputStream|OutputStream|Socket|URL|JSONObject|JSONArray|"
        r"ProviderConfig|SecretRef|RecognitionRoute|OverrideValueCodec|AppSettings|AppProfile|"
        r"Runnable|Callable|Executor|Thread|ClassLoader|Method|Constructor|Field)\b"
        r"|(?<![\w$.])(?:android|androidx|java\.io|java\.net|java\.nio\.file|"
        r"java\.lang\.reflect|com\.opentypeless\.android\.settings|org\.json|"
        r"kotlinx\.serialization|com\.google\.gson|com\.squareup\.moshi|"
        r"com\.fasterxml\.jackson)\.",
    )
    if set(imports) != set(CFG004_ALLOWED_IMPORTS[relative_path]) or forbidden.search(code):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG004_DOMAIN_DEPENDENCY",
                "versioned config partitions may use only exact pure value dependencies",
            )
        )

    shape_tokens: dict[str, tuple[str, ...]] = {
        PROCESSING_MODE_PATH: (
            "public enum ProcessingMode",
            "AUTO",
            "EXACT",
            "SMART",
            "TRANSLATE",
        ),
        GLOBAL_CONFIG_PATH: (
            "public record GlobalConfig(",
            "int formatVersion",
            "KeyboardConfig keyboard",
            "VoiceConfig voice",
            "ProcessingConfig processing",
            "PrivacyConfig privacy",
            "AutomationConfig automation",
            "public static final int FORMAT_VERSION = 1",
            "record KeyboardConfig(String layoutId)",
            "record VoiceConfig(OverrideValue<String> routeId)",
            "record ProcessingConfig(OverrideValue<ProcessingMode> mode)",
            "OverrideValue<Boolean> sendContext",
            "OverrideValue<Boolean> historyEnabled",
            "record AutomationConfig(OverrideValue<String> actionSetId)",
        ),
        APP_RULE_PATH: (
            "public record AppRule(",
            "String packageName",
            "OverrideValue<String> voiceRouteId",
            "OverrideValue<ProcessingMode> processingMode",
            "OverrideValue<Boolean> sendContext",
            "OverrideValue<Boolean> historyEnabled",
            "OverrideValue<String> actionSetId",
        ),
        RULE_OVERRIDES_PATH: (
            "public record RuleOverrides(",
            "OverrideValue<String> voiceRouteId",
            "OverrideValue<ProcessingMode> processingMode",
            "OverrideValue<Boolean> sendContext",
            "OverrideValue<Boolean> historyEnabled",
            "OverrideValue<String> actionSetId",
            "MAX_CONFIG_ID_CODE_POINTS = 128",
            "MAX_PACKAGE_NAME_CODE_POINTS = 255",
            "requireProcessingOverride",
            "requireBooleanOverride",
            "requireIdentifierOverride",
            "requirePackageName",
        ),
        FIELD_RULE_PATH: (
            "public record FieldRule(FieldMatcher matcher, RuleOverrides overrides)",
            "public record FieldMatcher(String packageName, FieldKind fieldKind)",
        ),
    }
    if any(token not in code for token in shape_tokens[relative_path]):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG004_MODEL_SHAPE",
                "expected exact Global/App/Field partition records and closed processing vocabulary",
            )
        )

    if relative_path == RULE_OVERRIDES_PATH:
        validation_tokens = (
            "safe.isEmpty() || safe.length() > MAX_CONFIG_ID_CODE_POINTS",
            "segments < 2 || segmentLength == 0",
            "safe.length() > MAX_PACKAGE_NAME_CODE_POINTS",
            "explicit.value() instanceof ProcessingMode",
            "explicit.value() instanceof Boolean",
            "payload instanceof String identifier",
        )
        if any(token not in code for token in validation_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG004_VALIDATION",
                    "IDs, package scope and erased generic payloads must fail closed",
                )
            )

    redaction_requirements = {
        GLOBAL_CONFIG_PATH: ("partitions=<redacted>", "layoutId=<redacted>"),
        APP_RULE_PATH: ("packageName=<redacted>", "overrides=<redacted>"),
        FIELD_RULE_PATH: ("packageName=<redacted>", "overrides=<redacted>"),
    }
    required_redaction = redaction_requirements.get(relative_path)
    if required_redaction is not None and any(
        token not in no_comments for token in required_redaction
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG004_REDACTION",
                "config identity and override diagnostics must remain redacted",
            )
        )
    return tuple(findings)


def _inspect_cfg005_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    if (
        relative_path != EFFECTIVE_PROFILE_RESOLVER_PATH
        and "EffectiveProfile.resolved(" in code
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG005_RESOLUTION_AUTHORITY",
                "only EffectiveProfileResolver may construct terminal resolved values",
            )
        )
    if relative_path not in CFG005_REQUIRED_SOURCE_PATHS:
        return tuple(findings)

    forbidden = re.compile(
        r"\b(?:Context|SharedPreferences|SQLiteDatabase|SQLiteOpenHelper|Cursor|ContentValues|"
        r"File|Path|InputStream|OutputStream|Socket|URL|JSONObject|JSONArray|Map|"
        r"ProviderConfig|SecretRef|RecognitionRoute|OverrideValueCodec|AppSettings|AppProfile|"
        r"Runnable|Callable|Executor|Thread|ClassLoader|Method|Constructor|Field|Comparator|Stream)\b"
        r"|(?<![\w$.])(?:android|androidx|java\.io|java\.net|java\.nio\.file|"
        r"java\.lang\.reflect|com\.opentypeless\.android\.settings|org\.json|"
        r"kotlinx\.serialization|com\.google\.gson|com\.squareup\.moshi|"
        r"com\.fasterxml\.jackson)\.",
    )
    if set(imports) != set(CFG005_ALLOWED_IMPORTS[relative_path]) or forbidden.search(code):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG005_DOMAIN_DEPENDENCY",
                "effective resolution may use only exact pure bounded config dependencies",
            )
        )

    if relative_path == EFFECTIVE_PROFILE_PATH:
        shape_tokens = (
            "public record EffectiveProfile(",
            "ResolvedValue<String> keyboardLayoutId",
            "ResolvedValue<String> voiceRouteId",
            "ResolvedValue<ProcessingMode> processingMode",
            "ResolvedValue<Boolean> sendContext",
            "ResolvedValue<Boolean> historyEnabled",
            "ResolvedValue<String> actionSetId",
            "public enum RuleSource",
            "HARD_SAFETY",
            "SESSION",
            "FIELD",
            "APPLICATION",
            "GLOBAL",
            "PROVIDER_DEFAULT",
            "public enum ResolutionExplanation",
            "HARD_SENSITIVE_FIELD",
            "REQUIRED_GLOBAL_VALUE",
            "EXPLICIT_VALUE",
            "EXPLICIT_DISABLED",
            "public static final class ResolvedValue<T>",
            "private ResolvedValue(",
            "value instanceof OverrideValue.Inherit<?>",
        )
        if any(token not in code for token in shape_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG005_RESULT_SHAPE",
                    "effective values must retain exact terminal state, source and explanation",
                )
            )
        if (
            "values=<redacted>" not in no_comments
            or "ResolvedValue{state=" not in no_comments
            or ' + value' in no_comments
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG005_REDACTION",
                    "effective result diagnostics must not expose resolved payloads",
                )
            )
    else:
        shape_tokens = (
            "public final class EffectiveProfileResolver",
            "public static final int MAX_APP_RULES = 256",
            "public static final int MAX_FIELD_RULES = 512",
            "private EffectiveProfileResolver()",
            "public static EffectiveProfile resolve(Request request)",
            "public record ProviderDefaults(",
            "public record Request(",
            "List<AppRule> appRules",
            "List<FieldRule> fieldRules",
            "RuleOverrides sessionOverrides",
            "String packageName",
            "FieldKind fieldKind",
            "public enum ResolutionFailure",
            "public static final class ResolutionException",
        )
        if any(token not in code for token in shape_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG005_RESOLVER_SHAPE",
                    "resolver input, limits and stable failures must keep the exact closed surface",
                )
            )

        precedence_tokens = (
            "selected(session, RuleSource.SESSION)",
            "selected(field, RuleSource.FIELD)",
            "selected(application, RuleSource.APPLICATION)",
            "selected(global, RuleSource.GLOBAL)",
            "selected(providerDefault, RuleSource.PROVIDER_DEFAULT)",
        )
        positions = [code.find(token) for token in precedence_tokens]
        hard_safety = (
            "safe.fieldKind() == FieldKind.SENSITIVE" in code
            and "hardValue(ProcessingMode.EXACT)" in code
            and code.count("hardDisabled()") >= 5
        )
        if any(position < 0 for position in positions) or positions != sorted(positions) or not hard_safety:
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG005_PRECEDENCE",
                    "hard safety and session-field-app-global-provider order must remain exact",
                )
            )

        bound_tokens = (
            "copy.size() == MAX_APP_RULES",
            "copy.size() == MAX_FIELD_RULES",
            "packages.add(rule.packageName())",
            "matchers.add(rule.matcher())",
            "List.copyOf(copy)",
            "PROVIDER_DEFAULT_INHERIT",
            "value instanceof OverrideValue.Inherit<?>",
        )
        if any(token not in code for token in bound_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG005_INPUT_BOUNDS",
                    "resolver rule collections must be bounded, duplicate-free and terminal",
                )
            )
        if (
            "ProviderDefaults{values=<redacted>}" not in no_comments
            or "Request{target=<redacted>" not in no_comments
            or "ResolutionException{failure=" not in no_comments
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG005_REDACTION",
                    "resolver inputs and failures must remain content-free in diagnostics",
                )
            )
    return tuple(findings)


def _inspect_cfg006_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    migration_reference = "LegacyAppSettingsMigration" in code
    if (
        migration_reference
        and relative_path not in {CFG006_MIGRATION_PATH, CFG006_REPOSITORY_PATH}
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG006_MIGRATION_AUTHORITY",
                "only the exact settings migration and repository may reference migration authority",
            )
        )
    if relative_path == CFG006_REPOSITORY_PATH:
        required = (
            "public GlobalConfig loadMigratedGlobalConfig()",
            "LegacyAppSettingsMigration.migrate(preferences, defaultBackend())",
            "LegacyAppSettingsMigration.writeProjection(editor, settings, revision)",
        )
        if (
            any(token not in code for token in required)
            or code.count("LegacyAppSettingsMigration.migrate(preferences, defaultBackend())") != 3
            or code.count("LegacyAppSettingsMigration.writeProjection(editor, settings, revision)") != 1
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG006_REPOSITORY_WIRING",
                    "repository must own the exact migrate/read and atomic save projection calls",
                )
            )
        return tuple(findings)
    if relative_path != CFG006_MIGRATION_PATH:
        return tuple(findings)

    exact_imports = {
        "android.annotation.SuppressLint",
        "android.content.SharedPreferences",
        "com.opentypeless.android.config.GlobalConfig",
        "com.opentypeless.android.config.OverrideValue",
        "com.opentypeless.android.config.OverrideValueCodec",
        "java.util.LinkedHashMap",
        "java.util.Map",
        "java.util.Objects",
        "java.util.Set",
    }
    if set(imports) != exact_imports:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG006_MIGRATION_DEPENDENCY",
                "migration may depend only on exact SharedPreferences, config codecs, and bounded values",
            )
        )

    shape_tokens = (
        "final class LegacyAppSettingsMigration",
        "MIGRATION_VERSION = 1",
        "public enum MigrationFailure",
        "MALFORMED_SOURCE",
        "UNKNOWN_TARGET_VERSION",
        "PARTIAL_TARGET",
        "COMMIT_FAILED",
        "READBACK_FAILED",
        "public static final class MigrationException",
        "interface Store",
        "record LegacyValues",
        "record Projection",
        "record ExistingTarget",
    )
    if (
        any(token not in code for token in shape_tokens)
        or 'SOURCE_VERSION = "0.2"' not in no_comments
        or 'LEGACY_LAYOUT_ID = "latin.base"' not in no_comments
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG006_MIGRATION_SHAPE",
                "expected exact versioned migration, stable failures, and bounded internal records",
            )
        )

    mapping_tokens = (
        'backend == RecognitionBackend.OPENAI_COMPATIBLE',
        'return "legacy.openai-compatible"',
        'backend == RecognitionBackend.LOCAL_OFFLINE',
        'return "legacy.local-offline"',
        'backend == RecognitionBackend.DASHSCOPE_STREAMING',
        'return "legacy.dashscope-streaming"',
        'backend == RecognitionBackend.SYSTEM_ON_DEVICE',
        'return "legacy.system-on-device"',
        'backend == RecognitionBackend.SYSTEM_DEFAULT',
        'return "legacy.system-default"',
        "mode == ProcessingMode.AUTO",
        "return com.opentypeless.android.config.ProcessingMode.AUTO",
        "mode == ProcessingMode.VERBATIM",
        "return com.opentypeless.android.config.ProcessingMode.EXACT",
        "mode == ProcessingMode.SMART",
        "return com.opentypeless.android.config.ProcessingMode.SMART",
        "mode == ProcessingMode.TRANSLATE",
        "return com.opentypeless.android.config.ProcessingMode.TRANSLATE",
        "OverrideValue.value(legacy.sendContext())",
        "OverrideValue.value(legacy.historyEnabled())",
        "OverrideValue.disabled()",
    )
    if any(token not in no_comments for token in mapping_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG006_EXACT_MAPPING",
                "legacy backend, mode, false booleans, keyboard, and disabled action mapping must remain exact",
            )
        )

    persistence_tokens = (
        "KEY_MIGRATION_VERSION",
        "KEY_SOURCE_VERSION",
        "KEY_SOURCE_REVISION",
        "KEY_BACKUP_RETAINED",
        "KEY_FORMAT_VERSION",
        "KEY_KEYBOARD_LAYOUT",
        "KEY_VOICE_ROUTE",
        "KEY_PROCESSING_MODE",
        "KEY_SEND_CONTEXT",
        "KEY_HISTORY_ENABLED",
        "KEY_ACTION_SET",
        "STRING_CODEC.toJson(route)",
        "PROCESSING_CODEC.toJson(mode)",
        "BOOLEAN_CODEC.toJson(sendContext)",
        "BOOLEAN_CODEC.toJson(historyEnabled)",
        "STRING_CODEC.toJson(actionSet)",
        "editor.commit()",
        "migrated.config().equals(projection.config())",
    )
    forbidden_persistence = (
        ".apply()",
        ".clear()",
        ".remove(",
        "getSharedPreferences(",
        "SQLite",
        "File",
        "SecretRef",
        "SecurePreferences",
        "ProviderConfig",
        "RecognitionRoute",
    )
    if (
        any(token not in no_comments for token in persistence_tokens)
        or any(token in no_comments for token in forbidden_persistence)
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG006_ATOMIC_PERSISTENCE",
                "projection must use one synchronous SharedPreferences commit with exact readback and no destructive or second-store I/O",
            )
        )

    secret_access = re.compile(
        r"\b(?:sttApiKey|streamingApiKey|llmApiKey|SecretRef|SecurePreferences)\b"
        r'|"(?:stt_api_key|streaming_api_key|llm_api_key)"'
    )
    if secret_access.search(no_comments):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG006_SECRET_BOUNDARY",
                "legacy secrets and secret storage must never enter the global-config projection",
            )
        )
    if (
        "MigrationException{failure=" not in no_comments
        or 'super(Objects.requireNonNull(failure, "failure").name())' not in no_comments
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG006_REDACTION",
                "migration failures must expose only a closed failure classification",
            )
        )
    return tuple(findings)


def _inspect_cfg007_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    migration_reference = "LegacyAppProfileMigration" in code
    allowed_paths = {CFG007_MIGRATION_PATH, CFG007_REPOSITORY_PATH}
    if migration_reference and relative_path not in allowed_paths:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG007_MIGRATION_AUTHORITY",
                "only the exact app-profile migration and repository may reference migration authority",
            )
        )
    if relative_path == CFG007_REPOSITORY_PATH:
        required = (
            "public List<AppRule> loadMigratedAppRules()",
            "LegacyAppProfileMigration.migrate(preferences)",
            "LegacyAppProfileMigration.readLegacyProfiles(preferences)",
            "LegacyAppProfileMigration.readProfilesForUpdate(preferences)",
            "LegacyAppProfileMigration.writeProfiles(preferences, profiles)",
            "synchronized (LegacyAppProfileMigration.class)",
        )
        if (
            any(token not in code for token in required)
            or code.count("LegacyAppProfileMigration.migrate(preferences)") != 1
            or code.count("LegacyAppProfileMigration.readLegacyProfiles(preferences)") != 1
            or code.count("LegacyAppProfileMigration.readProfilesForUpdate(preferences)") != 2
            or code.count("LegacyAppProfileMigration.writeProfiles(preferences, profiles)") != 1
            or ".apply()" in no_comments
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG007_REPOSITORY_WIRING",
                    "repository must own the exact load and same-file profile/rule transaction calls",
                )
            )
        return tuple(findings)
    if relative_path != CFG007_MIGRATION_PATH:
        return tuple(findings)

    exact_imports = {
        "android.annotation.SuppressLint",
        "android.content.SharedPreferences",
        "com.opentypeless.android.config.AppRule",
        "com.opentypeless.android.config.OverrideValue",
        "com.opentypeless.android.config.OverrideValueCodec",
        "org.json.JSONArray",
        "org.json.JSONException",
        "org.json.JSONObject",
        "org.json.JSONTokener",
        "java.util.ArrayList",
        "java.util.Comparator",
        "java.util.HashSet",
        "java.util.LinkedHashMap",
        "java.util.List",
        "java.util.Map",
        "java.util.Objects",
        "java.util.Set",
        "java.util.regex.Pattern",
    }
    if set(imports) != exact_imports:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG007_MIGRATION_DEPENDENCY",
                "profile migration may depend only on exact SharedPreferences, AppRule codecs, JSON, and bounded collections",
            )
        )

    shape_tokens = (
        "final class LegacyAppProfileMigration",
        "MIGRATION_VERSION = 1",
        "TARGET_FORMAT_VERSION = 1",
        'SOURCE_VERSION = "0.2"',
        'LEGACY_PROFILES = "profiles_v1"',
        "MAX_PROFILES = 100",
        "MAX_SOURCE_UTF16_UNITS = 1_000_000",
        "MAX_TARGET_UTF16_UNITS = 200_000",
        "enum MigrationFailure",
        "MALFORMED_SOURCE",
        "SOURCE_LIMIT_EXCEEDED",
        "DUPLICATE_SOURCE",
        "UNKNOWN_TARGET_VERSION",
        "PARTIAL_TARGET",
        "COMMIT_FAILED",
        "READBACK_FAILED",
        "static final class MigrationException",
        "interface Store",
        "record ExistingTarget",
    )
    if any(token not in no_comments for token in shape_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG007_MIGRATION_SHAPE",
                "expected exact versioned bounded profile migration and stable failure vocabulary",
            )
        )

    mapping_tokens = (
        "mode == ProcessingMode.AUTO",
        "return com.opentypeless.android.config.ProcessingMode.AUTO",
        "mode == ProcessingMode.VERBATIM",
        "return com.opentypeless.android.config.ProcessingMode.EXACT",
        "mode == ProcessingMode.SMART",
        "return com.opentypeless.android.config.ProcessingMode.SMART",
        "mode == ProcessingMode.TRANSLATE",
        "return com.opentypeless.android.config.ProcessingMode.TRANSLATE",
        "OverrideValue.inherit()",
        "OverrideValue.value(processingMode(profile.mode()))",
        "OverrideValue.value(profile.sendContext())",
        "rules.sort(Comparator.comparing(AppRule::packageName))",
        "packages.add(profile.packageName())",
        "array.length() > MAX_PROFILES",
    )
    if any(token not in no_comments for token in mapping_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG007_EXACT_MAPPING",
                "legacy mode, explicit false, inherit leaves, sorting, bounds, and duplicate rejection must remain exact",
            )
        )

    persistence_tokens = (
        "KEY_MIGRATION_VERSION",
        "KEY_SOURCE_VERSION",
        "KEY_FORMAT_VERSION",
        "KEY_BACKUP_RETAINED",
        "KEY_RULES",
        "values.put(LEGACY_PROFILES, encodedSource)",
        "editor.commit()",
        "readSource(after).equals(expectedSource)",
        "target.rules().equals(expectedRules)",
    )
    forbidden_persistence = (
        ".apply()",
        ".clear()",
        ".remove(",
        "getSharedPreferences(",
        "SQLite",
        "SecretRef",
        "SecurePreferences",
        "ProviderConfig",
        "RecognitionRoute",
        "java.io",
        "java.net",
    )
    if (
        any(token not in no_comments for token in persistence_tokens)
        or any(token in no_comments for token in forbidden_persistence)
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG007_ATOMIC_PERSISTENCE",
                "legacy profiles and complete rule shadow must share one synchronous commit and exact readback",
            )
        )

    encode_rules = re.search(
        r"private static String encodeRules\([\s\S]*?"
        r"private static List<AppRule> decodeRules",
        no_comments,
    )
    if (
        encode_rules is None
        or "targetLanguage" in encode_rules.group(0)
        or "customInstructions" in encode_rules.group(0)
        or "Secret" in encode_rules.group(0)
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG007_UNMAPPED_DATA_BOUNDARY",
                "unmapped language, instructions, and secrets must not enter AppRule target encoding",
            )
        )
    if (
        "MigrationException{failure=" not in no_comments
        or 'super(Objects.requireNonNull(failure, "failure").name())' not in no_comments
        or "ExistingTarget{rules=<redacted>}" not in no_comments
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG007_REDACTION",
                "profile migration failures and internal target diagnostics must remain content-free",
            )
        )
    return tuple(findings)


def _inspect_cfg008_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    store_reference = "SecretStore" in code
    allowed_paths = {
        CFG008_STORE_PATH,
        CFG006_REPOSITORY_PATH,
        SECRET_REF_PATH,
        PROVIDER_CONFIG_PATH,
    }
    if store_reference and relative_path not in allowed_paths:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG008_SECRET_STORE_AUTHORITY",
                "only the exact secret store, settings migration repository, and config identities may reference CFG-008 authority",
            )
        )
    bridge_tokens = (
        "commitLegacyPrepared(",
        "restoreLegacyPrepared(",
        "verifyLegacyPrepared(",
        "storedLegacyValue(",
    )
    if any(token in code for token in bridge_tokens) and relative_path not in {
        CFG008_STORE_PATH,
        CFG006_REPOSITORY_PATH,
    }:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG008_LEGACY_BRIDGE_CALLER",
                "only the exact SettingsRepository may use the ciphertext migration bridge",
            )
        )
    if relative_path == CFG006_REPOSITORY_PATH:
        required = (
            "private final SecretStore secretStore;",
            "secretStore = new SecretStore(this.context);",
            "public SecretStore.LegacyRefs loadMigratedSecretRefs()",
            "secretStore.migrateLegacy()",
            "secretStore.commitLegacyPrepared(preparedSecrets)",
            "secretStore.restoreLegacyPrepared(previous.secrets, previous.refs)",
            "secretStore.verifyLegacyPrepared(expectedSecrets, expectedRefs)",
            "secretStore.storedLegacyValue(",
        )
        if (
            any(token not in code for token in required)
            or code.count("secretStore.migrateLegacy()") != 3
            or code.count("secretStore.commitLegacyPrepared(preparedSecrets)") != 1
            or code.count(
                "secretStore.restoreLegacyPrepared(previous.secrets, previous.refs)"
            )
            != 1
            or code.count(
                "secretStore.verifyLegacyPrepared(expectedSecrets, expectedRefs)"
            )
            != 1
            or code.count("secretStore.storedLegacyValue(") != 3
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG008_REPOSITORY_WIRING",
                    "SettingsRepository must own the exact migration/read/save/recovery SecretRef bridge",
                )
            )
        return tuple(findings)
    if relative_path != CFG008_STORE_PATH:
        return tuple(findings)

    exact_imports = {
        "android.content.Context",
        "com.opentypeless.android.config.SecretRef",
        "java.util.Arrays",
        "java.util.EnumMap",
        "java.util.HashMap",
        "java.util.HashSet",
        "java.util.Map",
        "java.util.Objects",
        "java.util.Optional",
        "java.util.Set",
        "java.util.UUID",
    }
    if set(imports) != exact_imports:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG008_SECRET_STORE_DEPENDENCY",
                "SecretStore may depend only on Context, SecretRef, UUID, and bounded in-memory collections",
            )
        )

    shape_tokens = (
        "public final class SecretStore",
        "FORMAT_VERSION = 1",
        "MIGRATION_VERSION = 1",
        "MAX_SECRET_CODE_POINTS = 4_096",
        "MAX_ENTRIES = 64",
        "public SecretRef create(SecretRef.Kind kind, char[] secret)",
        "public SecretRef rotate(SecretRef current, char[] replacement)",
        "public boolean delete(SecretRef reference)",
        "public void use(SecretRef reference, SecretUse use)",
        "public LegacyRefs migrateLegacy()",
        "public LegacyRefs commitLegacyPrepared(Map<LegacySlot, String> preparedValues)",
        "public LegacyRefs restoreLegacyPrepared(",
        "public void verifyLegacyPrepared(",
        "public String storedLegacyValue(LegacySlot slot)",
        "interface Storage",
        "interface IdSource",
        "enum LegacySlot",
        "record LegacyRefs",
        "enum Failure",
        "static final class SecretStoreException",
    )
    if any(token not in no_comments for token in shape_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG008_SECRET_STORE_SHAPE",
                "expected exact bounded store, transient-use API, legacy bridge, and stable failures",
            )
        )

    lifecycle_tokens = (
        "newReference(kind, before.references())",
        "newReference(current.kind(), before.references())",
        "removals.add(entryKey(current))",
        "removals.add(kindKey(current))",
        "Arrays.fill(copy, '\\0')",
        "Arrays.fill(plaintext, '\\0')",
        "before.bindings().containsValue(current)",
        "before.bindings().containsValue(reference)",
        "Failure.LEGACY_AUTHORITY",
        "Failure.SECRET_NOT_FOUND",
    )
    if any(token not in no_comments for token in lifecycle_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG008_SECRET_LIFECYCLE",
                "create/rotate/delete/use must use exact refs, wipe buffers, and preserve legacy authority",
            )
        )

    migration = re.search(
        r"private LegacyRefs writeLegacyProjection\([\s\S]*?"
        r"private void commitAndVerify",
        no_comments,
    )
    migration_tokens = (
        "legacyStoredValues(before.raw())",
        "bindingKey(slot)",
        "entryKey(reference)",
        "kindKey(reference)",
        "mutationNeeded(before.raw(), values, removals)",
        "expectedCipher.equals(actualCipher)",
    )
    if (
        migration is None
        or any(token not in no_comments for token in migration_tokens)
        or ".decrypt(" in migration.group(0)
        or ".apply()" in no_comments
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG008_LEGACY_MIGRATION",
                "legacy ciphertext migration must retain source, avoid decrypt, commit synchronously, and read back exactly",
            )
        )

    forbidden = (
        "android.os.Bundle",
        "android.content.Intent",
        "Parcelable",
        "Serializable",
        "ObjectOutputStream",
        "android.util.Log",
        "java.net.",
        "java.io.",
        "getString(",
        "latest",
        "exportSecret",
    )
    if any(token in no_comments for token in forbidden):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG008_SECRET_EXFILTRATION",
                "SecretStore must not expose plaintext getters, Bundle/serialization/log/network/export surfaces",
            )
        )
    redaction_tokens = (
        'super("Secret store operation failed")',
        'return "SecretStoreException{failure=" + failure + "}"',
        'return "LegacyRefs{present=<redacted>}"',
        "throw failure(Failure.KEY_UNAVAILABLE)",
        "throw failure(Failure.USE_FAILED)",
    )
    if any(token not in no_comments for token in redaction_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG008_SECRET_REDACTION",
                "store failures and ref snapshots must remain content-free and cause-free",
            )
        )
    return tuple(findings)


def _inspect_cfg009_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    broad_visibility = (
        "Manifest.permission.QUERY_ALL_PACKAGES",
        "android.permission.QUERY_ALL_PACKAGES",
        "getInstalledApplications(",
        "getInstalledPackages(",
        "queryIntentActivities(",
    )
    if any(token in no_comments for token in broad_visibility):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG009_BROAD_PACKAGE_VISIBILITY",
                "App Picker must not request or emulate broad installed-package visibility",
            )
        )

    catalog_reference = "InstalledAppCatalog" in code
    if catalog_reference and relative_path not in {CFG009_CATALOG_PATH, CFG009_DIALOG_PATH}:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG009_CATALOG_AUTHORITY",
                "only the exact picker dialog may consume the current-user launchable-app catalog",
            )
        )
    model_reference = "AppPickerModel" in code
    if model_reference and relative_path not in {
        CFG009_MODEL_PATH,
        CFG009_CATALOG_PATH,
        CFG009_DIALOG_PATH,
        CFG009_ACTIVITY_PATH,
    }:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG009_INVENTORY_EXFILTRATION",
                "installed-app picker values may not leave the exact model/catalog/dialog/activity boundary",
            )
        )
    if "LauncherApps" in code and relative_path != CFG009_CATALOG_PATH:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG009_LAUNCHER_APPS_AUTHORITY",
                "LauncherApps capability is confined to InstalledAppCatalog",
            )
        )

    if relative_path == CFG009_MODEL_PATH:
        exact_imports = {
            "java.util.ArrayList",
            "java.util.Comparator",
            "java.util.LinkedHashMap",
            "java.util.List",
            "java.util.Locale",
            "java.util.Map",
            "java.util.Objects",
        }
        if set(imports) != exact_imports or any(
            token in no_comments
            for token in (
                "java.io.",
                "java.net.",
                "Serializable",
                "Parcelable",
                "SharedPreferences",
                "Context",
                "Intent",
            )
        ) or any(imported.startswith("android.") for imported in imports):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG009_MODEL_DEPENDENCY",
                    "AppPickerModel must remain an Android-free bounded value/search model",
                )
            )
        required = (
            "public final class AppPickerModel",
            "MAX_ENTRIES = 2_048",
            "MAX_LABEL_CODE_POINTS = 128",
            "MAX_QUERY_CODE_POINTS = 128",
            "public AppPickerModel(List<Entry> candidates)",
            "public List<Entry> entries()",
            "public List<Entry> search(String query)",
            "public record Entry(String label, String packageName)",
            "RuleOverrides.requirePackageName(packageName)",
            "return \"AppPickerModel{entries=<redacted>, count=\"",
            "return \"AppPickerEntry{label=<redacted>, packageName=<redacted>}\"",
        )
        if any(token not in no_comments for token in required):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG009_MODEL_SHAPE",
                    "AppPickerModel must keep exact bounded entry, search, validation, and redaction surfaces",
                )
            )
        return tuple(findings)

    if relative_path == CFG009_CATALOG_PATH:
        required = (
            "final class InstalledAppCatalog",
            "MAX_LAUNCHER_ACTIVITIES = 4_096",
            "getSystemService(LauncherApps.class)",
            "getActivityList(",
            "null, Process.myUserHandle()",
            "entries.size() > AppPickerModel.MAX_ENTRIES",
            "static final class Snapshot",
            "static final class CatalogUnavailableException extends RuntimeException",
            "apps=<redacted>",
        )
        forbidden = (
            "android.content.Intent",
            "SharedPreferences",
            "android.util.Log",
            "java.net.",
            "java.io.",
            "Serializable",
            "Parcelable",
            "getProfiles(",
            "registerCallback(",
        )
        if (
            any(token not in no_comments for token in required)
            or any(token in no_comments for token in forbidden)
            or no_comments.count("getActivityList(") != 1
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG009_CATALOG_SHAPE",
                    "catalog must use one bounded current-user LauncherApps query with redacted failures",
                )
            )
        return tuple(findings)

    if relative_path == CFG009_DIALOG_PATH:
        required = (
            "final class AppPickerDialog",
            "InstalledAppCatalog.load(activity)",
            "snapshot.model().search(query)",
            "new LruCache<>(32)",
            "listener.onAppSelected(",
            "listener.onAdvancedPackageRequested()",
            "setContentDescription(",
        )
        forbidden = (
            "SharedPreferences",
            "startActivity(",
            "android.util.Log",
            "java.net.",
            "java.io.",
        )
        if (
            any(token not in no_comments for token in required)
            or any(token in no_comments for token in forbidden)
            or no_comments.count("InstalledAppCatalog.load(activity)") != 1
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG009_DIALOG_SHAPE",
                    "dialog must provide bounded search, icons, accessibility, and explicit advanced fallback",
                )
            )
        return tuple(findings)

    if relative_path == CFG009_ACTIVITY_PATH:
        required = (
            "AppPickerDialog.show(this",
            "setAdvancedPackageVisible(false)",
            "packageName.setVisibility(View.GONE)",
            "R.string.app_picker_choose_installed",
            "R.string.app_picker_advanced_package",
        )
        if (
            any(token not in no_comments for token in required)
            or no_comments.count("AppPickerDialog.show(this") != 1
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG009_ACTIVITY_WIRING",
                    "AppProfileActivity must make picker selection primary and manual package input explicit",
                )
            )
    return tuple(findings)


def _inspect_cfg010_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    resolver_vocabulary = (
        "EffectiveProfile.ResolvedValue",
        "EffectiveProfile.RuleSource",
        "EffectiveProfile.ResolutionExplanation",
    )
    allowed_vocabulary_paths = {
        EFFECTIVE_PROFILE_PATH,
        EFFECTIVE_PROFILE_RESOLVER_PATH,
        CFG010_MODEL_PATH,
    }
    if relative_path not in allowed_vocabulary_paths and any(
        token in code for token in resolver_vocabulary
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG010_RESOLVER_VOCABULARY_SCOPE",
                "UI and other consumers must use RuleExplanationModel instead of rebuilding resolver rows",
            )
        )
    if relative_path != CFG010_MODEL_PATH:
        return tuple(findings)

    forbidden = re.compile(
        r"\b(?:EffectiveProfileResolver|Request|ProviderDefaults|GlobalConfig|AppRule|FieldRule|"
        r"Context|SharedPreferences|Bundle|Intent|Serializable|Parcelable|File|Path|Socket|URL|"
        r"Runnable|Callable|Executor|Thread|ClassLoader|Method|Constructor|Field)\b"
        r"|(?<![\w$.])(?:android|androidx|java\.io|java\.net|java\.nio\.file|"
        r"java\.lang\.reflect|com\.opentypeless\.android\.settings|org\.json|"
        r"kotlinx\.serialization|com\.google\.gson|com\.squareup\.moshi|"
        r"com\.fasterxml\.jackson)\.",
    )
    if set(imports) != set(CFG010_ALLOWED_IMPORTS) or forbidden.search(code):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG010_MODEL_DEPENDENCY",
                "rule explanation must remain a pure projection of EffectiveProfile only",
            )
        )

    shape_tokens = (
        "public final class RuleExplanationModel",
        "private RuleExplanationModel(List<Item> items)",
        "public static RuleExplanationModel from(EffectiveProfile profile)",
        "public List<Item> items()",
        "public Item item(Feature feature)",
        "public static List<RuleSource> precedence()",
        "public enum Feature",
        "KEYBOARD_LAYOUT",
        "VOICE_ROUTE",
        "PROCESSING_MODE",
        "SEND_CONTEXT",
        "HISTORY",
        "ACTION_SET",
        "public sealed interface DisplayValue permits DisplayValue.Disabled",
        "record Identifier(String value)",
        "record Processing(ProcessingMode value)",
        "record BooleanValue(boolean value)",
        "public record Item(",
        "RuleSource source",
        "ResolutionExplanation explanation",
    )
    if any(token not in code for token in shape_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG010_MODEL_SHAPE",
                "explanation model must retain exact six typed values, sources, and explanations",
            )
        )

    direct_projection = (
        "resolved.keyboardLayoutId()",
        "resolved.voiceRouteId()",
        "resolved.processingMode()",
        "resolved.sendContext()",
        "resolved.historyEnabled()",
        "resolved.actionSetId()",
    )
    precedence = (
        "RuleSource.HARD_SAFETY",
        "RuleSource.SESSION",
        "RuleSource.FIELD",
        "RuleSource.APPLICATION",
        "RuleSource.GLOBAL",
        "RuleSource.PROVIDER_DEFAULT",
    )
    positions = [code.find(token) for token in precedence]
    if (
        any(no_comments.count(token) != 1 for token in direct_projection)
        or any(position < 0 for position in positions)
        or positions != sorted(positions)
        or "EffectiveProfileResolver" in no_comments
        or "instanceof OverrideValue.Inherit" in no_comments
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG010_DIRECT_PROJECTION",
                "UI model must project each resolver terminal once and never recompute precedence",
            )
        )
    if (
        "RuleExplanationModel{values=<redacted>" not in no_comments
        or "value=<redacted>" not in no_comments
        or ' + value' in no_comments
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG010_REDACTION",
                "rule explanation diagnostics must not expose effective identifiers or values",
            )
        )
    return tuple(findings)


def _inspect_cfg011_source(
    relative_path: str,
    code: str,
    no_comments: str,
    imports: tuple[str, ...],
) -> tuple[ArchitectureViolation, ...]:
    findings: list[ArchitectureViolation] = []
    transaction_reference = "SettingsSaveTransaction" in code
    if transaction_reference and relative_path not in {
        CFG011_TRANSACTION_PATH,
        CFG006_REPOSITORY_PATH,
    }:
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG011_TRANSACTION_AUTHORITY",
                "only the exact settings repository may drive the durable configuration transaction",
            )
        )

    if relative_path == CFG011_TRANSACTION_PATH:
        shape_tokens = (
            "final class SettingsSaveTransaction",
            "interface Recovery",
            "interface Steps extends Recovery",
            "void restoreFromJournal()",
            "void verifyRestored()",
            "void clearJournal()",
            "void createJournal()",
            "void writeSecrets()",
            "void writeSettings()",
            "void verifyCommitted()",
            "static void execute(Steps steps)",
            "static void recover(boolean pending, Recovery steps)",
        )
        ordered_execute = re.search(
            r"steps\.createJournal\(\);[\s\S]*?"
            r"steps\.writeSecrets\(\);[\s\S]*?"
            r"steps\.writeSettings\(\);[\s\S]*?"
            r"steps\.verifyCommitted\(\);[\s\S]*?"
            r"steps\.clearJournal\(\);",
            no_comments,
        )
        ordered_recovery = re.search(
            r"steps\.restoreFromJournal\(\);[\s\S]*?"
            r"steps\.verifyRestored\(\);[\s\S]*?"
            r"steps\.clearJournal\(\);",
            no_comments,
        )
        if (
            imports
            or any(token not in no_comments for token in shape_tokens)
            or "public final class SettingsSaveTransaction" in no_comments
            or ordered_execute is None
            or ordered_recovery is None
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG011_TRANSACTION_SHAPE",
                    "transaction phases must remain package-confined, ordered, read back, and recoverable",
                )
            )
        rollback = re.search(
            r"private static void rollback\([\s\S]*?\n\s*\}",
            no_comments,
        )
        if (
            rollback is None
            or "steps.restoreFromJournal()" not in rollback.group(0)
            or "steps.verifyRestored()" not in rollback.group(0)
            or "failure.addSuppressed(rollbackFailure)" not in no_comments
            or ".apply()" in no_comments
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG011_ROLLBACK_PROTOCOL",
                    "failed saves must restore and verify before clearing the durable journal",
                )
            )
        return tuple(findings)

    if relative_path == CFG006_MIGRATION_PATH:
        validation = re.search(
            r"static GlobalConfig readValidated\(Store store,[\s\S]*?\n\s*\}",
            no_comments,
        )
        if (
            validation is None
            or "inspectTarget(snapshot)" not in validation.group(0)
            or "expected.config()" not in validation.group(0)
            or "commit(" in validation.group(0)
        ):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG011_CONFIG_READBACK",
                    "transaction readback must validate the exact projection without repairing it",
                )
            )
        return tuple(findings)

    if relative_path == CFG008_STORE_PATH:
        recovery_tokens = (
            "public LegacyRefs restoreLegacyPrepared(",
            "public void verifyLegacyPrepared(",
            "exactLegacyRefs(expectedRefs, exact)",
            "before.references().contains(restored)",
            "verifyLegacyState(exact, expected, Failure.READBACK_FAILED)",
        )
        if any(token not in no_comments for token in recovery_tokens):
            findings.append(
                ArchitectureViolation(
                    relative_path,
                    "CFG011_SECRET_IDENTITY_ROLLBACK",
                    "secret rollback must restore and read back the exact pre-transaction ref identities",
                )
            )
        return tuple(findings)

    if relative_path != CFG006_REPOSITORY_PATH:
        return tuple(findings)

    repository_tokens = (
        "SettingsSaveTransaction.execute(new SaveSteps(",
        "SettingsSaveTransaction.recover(pending, new RecoverySteps())",
        "private RecoveryState writeRecoveryJournal()",
        "RecoveryState readback = readRecoveryJournal()",
        "if (!before.sameState(readback))",
        "secretStore.restoreLegacyPrepared(previous.secrets, previous.refs)",
        "verifyState(previous.settings, previous.revision, previous.secrets, previous.refs)",
        "LegacyAppSettingsMigration.readValidated(preferences, defaultBackend())",
        "secretStore.verifyLegacyPrepared(expectedSecrets, expectedRefs)",
        "if (!transactionPreferences.getAll().isEmpty())",
        "private final class SaveSteps implements SettingsSaveTransaction.Steps",
        "private final class RecoverySteps implements SettingsSaveTransaction.Recovery",
        "private static final class RecoveryState",
        "public static final class SettingsTransactionException",
        "JOURNAL_KEYS = Set.of(",
        "TX_STT_REF",
        "TX_STREAMING_REF",
        "TX_LLM_REF",
    )
    forbidden = (".apply()", "android.util.Log", "printStackTrace", "getCause()")
    if (
        any(token not in no_comments for token in repository_tokens)
        or any(token in no_comments for token in forbidden)
        or code.count("SettingsSaveTransaction.execute(new SaveSteps(") != 1
        or code.count("SettingsSaveTransaction.recover(pending, new RecoverySteps())") != 1
    ):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG011_REPOSITORY_PROTOCOL",
                "repository must journal, write, exact-readback, rollback, recover and clear in one audited protocol",
            )
        )
    redaction_tokens = (
        'return "RecoveryState{settings=<redacted>, revision=<redacted>, secrets=<redacted>}"',
        'return "SettingsTransactionException{failure=" + failure + "}"',
    )
    if any(token not in no_comments for token in redaction_tokens):
        findings.append(
            ArchitectureViolation(
                relative_path,
                "CFG011_TRANSACTION_REDACTION",
                "journal state and transaction failures must remain content-free",
            )
        )
    return tuple(findings)


def inspect_source_tree(
    source_root: Path,
    *,
    enforce_legacy_inventory: bool = True,
) -> tuple[ArchitectureViolation, ...]:
    """Return deterministic boundary violations for Java/Kotlin production sources."""

    root = source_root.resolve()
    violations: list[ArchitectureViolation] = []
    observed_writers: dict[str, Counter[str]] = {}
    source_files = sorted((*source_root.rglob("*.java"), *source_root.rglob("*.kt")))
    for source_file in source_files:
        relative_path = source_file.relative_to(source_root).as_posix()
        resolved = source_file.resolve()
        if source_file.is_symlink() or not resolved.is_relative_to(root):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "SOURCE_CONTAINMENT",
                    "source symlinks and paths outside the production root are forbidden",
                )
            )
            continue

        source = source_file.read_text(encoding="utf-8")
        if source_file.suffix == ".java":
            try:
                source = _translate_java_unicode_escapes(source)
            except JavaUnicodeEscapeError as error:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "JAVA_UNICODE_ESCAPE_SYNTAX",
                        str(error),
                    )
                )
                continue
        elif _has_kotlin_escaped_identifier(source):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "KOTLIN_ESCAPED_IDENTIFIER",
                    "escaped identifiers are forbidden in production sources audited by this gate",
                )
            )
            continue
        code = _canonicalize_qualified_names(
            _normalize_code_identifiers(_strip_lexical(source, strings=True))
        )
        no_comments = _strip_lexical(source, strings=False)
        reflective_source = _collapse_adjacent_string_literals(no_comments)
        package_name = _package_name(code)
        imports = _imports(code)
        violations.extend(
            _inspect_cfg001_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_cfg002_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_rec001_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_rec002_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_str001_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_str002_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_str003_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_str005_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_str006_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_str010_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_rec003_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_rec004_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_rec005_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_rec006_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_rec006_scope_source(relative_path, no_comments)
        )
        violations.extend(
            _inspect_rec007_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_rec007_scope_source(relative_path, no_comments)
        )
        violations.extend(
            _inspect_rec008_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_rec008_scope_source(relative_path, no_comments)
        )
        violations.extend(
            _inspect_rec009_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_rec009_scope_source(relative_path, no_comments)
        )
        violations.extend(
            _inspect_rec010_source(relative_path, code, no_comments)
        )
        violations.extend(
            _inspect_rec010_scope_source(relative_path, no_comments)
        )
        violations.extend(
            _inspect_rec011_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_rec011_scope_source(relative_path, no_comments)
        )
        violations.extend(
            _inspect_rec012_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_rec012_scope_source(relative_path, no_comments)
        )
        violations.extend(
            _inspect_cfg003_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_cfg004_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_cfg005_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_cfg006_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_cfg007_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_cfg008_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_cfg009_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_cfg010_source(relative_path, code, no_comments, imports)
        )
        violations.extend(
            _inspect_cfg011_source(relative_path, code, no_comments, imports)
        )
        editor_domain = (
            package_name == EDITOR_DOMAIN_PACKAGE
            or (
                package_name.startswith(EDITOR_DOMAIN_PACKAGE + ".")
                and not (
                    package_name == EDITOR_HOST_PACKAGE
                    or package_name.startswith(EDITOR_HOST_PACKAGE + ".")
                )
            )
        )
        if any(f"{prefix}.@" in re.sub(r"\s+", "", code) for prefix in FORBIDDEN_TYPE_USE_ANNOTATION_PREFIXES):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "FORBIDDEN_CAPABILITY_TYPE_ANNOTATION",
                    "type-use annotations may not split an audited capability name",
                )
            )
        expected_package = Path(relative_path).parent.as_posix().replace("/", ".")
        if package_name != expected_package:
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "SOURCE_PACKAGE_MISMATCH",
                    f"expected package {expected_package or '<default>'}; declared {package_name or '<missing>'}",
                )
            )
        if relative_path == EDITOR_TRANSACTION_MANAGER_PATH and not re.search(
            r"(?m)^[ \t]*final[ \t]+class[ \t]+EditorTransactionManager\b", code
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "EDITOR_TRANSACTION_DECLARATION",
                    "EditorTransactionManager must be a package-private final top-level class",
                )
            )
        if relative_path == EDITOR_OPERATION_PATH:
            replace_shape = re.search(
                r"(?ms)^[ \t]*record\s+ReplaceSelection\s*\(\s*"
                r"TextRange\s+expectedSelection\s*,\s*"
                r"TextFingerprint\s+expectedTextHash\s*,\s*String\s+text\s*,\s*"
                r"OperationSource\s+source\s*\)\s*implements\s+EditorOperation\s*\{",
                code,
            )
            if replace_shape is None:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "REPLACE_SELECTION_MODEL_SHAPE",
                        "ReplaceSelection must retain exact range, SELECTED_TEXT fingerprint, "
                        "replacement and source fields",
                    )
                )
        if relative_path == EDITOR_OPERATION_KIND_PATH:
            kind = re.search(
                r"(?ms)\bpublic\s+enum\s+EditorOperationKind\s*\{(?P<body>.*)\}\s*$",
                code,
            )
            observed_kind_body = (
                re.sub(r"\s+", "", kind.group("body")) if kind is not None else ""
            )
            if observed_kind_body != ",".join(EDITOR_OPERATION_KIND_VALUES):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "EDITOR_TRANSACTION_AUDIT_KIND_SHAPE",
                        "EditorOperationKind must remain the exact seven-value content-free enum",
                    )
                )
        if relative_path == EDITOR_TRANSACTION_AUDIT_PATH:
            exact_audit_shape = re.search(
                r"(?ms)^[ \t]*public\s+record\s+EditorTransactionAudit\s*\(\s*"
                r"OperationSource\s+source\s*,\s*EditorOperationKind\s+operationKind\s*,\s*"
                r"EditorTransactionResult\s+result\s*\)\s*\{",
                code,
            )
            if exact_audit_shape is None:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "EDITOR_TRANSACTION_AUDIT_SHAPE",
                        "audit must remain an exact source/kind/result public record",
                    )
                )
            if EDITOR_TRANSACTION_AUDIT_FORBIDDEN_FIELD_PATTERN.search(code):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "EDITOR_TRANSACTION_AUDIT_CONTENT",
                        "audit may not retain text, editor identity, authorization or execution fields",
                    )
                )
        if relative_path in {
            EDITOR_SESSION_MANAGER_PATH,
            EDITOR_TRANSACTION_MANAGER_PATH,
        } and re.search(r"\bundoCommit\s*\(", code):
            undo_declarations = [
                name
                for name in METHOD_DECLARATION_NAME_PATTERN.findall(code)
                if name == "undoCommit"
            ]
            exact_undo_declarations = UNDO_FACADE_SIGNATURE_PATTERN.findall(code)
            if len(undo_declarations) != 1 or len(exact_undo_declarations) != 1:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "UNDO_FACADE_SHAPE",
                        "expected one package-confined undoCommit(String, "
                        "EditorSessionSnapshot, LiveAuthoritySupplier, UndoEvidenceReader) "
                        "returning EditorTransactionResult",
                    )
                )

        if relative_path in {
            EDITOR_SESSION_MANAGER_PATH,
            EDITOR_TRANSACTION_MANAGER_PATH,
        } and re.search(r"\brestoreRawCommit\s*\(", code):
            raw_declarations = [
                name
                for name in METHOD_DECLARATION_NAME_PATTERN.findall(code)
                if name == "restoreRawCommit"
            ]
            exact_raw_declarations = RAW_RESTORE_FACADE_SIGNATURE_PATTERN.findall(code)
            if len(raw_declarations) != 1 or len(exact_raw_declarations) != 1:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "RAW_RESTORE_FACADE_SHAPE",
                        "expected one package-confined restoreRawCommit(String, "
                        "EditorSessionSnapshot, LiveAuthoritySupplier, UndoEvidenceReader) "
                        "returning EditorTransactionResult",
                    )
                )

        if relative_path == EDITOR_TRANSACTION_MANAGER_PATH:
            audit_sink_shape = re.search(
                r"(?ms)^[ \t]*interface\s+AuditSink\s*\{\s*"
                r"void\s+record\s*\(\s*EditorTransactionAudit\s+audit\s*\)\s*;\s*\}",
                code,
            )
            audit_field_shape = re.search(
                r"(?m)^[ \t]*private\s+final\s+AuditSink\s+auditSink\s*;",
                code,
            )
            record_audit_shape = re.search(
                r"(?m)^[ \t]*private\s+void\s+recordAudit\s*\(\s*"
                r"EditorTransactionAudit\s+audit\s*\)\s*\{",
                code,
            )
            if (
                ("EditorTransactionAudit" in code or "AuditSink" in code)
                and (
                    audit_sink_shape is None
                    or audit_field_shape is None
                    or record_audit_shape is None
                    or len(re.findall(r"\bnew\s+EditorTransactionAudit\s*\(", code)) != 2
                    or len(re.findall(r"\bauditSink\s*\.\s*record\s*\(", code)) != 1
                    or "auditSink.record(audit)" not in code
                    or "catch (RuntimeException" not in code
                )
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "EDITOR_TRANSACTION_AUDIT_SINK",
                        "exact ETM must own one best-effort sink and the only two audit constructors",
                    )
                )
            for occurrence in re.finditer(
                r"\bnew\s+EditorTransactionResult\s*\.\s*RolledBack\s*\(", code
            ):
                declaration = _enclosing_method_declaration(code, occurrence.start())
                owner = declaration.group(1) if declaration is not None else None
                if owner != "restoreCommittedAndClassify":
                    violations.append(
                        ArchitectureViolation(
                            relative_path,
                            "EDT013_ROLLBACK_AUTHORITY",
                            "only restoreCommittedAndClassify may claim a fully verified rollback",
                        )
                    )
                    break

            replace_authority_declarations = re.finditer(
                r"(?ms)^[ \t]*(?:(?:public|protected|private|static|final|synchronized)\s+)*"
                r"[\w$.,?@<>\[\]]+\s+(?P<name>[A-Za-z_$][\w$]*)\s*\("
                r"[^)]*\bEditorOperation\.ReplaceLastCommit\b[^)]*\)",
                code,
            )
            for declaration in replace_authority_declarations:
                owner = declaration.group("name")
                if not any(token in owner.lower() for token in ("undo", "raw")):
                    violations.append(
                        ArchitectureViolation(
                            relative_path,
                            "UNDO_OPERATION_AUTHORITY",
                            "only exact Undo/Raw methods may accept or construct ReplaceLastCommit",
                        )
                    )
                    break

            if "EditorOperation.ReplaceSelection" in code:
                replace_policy_tokens = (
                    "replace.expectedSelection()",
                    "expected.selection()",
                    "validated.evidence().selection()",
                    "replace.expectedTextHash()",
                    "expected.selectedTextFingerprint()",
                    ".selectedText(validated.evidence().selected())",
                    ".securelyMatches(",
                    "expected.sensitive()",
                    "ReplaceProofState.INTENDED",
                    "ReplaceProofState.ORIGINAL",
                    "prepareReplaceTransition(",
                    "validateReplaceTransitionState(",
                )
                missing = [token for token in replace_policy_tokens if token not in code]
                if missing:
                    violations.append(
                        ArchitectureViolation(
                            relative_path,
                            "REPLACE_SELECTION_POLICY_PROOF",
                            "missing exact Replace range/hash/sensitive/outcome proof tokens: "
                            + ", ".join(missing),
                        )
                )

        keyboard_host_reference = re.search(r"\bKeyboardHost\b", code) is not None
        if (
            keyboard_host_reference
            and relative_path not in KEYBOARD_HOST_ALLOWED_SOURCE_CONSUMERS
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "KEYBOARD_HOST_SCOPE_TRANSFER",
                    "keyboard authority is confined to EditorSessionManager and the exact IME root",
                )
            )
        if relative_path == EDITOR_SESSION_MANAGER_PATH and keyboard_host_reference:
            keyboard_host_shape = re.search(
                r"(?ms)^[ \t]*public\s+interface\s+KeyboardHost\s*\{\s*"
                r"EditorInfo\s+currentEditorInfo\s*\(\s*\)\s*;\s*"
                r"InputConnection\s+currentInputConnection\s*\(\s*\)\s*;\s*\}",
                code,
            )
            exact_facades = all(pattern.search(code) for pattern in KEYBOARD_FACADE_PUBLIC_SIGNATURES)
            public_keyboard_methods = re.findall(
                r"(?m)^[ \t]*public\s+EditorTransactionResult\s+"
                r"(?:insertKeyboardText|deleteKeyboardBackward|performKeyboardEnter)\s*\(",
                code,
            )
            keyboard_tokens = (
                "OperationSource.LATIN",
                "expected.selectedTextFingerprint()",
                "new EditorOperation.DeleteBeforeCursor(1, OperationSource.LATIN)",
                "new EditorOperation.PerformEditorAction(action, OperationSource.LATIN)",
                'keyboardTextOperation(expected, "\\n")',
                "transactions.apply(expected, operation, authoritySupplier, evidenceReader)",
                "EditorSessionManager::readKeyboardEvidence",
            )
            if (
                keyboard_host_shape is None
                or not exact_facades
                or len(public_keyboard_methods) != 3
                or any(token not in source for token in keyboard_tokens)
                or re.search(r"\b(?:KeyEvent|CommitRecord|TransactionReceipt)\b", keyboard_host_shape.group(0) if keyboard_host_shape else "")
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "KEYBOARD_FACADE_SHAPE",
                        "keyboard façade must retain exact host/snapshot arguments and construct only LATIN operations",
                    )
                )
            exact_voice_facades = all(
                pattern.search(code) for pattern in VOICE_FACADE_PUBLIC_SIGNATURES
            )
            public_voice_methods = re.findall(
                r"(?m)^[ \t]*public\s+(?:EditorTransactionResult|TransactionReceipt)\s+"
                r"(?:setVoiceComposition|commitVoiceComposition|finishVoiceComposition|"
                r"commitVoiceText|undoVoiceCommit|restoreRawVoiceCommit)\s*\(",
                code,
            )
            voice_tokens = (
                "CompositionOwner.VOICE, revision, OperationSource.VOICE",
                "CompositionOwner.VOICE, expectedRevision, OperationSource.VOICE",
                "new CommitRecordRequest.Requested(rawTranscript)",
                "expected.selectedTextFingerprint()",
                "new EditorOperation.InsertText(text, OperationSource.VOICE)",
                "transactions.undoCommit(",
                "transactions.restoreRawCommit(",
                "EditorSessionManager::readKeyboardUndoEvidence",
            )
            if (
                not exact_voice_facades
                or len(public_voice_methods) != 6
                or any(token not in code for token in voice_tokens)
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "EDT017_VOICE_FACADE_SHAPE",
                        "voice facade must retain six exact host methods and construct only VOICE/exact-ID operations",
                    )
                )
        if (
            relative_path == "com/opentypeless/android/ime/OpenTypelessImeService.java"
            and keyboard_host_reference
        ):
            migrated_keyboard_tokens = (
                "implements EditorSessionManager.KeyboardHost",
                "editorSessionManager.insertKeyboardText(this, snapshot, text)",
                "editorSessionManager.deleteKeyboardBackward(this, snapshot)",
                "editorSessionManager.performKeyboardEnter(this, snapshot)",
                "captureKeyboardSnapshot()",
            )
            if any(token not in source for token in migrated_keyboard_tokens):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "KEYBOARD_FACADE_CALLER",
                        "exact IME key path must delegate all three operations to EditorSessionManager",
                    )
                )
            migrated_voice_tokens = (
                "VoiceEditorTransactionConfig.enabled(this)",
                "transactionWriter ? null : connection",
                "VoiceTransactionSession.acquire(",
                "if (target.transactionWriter)",
                "applyVoiceTransactionUpdate(target, update, text)",
                "commitVoiceTransactionResult(target, result)",
                "target.markVoiceTerminal()",
                "editorSessionManager.setVoiceComposition(",
                "editorSessionManager.commitVoiceComposition(",
                "editorSessionManager.commitVoiceText(",
                "editorSessionManager.undoVoiceCommit(",
                "editorSessionManager.restoreRawVoiceCommit(",
                "saveRecoverableDraft(target, recoverableText)",
            )
            if any(token not in code for token in migrated_voice_tokens):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "EDT017_VOICE_FACADE_CALLER",
                        "IME voice session must freeze one writer and route partial/final/Undo/Raw through the exact manager facades",
                    )
                )
            coordinator_tokens = (
                "private final CompositionCoordinator compositionCoordinator = new CompositionCoordinator()",
                "coordinator.observe(), new CompositionCoordinator.Acquisition.Voice()",
                "coordinator.voiceReady(compositionObservation)",
                "coordinator.voicePartial(",
                "coordinator.beginVoiceFinalizing(compositionObservation)",
                "coordinator.beginPreempt(",
                "coordinator.finishPreempt(",
                "coordinator.complete(compositionObservation)",
                "coordinator.cancel(compositionObservation)",
                "releaseVoiceCoordinatorAfterEditorLifecycle()",
            )
            if (
                any(token not in code for token in coordinator_tokens)
                or code.count("new CompositionCoordinator()") != 1
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "CMP004_VOICE_COORDINATOR_WIRING",
                        "transaction Voice must acquire one service-owned coordinator and release only through the bound session",
                    )
                )
            keyboard_preemption_tokens = (
                "private final CompositionConflictPolicy compositionConflictPolicy",
                "session.beginKeyboardPreemption(",
                "compositionConflictPolicy, finishingVoiceInput",
                "new CompositionCoordinator.Acquisition.Latin(1L)",
                "releaseVoiceForKeyboard(session, preemption)",
                "session.finishKeyboardRelease(preemption, resolution)",
                "session.finishKeyboardEvent(preemption, keyboardApplied)",
                "editorSessionManager.finishVoiceComposition(",
                "editorSessionManager.setVoiceComposition(",
                "captureKeyboardSnapshot()",
                "finishVoiceKeyboardPreemption(preemption,",
            )
            if (
                any(token not in code for token in keyboard_preemption_tokens)
                or code.count("CompositionConflictPolicy.defaults()") != 1
                or code.count("coordinator.beginPreempt(") != 2
                or code.count("coordinator.finishPreempt(") != 3
                or "routeLateResult = preemption.routeLateResult()" not in code
                or "activeTarget = null" not in code
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "CMP005_KEYBOARD_VOICE_PREEMPTION",
                        "keyboard interruption must use one frozen policy and the exact two-phase Voice-to-Latin bridge",
                    )
                )
            lifecycle_tokens = (
                "private final BroadcastReceiver screenOffReceiver",
                "private boolean voiceRestartBlockedByLifecycle",
                "this::cancelVoiceForLifecycle",
                "new IntentFilter(Intent.ACTION_SCREEN_OFF)",
                "Context.RECEIVER_NOT_EXPORTED",
                "screenOffReceiverRegistered = true",
                "screenOffReceiverRegistered = false",
                "cancelControllerForLifecycle(voiceController)",
                "voiceRestartBlockedByLifecycle = lifecycleRestartBlocked(",
                "&& !voiceRestartBlockedByLifecycle",
                "voiceController.cancel()",
                "target.markVoiceTerminal()",
                "shouldDispatchVoiceCallback(",
                "unregisterReceiver(screenOffReceiver)",
            )
            lifecycle_body = re.search(
                r"(?s)private\s+void\s+cancelVoiceForLifecycle\s*\(\s*\)\s*\{"
                r"(?P<body>.*?)\n\s*\}\n\n\s*static\s+BroadcastReceiver\s+createScreenOffReceiver",
                code,
            )
            lifecycle_callbacks = (
                "onStartInput",
                "onFinishInput",
                "onFinishInputView",
                "onWindowHidden",
                "onDestroy",
            )
            callbacks_use_exact_cancel = all(
                re.search(
                    rf"(?s)public\s+void\s+{callback}\s*\([^)]*\)\s*\{{"
                    r".{0,600}?cancelVoiceForLifecycle\s*\(\s*\)\s*;",
                    code,
                )
                for callback in lifecycle_callbacks
            )
            lifecycle_body_text = lifecycle_body.group("body") if lifecycle_body else ""
            if (
                any(token not in code for token in lifecycle_tokens)
                or lifecycle_body is None
                or not callbacks_use_exact_cancel
                or ".stop(" in lifecycle_body_text
                or "stopPipelinePreservingDraft" in lifecycle_body_text
                or "releaseVoiceCoordinatorAfterEditorLifecycle()" in lifecycle_body_text
                or code.count("lifecycleRestartBlocked(") != 4
                or "DetachedFinalizationGate" in code
                or "shouldDeferServiceShutdown" in code
                or "destroyFinalizationTimeout" in code
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "CMP006_VOICE_LIFECYCLE_CANCELLATION",
                        "input/view/window/destroy/screen-off must share one fail-closed cancel path and never await a background final",
                    )
                )
            if not re.search(
                r"(?s)private\s+void\s+applyVoiceTransactionUpdate\s*\(.{0,5000}?"
                r"editorSessionManager\.setVoiceComposition\(",
                code,
            ) or not re.search(
                r"(?s)private\s+void\s+commitVoiceTransactionResult\s*\(.{0,7000}?"
                r"editorSessionManager\.(?:commitVoiceText|setVoiceComposition)\(",
                code,
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "EDT017_WRITER_MUTUAL_EXCLUSION",
                        "transaction voice helpers must reach only the manager path and never legacy fallback",
                    )
                )
            forbidden_legacy_keyboard_methods = re.search(
                r"(?m)^[ \t]*private\s+void\s+(?:backspace|commitText|sendEnter)\s*\(",
                code,
            )
            if forbidden_legacy_keyboard_methods is not None or "sendKeyEvent" in code:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "KEYBOARD_LEGACY_WRITE_PATH",
                        "migrated key paths may not retain legacy direct writers or KeyEvent fallback",
                    )
                )

        if relative_path == VOICE_TRANSACTION_CONFIG_PATH:
            config_tokens = (
                'private static final String STORE = "voice_editor_transaction_runtime"',
                'private static final String VOICE_ENGINE_V2 = "voice_engine_v2"',
                'private static final String LEGACY_ENABLED = "enabled"',
                "preferences.contains(VOICE_ENGINE_V2)",
                "preferences.contains(LEGACY_ENABLED)",
                "preferences.getBoolean(VOICE_ENGINE_V2, true)",
                "preferences.getBoolean(LEGACY_ENABLED, true)",
                "public static synchronized boolean enabled(Context context)",
                "public static synchronized void setEnabled(Context context, boolean enabled)",
                ".putBoolean(VOICE_ENGINE_V2, enabled)",
                ".remove(LEGACY_ENABLED)",
                "migration.commit()",
            )
            if any(token not in source for token in config_tokens) or ".apply()" in source:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "EDT017_FEATURE_FLAG_SHAPE",
                        "voice writer rollback flag must be process-local, default-on and synchronously persisted",
                    )
                )
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC011_FEATURE_FLAG_SHAPE",
                        "voice_engine_v2 must migrate the legacy choice and persist rollback synchronously",
                    )
                )

        if (
            "CompositionCoordinator" in code
            and not relative_path.startswith("com/opentypeless/android/editor/")
            and relative_path
            != "com/opentypeless/android/ime/OpenTypelessImeService.java"
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "CMP004_COORDINATOR_SCOPE_TRANSFER",
                    "Voice coordinator observations may not move into provider, UI or adapter classes",
                )
            )

        if relative_path == VOICE_CONTROLLER_PATH:
            expected_methods = Counter(
                {
                    "start": 1,
                    "stop": 1,
                    "cancel": 1,
                    "state": 1,
                    "onState": 1,
                    "onRoute": 1,
                    "onReadyForSpeech": 1,
                    "onBeginningOfSpeech": 1,
                    "onTranscript": 1,
                    "onResult": 1,
                    "onError": 1,
                }
            )
            observed_methods = Counter(METHOD_DECLARATION_NAME_PATTERN.findall(code))
            required_tokens = (
                "public interface VoiceController",
                "enum State { IDLE, RECORDING, TRANSCRIBING, POLISHING }",
                "interface Events",
                "boolean start(DictationRequest request, Events events)",
                "void stop()",
                "void cancel()",
                "State state()",
            )
            forbidden_tokens = (
                "android.",
                "androidx.",
                "VoicePipeline",
                "InputConnection",
                "Activity",
                "Service",
                "Repository",
                "Store",
            )
            if (
                observed_methods != expected_methods
                or any(token not in code for token in required_tokens)
                or any(
                    imported.startswith("android.") or imported.startswith("androidx.")
                    for imported in imports
                )
                or any(token in code for token in forbidden_tokens[2:])
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC001_CONTROLLER_SHAPE",
                        "VoiceController must expose only exact start/stop/cancel/state/events data boundaries",
                    )
                )

        if relative_path == VOICE_PIPELINE_ADAPTER_PATH:
            adapter_tokens = (
                "public final class VoicePipelineAdapter implements VoiceController",
                "private final VoicePipeline pipeline",
                "pipeline.start(request, listenerFor(events))",
                "pipeline.stopRecording()",
                "pipeline.cancel()",
                "controllerState(pipeline.state())",
            )
            if (
                any(token not in code for token in adapter_tokens)
                or code.count("pipeline.start(") != 1
                or code.count("pipeline.stopRecording(") != 1
                or code.count("pipeline.cancel(") != 1
                or code.count("pipeline.state(") != 1
                or any(
                    imported.startswith("android.") or imported.startswith("androidx.")
                    for imported in imports
                )
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC001_PIPELINE_ADAPTER_SHAPE",
                        "legacy pipeline adapter must be the exact capability-free four-method bridge",
                    )
                )

        runtime_reference = re.search(r"\bVoicePipelineRuntime\b", code)
        if runtime_reference and relative_path not in {
            VOICE_PIPELINE_PATH,
            VOICE_PIPELINE_RUNTIME_PATH,
        }:
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "VOC007_RUNTIME_SCOPE_TRANSFER",
                    "the package-confined voice runtime may only be owned by its compatibility facade",
                )
            )

        if relative_path == VOICE_PIPELINE_PATH:
            required_tokens = (
                "public final class VoicePipeline",
                "private final VoicePipelineRuntime runtime",
                "runtime = new VoicePipelineRuntime(Objects.requireNonNull(context",
            )
            exact_calls = {
                "new VoicePipelineRuntime(": 1,
                "runtime.setRecordingContext(": 1,
                "runtime.start(": 1,
                "runtime.prewarmLocalOffline(": 1,
                "runtime.stopRecording(": 1,
                "runtime.cancel(": 1,
                "runtime.discard(": 1,
                "runtime.hasRecoverableAudio(": 1,
                "runtime.acknowledgeRecovery(": 1,
                "runtime.recover(": 1,
                "runtime.state(": 1,
                "runtime.shutdown(": 1,
                "VoicePipelineRuntime.shouldUseSpeechCoreV2(": 1,
                "VoicePipelineRuntime.shouldFallbackToLocal(": 1,
                "VoicePipelineRuntime.shouldRecoverVisiblePartial(": 1,
                "VoicePipelineRuntime.joinTranscriptSegments(": 1,
                "VoicePipelineRuntime.reconcileSystemFinal(": 1,
                "VoicePipelineRuntime.limitCodePoints(": 1,
                "VoicePipelineRuntime.parseSpeechCoreRecoveryId(": 1,
                "VoicePipelineRuntime.clearCancelledRun(": 1,
                "VoicePipelineRuntime.aiCandidateDisposition(": 1,
            }
            forbidden_tokens = (
                "AudioCapture",
                "OpenAiCompatibleClient",
                "TextProcessingPipeline",
                "Executor",
                "Future",
                "RecognitionDiagnosticsStore",
                "VoiceRecoveryJournal",
                "VoiceDraftJournal",
                "SystemSpeechRecognizer",
                "LocalOfflineRecognitionClient",
                "ParaformerStreamingRecognizer",
                "InputConnection",
                "EditorOperation",
                "Repository",
                "Store",
            )
            if (
                len(code.splitlines()) > 220
                or any(token not in code for token in required_tokens)
                or any(code.count(token) != count for token, count in exact_calls.items())
                or any(token in code for token in forbidden_tokens)
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC007_FACADE_SHAPE",
                        "VoicePipeline must remain a bounded compatibility-only facade over one runtime",
                    )
                )

        if relative_path == VOICE_PIPELINE_RUNTIME_PATH:
            if (
                re.search(r"(?m)^[ \t]*final[ \t]+class[ \t]+VoicePipelineRuntime\b", code)
                is None
                or re.search(
                    r"(?m)^[ \t]*(?:public|protected)[ \t]+(?:final[ \t]+)?class[ \t]+VoicePipelineRuntime\b",
                    code,
                )
                is not None
                or "public enum State" in code
                or "public interface Listener" in code
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC007_RUNTIME_SHAPE",
                        "voice execution runtime must remain package-confined and reuse facade compatibility types",
                    )
                )

        consumer_tokens = VOICE_CONTROLLER_CONSUMERS.get(relative_path)
        if consumer_tokens is not None and (
            "VoiceController" in code
            or "VoicePipelineAdapter" in code
            or VOICE_PIPELINE_CORE_CALL_PATTERN.search(code)
        ):
            if (
                any(token not in code for token in consumer_tokens)
                or VOICE_PIPELINE_CORE_CALL_PATTERN.search(code)
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC001_CONTROLLER_CALLER",
                        "production voice start/stop/cancel/state calls must use VoiceController",
                    )
                )
        elif (
            relative_path not in {VOICE_PIPELINE_PATH, VOICE_PIPELINE_ADAPTER_PATH}
            and VOICE_PIPELINE_CORE_CALL_PATTERN.search(code)
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "VOC001_PIPELINE_BYPASS",
                    "only VoicePipelineAdapter may call the legacy core session surface",
                )
            )

        raw_audio_reference = re.search(r"\b(?:AudioRecorder|RecordingSession)\b", code)
        if (
            raw_audio_reference
            and relative_path not in RAW_AUDIO_CAPTURE_ALLOWED_SOURCE_CONSUMERS
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "VOC002_RAW_CAPTURE_BYPASS",
                    "AudioRecorder and RecordingSession are confined behind AndroidAudioCapture",
                )
            )

        audio_capture_reference = re.search(r"\b(?:AudioCapture|AndroidAudioCapture)\b", code)
        if (
            audio_capture_reference
            and relative_path not in AUDIO_CAPTURE_ALLOWED_SOURCE_CONSUMERS
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "VOC002_CAPTURE_SCOPE_TRANSFER",
                    "AudioCapture is confined to the exact voice capture runtime",
                )
            )

        if relative_path == AUDIO_CAPTURE_PATH:
            expected_methods = Counter(
                {
                    "userControlledEndpointing": 1,
                    "onReady": 1,
                    "onBeginningOfSpeech": 1,
                    "onAudio": 1,
                    "onPcm16Frame": 1,
                    "setAttributionContext": 1,
                    "createSession": 1,
                    "record": 1,
                    "stream": 1,
                    "stop": 1,
                    "cancel": 1,
                }
            )
            observed_methods = Counter(METHOD_DECLARATION_NAME_PATTERN.findall(code))
            required_tokens = (
                "public interface AudioCapture",
                "int SAMPLE_RATE = 16_000",
                "interface Session",
                "boolean userControlledEndpointing()",
                "interface CaptureListener",
                "interface FrameConsumer",
                "Session createSession(boolean userControlledEndpointing)",
                "RecordedAudio record(Session session, int maximumSeconds, CaptureListener listener)",
                "void stop(Session session)",
                "void cancel(Session session)",
            )
            forbidden_tokens = (
                "AudioRecorder",
                "RecordingSession",
                "InputConnection",
                "EditorOperation",
                "OkHttp",
                "Repository",
                "Store",
                "java.net.",
                "java.io.",
            )
            if (
                observed_methods != expected_methods
                or any(token not in code for token in required_tokens)
                or any(token in code for token in forbidden_tokens)
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC002_CAPTURE_SHAPE",
                        "AudioCapture must remain the exact bounded microphone/session/frame boundary",
                    )
                )

        if relative_path == ANDROID_AUDIO_CAPTURE_PATH:
            required_tokens = (
                "public final class AndroidAudioCapture implements AudioCapture",
                "private final AudioRecorder recorder",
                "new AudioRecorder()",
                "new RecordingSession(userControlledEndpointing)",
                "private static final class RecorderSession implements Session",
                "private final AndroidAudioCapture owner",
                "private final RecordingSession delegate",
            )
            exact_calls = {
                "new AudioRecorder(": 1,
                "new RecordingSession(": 1,
                "recorder.setAttributionContext(": 1,
                "recorder.record(": 1,
                "recorder.stream(": 1,
                "recorder.stop(": 1,
                "recorder.cancel(": 1,
            }
            forbidden_tokens = (
                "InputConnection",
                "EditorOperation",
                "OkHttp",
                "Repository",
                "Store",
            )
            if (
                any(token not in code for token in required_tokens)
                or any(code.count(token) != count for token, count in exact_calls.items())
                or any(token in code for token in forbidden_tokens)
                or 'return "AudioCapture.Session";' not in source
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC002_CAPTURE_ADAPTER_SHAPE",
                        "AndroidAudioCapture must be the sole one-to-one raw recorder/session adapter",
                    )
                )

        if relative_path == AUDIO_RECORDER_PATH:
            package_private = re.search(
                r"(?m)^[ \t]*final[ \t]+class[ \t]+AudioRecorder\b", code
            )
            if (
                package_private is None
                or "static int boundedMaximumSeconds(int maximumSeconds)" not in code
                or code.count("boundedMaximumSeconds(maximumSeconds)") != 2
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC002_RAW_CAPTURE_SHAPE",
                        "raw recorder must remain package-confined and apply one shared duration bound to both modes",
                    )
                )

        if relative_path == RECORDING_SESSION_PATH and re.search(
            r"(?m)^[ \t]*public[ \t]+final[ \t]+class[ \t]+RecordingSession\b", code
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "VOC002_RAW_CAPTURE_SHAPE",
                    "mutable recording session must remain package-confined behind AudioCapture.Session",
                )
            )

        if relative_path == VOICE_PIPELINE_RUNTIME_PATH and audio_capture_reference:
            required_tokens = (
                "private final AudioCapture audioCapture = new AndroidAudioCapture()",
                "volatile AudioCapture.Session captureSession",
            )
            exact_calls = {
                "new AndroidAudioCapture(": 1,
                "audioCapture.setAttributionContext(": 2,
                "audioCapture.createSession(": 2,
                "audioCapture.record(": 1,
                "audioCapture.stop(": 1,
                "audioCapture.cancel(": 1,
            }
            if (
                any(token not in code for token in required_tokens)
                or any(code.count(token) != count for token, count in exact_calls.items())
                or raw_audio_reference
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC002_PIPELINE_BINDING",
                        "VoicePipelineRuntime must own one AudioCapture and route session lifecycle through it",
                    )
                )

        if relative_path == LOCAL_SPEECH_CORE_V2_SESSION_PATH and audio_capture_reference:
            required_tokens = (
                "private final AudioCapture audioCapture",
                "private volatile AudioCapture.Session captureSession",
                "void setCaptureSession(AudioCapture.Session session)",
                "audioCapture.stream(",
            )
            if (
                any(token not in code for token in required_tokens)
                or code.count("audioCapture.stream(") != 1
                or raw_audio_reference
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC002_LOCAL_STREAM_BINDING",
                        "local v2 must consume one opaque session through AudioCapture.stream",
                    )
                )

        if relative_path == STREAMING_RECOGNITION_ENGINE_PATH:
            required_tokens = (
                "AudioCapture audioCapture",
                "AudioCapture.Session captureSession",
                "AudioCapture.CaptureListener captureListener",
            )
            if any(token not in code for token in required_tokens) or raw_audio_reference:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC002_STREAMING_ENGINE_SHAPE",
                        "streaming recognition must accept only the AudioCapture boundary",
                    )
                )

        if relative_path == PARAFORMER_STREAMING_RECOGNIZER_PATH:
            if code.count("audioCapture.stream(") != 1 or raw_audio_reference:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC002_STREAMING_ENGINE_BINDING",
                        "Paraformer must receive PCM through one bounded AudioCapture stream",
                    )
                )

        text_processing_reference = re.search(
            r"\b(?:TextProcessingPipeline|StagedTextProcessingPipeline|"
            r"DeterministicPersonalizationStage|OpenAiOptionalLlmStage|"
            r"TranscriptIntegrityGuardStage)\b",
            code,
        )
        if (
            text_processing_reference
            and relative_path not in TEXT_PROCESSING_ALLOWED_SOURCE_CONSUMERS
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "VOC003_PIPELINE_SCOPE_TRANSFER",
                    "text-processing stages and content-bearing requests are confined to VoicePipeline",
                )
            )

        if relative_path == TEXT_PROCESSING_PIPELINE_PATH:
            expected_methods = Counter(
                {
                    "LlmRequest": 1,
                    "IntegrityRequest": 1,
                    "toString": 2,
                    "apply": 4,
                    "deterministic": 1,
                    "command": 1,
                    "optionalLlm": 1,
                    "integrity": 1,
                }
            )
            observed_methods = Counter(METHOD_DECLARATION_NAME_PATTERN.findall(code))
            required_tokens = (
                "public interface TextProcessingPipeline",
                "enum DeterministicFailurePolicy",
                "PRESERVE_INPUT",
                "PROPAGATE",
                "interface DeterministicStage",
                "interface CommandStage",
                "interface OptionalLlmStage",
                "interface IntegrityGuardStage",
                "ProcessingResult deterministic(",
                "Optional<String> command(String deterministicText)",
                "String optionalLlm(LlmRequest request, BooleanSupplier cancelled) throws Exception",
                "IntegrityResult integrity(IntegrityRequest request)",
            )
            llm_shape = re.search(
                r"(?s)record\s+LlmRequest\s*\(\s*ProcessingMode\s+mode\s*,\s*"
                r"InputContext\s+inputContext\s*,\s*PersonalizationSnapshot\s+personalization\s*,\s*"
                r"AppSettings\s+settings\s*,\s*String\s+deterministicText\s*\)",
                code,
            )
            integrity_shape = re.search(
                r"(?s)record\s+IntegrityRequest\s*\(\s*String\s+sourceText\s*,\s*"
                r"String\s+candidateText\s*,\s*ProcessingMode\s+mode\s*,\s*"
                r"PersonalizationSnapshot\s+personalization\s*\)",
                code,
            )
            forbidden_tokens = (
                "InputConnection",
                "ProjectionConnection",
                "Context",
                "Activity",
                "Service",
                "Repository",
                "Store",
                "SQLiteDatabase",
                "EditorOperation",
                "Thread",
                "Executor",
            )
            if (
                observed_methods != expected_methods
                or any(token not in code for token in required_tokens)
                or llm_shape is None
                or integrity_shape is None
                or any(
                    re.search(rf"\b{re.escape(token)}\b", code)
                    for token in forbidden_tokens
                )
                or any(
                    imported.startswith("android.") or imported.startswith("androidx.")
                    for imported in imports
                )
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC003_PIPELINE_SHAPE",
                        "TextProcessingPipeline must retain the exact capability-free four-stage surface",
                    )
                )
            if (
                'return "LlmRequest{<redacted>}";' not in source
                or 'return "IntegrityRequest{<redacted>}";' not in source
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC003_REQUEST_REDACTION",
                        "content-bearing stage requests must expose only fixed redacted toString markers",
                    )
                )

        if relative_path == STAGED_TEXT_PROCESSING_PIPELINE_PATH:
            expected_methods = Counter(
                {
                    "StagedTextProcessingPipeline": 1,
                    "deterministic": 1,
                    "command": 1,
                    "optionalLlm": 1,
                    "integrity": 1,
                }
            )
            expected_fields = (
                "private final DeterministicStage deterministicStage",
                "private final CommandStage commandStage",
                "private final OptionalLlmStage optionalLlmStage",
                "private final IntegrityGuardStage integrityGuardStage",
            )
            observed_methods = Counter(METHOD_DECLARATION_NAME_PATTERN.findall(code))
            if (
                "final class StagedTextProcessingPipeline implements TextProcessingPipeline"
                not in code
                or observed_methods != expected_methods
                or any(code.count(field) != 1 for field in expected_fields)
                or code.count("deterministicStage.apply(") != 1
                or code.count("commandStage.apply(") != 1
                or code.count("optionalLlmStage.apply(") != 1
                or code.count("integrityGuardStage.apply(") != 1
                or "InputConnection" in code
                or "EditorOperation" in code
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC003_STAGED_PIPELINE_SHAPE",
                        "staged dispatcher must own and delegate exactly one of each four stage",
                    )
                )

        if relative_path == DETERMINISTIC_PERSONALIZATION_STAGE_PATH:
            observed_methods = Counter(
                STRICT_METHOD_DECLARATION_START_PATTERN.findall(code)
            )
            required_tokens = (
                "final class DeterministicPersonalizationStage",
                "implements TextProcessingPipeline.DeterministicStage",
                "private static final int MAX_TRANSCRIPT_CODE_POINTS = 20_000",
                "PersonalizedTextProcessor.apply(exactInput, exactPersonalization)",
                "exactPolicy == TextProcessingPipeline.DeterministicFailurePolicy.PROPAGATE",
                "new ProcessingResult(boundedInput, List.of(), List.of())",
            )
            forbidden_tokens = (
                "public final class DeterministicPersonalizationStage",
                "InputConnection",
                "EditorOperation",
                "Context",
                "Activity",
                "Service",
                "Repository",
                "Store",
                "Thread",
                "Executor",
                "Throwable",
                "Serializable",
                "Parcelable",
            )
            if (
                observed_methods != Counter({"apply": 1})
                or any(token not in code for token in required_tokens)
                or any(token in code for token in forbidden_tokens)
                or code.count("PersonalizedTextProcessor.apply(") != 1
                or code.count("catch (IllegalArgumentException error)") != 1
                or any(
                    imported.startswith("android.") or imported.startswith("androidx.")
                    for imported in imports
                )
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC005_PERSONALIZATION_STAGE_SHAPE",
                        "deterministic personalization must remain one package-confined capability-free stage",
                    )
                )

        if relative_path == OPENAI_OPTIONAL_LLM_STAGE_PATH:
            observed_methods = Counter(
                STRICT_METHOD_DECLARATION_START_PATTERN.findall(code)
            )
            required_tokens = (
                "final class OpenAiOptionalLlmStage",
                "implements TextProcessingPipeline.OptionalLlmStage",
                "private final OpenAiCompatibleClient client",
                "OpenAiOptionalLlmStage(OpenAiCompatibleClient client)",
                "this.client = Objects.requireNonNull(client",
                "PromptComposer.systemPrompt(",
                "PromptComposer.userPrompt(",
                "client.complete(",
            )
            forbidden_tokens = (
                "public final class OpenAiOptionalLlmStage",
                "InputConnection",
                "EditorOperation",
                "Activity",
                "Service",
                "Repository",
                "Store",
                "Thread",
                "Executor",
                "Serializable",
                "Parcelable",
                "catch (",
                "Log.",
                "System.out",
                "System.err",
            )
            if (
                observed_methods != Counter({"apply": 1})
                or any(token not in code for token in required_tokens)
                or any(token in code for token in forbidden_tokens)
                or code.count("PromptComposer.systemPrompt(") != 1
                or code.count("PromptComposer.userPrompt(") != 1
                or code.count("client.complete(") != 1
                or any(
                    imported.startswith("android.") or imported.startswith("androidx.")
                    for imported in imports
                )
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC006_LLM_STAGE_SHAPE",
                        "optional LLM implementation must remain one package-confined exact client stage",
                    )
                )

        if relative_path == TRANSCRIPT_INTEGRITY_GUARD_STAGE_PATH:
            observed_methods = Counter(
                STRICT_METHOD_DECLARATION_START_PATTERN.findall(code)
            )
            required_tokens = (
                "final class TranscriptIntegrityGuardStage",
                "implements TextProcessingPipeline.IntegrityGuardStage",
                "TranscriptIntegrityGuard.validate(",
            )
            forbidden_tokens = (
                "public final class TranscriptIntegrityGuardStage",
                "InputConnection",
                "EditorOperation",
                "OpenAiCompatibleClient",
                "Context",
                "Activity",
                "Service",
                "Repository",
                "Store",
                "Thread",
                "Executor",
                "Serializable",
                "Parcelable",
                "catch (",
                "Log.",
                "System.out",
                "System.err",
            )
            if (
                observed_methods != Counter({"apply": 1})
                or any(token not in code for token in required_tokens)
                or any(token in code for token in forbidden_tokens)
                or code.count("TranscriptIntegrityGuard.validate(") != 1
                or re.search(r"(?m)^\s*(?:private|protected|public)\s+[^\n(=;]+\s+\w+\s*;", code)
                or any(
                    imported.startswith("android.") or imported.startswith("androidx.")
                    for imported in imports
                )
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC006_INTEGRITY_STAGE_SHAPE",
                        "integrity implementation must remain one package-confined stateless guard stage",
                    )
                )

        if relative_path == VOICE_PIPELINE_RUNTIME_PATH and text_processing_reference:
            required_tokens = (
                "private final TextProcessingPipeline textProcessingPipeline",
                "textProcessingPipeline = new StagedTextProcessingPipeline(",
                "new DeterministicPersonalizationStage()",
                "VoiceCommandProcessor.exactReplacement(text)",
                "new OpenAiOptionalLlmStage(client)",
                "new TranscriptIntegrityGuardStage()",
            )
            exact_calls = {
                "new StagedTextProcessingPipeline(": 1,
                "textProcessingPipeline.deterministic(": 2,
                "textProcessingPipeline.command(": 1,
                "textProcessingPipeline.optionalLlm(": 1,
                "textProcessingPipeline.integrity(": 1,
            }
            if (
                any(token not in code for token in required_tokens)
                or any(code.count(token) != count for token, count in exact_calls.items())
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC003_PIPELINE_CALLER",
                        "VoicePipelineRuntime must construct one staged dispatcher and route the exact processing flow through it",
                    )
                )

            if (
                code.count("new OpenAiOptionalLlmStage(client)") != 1
                or code.count("new TranscriptIntegrityGuardStage()") != 1
                or "client.complete(" in code
                or "PromptComposer.systemPrompt(" in code
                or "PromptComposer.userPrompt(" in code
                or "TranscriptIntegrityGuard.validate(" in code
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC006_PIPELINE_BINDING",
                        "VoicePipelineRuntime must only construct the independent LLM and integrity stages",
                    )
                )

            if (
                code.count("new DeterministicPersonalizationStage()") != 1
                or "PersonalizedTextProcessor" in code
                or "applyPersonalizationFailSafe" in code
                or "applyDeterministicStage" in code
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC005_PIPELINE_BINDING",
                        "VoicePipelineRuntime must only construct the independent deterministic personalization stage",
                    )
                )

        if relative_path == STAGE_PROVENANCE_PATH:
            provenance_shape = re.search(
                r"(?s)public\s+record\s+StageProvenance\s*\(\s*"
                r"Stage\s+stage\s*,\s*Disposition\s+disposition\s*\)",
                code,
            )
            stage_enum_shape = re.search(
                r"(?s)enum\s+Stage\s*\{\s*RECOGNITION\s*,\s*DETERMINISTIC\s*,\s*"
                r"LOCAL_COMMAND\s*,\s*OPTIONAL_LLM\s*,\s*INTEGRITY_GUARD\s*,\s*"
                r"FINALIZATION\s*\}",
                code,
            )
            disposition_enum_shape = re.search(
                r"(?s)enum\s+Disposition\s*\{\s*CAPTURED\s*,\s*RECOVERED\s*,\s*"
                r"APPLIED\s*,\s*SKIPPED\s*,\s*FAILED\s*,\s*ACCEPTED\s*,\s*"
                r"REJECTED\s*,\s*PUBLISHED\s*,\s*FALLBACK\s*\}",
                code,
            )
            required_tokens = (
                "if (!isAllowed(stage, disposition))",
            )
            forbidden_tokens = (
                "String text",
                "InputConnection",
                "EditorOperation",
                "Context",
                "Serializable",
                "Parcelable",
                "Throwable",
                "Thread",
                "Executor",
            )
            if (
                provenance_shape is None
                or stage_enum_shape is None
                or disposition_enum_shape is None
                or any(token not in code for token in required_tokens)
                or any(token in code for token in forbidden_tokens)
                or any(
                    imported.startswith("android.") or imported.startswith("androidx.")
                    for imported in imports
                )
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC004_PROVENANCE_SHAPE",
                        "stage provenance must remain an exact content-free closed value",
                    )
                )

        if relative_path == VOICE_RESULT_PATH:
            result_shape = re.search(
                r"(?s)public\s+record\s+VoiceResult\s*\(\s*"
                r"String\s+rawText\s*,\s*String\s+deterministicText\s*,\s*"
                r"String\s+candidateText\s*,\s*String\s+finalText\s*,\s*"
                r"List\s*<\s*StageProvenance\s*>\s+provenance\s*\)",
                code,
            )
            required_tokens = (
                "static final int MAX_TEXT_CODE_POINTS = 20_000",
                "provenance = List.copyOf(",
                "validateProvenance(",
                "static VoiceResult processed(",
                "static VoiceResult recovered(String text)",
                "public boolean aiOutputAccepted()",
                "requireWellFormedUtf16(safe, name)",
            )
            forbidden_tokens = (
                "InputConnection",
                "EditorOperation",
                "Context",
                "Activity",
                "Service",
                "Repository",
                "Store",
                "Serializable",
                "Parcelable",
                "Throwable",
                "Thread",
                "Executor",
            )
            if (
                result_shape is None
                or any(token not in code for token in required_tokens)
                or any(token in code for token in forbidden_tokens)
                or code.count("new StageProvenance(") != 1
                or any(
                    imported.startswith("android.") or imported.startswith("androidx.")
                    for imported in imports
                )
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC004_RESULT_SHAPE",
                        "VoiceResult must retain the exact immutable bounded four-text artifact",
                    )
                )
            if 'return "VoiceResult{<redacted>}";' not in source:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC004_RESULT_REDACTION",
                        "VoiceResult diagnostics must remain fixed and content-free",
                    )
                )

        if relative_path == DICTATION_RESULT_PATH:
            envelope_shape = re.search(
                r"(?s)public\s+record\s+DictationResult\s*\(\s*"
                r"VoiceResult\s+voiceResult\s*,\s*Outcome\s+outcome\s*,\s*"
                r"ProcessingMode\s+mode\s*,\s*RecognitionBackend\s+backend\s*,\s*"
                r"long\s+durationMs\s*,\s*boolean\s+reachedRecordingLimit\s*,\s*"
                r"boolean\s+recoveredPartial\s*,\s*List\s*<\s*Long\s*>\s+matchedTermIds\s*,\s*"
                r"List\s*<\s*Long\s*>\s+matchedCorrectionIds\s*,\s*String\s+recoveryId\s*\)",
                code,
            )
            required_tokens = (
                "return voiceResult.rawText()",
                "return voiceResult.deterministicText()",
                "return voiceResult.finalText()",
                "return voiceResult.aiOutputAccepted()",
                "matchedTermIds = List.copyOf(",
                "matchedCorrectionIds = List.copyOf(",
            )
            if envelope_shape is None or any(token not in code for token in required_tokens):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC004_DICTATION_ENVELOPE",
                        "DictationResult must hold one VoiceResult and delegate legacy text accessors",
                    )
                )
            if 'return "DictationResult{<redacted>}";' not in source:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC004_RESULT_REDACTION",
                        "DictationResult diagnostics must remain fixed and content-free",
                    )
                )

        if relative_path == VOICE_PIPELINE_RUNTIME_PATH and "VoiceResult" in code:
            required_tokens = (
                "candidateText = protectedCandidate.text()",
                "VoiceResult voiceResult = VoiceResult.processed(",
            )
            integrity_candidate = re.search(
                r"(?s)new\s+TextProcessingPipeline\.IntegrityRequest\s*\(\s*"
                r"integritySource\s*,\s*candidateText\s*,\s*run\.mode\s*,\s*snapshot\s*\)",
                code,
            )
            exact_calls = {
                "VoiceResult.processed(": 1,
                "VoiceResult.recovered(": 2,
            }
            if (
                any(token not in code for token in required_tokens)
                or integrity_candidate is None
                or any(code.count(token) != count for token, count in exact_calls.items())
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC004_PIPELINE_BINDING",
                        "VoicePipelineRuntime must publish the exact candidate checked by integrity in one artifact",
                    )
                )

        if relative_path in VOICE_RESULT_DIRECT_CONSUMERS:
            if re.search(r"\bresult\s*\.\s*(?:rawText|finalText)\s*\(", code):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC004_RESULT_CONSUMER",
                        "Raw, final delivery and history must read through DictationResult.voiceResult",
                    )
                )

        teach_factory_reference = re.search(r"\bcreateTeachIntent\s*\(", code)
        if (
            teach_factory_reference
            and relative_path not in VOC008_TEACH_FACTORY_ALLOWED_SOURCE_CONSUMERS
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "VOC008_TEACH_SCOPE_TRANSFER",
                    "only the exact IME root may invoke the HistoryActivity CommitRecord Teach factory",
                )
            )

        if relative_path == IME_SERVICE_PATH and (
            "teachCorrection" in code
            or "teachRecord" in code
            or "createTeachIntent" in code
        ):
            teach_start = code.find("private void teachCorrection()")
            teach_end = code.find("private boolean lastCommitStillTargetsCurrentEditor", teach_start)
            teach_body = code[teach_start:teach_end] if teach_start >= 0 and teach_end > teach_start else ""
            required_teach_tokens = (
                "final CommitRecord teachRecord;",
                "this.teachRecord = teachRecord;",
                "CommitRecord record = commit == null ? null : commit.teachRecord;",
                "HistoryActivity.createTeachIntent(this, record, commit.historyId)",
                "TeachCorrectionResolver.isEligible(commit.teachRecord)",
                "acceptSuccessfulTransactionCommit(target, result, committed.record())",
            )
            forbidden_teach_tokens = (
                "commit.rawText",
                "commit.insertedText",
                "commit.packageName",
                "commit.learningAllowed",
                ".putExtra(",
            )
            if (
                any(token not in code for token in required_teach_tokens)
                or len(re.findall(r"\bfinal\s+CommitRecord\s+teachRecord\s*;", code)) != 1
                or not teach_body
                or any(token in teach_body for token in forbidden_teach_tokens)
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC008_TEACH_AUTHORITY",
                        "Teach must be derived only from the exact same-stack CommitRecord, never copied LastVoiceCommit plaintext",
                    )
                )

        if relative_path == HISTORY_ACTIVITY_PATH:
            factory_shape = re.search(
                r"(?ms)^[ \t]*public\s+static\s+Intent\s+createTeachIntent\s*\(\s*"
                r"Context\s+context\s*,\s*CommitRecord\s+record\s*,\s*long\s+historyId\s*\)",
                code,
            )
            factory_tokens = (
                "HistoryEntry current = TeachCorrectionResolver.resolve(null, record)",
                ".putExtra(EXTRA_RAW_TEXT, current.rawText())",
                ".putExtra(EXTRA_FINAL_TEXT, current.finalText())",
                ".putExtra(EXTRA_APP_SCOPE, current.appPackage())",
            )
            if factory_shape is None or any(token not in code for token in factory_tokens):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC008_TEACH_FACTORY_SHAPE",
                        "HistoryActivity Teach factory must accept only Context, CommitRecord and history metadata",
                    )
                )

        if relative_path == TEACH_CORRECTION_RESOLVER_PATH:
            resolver_tokens = (
                "public static boolean isEligible(CommitRecord record)",
                "record.source() == OperationSource.VOICE",
                "record.learningAllowed()",
                "record.rawTranscript() instanceof CommitRecord.RawTranscript.Present",
                "public static HistoryEntry resolve(HistoryEntry stored, CommitRecord record)",
                "record.originalSession().packageName()",
                "record.originalSession().fieldKind().name()",
                "raw.text()",
                "record.insertedText()",
            )
            if any(token not in code for token in resolver_tokens):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "VOC008_TEACH_RESOLVER_SHAPE",
                        "Teach eligibility and current text/scope must be derived from an exact VOICE learning-enabled CommitRecord",
                    )
                )

        audit_scope_references = sorted(
            token
            for token in EDITOR_TRANSACTION_AUDIT_SCOPE_TOKENS
            if re.search(rf"\b{token}\b", code)
        )
        if (
            audit_scope_references
            and relative_path not in EDITOR_TRANSACTION_AUDIT_ALLOWED_SOURCE_CONSUMERS
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "EDITOR_TRANSACTION_AUDIT_SCOPE_TRANSFER",
                    "transaction audit metadata and its sink are confined to the exact model/host owners",
                )
            )
        if (
            relative_path != EDITOR_TRANSACTION_MANAGER_PATH
            and re.search(r"\bnew\s+EditorTransactionAudit\s*\(", code)
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "EDITOR_TRANSACTION_AUDIT_CALLER",
                    "only exact EditorTransactionManager may construct terminal audit observations",
                )
            )

        evidence_scope_references = sorted(
            token for token in UNDO_EVIDENCE_SCOPE_TOKENS if re.search(rf"\b{token}\b", code)
        )
        if (
            evidence_scope_references
            and relative_path not in UNDO_EVIDENCE_ALLOWED_SOURCE_CONSUMERS
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "UNDO_EVIDENCE_SCOPE_TRANSFER",
                    "Undo evidence capability is confined to EditorSessionManager and "
                    "EditorTransactionManager",
                )
            )
        raw_scope_references = sorted(
            token for token in RAW_RESTORE_SCOPE_TOKENS if re.search(rf"\b{token}\b", code)
        )
        if (
            raw_scope_references
            and relative_path not in UNDO_EVIDENCE_ALLOWED_SOURCE_CONSUMERS
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "RAW_RESTORE_SCOPE_TRANSFER",
                    "Raw Restore proof capability is confined to EditorSessionManager and "
                    "EditorTransactionManager",
                )
            )

        current_evidence_references = sorted(
            token
            for token in CURRENT_EVIDENCE_SCOPE_TOKENS
            if re.search(rf"\b{token}\b", code)
        )
        if (
            current_evidence_references
            and relative_path not in UNDO_EVIDENCE_ALLOWED_SOURCE_CONSUMERS
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "CURRENT_EVIDENCE_SCOPE_TRANSFER",
                    "live selection evidence and Replace proof capabilities are confined to "
                    "EditorSessionManager and EditorTransactionManager",
                )
            )

        if relative_path == EDITOR_SESSION_MANAGER_PATH and (
            evidence_scope_references
            or raw_scope_references
            or current_evidence_references
        ):
            reader = re.search(
                r"(?ms)^[ \t]*interface\s+UndoEvidenceReader\s*\{(?P<body>[^{}]*)\}",
                code,
            )
            expected_read = re.compile(
                r"^\s*UndoEvidenceReadResult\s+read\s*\(\s*InputConnection\s+"
                r"authorizedConnection\s*,\s*UndoEvidenceRequest\s+request\s*\)\s*;\s*$"
            )
            exact_shapes = (
                reader is not None
                and expected_read.fullmatch(reader.group("body")) is not None
                and re.search(
                    r"(?m)^[ \t]*record\s+UndoEvidenceRequest\s*\(\s*int\s+"
                    r"beforeUtf16Units\s*,\s*int\s+afterUtf16Units\s*\)",
                    code,
                )
                is not None
                and re.search(
                    r"(?m)^[ \t]*sealed\s+interface\s+UndoEvidenceReadResult\s+"
                    r"permits\s+UndoEvidence\s*,\s*UndoEvidenceUnavailable\s*\{\s*\}",
                    code,
                )
                is not None
                and re.search(
                    r"(?ms)^[ \t]*record\s+UndoEvidence\s*\(\s*boolean\s+"
                    r"selectionAvailable\s*,\s*int\s+selectionStart\s*,\s*"
                    r"int\s+selectionEnd\s*,\s*boolean\s+selectedTextAvailable\s*,\s*"
                    r"CharSequence\s+selectedText\s*,\s*"
                    r"boolean\s+beforeTextAvailable\s*,\s*CharSequence\s+beforeText\s*,\s*"
                    r"boolean\s+afterTextAvailable\s*,\s*CharSequence\s+afterText\s*\)\s*"
                    r"implements\s+UndoEvidenceReadResult\s*\{",
                    code,
                )
                is not None
                and re.search(
                    r"(?m)^[ \t]*record\s+UndoEvidenceUnavailable\s*\(\s*\)\s*"
                    r"implements\s+UndoEvidenceReadResult\s*\{\s*\}",
                    code,
                )
                is not None
            )
            if not exact_shapes:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "UNDO_EVIDENCE_SCOPE_SHAPE",
                        "Undo evidence reader/request/results must retain their exact "
                        "package-confined, bounded, field-only surface",
                    )
                )
            if 'return "UndoEvidence{<redacted>}";' not in source:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "UNDO_EVIDENCE_REDACTION",
                        "UndoEvidence.toString() must return only its fixed redacted marker",
                    )
                )

            if re.search(r"\b(?:RawTransition|RawProofState)\b", code):
                raw_start = code.find("static final class RawTransition")
                raw_end = code.find("static final class ReplaceTransition", raw_start)
                raw_code = code[raw_start : raw_end if raw_end >= 0 else None]
                raw_shapes = (
                    re.search(
                        r"(?m)^[ \t]*enum\s+RawProofState\s*\{\s*COMMITTED\s*,\s*"
                        r"ORIGINAL\s*,\s*UNDO\s*,\s*RAW\s*\}",
                        code,
                    )
                    is not None
                    and raw_start >= 0
                    and re.search(r"\bprivate\s+final\s+Object\s+ownerStamp\s*;", raw_code)
                    is not None
                    and re.search(
                        r"\bprivate\s+final\s+EditorSessionSnapshot\s+expectedOrigin\s*;",
                        raw_code,
                    )
                    is not None
                    and len(
                        re.findall(
                            r"\bprivate\s+final\s+TextRange\s+"
                            r"(?:managerSelection|provenFromSelection|targetSelection)\s*;",
                            raw_code,
                        )
                    )
                    == 3
                    and len(
                        re.findall(
                            r"\bprivate\s+final\s+TextFingerprint\s+"
                            r"expected(?:Inserted|Replacement)Fingerprint\s*;",
                            raw_code,
                        )
                    )
                    == 2
                    and re.search(
                        r"(?ms)^[ \t]*RawTransition\s+prepareRawTransition\s*\(\s*"
                        r"CommitRecord\s+record\s*,\s*RawProofState\s+fromState\s*,\s*"
                        r"RawProofState\s+targetState\s*\)",
                        code,
                    )
                    is not None
                    and 'return "RawTransition{<redacted>}";' in source
                )
                if not raw_shapes:
                    violations.append(
                        ArchitectureViolation(
                            relative_path,
                            "RAW_TRANSITION_SCOPE_SHAPE",
                            "Raw transition must remain owner-bound, fingerprint-bound, "
                            "package-confined and redacted",
                        )
                    )

            if current_evidence_references:
                current_reader = re.search(
                    r"(?ms)^[ \t]*interface\s+CurrentEvidenceReader\s*\{(?P<body>[^{}]*)\}",
                    code,
                )
                current_read = re.compile(
                    r"^\s*EvidenceReadResult\s+read\s*\(\s*InputConnection\s+"
                    r"authorizedConnection\s*,\s*CurrentEvidenceRequest\s+request\s*\)\s*;\s*$"
                )
                current_shapes = (
                    current_reader is not None
                    and current_read.fullmatch(current_reader.group("body")) is not None
                    and re.search(
                        r"(?m)^[ \t]*record\s+CurrentEvidenceRequest\s*\(\s*int\s+"
                        r"beforeUtf16Units\s*,\s*int\s+afterUtf16Units\s*\)",
                        code,
                    )
                    is not None
                    and re.search(
                        r"(?ms)^[ \t]*record\s+CurrentEvidence\s*\(\s*boolean\s+"
                        r"selectionAvailable\s*,\s*int\s+selectionStart\s*,\s*"
                        r"int\s+selectionEnd\s*,\s*boolean\s+selectedTextAvailable\s*,\s*"
                        r"CharSequence\s+selectedText\s*,\s*boolean\s+beforeTextAvailable\s*,\s*"
                        r"CharSequence\s+beforeText\s*,\s*boolean\s+afterTextAvailable\s*,\s*"
                        r"CharSequence\s+afterText\s*\)\s*implements\s+EvidenceReadResult\s*\{",
                        code,
                    )
                    is not None
                    and re.search(
                        r"(?m)^[ \t]*record\s+ValidatedEvidence\s*\(\s*TextRange\s+"
                        r"selection\s*,\s*String\s+selected\s*,\s*String\s+before\s*,\s*"
                        r"String\s+after\s*\)",
                        code,
                    )
                    is not None
                    and re.search(
                        r"(?m)^[ \t]*private\s+record\s+MaterializedEvidence\s*\(\s*"
                        r"TextRange\s+selection\s*,\s*String\s+selected\s*,\s*"
                        r"String\s+before\s*,\s*String\s+after\s*\)",
                        code,
                    )
                    is not None
                    and "evidence.selection()" in code
                )
                if not current_shapes:
                    violations.append(
                        ArchitectureViolation(
                            relative_path,
                            "CURRENT_EVIDENCE_SCOPE_SHAPE",
                            "current evidence must retain exact live-selection coordinates, "
                            "bounded requests and materialized selection",
                        )
                    )

                replace_start = code.find("static final class ReplaceTransition")
                replace_end = code.find("private final OwnerGuard", replace_start)
                replace_code = code[
                    replace_start : replace_end if replace_end >= 0 else None
                ]
                replace_shapes = (
                    re.search(
                        r"(?m)^[ \t]*enum\s+ReplaceProofState\s*\{\s*ORIGINAL\s*,\s*"
                        r"INTENDED\s*\}",
                        code,
                    )
                    is not None
                    and replace_start >= 0
                    and re.search(r"\bprivate\s+final\s+Object\s+ownerStamp\s*;", replace_code)
                    is not None
                    and re.search(r"\bprivate\s+boolean\s+claimed\s*;", replace_code)
                    is not None
                    and "InputConnection" not in replace_code
                    and not re.search(r"\bString\s+[A-Za-z_$][\w$]*\s*;", replace_code)
                    and 'return "ReplaceTransition{<redacted>}";' in source
                    and re.search(
                        r"(?ms)^[ \t]*ReplaceTransition\s+prepareReplaceTransition\s*\(\s*"
                        r"EditorSessionSnapshot\s+expected\s*,\s*"
                        r"EditorOperation\.ReplaceSelection\s+operation\s*,\s*"
                        r"ReplaceProofState\s+targetState\s*\)",
                        code,
                    )
                    is not None
                    and re.search(
                        r"(?ms)^[ \t]*ReplaceValidationResult\s+"
                        r"validateReplaceTransitionState\s*\(\s*ReplaceTransition\s+"
                        r"transition\s*,\s*LiveAuthoritySupplier\s+authoritySupplier\s*,\s*"
                        r"CurrentEvidenceReader\s+evidenceReader\s*\)",
                        code,
                    )
                    is not None
                )
                if not replace_shapes:
                    violations.append(
                        ArchitectureViolation(
                            relative_path,
                            "REPLACE_TRANSITION_SCOPE_SHAPE",
                            "Replace outcome proof must remain owner-bound, one-shot, "
                            "content-free and host-confined",
                        )
                    )

        if relative_path == EDITOR_TRANSACTION_MANAGER_PATH and re.search(
            r"\brestoreRawCommit\s*\(", code
        ):
            raw_restore_denial = re.search(
                r"operation\s*\.\s*source\s*\(\s*\)\s*==\s*OperationSource\.UNDO\s*"
                r"\|\|\s*operation\s*\.\s*source\s*\(\s*\)\s*==\s*"
                r"OperationSource\.RAW_RESTORE",
                code,
            )
            if raw_restore_denial is None:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "RAW_RESTORE_APPLY_DENIAL",
                        "ordinary apply policy must explicitly reject RAW_RESTORE source",
                    )
                )
            if len(re.findall(r"\bcommitLedger\s*\.\s*resolve\s*\(", code)) != 3:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "COMMIT_LEDGER_CALLER",
                        "exact ETM must retain only Undo, Raw and test-seam resolve edges",
                    )
                )
            if len(re.findall(r"\bcommitLedger\s*\.\s*consume\s*\(", code)) != 3:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "COMMIT_LEDGER_CALLER",
                        "exact ETM must retain only Undo, Raw and test-seam consume edges",
                    )
                )
        if relative_path == COMMIT_LEDGER_PATH:
            if not re.search(
                r"(?m)^[ \t]*final[ \t]+class[ \t]+CommitLedger\b", code
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "COMMIT_LEDGER_DECLARATION",
                        "CommitLedger must be a package-private final top-level class",
                    )
                )
            if not re.search(r"\bprivate\s+final\s+Thread\s+ownerThread\s*;", code):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "COMMIT_LEDGER_OWNER_CONFINEMENT",
                        "CommitLedger must capture one private final owner Thread",
                    )
                )
            if re.search(
                r"\b(?:capacity|Map|HashMap|LinkedHashMap|Collection|List|Set|"
                r"Queue|Deque)\b",
                code,
            ):
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "COMMIT_LEDGER_SINGLE_SLOT",
                        "CommitLedger must use one direct CommitRecord slot, never a capacity or collection",
                    )
                )
            for method_name in ("resolve", "consume"):
                exact_id = re.search(
                    rf"\b{method_name}\s*\(\s*String\s+commitId\s*,\s*"
                    r"EditorSessionSnapshot\s+[A-Za-z_$][\w$]*\s*\)",
                    code,
                )
                if exact_id is None:
                    violations.append(
                        ArchitectureViolation(
                            relative_path,
                            "COMMIT_LEDGER_EXACT_ID_API",
                            f"{method_name} must require exact commitId and current EditorSessionSnapshot",
                        )
                    )

        ledger_receivers = set(
            re.findall(r"\bCommitLedger\s+([A-Za-z_$][\w$]*)\b", code)
        )
        unauthorized_ledger_call = any(
            re.search(rf"\b{re.escape(receiver)}\s*\.\s*(?:resolve|consume)\s*\(", code)
            for receiver in ledger_receivers
        )
        if (
            relative_path not in {EDITOR_TRANSACTION_MANAGER_PATH, COMMIT_LEDGER_PATH}
            and unauthorized_ledger_call
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "COMMIT_LEDGER_CALLER",
                    "CommitLedger resolve/consume are confined to exact EditorTransactionManager",
                )
            )

        if relative_path in {
            EDITOR_TRANSACTION_MANAGER_PATH,
            COMMIT_LEDGER_PATH,
            TRANSACTION_RECEIPT_PATH,
        }:
            forbidden_lookups = sorted(
                {
                    name
                    for name in METHOD_DECLARATION_NAME_PATTERN.findall(code)
                    if _is_forbidden_commit_lookup_name(name)
                }
            )
            if forbidden_lookups:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "COMMIT_RECENCY_LOOKUP_API",
                        "forbidden mutable-recency commit lookup: "
                        + ", ".join(forbidden_lookups),
                    )
                )

        serialization_references = sorted(
            {
                value
                for value in imports
                if _has_type_prefix(value, EDITOR_SERIALIZATION_TYPE_PREFIXES)
            }
            | {
                prefix
                for prefix in EDITOR_SERIALIZATION_TYPE_PREFIXES
                if prefix in code
            }
        )
        if editor_domain and serialization_references:
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "EDITOR_MODEL_SERIALIZATION_DEPENDENCY",
                    ", ".join(serialization_references),
                )
            )

        if relative_path in COMMIT_ENVELOPE_PATHS:
            execution_references = sorted(
                {
                    value
                    for value in imports
                    if _has_type_prefix(
                        value, COMMIT_ENVELOPE_EXECUTION_TYPE_PREFIXES
                    )
                }
                | {
                    prefix
                    for prefix in COMMIT_ENVELOPE_EXECUTION_TYPE_PREFIXES
                    if prefix in code
                }
            )
            if execution_references:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "COMMIT_ENVELOPE_EXECUTION_CAPABILITY",
                        ", ".join(execution_references),
                    )
                )
            forbidden_declarations = sorted(
                set(COMMIT_ENVELOPE_FORBIDDEN_DECLARATION_PATTERN.findall(code))
            )
            throwable_declarations = [
                value
                for value in forbidden_declarations
                if value == "Throwable"
                or value.endswith("Exception")
                or value.endswith("Error")
            ]
            execution_declarations = sorted(
                set(forbidden_declarations) - set(throwable_declarations)
            )
            if throwable_declarations:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "COMMIT_ENVELOPE_THROWABLE",
                        ", ".join(throwable_declarations),
                    )
                )
            if execution_declarations:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "COMMIT_ENVELOPE_EXECUTION_CAPABILITY",
                        ", ".join(execution_declarations),
                    )
                )
        qualified_input_connection_references = {
            value
            for value in INPUT_CONNECTION_CAPABILITY_TYPES
            if value in code
        }
        imported_input_connection_capabilities = {
            value
            for value in imports
            if value in INPUT_CONNECTION_CAPABILITY_TYPES
            or value.endswith(".InputConnection")
            or value.endswith(".BaseInputConnection")
            or value.endswith(".InputConnectionWrapper")
        }
        references_input_connection = (
            bool(imported_input_connection_capabilities)
            or "android.view.inputmethod.*" in imports
            or "android.inputmethodservice.*" in imports
            or "android.inputmethodservice.InputMethodService" in imports
            or bool(qualified_input_connection_references)
            or bool(INPUT_CONNECTION_TYPE_USE_PATTERN.search(code))
            or any(value in no_comments for value in INPUT_CONNECTION_CAPABILITY_TYPES)
            or any(value in reflective_source for value in INPUT_CONNECTION_CAPABILITY_TYPES)
        )
        editor_writes = Counter(EDITOR_WRITE_PATTERN.findall(code))
        editor_writes.update(EDITOR_WRITE_METHOD_REFERENCE_PATTERN.findall(code))
        if references_input_connection:
            editor_writes["setSelection"] = len(
                re.findall(r"\.\s*setSelection\s*\(", code)
            )
            if not editor_writes["setSelection"]:
                del editor_writes["setSelection"]
        if editor_writes:
            observed_writers[relative_path] = editor_writes
            expected = LEGACY_EDITOR_WRITES.get(relative_path)
            if relative_path == EDITOR_TRANSACTION_MANAGER_PATH:
                forbidden = set(editor_writes) - EDITOR_TRANSACTION_WRITE_METHODS
                if forbidden:
                    violations.append(
                        ArchitectureViolation(
                            relative_path,
                            "EDITOR_TRANSACTION_WRITE_SURFACE",
                            "forbidden transaction mutators: "
                            + ", ".join(sorted(forbidden)),
                        )
                    )
                if editor_writes != EDITOR_TRANSACTION_EXACT_WRITES:
                    violations.append(
                        ArchitectureViolation(
                            relative_path,
                            "EDITOR_TRANSACTION_EXACT_WRITE_SURFACE",
                            f"expected {_format_counter(EDITOR_TRANSACTION_EXACT_WRITES)}; "
                            f"observed {_format_counter(editor_writes)}",
                        )
                    )
                wrong_owners = []
                for occurrence in EDITOR_WRITE_PATTERN.finditer(code):
                    method = occurrence.group(1)
                    declaration = _enclosing_method_declaration(code, occurrence.start())
                    owner = declaration.group(1) if declaration is not None else None
                    expected_owner = EDITOR_TRANSACTION_WRITE_OWNERS.get(method)
                    if expected_owner is not None and owner != expected_owner:
                        wrong_owners.append(f"{method}->{owner or '<none>'}")
                if wrong_owners:
                    violations.append(
                        ArchitectureViolation(
                            relative_path,
                            "EDITOR_TRANSACTION_WRITE_METHOD_SURFACE",
                            "writer calls escaped their exact audited methods: "
                            + ", ".join(sorted(wrong_owners)),
                        )
                    )
            elif expected is None:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "EDITOR_WRITE_OWNER",
                        "new editor writes must wait for EditorTransactionManager",
                    )
                )
            elif editor_writes != expected:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "EDITOR_WRITE_RATCHET",
                        f"expected {_format_counter(expected)}; observed {_format_counter(editor_writes)}",
                    )
                )

        if (
            relative_path == EDITOR_TRANSACTION_MANAGER_PATH
            and EDITOR_TRANSACTION_INDIRECT_IME_METHOD_PATTERN.search(code)
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "EDITOR_TRANSACTION_INDIRECT_IME_ACCESS",
                    "transaction writes must use only the scoped exact InputConnection parameter",
                )
            )

        if REFLECTIVE_METHOD_ACCESS_PATTERN.search(code):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "REFLECTIVE_METHOD_ACCESS",
                    "reflective method lookup requires an exact audited architecture owner",
                )
            )

        reflection_imports = sorted(
            value
            for value in imports
            if any(
                value == prefix or value.startswith(prefix + ".")
                for prefix in REFLECTION_CAPABILITY_IMPORT_PREFIXES
            )
        )
        if reflection_imports:
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "REFLECTION_CAPABILITY",
                    ", ".join(reflection_imports),
                )
            )
        reflection_qualified_references = sorted(
            set(
                re.findall(
                    r"(?<![\w$.])java\.lang\.(?:reflect|invoke)\."
                    r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*",
                    code,
                )
            )
        )
        if reflection_qualified_references:
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "REFLECTION_CAPABILITY",
                    ", ".join(reflection_qualified_references),
                )
            )

        if references_input_connection and relative_path not in INPUT_CONNECTION_OWNERS:
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "INPUT_CONNECTION_OWNER",
                    "only the audited Android host adapters may reference InputConnection",
                )
            )

        if (
            re.search(r"\.\s*getCurrentInputConnection\s*\(", code)
            and relative_path != "com/opentypeless/android/ime/OpenTypelessImeService.java"
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "INPUT_CONNECTION_ACCESSOR",
                    "getCurrentInputConnection is restricted to the IME host service",
                )
            )

        references_editor_host = (
            any(
                value == EDITOR_HOST_PACKAGE or value.startswith(EDITOR_HOST_PACKAGE + ".")
                for value in imports
            )
            or EDITOR_HOST_PACKAGE in code
            or EDITOR_HOST_PACKAGE in no_comments
        )
        inside_editor_host = package_name == EDITOR_HOST_PACKAGE or package_name.startswith(
            EDITOR_HOST_PACKAGE + "."
        )
        if (
            references_editor_host
            and not inside_editor_host
            and relative_path not in EDITOR_HOST_CAPABILITY_CONSUMERS
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "EDITOR_HOST_CAPABILITY_BOUNDARY",
                    "editor host capabilities are default-deny outside audited composition roots",
                )
            )

        references_transaction_manager = (
            EDITOR_TRANSACTION_MANAGER_FQCN in imports
            or EDITOR_TRANSACTION_MANAGER_FQCN in code
            or EDITOR_TRANSACTION_MANAGER_FQCN in no_comments
            or (
                inside_editor_host
                and bool(re.search(r"\bEditorTransactionManager\b", code))
            )
        )
        if (
            references_transaction_manager
            and relative_path not in EDITOR_TRANSACTION_ALLOWED_SOURCE_CONSUMERS
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "EDITOR_TRANSACTION_CAPABILITY_BOUNDARY",
                    "only EditorSessionManager may invoke the package-confined transaction writer",
                )
            )

        restricted_layer = _is_ui(relative_path, package_name) or _under(
            package_name, PROVIDER_PACKAGE_PREFIXES
        )
        if restricted_layer:
            restricted_references = {
                value
                for value in FORBIDDEN_EXECUTION_TYPES
                if value in imports or value in code or value in no_comments
            }
            if references_input_connection:
                restricted_references.add(INPUT_CONNECTION_TYPE)
            if re.search(r"\.\s*getCurrentInputConnection\s*\(", code):
                restricted_references.add("InputMethodService.getCurrentInputConnection")
            if restricted_references:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "UI_PROVIDER_EDITOR_CAPABILITY",
                        ", ".join(sorted(restricted_references)),
                    )
                )

        # Dynamic type loading can reconstruct a forbidden capability name from arbitrary runtime
        # data, which cannot be proven safe with a source-name allowlist. Keep it default-deny and
        # add exact audited owners only if a future task genuinely requires it.
        if REFLECTIVE_TYPE_LOAD_PATTERN.search(code) or REFLECTIVE_TYPE_LOAD_PATTERN.search(
            _normalize_code_identifiers(reflective_source)
        ):
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "REFLECTIVE_TYPE_LOADING",
                    "dynamic class loading requires an exact audited architecture owner",
                )
            )

        if editor_domain or _under(package_name, PURE_DOMAIN_PACKAGE_PREFIXES):
            android_imports = sorted(
                value
                for value in imports
                if value.startswith("android.") or value.startswith("androidx.")
            )
            qualified_android_references = sorted(
                set(
                    re.findall(
                        r"(?<![\w$.])(?:android|androidx)\.[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+",
                        code,
                    )
                )
            )
            android_references = sorted(
                set(android_imports).union(qualified_android_references)
            )
            if android_references:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "PURE_DOMAIN_ANDROID_DEPENDENCY",
                        ", ".join(android_references),
                    )
                )
            host_references = sorted(
                value
                for value in FORBIDDEN_EXECUTION_TYPES
                if value.startswith("com.opentypeless.android.editor.host")
                and (value in imports or value in code or value in no_comments)
            )
            if host_references:
                violations.append(
                    ArchitectureViolation(
                        relative_path,
                        "PURE_DOMAIN_HOST_DEPENDENCY",
                        ", ".join(host_references),
                    )
                )

    if enforce_legacy_inventory:
        missing = sorted(set(LEGACY_EDITOR_WRITES) - set(observed_writers))
        for relative_path in missing:
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "EDITOR_WRITE_INVENTORY",
                    "legacy writer disappeared; shrink the audited inventory in the same change",
                )
            )

    return tuple(sorted(set(violations)))


def _inspect_str001_schema(project_root: Path) -> tuple[ArchitectureViolation, ...]:
    path = project_root / STREAMING_RECOGNITION_SCHEMA_PATH
    relative = STREAMING_RECOGNITION_SCHEMA_PATH.as_posix()
    if not path.is_file() or path.is_symlink():
        return (
            ArchitectureViolation(
                relative,
                "STR001_SCHEMA_MISSING",
                "the v1 streaming RecognitionEvent JSON Schema must be a regular main resource",
            ),
        )
    raw = path.read_bytes()
    if len(raw) > 64_000:
        return (
            ArchitectureViolation(
                relative,
                "STR001_SCHEMA_CONTRACT",
                "the v1 streaming schema exceeded its reviewed source bound",
            ),
        )
    try:
        schema = json.loads(
            raw.decode("utf-8"),
            parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)),
        )
    except (UnicodeError, ValueError, json.JSONDecodeError):
        return (
            ArchitectureViolation(
                relative,
                "STR001_SCHEMA_CONTRACT",
                "the v1 streaming schema must be strict UTF-8 JSON",
            ),
        )

    expected_variants = (
        "preparing",
        "ready",
        "speech_started",
        "partial",
        "endpoint",
        "final",
        "failure",
        "cancelled",
    )
    expected_failures = {
        "UNAVAILABLE",
        "MODEL_MISSING",
        "PERMISSION_DENIED",
        "OEM_MIC_BLOCKED",
        "AUDIO_ERROR",
        "NETWORK_UNAVAILABLE",
        "NETWORK_TIMEOUT",
        "AUTHENTICATION",
        "QUOTA_EXCEEDED",
        "RATE_LIMITED",
        "SERVER_ERROR",
        "PROTOCOL_ERROR",
        "RECOGNIZER_BUSY",
        "NO_MATCH",
        "SPEECH_TIMEOUT",
        "UNSUPPORTED_LANGUAGE",
        "TARGET_CHANGED",
        "INTERNAL_ERROR",
    }
    try:
        definitions = schema["$defs"]
        refs = tuple(item["$ref"] for item in schema["oneOf"])
        variant_definitions = tuple(definitions[name] for name in expected_variants)
        contract_ok = (
            schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
            and schema["$id"]
            == "https://opentypeless.local/schema/streaming-recognition-event-v1.json"
            and refs == tuple(f"#/$defs/{name}" for name in expected_variants)
            and set(definitions)
            == {"protocol", "session_id", "sequence", "metadata", *expected_variants}
            and definitions["protocol"] == {"const": "opentypeless.streaming.v1"}
            and definitions["session_id"].get("minLength") == 1
            and definitions["session_id"].get("maxLength") == 128
            and definitions["sequence"].get("minimum") == 1
            and definitions["sequence"].get("maximum") == 9_223_372_036_854_775_807
            and all(item.get("additionalProperties") is False for item in variant_definitions)
            and all(
                item["properties"]["type"].get("const") == name
                for name, item in zip(expected_variants, variant_definitions)
            )
            and definitions["partial"]["properties"]["text"].get("maxLength") == 20_000
            and definitions["partial"]["properties"]["stable_prefix_utf16"].get("maximum")
            == 40_000
            and definitions["final"]["properties"]["text"].get("maxLength") == 20_000
            and definitions["metadata"].get("additionalProperties") is False
            and definitions["metadata"]["properties"]["detected_language_tag"].get(
                "maxLength"
            )
            == 63
            and definitions["metadata"]["properties"]["audio_duration_ms"].get(
                "maximum"
            )
            == 540_000
            and set(
                definitions["failure"]["properties"]["failure_class"]["enum"]
            )
            == expected_failures
        )
    except (KeyError, TypeError, ValueError):
        contract_ok = False
    if contract_ok:
        return ()
    return (
        ArchitectureViolation(
            relative,
            "STR001_SCHEMA_CONTRACT",
            "the schema must retain the exact closed v1 envelope, eight event variants, bounds, "
            "and non-cancellation FailureClass vocabulary",
        ),
    )


def inspect_android_project(android_root: Path) -> tuple[ArchitectureViolation, ...]:
    """Inspect every production app source set and reject unaudited custom source routing."""

    project_root = android_root.resolve()
    app_root = project_root / "app"
    app_sources = app_root / "src"
    violations: list[ArchitectureViolation] = []

    violations.extend(_inspect_str001_schema(project_root))

    manifest = app_sources / "main" / "AndroidManifest.xml"
    if manifest.is_file() and "android.permission.QUERY_ALL_PACKAGES" in manifest.read_text(
        encoding="utf-8"
    ):
        violations.append(
            ArchitectureViolation(
                "app/src/main/AndroidManifest.xml",
                "CFG009_BROAD_PACKAGE_VISIBILITY",
                "App Picker must not request QUERY_ALL_PACKAGES",
            )
        )

    build_scripts = (app_root / "build.gradle", app_root / "build.gradle.kts")
    for build_script in build_scripts:
        if not build_script.is_file():
            continue
        code = _strip_lexical(build_script.read_text(encoding="utf-8"), strings=True)
        if re.search(r"\bsourceSets\b|\bsrcDirs?\b|\bjava\.srcDir\b|\bkotlin\.srcDir\b", code):
            violations.append(
                ArchitectureViolation(
                    build_script.relative_to(project_root).as_posix(),
                    "UNAUDITED_SOURCE_SET_CONFIGURATION",
                    "custom Android production source directories require architecture-gate support",
                )
            )

    roots: list[Path] = []
    if app_sources.is_dir():
        for source_set in sorted(path for path in app_sources.iterdir() if path.is_dir()):
            if source_set.name in {"test", "androidTest"}:
                continue
            for language_root_name in ("java", "kotlin"):
                language_root = source_set / language_root_name
                if language_root.is_dir():
                    roots.append(language_root)

    for source_root in roots:
        scoped = inspect_source_tree(source_root, enforce_legacy_inventory=False)
        source_set_relative = source_root.relative_to(app_sources).as_posix()
        violations.extend(
            ArchitectureViolation(
                f"{source_set_relative}/{item.relative_path}", item.rule, item.detail
            )
            for item in scoped
        )

    main_java = app_sources / "main" / "java"
    if main_java.is_dir():
        for relative_path in sorted(CFG001_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "CFG001_SOURCE_MISSING",
                        "required ProviderConfig/SecretRef domain source is absent",
                    )
                )
        for relative_path in sorted(CFG002_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "CFG002_SOURCE_MISSING",
                        "required RecognitionRoute domain source is absent",
                    )
                )
        for relative_path in sorted(REC001_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "REC001_SOURCE_MISSING",
                        "required ProviderDescriptor/ProviderCapabilities source is absent",
                    )
                )
        for relative_path in sorted(REC002_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "REC002_SOURCE_MISSING",
                        "required RecognitionEvent/Metadata/Validator source is absent",
                    )
                )
        for relative_path in sorted(STR001_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "STR001_SOURCE_MISSING",
                        "required bounded streaming RecognitionEvent v1 codec source is absent",
                    )
                )
        for relative_path in sorted(STR002_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "STR002_SOURCE_MISSING",
                        "required bounded WebSocket streaming Provider/client source is absent",
                    )
                )
        for relative_path in sorted(STR003_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "STR003_SOURCE_MISSING",
                        "required bounded Qwen3-ASR/vLLM Provider/client source is absent",
                    )
                )
        for relative_path in sorted(STR005_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "STR005_SOURCE_MISSING",
                        "required bounded local streaming Provider/client/model source is absent",
                    )
                )
        for relative_path in sorted(STR006_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "STR006_SOURCE_MISSING",
                        "required bounded two-stage streaming/final Provider source is absent",
                    )
                )
        for relative_path in sorted(STR010_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "STR010_SOURCE_MISSING",
                        "required production RecognitionRouter controller/flag source is absent",
                    )
                )
        for relative_path in sorted(REC003_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "REC003_SOURCE_MISSING",
                        "required bounded ProviderRegistry source is absent",
                    )
                )
        for relative_path in sorted(REC004_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "REC004_SOURCE_MISSING",
                        "required Android System provider adapter source is absent",
                    )
                )
        for relative_path in sorted(REC005_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "REC005_SOURCE_MISSING",
                        "required OpenAI-compatible upload adapter/client source is absent",
                    )
                )
        for relative_path in sorted(REC006_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "REC006_SOURCE_MISSING",
                        "required SenseVoice final adapter/device probe/client source is absent",
                    )
                )
        for relative_path in sorted(REC007_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "REC007_SOURCE_MISSING",
                        "required prefix-replay provider/legacy preview source is absent",
                    )
                )
        for relative_path in sorted(REC008_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "REC008_SOURCE_MISSING",
                        "required unified recognition failure mapping source is absent",
                    )
                )
        for relative_path in sorted(REC009_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "REC009_SOURCE_MISSING",
                        "required finite RecognitionRouter source is absent",
                    )
                )
        for relative_path in sorted(REC010_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "REC010_SOURCE_MISSING",
                        "required privacy confirmation state machine source is absent",
                    )
                )
        for relative_path in sorted(REC011_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "REC011_SOURCE_MISSING",
                        "required provider circuit breaker/router binding source is absent",
                    )
                )
        for relative_path in sorted(REC012_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "REC012_SOURCE_MISSING",
                        "required generation-safe support/download lifecycle source is absent",
                    )
                )
        for relative_path in sorted(CFG003_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "CFG003_SOURCE_MISSING",
                        "required OverrideValue model/codec source is absent",
                    )
                )
        for relative_path in sorted(CFG004_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "CFG004_SOURCE_MISSING",
                        "required GlobalConfig/AppRule/FieldRule domain source is absent",
                    )
                )
        for relative_path in sorted(CFG005_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "CFG005_SOURCE_MISSING",
                        "required EffectiveProfile/resolver domain source is absent",
                    )
                )
        if not (main_java / CFG006_MIGRATION_PATH).is_file():
            violations.append(
                ArchitectureViolation(
                    f"main/java/{CFG006_MIGRATION_PATH}",
                    "CFG006_SOURCE_MISSING",
                    "required legacy AppSettings migration source is absent",
                )
            )
        if not (main_java / CFG007_MIGRATION_PATH).is_file():
            violations.append(
                ArchitectureViolation(
                    f"main/java/{CFG007_MIGRATION_PATH}",
                    "CFG007_SOURCE_MISSING",
                    "required legacy AppProfile migration source is absent",
                )
            )
        if not (main_java / CFG008_STORE_PATH).is_file():
            violations.append(
                ArchitectureViolation(
                    f"main/java/{CFG008_STORE_PATH}",
                    "CFG008_SOURCE_MISSING",
                    "required SecretRef store source is absent",
                )
            )
        for relative_path in sorted(CFG009_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "CFG009_SOURCE_MISSING",
                        "required App Picker model, catalog, dialog, or activity source is absent",
                    )
                )
        for relative_path in sorted(CFG010_REQUIRED_SOURCE_PATHS):
            if not (main_java / relative_path).is_file():
                violations.append(
                    ArchitectureViolation(
                        f"main/java/{relative_path}",
                        "CFG010_SOURCE_MISSING",
                        "required rule explanation UI model source is absent",
                    )
                )
        # Apply the exact legacy inventory once, against the source root that currently owns it.
        violations.extend(
            ArchitectureViolation(
                f"main/java/{item.relative_path}", item.rule, item.detail
            )
            for item in _legacy_inventory_violations(main_java)
        )

    if not roots:
        violations.append(
            ArchitectureViolation(
                "app/src",
                "PRODUCTION_SOURCE_ROOT",
                "no Android app production Java/Kotlin source root was found",
            )
        )
    return tuple(sorted(set(violations)))


def _legacy_inventory_violations(source_root: Path) -> tuple[ArchitectureViolation, ...]:
    """Apply the exact main-source writer inventory without duplicating other boundary findings."""

    observed_writers: dict[str, Counter[str]] = {}
    violations: list[ArchitectureViolation] = []
    for source_file in sorted((*source_root.rglob("*.java"), *source_root.rglob("*.kt"))):
        relative_path = source_file.relative_to(source_root).as_posix()
        source = source_file.read_text(encoding="utf-8")
        if source_file.suffix == ".java":
            try:
                source = _translate_java_unicode_escapes(source)
            except JavaUnicodeEscapeError:
                continue
        code = _canonicalize_qualified_names(
            _normalize_code_identifiers(_strip_lexical(source, strings=True))
        )
        editor_writes = Counter(EDITOR_WRITE_PATTERN.findall(code))
        editor_writes.update(EDITOR_WRITE_METHOD_REFERENCE_PATTERN.findall(code))
        if not editor_writes:
            continue
        observed_writers[relative_path] = editor_writes
        expected = LEGACY_EDITOR_WRITES.get(relative_path)
        if expected is not None and editor_writes != expected:
            violations.append(
                ArchitectureViolation(
                    relative_path,
                    "EDITOR_WRITE_RATCHET",
                    f"expected {_format_counter(expected)}; observed {_format_counter(editor_writes)}",
                )
            )
    for relative_path in sorted(set(LEGACY_EDITOR_WRITES) - set(observed_writers)):
        violations.append(
            ArchitectureViolation(
                relative_path,
                "EDITOR_WRITE_INVENTORY",
                "legacy writer disappeared; shrink the audited inventory in the same change",
            )
        )
    return tuple(sorted(set(violations)))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--android-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Android Gradle project root (default: parent of architecture-tests)",
    )
    args = parser.parse_args(argv)
    source_root = args.android_root / "app" / "src" / "main" / "java"
    if not source_root.is_dir():
        parser.error(f"production source root does not exist: {source_root}")
    violations = inspect_android_project(args.android_root)
    if violations:
        print("Android architecture contract violations:", file=sys.stderr)
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1
    print(f"Android architecture contracts passed: {args.android_root / 'app' / 'src'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

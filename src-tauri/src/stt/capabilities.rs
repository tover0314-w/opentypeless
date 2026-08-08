use crate::storage::AppConfig;
use serde::{Deserialize, Serialize};

pub const CAPABILITY_REGISTRY_VERSION: u32 = 1;
pub const CLIENT_FILE_BUFFER_BYTES: u64 = 24 * 1024 * 1024;
const CONSERVATIVE_FALLBACK_SECONDS: u32 = 30;
const MIN_CUSTOM_SECONDS: u32 = 30;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RecordingLimitMode {
    #[default]
    Auto,
    Custom,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SttTransport {
    FileUpload,
    Streaming,
    LocalBuffered,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum RecordingLimitSource {
    Provider,
    ClientBuffer,
    ProductSafety,
    UnknownUpstream,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SttRecordingCapability {
    pub registry_version: u32,
    pub provider_id: String,
    pub transport: SttTransport,
    pub recommended_max_seconds: u32,
    pub hard_max_seconds: u32,
    pub max_upload_bytes: Option<u64>,
    pub source: RecordingLimitSource,
    pub explanation_key: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedRecordingLimit {
    pub capability: SttRecordingCapability,
    pub mode: RecordingLimitMode,
    pub requested_seconds: u32,
    pub effective_max_seconds: u32,
}

fn capability(
    provider_id: &str,
    transport: SttTransport,
    recommended_max_seconds: u32,
    hard_max_seconds: u32,
    max_upload_bytes: Option<u64>,
    source: RecordingLimitSource,
    explanation_key: &str,
) -> SttRecordingCapability {
    SttRecordingCapability {
        registry_version: CAPABILITY_REGISTRY_VERSION,
        provider_id: provider_id.to_string(),
        transport,
        recommended_max_seconds,
        hard_max_seconds,
        max_upload_bytes,
        source,
        explanation_key: explanation_key.to_string(),
    }
}

fn provider_capability(provider_id: &str) -> SttRecordingCapability {
    match provider_id {
        "glm-asr" => capability(
            provider_id,
            SttTransport::FileUpload,
            30,
            30,
            Some(CLIENT_FILE_BUFFER_BYTES),
            RecordingLimitSource::Provider,
            "recordingLimits.reasons.providerDuration",
        ),
        "apple-speech" => capability(
            provider_id,
            SttTransport::LocalBuffered,
            60,
            60,
            None,
            RecordingLimitSource::Provider,
            "recordingLimits.reasons.appleSpeech",
        ),
        "groq-whisper" | "openai-whisper" | "siliconflow" => capability(
            provider_id,
            SttTransport::FileUpload,
            600,
            720,
            Some(CLIENT_FILE_BUFFER_BYTES),
            RecordingLimitSource::ClientBuffer,
            "recordingLimits.reasons.clientBuffer",
        ),
        "custom-whisper" => capability(
            provider_id,
            SttTransport::FileUpload,
            120,
            720,
            Some(CLIENT_FILE_BUFFER_BYTES),
            RecordingLimitSource::UnknownUpstream,
            "recordingLimits.reasons.unknownUpstream",
        ),
        "deepgram" | "assemblyai" | "volcengine-doubao" | "aliyun-qwen3-asr" => capability(
            provider_id,
            SttTransport::Streaming,
            600,
            3_600,
            None,
            RecordingLimitSource::ProductSafety,
            "recordingLimits.reasons.productSafety",
        ),
        _ => capability(
            provider_id,
            SttTransport::FileUpload,
            CONSERVATIVE_FALLBACK_SECONDS,
            CONSERVATIVE_FALLBACK_SECONDS,
            Some(CLIENT_FILE_BUFFER_BYTES),
            RecordingLimitSource::UnknownUpstream,
            "recordingLimits.reasons.unknownProvider",
        ),
    }
}

pub fn resolve_recording_limit(config: &AppConfig) -> ResolvedRecordingLimit {
    let capability = provider_capability(&config.stt_provider);
    let requested_seconds = match config.recording_limit_mode {
        RecordingLimitMode::Auto => capability.recommended_max_seconds,
        RecordingLimitMode::Custom => config.custom_recording_limit_seconds,
    };
    let effective_max_seconds = match config.recording_limit_mode {
        RecordingLimitMode::Auto => requested_seconds.min(capability.hard_max_seconds),
        RecordingLimitMode::Custom => requested_seconds
            .max(MIN_CUSTOM_SECONDS)
            .min(capability.hard_max_seconds),
    };

    ResolvedRecordingLimit {
        capability,
        mode: config.recording_limit_mode,
        requested_seconds,
        effective_max_seconds,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config(provider: &str, mode: RecordingLimitMode, custom_seconds: u32) -> AppConfig {
        AppConfig {
            stt_provider: provider.to_string(),
            recording_limit_mode: mode,
            custom_recording_limit_seconds: custom_seconds,
            ..AppConfig::default()
        }
    }

    #[test]
    fn registry_matches_the_reviewed_provider_matrix() {
        let cases = [
            (
                "glm-asr",
                SttTransport::FileUpload,
                30,
                30,
                RecordingLimitSource::Provider,
            ),
            (
                "apple-speech",
                SttTransport::LocalBuffered,
                60,
                60,
                RecordingLimitSource::Provider,
            ),
            (
                "groq-whisper",
                SttTransport::FileUpload,
                600,
                720,
                RecordingLimitSource::ClientBuffer,
            ),
            (
                "openai-whisper",
                SttTransport::FileUpload,
                600,
                720,
                RecordingLimitSource::ClientBuffer,
            ),
            (
                "siliconflow",
                SttTransport::FileUpload,
                600,
                720,
                RecordingLimitSource::ClientBuffer,
            ),
            (
                "custom-whisper",
                SttTransport::FileUpload,
                120,
                720,
                RecordingLimitSource::UnknownUpstream,
            ),
            (
                "deepgram",
                SttTransport::Streaming,
                600,
                3_600,
                RecordingLimitSource::ProductSafety,
            ),
            (
                "assemblyai",
                SttTransport::Streaming,
                600,
                3_600,
                RecordingLimitSource::ProductSafety,
            ),
            (
                "volcengine-doubao",
                SttTransport::Streaming,
                600,
                3_600,
                RecordingLimitSource::ProductSafety,
            ),
            (
                "aliyun-qwen3-asr",
                SttTransport::Streaming,
                600,
                3_600,
                RecordingLimitSource::ProductSafety,
            ),
        ];

        for (provider, transport, recommended, hard, source) in cases {
            let resolved =
                resolve_recording_limit(&config(provider, RecordingLimitMode::Auto, 600));
            assert_eq!(resolved.capability.transport, transport, "{provider}");
            assert_eq!(
                resolved.capability.recommended_max_seconds, recommended,
                "{provider}"
            );
            assert_eq!(resolved.capability.hard_max_seconds, hard, "{provider}");
            assert_eq!(resolved.capability.source, source, "{provider}");
            assert_eq!(resolved.effective_max_seconds, recommended, "{provider}");
        }
    }

    #[test]
    fn file_upload_caps_use_the_client_buffer() {
        for provider in [
            "groq-whisper",
            "openai-whisper",
            "siliconflow",
            "custom-whisper",
        ] {
            let resolved =
                resolve_recording_limit(&config(provider, RecordingLimitMode::Auto, 600));
            assert_eq!(
                resolved.capability.max_upload_bytes,
                Some(CLIENT_FILE_BUFFER_BYTES)
            );
            assert_eq!(resolved.capability.hard_max_seconds, 720);
        }
    }

    #[test]
    fn custom_mode_clamps_to_the_safe_range() {
        let too_low =
            resolve_recording_limit(&config("groq-whisper", RecordingLimitMode::Custom, 1));
        let too_high =
            resolve_recording_limit(&config("groq-whisper", RecordingLimitMode::Custom, 9_999));
        assert_eq!(too_low.effective_max_seconds, 30);
        assert_eq!(too_high.effective_max_seconds, 720);
    }

    #[test]
    fn unknown_provider_uses_a_conservative_fallback() {
        let resolved =
            resolve_recording_limit(&config("future-provider", RecordingLimitMode::Auto, 600));
        assert_eq!(resolved.capability.transport, SttTransport::FileUpload);
        assert_eq!(
            resolved.capability.source,
            RecordingLimitSource::UnknownUpstream
        );
        assert_eq!(resolved.effective_max_seconds, 30);
    }
}

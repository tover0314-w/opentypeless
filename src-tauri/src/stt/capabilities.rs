use crate::storage::AppConfig;
use serde::{Deserialize, Serialize};

pub const CAPABILITY_REGISTRY_VERSION: u32 = 1;
pub const MANAGED_CAPABILITY_TTL_SECONDS: i64 = 24 * 60 * 60;
pub const CLIENT_FILE_BUFFER_BYTES: u64 = 24 * 1024 * 1024;
pub const MANAGED_AUDIO_BYTES: u64 = 4_000_000;
const MANAGED_MULTIPART_HEADROOM_BYTES: u64 = 200_000;
const MANAGED_LOCAL_CEILING_SECONDS: u32 = 600;
const MANAGED_FALLBACK_SECONDS: u32 = 30;
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
    ManagedUpload,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum RecordingLimitSource {
    Provider,
    ManagedProduct,
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

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManagedSttFormat {
    pub mime_type: String,
    pub max_audio_bytes: u64,
    pub preferred_client_switch_bytes: Option<u64>,
    pub bitrate_bits_per_second: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManagedSttCapability {
    pub version: u32,
    pub max_recording_seconds: u32,
    pub max_multipart_bytes: u64,
    pub formats: Vec<ManagedSttFormat>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManagedSttCapabilityState {
    pub user_id: String,
    pub capability: ManagedSttCapability,
    pub server_generated_at: String,
    pub received_at_unix_seconds: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthenticatedAccountSnapshot {
    pub schema_version: u32,
    pub user_id: String,
    pub managed_stt_capabilities: Option<ManagedSttCapability>,
    pub generated_at: String,
}

pub fn managed_state_from_authenticated_snapshot(
    snapshot: AuthenticatedAccountSnapshot,
    expected_user_id: &str,
    received_at_unix_seconds: i64,
) -> Result<Option<ManagedSttCapabilityState>, String> {
    if expected_user_id.trim().is_empty() || snapshot.user_id != expected_user_id {
        return Err("Authenticated account snapshot user does not match".to_string());
    }
    if snapshot.schema_version != 1 {
        return Err("Unsupported account snapshot schema".to_string());
    }
    let generated_at = chrono::DateTime::parse_from_rfc3339(&snapshot.generated_at)
        .map_err(|_| "Account snapshot generatedAt is invalid".to_string())?;
    if generated_at.timestamp() > received_at_unix_seconds.saturating_add(5 * 60) {
        return Err("Account snapshot generatedAt is in the future".to_string());
    }

    Ok(snapshot
        .managed_stt_capabilities
        .map(|capability| ManagedSttCapabilityState {
            user_id: snapshot.user_id,
            capability,
            server_generated_at: snapshot.generated_at,
            received_at_unix_seconds,
        }))
}

impl ManagedSttCapabilityState {
    fn is_fresh(&self, now_unix_seconds: i64) -> bool {
        let Ok(generated_at) = chrono::DateTime::parse_from_rfc3339(&self.server_generated_at)
        else {
            return false;
        };
        let generated_at = generated_at.timestamp();
        let newest_allowed = now_unix_seconds.saturating_add(5 * 60);
        let oldest_allowed = now_unix_seconds.saturating_sub(MANAGED_CAPABILITY_TTL_SECONDS);
        let received_is_fresh = self.received_at_unix_seconds <= newest_allowed
            && self.received_at_unix_seconds >= oldest_allowed;
        let generated_is_fresh = generated_at <= newest_allowed && generated_at >= oldest_allowed;
        received_is_fresh && generated_is_fresh
    }
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

fn static_provider_capability(provider_id: &str) -> SttRecordingCapability {
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
            MANAGED_FALLBACK_SECONDS,
            MANAGED_FALLBACK_SECONDS,
            Some(CLIENT_FILE_BUFFER_BYTES),
            RecordingLimitSource::UnknownUpstream,
            "recordingLimits.reasons.unknownProvider",
        ),
    }
}

struct CompatibleManagedLimits {
    hard_max_seconds: u32,
    max_audio_bytes: u64,
    preferred_wav_max_bytes: u64,
}

fn compatible_managed_limits(
    state: &ManagedSttCapabilityState,
    now_unix_seconds: i64,
) -> Option<CompatibleManagedLimits> {
    if state.capability.version != 2 || !state.is_fresh(now_unix_seconds) {
        return None;
    }
    let wav = state
        .capability
        .formats
        .iter()
        .find(|format| format.mime_type.eq_ignore_ascii_case("audio/wav"))?;
    let opus = state.capability.formats.iter().find(|format| {
        format
            .mime_type
            .eq_ignore_ascii_case("audio/ogg; codecs=opus")
            && format.bitrate_bits_per_second == Some(48_000)
    })?;
    if wav.max_audio_bytes == 0
        || opus.max_audio_bytes == 0
        || state.capability.max_multipart_bytes <= MANAGED_MULTIPART_HEADROOM_BYTES
    {
        return None;
    }

    let multipart_audio_budget = state
        .capability
        .max_multipart_bytes
        .saturating_sub(MANAGED_MULTIPART_HEADROOM_BYTES);
    let max_audio_bytes = opus
        .max_audio_bytes
        .min(multipart_audio_budget)
        .min(MANAGED_AUDIO_BYTES);
    let byte_limited_seconds = max_audio_bytes
        .saturating_mul(8)
        .checked_div(48_000)?
        .min(u64::from(u32::MAX)) as u32;
    let hard_max_seconds = state
        .capability
        .max_recording_seconds
        .min(MANAGED_LOCAL_CEILING_SECONDS)
        .min(byte_limited_seconds);
    let preferred_wav_max_bytes = wav
        .preferred_client_switch_bytes?
        .min(wav.max_audio_bytes)
        .min(max_audio_bytes);
    (hard_max_seconds > 0 && preferred_wav_max_bytes > 44).then_some(CompatibleManagedLimits {
        hard_max_seconds,
        max_audio_bytes,
        preferred_wav_max_bytes,
    })
}

pub fn managed_audio_encoding_config(
    config: &AppConfig,
    now_unix_seconds: i64,
) -> Option<super::managed_audio::ManagedAudioEncodingConfig> {
    if config.stt_provider != "cloud" {
        return None;
    }
    let limits = compatible_managed_limits(
        config.managed_stt_capability_state.as_ref()?,
        now_unix_seconds,
    )?;
    Some(super::managed_audio::ManagedAudioEncodingConfig {
        preferred_wav_max_bytes: limits.preferred_wav_max_bytes,
        max_audio_bytes: limits.max_audio_bytes,
        bitrate_bits_per_second: 48_000,
    })
}

fn managed_capability(
    state: Option<&ManagedSttCapabilityState>,
    now_unix_seconds: i64,
) -> SttRecordingCapability {
    match state.and_then(|state| compatible_managed_limits(state, now_unix_seconds)) {
        Some(limits) => capability(
            "cloud",
            SttTransport::ManagedUpload,
            limits.hard_max_seconds,
            limits.hard_max_seconds,
            Some(limits.max_audio_bytes),
            RecordingLimitSource::ManagedProduct,
            "recordingLimits.reasons.managedCapability",
        ),
        None => capability(
            "cloud",
            SttTransport::ManagedUpload,
            MANAGED_FALLBACK_SECONDS,
            MANAGED_FALLBACK_SECONDS,
            Some(MANAGED_AUDIO_BYTES),
            RecordingLimitSource::ManagedProduct,
            "recordingLimits.reasons.managedFallback",
        ),
    }
}

pub fn resolve_recording_limit(
    config: &AppConfig,
    managed_state: Option<&ManagedSttCapabilityState>,
    now_unix_seconds: i64,
) -> ResolvedRecordingLimit {
    let managed_state = managed_state.or(config.managed_stt_capability_state.as_ref());
    let capability = if config.stt_provider == "cloud" {
        managed_capability(managed_state, now_unix_seconds)
    } else {
        static_provider_capability(&config.stt_provider)
    };
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
    use crate::storage::AppConfig;

    const NOW: i64 = 1_753_171_200;

    fn config(provider: &str, mode: RecordingLimitMode, custom_seconds: u32) -> AppConfig {
        AppConfig {
            stt_provider: provider.to_string(),
            recording_limit_mode: mode,
            custom_recording_limit_seconds: custom_seconds,
            ..AppConfig::default()
        }
    }

    fn managed_state(
        version: u32,
        max_recording_seconds: u32,
        received_at_unix_seconds: i64,
    ) -> ManagedSttCapabilityState {
        ManagedSttCapabilityState {
            user_id: "user-1".to_string(),
            capability: ManagedSttCapability {
                version,
                max_recording_seconds,
                max_multipart_bytes: 4_200_000,
                formats: vec![
                    ManagedSttFormat {
                        mime_type: "audio/wav".to_string(),
                        max_audio_bytes: 4_000_000,
                        preferred_client_switch_bytes: Some(3_500_000),
                        bitrate_bits_per_second: None,
                    },
                    ManagedSttFormat {
                        mime_type: "audio/ogg; codecs=opus".to_string(),
                        max_audio_bytes: 4_000_000,
                        preferred_client_switch_bytes: None,
                        bitrate_bits_per_second: Some(48_000),
                    },
                ],
            },
            server_generated_at: "2025-07-22T08:00:00Z".to_string(),
            received_at_unix_seconds,
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
                3600,
                RecordingLimitSource::ProductSafety,
            ),
            (
                "assemblyai",
                SttTransport::Streaming,
                600,
                3600,
                RecordingLimitSource::ProductSafety,
            ),
            (
                "volcengine-doubao",
                SttTransport::Streaming,
                600,
                3600,
                RecordingLimitSource::ProductSafety,
            ),
            (
                "aliyun-qwen3-asr",
                SttTransport::Streaming,
                600,
                3600,
                RecordingLimitSource::ProductSafety,
            ),
        ];

        for (provider, transport, recommended, hard, source) in cases {
            let resolved = resolve_recording_limit(
                &config(provider, RecordingLimitMode::Auto, 600),
                None,
                NOW,
            );
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
    fn file_upload_caps_are_bounded_by_the_existing_client_buffer() {
        for provider in [
            "groq-whisper",
            "openai-whisper",
            "siliconflow",
            "custom-whisper",
        ] {
            let resolved = resolve_recording_limit(
                &config(provider, RecordingLimitMode::Auto, 600),
                None,
                NOW,
            );
            assert_eq!(resolved.capability.max_upload_bytes, Some(24 * 1024 * 1024));
            assert_eq!(resolved.capability.hard_max_seconds, 720);
        }
    }

    #[test]
    fn custom_mode_clamps_to_the_safe_range_without_losing_provider_policy() {
        let too_low = resolve_recording_limit(
            &config("groq-whisper", RecordingLimitMode::Custom, 1),
            None,
            NOW,
        );
        let too_high = resolve_recording_limit(
            &config("groq-whisper", RecordingLimitMode::Custom, 9_999),
            None,
            NOW,
        );

        assert_eq!(too_low.effective_max_seconds, 30);
        assert_eq!(too_high.effective_max_seconds, 720);
    }

    #[test]
    fn unknown_provider_uses_a_conservative_fallback() {
        let resolved = resolve_recording_limit(
            &config("future-provider", RecordingLimitMode::Auto, 600),
            None,
            NOW,
        );

        assert_eq!(resolved.capability.transport, SttTransport::FileUpload);
        assert_eq!(
            resolved.capability.source,
            RecordingLimitSource::UnknownUpstream
        );
        assert_eq!(resolved.capability.hard_max_seconds, 30);
        assert_eq!(resolved.effective_max_seconds, 30);
    }

    #[test]
    fn cloud_keeps_the_historical_fallback_without_fresh_compatible_v2() {
        let cloud = config("cloud", RecordingLimitMode::Auto, 600);
        let version_one = managed_state(1, 600, NOW);
        let stale = managed_state(2, 600, NOW - MANAGED_CAPABILITY_TTL_SECONDS - 1);

        assert_eq!(
            resolve_recording_limit(&cloud, None, NOW).effective_max_seconds,
            30
        );
        assert_eq!(
            resolve_recording_limit(&cloud, Some(&version_one), NOW).effective_max_seconds,
            30
        );
        assert_eq!(
            resolve_recording_limit(&cloud, Some(&stale), NOW).effective_max_seconds,
            30
        );
    }

    #[test]
    fn fresh_v2_enables_cloud_but_can_only_narrow_the_local_ceiling() {
        let cloud = config("cloud", RecordingLimitMode::Auto, 600);
        let exact = managed_state(2, 600, NOW);
        let narrower = managed_state(2, 300, NOW);
        let wider = managed_state(2, 1_200, NOW);

        let enabled = resolve_recording_limit(&cloud, Some(&exact), NOW);
        assert_eq!(enabled.capability.transport, SttTransport::ManagedUpload);
        assert_eq!(enabled.capability.max_upload_bytes, Some(4_000_000));
        assert_eq!(enabled.effective_max_seconds, 600);
        assert_eq!(
            resolve_recording_limit(&cloud, Some(&narrower), NOW).effective_max_seconds,
            300
        );
        assert_eq!(
            resolve_recording_limit(&cloud, Some(&wider), NOW).effective_max_seconds,
            600
        );
    }

    #[test]
    fn managed_encoder_config_is_available_only_for_fresh_cloud_v2() {
        let state = managed_state(2, 600, NOW);
        let mut cloud = config("cloud", RecordingLimitMode::Auto, 600);
        cloud.managed_stt_capability_state = Some(state.clone());

        let encoding = managed_audio_encoding_config(&cloud, NOW).unwrap();
        assert_eq!(encoding.preferred_wav_max_bytes, 3_500_000);
        assert_eq!(encoding.max_audio_bytes, 4_000_000);
        assert_eq!(encoding.bitrate_bits_per_second, 48_000);

        cloud.stt_provider = "groq-whisper".to_string();
        assert_eq!(managed_audio_encoding_config(&cloud, NOW), None);

        cloud.stt_provider = "cloud".to_string();
        cloud.managed_stt_capability_state = Some(managed_state(
            2,
            600,
            NOW - MANAGED_CAPABILITY_TTL_SECONDS - 1,
        ));
        assert_eq!(managed_audio_encoding_config(&cloud, NOW), None);
    }

    #[test]
    fn authenticated_snapshot_is_the_only_cache_input_and_is_bound_to_the_user() {
        let snapshot = AuthenticatedAccountSnapshot {
            schema_version: 1,
            user_id: "user-1".to_string(),
            managed_stt_capabilities: Some(managed_state(2, 600, NOW).capability),
            generated_at: "2025-07-22T08:00:00Z".to_string(),
        };

        let state = managed_state_from_authenticated_snapshot(snapshot.clone(), "user-1", NOW)
            .unwrap()
            .unwrap();
        assert_eq!(state.user_id, "user-1");
        assert!(managed_state_from_authenticated_snapshot(snapshot, "user-2", NOW).is_err());
    }

    #[test]
    fn authenticated_snapshot_without_capability_withdraws_the_cache() {
        let snapshot = AuthenticatedAccountSnapshot {
            schema_version: 1,
            user_id: "user-1".to_string(),
            managed_stt_capabilities: None,
            generated_at: "2025-07-22T08:00:00Z".to_string(),
        };

        assert_eq!(
            managed_state_from_authenticated_snapshot(snapshot, "user-1", NOW).unwrap(),
            None
        );
    }
}

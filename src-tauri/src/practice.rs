use std::sync::Mutex;
use std::time::Duration;
use tauri::Manager;
use tokio::sync::watch;

const MAX_TEXT_BYTES: usize = 32_768;
const MAX_AUDIO_BYTES: usize = 16000 * 2 * 60;
const SESSION_TIMEOUT: Duration = Duration::from_secs(240);

#[derive(Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PracticeSnapshot {
    pub session: String,
    pub phase: String,
    pub raw_text: String,
    pub polish_preview: String,
    pub result: String,
    pub level: f32,
    pub error: Option<crate::error::UserError>,
}

struct Session {
    snapshot: PracticeSnapshot,
    control: watch::Sender<u8>,
}

#[derive(Default)]
pub struct PracticeState {
    current: Mutex<Option<Session>>,
    task: Mutex<Option<String>>,
}

impl PracticeState {
    pub(crate) fn is_busy(&self) -> bool {
        if self
            .task
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .is_some()
        {
            return true;
        }
        self.current
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            .is_some_and(|session| is_active(&session.snapshot.phase))
    }

    fn claim_task(&self, session: &str) {
        *self.task.lock().unwrap_or_else(|error| error.into_inner()) = Some(session.into());
    }

    fn release_task(&self, expected: &str) {
        let mut task = self.task.lock().unwrap_or_else(|error| error.into_inner());
        if task.as_deref() == Some(expected) {
            *task = None;
        }
    }

    fn finish_dropped_task(&self, expected: &str) {
        self.finish(
            expected,
            Err(crate::error::AppError::Config(
                "Practice task ended before completion".into(),
            )),
        );
        self.release_task(expected);
    }

    fn reserve(&self) -> Result<String, String> {
        let mut current = self
            .current
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if current
            .as_ref()
            .is_some_and(|session| is_active(&session.snapshot.phase))
        {
            return Err("practice_busy".into());
        }
        let session = uuid::Uuid::new_v4().to_string();
        *current = Some(Session {
            snapshot: PracticeSnapshot {
                session: session.clone(),
                phase: "preparing".into(),
                raw_text: String::new(),
                polish_preview: String::new(),
                result: String::new(),
                level: 0.0,
                error: None,
            },
            control: watch::channel(0).0,
        });
        Ok(session)
    }

    pub fn snapshot(&self, expected: &str) -> Result<PracticeSnapshot, String> {
        self.current
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            .filter(|session| session.snapshot.session == expected)
            .map(|session| session.snapshot.clone())
            .ok_or_else(|| "practice_stopped".into())
    }

    fn update(&self, expected: &str, apply: impl FnOnce(&mut PracticeSnapshot)) -> bool {
        let mut current = self
            .current
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let Some(session) = current.as_mut().filter(|session| {
            session.snapshot.session == expected && is_active(&session.snapshot.phase)
        }) else {
            return false;
        };
        apply(&mut session.snapshot);
        true
    }

    fn append_polish_preview(&self, expected: &str, chunk: &str) {
        self.update(expected, |snapshot| {
            if snapshot.phase == "polishing"
                && snapshot.polish_preview.len().saturating_add(chunk.len()) <= MAX_TEXT_BYTES
            {
                snapshot.polish_preview.push_str(chunk);
            }
        });
    }

    fn finish(&self, expected: &str, outcome: Result<String, crate::error::AppError>) {
        self.finish_with_notification(expected, outcome, |_| {});
    }

    fn finish_with_notification(
        &self,
        expected: &str,
        outcome: Result<String, crate::error::AppError>,
        notify: impl FnOnce(&'static str),
    ) {
        self.update(expected, |snapshot| {
            snapshot.polish_preview.clear();
            snapshot.level = 0.0;
            match outcome {
                Ok(result) => {
                    snapshot.phase = "complete".into();
                    snapshot.result = result;
                    snapshot.error = None;
                }
                Err(error) => {
                    if matches!(&error, crate::error::AppError::CloudSessionInvalid) {
                        notify("auth:session-invalid-owned");
                    }
                    let polish_setup_failure = matches!(
                        &error,
                        crate::error::AppError::Config(message)
                            if matches!(
                                message.as_str(),
                                "Polish provider and model are not configured"
                                    | "Polish endpoint is not a valid URL"
                                    | "Polish endpoint must use HTTP or HTTPS"
                                    | "Polish credential is not configured"
                                    | "Polish credential could not be read"
                            )
                    );
                    let mut user_error =
                        if matches!(&error, crate::error::AppError::CloudSessionInvalid)
                            || matches!(&error, crate::error::AppError::Config(message)
                                if message == "Practice cloud session token is missing")
                        {
                            crate::error::UserError {
                                code: "authentication_required".into(),
                                details: None,
                                retry_count: 0,
                            }
                        } else if matches!(&error, crate::error::AppError::Config(message)
                            if message == "STT credential is not configured")
                        {
                            crate::error::UserError {
                                code: "stt_not_configured".into(),
                                details: None,
                                retry_count: 0,
                            }
                        } else if snapshot.phase == "polishing" || polish_setup_failure {
                            crate::pipeline::llm_polish_user_error(&error)
                        } else if matches!(&error, crate::error::AppError::Config(message)
                            if message == "No speech was recognized")
                        {
                            crate::error::UserError {
                                code: "stt_no_speech_detected".into(),
                                details: None,
                                retry_count: 0,
                            }
                        } else {
                            error.to_user_error()
                        };
                    if polish_setup_failure {
                        user_error.details = None;
                    }
                    snapshot.phase = "error".into();
                    snapshot.result.clear();
                    snapshot.error = Some(user_error);
                }
            }
        });
    }

    pub fn stop(&self, expected: &str) -> Result<(), String> {
        let current = self
            .current
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let session = current
            .as_ref()
            .filter(|session| session.snapshot.session == expected)
            .ok_or("practice_stopped")?;
        if is_active(&session.snapshot.phase) && *session.control.borrow() != 2 {
            session.control.send_replace(1);
        }
        Ok(())
    }

    pub fn cancel(&self, expected: &str) -> Result<(), String> {
        let mut current = self
            .current
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let session = current
            .as_mut()
            .filter(|session| session.snapshot.session == expected)
            .ok_or("practice_stopped")?;
        cancel_session(session);
        Ok(())
    }

    pub fn cancel_current(&self) {
        let mut current = self
            .current
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if let Some(session) = current.as_mut() {
            cancel_session(session);
        }
    }

    fn subscribe(&self, expected: &str) -> Result<watch::Receiver<u8>, String> {
        self.current
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            .filter(|session| session.snapshot.session == expected)
            .map(|session| session.control.subscribe())
            .ok_or_else(|| "practice_stopped".into())
    }
}

fn cancel_session(session: &mut Session) {
    session.control.send_replace(2);
    session.snapshot.phase = "cancelled".into();
    session.snapshot.raw_text.clear();
    session.snapshot.polish_preview.clear();
    session.snapshot.result.clear();
    session.snapshot.level = 0.0;
    session.snapshot.error = None;
}

pub fn cancel_for_window_event(app: &tauri::AppHandle, label: &str, event: &tauri::WindowEvent) {
    if label == "main"
        && matches!(
            event,
            tauri::WindowEvent::CloseRequested { .. } | tauri::WindowEvent::Destroyed
        )
    {
        if let Some(state) = app.try_state::<PracticeState>() {
            state.cancel_current();
        }
    }
}

pub fn cancel_on_exit(app: &tauri::AppHandle) {
    if let Some(state) = app.try_state::<PracticeState>() {
        state.cancel_current();
    }
}

fn is_active(phase: &str) -> bool {
    matches!(
        phase,
        "preparing" | "recording" | "transcribing" | "polishing"
    )
}

async fn wait_control(receiver: &mut watch::Receiver<u8>, minimum: u8) {
    loop {
        if *receiver.borrow_and_update() >= minimum {
            return;
        }
        if receiver.changed().await.is_err() {
            return;
        }
    }
}

fn validate_text(text: &str) -> Result<String, crate::error::AppError> {
    if text.len() > MAX_TEXT_BYTES {
        return Err(crate::error::AppError::Config(
            "Practice result exceeds text limit".into(),
        ));
    }
    if text.trim().is_empty() {
        return Err(crate::error::AppError::Config(
            "No speech was recognized".into(),
        ));
    }
    Ok(text.trim().to_string())
}

async fn collect_transcript(
    provider: &mut dyn crate::stt::SttProvider,
    audio: &mut tokio::sync::mpsc::Receiver<Vec<u8>>,
    check_capture: impl Fn() -> Result<(), crate::error::AppError>,
    publish: impl Fn(&str),
) -> Result<String, crate::error::AppError> {
    let mut total = 0usize;
    let mut raw = String::new();
    loop {
        tokio::select! {
            chunk = audio.recv() => {
                check_capture()?;
                let Some(chunk) = chunk else { break; };
                total = total.saturating_add(chunk.len());
                if total > MAX_AUDIO_BYTES {
                    return Err(crate::error::AppError::Config("Practice audio exceeds limit".into()));
                }
                provider.send_audio(&chunk).await?;
            }
            event = provider.recv_transcript() => {
                match event? {
                    Some(crate::stt::TranscriptEvent::Partial { text }) => {
                        let mut preview = raw.clone();
                        append_text(&mut preview, &text)?;
                        publish(&preview);
                    }
                    Some(crate::stt::TranscriptEvent::Final { text, .. }) => {
                        append_text(&mut raw, &text)?;
                        publish(&raw);
                    }
                    Some(crate::stt::TranscriptEvent::Error { message }) => {
                        return Err(crate::error::AppError::Config(message));
                    }
                    None => tokio::time::sleep(Duration::from_millis(10)).await,
                    _ => {}
                }
            }
        }
    }
    if let Some(text) = provider.disconnect().await? {
        append_text(&mut raw, &text)?;
    }
    validate_text(&raw)
}

fn append_text(raw: &mut String, text: &str) -> Result<(), crate::error::AppError> {
    let separator = usize::from(!raw.is_empty() && !text.is_empty());
    if raw
        .len()
        .saturating_add(text.len())
        .saturating_add(separator)
        > MAX_TEXT_BYTES
    {
        return Err(crate::error::AppError::Config(
            "Practice result exceeds text limit".into(),
        ));
    }
    if separator != 0 {
        raw.push(' ');
    }
    raw.push_str(text);
    Ok(())
}

fn polish_request(
    text: &str,
    config: &crate::storage::AppConfig,
    session: &str,
) -> crate::llm::PolishRequest {
    crate::llm::PolishRequest {
        language_variants: config.language_variants.clone(),
        raw_text: text.into(),
        context: crate::app_detector::types::ContextProfile::general_native().summary(),
        dictionary: Vec::new(),
        correction_rules: Vec::new(),
        polish_style: config.polish_style.clone(),
        mapped_scene_prompt: String::new(),
        active_scene_prompt: String::new(),
        polish_custom_prompt: config.polish_custom_prompt.clone(),
        translate_enabled: false,
        target_lang: String::new(),
        selected_text: None,
        operation_id: Some(session.into()),
        voice_intent: crate::voice_intent::VoiceIntent {
            kind: crate::voice_intent::VoiceIntentKind::DictateInsert,
            placement: crate::voice_intent::VoiceOutputPlacement::InsertAtCursor,
            confidence: 1.0,
            search_provider: None,
            payload: None,
            grammar_locale: None,
            fallback_reason: None,
        },
    }
}

fn task_cleanup(
    guard: tokio::sync::OwnedMutexGuard<()>,
    finish: impl FnOnce(),
) -> crate::voice_operation::OnDrop<impl FnOnce()> {
    crate::voice_operation::OnDrop::new(move || {
        drop(guard);
        finish();
    })
}

pub fn start(app: tauri::AppHandle) -> Result<String, String> {
    let pipeline = app.state::<crate::pipeline::PipelineHandle>();
    let guard = pipeline.try_voice_start_guard()?;
    if pipeline.current_state() != crate::pipeline::PipelineState::Idle
        || app
            .state::<crate::commands::ask::AskDictationState>()
            .is_busy()
    {
        return Err("practice_busy".into());
    }
    let state = app.state::<PracticeState>();
    let session = state.reserve()?;
    let control = state.subscribe(&session)?;
    state.claim_task(&session);
    crate::edit_learning::cancel(&app);
    crate::audio::preview::stop_for_voice_operation(&app);
    let task_session = session.clone();
    let cloud_session = app.state::<crate::SessionTokenStore>().snapshot();
    let cleanup_app = app.clone();
    let cleanup_session = task_session.clone();
    let cleanup = task_cleanup(guard, move || {
        cleanup_app
            .state::<PracticeState>()
            .finish_dropped_task(&cleanup_session);
    });
    tauri::async_runtime::spawn(async move {
        let _cleanup = cleanup;
        let mut cancellation = control.clone();
        let outcome = tokio::select! {
            biased;
            _ = wait_control(&mut cancellation, 2) => None,
            result = tokio::time::timeout(SESSION_TIMEOUT, run(&app, &task_session, control, &cloud_session)) => {
                Some(result.unwrap_or(Err(crate::error::AppError::Timeout(SESSION_TIMEOUT))))
            }
        };
        let state = app.state::<PracticeState>();
        if let Some(outcome) = outcome {
            state.finish_with_notification(&task_session, outcome, |event| {
                let _ = tauri::Emitter::emit(
                    &app,
                    event,
                    serde_json::json!({ "sessionEpoch": cloud_session.session_epoch }),
                );
            });
        }
    });
    Ok(session)
}

async fn run(
    app: &tauri::AppHandle,
    session: &str,
    mut control: watch::Receiver<u8>,
    cloud_session: &crate::CloudSessionSnapshot,
) -> Result<String, crate::error::AppError> {
    use crate::credentials::{
        resolve_llm_config_secret, resolve_stt_config_secret, SystemCredentialVault,
    };
    let config = app
        .state::<crate::storage::ConfigManager>()
        .load()
        .await
        .map_err(|error| crate::error::AppError::Config(error.to_string()))?;
    validate_polish_configuration(&config)?;
    let token = cloud_session.token.clone().unwrap_or_default();
    let stt_key = if config.stt_provider == "cloud" {
        token.clone()
    } else {
        resolve_stt_config_secret(&config, &SystemCredentialVault)
            .map_err(|error| crate::error::AppError::Config(error.to_string()))?
    };
    let llm_key = if !config.polish_enabled {
        String::new()
    } else if config.llm_provider == "cloud" {
        token
    } else {
        resolve_llm_config_secret(&config, &SystemCredentialVault).map_err(|_| {
            crate::error::AppError::Config("Polish credential could not be read".into())
        })?
    };
    validate_practice_credentials(&config, &stt_key, &llm_key)?;
    let custom = if config.stt_provider == crate::stt::config::CUSTOM_WHISPER_PROVIDER {
        Some(
            crate::stt::config::build_custom_whisper_config(
                &config.stt_custom_base_url,
                &config.stt_custom_model,
            )
            .map_err(crate::error::AppError::Config)?,
        )
    } else {
        None
    };
    let client = app.state::<reqwest::Client>().inner().clone();
    let mut provider =
        crate::stt::create_provider(&config.stt_provider, custom, Some(client.clone()))?;
    let stt_config = crate::stt::SttConfig {
        api_key: stt_key,
        language: (config.stt_language != "multi").then(|| config.stt_language.clone()),
        smart_format: true,
        sample_rate: 16000,
        resource_id: (config.stt_provider == crate::stt::volcengine::VOLCENGINE_DOUBAO_PROVIDER)
            .then(|| config.stt_volcengine_resource_id.clone()),
        operation_id: Some(session.into()),
        managed_audio: crate::stt::capabilities::managed_audio_encoding_config(
            &config,
            chrono::Utc::now().timestamp(),
        ),
        provider_region: (config.stt_provider
            == crate::stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_PROVIDER)
            .then(|| config.stt_aliyun_qwen_region.clone()),
    };
    let (mut capture, mut audio) = crate::audio::AudioCaptureHandle::start(
        crate::audio::AudioConfig::for_device(&config.microphone_device),
    )
    .map_err(|error| crate::error::AppError::Config(error.to_string()))?;
    tokio::time::timeout(crate::audio::STARTUP_TIMEOUT, async {
        capture
            .wait_until_ready()
            .await
            .map_err(|error| crate::error::AppError::Config(error.to_string()))?;
        provider.connect(&stt_config).await
    })
    .await
    .map_err(|_| crate::error::AppError::Timeout(crate::audio::STARTUP_TIMEOUT))??;
    let state = app.state::<PracticeState>();
    state.update(session, |snapshot| snapshot.phase = "recording".into());
    let status = capture.status();
    let mut meter = tokio::time::interval(Duration::from_millis(100));
    let limit = crate::stt::capabilities::resolve_recording_limit(
        &config,
        None,
        chrono::Utc::now().timestamp(),
    )
    .effective_max_seconds
    .min(provider.recording_limit_override_seconds().unwrap_or(60))
    .min(60);
    let recording_timeout = tokio::time::sleep(Duration::from_secs(u64::from(limit)));
    tokio::pin!(recording_timeout);
    let collection = collect_transcript(
        provider.as_mut(),
        &mut audio,
        || status.error().map_or(Ok(()), Err),
        |raw| {
            state.update(session, |snapshot| snapshot.raw_text = raw.into());
        },
    );
    tokio::pin!(collection);
    let mut stopping = false;
    let raw = loop {
        tokio::select! {
            biased;
            _ = wait_control(&mut control, 1), if !stopping => {
                capture.stop();
                stopping = true;
                state.update(session, |snapshot| { snapshot.phase = "transcribing".into(); snapshot.level = 0.0; });
            }
            _ = &mut recording_timeout, if !stopping => {
                capture.stop();
                stopping = true;
                state.update(session, |snapshot| snapshot.phase = "transcribing".into());
            }
            result = &mut collection => break result?,
            _ = meter.tick() => {
                let level = capture.get_volume().clamp(0.0, 1.0);
                state.update(session, |snapshot| snapshot.level = level);
            }
        }
    };
    capture.stop();
    state.update(session, |snapshot| {
        snapshot.phase = "transcribing".into();
        snapshot.level = 0.0;
    });
    state.update(session, |snapshot| snapshot.raw_text = raw.clone());
    if !config.polish_enabled {
        return Ok(raw);
    }
    state.update(session, |snapshot| snapshot.phase = "polishing".into());
    let callback_app = app.clone();
    let callback_session = session.to_string();
    let on_chunk: crate::llm::ChunkCallback = Box::new(move |chunk| {
        callback_app
            .state::<PracticeState>()
            .append_polish_preview(&callback_session, chunk);
    });
    let provider = crate::llm::create_provider(&config.llm_provider, Some(client));
    polish_transcript(
        provider.as_ref(),
        &crate::llm::LlmConfig {
            provider: config.llm_provider.clone(),
            api_key: llm_key,
            model: config.llm_model.clone(),
            base_url: config.llm_base_url.clone(),
            max_tokens: 4096,
            temperature: 0.3,
        },
        &polish_request(&raw, &config, session),
        &on_chunk,
    )
    .await
}

async fn polish_transcript(
    provider: &dyn crate::llm::LlmProvider,
    config: &crate::llm::LlmConfig,
    request: &crate::llm::PolishRequest,
    on_chunk: &crate::llm::ChunkCallback,
) -> Result<String, crate::error::AppError> {
    let result = provider.polish(config, request, Some(on_chunk)).await?;
    validate_text(&result.polished_text)
}

fn validate_practice_credentials(
    config: &crate::storage::AppConfig,
    stt_key: &str,
    llm_key: &str,
) -> Result<(), crate::error::AppError> {
    if (config.stt_provider == "cloud" && stt_key.trim().is_empty())
        || (config.polish_enabled && config.llm_provider == "cloud" && llm_key.trim().is_empty())
    {
        return Err(crate::error::AppError::Config(
            "Practice cloud session token is missing".into(),
        ));
    }
    if crate::stt::config::stt_provider_requires_api_key(&config.stt_provider)
        && stt_key.trim().is_empty()
    {
        return Err(crate::error::AppError::Config(
            "STT credential is not configured".into(),
        ));
    }
    if config.polish_enabled
        && !crate::llm::has_usable_provider_credentials(&config.llm_provider, llm_key)
    {
        return Err(crate::error::AppError::Config(
            "Polish credential is not configured".into(),
        ));
    }
    Ok(())
}

fn validate_polish_configuration(
    config: &crate::storage::AppConfig,
) -> Result<(), crate::error::AppError> {
    if !config.polish_enabled || config.llm_provider == "cloud" {
        return Ok(());
    }
    if config.llm_provider.trim().is_empty() || config.llm_model.trim().is_empty() {
        return Err(crate::error::AppError::Config(
            "Polish provider and model are not configured".into(),
        ));
    }
    let endpoint = url::Url::parse(&config.llm_base_url)
        .map_err(|_| crate::error::AppError::Config("Polish endpoint is not a valid URL".into()))?;
    if !matches!(endpoint.scheme(), "http" | "https") || endpoint.host_str().is_none() {
        return Err(crate::error::AppError::Config(
            "Polish endpoint must use HTTP or HTTPS".into(),
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::stt::{SttConfig, SttProvider, TranscriptEvent};
    use std::collections::VecDeque;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;
    #[test]
    fn auxiliary_ipc_cannot_read_stop_or_cancel_main_practice() {
        let state = PracticeState::default();
        let session = state.reserve().unwrap();
        state.update(&session, |snapshot| {
            snapshot.raw_text = "synthetic private preview".into();
        });
        let app = tauri::test::mock_builder()
            .manage(state)
            .invoke_handler(tauri::generate_handler![
                crate::commands::practice::get_practice,
                crate::commands::practice::stop_practice,
                crate::commands::practice::cancel_practice,
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask"] {
            let window = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for command in ["get_practice", "stop_practice", "cancel_practice"] {
                let result = tauri::test::get_ipc_response(
                    &window,
                    tauri::webview::InvokeRequest {
                        cmd: command.into(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: "tauri://localhost".parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                            "session": session
                        })),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.into(),
                    },
                );
                assert_eq!(
                    result.unwrap_err(),
                    serde_json::json!("Practice is only available from the main window")
                );
                let state = app.state::<PracticeState>();
                assert_eq!(state.snapshot(&session).unwrap().phase, "preparing");
                assert_eq!(
                    state.snapshot(&session).unwrap().raw_text,
                    "synthetic private preview"
                );
                assert_eq!(*state.subscribe(&session).unwrap().borrow(), 0);
            }
        }
    }
    #[test]
    fn window_close_cancels_current_session_and_clears_private_preview() {
        let state = PracticeState::default();
        let session = state.reserve().unwrap();
        let receiver = state.subscribe(&session).unwrap();
        state.update(&session, |snapshot| {
            snapshot.raw_text = "private synthetic preview".into();
            snapshot.result = "private synthetic result".into();
        });
        state.cancel_current();
        assert_eq!(*receiver.borrow(), 2);
        let snapshot = state.snapshot(&session).unwrap();
        assert_eq!(snapshot.phase, "cancelled");
        assert!(snapshot.raw_text.is_empty());
        assert!(snapshot.result.is_empty());
        assert!(!state.update(&session, |snapshot| snapshot.phase = "complete".into()));
        state.cancel_current();
        assert!(state.reserve().is_ok());
    }

    #[test]
    fn main_ipc_preserves_session_ownership_for_read_stop_and_cancel() {
        let state = PracticeState::default();
        let session = state.reserve().unwrap();
        let app = tauri::test::mock_builder()
            .manage(state)
            .invoke_handler(tauri::generate_handler![
                crate::commands::practice::get_practice,
                crate::commands::practice::stop_practice,
                crate::commands::practice::cancel_practice,
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let invoke = |command: &str, expected: &str| {
            tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: command.into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "session": expected
                    })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            )
        };
        let initial = invoke("get_practice", &session)
            .unwrap()
            .deserialize::<serde_json::Value>()
            .unwrap();
        assert_eq!(initial["session"], session);
        assert_eq!(initial["phase"], "preparing");
        assert_eq!(initial["rawText"], "");
        assert_eq!(initial["polishPreview"], "");
        assert_eq!(initial["result"], "");
        assert_eq!(initial["level"], 0.0);
        assert!(initial["error"].is_null());
        assert!(invoke("stop_practice", &session).is_ok());
        assert_eq!(
            *app.state::<PracticeState>()
                .subscribe(&session)
                .unwrap()
                .borrow(),
            1
        );
        assert!(invoke("cancel_practice", &session).is_ok());
        let cancelled = invoke("get_practice", &session)
            .unwrap()
            .deserialize::<serde_json::Value>()
            .unwrap();
        assert_eq!(cancelled["phase"], "cancelled");
        assert_eq!(cancelled["session"], session);
        assert_eq!(cancelled["rawText"], "");
        assert_eq!(cancelled["polishPreview"], "");
        assert_eq!(cancelled["result"], "");
        assert_eq!(
            app.state::<PracticeState>()
                .snapshot(&session)
                .unwrap()
                .phase,
            "cancelled"
        );
        let current = app.state::<PracticeState>().reserve().unwrap();
        for command in ["get_practice", "stop_practice", "cancel_practice"] {
            assert_eq!(
                invoke(command, &session).unwrap_err(),
                serde_json::json!("practice_stopped")
            );
        }
        assert_eq!(
            app.state::<PracticeState>()
                .snapshot(&current)
                .unwrap()
                .phase,
            "preparing"
        );
        assert_eq!(
            *app.state::<PracticeState>()
                .subscribe(&current)
                .unwrap()
                .borrow(),
            0
        );
    }

    #[test]
    fn native_cleanup_is_safe_before_any_practice_starts() {
        PracticeState::default().cancel_current();
    }

    #[tokio::test]
    async fn dropping_an_unpolled_future_releases_its_task_and_voice_lock() {
        let state = Arc::new(PracticeState::default());
        let session = state.reserve().unwrap();
        state.claim_task(&session);
        let lock = Arc::new(tokio::sync::Mutex::new(()));
        let guard = lock.clone().lock_owned().await;
        let cleanup_state = state.clone();
        let cleanup_session = session.clone();
        let cleanup = task_cleanup(guard, move || {
            cleanup_state.finish_dropped_task(&cleanup_session);
        });
        let future = async move {
            let _cleanup = cleanup;
            std::future::pending::<()>().await;
        };
        drop(future);
        assert!(lock.try_lock().is_ok());
        assert!(!state.is_busy());
        assert_eq!(state.snapshot(&session).unwrap().phase, "error");
    }

    #[tokio::test]
    async fn aborting_a_polled_future_releases_its_task_and_voice_lock() {
        let state = Arc::new(PracticeState::default());
        let session = state.reserve().unwrap();
        state.claim_task(&session);
        let lock = Arc::new(tokio::sync::Mutex::new(()));
        let guard = lock.clone().lock_owned().await;
        let cleanup_state = state.clone();
        let cleanup_session = session.clone();
        let cleanup = task_cleanup(guard, move || {
            cleanup_state.finish_dropped_task(&cleanup_session);
        });
        let (started, ready) = tokio::sync::oneshot::channel();
        let task = tokio::spawn(async move {
            let _cleanup = cleanup;
            started.send(()).unwrap();
            std::future::pending::<()>().await;
        });
        ready.await.unwrap();
        task.abort();
        assert!(task.await.unwrap_err().is_cancelled());
        assert!(lock.try_lock().is_ok());
        assert!(!state.is_busy());
        assert_eq!(state.snapshot(&session).unwrap().phase, "error");
    }

    #[test]
    fn cancelled_practice_remains_busy_until_its_task_releases_the_voice_lock() {
        let state = PracticeState::default();
        let session = state.reserve().unwrap();
        state.claim_task(&session);
        state.cancel(&session).unwrap();
        assert!(state.is_busy());
        state.release_task(&session);
        assert!(!state.is_busy());
    }

    #[test]
    fn old_task_release_cannot_clear_new_task_ownership() {
        let state = PracticeState::default();
        let old = state.reserve().unwrap();
        state.claim_task(&old);
        state.cancel(&old).unwrap();
        state.release_task(&old);
        let current = state.reserve().unwrap();
        state.claim_task(&current);
        state.cancel(&current).unwrap();
        state.release_task(&old);
        assert!(state.is_busy());
        state.release_task(&current);
        assert!(!state.is_busy());
    }

    #[test]
    fn failed_task_clears_polish_preview_and_reports_error_without_success() {
        let state = PracticeState::default();
        let session = state.reserve().unwrap();
        state.update(&session, |snapshot| {
            snapshot.phase = "polishing".into();
            snapshot.raw_text = "confirmed speech".into();
            snapshot.level = 0.5;
        });
        state.append_polish_preview(&session, "unfinished");
        state.finish(
            &session,
            Err(crate::error::AppError::Timeout(SESSION_TIMEOUT)),
        );
        let snapshot = state.snapshot(&session).unwrap();
        assert_eq!(snapshot.phase, "error");
        assert!(snapshot.error.is_some());
        assert!(snapshot.result.is_empty());
        assert!(snapshot.polish_preview.is_empty());
        assert_eq!(snapshot.level, 0.0);
        assert_eq!(snapshot.raw_text, "confirmed speech");
        assert!(!state.is_busy());
    }

    #[test]
    fn practice_invalidation_notification_retains_request_identity_after_token_change() {
        use tauri::Listener;

        let app = tauri::test::mock_builder()
            .manage(crate::SessionTokenStore(
                Arc::new(Mutex::new("synthetic-old-session".into())),
                std::sync::atomic::AtomicBool::new(false),
                Mutex::new(uuid::Uuid::new_v4().to_string()),
            ))
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let received = Arc::new(Mutex::new(Vec::<serde_json::Value>::new()));
        let captured = received.clone();
        let listener = app.listen_any("auth:session-invalid-owned", move |event| {
            captured
                .lock()
                .unwrap()
                .push(serde_json::from_str(event.payload()).unwrap());
        });
        let state = PracticeState::default();
        let session = state.reserve().unwrap();
        let request_session = app.state::<crate::SessionTokenStore>().snapshot();
        *app.state::<crate::SessionTokenStore>().0.lock().unwrap() = "synthetic-new-session".into();
        state.finish_with_notification(
            &session,
            Err(crate::error::AppError::CloudSessionInvalid),
            |event| {
                tauri::Emitter::emit(
                    &app,
                    event,
                    serde_json::json!({ "sessionEpoch": request_session.session_epoch }),
                )
                .unwrap();
            },
        );

        assert_ne!(
            request_session.token.unwrap(),
            *app.state::<crate::SessionTokenStore>().0.lock().unwrap()
        );
        assert_eq!(
            *received.lock().unwrap(),
            vec![serde_json::json!({ "sessionEpoch": request_session.session_epoch })]
        );
        assert_eq!(state.snapshot(&session).unwrap().phase, "error");
        app.unlisten(listener);
    }

    #[test]
    fn practice_session_invalidation_reaches_a_native_event_listener_once() {
        use tauri::Listener;

        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let received = Arc::new(Mutex::new(Vec::<serde_json::Value>::new()));
        let captured = received.clone();
        let listener = app.listen_any("auth:session-invalid-owned", move |event| {
            captured
                .lock()
                .unwrap()
                .push(serde_json::from_str(event.payload()).unwrap());
        });
        let state = PracticeState::default();
        let session = state.reserve().unwrap();
        for _attempt in 0..2 {
            state.finish_with_notification(
                &session,
                Err(crate::error::AppError::CloudSessionInvalid),
                |event| {
                    tauri::Emitter::emit(app.handle(), event, ()).unwrap();
                },
            );
        }
        assert_eq!(*received.lock().unwrap(), vec![serde_json::Value::Null]);
        assert_eq!(
            state.snapshot(&session).unwrap().error.unwrap().code,
            "authentication_required"
        );
        app.unlisten(listener);
    }

    #[test]
    fn practice_missing_credentials_show_setup_reasons_without_invalidating_account() {
        for (message, expected_code) in [
            (
                "Practice cloud session token is missing",
                "authentication_required",
            ),
            ("STT credential is not configured", "stt_not_configured"),
        ] {
            let state = PracticeState::default();
            let session = state.reserve().unwrap();
            state.finish_with_notification(
                &session,
                Err(crate::error::AppError::Config(message.into())),
                |_| panic!("missing credentials must not invalidate the account"),
            );
            let error = state.snapshot(&session).unwrap().error.unwrap();
            assert_eq!(error.code, expected_code);
            assert!(error.details.is_none());
            assert!(!state.is_busy());
        }
    }

    #[test]
    fn invalid_cloud_session_has_an_authentication_reason_in_each_practice_stage() {
        for phase in ["preparing", "recording", "transcribing", "polishing"] {
            let state = PracticeState::default();
            let session = state.reserve().unwrap();
            state.update(&session, |snapshot| snapshot.phase = phase.into());
            state.finish(&session, Err(crate::error::AppError::CloudSessionInvalid));
            let error = state.snapshot(&session).unwrap().error.unwrap();
            assert_eq!(error.code, "authentication_required", "{phase}");
            assert!(error.details.is_none());
            assert!(!state.is_busy());
        }
    }

    #[test]
    fn practice_notifies_session_invalidation_only_for_its_active_take() {
        let state = PracticeState::default();
        let session = state.reserve().unwrap();
        let mut notifications = Vec::new();
        state.finish_with_notification(
            &session,
            Err(crate::error::AppError::CloudSessionInvalid),
            |event| notifications.push(event),
        );
        assert_eq!(notifications, vec!["auth:session-invalid-owned"]);
        assert_eq!(state.snapshot(&session).unwrap().phase, "error");
        state.finish_with_notification(
            &session,
            Err(crate::error::AppError::CloudSessionInvalid),
            |_| panic!("completed take must not notify again"),
        );
        let next = state.reserve().unwrap();
        state.finish_with_notification(
            &session,
            Err(crate::error::AppError::CloudSessionInvalid),
            |_| panic!("old take must not invalidate the new session"),
        );
        state.finish_with_notification(
            &next,
            Err(crate::error::AppError::Network("Synthetic offline".into())),
            |_| panic!("network failure must not invalidate the account"),
        );
        let cancelled = state.reserve().unwrap();
        state.cancel(&cancelled).unwrap();
        state.finish_with_notification(
            &cancelled,
            Err(crate::error::AppError::CloudSessionInvalid),
            |_| panic!("cancelled take must not invalidate the account"),
        );
    }

    #[test]
    fn polish_setup_failure_is_not_reported_as_speech_failure() {
        for message in [
            "Polish provider and model are not configured",
            "Polish endpoint is not a valid URL",
            "Polish endpoint must use HTTP or HTTPS",
            "Polish credential is not configured",
            "Polish credential could not be read",
        ] {
            let state = PracticeState::default();
            let session = state.reserve().unwrap();
            state.finish(
                &session,
                Err(crate::error::AppError::Config(message.into())),
            );
            let error = state.snapshot(&session).unwrap().error.unwrap();
            assert_eq!(error.code, "llm_failed");
            assert!(error.details.is_none());
            assert!(!state.is_busy());
        }
    }

    #[test]
    fn polish_failure_snapshot_excludes_private_provider_details() {
        for error in [
            crate::error::AppError::Config("synthetic-secret private words".into()),
            crate::error::AppError::LlmQuota("synthetic-secret private words".into()),
        ] {
            let state = PracticeState::default();
            let session = state.reserve().unwrap();
            state.update(&session, |snapshot| snapshot.phase = "polishing".into());
            state.finish(&session, Err(error));
            let snapshot = state.snapshot(&session).unwrap();
            let serialized = serde_json::to_string(&snapshot).unwrap();
            assert!(!serialized.contains("synthetic-secret"));
            assert!(!serialized.contains("private words"));
            assert!(matches!(
                snapshot.error.unwrap().code.as_str(),
                "llm_failed" | "llm_quota_exceeded"
            ));
        }
    }

    #[test]
    fn successful_task_finishes_once_and_ignores_late_failure() {
        let state = PracticeState::default();
        let session = state.reserve().unwrap();
        state.finish(&session, Ok("Final result.".into()));
        state.finish(
            &session,
            Err(crate::error::AppError::Timeout(SESSION_TIMEOUT)),
        );
        let snapshot = state.snapshot(&session).unwrap();
        assert_eq!(snapshot.phase, "complete");
        assert_eq!(snapshot.result, "Final result.");
        assert!(snapshot.error.is_none());
    }

    #[test]
    fn old_task_completion_cannot_change_new_practice() {
        let state = PracticeState::default();
        let old = state.reserve().unwrap();
        state.cancel(&old).unwrap();
        let current = state.reserve().unwrap();
        state.finish(&old, Ok("old result".into()));
        state.finish(&old, Err(crate::error::AppError::Timeout(SESSION_TIMEOUT)));
        assert_eq!(state.snapshot(&current).unwrap().phase, "preparing");
        assert!(state.snapshot(&current).unwrap().result.is_empty());
        assert!(state.snapshot(&current).unwrap().error.is_none());
    }

    struct SyntheticLlm {
        final_text: Option<String>,
    }

    #[tokio::test]
    async fn synthetic_take_reaches_complete_or_error_and_releases_task_ownership() {
        for final_text in [Some("Final result.".into()), None] {
            let state = Arc::new(PracticeState::default());
            let session = state.reserve().unwrap();
            state.claim_task(&session);
            let lock = Arc::new(tokio::sync::Mutex::new(()));
            let guard = lock.clone().lock_owned().await;
            let cleanup_state = state.clone();
            let cleanup_session = session.clone();
            let cleanup = task_cleanup(guard, move || {
                cleanup_state.finish_dropped_task(&cleanup_session);
            });
            let (sender, mut audio) = tokio::sync::mpsc::channel(2);
            sender.send(vec![0; 320]).await.unwrap();
            let mut stt = StreamingStt {
                audio_sender: Some(sender),
                events: VecDeque::from([TranscriptEvent::Final {
                    text: "synthetic speech".into(),
                    confidence: 1.0,
                }]),
            };
            state.update(&session, |snapshot| snapshot.phase = "recording".into());
            let raw = collect_transcript(
                &mut stt,
                &mut audio,
                || Ok(()),
                |text| {
                    state.update(&session, |snapshot| snapshot.raw_text = text.into());
                },
            )
            .await
            .unwrap();
            state.update(&session, |snapshot| {
                snapshot.raw_text = raw.clone();
                snapshot.phase = "polishing".into();
            });
            let callback_state = state.clone();
            let callback_session = session.clone();
            let callback: crate::llm::ChunkCallback = Box::new(move |chunk| {
                callback_state.append_polish_preview(&callback_session, chunk);
            });
            let succeeded = final_text.is_some();
            let outcome = polish_transcript(
                &SyntheticLlm { final_text },
                &crate::llm::LlmConfig::default(),
                &polish_request(&raw, &crate::storage::AppConfig::default(), &session),
                &callback,
            )
            .await;
            assert_eq!(
                state.snapshot(&session).unwrap().polish_preview,
                "unfinished preview"
            );
            state.finish(&session, outcome);
            assert!(state.is_busy());
            drop(cleanup);
            assert!(!state.is_busy());
            assert!(lock.try_lock().is_ok());
            let snapshot = state.snapshot(&session).unwrap();
            assert_eq!(snapshot.raw_text, "synthetic speech");
            assert!(snapshot.polish_preview.is_empty());
            if succeeded {
                assert_eq!(snapshot.phase, "complete");
                assert_eq!(snapshot.result, "Final result.");
                assert!(snapshot.error.is_none());
            } else {
                assert_eq!(snapshot.phase, "error");
                assert!(snapshot.result.is_empty());
                assert!(snapshot.error.is_some());
            }
        }
    }

    #[async_trait::async_trait]
    impl crate::llm::LlmProvider for SyntheticLlm {
        async fn polish(
            &self,
            _: &crate::llm::LlmConfig,
            request: &crate::llm::PolishRequest,
            on_chunk: Option<&crate::llm::ChunkCallback>,
        ) -> Result<crate::llm::PolishResponse, crate::error::AppError> {
            assert_eq!(request.raw_text, "synthetic speech");
            assert!(request.selected_text.is_none());
            if let Some(callback) = on_chunk {
                callback("unfinished preview");
            }
            match &self.final_text {
                Some(text) => Ok(crate::llm::PolishResponse {
                    polished_text: text.clone(),
                }),
                None => Err(crate::error::AppError::Config(
                    "Synthetic model stream failed".into(),
                )),
            }
        }
        fn name(&self) -> &str {
            "Synthetic model"
        }
    }

    #[tokio::test]
    async fn practice_model_returns_final_response_not_stream_preview() {
        let previews = Arc::new(Mutex::new(Vec::new()));
        let captured = previews.clone();
        let callback: crate::llm::ChunkCallback = Box::new(move |text| {
            captured.lock().unwrap().push(text.to_string());
        });
        let result = polish_transcript(
            &SyntheticLlm {
                final_text: Some("Final result.".into()),
            },
            &crate::llm::LlmConfig::default(),
            &polish_request(
                "synthetic speech",
                &crate::storage::AppConfig::default(),
                "test",
            ),
            &callback,
        )
        .await
        .unwrap();
        assert_eq!(result, "Final result.");
        assert_eq!(*previews.lock().unwrap(), ["unfinished preview"]);
    }

    #[tokio::test]
    async fn practice_model_preview_cannot_rescue_failure_or_invalid_final_text() {
        for final_text in [None, Some(" ".into()), Some("x".repeat(MAX_TEXT_BYTES + 1))] {
            let previews = Arc::new(AtomicUsize::new(0));
            let captured = previews.clone();
            let callback: crate::llm::ChunkCallback = Box::new(move |_| {
                captured.fetch_add(1, Ordering::SeqCst);
            });
            assert!(polish_transcript(
                &SyntheticLlm { final_text },
                &crate::llm::LlmConfig::default(),
                &polish_request(
                    "synthetic speech",
                    &crate::storage::AppConfig::default(),
                    "test"
                ),
                &callback,
            )
            .await
            .is_err());
            assert_eq!(previews.load(Ordering::SeqCst), 1);
        }
    }

    #[test]
    fn practice_credential_preflight_requires_cloud_login_and_nonblank_byok() {
        for (stt_provider, llm_provider, polish_enabled, stt_key, llm_key, message) in [
            (
                "cloud",
                "ollama",
                false,
                "",
                "",
                "Practice cloud session token is missing",
            ),
            (
                "cloud",
                "ollama",
                false,
                "   ",
                "",
                "Practice cloud session token is missing",
            ),
            (
                "custom-whisper",
                "cloud",
                true,
                "",
                "",
                "Practice cloud session token is missing",
            ),
            (
                "openai-whisper",
                "ollama",
                false,
                "",
                "",
                "STT credential is not configured",
            ),
            (
                "openai-whisper",
                "ollama",
                false,
                "   ",
                "",
                "STT credential is not configured",
            ),
            (
                "custom-whisper",
                "openai",
                true,
                "",
                "",
                "Polish credential is not configured",
            ),
        ] {
            let config = crate::storage::AppConfig {
                stt_provider: stt_provider.into(),
                llm_provider: llm_provider.into(),
                polish_enabled,
                ..Default::default()
            };
            let error = validate_practice_credentials(&config, stt_key, llm_key).unwrap_err();
            assert!(
                matches!(error, crate::error::AppError::Config(ref actual) if actual == message),
                "{stt_provider}/{llm_provider}: {error}"
            );
        }
    }

    #[test]
    fn practice_credential_preflight_preserves_keyless_and_optional_polish() {
        for (stt_provider, llm_provider, polish_enabled, stt_key, llm_key) in [
            ("custom-whisper", "ollama", true, "", ""),
            ("apple-speech", "cloud", false, "", ""),
            ("custom-whisper", "openai", false, "", ""),
            (
                "cloud",
                "cloud",
                true,
                "synthetic-session",
                "synthetic-session",
            ),
            (
                "openai-whisper",
                "openai",
                true,
                "synthetic-stt-key",
                "synthetic-llm-key",
            ),
        ] {
            let config = crate::storage::AppConfig {
                stt_provider: stt_provider.into(),
                llm_provider: llm_provider.into(),
                polish_enabled,
                ..Default::default()
            };
            assert!(
                validate_practice_credentials(&config, stt_key, llm_key).is_ok(),
                "{stt_provider}/{llm_provider}"
            );
        }
    }

    #[test]
    fn practice_polish_preflight_rejects_missing_model_and_invalid_endpoint() {
        for (provider, model, endpoint) in [
            ("", "model", "https://example.test/v1"),
            ("openai", "", "https://example.test/v1"),
            ("openai", "model", ""),
            ("openai", "model", "file:///private/synthetic"),
            ("openai", "model", "not a URL"),
        ] {
            let config = crate::storage::AppConfig {
                polish_enabled: true,
                llm_provider: provider.into(),
                llm_model: model.into(),
                llm_base_url: endpoint.into(),
                ..Default::default()
            };
            assert!(validate_polish_configuration(&config).is_err());
        }
    }

    #[test]
    fn practice_polish_preflight_preserves_local_cloud_and_disabled_polish() {
        let mut config = crate::storage::AppConfig {
            polish_enabled: true,
            llm_provider: "ollama".into(),
            llm_model: "local-model".into(),
            llm_base_url: "http://localhost:11434/v1".into(),
            ..Default::default()
        };
        assert!(validate_polish_configuration(&config).is_ok());
        config.llm_provider = "cloud".into();
        config.llm_model.clear();
        config.llm_base_url.clear();
        assert!(validate_polish_configuration(&config).is_ok());
        config.polish_enabled = false;
        config.llm_provider.clear();
        assert!(validate_polish_configuration(&config).is_ok());
    }

    #[test]
    fn polish_chunks_are_preview_only_and_cancel_clears_them() {
        let state = PracticeState::default();
        let session = state.reserve().unwrap();
        state.update(&session, |snapshot| snapshot.phase = "polishing".into());
        state.append_polish_preview(&session, "Hello");
        state.append_polish_preview(&session, " world.");
        let snapshot = state.snapshot(&session).unwrap();
        assert_eq!(snapshot.polish_preview, "Hello world.");
        assert!(snapshot.result.is_empty());
        state.cancel(&session).unwrap();
        state.append_polish_preview(&session, "late");
        assert!(state.snapshot(&session).unwrap().polish_preview.is_empty());
    }

    #[test]
    fn polish_preview_rejects_oversized_chunks_without_partial_write() {
        let state = PracticeState::default();
        let session = state.reserve().unwrap();
        state.update(&session, |snapshot| snapshot.phase = "polishing".into());
        state.append_polish_preview(&session, "small");
        state.append_polish_preview(&session, &"x".repeat(MAX_TEXT_BYTES));
        assert_eq!(state.snapshot(&session).unwrap().polish_preview, "small");
    }

    #[test]
    fn old_polish_callback_cannot_write_into_a_new_session() {
        let state = PracticeState::default();
        let old = state.reserve().unwrap();
        state.cancel(&old).unwrap();
        let current = state.reserve().unwrap();
        state.update(&current, |snapshot| snapshot.phase = "polishing".into());
        state.append_polish_preview(&old, "old text");
        assert!(state.snapshot(&current).unwrap().polish_preview.is_empty());
    }

    struct StreamingStt {
        events: VecDeque<TranscriptEvent>,
        audio_sender: Option<tokio::sync::mpsc::Sender<Vec<u8>>>,
    }

    #[async_trait::async_trait]
    impl SttProvider for StreamingStt {
        async fn connect(&mut self, _: &SttConfig) -> Result<(), crate::error::AppError> {
            Ok(())
        }
        async fn send_audio(&mut self, _: &[u8]) -> Result<(), crate::error::AppError> {
            Ok(())
        }
        async fn recv_transcript(
            &mut self,
        ) -> Result<Option<TranscriptEvent>, crate::error::AppError> {
            if let Some(event) = self.events.pop_front() {
                return Ok(Some(event));
            }
            self.audio_sender.take();
            std::future::pending().await
        }
        async fn disconnect(&mut self) -> Result<Option<String>, crate::error::AppError> {
            Ok(None)
        }
        fn name(&self) -> &str {
            "Synthetic streaming"
        }
    }

    #[tokio::test]
    async fn partial_revisions_are_visible_but_only_final_text_is_returned() {
        let (sender, mut audio) = tokio::sync::mpsc::channel(1);
        let mut provider = StreamingStt {
            audio_sender: Some(sender),
            events: VecDeque::from([
                TranscriptEvent::Partial {
                    text: "hello w".into(),
                },
                TranscriptEvent::Partial {
                    text: "hello world".into(),
                },
                TranscriptEvent::Final {
                    text: "Hello world.".into(),
                    confidence: 1.0,
                },
                TranscriptEvent::Partial {
                    text: "not finalized".into(),
                },
            ]),
        };
        let updates = Mutex::new(Vec::new());
        let result = collect_transcript(
            &mut provider,
            &mut audio,
            || Ok(()),
            |text| {
                updates.lock().unwrap().push(text.to_string());
            },
        )
        .await
        .unwrap();
        assert_eq!(result, "Hello world.");
        assert_eq!(
            *updates.lock().unwrap(),
            [
                "hello w",
                "hello world",
                "Hello world.",
                "Hello world. not finalized",
            ]
        );
    }

    #[tokio::test]
    async fn oversized_partial_is_not_published_or_accepted_as_result() {
        let (sender, mut audio) = tokio::sync::mpsc::channel(1);
        let mut provider = StreamingStt {
            audio_sender: Some(sender),
            events: VecDeque::from([TranscriptEvent::Partial {
                text: "x".repeat(MAX_TEXT_BYTES + 1),
            }]),
        };
        let updates = Mutex::new(Vec::new());
        let error = collect_transcript(
            &mut provider,
            &mut audio,
            || Ok(()),
            |text| {
                updates.lock().unwrap().push(text.to_string());
            },
        )
        .await
        .unwrap_err();
        assert!(error.to_string().contains("text limit"));
        assert!(updates.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn partial_only_stream_cannot_complete_successfully() {
        let (sender, mut audio) = tokio::sync::mpsc::channel(1);
        let mut provider = StreamingStt {
            audio_sender: Some(sender),
            events: VecDeque::from([TranscriptEvent::Partial {
                text: "unfinished".into(),
            }]),
        };
        let result = collect_transcript(&mut provider, &mut audio, || Ok(()), |_| {}).await;
        assert!(result.unwrap_err().to_string().contains("No speech"));
    }

    #[tokio::test]
    async fn empty_partial_clears_preview_without_erasing_final_text() {
        let (sender, mut audio) = tokio::sync::mpsc::channel(1);
        let mut provider = StreamingStt {
            audio_sender: Some(sender),
            events: VecDeque::from([
                TranscriptEvent::Final {
                    text: "Confirmed.".into(),
                    confidence: 1.0,
                },
                TranscriptEvent::Partial {
                    text: "temporary".into(),
                },
                TranscriptEvent::Partial {
                    text: String::new(),
                },
            ]),
        };
        let updates = Mutex::new(Vec::new());
        let result = collect_transcript(
            &mut provider,
            &mut audio,
            || Ok(()),
            |text| {
                updates.lock().unwrap().push(text.to_string());
            },
        )
        .await
        .unwrap();
        assert_eq!(result, "Confirmed.");
        assert_eq!(
            *updates.lock().unwrap(),
            ["Confirmed.", "Confirmed. temporary", "Confirmed."]
        );
    }

    struct SyntheticStt {
        sent: Arc<AtomicUsize>,
        finalized: Arc<AtomicUsize>,
    }

    #[async_trait::async_trait]
    impl SttProvider for SyntheticStt {
        async fn connect(&mut self, _: &SttConfig) -> Result<(), crate::error::AppError> {
            Ok(())
        }
        async fn send_audio(&mut self, chunk: &[u8]) -> Result<(), crate::error::AppError> {
            self.sent.fetch_add(chunk.len(), Ordering::SeqCst);
            Ok(())
        }
        async fn recv_transcript(
            &mut self,
        ) -> Result<Option<TranscriptEvent>, crate::error::AppError> {
            std::future::pending().await
        }
        async fn disconnect(&mut self) -> Result<Option<String>, crate::error::AppError> {
            self.finalized.fetch_add(1, Ordering::SeqCst);
            Ok(Some("synthetic spoken words".into()))
        }
        fn name(&self) -> &str {
            "Synthetic"
        }
    }

    #[tokio::test]
    async fn synthetic_audio_is_finalized_and_returned_only_as_text() {
        let sent = Arc::new(AtomicUsize::new(0));
        let finalized = Arc::new(AtomicUsize::new(0));
        let mut provider = SyntheticStt {
            sent: sent.clone(),
            finalized: finalized.clone(),
        };
        let (sender, mut audio) = tokio::sync::mpsc::channel(2);
        sender.send(vec![0; 320]).await.unwrap();
        drop(sender);
        let result = collect_transcript(&mut provider, &mut audio, || Ok(()), |_| {})
            .await
            .unwrap();
        assert_eq!(result, "synthetic spoken words");
        assert_eq!(sent.load(Ordering::SeqCst), 320);
        assert_eq!(finalized.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn cancelled_collection_does_not_finalize_or_upload_audio() {
        let sent = Arc::new(AtomicUsize::new(0));
        let finalized = Arc::new(AtomicUsize::new(0));
        let mut provider = SyntheticStt {
            sent: sent.clone(),
            finalized: finalized.clone(),
        };
        let (sender, mut audio) = tokio::sync::mpsc::channel(2);
        sender.send(vec![0; 320]).await.unwrap();
        let (cancel, mut receiver) = watch::channel(0);
        cancel.send_replace(2);
        tokio::select! {
            biased;
            _ = wait_control(&mut receiver, 2) => {},
            _ = collect_transcript(&mut provider, &mut audio, || Ok(()), |_| {}) => panic!("cancelled collection ran"),
        }
        assert_eq!(sent.load(Ordering::SeqCst), 0);
        assert_eq!(finalized.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn capture_failure_prevents_audio_delivery_and_finalization() {
        let sent = Arc::new(AtomicUsize::new(0));
        let finalized = Arc::new(AtomicUsize::new(0));
        let mut provider = SyntheticStt {
            sent: sent.clone(),
            finalized: finalized.clone(),
        };
        let (sender, mut audio) = tokio::sync::mpsc::channel(2);
        sender.send(vec![0; 320]).await.unwrap();
        drop(sender);
        let result = collect_transcript(
            &mut provider,
            &mut audio,
            || {
                Err(crate::error::AppError::Config(
                    "Synthetic capture failure".into(),
                ))
            },
            |_| {},
        )
        .await;
        assert!(result.is_err());
        assert_eq!(sent.load(Ordering::SeqCst), 0);
        assert_eq!(finalized.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn oversized_audio_fails_without_finalizing() {
        let sent = Arc::new(AtomicUsize::new(0));
        let finalized = Arc::new(AtomicUsize::new(0));
        let mut provider = SyntheticStt {
            sent: sent.clone(),
            finalized: finalized.clone(),
        };
        let (sender, mut audio) = tokio::sync::mpsc::channel(2);
        sender.send(vec![0; MAX_AUDIO_BYTES + 1]).await.unwrap();
        drop(sender);
        assert!(
            collect_transcript(&mut provider, &mut audio, || Ok(()), |_| {})
                .await
                .is_err()
        );
        assert_eq!(sent.load(Ordering::SeqCst), 0);
        assert_eq!(finalized.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn separator_is_included_in_the_text_budget() {
        let mut raw = "x".repeat(MAX_TEXT_BYTES);
        assert!(append_text(&mut raw, "y").is_err());
        assert_eq!(raw.len(), MAX_TEXT_BYTES);
    }

    #[test]
    fn stale_session_cannot_stop_or_update_the_next_session() {
        let state = PracticeState::default();
        let first = state.reserve().unwrap();
        assert!(state.reserve().is_err());
        state.cancel(&first).unwrap();
        let second = state.reserve().unwrap();
        assert!(state.stop(&first).is_err());
        assert!(!state.update(&first, |snapshot| snapshot.raw_text = "old".into()));
        assert_eq!(state.snapshot(&second).unwrap().raw_text, "");
    }

    #[test]
    fn duplicate_stop_is_safe_and_cancellation_is_terminal() {
        let state = PracticeState::default();
        let session = state.reserve().unwrap();
        state.update(&session, |snapshot| {
            snapshot.raw_text = "temporary preview".into()
        });
        state.stop(&session).unwrap();
        state.stop(&session).unwrap();
        state.cancel(&session).unwrap();
        assert_eq!(state.snapshot(&session).unwrap().phase, "cancelled");
        assert!(state.snapshot(&session).unwrap().raw_text.is_empty());
        assert!(!state.update(&session, |snapshot| snapshot.phase = "complete".into()));
    }

    #[test]
    fn empty_transcript_has_a_no_speech_reason_but_empty_polish_is_a_model_failure() {
        for (phase, expected_code) in [
            ("transcribing", "stt_no_speech_detected"),
            ("polishing", "llm_failed"),
        ] {
            let state = PracticeState::default();
            let session = state.reserve().unwrap();
            state.update(&session, |snapshot| snapshot.phase = phase.into());
            state.finish(&session, validate_text("   "));
            let snapshot = state.snapshot(&session).unwrap();
            assert_eq!(snapshot.phase, "error");
            assert_eq!(snapshot.error.unwrap().code, expected_code);
            assert!(snapshot.result.is_empty());
        }
    }

    #[test]
    fn transcript_is_bounded_and_empty_results_are_rejected() {
        assert!(validate_text(" ").is_err());
        assert!(validate_text(&"x".repeat(MAX_TEXT_BYTES + 1)).is_err());
        assert_eq!(validate_text("spoken text").unwrap(), "spoken text");
    }

    #[test]
    fn practice_request_has_no_target_context_or_actions() {
        let request = polish_request("spoken text", &crate::storage::AppConfig::default(), "test");
        assert_eq!(request.raw_text, "spoken text");
        assert!(request.selected_text.is_none());
        assert!(request.dictionary.is_empty());
        assert!(request.correction_rules.is_empty());
        assert!(!request.translate_enabled);
        assert_eq!(
            request.context.family,
            crate::app_detector::types::ContextFamily::General
        );
    }
}

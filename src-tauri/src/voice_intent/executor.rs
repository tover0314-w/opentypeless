use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use crate::app_detector::types::TargetAppGuard;

use super::search::SearchUrl;
use super::{VoiceIntent, VoiceIntentKind, VoiceOutputPlacement, VoiceRoutingFlags};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VoiceExecutionStatus {
    Completed,
    PopupFallback,
    CopiedFallback,
    Prevented,
    Failed,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VoiceExecutionFallbackReason {
    FeatureDisabled,
    EmptyOutput,
    TargetChanged,
    SelectionLost,
    FocusRestoreFailed,
    OutputFailed,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct VoiceExecutionResult {
    pub intent_kind: VoiceIntentKind,
    pub requested_placement: VoiceOutputPlacement,
    pub actual_placement: Option<VoiceOutputPlacement>,
    pub status: VoiceExecutionStatus,
    pub fallback_reason: Option<VoiceExecutionFallbackReason>,
}

pub struct VoiceExecutionRequest<'a> {
    pub intent: &'a VoiceIntent,
    pub generated_output: &'a str,
    pub target_guard: &'a TargetAppGuard,
    pub selected_text_available: bool,
    pub restore_target_before_insert: bool,
    pub flags: VoiceRoutingFlags,
}

#[async_trait]
pub trait VoiceExecutionBackend: Send {
    fn target_matches(&mut self, guard: &TargetAppGuard) -> bool;
    async fn restore_target(&mut self, guard: &TargetAppGuard) -> Result<bool, String>;
    async fn insert_at_cursor(&mut self, text: &str) -> Result<(), String>;
    async fn replace_selection(&mut self, text: &str) -> Result<(), String>;
    async fn popup_answer(&mut self, text: &str) -> Result<(), String>;
    async fn copy_to_clipboard(&mut self, text: &str) -> Result<(), String>;
    async fn open_search(&mut self, url: &SearchUrl) -> Result<(), String>;
}

pub async fn execute_voice_intent(
    request: VoiceExecutionRequest<'_>,
    backend: &mut dyn VoiceExecutionBackend,
) -> VoiceExecutionResult {
    if !feature_enabled(request.intent.kind, request.flags) {
        return result(
            request.intent,
            None,
            VoiceExecutionStatus::Prevented,
            Some(VoiceExecutionFallbackReason::FeatureDisabled),
        );
    }

    if request.intent.kind != VoiceIntentKind::Search && request.generated_output.trim().is_empty()
    {
        return result(
            request.intent,
            None,
            VoiceExecutionStatus::Prevented,
            Some(VoiceExecutionFallbackReason::EmptyOutput),
        );
    }

    match request.intent.placement {
        VoiceOutputPlacement::InsertAtCursor => execute_insert(request, backend).await,
        VoiceOutputPlacement::ReplaceSelection => execute_replacement(request, backend).await,
        VoiceOutputPlacement::PopupAnswer => {
            popup_or_copy(request.intent, request.generated_output, None, backend).await
        }
        VoiceOutputPlacement::OpenUrl => execute_search(request, backend).await,
    }
}

async fn execute_insert(
    request: VoiceExecutionRequest<'_>,
    backend: &mut dyn VoiceExecutionBackend,
) -> VoiceExecutionResult {
    if request.restore_target_before_insert {
        let restored = backend
            .restore_target(request.target_guard)
            .await
            .unwrap_or(false);
        if !restored {
            return copy_with_optional_popup(
                request.intent,
                request.generated_output,
                VoiceExecutionFallbackReason::FocusRestoreFailed,
                true,
                backend,
            )
            .await;
        }
    }

    if !backend.target_matches(request.target_guard) {
        return copy_with_optional_popup(
            request.intent,
            request.generated_output,
            VoiceExecutionFallbackReason::TargetChanged,
            request.restore_target_before_insert,
            backend,
        )
        .await;
    }

    match backend.insert_at_cursor(request.generated_output).await {
        Ok(()) => result(
            request.intent,
            Some(VoiceOutputPlacement::InsertAtCursor),
            VoiceExecutionStatus::Completed,
            None,
        ),
        Err(_) => {
            let copied = backend
                .copy_to_clipboard(request.generated_output)
                .await
                .is_ok();
            result(
                request.intent,
                None,
                if copied {
                    VoiceExecutionStatus::CopiedFallback
                } else {
                    VoiceExecutionStatus::Failed
                },
                Some(VoiceExecutionFallbackReason::OutputFailed),
            )
        }
    }
}

async fn copy_with_optional_popup(
    intent: &VoiceIntent,
    output: &str,
    reason: VoiceExecutionFallbackReason,
    attempt_popup: bool,
    backend: &mut dyn VoiceExecutionBackend,
) -> VoiceExecutionResult {
    let copied = backend.copy_to_clipboard(output).await.is_ok();
    let popup_accepted = attempt_popup && backend.popup_answer(output).await.is_ok();
    let (status, placement) = if copied {
        (VoiceExecutionStatus::CopiedFallback, None)
    } else if popup_accepted {
        (
            VoiceExecutionStatus::PopupFallback,
            Some(VoiceOutputPlacement::PopupAnswer),
        )
    } else {
        (VoiceExecutionStatus::Failed, None)
    };
    result(intent, placement, status, Some(reason))
}

async fn execute_replacement(
    request: VoiceExecutionRequest<'_>,
    backend: &mut dyn VoiceExecutionBackend,
) -> VoiceExecutionResult {
    if !backend.target_matches(request.target_guard) {
        return popup_or_copy(
            request.intent,
            request.generated_output,
            Some(VoiceExecutionFallbackReason::TargetChanged),
            backend,
        )
        .await;
    }
    if !request.selected_text_available {
        return popup_or_copy(
            request.intent,
            request.generated_output,
            Some(VoiceExecutionFallbackReason::SelectionLost),
            backend,
        )
        .await;
    }

    match backend.replace_selection(request.generated_output).await {
        Ok(()) => result(
            request.intent,
            Some(VoiceOutputPlacement::ReplaceSelection),
            VoiceExecutionStatus::Completed,
            None,
        ),
        Err(_) => {
            popup_or_copy(
                request.intent,
                request.generated_output,
                Some(VoiceExecutionFallbackReason::OutputFailed),
                backend,
            )
            .await
        }
    }
}

async fn execute_search(
    request: VoiceExecutionRequest<'_>,
    backend: &mut dyn VoiceExecutionBackend,
) -> VoiceExecutionResult {
    let Some(provider) = request.intent.search_provider else {
        return result(
            request.intent,
            None,
            VoiceExecutionStatus::Prevented,
            Some(VoiceExecutionFallbackReason::OutputFailed),
        );
    };
    let Some(query) = request.intent.payload.as_deref() else {
        return result(
            request.intent,
            None,
            VoiceExecutionStatus::Prevented,
            Some(VoiceExecutionFallbackReason::EmptyOutput),
        );
    };
    let search_url = match SearchUrl::new(provider, query) {
        Ok(url) => url,
        Err(_) => {
            return result(
                request.intent,
                None,
                VoiceExecutionStatus::Prevented,
                Some(VoiceExecutionFallbackReason::OutputFailed),
            )
        }
    };
    match backend.open_search(&search_url).await {
        Ok(()) => result(
            request.intent,
            Some(VoiceOutputPlacement::OpenUrl),
            VoiceExecutionStatus::Completed,
            None,
        ),
        Err(_) => result(
            request.intent,
            None,
            VoiceExecutionStatus::Failed,
            Some(VoiceExecutionFallbackReason::OutputFailed),
        ),
    }
}

async fn popup_or_copy(
    intent: &VoiceIntent,
    output: &str,
    fallback_reason: Option<VoiceExecutionFallbackReason>,
    backend: &mut dyn VoiceExecutionBackend,
) -> VoiceExecutionResult {
    if backend.popup_answer(output).await.is_ok() {
        return result(
            intent,
            Some(VoiceOutputPlacement::PopupAnswer),
            if fallback_reason.is_some() {
                VoiceExecutionStatus::PopupFallback
            } else {
                VoiceExecutionStatus::Completed
            },
            fallback_reason,
        );
    }
    let copied = backend.copy_to_clipboard(output).await.is_ok();
    result(
        intent,
        None,
        if copied {
            VoiceExecutionStatus::CopiedFallback
        } else {
            VoiceExecutionStatus::Failed
        },
        fallback_reason.or(Some(VoiceExecutionFallbackReason::OutputFailed)),
    )
}

fn feature_enabled(kind: VoiceIntentKind, flags: VoiceRoutingFlags) -> bool {
    match kind {
        VoiceIntentKind::DraftInsert => flags.draft_insert,
        VoiceIntentKind::RewriteSelection => flags.rewrite_selection,
        VoiceIntentKind::TranslateSelection => flags.translate_selection,
        VoiceIntentKind::Search => flags.search,
        VoiceIntentKind::DictateInsert
        | VoiceIntentKind::TranslateInsert
        | VoiceIntentKind::AskSelection
        | VoiceIntentKind::OpenQuestion => true,
    }
}

fn result(
    intent: &VoiceIntent,
    actual_placement: Option<VoiceOutputPlacement>,
    status: VoiceExecutionStatus,
    fallback_reason: Option<VoiceExecutionFallbackReason>,
) -> VoiceExecutionResult {
    VoiceExecutionResult {
        intent_kind: intent.kind,
        requested_placement: intent.placement,
        actual_placement,
        status,
        fallback_reason,
    }
}

#[cfg(test)]
mod tests {
    use std::sync::LazyLock;

    use async_trait::async_trait;

    use super::*;
    use crate::app_detector::types::TargetAppGuard;
    use crate::voice_intent::{
        CommandLocale, SearchProvider, VoiceIntent, VoiceIntentKind, VoiceOutputPlacement,
        VoiceRoutingFlags,
    };

    static TARGET_GUARD: LazyLock<TargetAppGuard> = LazyLock::new(TargetAppGuard::default);

    #[derive(Default)]
    struct FakeBackend {
        actions: Vec<&'static str>,
        target_matches: bool,
        restore_succeeds: bool,
        popup_fails: bool,
        insert_fails: bool,
        copy_fails: bool,
        opened_url: Option<String>,
    }

    #[async_trait]
    impl VoiceExecutionBackend for FakeBackend {
        fn target_matches(&mut self, _guard: &TargetAppGuard) -> bool {
            self.actions.push("target_matches");
            self.target_matches
        }

        async fn restore_target(&mut self, _guard: &TargetAppGuard) -> Result<bool, String> {
            self.actions.push("restore_target");
            Ok(self.restore_succeeds)
        }

        async fn insert_at_cursor(&mut self, _text: &str) -> Result<(), String> {
            self.actions.push("insert_at_cursor");
            if self.insert_fails {
                Err("insertion unavailable".to_string())
            } else {
                Ok(())
            }
        }

        async fn replace_selection(&mut self, _text: &str) -> Result<(), String> {
            self.actions.push("replace_selection");
            Ok(())
        }

        async fn popup_answer(&mut self, _text: &str) -> Result<(), String> {
            self.actions.push("popup_answer");
            if self.popup_fails {
                Err("popup unavailable".to_string())
            } else {
                Ok(())
            }
        }

        async fn copy_to_clipboard(&mut self, _text: &str) -> Result<(), String> {
            self.actions.push("copy_to_clipboard");
            if self.copy_fails {
                Err("clipboard unavailable".to_string())
            } else {
                Ok(())
            }
        }

        async fn open_search(&mut self, url: &SearchUrl) -> Result<(), String> {
            self.actions.push("open_search");
            self.opened_url = Some(url.as_str().to_string());
            Ok(())
        }
    }

    fn intent(kind: VoiceIntentKind) -> VoiceIntent {
        let (placement, provider, payload) = match kind {
            VoiceIntentKind::DictateInsert
            | VoiceIntentKind::DraftInsert
            | VoiceIntentKind::TranslateInsert => (
                VoiceOutputPlacement::InsertAtCursor,
                None,
                (kind == VoiceIntentKind::DraftInsert).then(|| "draft payload".to_string()),
            ),
            VoiceIntentKind::RewriteSelection | VoiceIntentKind::TranslateSelection => {
                (VoiceOutputPlacement::ReplaceSelection, None, None)
            }
            VoiceIntentKind::AskSelection | VoiceIntentKind::OpenQuestion => {
                (VoiceOutputPlacement::PopupAnswer, None, None)
            }
            VoiceIntentKind::Search => (
                VoiceOutputPlacement::OpenUrl,
                Some(SearchProvider::Google),
                Some("rust".to_string()),
            ),
        };
        VoiceIntent::from_parts(
            kind,
            placement,
            1.0,
            provider,
            payload,
            Some(CommandLocale::En),
            None,
        )
        .unwrap()
    }

    fn request<'a>(
        intent: &'a VoiceIntent,
        output: &'a str,
        selected_text_available: bool,
        restore_target_before_insert: bool,
        flags: VoiceRoutingFlags,
    ) -> VoiceExecutionRequest<'a> {
        VoiceExecutionRequest {
            intent,
            generated_output: output,
            target_guard: &TARGET_GUARD,
            selected_text_available,
            restore_target_before_insert,
            flags,
        }
    }

    #[tokio::test]
    async fn insertion_fallback_requires_successful_delivery_receipts() {
        let draft = intent(VoiceIntentKind::DraftInsert);
        for (restore, restored, copied, popup, expected_status, expected_placement) in [
            (
                true,
                false,
                false,
                true,
                VoiceExecutionStatus::PopupFallback,
                Some(VoiceOutputPlacement::PopupAnswer),
            ),
            (
                true,
                false,
                false,
                false,
                VoiceExecutionStatus::Failed,
                None,
            ),
            (
                true,
                true,
                false,
                true,
                VoiceExecutionStatus::PopupFallback,
                Some(VoiceOutputPlacement::PopupAnswer),
            ),
            (true, true, false, false, VoiceExecutionStatus::Failed, None),
            (
                false,
                false,
                false,
                true,
                VoiceExecutionStatus::Failed,
                None,
            ),
            (
                true,
                false,
                true,
                false,
                VoiceExecutionStatus::CopiedFallback,
                None,
            ),
        ] {
            let mut backend = FakeBackend {
                restore_succeeds: restored,
                copy_fails: !copied,
                popup_fails: !popup,
                ..Default::default()
            };
            let execution = execute_voice_intent(
                request(
                    &draft,
                    "recoverable answer",
                    false,
                    restore,
                    VoiceRoutingFlags::default(),
                ),
                &mut backend,
            )
            .await;
            assert_eq!(
                execution.status, expected_status,
                "{restore}/{restored}/{copied}/{popup}"
            );
            assert_eq!(execution.actual_placement, expected_placement);
            assert!(!backend.actions.contains(&"insert_at_cursor"));
            assert_eq!(
                execution.fallback_reason,
                Some(if restore && !restored {
                    VoiceExecutionFallbackReason::FocusRestoreFailed
                } else {
                    VoiceExecutionFallbackReason::TargetChanged
                })
            );
        }
    }

    #[tokio::test]
    async fn voice_intent_executor_runs_each_requested_placement() {
        let cases = [
            (VoiceIntentKind::DictateInsert, "insert_at_cursor"),
            (VoiceIntentKind::RewriteSelection, "replace_selection"),
            (VoiceIntentKind::AskSelection, "popup_answer"),
            (VoiceIntentKind::Search, "open_search"),
        ];
        for (kind, expected_action) in cases {
            let intent = intent(kind);
            let mut backend = FakeBackend {
                target_matches: true,
                ..Default::default()
            };
            let result = execute_voice_intent(
                request(
                    &intent,
                    "generated output",
                    true,
                    false,
                    VoiceRoutingFlags::default(),
                ),
                &mut backend,
            )
            .await;

            assert_eq!(result.status, VoiceExecutionStatus::Completed);
            assert!(backend.actions.contains(&expected_action), "{kind:?}");
            if kind == VoiceIntentKind::Search {
                assert_eq!(
                    backend.opened_url.as_deref(),
                    Some("https://www.google.com/search?q=rust")
                );
            }
        }
    }

    #[tokio::test]
    async fn voice_intent_executor_prevents_replacement_when_target_or_selection_is_lost() {
        for (target_matches, selected_text_available, reason) in [
            (false, true, VoiceExecutionFallbackReason::TargetChanged),
            (true, false, VoiceExecutionFallbackReason::SelectionLost),
        ] {
            let intent = intent(VoiceIntentKind::RewriteSelection);
            let mut backend = FakeBackend {
                target_matches,
                ..Default::default()
            };
            let result = execute_voice_intent(
                request(
                    &intent,
                    "replacement",
                    selected_text_available,
                    false,
                    VoiceRoutingFlags::default(),
                ),
                &mut backend,
            )
            .await;

            assert_eq!(result.status, VoiceExecutionStatus::PopupFallback);
            assert_eq!(result.fallback_reason, Some(reason));
            assert!(!backend.actions.contains(&"replace_selection"));
            assert!(backend.actions.contains(&"popup_answer"));
        }
    }

    #[tokio::test]
    async fn voice_intent_executor_restores_ask_target_then_revalidates_before_insert() {
        let draft = intent(VoiceIntentKind::DraftInsert);
        let mut restored = FakeBackend {
            target_matches: true,
            restore_succeeds: true,
            ..Default::default()
        };
        let result = execute_voice_intent(
            request(&draft, "draft", false, true, VoiceRoutingFlags::default()),
            &mut restored,
        )
        .await;
        assert_eq!(result.status, VoiceExecutionStatus::Completed);
        assert_eq!(
            restored.actions,
            ["restore_target", "target_matches", "insert_at_cursor"]
        );

        let mut failed = FakeBackend {
            restore_succeeds: false,
            ..Default::default()
        };
        let result = execute_voice_intent(
            request(&draft, "draft", false, true, VoiceRoutingFlags::default()),
            &mut failed,
        )
        .await;
        assert_eq!(result.status, VoiceExecutionStatus::CopiedFallback);
        assert_eq!(
            result.fallback_reason,
            Some(VoiceExecutionFallbackReason::FocusRestoreFailed)
        );
        assert!(!failed.actions.contains(&"insert_at_cursor"));
        assert!(failed.actions.contains(&"copy_to_clipboard"));
        assert!(failed.actions.contains(&"popup_answer"));
    }

    #[tokio::test]
    async fn voice_intent_executor_rechecks_every_independent_kill_switch() {
        let cases = [
            (
                VoiceIntentKind::DraftInsert,
                VoiceRoutingFlags {
                    draft_insert: false,
                    ..VoiceRoutingFlags::default()
                },
            ),
            (
                VoiceIntentKind::RewriteSelection,
                VoiceRoutingFlags {
                    rewrite_selection: false,
                    ..VoiceRoutingFlags::default()
                },
            ),
            (
                VoiceIntentKind::TranslateSelection,
                VoiceRoutingFlags {
                    translate_selection: false,
                    ..VoiceRoutingFlags::default()
                },
            ),
            (
                VoiceIntentKind::Search,
                VoiceRoutingFlags {
                    search: false,
                    ..VoiceRoutingFlags::default()
                },
            ),
        ];
        for (kind, flags) in cases {
            let intent = intent(kind);
            let mut backend = FakeBackend {
                target_matches: true,
                ..Default::default()
            };
            let result =
                execute_voice_intent(request(&intent, "output", true, false, flags), &mut backend)
                    .await;
            assert_eq!(result.status, VoiceExecutionStatus::Prevented);
            assert_eq!(
                result.fallback_reason,
                Some(VoiceExecutionFallbackReason::FeatureDisabled)
            );
            assert!(backend.actions.is_empty());
        }
    }

    #[tokio::test]
    async fn voice_intent_executor_never_replaces_with_empty_output() {
        let intent = intent(VoiceIntentKind::TranslateSelection);
        let mut backend = FakeBackend {
            target_matches: true,
            ..Default::default()
        };
        let result = execute_voice_intent(
            request(&intent, "  ", true, false, VoiceRoutingFlags::default()),
            &mut backend,
        )
        .await;
        assert_eq!(result.status, VoiceExecutionStatus::Prevented);
        assert_eq!(
            result.fallback_reason,
            Some(VoiceExecutionFallbackReason::EmptyOutput)
        );
        assert!(backend.actions.is_empty());
    }

    #[tokio::test]
    async fn voice_intent_executor_copies_when_popup_fallback_is_unavailable() {
        let intent = intent(VoiceIntentKind::RewriteSelection);
        let mut backend = FakeBackend {
            target_matches: false,
            popup_fails: true,
            ..Default::default()
        };
        let result = execute_voice_intent(
            request(
                &intent,
                "safe answer",
                true,
                false,
                VoiceRoutingFlags::default(),
            ),
            &mut backend,
        )
        .await;
        assert_eq!(result.status, VoiceExecutionStatus::CopiedFallback);
        assert_eq!(
            backend.actions,
            ["target_matches", "popup_answer", "copy_to_clipboard"]
        );
    }

    #[tokio::test]
    async fn voice_intent_executor_release_matrix_never_uses_a_failed_destructive_target() {
        let insert = intent(VoiceIntentKind::DraftInsert);
        let mut permission_denied = FakeBackend {
            target_matches: true,
            insert_fails: true,
            ..Default::default()
        };
        let result = execute_voice_intent(
            request(&insert, "draft", false, false, VoiceRoutingFlags::default()),
            &mut permission_denied,
        )
        .await;
        assert_eq!(result.status, VoiceExecutionStatus::CopiedFallback);
        assert_eq!(
            permission_denied.actions,
            ["target_matches", "insert_at_cursor", "copy_to_clipboard"]
        );

        let replacement = intent(VoiceIntentKind::RewriteSelection);
        let mut application_closed = FakeBackend {
            target_matches: false,
            popup_fails: true,
            copy_fails: true,
            ..Default::default()
        };
        let result = execute_voice_intent(
            request(
                &replacement,
                "replacement",
                true,
                false,
                VoiceRoutingFlags::default(),
            ),
            &mut application_closed,
        )
        .await;
        assert_eq!(result.status, VoiceExecutionStatus::Failed);
        assert!(!application_closed.actions.contains(&"replace_selection"));
        assert_eq!(
            application_closed.actions,
            ["target_matches", "popup_answer", "copy_to_clipboard"]
        );

        let mut disabled = FakeBackend {
            target_matches: true,
            ..Default::default()
        };
        let result = execute_voice_intent(
            request(
                &replacement,
                "replacement",
                true,
                false,
                VoiceRoutingFlags {
                    rewrite_selection: false,
                    ..VoiceRoutingFlags::default()
                },
            ),
            &mut disabled,
        )
        .await;
        assert_eq!(result.status, VoiceExecutionStatus::Prevented);
        assert!(disabled.actions.is_empty());
    }
}

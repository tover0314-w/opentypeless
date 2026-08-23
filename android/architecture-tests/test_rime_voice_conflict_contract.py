from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from rime_voice_conflict_contract import HOST_ANDROID, POLICY, SERVICE, UNIT, VOICE_ANDROID, inspect_sources


class RimeVoiceConflictContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = Path(__file__).resolve().parents[1] / "app" / "src"

    def test_current_contract_passes(self) -> None:
        self.assertEqual([], inspect_sources(self.source))

    def test_rejects_start_that_captures_voice_before_rime_release(self) -> None:
        self._mutate(SERVICE, "RimeVoicePreemption rimePreemption = null;", "CommitTarget premature = captureTarget();\n        RimeVoicePreemption rimePreemption = null;", "RIME_VOICE_START_ORDER")

    def test_rejects_pending_candidate_or_key_gate_removal(self) -> None:
        self._mutate(SERVICE, "if (lease.controller == null\n                || lease.pendingKeyCommands != 0", "if (lease.controller == null\n                || false", "RIME_VOICE_SERVICE_PREEMPT")

    def test_rejects_policy_bypass(self) -> None:
        self._mutate(SERVICE, "compositionCoordinator, lease.observation, compositionConflictPolicy", "compositionCoordinator, lease.observation, CompositionConflictPolicy.defaults()", "RIME_VOICE_SERVICE_PREEMPT")

    def test_rejects_preemption_without_voice_acquisition(self) -> None:
        self._mutate(SERVICE, "decision.releaseDirective(),\n                    new CompositionCoordinator.Acquisition.Voice()", "decision.releaseDirective(),\n                    new CompositionCoordinator.Acquisition.Latin(1L)", "RIME_VOICE_HANDOFF")

    def test_rejects_current_cursor_or_direct_writer_fallback(self) -> None:
        self._mutate(SERVICE, "private RimeReleaseProof releaseRimeForVoice(", "private RimeReleaseProof releaseRimeForVoice(\n            /* InputConnection.commitText fallback */", "RIME_VOICE_EDITOR_BYPASS")

    def test_rejects_cancel_without_empty_exact_rime_revision(self) -> None:
        self._mutate(SERVICE, 'this, lease.editorSnapshot, "", cancellationRevision', 'this, lease.editorSnapshot, lease.preedit, cancellationRevision', "RIME_VOICE_EDITOR_RELEASE")

    def test_rejects_uncertain_release_published_as_voice(self) -> None:
        self._mutate(SERVICE, "case UNCERTAIN -> CompositionCoordinator.ReleaseResolution.UNCERTAIN", "case UNCERTAIN -> CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED", "RIME_VOICE_SERVICE_PREEMPT")

    def test_rejects_missing_physical_commit_cancel_device_matrix(self) -> None:
        self._mutate(HOST_ANDROID, "rimeToVoiceCommitAndCancelPathsNeverOverlapOrLoseVisibleText", "removedRimeVoicePhysicalMatrix", "RIME_VOICE_TEST_MATRIX")

    def _mutate(self, relative: Path, old: str, new: str, expected: str) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for path in (SERVICE, POLICY, UNIT, HOST_ANDROID, VOICE_ANDROID):
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text((self.source / path).read_text(encoding="utf-8"), encoding="utf-8")
            target = root / relative
            text = target.read_text(encoding="utf-8")
            self.assertIn(old, text)
            target.write_text(text.replace(old, new, 1), encoding="utf-8")
            self.assertTrue(any(expected in item for item in inspect_sources(root)))


if __name__ == "__main__":
    unittest.main()

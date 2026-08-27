from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from rime_engine_contract import (
    CONTROLLER,
    CONTROLLER_TEST,
    CONFIG,
    CONFIG_TEST,
    ACTIVITY,
    ENGINE,
    NATIVE,
    NATIVE_TEST,
    PREFERENCES,
    PENDING_SYMBOLS,
    RIME_ROOT,
    SERVICE,
    SNAPSHOT,
    TEST,
    inspect_android,
)


class RimeEngineContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        for relative in (
            ENGINE, SNAPSHOT, NATIVE, CONTROLLER, CONFIG, PENDING_SYMBOLS,
            TEST, NATIVE_TEST, CONTROLLER_TEST, CONFIG_TEST,
            SERVICE, PREFERENCES, ACTIVITY,
        ):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(android / relative, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def rules(self) -> set[str]:
        return {item.rule for item in inspect_android(self.root)}

    def mutate(self, relative: Path, old: str, new: str) -> None:
        path = self.root / relative
        source = path.read_text(encoding="utf-8")
        self.assertIn(old, source)
        path.write_text(source.replace(old, new, 1), encoding="utf-8")

    def test_current_contract_passes(self) -> None:
        self.assertEqual((), inspect_android(self.root))

    def test_rejects_extra_rime_source(self) -> None:
        extra = self.root / RIME_ROOT / "HiddenRimeAdapter.java"
        extra.write_text("final class HiddenRimeAdapter {}\n", encoding="utf-8")
        self.assertIn("RIM001_SOURCE_SET", self.rules())

    def test_rejects_editor_capability(self) -> None:
        path = self.root / ENGINE
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\nandroid.view.inputmethod.InputConnection connection;\n",
            encoding="utf-8",
        )
        self.assertIn("RIM001_CAPABILITY_BOUNDARY", self.rules())

    def test_rejects_jni_runtime_capability(self) -> None:
        path = self.root / SNAPSHOT
        path.write_text(
            path.read_text(encoding="utf-8") + '\nSystem.loadLibrary("rime");\n',
            encoding="utf-8",
        )
        self.assertIn("RIM001_CAPABILITY_BOUNDARY", self.rules())

    def test_rejects_unbounded_pending_symbol_suffix(self) -> None:
        self.mutate(PENDING_SYMBOLS, "MAXIMUM_SYMBOLS = 8", "MAXIMUM_SYMBOLS = 800")
        self.assertIn("KBD015_RIME_SYMBOL_BOUNDARY", self.rules())

    def test_rejects_pending_symbol_editor_capability(self) -> None:
        path = self.root / PENDING_SYMBOLS
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\nandroid.view.inputmethod.InputConnection connection;\n",
            encoding="utf-8",
        )
        self.assertIn("RIM001_CAPABILITY_BOUNDARY", self.rules())

    def test_rejects_process_without_coordination_generation(self) -> None:
        self.mutate(
            ENGINE,
            "long editorGeneration, long coordinationGeneration, Key key",
            "long editorGeneration, long ignoredGeneration, Key key",
        )
        self.assertIn("RIM001_ENGINE_CONTRACT", self.rules())

    def test_rejects_candidate_snapshot_without_revision_binding(self) -> None:
        self.mutate(
            SNAPSHOT,
            "candidatePage.pageRevision() != revision",
            "candidatePage.pageRevision() < 0L",
        )
        self.assertIn("RIM001_SNAPSHOT_CONTRACT", self.rules())

    def test_rejects_plaintext_diagnostics(self) -> None:
        self.mutate(SNAPSHOT, "preedit=<redacted>", "preedit=" + '" + preedit + "')
        self.assertIn("RIM001_SNAPSHOT_CONTRACT", self.rules())

    def test_rejects_runtime_wiring_that_bypasses_the_manager(self) -> None:
        self.mutate(
            SERVICE,
            "editorSessionManager.setRimeComposition(",
            "nativeAdapter.setComposingText(",
        )
        self.assertIn("RIM004_RUNTIME_WIRING", self.rules())

    def test_rejects_missing_synchronous_selection_guard(self) -> None:
        self.mutate(
            SERVICE,
            "lease.expectedCaret = (int) caret;",
            "lease.expectedCaret = -1;",
        )
        self.assertIn("RIM004_SYNC_SELECTION_GUARD", self.rules())

    def test_rejects_candidate_native_double_select(self) -> None:
        self.mutate(
            NATIVE,
            "session.selectCandidate(nativeIndex), false, \"commit text\"",
            "session.selectCandidate(nativeIndex + session.selectCandidate(nativeIndex).length()),"
            " false, \"commit text\"",
        )
        self.assertIn("RIM005_NATIVE_ONE_SHOT", self.rules())

    def test_rejects_candidate_service_without_exact_text_binding(self) -> None:
        self.mutate(
            SERVICE,
            "commit.text().equals(selection.expectedText())",
            "commit.text().isEmpty()",
        )
        self.assertIn("RIM005_RUNTIME_WIRING", self.rules())

    def test_rejects_rime_commit_that_drops_pending_symbols(self) -> None:
        self.mutate(
            SERVICE,
            "lease.pendingSymbols.appendTo(commit.text())",
            "commit.text()",
        )
        self.assertIn("KBD015_RIME_SYMBOL_WIRING", self.rules())

    def test_rejects_candidate_rendering_suppression(self) -> None:
        self.mutate(
            SERVICE,
            "renderRimeCandidatePage(lease);",
            "suppressRimeCandidatePage();",
        )
        self.assertIn("RIM005_RUNTIME_WIRING", self.rules())

    def test_rejects_candidate_rendering_without_pending_key_guard(self) -> None:
        self.mutate(
            SERVICE,
            "|| !lease.hasComposition()\n                || lease.pendingKeyCommands != 0",
            "|| !lease.hasComposition()",
        )
        self.assertIn("RIM005_CANDIDATE_PRESENTATION", self.rules())

    def test_rejects_missing_complete_lifecycle_test(self) -> None:
        self.mutate(
            TEST,
            "deterministicFakeExercisesActivateProcessSnapshotCandidateAndDeactivate",
            "partialLifecycleOnly",
        )
        self.assertIn("RIM001_CONTRACT_TEST", self.rules())

    def test_rejects_arbitrary_native_option_name(self) -> None:
        self.mutate(
            CONFIG,
            'case OPTION_FULL_SHAPE -> fullShape;',
            'case OPTION_FULL_SHAPE, "user_option" -> fullShape;',
        )
        self.assertIn("RIM006_CONFIG_CONTRACT", self.rules())

    def test_rejects_preferences_without_installed_schema_binding(self) -> None:
        self.mutate(
            PREFERENCES,
            "if (!schemas.contains(config.schemaId())) {",
            "if (false) {",
        )
        self.assertIn("RIM006_PERSISTENCE_CONTRACT", self.rules())

    def test_rejects_service_that_ignores_persisted_configuration(self) -> None:
        self.mutate(
            SERVICE,
            "rimeRuntimePreferences.load(runtime.selectedSchemas())",
            "RimeRuntimeConfig.defaults(runtime.selectedSchemas().get(0))",
        )
        self.assertIn("RIM006_RUNTIME_WIRING", self.rules())


if __name__ == "__main__":
    unittest.main()

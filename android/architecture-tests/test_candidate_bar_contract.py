from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from candidate_bar_contract import (
    BAR,
    CANDIDATE_ROOT,
    MODEL,
    MODEL_TEST,
    SERVICE,
    VIEW_TEST,
    inspect_android,
)


class CandidateBarContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        for relative in (MODEL, BAR, SERVICE, MODEL_TEST, VIEW_TEST):
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

    def test_rejects_extra_candidate_source(self) -> None:
        extra = self.root / CANDIDATE_ROOT / "HiddenCandidateWriter.java"
        extra.write_text("final class HiddenCandidateWriter {}\n", encoding="utf-8")
        self.assertIn("KBD007_SOURCE_SET", self.rules())

    def test_rejects_editor_capability_in_bar(self) -> None:
        path = self.root / BAR
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\nandroid.view.inputmethod.InputConnection connection;\n",
            encoding="utf-8",
        )
        self.assertIn("KBD007_CAPABILITY_BOUNDARY", self.rules())

    def test_rejects_unbounded_candidate_count(self) -> None:
        self.mutate(MODEL, "MAXIMUM_CANDIDATES = 16", "MAXIMUM_CANDIDATES = 160")
        self.assertIn("KBD007_MODEL_CONTRACT", self.rules())

    def test_rejects_selection_without_page_revision(self) -> None:
        self.mutate(MODEL, "long pageRevision,", "long ignoredRevision,")
        self.assertIn("KBD007_MODEL_CONTRACT", self.rules())

    def test_rejects_non_destructive_sensitive_hiding(self) -> None:
        self.mutate(BAR, "if (!visible) clear();", "if (!visible) root.setVisibility(View.GONE);")
        self.assertIn("KBD007_VIEW_CONTRACT", self.rules())

    def test_rejects_stale_page_guard_removal(self) -> None:
        self.mutate(BAR, "renderedPage != page", "renderedPage == null")
        self.assertIn("KBD007_VIEW_CONTRACT", self.rules())

    def test_rejects_missing_finish_input_clear(self) -> None:
        self.mutate(
            SERVICE,
            "keyboardCandidateBar.setPlaintextVisible(false);",
            "keyboardCandidateBar.setInteractionEnabled(false);",
        )
        self.assertIn("KBD007_SERVICE_WIRING", self.rules())

    def test_rejects_rime_candidate_callback_without_generation_bound_route(self) -> None:
        self.mutate(
            SERVICE,
            "routeRimeCandidateSelection(selection);",
            "rejectUnboundCandidateEvent();",
        )
        self.assertIn("KBD007_SERVICE_WIRING", self.rules())


if __name__ == "__main__":
    unittest.main()

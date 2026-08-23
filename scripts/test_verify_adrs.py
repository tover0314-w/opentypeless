from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import verify_adrs


class VerifyAdrsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_directory.name)
        self.adr_root = self.root / verify_adrs.ADR_ROOT
        self.adr_root.mkdir(parents=True)
        for name in ("README.md", "README_zh.md"):
            (self.root / name).write_text("[ADR](docs/adr/README.md)\n", encoding="utf-8")
        (self.root / "AGENTS.md").write_text(
            "[ADR](docs/adr/README.md) [template](docs/adr/0000-template.md)\n",
            encoding="utf-8",
        )
        statuses = " ".join(verify_adrs.VALID_STATUSES)
        (self.adr_root / verify_adrs.INDEX_NAME).write_text(
            f"[template]({verify_adrs.TEMPLATE_NAME})\n{statuses}\n[decision](0001-test-decision.md)\n",
            encoding="utf-8",
        )
        (self.adr_root / verify_adrs.TEMPLATE_NAME).write_text(
            self._record("NNNN", "Proposed", "Short decision title"), encoding="utf-8"
        )
        (self.adr_root / "0001-test-decision.md").write_text(
            self._record("0001", "Accepted", "Test decision"), encoding="utf-8"
        )

    def tearDown(self) -> None:
        self.temp_directory.cleanup()

    @staticmethod
    def _record(identifier: str, status: str, title: str) -> str:
        return (
            f"# ADR-{identifier}: {title}\n\n"
            f"## Status\n\n{status}\n\n"
            "## Background\n\nEvidence-backed context.\n\n"
            "## Decision\n\nBounded choice.\n\n"
            "## Consequences\n\nKnown tradeoffs.\n\n"
            "## Validation\n\n`python3 verification.py` PASS.\n"
        )

    def test_accepts_indexed_complete_decision(self) -> None:
        self.assertEqual([], verify_adrs.validate_repository(self.root))

    def test_rejects_invalid_status_and_missing_section(self) -> None:
        path = self.adr_root / "0001-test-decision.md"
        path.write_text(
            self._record("0001", "Unknown", "Test decision").replace(
                "## Consequences\n\nKnown tradeoffs.\n\n", ""
            ),
            encoding="utf-8",
        )

        errors = verify_adrs.validate_repository(self.root)

        self.assertTrue(any("invalid ADR status" in error for error in errors))
        self.assertTrue(any("missing or empty section: Consequences" in error for error in errors))

    def test_rejects_title_id_mismatch_and_unindexed_decision(self) -> None:
        path = self.adr_root / "0002-other-decision.md"
        path.write_text(self._record("0003", "Proposed", "Other decision"), encoding="utf-8")

        errors = verify_adrs.validate_repository(self.root)

        self.assertTrue(any("title/id does not match" in error for error in errors))
        self.assertTrue(any("missing indexed link" in error for error in errors))

    def test_rejects_symlink_and_accepted_placeholder_validation(self) -> None:
        path = self.adr_root / "0001-test-decision.md"
        path.write_text(
            self._record("0001", "Accepted", "Test decision").replace(
                "`python3 verification.py` PASS.", "TODO: 以后验证"
            ),
            encoding="utf-8",
        )
        template = self.adr_root / verify_adrs.TEMPLATE_NAME
        template.unlink()
        template.symlink_to(path)

        errors = verify_adrs.validate_repository(self.root)

        self.assertTrue(any("missing or non-regular ADR file" in error for error in errors))
        self.assertTrue(any("placeholder validation" in error for error in errors))


if __name__ == "__main__":
    unittest.main()

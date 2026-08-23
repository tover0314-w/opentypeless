from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import verify_agents


class VerifyAgentsTest(unittest.TestCase):
    def test_accepts_current_root_contract(self) -> None:
        self.assertEqual(set(), self.rules(self.valid_fixture()))

    def test_rejects_preflight_path_order_and_safety_drift(self) -> None:
        files = self.valid_fixture()
        cases = (
            (
                "path",
                "2. 读取 `docs/opentypeless_specs/00_README.md`；",
                "2. 读取 `00_README.md`；",
                "DOC004_PREFLIGHT_ORDER",
            ),
            ("order", "7. 检查最新 CI；", "7. 检查稍后的 CI；", "DOC004_PREFLIGHT_ORDER"),
            ("safety", "- 不得关闭 Gradle dependency verification；", "- 可以关闭 Gradle dependency verification；", "DOC004_SAFETY_PARITY"),
        )
        for name, old, new, expected in cases:
            with self.subTest(name=name):
                mutated = dict(files)
                mutated["AGENTS.md"] = mutated["AGENTS.md"].replace(old, new, 1)
                self.assertIn(expected, self.rules(mutated))

    def test_rejects_test_report_and_blocker_contract_drift(self) -> None:
        files = self.valid_fixture()
        cases = (
            ("test", "./gradlew lintRelease", "./gradlew lintDebug", "DOC004_TEST_COMMANDS"),
            ("report", "NOT RUN — reason", "SKIPPED", "DOC004_REPORT_CONTRACT"),
            ("rollback", "## Rollback", "## Recovery", "DOC004_REPORT_CONTRACT"),
            ("blocker", "此时输出 BLOCKED 报告和最小证据", "此时继续猜测", "DOC004_BLOCKER_POLICY"),
        )
        for name, old, new, expected in cases:
            with self.subTest(name=name):
                mutated = dict(files)
                mutated["AGENTS.md"] = mutated["AGENTS.md"].replace(old, new, 1)
                self.assertIn(expected, self.rules(mutated))

    @staticmethod
    def valid_fixture() -> dict[str, str]:
        root = Path(__file__).resolve().parents[1]
        return {
            "AGENTS.md": (root / "AGENTS.md").read_text(encoding="utf-8"),
            "docs/opentypeless_specs/AGENTS.md": (
                root / "docs/opentypeless_specs/AGENTS.md"
            ).read_text(encoding="utf-8"),
        }

    @staticmethod
    def rules(files: dict[str, str]) -> set[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative, content in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            return {item.rule for item in verify_agents.inspect_agents(root)}


if __name__ == "__main__":
    unittest.main()

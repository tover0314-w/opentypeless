from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from privacy_policy_contract import ENGINE, TEST, inspect_android


class PrivacyPolicyContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        for relative in (ENGINE, TEST):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(android / relative, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def rules(self) -> set[str]:
        return {item.rule for item in inspect_android(self.root)}

    def mutate(self, old: str, new: str) -> None:
        path = self.root / ENGINE
        source = path.read_text(encoding="utf-8")
        self.assertIn(old, source)
        path.write_text(source.replace(old, new, 1), encoding="utf-8")

    def test_current_contract_passes(self) -> None:
        self.assertEqual((), inspect_android(self.root))

    def test_rejects_android_or_editor_capability(self) -> None:
        path = self.root / ENGINE
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\nandroid.view.inputmethod.InputConnection forbidden;\n",
            encoding="utf-8",
        )
        self.assertIn("SEC001_PURE_AUTHORITY", self.rules())

    def test_rejects_sensitive_caller_downgrade(self) -> None:
        self.mutate(
            "safe.sensitiveField() || profileRequiresFullRestriction(safe.profile())",
            "safe.sensitiveField()",
        )
        self.assertIn("SEC001_HARD_PRECEDENCE", self.rules())

    def test_rejects_no_learning_bypass(self) -> None:
        self.mutate("!request.learningAllowed()", "false")
        self.assertIn("SEC001_HARD_PRECEDENCE", self.rules())

    def test_rejects_incognito_context_disclosure(self) -> None:
        self.mutate(
            "capability == Capability.SEND_CONTEXT\n                || capability == Capability.HISTORY",
            "capability == Capability.HISTORY",
        )
        self.assertIn("SEC001_RETENTION_CLOSURE", self.rules())

    def test_rejects_app_rule_after_user_choice(self) -> None:
        self.mutate(
            "if (!request.appMaximum().allows(capability))",
            "if (request.appMaximum().allows(capability))",
        )
        self.assertIn("SEC001_HARD_PRECEDENCE", self.rules())

    def test_rejects_teach_without_learning_closure(self) -> None:
        self.mutate("if (teach.allowed() && !learning.allowed())", "if (false)")
        self.assertIn("SEC001_RETENTION_CLOSURE", self.rules())

    def test_rejects_profile_identifier_diagnostic(self) -> None:
        self.mutate(
            '"Request{profile=<redacted>, sensitive="',
            '"Request{profile=" + profile + ", sensitive="',
        )
        self.assertIn("SEC001_REDACTED_DIAGNOSTICS", self.rules())


if __name__ == "__main__":
    unittest.main()

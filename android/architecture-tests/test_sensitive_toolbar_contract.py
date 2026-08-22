from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from sensitive_toolbar_contract import (
    ENGINE,
    HOST_TEST,
    JVM_TEST,
    LAYOUT,
    POLICY,
    SERVICE,
    VIEW_TEST,
    inspect_android,
)


class SensitiveToolbarContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        for relative in (POLICY, ENGINE, LAYOUT, SERVICE, JVM_TEST, VIEW_TEST, HOST_TEST):
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

    def test_rejects_editor_capability_in_policy(self) -> None:
        path = self.root / POLICY
        path.write_text(path.read_text(encoding="utf-8")
                        + "\nandroid.view.inputmethod.InputConnection editor;\n",
                        encoding="utf-8")
        self.assertIn("SEC005_POLICY_CAPABILITY", self.rules())

    def test_rejects_voice_left_visible_in_sensitive_policy(self) -> None:
        self.mutate(
            POLICY,
            "!safe.denies(PrivacyPolicyEngine.Capability.VOICE)",
            "true",
        )
        self.assertIn("SEC005_CLOSED_POLICY", self.rules())

    def test_rejects_no_learning_teach_bypass(self) -> None:
        self.mutate(
            ENGINE,
            "(!learningAllowed && isLearningBound(capability))",
            "false",
        )
        self.assertIn("SEC005_HARD_SAFETY", self.rules())

    def test_rejects_disabled_instead_of_hidden_action(self) -> None:
        self.mutate(
            LAYOUT,
            "action.setVisibility(visible ? View.VISIBLE : View.GONE);",
            "action.setEnabled(visible);",
        )
        self.assertIn("SEC005_LAYOUT_VISIBILITY", self.rules())

    def test_rejects_service_raw_sensitive_boolean_projection(self) -> None:
        self.mutate(
            SERVICE,
            "PrivacyPolicyEngine.hardSafety(\n                        privacy.sensitive(), privacy.learningAllowed())",
            "PrivacyPolicyEngine.hardSafety(false, true)",
        )
        self.assertIn("SEC005_SERVICE_WIRING", self.rules())

    def test_rejects_teach_without_toolbar_policy(self) -> None:
        self.mutate(
            SERVICE,
            "keyboardToolbarPrivacy.teachVisible()\n                    && TeachCorrectionResolver.isEligible",
            "TeachCorrectionResolver.isEligible",
        )
        self.assertIn("SEC005_SERVICE_WIRING", self.rules())

    def test_rejects_clipboard_without_toolbar_policy(self) -> None:
        self.mutate(
            SERVICE,
            "if (!keyboardToolbarPrivacy.clipboardVisible()) hideClipboardPanel();",
            "if (false) hideClipboardPanel();",
        )
        self.assertIn("SEC005_SERVICE_WIRING", self.rules())

    def test_rejects_missing_view_restore(self) -> None:
        self.mutate(
            VIEW_TEST,
            "privacyVisibilityHidesAndRestoresOnlyTheRequestedActions",
            "privacyVisibilityOnlyHidesActions",
        )
        self.assertIn("SEC005_VIEW_TEST", self.rules())

    def test_rejects_incomplete_system_transition(self) -> None:
        self.mutate(
            HOST_TEST,
            "focusField(R.id.host_no_learning);",
            "focusField(R.id.host_plain_text);",
        )
        self.assertIn("SEC005_SYSTEM_TEST", self.rules())


if __name__ == "__main__":
    unittest.main()

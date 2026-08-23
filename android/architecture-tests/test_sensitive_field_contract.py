from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
import unittest

from sensitive_field_contract import (
    CLASSIFIER,
    HOST,
    HOST_TEST,
    MANAGER,
    SERVICE,
    inspect_android,
)


class SensitiveFieldContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        android = Path(__file__).resolve().parents[1]
        for relative in (CLASSIFIER, SERVICE, MANAGER, HOST, HOST_TEST):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(android / relative, target)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def rules(self) -> set[str]:
        return {item.rule for item in inspect_android(self.root)}

    def replace(self, relative: Path, old: str, new: str) -> None:
        path = self.root / relative
        source = path.read_text(encoding="utf-8")
        self.assertIn(old, source)
        path.write_text(source.replace(old, new, 1), encoding="utf-8")

    def test_current_contract_passes(self) -> None:
        self.assertEqual((), inspect_android(self.root))

    def test_rejects_password_variation_removal(self) -> None:
        self.replace(
            CLASSIFIER,
            "TYPE_TEXT_VARIATION_WEB_PASSWORD",
            "TYPE_TEXT_VARIATION_WEB_EDIT_TEXT",
        )
        self.assertIn("SEC002_CLASSIFICATION_SHAPE", self.rules())

    def test_rejects_caller_downgrade_before_sensitive_projection(self) -> None:
        self.replace(
            CLASSIFIER,
            "if (privacy.sensitive()) return FieldKind.SENSITIVE;",
            "if (privacy.sensitive()) return FieldKind.GENERAL;",
        )
        self.assertIn("SEC002_CLASSIFICATION_SHAPE", self.rules())

    def test_rejects_unbounded_metadata(self) -> None:
        self.replace(
            CLASSIFIER,
            "MAX_METADATA_CODE_POINTS = 256",
            "MAX_METADATA_CODE_POINTS = Integer.MAX_VALUE",
        )
        self.assertIn("SEC002_CLASSIFICATION_SHAPE", self.rules())

    def test_rejects_editor_or_context_capability(self) -> None:
        path = self.root / CLASSIFIER
        path.write_text(
            path.read_text(encoding="utf-8")
            + "\nandroid.view.inputmethod.InputConnection connection;\n",
            encoding="utf-8",
        )
        self.assertIn("SEC002_METADATA_ONLY", self.rules())

    def test_rejects_package_name_heuristic(self) -> None:
        path = self.root / CLASSIFIER
        path.write_text(
            path.read_text(encoding="utf-8") + "\nString packageName = info.packageName;\n",
            encoding="utf-8",
        )
        self.assertIn("SEC002_METADATA_ONLY", self.rules())

    def test_rejects_second_service_classification(self) -> None:
        self.replace(
            SERVICE,
            "currentFieldKind = InputContextClassifier.classify(attribute);",
            "currentFieldKind = InputContextClassifier.classify(attribute); "
            "InputContextClassifier.classify(attribute);",
        )
        self.assertIn("SEC002_SINGLE_CLASSIFIER", self.rules())

    def test_rejects_missing_test_host_sensitive_field(self) -> None:
        self.replace(HOST, "R.id.host_payment_card", "R.id.host_number")
        self.assertIn("SEC002_TEST_HOST_MATRIX", self.rules())

    def test_rejects_incomplete_selected_ime_matrix(self) -> None:
        self.replace(
            HOST_TEST,
            "assertFieldProfile(automation, expectedPackage, R.id.host_identity_number,",
            "assertFieldProfile(automation, expectedPackage, R.id.host_plain_text,",
        )
        self.assertIn("SEC002_SYSTEM_IME_MATRIX", self.rules())


if __name__ == "__main__":
    unittest.main()

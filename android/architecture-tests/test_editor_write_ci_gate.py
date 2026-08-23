#!/usr/bin/env python3

from __future__ import annotations

from pathlib import Path
import tempfile
import textwrap
import unittest

from editor_write_ci_gate import inspect_ci_wiring


class EditorWriteCiGateTest(unittest.TestCase):
    def test_current_repository_wiring_is_fail_closed(self):
        repo_root = Path(__file__).resolve().parents[2]
        self.assertEqual((), inspect_ci_wiring(repo_root))

    def test_rejects_missing_or_advisory_ci_entrypoints(self):
        files = self.valid_fixture()
        cases = {
            "missing direct gate": (
                files[".github/workflows/ci.yml"].replace(
                    "      - name: Android preflight and static policy checks\n"
                    "        run: scripts/verify_android.sh preflight\n",
                    "",
                ),
                "CI_EDITOR_WRITE_GATE_STEP",
            ),
            "advisory android job": (
                files[".github/workflows/ci.yml"].replace(
                    "      - name: Android unit and architecture tests\n",
                    "      - name: Android unit and architecture tests\n"
                    "        continue-on-error: true\n",
                ),
                "CI_ANDROID_CONTINUE_ON_ERROR",
            ),
            "missing strict verifier": (
                files[".github/workflows/ci.yml"].replace(
                    "        run: scripts/verify_android.sh unit",
                    "        run: echo skipped",
                ),
                "CI_ANDROID_VERIFY_STEP",
            ),
        }
        for name, (workflow, expected_rule) in cases.items():
            with self.subTest(name=name):
                mutated = dict(files)
                mutated[".github/workflows/ci.yml"] = workflow
                self.assertIn(expected_rule, self.rules(mutated))

    def test_rejects_source_compiled_variant_or_dependency_gate_drift(self):
        files = self.valid_fixture()
        cases = (
            (
                "source production scan",
                "scripts/verify_android.sh",
                "architecture_contracts.py",
                "architecture_contracts_removed.py",
                "VERIFY_SCRIPT_SOURCE_SCAN",
            ),
            (
                "compiled check",
                "scripts/verify_android.sh",
                ":architecture-gate:check",
                ":architecture-gate:skipped",
                "VERIFY_SCRIPT_COMPILED_GATE",
            ),
            (
                "dependency verification",
                "scripts/verify_android.sh",
                "--dependency-verification=strict",
                "--dependency-verification=off",
                "VERIFY_SCRIPT_STRICT_DEPENDENCIES",
            ),
            (
                "release variant",
                "android/architecture-gate/build.gradle.kts",
                'listOf("debug", "release")',
                'listOf("debug")',
                "GRADLE_COMPILED_VARIANTS",
            ),
            (
                "compiled inspection dependency",
                "android/architecture-gate/build.gradle.kts",
                'dependsOn(tasks.named("test"), verifyCompiledArchitecture)',
                'dependsOn(tasks.named("test"))',
                "GRADLE_COMPILED_CHECK",
            ),
            (
                "app release export",
                "android/app/build.gradle.kts",
                'setOf("debug", "release")',
                'setOf("debug")',
                "GRADLE_COMPILED_EXPORT",
            ),
        )
        for name, path, old, new, expected_rule in cases:
            with self.subTest(name=name):
                mutated = dict(files)
                self.assertIn(old, mutated[path])
                mutated[path] = mutated[path].replace(old, new, 1)
                self.assertIn(expected_rule, self.rules(mutated))

    def rules(self, files: dict[str, str]) -> set[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative_path, content in files.items():
                target = root / relative_path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding="utf-8")
            return {item.rule for item in inspect_ci_wiring(root)}

    @staticmethod
    def valid_fixture() -> dict[str, str]:
        return {
            ".github/workflows/ci.yml": textwrap.dedent(
                """
                name: CI
                jobs:
                  check-android:
                    runs-on: ubuntu-latest
                    steps:
                      - name: Android preflight and static policy checks
                        run: scripts/verify_android.sh preflight
                      - name: Android unit and architecture tests
                        run: scripts/verify_android.sh unit
                  other-job:
                    runs-on: ubuntu-latest
                """
            ),
            "scripts/verify_android.sh": textwrap.dedent(
                """
                #!/usr/bin/env bash
                set -euo pipefail
                python3 "$ANDROID_DIR/architecture-tests/editor_write_ci_gate.py" --repo-root "$REPO_ROOT"
                python3 -m unittest discover -s "$ANDROID_DIR/architecture-tests" -p 'test_*.py' -v
                python3 "$ANDROID_DIR/architecture-tests/architecture_contracts.py" \\
                  --android-root "$ANDROID_DIR"
                ./gradlew --dependency-verification=strict \\
                  :architecture-gate:check \\
                  testDebugUnitTest
                """
            ),
            "android/settings.gradle.kts": 'include(":architecture-gate")\n',
            "android/architecture-gate/build.gradle.kts": textwrap.dedent(
                """
                val compiledArchitectureVariants = listOf("debug", "release")
                val verifyCompiledArchitecture = tasks.register("verifyCompiledArchitecture")
                tasks.named("check") {
                    dependsOn(tasks.named("test"), verifyCompiledArchitecture)
                }
                """
            ),
            "android/app/build.gradle.kts": textwrap.dedent(
                """
                if (variant.name !in setOf("debug", "release")) {
                    return@onVariants
                }
                """
            ),
        }


if __name__ == "__main__":
    unittest.main()

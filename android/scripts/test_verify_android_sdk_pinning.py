from __future__ import annotations

from pathlib import Path
import tempfile
import textwrap
import unittest

import verify_android_sdk_pinning


class VerifyAndroidSdkPinningTest(unittest.TestCase):
    def test_accepts_exact_pinned_workflow(self) -> None:
        self.assertEqual(set(), self.rules(self.valid_fixture()))

    def test_rejects_constant_package_and_gradle_drift(self) -> None:
        files = self.valid_fixture()
        cases = (
            (
                "compile constant",
                ".github/workflows/ci.yml",
                "ANDROID_COMPILE_SDK: '35'",
                "ANDROID_COMPILE_SDK: 'latest'",
                "BLD002_WORKFLOW_CONSTANTS",
            ),
            (
                "build tools package",
                ".github/workflows/ci.yml",
                '"build-tools;${ANDROID_BUILD_TOOLS}"',
                '"build-tools;latest"',
                "BLD002_ANDROID_PACKAGES",
            ),
            (
                "app compile sdk",
                "android/app/build.gradle.kts",
                "compileSdk = 35",
                "compileSdk = 36",
                "BLD002_COMPILE_SDK",
            ),
            (
                "host target sdk",
                "android/test-host/build.gradle.kts",
                "targetSdk = 35",
                "targetSdk = 34",
                "BLD002_TARGET_SDK",
            ),
        )
        for name, path, old, new, rule in cases:
            with self.subTest(name=name):
                mutated = dict(files)
                mutated[path] = mutated[path].replace(old, new, 1)
                self.assertIn(rule, self.rules(mutated))

    def test_rejects_image_matrix_runner_and_local_gate_drift(self) -> None:
        files = self.valid_fixture()
        cases = (
            (
                "image target",
                ".github/workflows/ci.yml",
                "${ANDROID_EMULATOR_TARGET};${ANDROID_EMULATOR_ARCH}",
                "default;x86_64",
                "BLD002_EMULATOR_IMAGE",
            ),
            (
                "API matrix",
                ".github/workflows/ci.yml",
                "api_level: [26, 33, 35, 36]",
                "api_level: [35]",
                "BLD002_EMULATOR_IMAGE",
            ),
            (
                "advisory install",
                ".github/workflows/ci.yml",
                "      - name: Install pinned Android SDK packages\n",
                "      - name: Install pinned Android SDK packages\n"
                "        continue-on-error: true\n",
                "BLD002_FAIL_CLOSED",
            ),
            (
                "local gate",
                "scripts/verify_android.sh",
                "verify_android_sdk_pinning.py",
                "sdk_check_removed.py",
                "BLD002_LOCAL_GATE",
            ),
        )
        for name, path, old, new, rule in cases:
            with self.subTest(name=name):
                mutated = dict(files)
                mutated[path] = mutated[path].replace(old, new, 1)
                self.assertIn(rule, self.rules(mutated))

    @staticmethod
    def rules(files: dict[str, str]) -> set[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative, content in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            return {
                item.rule
                for item in verify_android_sdk_pinning.inspect_sdk_pinning(root)
            }

    @staticmethod
    def valid_fixture() -> dict[str, str]:
        workflow = textwrap.dedent(
            """
            name: CI
            env:
              ANDROID_COMPILE_SDK: '35'
              ANDROID_BUILD_TOOLS: '35.0.0'
              ANDROID_EMULATOR_TARGET: google_apis
              ANDROID_EMULATOR_ARCH: x86_64
            jobs:
              check-android:
                runs-on: ubuntu-latest
                steps:
                  - name: Install pinned Android SDK packages
                    run: |
                      sdkmanager --install \\
                        "platform-tools" \\
                        "platforms;android-${ANDROID_COMPILE_SDK}" \\
                        "build-tools;${ANDROID_BUILD_TOOLS}"
                      test -d "${ANDROID_SDK_ROOT}/platforms/android-${ANDROID_COMPILE_SDK}"
                      test -d "${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS}"
              test-android-emulator:
                runs-on: ubuntu-latest
                strategy:
                  matrix:
                    api_level: [26, 33, 35, 36]
                steps:
                  - name: Install pinned Android SDK and emulator image
                    run: |
                      emulator_image="system-images;android-${{ matrix.api_level }};${ANDROID_EMULATOR_TARGET};${ANDROID_EMULATOR_ARCH}"
                      sdkmanager --install \\
                        "platform-tools" \\
                        "emulator" \\
                        "platforms;android-${ANDROID_COMPILE_SDK}" \\
                        "build-tools;${ANDROID_BUILD_TOOLS}" \\
                        "${emulator_image}"
                      test -d "${ANDROID_SDK_ROOT}/platforms/android-${ANDROID_COMPILE_SDK}"
                      test -d "${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS}"
                      sdkmanager --list_installed | grep -F "${emulator_image}"
                  - uses: reactivecircus/android-emulator-runner@sha
                    with:
                      api-level: ${{ matrix.api_level }}
                      target: ${{ env.ANDROID_EMULATOR_TARGET }}
                      arch: ${{ env.ANDROID_EMULATOR_ARCH }}
            """
        )
        gradle = "android {\n    compileSdk = 35\n    defaultConfig {\n        targetSdk = 35\n    }\n}\n"
        return {
            ".github/workflows/ci.yml": workflow,
            "scripts/verify_android.sh": (
                'python3 "$ANDROID_DIR/scripts/verify_android_sdk_pinning.py" \\\n'
                '  --repo-root "$REPO_ROOT"\n'
            ),
            "android/app/build.gradle.kts": gradle,
            "android/test-host/build.gradle.kts": gradle,
        }


if __name__ == "__main__":
    unittest.main()

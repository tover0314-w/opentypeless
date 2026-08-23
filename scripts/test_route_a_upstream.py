from __future__ import annotations

from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest import mock

import route_a_upstream


REPO_ROOT = Path(__file__).resolve().parents[1]
CONTRACT_SOURCE = REPO_ROOT / "third_party/keyboard/route_a"


class RouteAUpstreamContractTest(unittest.TestCase):
    def test_committed_contract_passes(self) -> None:
        upstream, boundary, _, patches, expected = route_a_upstream.load_contract(REPO_ROOT)
        self.assertEqual("2e82060251897226c0739b9f52d1d051b02305fb", upstream["commit"])
        self.assertEqual(3, boundary["max_patch_count"])
        self.assertEqual(3, len(patches))
        self.assertEqual("5a911de0dc2242f146b3cfcc47c2b8b1dc90f7e5", upstream["materialized_tree"])
        self.assertEqual(896, upstream["file_count"])
        self.assertEqual("179eca9923d2e93af0acdadde454d901d58bf8c0", expected["tree"])

    def test_duplicate_json_key_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "duplicate.json"
            path.write_text('{"schema_version":1,"schema_version":1}\n', encoding="utf-8")
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "duplicate JSON key"):
                route_a_upstream.load_json(path)

    def test_unknown_lock_key_is_rejected(self) -> None:
        with self.contract_copy() as root:
            self.mutate_json(root / route_a_upstream.LOCK_REL, lambda value: value.update({"extra": True}))
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "keys mismatch"):
                route_a_upstream.load_contract(root)

    def test_wrong_upstream_remote_is_rejected(self) -> None:
        with self.contract_copy() as root:
            self.mutate_json(
                root / route_a_upstream.LOCK_REL,
                lambda value: value["upstream"].update({"remote": "https://example.invalid/fork.git"}),
            )
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "fixed identity"):
                route_a_upstream.load_contract(root)

    def test_component_license_or_digest_drift_is_rejected(self) -> None:
        mutations = (
            {"license": "GPL-3.0-only"},
            {"archive_sha256": "0" * 64},
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.contract_copy() as root:
                self.mutate_json(
                    root / route_a_upstream.LOCK_REL,
                    lambda value: value["components"][0].update(mutation),
                )
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "component lock drifted"):
                    route_a_upstream.load_contract(root)

    def test_boundary_broadening_is_rejected(self) -> None:
        with self.contract_copy() as root:
            self.mutate_json(
                root / route_a_upstream.BOUNDARY_REL,
                lambda value: value["allowed_prefixes"].append("app/"),
            )
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "drifted"):
                route_a_upstream.load_contract(root)

    def test_legal_baseline_weakening_is_rejected(self) -> None:
        with self.contract_copy() as root:
            self.mutate_json(
                root / route_a_upstream.LEGAL_REL,
                lambda value: value["required_provenance"].clear(),
            )
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "legal baseline drifted"):
                route_a_upstream.load_contract(root)

    def test_reordered_queue_is_rejected(self) -> None:
        with self.contract_copy() as root:
            def reorder(value: dict[str, object]) -> None:
                value["patches"][0], value["patches"][1] = value["patches"][1], value["patches"][0]

            self.mutate_json(root / route_a_upstream.SERIES_REL, reorder)
            with self.assertRaises(route_a_upstream.RouteAError):
                route_a_upstream.load_contract(root)

    def test_semantically_valid_series_metadata_drift_is_rejected(self) -> None:
        with self.contract_copy() as root:
            def renumber(value: dict[str, object]) -> None:
                for index, entry in enumerate(value["patches"]):
                    entry["order"] = (index + 1) * 10

            self.mutate_json(root / route_a_upstream.SERIES_REL, renumber)
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "fully reviewed finite queue"):
                route_a_upstream.load_contract(root)

    def test_patch_digest_drift_is_rejected(self) -> None:
        with self.contract_copy() as root:
            patch = root / route_a_upstream.PATCH_DIR_REL / "0001-build-wiring.patch"
            patch.write_bytes(patch.read_bytes() + b"\n")
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "digest mismatch"):
                route_a_upstream.load_contract(root)

    def test_touched_path_declaration_drift_is_rejected(self) -> None:
        with self.contract_copy() as root:
            self.mutate_json(
                root / route_a_upstream.SERIES_REL,
                lambda value: value["patches"][0]["touched_paths"].pop(),
            )
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "touched-path list mismatch"):
                route_a_upstream.load_contract(root)

    def test_extra_patch_is_rejected(self) -> None:
        with self.contract_copy() as root:
            (root / route_a_upstream.PATCH_DIR_REL / "9999-hidden.patch").write_text("hidden\n", encoding="utf-8")
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "exact set/order mismatch"):
                route_a_upstream.load_contract(root)

    def test_non_patch_extra_queue_file_is_rejected(self) -> None:
        with self.contract_copy() as root:
            (root / route_a_upstream.PATCH_DIR_REL / ".hidden").write_text("hidden\n", encoding="utf-8")
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "unexpected file"):
                route_a_upstream.load_contract(root)

    def test_missing_patch_is_rejected(self) -> None:
        with self.contract_copy() as root:
            (root / route_a_upstream.PATCH_DIR_REL / "0002-editor-host.patch").unlink()
            with self.assertRaises(route_a_upstream.RouteAError):
                route_a_upstream.load_contract(root)

    def test_binary_patch_marker_is_rejected_even_if_manifest_is_rehashed(self) -> None:
        with self.contract_copy() as root:
            patch = root / route_a_upstream.PATCH_DIR_REL / "0001-build-wiring.patch"
            patch.write_bytes(patch.read_bytes() + b"GIT binary patch\n")

            def rehash(value: dict[str, object]) -> None:
                entry = value["patches"][0]
                entry["bytes"] = patch.stat().st_size
                entry["sha256"] = route_a_upstream.sha256_file(patch)

            self.mutate_json(root / route_a_upstream.SERIES_REL, rehash)
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "forbidden patch marker"):
                route_a_upstream.load_contract(root)

    def test_patch_parser_rejects_database_archive_model_and_native_paths(self) -> None:
        boundary = route_a_upstream.load_json(REPO_ROOT / route_a_upstream.BOUNDARY_REL)
        for artifact in ("data.sqlite3", "payload.zip", "weights.model", "native.so"):
            with self.subTest(artifact=artifact), self.patch_file(
                f"diff --git a/route-a-safety-eval/{artifact} b/route-a-safety-eval/{artifact}\n"
            ) as patch:
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "restricted boundary"):
                    route_a_upstream._parse_patch(patch, boundary)

    def test_patch_parser_rejects_traversal(self) -> None:
        boundary = route_a_upstream.load_json(REPO_ROOT / route_a_upstream.BOUNDARY_REL)
        with self.patch_file("diff --git a/../escape b/../escape\n") as patch:
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "unsafe patch path"):
                route_a_upstream._parse_patch(patch, boundary)

    def test_patch_parser_rejects_rename(self) -> None:
        boundary = route_a_upstream.load_json(REPO_ROOT / route_a_upstream.BOUNDARY_REL)
        with self.patch_file("diff --git a/settings.gradle.kts b/route-a-safety-eval/settings.gradle.kts\n") as patch:
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "rename/copy"):
                route_a_upstream._parse_patch(patch, boundary)

    def test_patch_parser_rejects_unparsed_quoted_diff_header(self) -> None:
        boundary = route_a_upstream.load_json(REPO_ROOT / route_a_upstream.BOUNDARY_REL)
        with self.patch_file(
            'diff --git "a/app/evil file" "b/app/evil file"\n'
            "new file mode 100644\n"
            "--- /dev/null\n"
            '+++ "b/app/evil file"\n'
            "@@ -0,0 +1 @@\n"
            "+evil\n"
        ) as patch:
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "unparsed or quoted"):
                route_a_upstream._parse_patch(patch, boundary)

    def test_patch_parser_rejects_symlink_and_gitlink_modes(self) -> None:
        boundary = route_a_upstream.load_json(REPO_ROOT / route_a_upstream.BOUNDARY_REL)
        for mode in ("120000", "160000"):
            with self.subTest(mode=mode), self.patch_file(
                "diff --git a/route-a-safety-eval/link b/route-a-safety-eval/link\n"
                f"new file mode {mode}\n"
            ) as patch:
                with self.assertRaises(route_a_upstream.RouteAError):
                    route_a_upstream._parse_patch(patch, boundary)

    def test_patch_parser_rejects_executable_and_mode_drift(self) -> None:
        boundary = route_a_upstream.load_json(REPO_ROOT / route_a_upstream.BOUNDARY_REL)
        mode_lines = (
            "new file mode 100755",
            "old mode 100644\nnew mode 100755",
            "deleted file mode 100644",
        )
        for mode_lines_fixture in mode_lines:
            with self.subTest(mode_lines=mode_lines_fixture), self.patch_file(
                "diff --git a/route-a-safety-eval/file b/route-a-safety-eval/file\n"
                f"{mode_lines_fixture}\n"
            ) as patch:
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "mode"):
                    route_a_upstream._parse_patch(patch, boundary)

    def test_patch_parser_rejects_whole_app_path(self) -> None:
        boundary = route_a_upstream.load_json(REPO_ROOT / route_a_upstream.BOUNDARY_REL)
        with self.patch_file("diff --git a/app/build.gradle.kts b/app/build.gradle.kts\n") as patch:
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "restricted boundary"):
                route_a_upstream._parse_patch(patch, boundary)

    def test_safe_archive_preflight_and_extract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "valid.tar.gz"
            self.write_tar(archive, [("tiny-root", "dir", b""), ("tiny-root/LICENSE", "file", b"license\n")])
            upstream = self.synthetic_archive_identity(archive, "tiny-root", 1)
            members = route_a_upstream._preflight_archive(archive, upstream)
            destination = root / "out"
            route_a_upstream._extract_archive(archive, destination, members)
            self.assertEqual(b"license\n", (destination / "LICENSE").read_bytes())

    def test_archive_traversal_and_absolute_paths_are_rejected(self) -> None:
        for dangerous in ("tiny-root/../escape", "/tiny-root/absolute"):
            with self.subTest(path=dangerous), tempfile.TemporaryDirectory() as temporary:
                archive = Path(temporary) / "bad.tar.gz"
                self.write_tar(archive, [(dangerous, "file", b"x")])
                upstream = self.synthetic_archive_identity(archive, "tiny-root", 1)
                with self.assertRaises(route_a_upstream.RouteAError):
                    route_a_upstream._preflight_archive(archive, upstream)

    def test_archive_symlink_hardlink_and_fifo_are_rejected(self) -> None:
        for kind in ("symlink", "hardlink", "fifo"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                archive = Path(temporary) / "bad.tar.gz"
                self.write_tar(archive, [("tiny-root/bad", kind, b"")])
                upstream = self.synthetic_archive_identity(archive, "tiny-root", 0)
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "link or special"):
                    route_a_upstream._preflight_archive(archive, upstream)

    def test_archive_duplicate_case_collision_and_multi_root_are_rejected(self) -> None:
        cases = (
            [("tiny-root/A", "file", b"a"), ("tiny-root/A", "file", b"b")],
            [("tiny-root/A", "file", b"a"), ("tiny-root/a", "file", b"b")],
            [("tiny-root/A", "file", b"a"), ("other-root/B", "file", b"b")],
        )
        for members in cases:
            with self.subTest(members=members), tempfile.TemporaryDirectory() as temporary:
                archive = Path(temporary) / "bad.tar.gz"
                self.write_tar(archive, members)
                upstream = self.synthetic_archive_identity(archive, "tiny-root", 2)
                with self.assertRaises(route_a_upstream.RouteAError):
                    route_a_upstream._preflight_archive(archive, upstream)

    def test_archive_size_limit_is_enforced(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "large.tar.gz"
            self.write_tar(archive, [("tiny-root/file", "file", b"xx")])
            upstream = self.synthetic_archive_identity(archive, "tiny-root", 1)
            with mock.patch.object(route_a_upstream, "MAX_ARCHIVE_FILE_BYTES", 1):
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "file-size limit"):
                    route_a_upstream._preflight_archive(archive, upstream)

    def test_archive_symlink_input_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "real.tar.gz"
            self.write_tar(archive, [("tiny-root/file", "file", b"x")])
            link = root / "link.tar.gz"
            link.symlink_to(archive)
            upstream = self.synthetic_archive_identity(archive, "tiny-root", 1)
            with self.assertRaisesRegex(route_a_upstream.RouteAError, "regular file"):
                route_a_upstream._preflight_archive(link, upstream)

    def test_git_environment_drops_inherited_git_injection(self) -> None:
        injected = {
            "GIT_DIR": "/tmp/attacker-controlled-git-dir",
            "GIT_WORK_TREE": "/tmp/attacker-controlled-work-tree",
            "GIT_CONFIG_COUNT": "1",
            "GIT_CONFIG_KEY_0": "core.hooksPath",
            "GIT_CONFIG_VALUE_0": "/tmp/attacker-controlled-hooks",
        }
        with mock.patch.dict(os.environ, injected, clear=False):
            environment = route_a_upstream._git_environment()
        for key in injected:
            self.assertNotIn(key, environment)
        self.assertEqual("1", environment["GIT_CONFIG_NOSYSTEM"])
        self.assertEqual(os.devnull, environment["GIT_CONFIG_GLOBAL"])

    def test_git_ignores_a_path_preceding_executable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fake_directory = Path(temporary) / "bin"
            fake_directory.mkdir()
            marker = Path(temporary) / "fake-git-was-invoked"
            fake_git = fake_directory / "git"
            fake_git.write_text(f"#!/bin/sh\ntouch '{marker}'\nexit 99\n", encoding="utf-8")
            fake_git.chmod(0o755)
            with mock.patch.dict(os.environ, {"PATH": str(fake_directory)}, clear=False):
                completed = route_a_upstream._git(REPO_ROOT, "--version")
            self.assertEqual(0, completed.returncode)
            self.assertFalse(marker.exists())

    def test_initialize_index_force_adds_ignored_archive_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            tree = Path(temporary) / "tree"
            tree.mkdir()
            (tree / ".gitignore").write_text(".idea/\n", encoding="utf-8")
            ignored = tree / ".idea/tracked.xml"
            ignored.parent.mkdir()
            ignored.write_text("tracked upstream\n", encoding="utf-8")
            route_a_upstream._initialize_index(tree)
            tracked = route_a_upstream._git(tree, "ls-files", "-z").stdout.split(b"\0")
            self.assertIn(b".idea/tracked.xml", tracked)

    def test_verify_source_accepts_exact_clean_repo(self) -> None:
        with self.synthetic_source_repo() as fixture:
            with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                result = route_a_upstream.verify_source(REPO_ROOT, fixture.root)
            self.assertTrue(result["source"]["clean"])

    def test_verify_source_rejects_wrong_remote(self) -> None:
        with self.synthetic_source_repo() as fixture:
            route_a_upstream._git(fixture.root, "remote", "set-url", "origin", "https://example.invalid/fork.git")
            with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "remote mismatch"):
                    route_a_upstream.verify_source(REPO_ROOT, fixture.root)

    def test_verify_source_rejects_fsmonitor_without_executing_it(self) -> None:
        with self.synthetic_source_repo() as fixture:
            marker = fixture.root.parent / "fsmonitor-was-executed"
            canary = fixture.root.parent / "fsmonitor-canary"
            canary.write_text(f"#!/bin/sh\ntouch '{marker}'\nexit 0\n", encoding="utf-8")
            canary.chmod(0o755)
            route_a_upstream._git(fixture.root, "config", "--local", "core.fsmonitor", str(canary))
            with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "dangerous local Git config"):
                    route_a_upstream.verify_source(REPO_ROOT, fixture.root)
            self.assertFalse(marker.exists())

    def test_verify_source_requires_real_git_directory(self) -> None:
        with self.synthetic_source_repo() as fixture:
            real_git = fixture.root.parent / "moved-git-dir"
            (fixture.root / ".git").rename(real_git)
            (fixture.root / ".git").symlink_to(real_git, target_is_directory=True)
            with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "real Git metadata"):
                    route_a_upstream.verify_source(REPO_ROOT, fixture.root)

    def test_verify_source_rejects_dangerous_worktree_attributes(self) -> None:
        with self.synthetic_source_repo() as fixture:
            (fixture.root / ".gitattributes").write_text("* filter=attacker\n", encoding="utf-8")
            with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "dangerous Git attributes"):
                    route_a_upstream.verify_source(REPO_ROOT, fixture.root)

    def test_verify_source_rejects_untracked_staged_and_ignored_files(self) -> None:
        mutations = ("untracked", "staged", "ignored")
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.synthetic_source_repo() as fixture:
                if mutation == "untracked":
                    (fixture.root / "untracked.txt").write_text("x\n", encoding="utf-8")
                elif mutation == "staged":
                    (fixture.root / "tracked.txt").write_text("changed\n", encoding="utf-8")
                    route_a_upstream._git(fixture.root, "add", "tracked.txt")
                else:
                    info_exclude = fixture.root / ".git/info/exclude"
                    info_exclude.write_text(info_exclude.read_text(encoding="utf-8") + "ignored.tmp\n", encoding="utf-8")
                    (fixture.root / "ignored.tmp").write_text("x\n", encoding="utf-8")
                with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                    with self.assertRaisesRegex(route_a_upstream.RouteAError, "dirty"):
                        route_a_upstream.verify_source(REPO_ROOT, fixture.root)

    def test_verify_source_rejects_head_tree_drift(self) -> None:
        with self.synthetic_source_repo() as fixture:
            (fixture.root / "tracked.txt").write_text("two\n", encoding="utf-8")
            route_a_upstream._git(fixture.root, "commit", "-am", "two")
            with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "HEAD or tree mismatch"):
                    route_a_upstream.verify_source(REPO_ROOT, fixture.root)

    def test_offline_replay_is_deterministic_and_exports_no_git_metadata(self) -> None:
        with self.synthetic_replay_fixture() as fixture:
            reports: list[bytes] = []
            outputs: list[Path] = []
            with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                for suffix in ("one", "two"):
                    output = fixture.root / f"output-{suffix}"
                    report = fixture.root / f"report-{suffix}.json"
                    route_a_upstream.replay(fixture.repo_root, fixture.archive, output, report)
                    reports.append(report.read_bytes())
                    outputs.append(output)
            self.assertEqual(reports[0], reports[1])
            self.assertFalse((outputs[0] / ".git").exists())
            self.assertEqual(
                (outputs[0] / "route-a-safety-eval/hello.txt").read_bytes(),
                (outputs[1] / "route-a-safety-eval/hello.txt").read_bytes(),
            )

    def test_replay_rejects_unparsed_extra_diff_via_runtime_delta(self) -> None:
        with self.synthetic_replay_fixture() as fixture:
            patch = fixture.repo_root / route_a_upstream.PATCH_DIR_REL / "0001.patch"
            with patch.open("a", encoding="utf-8") as output:
                output.write(
                    'diff --git "a/app/evil file" "b/app/evil file"\n'
                    "new file mode 100644\n"
                    "--- /dev/null\n"
                    '+++ "b/app/evil file"\n'
                    "@@ -0,0 +1 @@\n"
                    "+evil\n"
                )
            output_dir = fixture.root / "quoted-path-output"
            report = fixture.root / "quoted-path-report.json"
            with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "runtime patch path escapes"):
                    route_a_upstream.replay(fixture.repo_root, fixture.archive, output_dir, report)
            self.assertFalse(output_dir.exists())
            self.assertFalse(report.exists())

    def test_replay_rejects_existing_or_symlink_output_without_mutating_it(self) -> None:
        with self.synthetic_replay_fixture() as fixture:
            existing = fixture.root / "existing"
            existing.mkdir()
            sentinel = existing / "sentinel"
            sentinel.write_text("keep\n", encoding="utf-8")
            with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "must not already exist"):
                    route_a_upstream.replay(
                        fixture.repo_root, fixture.archive, existing, fixture.root / "existing-report.json"
                    )
            self.assertEqual("keep\n", sentinel.read_text(encoding="utf-8"))

            link = fixture.root / "output-link"
            link.symlink_to(existing, target_is_directory=True)
            with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                with self.assertRaises(route_a_upstream.RouteAError):
                    route_a_upstream.replay(
                        fixture.repo_root, fixture.archive, link, fixture.root / "link-report.json"
                    )

    def test_replay_rejects_report_inside_output_directory(self) -> None:
        with self.synthetic_replay_fixture() as fixture:
            output = fixture.root / "output"
            report = output / "report.json"
            with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "outside"):
                    route_a_upstream.replay(fixture.repo_root, fixture.archive, output, report)
            self.assertFalse(output.exists())

    def test_replay_does_not_delete_a_concurrently_created_output(self) -> None:
        with self.synthetic_replay_fixture() as fixture:
            output = fixture.root / "concurrent-output"
            sentinel = output / "sentinel"

            def collide_during_extract(*_arguments) -> None:
                output.mkdir()
                sentinel.write_text("keep\n", encoding="utf-8")
                raise route_a_upstream.RouteAError("synthetic extraction failure")

            with (
                mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract),
                mock.patch.object(route_a_upstream, "_extract_archive", side_effect=collide_during_extract),
            ):
                with self.assertRaisesRegex(route_a_upstream.RouteAError, "synthetic extraction failure"):
                    route_a_upstream.replay(
                        fixture.repo_root,
                        fixture.archive,
                        output,
                        fixture.root / "concurrent-report.json",
                    )
            self.assertEqual("keep\n", sentinel.read_text(encoding="utf-8"))

    def test_replay_patch_conflict_is_atomic(self) -> None:
        with self.synthetic_replay_fixture() as fixture:
            patch = fixture.repo_root / route_a_upstream.PATCH_DIR_REL / "0001.patch"
            patch.write_text(
                "diff --git a/route-a-safety-eval/missing.txt b/route-a-safety-eval/missing.txt\n"
                "--- a/route-a-safety-eval/missing.txt\n"
                "+++ b/route-a-safety-eval/missing.txt\n"
                "@@ -1 +1 @@\n"
                "-before\n"
                "+after\n",
                encoding="utf-8",
            )
            output = fixture.root / "conflict-output"
            report = fixture.root / "conflict-report.json"
            with mock.patch.object(route_a_upstream, "load_contract", return_value=fixture.contract):
                with self.assertRaises(route_a_upstream.RouteAError):
                    route_a_upstream.replay(fixture.repo_root, fixture.archive, output, report)
            self.assertFalse(output.exists())
            self.assertFalse(report.exists())

    @contextmanager
    def contract_copy(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            destination = root / "third_party/keyboard/route_a"
            destination.parent.mkdir(parents=True)
            shutil.copytree(CONTRACT_SOURCE, destination)
            yield root

    @staticmethod
    def mutate_json(path: Path, mutation) -> None:
        value = json.loads(path.read_text(encoding="utf-8"))
        mutation(value)
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    @contextmanager
    def patch_file(self, content: str):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "fixture.patch"
            path.write_text(content, encoding="utf-8")
            yield path

    @staticmethod
    def write_tar(path: Path, members: list[tuple[str, str, bytes]]) -> None:
        with tarfile.open(path, mode="w:gz") as archive:
            for name, kind, data in members:
                info = tarfile.TarInfo(name)
                info.mtime = 0
                if kind == "dir":
                    info.type = tarfile.DIRTYPE
                    info.mode = 0o755
                    info.size = 0
                    archive.addfile(info)
                elif kind == "file":
                    info.type = tarfile.REGTYPE
                    info.mode = 0o644
                    info.size = len(data)
                    archive.addfile(info, io.BytesIO(data))
                elif kind == "symlink":
                    info.type = tarfile.SYMTYPE
                    info.linkname = "target"
                    archive.addfile(info)
                elif kind == "hardlink":
                    info.type = tarfile.LNKTYPE
                    info.linkname = "tiny-root/target"
                    archive.addfile(info)
                elif kind == "fifo":
                    info.type = tarfile.FIFOTYPE
                    archive.addfile(info)
                else:
                    raise AssertionError(kind)

    @staticmethod
    def synthetic_archive_identity(archive: Path, root: str, file_count: int) -> dict[str, object]:
        return {
            "archive_bytes": archive.stat().st_size,
            "archive_sha256": route_a_upstream.sha256_file(archive),
            "archive_root": root,
            "file_count": file_count,
        }

    class SourceFixture:
        def __init__(self, temporary: tempfile.TemporaryDirectory, root: Path, contract):
            self.temporary = temporary
            self.root = root
            self.contract = contract

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            self.temporary.cleanup()

    def synthetic_source_repo(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name) / "source"
        root.mkdir()
        route_a_upstream._git(root, "init", "-q", "--initial-branch=main")
        route_a_upstream._git(root, "config", "--local", "user.name", "Test")
        route_a_upstream._git(root, "config", "--local", "user.email", "test@example.invalid")
        (root / "tracked.txt").write_text("one\n", encoding="utf-8")
        (root / ".gitattributes").write_text("* text=auto eol=lf\n", encoding="utf-8")
        route_a_upstream._git(root, "add", "tracked.txt", ".gitattributes")
        route_a_upstream._git(root, "commit", "-m", "one")
        remote = "https://github.com/example/upstream.git"
        route_a_upstream._git(root, "remote", "add", "origin", remote)
        upstream = {
            "remote": remote,
            "commit": route_a_upstream._git_text(root, "rev-parse", "HEAD^{commit}"),
            "git_tree": route_a_upstream._git_text(root, "rev-parse", "HEAD^{tree}"),
            "gitlinks": [],
            "file_count": 2,
        }
        contract = (upstream, {}, {}, [], {})
        return self.SourceFixture(temporary, root, contract)

    class ReplayFixture:
        def __init__(self, temporary, root, repo_root, archive, contract):
            self.temporary = temporary
            self.root = root
            self.repo_root = repo_root
            self.archive = archive
            self.contract = contract

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            self.temporary.cleanup()

    def synthetic_replay_fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        repo_root = root / "repo"
        patch_dir = repo_root / route_a_upstream.PATCH_DIR_REL
        patch_dir.mkdir(parents=True)
        archive = root / "tiny.tar.gz"
        self.write_tar(
            archive,
            [("tiny-root", "dir", b""), ("tiny-root/LICENSE", "file", b"license\n")],
        )
        upstream = {
            "remote": "https://github.com/example/tiny.git",
            "commit": "1" * 40,
            "git_tree": "2" * 40,
            "archive_bytes": archive.stat().st_size,
            "archive_sha256": route_a_upstream.sha256_file(archive),
            "archive_root": "tiny-root",
            "materialized_tree": "",
            "file_count": 1,
            "gitlinks": [],
            "license": {
                "path": "LICENSE",
                "spdx": "Apache-2.0",
                "sha256": hashlib.sha256(b"license\n").hexdigest(),
            },
        }

        preparation = root / "preparation"
        members = route_a_upstream._preflight_archive(archive, upstream)
        route_a_upstream._extract_archive(archive, preparation, members)
        base_tree = route_a_upstream._initialize_index(preparation)
        upstream["materialized_tree"] = base_tree
        route_a_upstream._git(preparation, "commit", "-m", "base")
        new_path = preparation / "route-a-safety-eval/hello.txt"
        new_path.parent.mkdir(parents=True)
        new_path.write_text("hello\n", encoding="utf-8")
        route_a_upstream._git(preparation, "add", "route-a-safety-eval/hello.txt")
        patch_bytes = route_a_upstream._git(preparation, "diff", "--cached", "--no-ext-diff", "HEAD").stdout
        patch_path = patch_dir / "0001.patch"
        patch_path.write_bytes(patch_bytes)
        output_tree = route_a_upstream._git_text(preparation, "write-tree")
        entry = {
            "order": 1,
            "id": "tiny",
            "task_id": "KSP-011",
            "file": "0001.patch",
            "bytes": len(patch_bytes),
            "sha256": route_a_upstream.sha256_file(patch_path),
            "input_tree": base_tree,
            "output_tree": output_tree,
            "touched_paths": ["route-a-safety-eval/hello.txt"],
        }
        legal = {
            "protected_upstream_files": [
                {"path": "LICENSE", "sha256": upstream["license"]["sha256"], "spdx": "Apache-2.0"}
            ],
            "new_source_license_roots": [],
            "required_provenance": [],
        }
        boundary = {
            "allowed_exact_paths": set(),
            "allowed_prefixes": {"route-a-safety-eval/"},
            "forbidden_prefixes": {"app/", ".git/"},
            "forbidden_suffixes": {".db", ".model", ".so", ".zip"},
        }
        expected = {"tree": output_tree, "file_count": 2, "license_sha256": upstream["license"]["sha256"]}
        contract = (upstream, boundary, legal, [entry], expected)
        shutil.rmtree(preparation)
        return self.ReplayFixture(temporary, root, repo_root, archive, contract)


if __name__ == "__main__":
    unittest.main()

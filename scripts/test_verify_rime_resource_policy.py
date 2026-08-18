#!/usr/bin/env python3
"""Red-team contract tests for the KSP-012 zero-bundle verifier."""

from __future__ import annotations

import base64
import bz2
import gzip
import io
import json
import lzma
from pathlib import Path
import re
import stat
import tarfile
import tempfile
import unittest
import unicodedata
import zipfile

import verify_rime_resource_policy as verifier


REPO_ROOT = Path(__file__).resolve().parents[1]


def _zip_bytes(entries: list[tuple[str, bytes]], *, compression: int = zipfile.ZIP_STORED) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=compression) as archive:
        for path, data in entries:
            archive.writestr(path, data)
    return output.getvalue()


def _fixture_bytes(source_path: str) -> bytes:
    patch = REPO_ROOT / "third_party/keyboard/route_a/patches/0003-safety-eval.patch"
    target = f"+++ b/{source_path}"
    lines = patch.read_text(encoding="utf-8").splitlines(keepends=True)
    collecting = False
    in_hunk = False
    result: list[str] = []
    for line in lines:
        if line.startswith("diff --git "):
            if collecting:
                break
            in_hunk = False
        if line.rstrip("\r\n") == target:
            collecting = True
            continue
        if not collecting:
            continue
        if line.startswith("@@"):
            in_hunk = True
            continue
        if in_hunk and line.startswith("+") and not line.startswith("+++"):
            result.append(line[1:])
        elif in_hunk and line.startswith(" "):
            result.append(line[1:])
    if not result:
        raise AssertionError(f"fixture not found in reviewed patch: {source_path}")
    return "".join(result).encode("utf-8")


class ResourcePolicyContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = verifier.load_contract(REPO_ROOT)

    def state(self, profile: str = "product") -> verifier.ScanState:
        return verifier.ScanState(profile=profile, contract=self.contract)

    def test_frozen_policy_and_schema_load(self) -> None:
        self.assertEqual(verifier.TRUSTED_POLICY_CANONICAL_SHA256, self.contract.policy_sha256)
        self.assertEqual(
            verifier.TRUSTED_IMPORT_SCHEMA_CANONICAL_SHA256,
            self.contract.import_schema_sha256,
        )
        self.assertEqual(3, len(self.contract.fixtures))
        self.assertEqual(16, len(self.contract.native_engines))
        self.assertEqual(55, len(self.contract.artifact_expectations))
        self.assertEqual(24, len(self.contract.opaque_binaries))
        self.assertEqual(1, len(self.contract.trusted_tree_manifest_sha256))

    def test_import_schema_matches_accepted_adr_contract(self) -> None:
        schema = json.loads((REPO_ROOT / verifier.IMPORT_SCHEMA_REL).read_text(encoding="utf-8"))
        properties = schema["properties"]
        self.assertFalse(schema["additionalProperties"])
        self.assertEqual(set(schema["required"]), set(properties))
        self.assertEqual("opentypeless.rime-resource-manifest", properties["format"]["const"])
        self.assertEqual(1, properties["version"]["const"])
        self.assertEqual("ANDROID_SAF_OPEN_DOCUMENT", properties["entrypoint"]["const"])
        self.assertEqual("USER_PROVIDED_UNVERIFIED", properties["usageBasis"]["const"])
        self.assertEqual("USER_PROVIDED_UNVERIFIED", properties["trustState"]["const"])
        self.assertEqual("LOCAL_ONLY", properties["distributionScope"]["const"])
        for field in (
            "packageId", "packageVersion", "displayName", "sourceUrl", "sourceRevision",
            "author", "rightsholder", "licenseExpression", "licenseTextPath", "noticePaths",
            "compatibleLibrime", "selectedSchemas", "files", "dependencies",
        ):
            self.assertIn(field, properties)
        self.assertFalse(schema["$defs"]["fileEntry"]["additionalProperties"])
        self.assertFalse(schema["$defs"]["dependencyEntry"]["additionalProperties"])

    def test_import_schema_human_fields_reject_controls_and_bidi(self) -> None:
        schema = json.loads((REPO_ROOT / verifier.IMPORT_SCHEMA_REL).read_text(encoding="utf-8"))
        guarded = (
            schema["properties"][field]["pattern"]
            for field in (
                "displayName", "sourceUrl", "sourceRevision", "author",
                "rightsholder", "licenseExpression",
            )
        )
        samples = ("\n", "\r", "\u0085", "\u061c", "\u200e", "\u200f", "\u2028", "\u202e", "\u2066", "\u2069")
        for pattern in guarded:
            self.assertIsNotNone(re.fullmatch(pattern, "安全来源 v1"))
            for sample in samples:
                self.assertIsNone(re.fullmatch(pattern, f"safe{sample}spoof"))
        self.assertIn("null", schema["properties"]["sourceUrl"]["type"])

    def manifest(self) -> dict[str, object]:
        file_entry = lambda path, role: {
            "path": path,
            "size": 1,
            "sha256": "0" * 64,
            "role": role,
        }
        return {
            "format": "opentypeless.rime-resource-manifest",
            "version": 1,
            "entrypoint": "ANDROID_SAF_OPEN_DOCUMENT",
            "networkAccess": False,
            "autoUpdate": False,
            "fileSetPolicy": "EXACT_MANIFEST_ONLY",
            "packageId": "user.local.flypy",
            "packageVersion": "1",
            "displayName": "用户本地方案",
            "sourceUrl": None,
            "sourceRevision": "local-1",
            "author": "用户提供",
            "rightsholder": "未知",
            "licenseExpression": "NOASSERTION",
            "licenseTextPath": "LICENSE.txt",
            "noticePaths": ["NOTICE.txt"],
            "usageBasis": "USER_PROVIDED_UNVERIFIED",
            "trustState": "USER_PROVIDED_UNVERIFIED",
            "distributionScope": "LOCAL_ONLY",
            "compatibleLibrime": {"minimumVersion": "1.8", "maximumVersionExclusive": "2"},
            "selectedSchemas": ["local"],
            "files": [
                file_entry("local.schema.yaml", "SCHEMA_YAML"),
                file_entry("LICENSE.txt", "LICENSE_TEXT"),
                file_entry("NOTICE.txt", "NOTICE_TEXT"),
            ],
            "dependencies": [{
                "packageId": "user.local.dependency",
                "packageVersion": "1",
                "sourceRevision": "local-1",
                "licenseExpression": "NOASSERTION",
                "files": [file_entry("dependency.dict.yaml", "DICTIONARY_YAML")],
            }],
        }

    def test_import_manifest_semantics_are_closed_world(self) -> None:
        manifest = self.manifest()
        verifier.validate_import_manifest_semantics(
            manifest,
            {"local.schema.yaml", "LICENSE.txt", "NOTICE.txt", "dependency.dict.yaml"},
        )
        cases = []
        unsafe_display = json.loads(json.dumps(manifest, ensure_ascii=False))
        unsafe_display["author"] = "trusted\u202eexe"
        cases.append(unsafe_display)
        collision = json.loads(json.dumps(manifest, ensure_ascii=False))
        collision["dependencies"][0]["files"][0]["path"] = "LOCAL.SCHEMA.YAML"
        cases.append(collision)
        missing_reference = json.loads(json.dumps(manifest, ensure_ascii=False))
        missing_reference["licenseTextPath"] = "missing.txt"
        cases.append(missing_reference)
        unknown = json.loads(json.dumps(manifest, ensure_ascii=False))
        unknown["trusted"] = True
        cases.append(unknown)
        for value in cases:
            with self.assertRaises(verifier.PolicyError):
                verifier.validate_import_manifest_semantics(value)
        with self.assertRaisesRegex(verifier.PolicyError, "exact manifest closure"):
            verifier.validate_import_manifest_semantics(manifest, {"local.schema.yaml"})

    def test_policy_unknown_key_or_duplicate_key_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            policy_path = root / verifier.POLICY_REL
            schema_path = root / verifier.IMPORT_SCHEMA_REL
            policy_path.parent.mkdir(parents=True)
            schema_path.parent.mkdir(parents=True)
            policy = json.loads((REPO_ROOT / verifier.POLICY_REL).read_text(encoding="utf-8"))
            policy["unknown"] = True
            policy_path.write_text(json.dumps(policy), encoding="utf-8")
            schema_path.write_bytes((REPO_ROOT / verifier.IMPORT_SCHEMA_REL).read_bytes())
            with self.assertRaisesRegex(verifier.PolicyError, "policy drifted"):
                verifier.load_contract(root)
            policy_path.write_text('{"schema_version":1,"schema_version":1}', encoding="utf-8")
            with self.assertRaisesRegex(verifier.PolicyError, "duplicate JSON key"):
                verifier.load_contract(root)

    def test_schema_unknown_key_fails_canonical_contract(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            policy_path = root / verifier.POLICY_REL
            schema_path = root / verifier.IMPORT_SCHEMA_REL
            policy_path.parent.mkdir(parents=True)
            schema_path.parent.mkdir(parents=True)
            policy_path.write_bytes((REPO_ROOT / verifier.POLICY_REL).read_bytes())
            schema = json.loads((REPO_ROOT / verifier.IMPORT_SCHEMA_REL).read_text(encoding="utf-8"))
            schema["unknown"] = True
            schema_path.write_text(json.dumps(schema), encoding="utf-8")
            with self.assertRaisesRegex(verifier.PolicyError, "schema drifted"):
                verifier.load_contract(root)

    def test_repository_profile_accepts_only_three_exact_synthetic_fixtures(self) -> None:
        state = verifier.scan_repository(REPO_ROOT, self.contract)
        self.assertEqual([], state.findings)
        self.assertEqual(3, state.synthetic_evidence_fixtures)
        self.assertEqual(0, state.real_xiaohe_resources)
        self.assertEqual(0, state.forbidden_rime_resources)

    def test_reviewed_local_import_source_drift_fails_closed(self) -> None:
        reviewed = next(
            item
            for item in self.contract.reviewed_dynamic_sources
            if item.classification == "RIM003_LOCAL_IMPORTER"
        )
        state = self.state("repository")
        verifier._inspect_production_source(
            state,
            reviewed.path,
            (REPO_ROOT / reviewed.path).read_bytes() + b" ",
        )
        self.assertIn(
            "REVIEWED_DYNAMIC_SOURCE_DRIFT",
            {finding.code for finding in state.findings},
        )

    def test_evidence_accepts_exact_fixtures_and_product_rejects_them(self) -> None:
        evidence = self.state("evidence")
        product = self.state("product")
        for fixture in self.contract.fixtures:
            data = _fixture_bytes(fixture.source_path)
            self.assertEqual(fixture.bytes, len(data))
            verifier._scan_blob(evidence, fixture.source_path, data)
            verifier._scan_blob(product, fixture.source_path, data)
        self.assertEqual(3, evidence.synthetic_evidence_fixtures)
        self.assertEqual([], evidence.findings)
        self.assertEqual(3, len(product.findings))
        self.assertTrue(all(item.code == "SYNTHETIC_FIXTURE_PROFILE" for item in product.findings))

    def test_synthetic_fixture_hash_drift_is_rejected(self) -> None:
        fixture = self.contract.fixtures[0]
        state = self.state("evidence")
        verifier._scan_blob(state, fixture.source_path, _fixture_bytes(fixture.source_path) + b"x")
        self.assertEqual("SYNTHETIC_FIXTURE_DRIFT", state.findings[0].code)

    def test_renamed_structural_schema_and_dictionary_are_rejected(self) -> None:
        schema = (
            b"# SYNTHETIC_TEST_ONLY\nschema:\n  schema_id: forbidden_synthetic\n"
            b"engine:\n  translators: []\n"
        )
        dictionary = (
            "# Rime dictionary\n---\nname: forbidden_synthetic\nversion: '1'\n"
            "sort: by_weight\n...\n甲\tzz\t1\n"
        ).encode("utf-8")
        state = self.state()
        verifier._scan_blob(state, "renamed-one.txt", schema)
        verifier._scan_blob(state, "renamed-two.dat", dictionary)
        self.assertEqual(2, state.forbidden_rime_resources)
        self.assertTrue(all("RIME_STRUCTURAL_DATA" in item.detail for item in state.findings))

    def test_xiaohe_marker_is_rejected_without_reporting_body(self) -> None:
        body = "SYNTHETIC_TEST_ONLY double_pinyin_flypy private-body-token".encode("utf-8")
        state = self.state()
        verifier._scan_blob(state, "renamed.data", body)
        report = json.dumps(verifier._report(state, []), ensure_ascii=False)
        self.assertEqual(1, state.real_xiaohe_resources)
        self.assertNotIn("private-body-token", report)
        self.assertNotIn("double_pinyin_flypy", report)

    def test_lua_database_userdb_and_compiled_table_are_rejected(self) -> None:
        state = self.state()
        cases = (
            ("payload.lua", b"-- SYNTHETIC_TEST_ONLY"),
            ("rime/cache.db", b"synthetic"),
            ("data/rime-userdb.txt", b"synthetic"),
            ("tables/rime-table.bin", b"\x00synthetic"),
        )
        for path, data in cases:
            verifier._scan_blob(state, path, data)
        self.assertEqual(4, state.forbidden_rime_resources)

    def test_unknown_rime_engine_is_rejected_without_exact_provenance(self) -> None:
        state = self.state("evidence")
        verifier._scan_blob(state, "lib/arm64-v8a/librime.so", b"\x7fELFsynthetic-engine")
        self.assertEqual("UNKNOWN_NATIVE_ENGINE", state.findings[0].code)

    def test_unknown_stealth_native_and_unknown_asset_are_rejected(self) -> None:
        state = self.state("product")
        verifier._scan_blob(state, "lib/arm64-v8a/libstealth.so", b"\x7fELFsynthetic")
        verifier._scan_blob(state, "assets/opaque.bin", b"synthetic")
        self.assertEqual(
            ["UNKNOWN_NATIVE_ENGINE", "UNKNOWN_APK_ASSET"],
            [finding.code for finding in state.findings],
        )

    def test_nested_container_cannot_reuse_exact_fixture_identity(self) -> None:
        fixture = self.contract.fixtures[0]
        inner = _zip_bytes([(fixture.apk_path, _fixture_bytes(fixture.source_path))])
        outer = _zip_bytes([("payload.bin", inner)])
        state = self.state("evidence")
        verifier._scan_blob(state, "evidence.apk", outer)
        self.assertEqual("SYNTHETIC_FIXTURE_NESTING", state.findings[0].code)
        self.assertIn("evidence.apk!/payload.bin!/assets/rime/default.yaml", state.findings[0].path)

    def test_nested_archive_finds_renamed_resource(self) -> None:
        schema = b"schema:\n  schema_id: synthetic_bad\nengine:\n  translators: []\n"
        inner = _zip_bytes([("opaque.data", schema)])
        outer = _zip_bytes([("payload.bin", inner)])
        state = self.state()
        verifier._scan_blob(state, "outer.apk", outer)
        self.assertEqual(1, state.forbidden_rime_resources)
        self.assertEqual(2, state.scanned_containers)

    def test_nested_archive_depth_is_bounded(self) -> None:
        data = b"safe"
        for _ in range(self.contract.limits.max_container_depth + 2):
            data = _zip_bytes([("payload.bin", data)])
        with self.assertRaisesRegex(verifier.PolicyError, "depth exceeded"):
            verifier._scan_blob(self.state(), "outer.zip", data)

    def test_archive_ratio_is_bounded(self) -> None:
        data = _zip_bytes([("large.txt", b"0" * 200_000)], compression=zipfile.ZIP_DEFLATED)
        with self.assertRaisesRegex(verifier.PolicyError, "compression ratio"):
            verifier._scan_blob(self.state(), "ratio.zip", data)

    def test_stream_codecs_are_recursively_scanned(self) -> None:
        schema = b"schema:\n  schema_id: codec_bad\nengine:\n  translators: []\n"
        cases = {
            "payload.gz": gzip.compress(schema),
            "payload.xz": lzma.compress(schema),
            "payload.lzma": lzma.compress(schema, format=lzma.FORMAT_ALONE),
            "payload.bz2": bz2.compress(schema),
        }
        for path, data in cases.items():
            state = self.state("product")
            verifier._scan_blob(state, path, data)
            self.assertEqual("FORBIDDEN_RIME_RESOURCE", state.findings[0].code, path)

    def test_tar_gzip_ratio_is_bounded(self) -> None:
        output = io.BytesIO()
        payload = b"0" * 200_000
        with tarfile.open(fileobj=output, mode="w:gz") as archive:
            info = tarfile.TarInfo("large.txt")
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))
        with self.assertRaisesRegex(verifier.PolicyError, "tar compression ratio"):
            verifier._scan_blob(self.state(), "ratio.tar.gz", output.getvalue())

    def test_archive_traversal_casefold_and_nfc_paths_fail_closed(self) -> None:
        with self.assertRaisesRegex(verifier.PolicyError, "unsafe path"):
            verifier._scan_blob(self.state(), "bad.zip", _zip_bytes([("../escape.txt", b"x")]))
        collision = _zip_bytes([("Data/File.txt", b"a"), ("data/file.TXT", b"b")])
        with self.assertRaisesRegex(verifier.PolicyError, "collision"):
            verifier._scan_blob(self.state(), "case.zip", collision)
        nfd = unicodedata.normalize("NFD", "é.txt")
        with self.assertRaisesRegex(verifier.PolicyError, "non-NFC"):
            verifier._scan_blob(self.state(), "nfc.zip", _zip_bytes([(nfd, b"x")]))
        with self.assertRaisesRegex(verifier.PolicyError, "unsafe path"):
            verifier._safe_path("C:/payload.yaml", 4096, 32)

    def test_archive_symlink_is_rejected(self) -> None:
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            info = zipfile.ZipInfo("link")
            info.create_system = 3
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(info, "target")
        with self.assertRaisesRegex(verifier.PolicyError, "symlink"):
            verifier._scan_blob(self.state(), "link.zip", output.getvalue())

    def test_archive_fifo_is_rejected(self) -> None:
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            info = zipfile.ZipInfo("fifo")
            info.create_system = 3
            info.external_attr = (stat.S_IFIFO | 0o600) << 16
            archive.writestr(info, b"synthetic")
        with self.assertRaisesRegex(verifier.PolicyError, "special ZIP member"):
            verifier._scan_blob(self.state(), "fifo.zip", output.getvalue())

    def test_encoded_source_blob_and_dynamic_decode_to_rime_store_are_rejected(self) -> None:
        schema = (
            b"schema:\n  schema_id: synthetic_encoded\nengine:\n  translators: []\n"
            + b"# synthetic padding\n" * 16
        )
        encoded = base64.b64encode(schema).decode("ascii")
        split_encoded = " + ".join(
            f'"{encoded[index:index + 64]}"' for index in range(0, len(encoded), 64)
        )
        state = self.state("repository")
        verifier._inspect_production_source(
            state,
            "android/app/src/main/java/Encoded.java",
            f"class Encoded {{ String payload = {split_encoded}; }}".encode("utf-8"),
        )
        verifier._inspect_production_source(
            state,
            "android/app/src/main/java/Decode.java",
            b"class Decode { void x(){ Base64.getDecoder().decode(value); } }",
        )
        verifier._inspect_production_source(
            state,
            "android/app/src/main/java/Store.java",
            b"class Store { void x(){ file.write(rimePath, value); } }",
        )
        verifier._finalize_production_source_guards(state)
        codes = {item.code for item in state.findings}
        self.assertIn("FORBIDDEN_RIME_RESOURCE", codes)
        self.assertIn("DYNAMIC_DECODE_TO_RIME_STORE", codes)

    def test_hex_and_gzip_source_constants_are_fail_closed(self) -> None:
        schema = (
            b"schema:\n  schema_id: synthetic_encoded\nengine:\n  translators: []\n"
            + b"".join(f"# synthetic-{index:03d}\n".encode("ascii") for index in range(128))
        )
        cases = (
            ("Hex.java", schema.hex()),
            ("Gzip.java", base64.b64encode(gzip.compress(schema)).decode("ascii")),
        )
        for filename, encoded in cases:
            state = self.state("repository")
            verifier._inspect_production_source(
                state,
                f"android/app/src/main/java/{filename}",
                f'class Encoded {{ String payload = "{encoded}"; }}'.encode("utf-8"),
            )
            codes = {item.code for item in state.findings}
            self.assertIn("OPAQUE_PRODUCTION_BLOB", codes)
            self.assertIn("FORBIDDEN_RIME_RESOURCE", codes)

    def test_split_decimal_byte_arrays_with_neutral_sink_fail_closed(self) -> None:
        schema = (
            b"schema:\n  schema_id: split_bad\nengine:\n  translators: []\n"
            + b"# padding\n" * 8
        )
        fragments = []
        for index in range(0, len(schema), 20):
            values = ",".join(str(value) for value in schema[index:index + 20])
            fragments.append(f"new byte[]{{{values}}}")
        source = (
            "class Split { byte[] payload = concat(" + ",".join(fragments) + "); "
            "String sharedDirectory = \"files\"; }"
        ).encode("utf-8")
        state = self.state("repository")
        verifier._inspect_production_source(
            state,
            "android/app/src/main/java/Split.java",
            source,
        )
        self.assertIn(
            "PRODUCTION_BYTE_ARRAY_LITERAL",
            {finding.code for finding in state.findings},
        )

    def test_all_source_and_document_locations_are_scanned(self) -> None:
        schema = (
            b"schema:\n  schema_id: hidden\nengine:\n  translators: []\n"
            + b"# padding\n" * 16
        )
        encoded = base64.b64encode(schema).decode("ascii")
        decimal = ",".join(str(value) for value in schema)
        with tempfile.TemporaryDirectory() as raw:
            tree = Path(raw)
            paths = {
                "docs/hidden.md": schema,
                "scripts/hidden.py": f'payload = "{encoded}"\n'.encode("utf-8"),
                "android/app/src/androidTest/java/Hidden.java": (
                    f"class Hidden {{ byte[] payload = new byte[]{{{decimal}}}; "
                    "String sharedDirectory = \"files\"; }}"
                ).encode("utf-8"),
            }
            for relative, data in paths.items():
                path = tree / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            state = verifier.scan_tree(tree, "product", self.contract)
        finding_paths = {finding.path for finding in state.findings}
        self.assertTrue(set(paths) <= {path.split("!/")[0] for path in finding_paths})

    def test_unknown_xor_or_encrypted_opaque_binary_fails_closed(self) -> None:
        schema = b"schema:\n  schema_id: encrypted\nengine:\n  translators: []\n"
        encrypted = bytes(value ^ 0xA5 for value in schema)
        with tempfile.TemporaryDirectory() as raw:
            tree = Path(raw)
            payload = tree / "generated/payload.bin"
            payload.parent.mkdir(parents=True)
            payload.write_bytes(encrypted)
            (tree / "generated/ascii-ciphertext.bin").write_bytes(b"printable-xor-ciphertext")
            state = verifier.scan_tree(tree, "product", self.contract)
        self.assertEqual(
            ["UNKNOWN_OPAQUE_BINARY", "UNKNOWN_OPAQUE_BINARY"],
            [finding.code for finding in state.findings],
        )

    def test_7z_and_zstd_containers_are_explicitly_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            tree = Path(raw)
            (tree / "payload.7z").write_bytes(b"7z\xbc\xaf'\x1c" + b"synthetic")
            (tree / "payload.zst").write_bytes(b"\x28\xb5\x2f\xfd" + b"synthetic")
            state = verifier.scan_tree(tree, "product", self.contract)
        self.assertEqual(
            ["UNSUPPORTED_OPAQUE_CONTAINER", "UNSUPPORTED_OPAQUE_CONTAINER"],
            [finding.code for finding in state.findings],
        )

    def test_tree_rejects_oversized_file_before_read(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            tree = Path(raw)
            oversized = tree / "opaque.bin"
            oversized.touch()
            with oversized.open("r+b") as stream:
                stream.truncate(self.contract.limits.max_entry_bytes + 1)
            with self.assertRaisesRegex(verifier.PolicyError, "before read"):
                verifier.scan_tree(tree, "product", self.contract)

    def test_report_uses_stable_labels_not_host_paths(self) -> None:
        with tempfile.TemporaryDirectory(dir="/private/tmp") as raw:
            apk = Path(raw) / "stable.apk"
            apk.write_bytes(_zip_bytes([("assets/notice.txt", b"synthetic")]))
            state = verifier.scan_apks([apk], "product", self.contract)
            report = json.dumps(verifier._report(state, [apk]), ensure_ascii=False)
        self.assertIn('"label": "stable.apk"', report)
        self.assertNotIn("/private/tmp", report)
        self.assertIn('"enumerated_files"', report)
        self.assertIn('"inspected_files"', report)
        self.assertIn('"manifest_sha256"', report)

    def test_report_path_inside_input_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            tree = Path(raw)
            with self.assertRaisesRegex(verifier.PolicyError, "outside every scanned directory"):
                verifier._validate_report_location(tree / "report.json", [tree])

    def test_artifact_expectation_rejects_missing_required_members(self) -> None:
        expectation = next(
            item for item in self.contract.artifact_expectations
            if item.fixture_ids and item.native_engine_ids
        )
        state = self.state("evidence")
        verifier._check_artifact_expectation(state, "evidence.apk", expectation)
        self.assertEqual(
            {
                "ARTIFACT_FIXTURE_SET_MISMATCH",
                "ARTIFACT_NATIVE_SET_MISMATCH",
                "ARTIFACT_ASSET_SET_MISMATCH",
            },
            {finding.code for finding in state.findings},
        )

    def test_patch_deletion_preimage_is_scanned(self) -> None:
        patch_text = """diff --git a/opaque.data b/opaque.data
deleted file mode 100644
--- a/opaque.data
+++ /dev/null
@@ -1,4 +0,0 @@
-schema:
-  schema_id: synthetic_deleted
-engine:
-  translators: []
"""
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "deleted.patch"
            path.write_text(patch_text, encoding="utf-8")
            state = self.state("repository")
            verifier._scan_patch(state, path, "deleted.patch")
        self.assertEqual(1, state.forbidden_rime_resources)

    def test_unreviewed_clean_product_apk_fails_exact_artifact_gate(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            apk = Path(raw) / "clean.apk"
            apk.write_bytes(_zip_bytes([("assets/notice.txt", b"synthetic safe")]))
            state = verifier.scan_apks([apk], "product", self.contract)
        self.assertIn("UNREVIEWED_ARTIFACT", {finding.code for finding in state.findings})
        self.assertEqual(0, state.real_xiaohe_resources)

    def test_verify_android_wires_pre_and_post_build_resource_gates(self) -> None:
        script = (REPO_ROOT / "scripts/verify_android.sh").read_text(encoding="utf-8")
        self.assertLess(script.index("run_rime_working_tree_gate\n"), script.index("require_command java"))
        self.assertEqual(2, script.count("run_rime_built_apk_gate\n"))
        self.assertIn('--profile product \\\n    --apk "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"', script)
        self.assertIn('--profile test \\\n    --apk "$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"', script)
        self.assertIn(
            '--apk "$ANDROID_DIR/test-host/build/outputs/apk/debug/test-host-debug.apk"',
            script,
        )
        self.assertIn(
            '--apk "$ANDROID_DIR/test-host/build/outputs/apk/androidTest/debug/'
            'test-host-debug-androidTest.apk"',
            script,
        )
        all_block = script[script.index("  all)"):script.index("  preflight)")]
        assemble_block = script[script.index("  assemble)"):script.index("  metrics)")]
        self.assertLess(all_block.index("run_gradle"), all_block.index("run_rime_built_apk_gate"))
        self.assertLess(assemble_block.index("run_gradle"), assemble_block.index("run_rime_built_apk_gate"))


if __name__ == "__main__":
    unittest.main()

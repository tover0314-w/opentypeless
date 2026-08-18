#!/usr/bin/env python3
"""Fail-closed KSP-012 Rime/Xiaohè resource and artifact verifier.

The verifier never fetches or installs data. It validates the frozen policy and
future import manifest schema, scans repository/replay trees, and recursively
inspects supplied APK/container bytes without emitting resource contents.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import bz2
from dataclasses import dataclass, field
import gzip
import hashlib
import io
import json
import lzma
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tarfile
import unicodedata
import zipfile
import zlib


POLICY_REL = Path("third_party/rime/resource-policy.v1.json")
IMPORT_SCHEMA_REL = Path("protocol/opentypeless-rime-import-manifest-v1.schema.json")
ROUTE_A_SERIES_REL = Path("third_party/keyboard/route_a/patches/series.v1.json")

TRUSTED_POLICY_CANONICAL_SHA256 = "b5ac1cbbca36dc793f5e3cdaf269c3c5bc008e3c69c298ca0a0f937b914dc5f7"
TRUSTED_IMPORT_SCHEMA_CANONICAL_SHA256 = "5d466e6bf38959deb47fc15bd946e3429e559ad4342367b9435ce1d9330f30cf"

HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")
DIFF_HEADER = re.compile(r"diff --git a/([^\t\r\n ]+) b/([^\t\r\n ]+)")
YAML_TOP_LEVEL_KEY = re.compile(r"(?m)^([A-Za-z_][A-Za-z0-9_-]*):(?:[ \t]|$)")
SCHEMA_ID = re.compile(r"(?m)^[ \t]+schema_id:[ \t]*[^\s#]+")
DICTIONARY_ROW = re.compile(r"(?m)^[^#\s][^\t\r\n]*\t[^\t\r\n]+(?:\t[^\t\r\n]+)?$")

CONTROL_OR_BIDI = {chr(value) for value in range(32)} | {
    chr(value) for value in range(127, 160)
} | {
    "\u061c", "\u200e", "\u200f", "\u2028", "\u2029",
    "\u202a", "\u202b", "\u202c", "\u202d", "\u202e",
    "\u2066", "\u2067", "\u2068", "\u2069",
}

PRODUCTION_SOURCE_SUFFIXES = {
    ".c", ".cc", ".cpp", ".h", ".hpp", ".java", ".js", ".jsx", ".kt",
    ".kts", ".gradle", ".m", ".mm", ".py", ".rs", ".swift", ".ts", ".tsx",
}
EXPLICIT_TEXT_SUFFIXES = {
    ".c", ".cc", ".cfg", ".conf", ".cpp", ".css", ".csv", ".diff",
    ".gradle", ".h", ".hpp", ".html", ".java", ".js", ".json", ".jsx",
    ".aidl", ".bat", ".jsonl", ".kt", ".kts", ".lock", ".m", ".md",
    ".mjs", ".mm", ".patch", ".plist", ".pro", ".properties", ".ps1",
    ".py", ".rs", ".scss", ".sh", ".sql", ".svg", ".swift", ".toml",
    ".ts", ".tsv", ".tsx", ".txt", ".xml", ".yaml", ".yml",
}
EXPLICIT_TEXT_NAMES = {
    ".dockerignore", ".editorconfig", ".gitattributes", ".gitignore", ".npmrc",
    ".prettierrc", "CHANGELOG", "CODEOWNERS", "COPYING", "Dockerfile", "gradlew",
    "LICENSE", "Makefile", "NOTICE", "README",
}
CONTAINER_SUFFIXES = (
    ".apk", ".aar", ".jar", ".zip", ".tar", ".tgz", ".tar.gz", ".gz",
    ".xz", ".lzma", ".bz2",
)
UNSUPPORTED_CONTAINER_SUFFIXES = (".7z", ".zst", ".zstd")
REPO_SKIPPED_PARTS = {
    ".git", ".gradle", "build", "node_modules", "target", "dist", "__pycache__",
}
PROFILES = {"repository", "evidence", "product", "test"}
BASE64_LITERAL = re.compile(r'''["']([A-Za-z0-9+/]{128,}={0,2})["']''')
HEX_LITERAL = re.compile(r'''["']([0-9A-Fa-f]{256,})["']''')
BYTE_LITERAL = re.compile(r"0x([0-9A-Fa-f]{2})")
BYTE_ARRAY_BLOCK = re.compile(
    r"(?:0x[0-9A-Fa-f]{2}(?:\.toByte\(\))?\s*,\s*){127,}"
    r"0x[0-9A-Fa-f]{2}(?:\.toByte\(\))?"
)
DECIMAL_BYTE_ARRAY_BLOCK = re.compile(
    r"(?:byteArrayOf\s*\(|new\s+byte\s*\[\s*\]\s*\{|ByteArray\s*\([^)]*\)\s*\{)"
    r"(?P<body>[^)}]{0,65536})[)}]",
    re.IGNORECASE | re.DOTALL,
)
DECIMAL_BYTE_TOKEN = re.compile(r"(?<![A-Za-z0-9_])-?[0-9]{1,3}(?:\.toByte\(\))?(?![A-Za-z0-9_])")
QUOTED_BASE64_FRAGMENT = re.compile(r'''["']([A-Za-z0-9+/]{32,}={0,2})["']''')
QUOTED_HEX_FRAGMENT = re.compile(r'''["']([0-9A-Fa-f]{32,})["']''')
CONCAT_BASE64_LITERAL = re.compile(
    r'''(?:["'][A-Za-z0-9+/]{32,}={0,2}["']\s*\+\s*)+["'][A-Za-z0-9+/]{32,}={0,2}["']'''
)
CONCAT_HEX_LITERAL = re.compile(
    r'''(?:["'][0-9A-Fa-f]{32,}["']\s*\+\s*)+["'][0-9A-Fa-f]{32,}["']'''
)
DECODE_SOURCE = re.compile(
    r"base64\s*\.\s*(?:decode|getdecoder)|decodebase64|fromhex|decodehex|"
    r"gzipinputstream|gzdecode|gzinflate|inflater(?:inputstream)?",
    re.IGNORECASE,
)
RIME_STORE_SOURCE = re.compile(
    r"(?:(?:rime(?:path|dir|store|data|resource|asset|userdb)|(?:assets?|files?)[/\\]rime)"
    r".{0,160}\b(?:write|store|outputstream|filesdir)\w*\b|"
    r"\b(?:write|store|outputstream|filesdir)\w*\b.{0,160}"
    r"(?:rime(?:path|dir|store|data|resource|asset|userdb)|(?:assets?|files?)[/\\]rime))",
    re.IGNORECASE | re.DOTALL,
)


class PolicyError(RuntimeError):
    """The KSP-012 policy, input, or artifact violated a fail-closed rule."""


@dataclass(frozen=True)
class SyntheticFixture:
    fixture_id: str
    source_path: str
    apk_path: str
    bytes: int
    sha256: str


@dataclass(frozen=True)
class NativeEngineBaseline:
    engine_id: str
    path: str
    bytes: int
    sha256: str
    profiles: frozenset[str]


@dataclass(frozen=True)
class ArtifactExpectation:
    sha256: str
    profiles: frozenset[str]
    fixture_ids: frozenset[str]
    native_engine_ids: frozenset[str]


@dataclass(frozen=True)
class OpaqueBinaryBaseline:
    path: str
    bytes: int
    sha256: str
    classification: str


@dataclass(frozen=True)
class ReviewedDynamicSource:
    path: str
    bytes: int
    sha256: str
    classification: str


@dataclass(frozen=True)
class Limits:
    max_path_bytes: int
    max_path_depth: int
    max_container_depth: int
    max_container_members: int
    max_entry_bytes: int
    max_total_expanded_bytes: int
    max_compression_ratio: int


@dataclass(frozen=True)
class Contract:
    policy_sha256: str
    import_schema_sha256: str
    fixtures: tuple[SyntheticFixture, ...]
    native_engines: tuple[NativeEngineBaseline, ...]
    artifact_expectations: tuple[ArtifactExpectation, ...]
    opaque_binaries: tuple[OpaqueBinaryBaseline, ...]
    reviewed_dynamic_sources: tuple[ReviewedDynamicSource, ...]
    trusted_tree_manifest_sha256: frozenset[str]
    profile_fixture_ids: dict[str, frozenset[str]]
    limits: Limits


@dataclass(frozen=True)
class Finding:
    code: str
    path: str
    profile: str
    detail: str


@dataclass
class ScanState:
    profile: str
    contract: Contract
    enumerated_files: int = 0
    inspected_files: int = 0
    scanned_containers: int = 0
    scanned_container_members: int = 0
    expanded_bytes: int = 0
    real_xiaohe_resources: int = 0
    synthetic_evidence_fixtures: int = 0
    exact_native_engines: int = 0
    forbidden_rime_resources: int = 0
    findings: list[Finding] = field(default_factory=list)
    production_decoder_paths: set[str] = field(default_factory=set)
    production_rime_store_paths: set[str] = field(default_factory=set)
    reviewed_dynamic_source_paths: set[str] = field(default_factory=set)
    current_artifact_fixture_ids: set[str] = field(default_factory=set)
    current_artifact_native_ids: set[str] = field(default_factory=set)
    current_artifact_assets: set[str] = field(default_factory=set)
    manifest_records: list[tuple[str, int, str]] = field(default_factory=list)
    deferred_opaque_binaries: list[tuple[str, int, str]] = field(default_factory=list)

    def reject(self, code: str, path: str, detail: str) -> None:
        self.findings.append(Finding(code, path, self.profile, detail))


def _reject_duplicate_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise PolicyError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _load_json(path: Path) -> dict[str, object]:
    if not path.is_file() or path.is_symlink():
        raise PolicyError(f"required JSON file is missing or unsafe: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_reject_duplicate_pairs)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise PolicyError(f"invalid JSON: {path}") from error
    if not isinstance(value, dict):
        raise PolicyError(f"JSON root must be an object: {path}")
    return value


def _canonical_sha256(value: object) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _exact_keys(value: dict[str, object], expected: set[str], label: str) -> None:
    actual = set(value)
    if actual != expected:
        raise PolicyError(
            f"{label} keys mismatch; missing={sorted(expected - actual)}, "
            f"extra={sorted(actual - expected)}"
        )


def _require_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise PolicyError(f"{label} must be a non-empty string")
    return value


def _require_int(value: object, label: str, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise PolicyError(f"{label} must be an integer >= {minimum}")
    return value


def _require_sha(value: object, label: str, pattern: re.Pattern[str] = HEX64) -> str:
    text = _require_string(value, label)
    if pattern.fullmatch(text) is None:
        raise PolicyError(f"{label} must be a full lowercase digest")
    return text


def _validate_policy(value: dict[str, object]) -> Contract:
    if _canonical_sha256(value) != TRUSTED_POLICY_CANONICAL_SHA256:
        raise PolicyError("resource policy drifted from the KSP-012 reviewed canonical contract")
    _exact_keys(
        value,
        {
            "schema_version", "policy_id", "task_id", "decision", "reviewed_sources",
            "lgpl_dependency_closure", "native_engine_baseline",
            "synthetic_evidence_fixtures", "artifact_expectations",
            "opaque_binary_baseline", "reviewed_dynamic_source_baseline",
            "trusted_tree_manifest_baseline",
            "profiles", "limits",
        },
        "resource policy",
    )
    if (
        value["schema_version"] != 1
        or value["policy_id"] != "opentypeless-rime-resource-policy"
        or value["task_id"] != "KSP-012"
    ):
        raise PolicyError("unsupported resource policy identity")

    decision = value["decision"]
    if not isinstance(decision, dict):
        raise PolicyError("decision must be an object")
    _exact_keys(
        decision,
        {
            "distribution_mode", "real_xiaohe_bundled_allowlist",
            "real_xiaohe_test_fixture_allowlist", "product_rime_resource_allowlist",
            "network_acquisition", "unverified_user_material",
        },
        "decision",
    )
    if decision != {
        "distribution_mode": "USER_IMPORT_ONLY",
        "real_xiaohe_bundled_allowlist": [],
        "real_xiaohe_test_fixture_allowlist": [],
        "product_rime_resource_allowlist": [],
        "network_acquisition": "FORBIDDEN",
        "unverified_user_material": "LOCAL_ONLY",
    }:
        raise PolicyError("resource distribution decision was weakened")

    sources = value["reviewed_sources"]
    if not isinstance(sources, list) or len(sources) != 2:
        raise PolicyError("reviewed_sources must contain the exact two reviewed identities")
    source_keys = {
        "id", "source_type", "remote", "commit", "path", "blob_sha1", "bytes",
        "license_spdx", "public_redistribution_grant", "bundled", "decision", "reason",
    }
    source_by_id: dict[str, dict[str, object]] = {}
    for index, source in enumerate(sources):
        if not isinstance(source, dict):
            raise PolicyError(f"reviewed_sources[{index}] must be an object")
        _exact_keys(source, source_keys, f"reviewed_sources[{index}]")
        source_id = _require_string(source["id"], f"reviewed_sources[{index}].id")
        if source_id in source_by_id:
            raise PolicyError(f"duplicate reviewed source: {source_id}")
        if source["bundled"] is not False:
            raise PolicyError(f"reviewed source became bundled: {source_id}")
        source_by_id[source_id] = source
    official = source_by_id.get("official-flypy")
    rime = source_by_id.get("rime-double-pinyin-flypy-schema")
    if official is None or rime is None:
        raise PolicyError("reviewed source set mismatch")
    if (
        official["remote"] != "https://www.flypy.cc/"
        or official["license_spdx"] != "NOASSERTION"
        or official["public_redistribution_grant"] is not False
        or official["decision"] != "USER_PROVIDED_ONLY"
    ):
        raise PolicyError("official Flypy no-redistribution decision drifted")
    if (
        rime["commit"] != "01a13287cbd27819be1c34fa1ddc1b3643d5001b"
        or rime["path"] != "double_pinyin_flypy.schema.yaml"
        or rime["blob_sha1"] != "4c78a06b5df625c82904ec2a6b07e161c79cf44a"
        or rime["bytes"] != 3125
        or rime["license_spdx"] != "GPL-3.0-only"
        or rime["decision"] != "REJECTED_FOR_MAIN_PRODUCT"
    ):
        raise PolicyError("reviewed GPL Flypy schema identity drifted")

    closure = value["lgpl_dependency_closure"]
    if not isinstance(closure, list) or len(closure) != 4:
        raise PolicyError("LGPL dependency closure must contain exactly four reviewed inputs")
    closure_keys = {
        "id", "remote", "commit", "license_spdx", "license_blob_sha1", "role", "bundled",
    }
    expected_closure = {"rime-prelude", "rime-luna-pinyin", "rime-stroke", "rime-essay"}
    seen_closure: set[str] = set()
    for index, dependency in enumerate(closure):
        if not isinstance(dependency, dict):
            raise PolicyError(f"lgpl_dependency_closure[{index}] must be an object")
        _exact_keys(dependency, closure_keys, f"lgpl_dependency_closure[{index}]")
        dependency_id = _require_string(dependency["id"], f"dependency[{index}].id")
        if dependency_id in seen_closure:
            raise PolicyError(f"duplicate LGPL dependency: {dependency_id}")
        seen_closure.add(dependency_id)
        _require_sha(dependency["commit"], f"dependency[{index}].commit", HEX40)
        _require_sha(dependency["license_blob_sha1"], f"dependency[{index}].license_blob_sha1", HEX40)
        if dependency["license_spdx"] != "LGPL-3.0-only" or dependency["bundled"] is not False:
            raise PolicyError(f"LGPL dependency decision drifted: {dependency_id}")
    if seen_closure != expected_closure:
        raise PolicyError("LGPL dependency set mismatch")

    raw_native_engines = value["native_engine_baseline"]
    if not isinstance(raw_native_engines, list) or len(raw_native_engines) != 16:
        raise PolicyError("native engine baseline must contain exactly sixteen reviewed identities")
    native_keys = {
        "id", "path", "bytes", "sha256", "profiles", "classification",
        "provenance_reference",
    }
    native_engines: list[NativeEngineBaseline] = []
    seen_native_ids: set[str] = set()
    seen_native_identity: set[tuple[str, str]] = set()
    for index, raw_native in enumerate(raw_native_engines):
        if not isinstance(raw_native, dict):
            raise PolicyError(f"native_engine_baseline[{index}] must be an object")
        _exact_keys(raw_native, native_keys, f"native_engine_baseline[{index}]")
        engine_id = _require_string(raw_native["id"], f"native[{index}].id")
        path = _safe_path(_require_string(raw_native["path"], f"native[{index}].path"), 4096, 32)
        size = _require_int(raw_native["bytes"], f"native[{index}].bytes", 1)
        digest = _require_sha(raw_native["sha256"], f"native[{index}].sha256")
        profiles = raw_native["profiles"]
        if (
            engine_id in seen_native_ids
            or (path, digest) in seen_native_identity
            or not isinstance(profiles, list)
            or not profiles
            or any(profile not in PROFILES for profile in profiles)
            or len(set(profiles)) != len(profiles)
            or raw_native["classification"] not in {
                "ENGINE_ONLY_SOURCE_BUILT_EVIDENCE",
                "PINNED_PRODUCT_NATIVE_RUNTIME",
                "PINNED_REPOSITORY_NATIVE_RUNTIME",
            }
            or not isinstance(raw_native["provenance_reference"], str)
        ):
            raise PolicyError(f"native engine provenance drifted: {path}")
        seen_native_ids.add(engine_id)
        seen_native_identity.add((path, digest))
        native_engines.append(NativeEngineBaseline(
            engine_id=engine_id,
            path=path,
            bytes=size,
            sha256=digest,
            profiles=frozenset(profiles),
        ))

    raw_fixtures = value["synthetic_evidence_fixtures"]
    if not isinstance(raw_fixtures, list) or len(raw_fixtures) != 3:
        raise PolicyError("exactly three synthetic evidence fixtures are required")
    fixture_keys = {
        "id", "source_path", "apk_path", "bytes", "sha256", "license_spdx", "classification",
    }
    fixtures: list[SyntheticFixture] = []
    seen_fixture_ids: set[str] = set()
    seen_fixture_paths: set[str] = set()
    for index, raw_fixture in enumerate(raw_fixtures):
        if not isinstance(raw_fixture, dict):
            raise PolicyError(f"synthetic_evidence_fixtures[{index}] must be an object")
        _exact_keys(raw_fixture, fixture_keys, f"synthetic_evidence_fixtures[{index}]")
        fixture_id = _require_string(raw_fixture["id"], f"fixture[{index}].id")
        source_path = _safe_path(
            _require_string(raw_fixture["source_path"], f"fixture[{index}].source_path"),
            4096,
            32,
        )
        apk_path = _safe_path(
            _require_string(raw_fixture["apk_path"], f"fixture[{index}].apk_path"),
            4096,
            32,
        )
        if fixture_id in seen_fixture_ids or source_path in seen_fixture_paths or apk_path in seen_fixture_paths:
            raise PolicyError("duplicate synthetic fixture identity or path")
        seen_fixture_ids.add(fixture_id)
        seen_fixture_paths.update({source_path, apk_path})
        if (
            raw_fixture["license_spdx"] != "MIT"
            or raw_fixture["classification"] != "EVIDENCE_ONLY_SYNTHETIC"
        ):
            raise PolicyError(f"synthetic fixture classification drifted: {fixture_id}")
        fixtures.append(SyntheticFixture(
            fixture_id=fixture_id,
            source_path=source_path,
            apk_path=apk_path,
            bytes=_require_int(raw_fixture["bytes"], f"fixture[{index}].bytes", 1),
            sha256=_require_sha(raw_fixture["sha256"], f"fixture[{index}].sha256"),
        ))

    raw_expectations = value["artifact_expectations"]
    if not isinstance(raw_expectations, list) or len(raw_expectations) != 56:
        raise PolicyError("artifact expectations must contain the exact fifty-six reviewed APKs")
    expectation_keys = {
        "sha256", "profiles", "required_synthetic_fixture_ids", "required_native_engine_ids",
    }
    artifact_expectations: list[ArtifactExpectation] = []
    seen_artifacts: set[str] = set()
    for index, raw_expectation in enumerate(raw_expectations):
        if not isinstance(raw_expectation, dict):
            raise PolicyError(f"artifact_expectations[{index}] must be an object")
        _exact_keys(raw_expectation, expectation_keys, f"artifact_expectations[{index}]")
        digest = _require_sha(raw_expectation["sha256"], f"artifact[{index}].sha256")
        profiles = raw_expectation["profiles"]
        fixture_ids = raw_expectation["required_synthetic_fixture_ids"]
        native_ids = raw_expectation["required_native_engine_ids"]
        if (
            digest in seen_artifacts
            or not isinstance(profiles, list)
            or not profiles
            or any(profile not in PROFILES for profile in profiles)
            or len(set(profiles)) != len(profiles)
            or not isinstance(fixture_ids, list)
            or len(set(fixture_ids)) != len(fixture_ids)
            or not set(fixture_ids) <= seen_fixture_ids
            or not isinstance(native_ids, list)
            or len(set(native_ids)) != len(native_ids)
            or not set(native_ids) <= seen_native_ids
        ):
            raise PolicyError(f"artifact expectation is invalid: {digest}")
        seen_artifacts.add(digest)
        artifact_expectations.append(ArtifactExpectation(
            sha256=digest,
            profiles=frozenset(profiles),
            fixture_ids=frozenset(fixture_ids),
            native_engine_ids=frozenset(native_ids),
        ))

    raw_opaque_binaries = value["opaque_binary_baseline"]
    if not isinstance(raw_opaque_binaries, list) or len(raw_opaque_binaries) != 24:
        raise PolicyError("opaque binary baseline must contain exactly 24 reviewed identities")
    opaque_binaries: list[OpaqueBinaryBaseline] = []
    seen_opaque_paths: set[str] = set()
    for index, raw_binary in enumerate(raw_opaque_binaries):
        if not isinstance(raw_binary, dict):
            raise PolicyError(f"opaque_binary_baseline[{index}] must be an object")
        _exact_keys(
            raw_binary,
            {"path", "bytes", "sha256", "classification"},
            f"opaque_binary_baseline[{index}]",
        )
        path = _safe_path(_require_string(raw_binary["path"], f"opaque[{index}].path"), 4096, 32)
        if (
            path in seen_opaque_paths
            or raw_binary["classification"] not in {
                "PINNED_BINARY_CONTAINER", "REVIEWED_STATIC_MEDIA",
            }
        ):
            raise PolicyError(f"opaque binary baseline identity is invalid: {path}")
        seen_opaque_paths.add(path)
        opaque_binaries.append(OpaqueBinaryBaseline(
            path=path,
            bytes=_require_int(raw_binary["bytes"], f"opaque[{index}].bytes", 1),
            sha256=_require_sha(raw_binary["sha256"], f"opaque[{index}].sha256"),
            classification=raw_binary["classification"],
        ))

    raw_dynamic_sources = value["reviewed_dynamic_source_baseline"]
    if not isinstance(raw_dynamic_sources, list) or len(raw_dynamic_sources) != 8:
        raise PolicyError("reviewed dynamic source baseline must contain exactly 8 identities")
    reviewed_dynamic_sources: list[ReviewedDynamicSource] = []
    seen_dynamic_paths: set[str] = set()
    for index, raw_source in enumerate(raw_dynamic_sources):
        if not isinstance(raw_source, dict):
            raise PolicyError(f"reviewed_dynamic_source_baseline[{index}] must be an object")
        _exact_keys(
            raw_source,
            {"path", "bytes", "sha256", "classification"},
            f"reviewed_dynamic_source_baseline[{index}]",
        )
        path = _safe_path(
            _require_string(raw_source["path"], f"dynamic_source[{index}].path"),
            4096,
            32,
        )
        if (
            path in seen_dynamic_paths
            or raw_source["classification"] not in {
                "RIM003_LOCAL_IMPORTER", "PINNED_EXISTING_DECODER",
            }
        ):
            raise PolicyError(f"reviewed dynamic source identity is invalid: {path}")
        seen_dynamic_paths.add(path)
        reviewed_dynamic_sources.append(ReviewedDynamicSource(
            path=path,
            bytes=_require_int(raw_source["bytes"], f"dynamic_source[{index}].bytes", 1),
            sha256=_require_sha(raw_source["sha256"], f"dynamic_source[{index}].sha256"),
            classification=raw_source["classification"],
        ))

    raw_tree_baselines = value["trusted_tree_manifest_baseline"]
    if not isinstance(raw_tree_baselines, list) or len(raw_tree_baselines) != 1:
        raise PolicyError("trusted tree baseline must contain exactly the KSP-011 replay")
    tree_baseline = raw_tree_baselines[0]
    if not isinstance(tree_baseline, dict):
        raise PolicyError("trusted tree baseline entry must be an object")
    _exact_keys(
        tree_baseline,
        {"profile", "manifest_sha256", "classification"},
        "trusted tree baseline",
    )
    if (
        tree_baseline["profile"] != "repository"
        or tree_baseline["classification"] != "KSP011_EXACT_REPLAY_TREE"
    ):
        raise PolicyError("trusted tree baseline classification drifted")
    trusted_tree_manifest_sha256 = frozenset({
        _require_sha(tree_baseline["manifest_sha256"], "trusted tree manifest sha256")
    })

    raw_profiles = value["profiles"]
    if not isinstance(raw_profiles, dict):
        raise PolicyError("profiles must be an object")
    _exact_keys(raw_profiles, PROFILES, "profiles")
    profile_fixture_ids: dict[str, frozenset[str]] = {}
    profile_keys = {"allowed_synthetic_fixture_ids", "resource_mode", "allow_real_xiaohe"}
    for profile_name in sorted(PROFILES):
        raw_profile = raw_profiles[profile_name]
        if not isinstance(raw_profile, dict):
            raise PolicyError(f"profile {profile_name} must be an object")
        _exact_keys(raw_profile, profile_keys, f"profile {profile_name}")
        allowed = raw_profile["allowed_synthetic_fixture_ids"]
        if not isinstance(allowed, list) or any(not isinstance(item, str) for item in allowed):
            raise PolicyError(f"profile {profile_name} fixture allowlist must be a string list")
        allowed_set = frozenset(allowed)
        if len(allowed_set) != len(allowed) or not allowed_set <= seen_fixture_ids:
            raise PolicyError(f"profile {profile_name} fixture allowlist is invalid")
        expected_allowed = seen_fixture_ids if profile_name in {"repository", "evidence"} else set()
        expected_mode = (
            "EXACT_SYNTHETIC_EVIDENCE_ONLY"
            if profile_name in {"repository", "evidence"}
            else "NO_RIME_DATA_RESOURCES"
        )
        if (
            allowed_set != expected_allowed
            or raw_profile["resource_mode"] != expected_mode
            or raw_profile["allow_real_xiaohe"] is not False
        ):
            raise PolicyError(f"profile {profile_name} weakened the zero-bundle decision")
        profile_fixture_ids[profile_name] = allowed_set

    raw_limits = value["limits"]
    if not isinstance(raw_limits, dict):
        raise PolicyError("limits must be an object")
    limit_keys = {
        "max_path_bytes", "max_path_depth", "max_container_depth", "max_container_members",
        "max_entry_bytes", "max_total_expanded_bytes", "max_compression_ratio",
    }
    _exact_keys(raw_limits, limit_keys, "limits")
    limits = Limits(**{
        key: _require_int(raw_limits[key], f"limits.{key}", 1) for key in limit_keys
    })
    return Contract(
        policy_sha256=TRUSTED_POLICY_CANONICAL_SHA256,
        import_schema_sha256=TRUSTED_IMPORT_SCHEMA_CANONICAL_SHA256,
        fixtures=tuple(fixtures),
        native_engines=tuple(native_engines),
        artifact_expectations=tuple(artifact_expectations),
        opaque_binaries=tuple(opaque_binaries),
        reviewed_dynamic_sources=tuple(reviewed_dynamic_sources),
        trusted_tree_manifest_sha256=trusted_tree_manifest_sha256,
        profile_fixture_ids=profile_fixture_ids,
        limits=limits,
    )


def _validate_import_schema(value: dict[str, object]) -> None:
    if _canonical_sha256(value) != TRUSTED_IMPORT_SCHEMA_CANONICAL_SHA256:
        raise PolicyError("Rime import manifest schema drifted from the KSP-012 canonical contract")
    _exact_keys(
        value,
        {
            "$schema", "$id", "title", "type", "additionalProperties", "required",
            "properties", "$defs",
        },
        "import manifest schema",
    )
    if (
        value["$schema"] != "https://json-schema.org/draft/2020-12/schema"
        or value["type"] != "object"
        or value["additionalProperties"] is not False
    ):
        raise PolicyError("import manifest schema root is not fail closed")
    properties = value["properties"]
    if not isinstance(properties, dict):
        raise PolicyError("import manifest schema properties must be an object")
    expected_properties = {
        "format", "version", "entrypoint", "networkAccess", "autoUpdate",
        "fileSetPolicy", "packageId", "packageVersion", "displayName", "sourceUrl",
        "sourceRevision", "author", "rightsholder", "licenseExpression",
        "licenseTextPath", "noticePaths", "usageBasis", "trustState",
        "distributionScope", "compatibleLibrime", "selectedSchemas", "files",
        "dependencies",
    }
    if set(properties) != expected_properties:
        raise PolicyError("import manifest top-level field contract drifted")
    required = value["required"]
    if (
        not isinstance(required, list)
        or len(required) != len(properties)
        or set(required) != set(properties)
    ):
        raise PolicyError("every import manifest root property must be required")
    expected_constants = {
        "format": "opentypeless.rime-resource-manifest",
        "version": 1,
        "entrypoint": "ANDROID_SAF_OPEN_DOCUMENT",
        "networkAccess": False,
        "autoUpdate": False,
        "fileSetPolicy": "EXACT_MANIFEST_ONLY",
        "usageBasis": "USER_PROVIDED_UNVERIFIED",
        "trustState": "USER_PROVIDED_UNVERIFIED",
        "distributionScope": "LOCAL_ONLY",
    }
    for field, expected in expected_constants.items():
        raw = properties.get(field)
        if not isinstance(raw, dict) or raw.get("const") != expected:
            raise PolicyError(f"import manifest {field} safety constant drifted")
    definitions = value["$defs"]
    if not isinstance(definitions, dict) or set(definitions) != {
        "packageId", "versionString", "safeRelativePath", "fileEntry", "dependencyEntry"
    }:
        raise PolicyError("import manifest definitions drifted")
    for name in ("compatibleLibrime",):
        item = properties.get(name)
        if not isinstance(item, dict) or item.get("additionalProperties") is not False:
            raise PolicyError(f"import manifest {name} must reject unknown keys")
    files = properties.get("files")
    if not isinstance(files, dict) or files.get("maxItems") != 512:
        raise PolicyError("import manifest file-set bounds drifted")
    if files.get("items") != {"$ref": "#/$defs/fileEntry"}:
        raise PolicyError("import manifest files must use the closed file entry contract")
    file_entry = definitions.get("fileEntry")
    if not isinstance(file_entry, dict) or file_entry.get("additionalProperties") is not False:
        raise PolicyError("import manifest file entries must reject unknown keys")
    file_properties = file_entry.get("properties")
    if not isinstance(file_properties, dict) or set(file_properties) != {"path", "size", "sha256", "role"}:
        raise PolicyError("import manifest exact file identity contract drifted")
    roles = file_properties.get("role", {}).get("enum")
    if not isinstance(roles, list) or any(
        token in role.casefold() for role in roles for token in ("native", "lua", "userdb", "executable")
    ):
        raise PolicyError("import manifest roles are not data-only")
    dependencies = properties.get("dependencies")
    dependency_entry = definitions.get("dependencyEntry")
    if (
        not isinstance(dependencies, dict)
        or dependencies.get("items") != {"$ref": "#/$defs/dependencyEntry"}
        or not isinstance(dependency_entry, dict)
        or dependency_entry.get("additionalProperties") is not False
    ):
        raise PolicyError("import manifest dependency closure is not closed-world")
    dependency_properties = dependency_entry.get("properties")
    if not isinstance(dependency_properties, dict) or set(dependency_properties) != {
        "packageId", "packageVersion", "sourceRevision", "licenseExpression", "files"
    }:
        raise PolicyError("import manifest dependency identity/closure contract drifted")
    guarded_pattern = "^[^\\u0000-\\u001f\\u007f-\\u009f\\u061c\\u200e\\u200f\\u2028-\\u202e\\u2066-\\u2069]+$"
    for field in (
        "displayName", "sourceUrl", "sourceRevision", "author", "rightsholder",
        "licenseExpression",
    ):
        raw = properties.get(field)
        if not isinstance(raw, dict) or raw.get("pattern") != guarded_pattern:
            raise PolicyError(f"import manifest {field} display-safety guard drifted")
    if definitions.get("versionString", {}).get("pattern") != guarded_pattern:
        raise PolicyError("import manifest version display-safety guard drifted")
    for field in ("sourceRevision", "licenseExpression"):
        raw = dependency_properties.get(field)
        if not isinstance(raw, dict) or raw.get("pattern") != guarded_pattern:
            raise PolicyError(f"dependency {field} display-safety guard drifted")


def load_contract(repo_root: Path) -> Contract:
    policy = _load_json(repo_root / POLICY_REL)
    schema = _load_json(repo_root / IMPORT_SCHEMA_REL)
    contract = _validate_policy(policy)
    _validate_import_schema(schema)
    return contract


MANIFEST_ROOT_KEYS = {
    "format", "version", "entrypoint", "networkAccess", "autoUpdate",
    "fileSetPolicy", "packageId", "packageVersion", "displayName", "sourceUrl",
    "sourceRevision", "author", "rightsholder", "licenseExpression",
    "licenseTextPath", "noticePaths", "usageBasis", "trustState",
    "distributionScope", "compatibleLibrime", "selectedSchemas", "files",
    "dependencies",
}
MANIFEST_FILE_KEYS = {"path", "size", "sha256", "role"}
MANIFEST_DEPENDENCY_KEYS = {
    "packageId", "packageVersion", "sourceRevision", "licenseExpression", "files",
}
MANIFEST_ROLES = {
    "SCHEMA_YAML", "DICTIONARY_YAML", "CONFIG_YAML", "TEXT_TABLE",
    "OPENCC_CONFIG", "OPENCC_DATA", "LICENSE_TEXT", "NOTICE_TEXT",
    "PROVENANCE_TEXT",
}


def _manifest_text(value: object, label: str, maximum: int) -> str:
    text = _require_string(value, label)
    if len(text) > maximum or any(character in CONTROL_OR_BIDI for character in text):
        raise PolicyError(f"{label} contains unsafe display characters or exceeds its bound")
    return text


def _semantic_file_entries(
    raw_entries: object,
    label: str,
    seen_paths: dict[str, str],
) -> dict[str, str]:
    if not isinstance(raw_entries, list) or not 1 <= len(raw_entries) <= 512:
        raise PolicyError(f"{label} must contain 1..512 exact files")
    roles_by_path: dict[str, str] = {}
    for index, raw_entry in enumerate(raw_entries):
        if not isinstance(raw_entry, dict):
            raise PolicyError(f"{label}[{index}] must be an object")
        _exact_keys(raw_entry, MANIFEST_FILE_KEYS, f"{label}[{index}]")
        path = _safe_path(_require_string(raw_entry["path"], f"{label}[{index}].path"), 4096, 32)
        key = _path_key(path)
        if key in seen_paths:
            raise PolicyError(f"manifest NFC/case path collision: {seen_paths[key]} vs {path}")
        seen_paths[key] = path
        _require_int(raw_entry["size"], f"{label}[{index}].size", 1)
        if raw_entry["size"] > 67108864:
            raise PolicyError(f"{label}[{index}].size exceeds the frozen bound")
        _require_sha(raw_entry["sha256"], f"{label}[{index}].sha256")
        role = raw_entry["role"]
        if role not in MANIFEST_ROLES:
            raise PolicyError(f"{label}[{index}].role is not data-only")
        roles_by_path[path] = role
    return roles_by_path


def validate_import_manifest_semantics(
    manifest: dict[str, object],
    actual_file_paths: set[str] | None = None,
) -> None:
    """Enforce cross-field closed-world rules JSON Schema cannot express alone."""
    if not isinstance(manifest, dict):
        raise PolicyError("Rime import manifest root must be an object")
    _exact_keys(manifest, MANIFEST_ROOT_KEYS, "Rime import manifest")
    constants = {
        "format": "opentypeless.rime-resource-manifest",
        "version": 1,
        "entrypoint": "ANDROID_SAF_OPEN_DOCUMENT",
        "networkAccess": False,
        "autoUpdate": False,
        "fileSetPolicy": "EXACT_MANIFEST_ONLY",
        "usageBasis": "USER_PROVIDED_UNVERIFIED",
        "trustState": "USER_PROVIDED_UNVERIFIED",
        "distributionScope": "LOCAL_ONLY",
    }
    for field, expected in constants.items():
        if manifest[field] != expected:
            raise PolicyError(f"Rime import manifest {field} safety constant mismatch")
    for field, maximum in {
        "packageVersion": 64, "displayName": 128, "sourceRevision": 128,
        "author": 256, "rightsholder": 256, "licenseExpression": 256,
    }.items():
        _manifest_text(manifest[field], field, maximum)
    package_id = _manifest_text(manifest["packageId"], "packageId", 128)
    if re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", package_id) is None:
        raise PolicyError("packageId is invalid")
    source_url = manifest["sourceUrl"]
    if source_url is not None:
        _manifest_text(source_url, "sourceUrl", 2048)
    compatibility = manifest["compatibleLibrime"]
    if not isinstance(compatibility, dict):
        raise PolicyError("compatibleLibrime must be an object")
    _exact_keys(
        compatibility,
        {"minimumVersion", "maximumVersionExclusive"},
        "compatibleLibrime",
    )
    _manifest_text(compatibility["minimumVersion"], "minimumVersion", 64)
    _manifest_text(compatibility["maximumVersionExclusive"], "maximumVersionExclusive", 64)

    seen_paths: dict[str, str] = {}
    root_files = _semantic_file_entries(manifest["files"], "files", seen_paths)
    dependencies = manifest["dependencies"]
    if not isinstance(dependencies, list) or len(dependencies) > 64:
        raise PolicyError("dependencies must contain at most 64 entries")
    dependency_ids = {_path_key(package_id)}
    for index, dependency in enumerate(dependencies):
        if not isinstance(dependency, dict):
            raise PolicyError(f"dependencies[{index}] must be an object")
        _exact_keys(dependency, MANIFEST_DEPENDENCY_KEYS, f"dependencies[{index}]")
        dependency_id = _manifest_text(dependency["packageId"], f"dependencies[{index}].packageId", 128)
        if re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,127}", dependency_id) is None:
            raise PolicyError(f"dependencies[{index}].packageId is invalid")
        dependency_key = _path_key(dependency_id)
        if dependency_key in dependency_ids:
            raise PolicyError(f"duplicate dependency packageId: {dependency_id}")
        dependency_ids.add(dependency_key)
        for field, maximum in {
            "packageVersion": 64, "sourceRevision": 128, "licenseExpression": 256,
        }.items():
            _manifest_text(dependency[field], f"dependencies[{index}].{field}", maximum)
        _semantic_file_entries(dependency["files"], f"dependencies[{index}].files", seen_paths)

    license_path = _safe_path(_require_string(manifest["licenseTextPath"], "licenseTextPath"), 4096, 32)
    if root_files.get(license_path) != "LICENSE_TEXT":
        raise PolicyError("licenseTextPath must reference a root LICENSE_TEXT file")
    notices = manifest["noticePaths"]
    if not isinstance(notices, list) or len(notices) > 16:
        raise PolicyError("noticePaths must contain at most 16 paths")
    notice_keys: set[str] = set()
    for raw_path in notices:
        path = _safe_path(_require_string(raw_path, "noticePaths item"), 4096, 32)
        key = _path_key(path)
        if key in notice_keys or root_files.get(path) != "NOTICE_TEXT":
            raise PolicyError("noticePaths must uniquely reference root NOTICE_TEXT files")
        notice_keys.add(key)
    selected = manifest["selectedSchemas"]
    if not isinstance(selected, list) or not 1 <= len(selected) <= 32:
        raise PolicyError("selectedSchemas must contain 1..32 schema ids")
    selected_keys: set[str] = set()
    for raw_schema in selected:
        schema_id = _manifest_text(raw_schema, "selectedSchemas item", 128)
        if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", schema_id) is None:
            raise PolicyError(f"invalid selected schema id: {schema_id}")
        key = _path_key(schema_id)
        if key in selected_keys:
            raise PolicyError(f"duplicate selected schema id: {schema_id}")
        selected_keys.add(key)
        expected_path = f"{schema_id}.schema.yaml"
        if root_files.get(expected_path) != "SCHEMA_YAML":
            raise PolicyError(f"selected schema has no exact root file: {schema_id}")
    if actual_file_paths is not None:
        normalized_actual: dict[str, str] = {}
        for raw_path in actual_file_paths:
            path = _safe_path(raw_path, 4096, 32)
            key = _path_key(path)
            if key in normalized_actual:
                raise PolicyError("actual import file set contains an NFC/case collision")
            normalized_actual[key] = path
        if set(normalized_actual) != set(seen_paths):
            raise PolicyError("actual import file set differs from the exact manifest closure")


def _safe_path(path: str, max_bytes: int, max_depth: int) -> str:
    if (
        not path
        or path.startswith("/")
        or re.match(r"^[A-Za-z]:", path) is not None
        or "\\" in path
        or "//" in path
        or path.endswith("/")
        or any(ch in CONTROL_OR_BIDI for ch in path)
    ):
        raise PolicyError(f"unsafe path: {path!r}")
    normalized = unicodedata.normalize("NFC", path)
    if normalized != path:
        raise PolicyError(f"non-NFC path is forbidden: {path!r}")
    if len(path.encode("utf-8")) > max_bytes:
        raise PolicyError(f"path exceeds byte limit: {path!r}")
    parts = PurePosixPath(path).parts
    if not parts or len(parts) > max_depth or any(part in {"", ".", ".."} for part in parts):
        raise PolicyError(f"unsafe path depth or segment: {path!r}")
    return "/".join(parts)


def _path_key(path: str) -> str:
    return unicodedata.normalize("NFC", path).casefold()


def _fixture_for_path(contract: Contract, path: str) -> SyntheticFixture | None:
    for fixture in contract.fixtures:
        if path in {fixture.source_path, fixture.apk_path}:
            return fixture
    return None


def _native_engine_for_blob(
    contract: Contract,
    profile: str,
    path: str,
    size: int,
    digest: str,
) -> NativeEngineBaseline | None:
    for engine in contract.native_engines:
        if (
            path == engine.path
            and size == engine.bytes
            and digest == engine.sha256
            and profile in engine.profiles
        ):
            return engine
    return None


def _is_native_path(path: str) -> bool:
    return path.casefold().endswith(".so")


def _artifact_expectation(
    contract: Contract,
    digest: str,
    profile: str,
) -> ArtifactExpectation | None:
    for expectation in contract.artifact_expectations:
        if expectation.sha256 == digest and profile in expectation.profiles:
            return expectation
    return None


def _opaque_binary_for_blob(
    contract: Contract,
    path: str,
    size: int,
    digest: str,
) -> OpaqueBinaryBaseline | None:
    for baseline in contract.opaque_binaries:
        if path == baseline.path and size == baseline.bytes and digest == baseline.sha256:
            return baseline
    return None


def _defer_opaque_binary(state: ScanState, path: str, data: bytes) -> None:
    identity = (path, len(data), hashlib.sha256(data).hexdigest())
    if identity not in state.deferred_opaque_binaries:
        state.deferred_opaque_binaries.append(identity)


def _finalize_opaque_binary_guards(state: ScanState, allow_trusted_tree: bool) -> None:
    if not state.deferred_opaque_binaries:
        return
    if allow_trusted_tree and _manifest_digest(state) in state.contract.trusted_tree_manifest_sha256:
        state.deferred_opaque_binaries.clear()
        return
    for path, _, _ in state.deferred_opaque_binaries:
        state.reject(
            "UNKNOWN_OPAQUE_BINARY",
            path,
            "opaque binary is outside the exact path/size/SHA-256 or trusted-tree baseline",
        )


def _check_artifact_expectation(
    state: ScanState,
    label: str,
    expectation: ArtifactExpectation,
) -> None:
    expected_assets = {
        fixture.apk_path
        for fixture in state.contract.fixtures
        if fixture.fixture_id in expectation.fixture_ids
    }
    if state.current_artifact_fixture_ids != set(expectation.fixture_ids):
        state.reject(
            "ARTIFACT_FIXTURE_SET_MISMATCH",
            label,
            "exact synthetic fixture set is incomplete or contains extras",
        )
    if state.current_artifact_native_ids != set(expectation.native_engine_ids):
        state.reject(
            "ARTIFACT_NATIVE_SET_MISMATCH",
            label,
            "exact native engine set is incomplete or contains extras",
        )
    if state.current_artifact_assets != expected_assets:
        state.reject(
            "ARTIFACT_ASSET_SET_MISMATCH",
            label,
            "exact APK asset path set is incomplete or contains extras",
        )


def _decode_text(data: bytes) -> str | None:
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def _is_production_source(relative: Path) -> bool:
    if relative.suffix.casefold() not in PRODUCTION_SOURCE_SUFFIXES:
        return False
    folded_parts = tuple(part.casefold() for part in relative.parts)
    if not folded_parts or folded_parts[0] in {"scripts", "benchmarks", "third_party"}:
        return False
    if (
        any(part in {"test", "tests", "androidtest", "__tests__"} for part in folded_parts)
        or relative.name.casefold().startswith("test_")
        or relative.stem.casefold().endswith("test")
    ):
        return False
    if any(
        folded_parts[index:index + 2] == ("src", "main")
        for index in range(len(folded_parts) - 1)
    ):
        return True
    if folded_parts[:2] == ("android", "architecture-tests"):
        return False
    if folded_parts[0] == "android":
        return relative.suffix.casefold() in {".gradle", ".kts", ".py"}
    if folded_parts[0] == "src-tauri":
        return len(folded_parts) > 1 and folded_parts[1] in {"src", "build.rs"}
    return folded_parts[0] == "src" or len(folded_parts) == 1


def _inspect_production_source(
    state: ScanState,
    relative_text: str,
    data: bytes,
    *,
    track_dynamic_store: bool = True,
) -> None:
    state.inspected_files += 1
    reviewed = next(
        (item for item in state.contract.reviewed_dynamic_sources if item.path == relative_text),
        None,
    )
    if reviewed is not None:
        state.reviewed_dynamic_source_paths.add(relative_text)
        digest = hashlib.sha256(data).hexdigest()
        if len(data) != reviewed.bytes or digest != reviewed.sha256:
            state.reject(
                "REVIEWED_DYNAMIC_SOURCE_DRIFT",
                relative_text,
                "reviewed local-import or decoder source identity changed",
            )
    text = _decode_text(data)
    if text is None:
        state.reject("NON_UTF8_PRODUCTION_SOURCE", relative_text, "production source is not UTF-8")
        return
    if track_dynamic_store and DECODE_SOURCE.search(text) is not None:
        state.production_decoder_paths.add(relative_text)
    if track_dynamic_store and RIME_STORE_SOURCE.search(text) is not None:
        state.production_rime_store_paths.add(relative_text)

    direct_kinds, _ = _classify_resource(relative_text, data)
    if "RIME_STRUCTURAL_DATA" in direct_kinds:
        state.forbidden_rime_resources += 1
        state.reject(
            "ENCODED_OR_EMBEDDED_RIME_RESOURCE",
            relative_text,
            "source/document contains structural Rime data rather than metadata",
        )

    decoded_index = 0
    opaque_reported = False
    byte_array_reported = False

    def mark_opaque() -> None:
        nonlocal opaque_reported
        if opaque_reported or not track_dynamic_store:
            return
        opaque_reported = True
        state.forbidden_rime_resources += 1
        state.reject(
            "OPAQUE_PRODUCTION_BLOB",
            relative_text,
            "large encoded or byte-array literal is forbidden in production source",
        )

    for match in BASE64_LITERAL.finditer(text):
        mark_opaque()
        encoded = match.group(1)
        if len(encoded) > state.contract.limits.max_entry_bytes * 2:
            raise PolicyError(f"encoded source literal exceeds byte limit: {relative_text}")
        try:
            decoded = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError):
            continue
        decoded_index += 1
        _scan_blob(
            state,
            f"decoded/base64-{decoded_index}.bin",
            decoded,
            display_path=f"{relative_text}!/decoded-base64-{decoded_index}",
            allow_fixture_baseline=False,
            allow_native_baseline=False,
        )
    for match in HEX_LITERAL.finditer(text):
        mark_opaque()
        encoded = match.group(1)
        if len(encoded) > state.contract.limits.max_entry_bytes * 2:
            raise PolicyError(f"encoded source literal exceeds byte limit: {relative_text}")
        try:
            decoded = bytes.fromhex(encoded)
        except ValueError:
            continue
        decoded_index += 1
        _scan_blob(
            state,
            f"decoded/hex-{decoded_index}.bin",
            decoded,
            display_path=f"{relative_text}!/decoded-hex-{decoded_index}",
            allow_fixture_baseline=False,
            allow_native_baseline=False,
        )
    for concatenation in CONCAT_BASE64_LITERAL.finditer(text):
        base64_fragments = QUOTED_BASE64_FRAGMENT.findall(concatenation.group(0))
        encoded = "".join(base64_fragments)
        if len(encoded) >= 128:
            mark_opaque()
            try:
                decoded = base64.b64decode(encoded, validate=True)
            except (binascii.Error, ValueError):
                decoded = b""
            if decoded:
                decoded_index += 1
                _scan_blob(
                    state,
                    f"decoded/base64-fragments-{decoded_index}.bin",
                    decoded,
                    display_path=f"{relative_text}!/decoded-base64-fragments",
                    allow_fixture_baseline=False,
                    allow_native_baseline=False,
                )
    for concatenation in CONCAT_HEX_LITERAL.finditer(text):
        hex_fragments = QUOTED_HEX_FRAGMENT.findall(concatenation.group(0))
        encoded = "".join(hex_fragments)
        if len(encoded) >= 256 and len(encoded) % 2 == 0:
            mark_opaque()
            decoded = bytes.fromhex(encoded)
            _scan_blob(
                state,
                "decoded/hex-fragments.bin",
                decoded,
                display_path=f"{relative_text}!/decoded-hex-fragments",
                allow_fixture_baseline=False,
                allow_native_baseline=False,
            )
    for block in BYTE_ARRAY_BLOCK.finditer(text):
        byte_literals = BYTE_LITERAL.findall(block.group(0))
        mark_opaque()
        decoded = bytes(int(value, 16) for value in byte_literals)
        _scan_blob(
            state,
            "decoded/byte-array.bin",
            decoded,
            display_path=f"{relative_text}!/decoded-byte-array",
            allow_fixture_baseline=False,
            allow_native_baseline=False,
        )
    for block in DECIMAL_BYTE_ARRAY_BLOCK.finditer(text):
        if track_dynamic_store and not byte_array_reported:
            byte_array_reported = True
            state.forbidden_rime_resources += 1
            state.reject(
                "PRODUCTION_BYTE_ARRAY_LITERAL",
                relative_text,
                "production byte-array literals are forbidden regardless of fragment size or sink name",
            )
        body = block.group("body")
        tokens = DECIMAL_BYTE_TOKEN.findall(body)
        if not tokens:
            continue
        normalized = re.sub(r"\.toByte\(\)", "", body, flags=re.IGNORECASE)
        normalized = re.sub(r"\(\s*byte\s*\)", "", normalized, flags=re.IGNORECASE)
        parts = [part.strip() for part in normalized.split(",")]
        if len(parts) != len(tokens) or any(re.fullmatch(r"-?[0-9]{1,3}", part) is None for part in parts):
            continue
        values = [int(part) for part in parts]
        if any(value < -128 or value > 255 for value in values):
            raise PolicyError(f"decimal byte literal is out of range: {relative_text}")
        mark_opaque()
        decoded = bytes(value % 256 for value in values)
        _scan_blob(
            state,
            "decoded/decimal-byte-array.bin",
            decoded,
            display_path=f"{relative_text}!/decoded-decimal-byte-array",
            allow_fixture_baseline=False,
            allow_native_baseline=False,
        )


def _finalize_production_source_guards(state: ScanState) -> None:
    reviewed_paths = {item.path for item in state.contract.reviewed_dynamic_sources}
    unreviewed_decoders = state.production_decoder_paths - reviewed_paths
    unreviewed_stores = state.production_rime_store_paths - reviewed_paths
    for path in sorted(unreviewed_decoders | unreviewed_stores):
        state.reject(
            "UNREVIEWED_DYNAMIC_SOURCE",
            path,
            "decoder or Rime-storage source is outside the exact reviewed source baseline",
        )
    if (
        state.production_decoder_paths
        and state.production_rime_store_paths
        and (unreviewed_decoders or unreviewed_stores)
    ):
        decoder = sorted(unreviewed_decoders or state.production_decoder_paths)[0]
        store = sorted(unreviewed_stores or state.production_rime_store_paths)[0]
        state.reject(
            "DYNAMIC_DECODE_TO_RIME_STORE",
            store,
            f"unreviewed decoder and Rime storage path coexist; decoder={decoder}",
        )


def _classify_resource(path: str, data: bytes) -> tuple[set[str], bool]:
    lower = path.casefold()
    name = PurePosixPath(lower).name
    text = _decode_text(data)
    folded = text.casefold() if text is not None else ""
    xiao_path = any(token in lower for token in ("xiaoh", "flypy", "小鹤", "小鶴"))
    xiao_content = any(token in folded for token in (
        "double_pinyin_flypy", "小鹤", "小鶴", "小鶴雙拼", "小鹤双拼",
    ))
    rime_path = lower.startswith("rime/") or any(token in lower for token in (
        "/rime/", "/rime-data/", "rime-data/", "userdb", "librime", "opentypeless_rime",
    )) or name.startswith("rime")
    kinds: set[str] = set()

    if lower.endswith((".schema.yaml", ".schema.yml")):
        kinds.add("RIME_SCHEMA")
    if lower.endswith((".dict.yaml", ".dict.yml")):
        kinds.add("RIME_DICTIONARY")
    if rime_path and lower.endswith((".yaml", ".yml", ".txt")):
        kinds.add("RIME_DATA")
    if lower.endswith(".lua"):
        kinds.add("LUA")
    if lower.endswith((".db", ".sqlite", ".sqlite3")) and (rime_path or xiao_path or "userdb" in lower):
        kinds.add("RIME_DATABASE")
    if "userdb" in lower or lower.endswith((".userdb", ".userdb.txt")):
        kinds.add("RIME_USERDB")
    if lower.endswith(".bin") and any(token in lower for token in (
        "prism", "reverse", "table", "rime", "xiaoh", "flypy",
    )):
        kinds.add("RIME_COMPILED_TABLE")

    if text is not None:
        keys = set(YAML_TOP_LEVEL_KEY.findall(text))
        structural_schema = (
            "schema" in keys
            and SCHEMA_ID.search(text) is not None
            and bool(keys & {"engine", "translator", "speller", "switches"})
        )
        structural_dictionary = (
            ("# rime dictionary" in folded or {"name", "version", "sort"} <= keys)
            and DICTIONARY_ROW.search(text) is not None
        )
        if structural_schema or structural_dictionary:
            kinds.add("RIME_STRUCTURAL_DATA")
        if "octagram" in folded:
            kinds.add("GPL_OCTAGRAM")
        if "gpl-2.0-or-later" in folded or "gpl-3.0-only" in folded:
            if lower.endswith(".lua") or rime_path or xiao_path:
                kinds.add("GPL_RIME_PAYLOAD")
    if xiao_path or xiao_content:
        kinds.add("REAL_XIAOHE_MARKER")
    return kinds, bool(xiao_path or xiao_content)


def _evaluate_blob(
    state: ScanState,
    path: str,
    data: bytes,
    *,
    display_path: str | None = None,
    allow_fixture_baseline: bool = True,
    allow_native_baseline: bool = True,
    trusted_parent_binary: bool = False,
    closed_world_binary: bool = False,
) -> None:
    reported_path = display_path or path
    fixture = _fixture_for_path(state.contract, path)
    digest = hashlib.sha256(data).hexdigest()
    if path.casefold().startswith("assets/"):
        state.current_artifact_assets.add(path)
    if fixture is not None:
        if not allow_fixture_baseline:
            state.forbidden_rime_resources += 1
            state.reject(
                "SYNTHETIC_FIXTURE_NESTING",
                reported_path,
                "synthetic fixture is not a direct evidence artifact member",
            )
            return
        if len(data) != fixture.bytes or digest != fixture.sha256:
            state.reject(
                "SYNTHETIC_FIXTURE_DRIFT",
                reported_path,
                "exact fixture bytes or SHA-256 changed",
            )
            return
        if fixture.fixture_id not in state.contract.profile_fixture_ids[state.profile]:
            state.forbidden_rime_resources += 1
            state.reject(
                "SYNTHETIC_FIXTURE_PROFILE",
                reported_path,
                "synthetic fixture is evidence-only",
            )
            return
        state.synthetic_evidence_fixtures += 1
        state.current_artifact_fixture_ids.add(fixture.fixture_id)
        return

    if path.casefold().startswith("assets/"):
        state.forbidden_rime_resources += 1
        state.reject(
            "UNKNOWN_APK_ASSET",
            reported_path,
            "APK assets must match the exact reviewed profile allowlist",
        )
        return

    if _is_native_path(path):
        baseline = _native_engine_for_blob(
            state.contract,
            state.profile,
            path,
            len(data),
            digest,
        )
        if allow_native_baseline and baseline is not None:
            state.exact_native_engines += 1
            state.current_artifact_native_ids.add(baseline.engine_id)
            return
        state.forbidden_rime_resources += 1
        state.reject(
            "UNKNOWN_NATIVE_ENGINE",
            reported_path,
            "native engine is not an exact path/size/SHA-256 provenance baseline",
        )
        return

    kinds, is_real_xiaohe = _classify_resource(path, data)
    if is_real_xiaohe:
        state.real_xiaohe_resources += 1
    if kinds:
        state.forbidden_rime_resources += 1
        state.reject(
            "FORBIDDEN_RIME_RESOURCE",
            reported_path,
            "resource classes=" + ",".join(sorted(kinds)),
        )
        return
    if (
        (closed_world_binary or _decode_text(data) is None)
        and not trusted_parent_binary
        and "!/decoded-" not in reported_path
    ):
        baseline = _opaque_binary_for_blob(
            state.contract,
            path,
            len(data),
            digest,
        )
        if baseline is None:
            _defer_opaque_binary(state, reported_path, data)


def _looks_like_container(path: str, data: bytes) -> bool:
    lower = path.casefold()
    return (
        lower.endswith(CONTAINER_SUFFIXES)
        or data.startswith(b"PK\x03\x04")
        or data.startswith(b"\x1f\x8b")
        or data.startswith(b"\xfd7zXZ\x00")
        or data.startswith(b"BZh")
        or len(data) > 262 and data[257:262] == b"ustar"
    )


def _check_expanded_budget(state: ScanState, baseline: int, path: str) -> None:
    if state.expanded_bytes - baseline > state.contract.limits.max_total_expanded_bytes:
        raise PolicyError(f"expanded byte budget exceeded while scanning {path}")


def _consume_expanded(state: ScanState, amount: int, baseline: int, path: str) -> None:
    state.expanded_bytes += amount
    _check_expanded_budget(state, baseline, path)


def _scan_zip(
    state: ScanState,
    logical_path: str,
    data: bytes,
    depth: int,
    trusted_parent_binary: bool,
    closed_world_binary: bool,
) -> None:
    state.scanned_containers += 1
    baseline = state.expanded_bytes
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        infos = archive.infolist()
        if len(infos) > state.contract.limits.max_container_members:
            raise PolicyError(f"container member limit exceeded: {logical_path}")
        seen: dict[str, str] = {}
        for info in infos:
            is_directory = info.is_dir()
            raw_name = info.filename[:-1] if is_directory and info.filename.endswith("/") else info.filename
            member = _safe_path(
                raw_name,
                state.contract.limits.max_path_bytes,
                state.contract.limits.max_path_depth,
            )
            key = _path_key(member)
            if key in seen:
                raise PolicyError(
                    f"NFC/case path collision in {logical_path}: {seen[key]} vs {member}"
                )
            seen[key] = member
            mode = (info.external_attr >> 16) & 0xFFFF
            if stat.S_ISLNK(mode):
                raise PolicyError(f"symlink member is forbidden: {logical_path}!/{member}")
            if info.flag_bits & 0x1:
                raise PolicyError(f"encrypted member is forbidden: {logical_path}!/{member}")
            if is_directory:
                continue
            file_type = stat.S_IFMT(mode)
            if file_type not in {0, stat.S_IFREG}:
                raise PolicyError(f"special ZIP member is forbidden: {logical_path}!/{member}")
            if info.file_size > state.contract.limits.max_entry_bytes:
                raise PolicyError(f"container entry exceeds byte limit: {logical_path}!/{member}")
            if info.file_size and info.compress_size == 0:
                raise PolicyError(f"invalid compression size: {logical_path}!/{member}")
            if info.compress_size and info.file_size / info.compress_size > state.contract.limits.max_compression_ratio:
                raise PolicyError(f"compression ratio limit exceeded: {logical_path}!/{member}")
            member_data = archive.read(info)
            if len(member_data) != info.file_size:
                raise PolicyError(f"container member size mismatch: {logical_path}!/{member}")
            _consume_expanded(state, len(member_data), baseline, f"{logical_path}!/{member}")
            state.scanned_container_members += 1
            member_display = f"{logical_path}!/{member}"
            _scan_blob(
                state,
                member,
                member_data,
                depth + 1,
                display_path=member_display,
                allow_fixture_baseline=(depth == 0 and logical_path.casefold().endswith(".apk")),
                allow_native_baseline=(
                    depth == 0 and logical_path.casefold().endswith((".apk", ".aar"))
                ),
                trusted_parent_binary=trusted_parent_binary,
                closed_world_binary=closed_world_binary,
            )
            _check_expanded_budget(state, baseline, f"{logical_path}!/{member}")


def _scan_tar(
    state: ScanState,
    logical_path: str,
    data: bytes,
    depth: int,
    trusted_parent_binary: bool,
    closed_world_binary: bool,
) -> None:
    state.scanned_containers += 1
    baseline = state.expanded_bytes
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as archive:
        members = archive.getmembers()
        if len(members) > state.contract.limits.max_container_members:
            raise PolicyError(f"container member limit exceeded: {logical_path}")
        declared_expanded = sum(info.size for info in members if info.isfile())
        if declared_expanded > state.contract.limits.max_total_expanded_bytes:
            raise PolicyError(f"tar expanded byte budget exceeded: {logical_path}")
        if data and declared_expanded / len(data) > state.contract.limits.max_compression_ratio:
            raise PolicyError(f"tar compression ratio limit exceeded: {logical_path}")
        seen: dict[str, str] = {}
        for info in members:
            raw_name = info.name[:-1] if info.isdir() and info.name.endswith("/") else info.name
            member = _safe_path(
                raw_name,
                state.contract.limits.max_path_bytes,
                state.contract.limits.max_path_depth,
            )
            key = _path_key(member)
            if key in seen:
                raise PolicyError(
                    f"NFC/case path collision in {logical_path}: {seen[key]} vs {member}"
                )
            seen[key] = member
            if info.isdir():
                continue
            if not info.isfile():
                raise PolicyError(f"non-regular tar member is forbidden: {logical_path}!/{member}")
            if info.size > state.contract.limits.max_entry_bytes:
                raise PolicyError(f"container entry exceeds byte limit: {logical_path}!/{member}")
            stream = archive.extractfile(info)
            if stream is None:
                raise PolicyError(f"unable to read tar member: {logical_path}!/{member}")
            member_data = stream.read(state.contract.limits.max_entry_bytes + 1)
            if len(member_data) != info.size:
                raise PolicyError(f"container member size mismatch: {logical_path}!/{member}")
            _consume_expanded(state, len(member_data), baseline, f"{logical_path}!/{member}")
            state.scanned_container_members += 1
            _scan_blob(
                state,
                member,
                member_data,
                depth + 1,
                display_path=f"{logical_path}!/{member}",
                allow_fixture_baseline=False,
                allow_native_baseline=False,
                trusted_parent_binary=trusted_parent_binary,
                closed_world_binary=closed_world_binary,
            )
            _check_expanded_budget(state, baseline, f"{logical_path}!/{member}")


def _scan_gzip(
    state: ScanState,
    logical_path: str,
    data: bytes,
    depth: int,
    trusted_parent_binary: bool,
    closed_world_binary: bool,
) -> None:
    state.scanned_containers += 1
    baseline = state.expanded_bytes
    with gzip.GzipFile(fileobj=io.BytesIO(data), mode="rb") as stream:
        expanded = stream.read(state.contract.limits.max_entry_bytes + 1)
    if len(expanded) > state.contract.limits.max_entry_bytes:
        raise PolicyError(f"gzip entry exceeds byte limit: {logical_path}")
    if data and len(expanded) / len(data) > state.contract.limits.max_compression_ratio:
        raise PolicyError(f"gzip compression ratio limit exceeded: {logical_path}")
    _consume_expanded(state, len(expanded), baseline, logical_path)
    state.scanned_container_members += 1
    member = PurePosixPath(logical_path).name
    if member.casefold().endswith(".gz"):
        member = member[:-3] or "payload"
    member = _safe_path(
        member,
        state.contract.limits.max_path_bytes,
        state.contract.limits.max_path_depth,
    )
    _scan_blob(
        state,
        member,
        expanded,
        depth + 1,
        display_path=f"{logical_path}!/{member}",
        allow_fixture_baseline=False,
        allow_native_baseline=False,
        trusted_parent_binary=trusted_parent_binary,
        closed_world_binary=closed_world_binary,
    )
    _check_expanded_budget(state, baseline, logical_path)


def _scan_single_stream(
    state: ScanState,
    logical_path: str,
    data: bytes,
    depth: int,
    codec: str,
    trusted_parent_binary: bool,
    closed_world_binary: bool,
) -> None:
    state.scanned_containers += 1
    baseline = state.expanded_bytes
    limit = state.contract.limits.max_entry_bytes
    if codec == "xz":
        decompressor = lzma.LZMADecompressor()
    elif codec == "bz2":
        decompressor = bz2.BZ2Decompressor()
    else:
        raise AssertionError(codec)
    expanded = decompressor.decompress(data, max_length=limit + 1)
    if len(expanded) > limit or not decompressor.eof:
        raise PolicyError(f"{codec} entry exceeds byte limit or is truncated: {logical_path}")
    if data and len(expanded) / len(data) > state.contract.limits.max_compression_ratio:
        raise PolicyError(f"{codec} compression ratio limit exceeded: {logical_path}")
    _consume_expanded(state, len(expanded), baseline, logical_path)
    state.scanned_container_members += 1
    member = PurePosixPath(logical_path.split("!/")[-1]).name
    suffix = ".bz2" if codec == "bz2" else (".lzma" if member.casefold().endswith(".lzma") else ".xz")
    if member.casefold().endswith(suffix):
        member = member[:-len(suffix)] or "payload"
    member = _safe_path(
        member,
        state.contract.limits.max_path_bytes,
        state.contract.limits.max_path_depth,
    )
    _scan_blob(
        state,
        member,
        expanded,
        depth + 1,
        display_path=f"{logical_path}!/{member}",
        allow_fixture_baseline=False,
        allow_native_baseline=False,
        trusted_parent_binary=trusted_parent_binary,
        closed_world_binary=closed_world_binary,
    )
    _check_expanded_budget(state, baseline, logical_path)


def _is_tar_bytes(data: bytes) -> bool:
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:*"):
            return True
    except (tarfile.TarError, OSError, EOFError, RuntimeError, ValueError, zlib.error):
        return False


def _scan_blob(
    state: ScanState,
    path: str,
    data: bytes,
    depth: int = 0,
    *,
    display_path: str | None = None,
    allow_fixture_baseline: bool = True,
    allow_native_baseline: bool = True,
    trusted_parent_binary: bool = False,
    closed_world_binary: bool = False,
) -> None:
    state.inspected_files += 1
    safe = _safe_path(
        path,
        state.contract.limits.max_path_bytes,
        state.contract.limits.max_path_depth,
    )
    if depth > state.contract.limits.max_container_depth:
        raise PolicyError(f"nested container depth exceeded: {display_path or safe}")
    logical_path = display_path or safe
    lower = safe.casefold()
    if (
        lower.endswith(UNSUPPORTED_CONTAINER_SUFFIXES)
        or data.startswith(b"7z\xbc\xaf'\x1c")
        or data.startswith(b"\x28\xb5\x2f\xfd")
    ):
        state.reject(
            "UNSUPPORTED_OPAQUE_CONTAINER",
            logical_path,
            "7z/zstd containers are forbidden because this verifier cannot inspect them",
        )
        return
    digest = hashlib.sha256(data).hexdigest()
    binary_baseline = _opaque_binary_for_blob(state.contract, safe, len(data), digest)
    trusted_current_binary = trusted_parent_binary or (
        binary_baseline is not None
        and binary_baseline.classification == "PINNED_BINARY_CONTAINER"
    )
    if _looks_like_container(safe, data):
        if not trusted_current_binary:
            _defer_opaque_binary(state, logical_path, data)
        try:
            if zipfile.is_zipfile(io.BytesIO(data)):
                _scan_zip(
                    state, logical_path, data, depth,
                    trusted_current_binary, closed_world_binary,
                )
                return
            if data.startswith(b"\x1f\x8b") and not safe.casefold().endswith((".tar.gz", ".tgz")):
                _scan_gzip(
                    state, logical_path, data, depth,
                    trusted_current_binary, closed_world_binary,
                )
                return
            if _is_tar_bytes(data):
                _scan_tar(
                    state, logical_path, data, depth,
                    trusted_current_binary, closed_world_binary,
                )
                return
            if data.startswith(b"\xfd7zXZ\x00") or safe.casefold().endswith((".xz", ".lzma")):
                _scan_single_stream(
                    state, logical_path, data, depth, "xz",
                    trusted_current_binary, closed_world_binary,
                )
                return
            if data.startswith(b"BZh") or safe.casefold().endswith(".bz2"):
                _scan_single_stream(
                    state, logical_path, data, depth, "bz2",
                    trusted_current_binary, closed_world_binary,
                )
                return
        except PolicyError:
            raise
        except (
            OSError, EOFError, RuntimeError, ValueError, NotImplementedError, zlib.error,
            gzip.BadGzipFile, lzma.LZMAError, tarfile.TarError, zipfile.BadZipFile,
            zipfile.LargeZipFile,
        ) as error:
            raise PolicyError(f"invalid or unsafe container: {logical_path}") from error
        if safe.casefold().endswith(CONTAINER_SUFFIXES):
            raise PolicyError(f"declared container could not be parsed: {logical_path}")
    _evaluate_blob(
        state,
        safe,
        data,
        display_path=logical_path,
        allow_fixture_baseline=allow_fixture_baseline,
        allow_native_baseline=allow_native_baseline,
        trusted_parent_binary=trusted_parent_binary,
        closed_world_binary=closed_world_binary,
    )


def _should_skip_repo_path(relative: Path) -> bool:
    return relative.name == ".DS_Store" or any(part in REPO_SKIPPED_PARTS for part in relative.parts)


def _is_explicit_text_path(relative: Path) -> bool:
    return (
        relative.suffix.casefold() in EXPLICIT_TEXT_SUFFIXES
        or relative.name in EXPLICIT_TEXT_NAMES
    )


def _read_bounded_file(path: Path, limit: int, label: str) -> bytes:
    size = path.stat().st_size
    if size > limit:
        raise PolicyError(f"file exceeds byte limit before read: {label}")
    data = path.read_bytes()
    if len(data) != size:
        raise PolicyError(f"file size changed during read: {label}")
    return data


def _record_manifest_file(state: ScanState, path: str, data: bytes) -> None:
    state.manifest_records.append((path, len(data), hashlib.sha256(data).hexdigest()))


def _scan_patch(state: ScanState, patch_path: Path, patch_label: str) -> None:
    data = _read_bounded_file(
        patch_path,
        state.contract.limits.max_entry_bytes,
        patch_label,
    )
    state.inspected_files += 1
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise PolicyError(f"patch is not UTF-8: {patch_label}") from error
    current_old: str | None = None
    current_new: str | None = None
    old_lines: list[str] = []
    new_lines: list[str] = []
    in_hunk = False

    def flush() -> None:
        nonlocal current_old, current_new, old_lines, new_lines, in_hunk
        if current_old is not None and old_lines:
            _scan_blob(state, current_old, "".join(old_lines).encode("utf-8"))
        if current_new is not None and new_lines:
            _scan_blob(state, current_new, "".join(new_lines).encode("utf-8"))
        current_old = None
        current_new = None
        old_lines = []
        new_lines = []
        in_hunk = False

    for line in text.splitlines(keepends=True):
        if line.startswith("diff --git "):
            flush()
            match = DIFF_HEADER.fullmatch(line.rstrip("\r\n"))
            if match is None:
                raise PolicyError(f"unparsed or quoted diff header: {patch_label}")
            current_old = _safe_path(
                match.group(1), state.contract.limits.max_path_bytes, state.contract.limits.max_path_depth
            )
            current_new = _safe_path(
                match.group(2), state.contract.limits.max_path_bytes, state.contract.limits.max_path_depth
            )
            continue
        if current_new is None:
            continue
        if line.startswith("@@"):
            in_hunk = True
            continue
        if not in_hunk or line.startswith("\\ No newline at end of file"):
            continue
        if line.startswith("+") and not line.startswith("+++"):
            new_lines.append(line[1:])
        elif line.startswith("-") and not line.startswith("---"):
            old_lines.append(line[1:])
        elif line.startswith(" "):
            old_lines.append(line[1:])
            new_lines.append(line[1:])
    flush()


def scan_repository(repo_root: Path, contract: Contract) -> ScanState:
    state = ScanState(profile="repository", contract=contract)
    series = _load_json(repo_root / ROUTE_A_SERIES_REL)
    patches = series.get("patches")
    if not isinstance(patches, list):
        raise PolicyError("Route-A patch series is invalid")
    patch_paths: set[Path] = set()
    for index, patch in enumerate(patches):
        if not isinstance(patch, dict) or not isinstance(patch.get("file"), str):
            raise PolicyError(f"Route-A patch series entry {index} is invalid")
        path = ROUTE_A_SERIES_REL.parent / patch["file"]
        patch_paths.add(path)

    seen: dict[str, str] = {}
    for path in sorted(repo_root.rglob("*")):
        relative = path.relative_to(repo_root)
        if _should_skip_repo_path(relative):
            continue
        if path.is_symlink():
            raise PolicyError(f"repository symlink is forbidden in scanned source: {relative}")
        if path.is_dir():
            continue
        if not path.is_file():
            raise PolicyError(f"repository special file is forbidden: {relative}")
        if path.lstat().st_nlink != 1:
            raise PolicyError(f"repository hardlink is forbidden: {relative}")
        state.enumerated_files += 1
        relative_text = _safe_path(
            relative.as_posix(), contract.limits.max_path_bytes, contract.limits.max_path_depth
        )
        key = _path_key(relative_text)
        if key in seen:
            raise PolicyError(f"repository NFC/case path collision: {seen[key]} vs {relative_text}")
        seen[key] = relative_text
        data = _read_bounded_file(path, contract.limits.max_entry_bytes, relative_text)
        _record_manifest_file(state, relative_text, data)
        if relative in {POLICY_REL, IMPORT_SCHEMA_REL}:
            state.inspected_files += 1
            continue
        fixture = _fixture_for_path(contract, relative_text)
        if fixture is not None:
            _scan_blob(state, relative_text, data)
            continue
        if _decode_text(data) is not None and _is_explicit_text_path(relative):
            _inspect_production_source(
                state,
                relative_text,
                data,
                track_dynamic_store=_is_production_source(relative),
            )
        else:
            _scan_blob(state, relative_text, data, closed_world_binary=True)
    for patch_path in sorted(patch_paths):
        _scan_patch(state, repo_root / patch_path, patch_path.as_posix())
    _finalize_production_source_guards(state)
    _finalize_opaque_binary_guards(state, allow_trusted_tree=False)
    return state


def scan_tree(tree: Path, profile: str, contract: Contract) -> ScanState:
    if profile not in PROFILES:
        raise PolicyError(f"unsupported profile: {profile}")
    if not tree.is_dir() or tree.is_symlink():
        raise PolicyError(f"scan tree is missing or unsafe: {tree}")
    state = ScanState(profile=profile, contract=contract)
    seen: dict[str, str] = {}
    for path in sorted(tree.rglob("*")):
        relative = path.relative_to(tree)
        if ".git" in relative.parts:
            continue
        if path.is_symlink():
            raise PolicyError(f"tree symlink is forbidden: {relative}")
        if path.is_dir():
            continue
        if not path.is_file():
            raise PolicyError(f"tree special file is forbidden: {relative}")
        if path.lstat().st_nlink != 1:
            raise PolicyError(f"tree hardlink is forbidden: {relative}")
        state.enumerated_files += 1
        relative_text = _safe_path(
            relative.as_posix(), contract.limits.max_path_bytes, contract.limits.max_path_depth
        )
        key = _path_key(relative_text)
        if key in seen:
            raise PolicyError(f"tree NFC/case path collision: {seen[key]} vs {relative_text}")
        seen[key] = relative_text
        data = _read_bounded_file(path, contract.limits.max_entry_bytes, relative_text)
        _record_manifest_file(state, relative_text, data)
        fixture = _fixture_for_path(contract, relative_text)
        if fixture is not None:
            _scan_blob(state, relative_text, data)
            continue
        if _decode_text(data) is not None and _is_explicit_text_path(relative):
            _inspect_production_source(
                state,
                relative_text,
                data,
                track_dynamic_store=_is_production_source(relative),
            )
        else:
            _scan_blob(state, relative_text, data, closed_world_binary=True)
    _finalize_production_source_guards(state)
    _finalize_opaque_binary_guards(state, allow_trusted_tree=True)
    return state


def scan_apks(apks: list[Path], profile: str, contract: Contract) -> ScanState:
    if profile not in PROFILES:
        raise PolicyError(f"unsupported profile: {profile}")
    if not apks:
        raise PolicyError("at least one APK is required")
    state = ScanState(profile=profile, contract=contract)
    labels = _stable_input_labels(apks)
    for apk, label in zip(apks, labels):
        if not apk.is_file() or apk.is_symlink():
            raise PolicyError(f"APK is missing or unsafe: {apk}")
        state.enumerated_files += 1
        data = _read_bounded_file(
            apk,
            contract.limits.max_total_expanded_bytes,
            label,
        )
        if not zipfile.is_zipfile(io.BytesIO(data)):
            raise PolicyError(f"APK is not a valid ZIP container: {apk}")
        artifact_digest = hashlib.sha256(data).hexdigest()
        _record_manifest_file(state, label, data)
        state.current_artifact_fixture_ids = set()
        state.current_artifact_native_ids = set()
        state.current_artifact_assets = set()
        expectation = _artifact_expectation(contract, artifact_digest, profile)
        _scan_blob(state, label, data, trusted_parent_binary=True)
        if expectation is None:
            state.reject(
                "UNREVIEWED_ARTIFACT",
                label,
                "APK must have an exact reviewed artifact expectation for this profile",
            )
            continue
        _check_artifact_expectation(state, label, expectation)
    return state


def _stable_input_labels(paths: list[Path]) -> list[str]:
    totals: dict[str, int] = {}
    for path in paths:
        totals[path.name] = totals.get(path.name, 0) + 1
    ordinals: dict[str, int] = {}
    labels: list[str] = []
    for path in paths:
        ordinals[path.name] = ordinals.get(path.name, 0) + 1
        labels.append(
            path.name
            if totals[path.name] == 1
            else f"{path.stem}#{ordinals[path.name]}{path.suffix}"
        )
    return labels


def _input_report(path: Path, label: str, limit: int) -> dict[str, object]:
    if not path.is_file():
        return {"label": label, "bytes": None, "sha256": None}
    size = path.stat().st_size
    if size > limit:
        raise PolicyError(f"input exceeds report hash limit: {path.name}")
    digest = hashlib.sha256()
    consumed = 0
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            consumed += len(chunk)
            if consumed > limit:
                raise PolicyError(f"input grew beyond report hash limit: {path.name}")
            digest.update(chunk)
    if consumed != size:
        raise PolicyError(f"input size changed while hashing: {path.name}")
    return {"label": label, "bytes": size, "sha256": digest.hexdigest()}


def _manifest_digest(state: ScanState) -> str:
    encoded = json.dumps(
        sorted(state.manifest_records),
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _report(state: ScanState, inputs: list[Path]) -> dict[str, object]:
    if state.profile == "repository":
        scan_scope = "WORKING_TREE_AND_TRUSTED_PATCH_QUEUE"
    elif any(path.is_dir() for path in inputs):
        scan_scope = "FILESYSTEM_TREE"
    else:
        scan_scope = "APK_ARTIFACTS"
    return {
        "schema_version": 1,
        "scan_scope": scan_scope,
        "policy_sha256": state.contract.policy_sha256,
        "import_schema_sha256": state.contract.import_schema_sha256,
        "profile": state.profile,
        "inputs": [
            _input_report(path, label, state.contract.limits.max_total_expanded_bytes)
            for path, label in zip(inputs, _stable_input_labels(inputs))
        ],
        "manifest_sha256": _manifest_digest(state),
        "counts": {
            "enumerated_files": state.enumerated_files,
            "inspected_files": state.inspected_files,
            "scanned_containers": state.scanned_containers,
            "scanned_container_members": state.scanned_container_members,
            "expanded_bytes": state.expanded_bytes,
            "real_xiaohe_resources": state.real_xiaohe_resources,
            "synthetic_evidence_fixtures": state.synthetic_evidence_fixtures,
            "exact_native_engines": state.exact_native_engines,
            "forbidden_rime_resources": state.forbidden_rime_resources,
            "violations": len(state.findings),
        },
        "violations": [
            {
                "code": finding.code,
                "path": finding.path,
                "profile": finding.profile,
                "detail": finding.detail,
            }
            for finding in state.findings
        ],
    }


def _write_report(value: dict[str, object], report: Path | None) -> None:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    if report is None:
        sys.stdout.write(encoded)
        return
    if report.exists():
        raise PolicyError(f"report path already exists: {report}")
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(encoded, encoding="utf-8")


def _validate_report_location(report: Path | None, inputs: list[Path]) -> None:
    if report is None:
        return
    resolved = report.resolve(strict=False)
    for source in inputs:
        candidate = source.resolve(strict=True)
        if candidate.is_dir() and (resolved == candidate or resolved.is_relative_to(candidate)):
            raise PolicyError("report path must be outside every scanned directory")
        if candidate.is_file() and resolved == candidate:
            raise PolicyError("report path must not overwrite a scanned input")


def _finish(state: ScanState, inputs: list[Path], report: Path | None) -> None:
    _validate_report_location(report, inputs)
    value = _report(state, inputs)
    _write_report(value, report)
    if state.findings:
        first = state.findings[0]
        raise PolicyError(f"{first.code}: {first.path}: {first.detail}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    verify = subparsers.add_parser(
        "verify",
        help="validate contracts and scan the working tree plus trusted patch queue",
    )
    verify.add_argument("--repo-root", type=Path, required=True)
    verify.add_argument("--report", type=Path)

    tree = subparsers.add_parser("scan-tree", help="scan a materialized source/evidence tree")
    tree.add_argument("--repo-root", type=Path, required=True)
    tree.add_argument("--tree", type=Path, required=True)
    tree.add_argument("--profile", choices=sorted(PROFILES), required=True)
    tree.add_argument("--report", type=Path)

    apk = subparsers.add_parser("scan-apk", help="scan one or more APKs recursively")
    apk.add_argument("--repo-root", type=Path, required=True)
    apk.add_argument("--apk", type=Path, action="append", required=True)
    apk.add_argument("--profile", choices=sorted(PROFILES), required=True)
    apk.add_argument("--report", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        repo_root = args.repo_root.resolve(strict=True)
        contract = load_contract(repo_root)
        if args.command == "verify":
            state = scan_repository(repo_root, contract)
            _finish(state, [repo_root], args.report)
        elif args.command == "scan-tree":
            tree = args.tree.resolve(strict=True)
            state = scan_tree(tree, args.profile, contract)
            _finish(state, [tree], args.report)
        elif args.command == "scan-apk":
            apks = [path.resolve(strict=True) for path in args.apk]
            state = scan_apks(apks, args.profile, contract)
            _finish(state, apks, args.report)
        else:
            raise AssertionError(args.command)
        return 0
    except (OSError, PolicyError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

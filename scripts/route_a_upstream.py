#!/usr/bin/env python3
"""Fail-closed, offline replay verifier for the locked Route-A source queue."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile
import unicodedata
from urllib.parse import urlsplit


LOCK_REL = Path("third_party/keyboard/route_a/upstream-lock.v1.json")
BOUNDARY_REL = Path("third_party/keyboard/route_a/source-boundary.v1.json")
LEGAL_REL = Path("third_party/keyboard/route_a/legal-baseline.v1.json")
SERIES_REL = Path("third_party/keyboard/route_a/patches/series.v1.json")
PATCH_DIR_REL = SERIES_REL.parent

HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")
DIFF_HEADER = re.compile(r"diff --git a/([^\t\r\n ]+) b/([^\t\r\n ]+)")
CONTROL_OR_BIDI = {
    chr(value) for value in range(32)
} | {chr(127), "\u202a", "\u202b", "\u202c", "\u202d", "\u202e", "\u2066", "\u2067", "\u2068", "\u2069"}
MAX_ARCHIVE_MEMBERS = 5_000
MAX_EXPANDED_BYTES = 128 * 1024 * 1024
MAX_ARCHIVE_FILE_BYTES = 32 * 1024 * 1024
MAX_PATH_BYTES = 4_096
MAX_PATH_DEPTH = 32
MAX_PATCH_LINE_BYTES = 1024 * 1024
MAX_ATTRIBUTE_FILE_BYTES = 1024 * 1024
MAX_ATTRIBUTE_FILE_COUNT = 1024
GIT_BINARY = Path("/usr/bin/git")

DANGEROUS_LOCAL_CONFIG_KEYS = {
    "core.attributesfile",
    "core.editor",
    "core.fsmonitor",
    "core.gitproxy",
    "core.hookspath",
    "core.pager",
    "core.sshcommand",
    "core.untrackedcache",
    "core.worktree",
    "credential.helper",
    "extensions.worktreeconfig",
    "gpg.program",
    "interactive.difffilter",
    "sequence.editor",
}
DANGEROUS_LOCAL_CONFIG_PREFIXES = (
    "alias.",
    "diff.",
    "filter.",
    "include.",
    "includeif.",
    "merge.",
    "submodule.",
)
DANGEROUS_ATTRIBUTE = re.compile(
    rb"(?:^|[ \t])[-!]?(?:diff|filter|merge|working-tree-encoding)(?:=|[ \t]|$)",
    re.IGNORECASE,
)


TRUSTED_UPSTREAM = {
    "remote": "https://github.com/florisboard/florisboard.git",
    "display_tag": "v0.5.2",
    "commit": "2e82060251897226c0739b9f52d1d051b02305fb",
    "git_tree": "f1da19f9887f353ada940787387674aad7ab80cd",
    "archive_url": "https://codeload.github.com/florisboard/florisboard/tar.gz/2e82060251897226c0739b9f52d1d051b02305fb",
    "archive_bytes": 20_748_703,
    "archive_sha256": "ba279c66ad4800b8b6242758e734a2f5852d6c1775cb54a538a497b595215594",
    "archive_root": "florisboard-2e82060251897226c0739b9f52d1d051b02305fb",
    "materialized_tree": "5a911de0dc2242f146b3cfcc47c2b8b1dc90f7e5",
    "file_count": 896,
    "gitlinks": [],
    "license": {
        "path": "LICENSE",
        "spdx": "Apache-2.0",
        "sha256": "b7eb0e4356678f0fa7fb53a35c864ec0b3dca6c6602a26c3cb972c13e2041fcf",
    },
}

TRUSTED_COMPONENTS = {
    "jetpref": ("https://github.com/patrickgold/jetpref.git", "d6e12dda6517345dacc3682aa476a8448a71c34b"),
    "librime": ("https://github.com/rime/librime.git", "33e78140250125871856cdc5b42ddc6a5fcd3cd4"),
    "glog": ("https://github.com/google/glog.git", "7b134a5c82c0c0b5698bb6bf7a835b230c5638e4"),
    "googletest": ("https://github.com/google/googletest.git", "f8d7d77c06936315286eb55f8de22cd23c188571"),
    "leveldb": ("https://github.com/google/leveldb.git", "99b3c03b3284f5886f9ef9a4ef703d57373e61be"),
    "leveldb-benchmark": ("https://github.com/google/benchmark.git", "bf585a2789e30585b4e3ce6baf11ef2750b54677"),
    "leveldb-googletest": ("https://github.com/google/googletest.git", "c27acebba3b3c7d94209e0467b0a801db4af73ed"),
    "marisa-trie": ("https://github.com/s-yata/marisa-trie.git", "3e87d53b78e15f2f43783d5e376561a8c9722051"),
    "opencc": ("https://github.com/BYVoid/OpenCC.git", "556ed22496d650bd0b13b6c163be9814637970ae"),
    "yaml-cpp": ("https://github.com/jbeder/yaml-cpp.git", "2f86d13775d119edbb69af52e5f566fd65c6953b"),
    "boost": ("https://archives.boost.io/release/1.89.0/source/boost-1.89.0-cmake.tar.xz", None),
}
TRUSTED_COMPONENTS_CANONICAL_SHA256 = "9af90d3656d1b90ca3642eab7a3c112754584809da3f9565fd846924966ecd05"
TRUSTED_LEGAL_CANONICAL_SHA256 = "a64a5717a1787327f71cee9328df237da08983f67f67144fcb29af9727e3a272"
TRUSTED_SERIES_CANONICAL_SHA256 = "317e2bc7b3438f2da328f323be24aade552d7bfe572d23e3f66c5d9a5099d0da"

TRUSTED_BOUNDARY = {
    "classification": "evidence-only-restricted-source-boundary",
    "allowed_exact_paths": {
        "gradle/verification-metadata.xml",
        "settings.gradle.kts",
        "tools/test_verify_route_a_safety_manifest.py",
        "tools/verify_route_a_safety_manifest.py",
    },
    "allowed_prefixes": {"opentypeless-editor-host/", "route-a-safety-eval/"},
    "forbidden_prefixes": {".git/", ".github/", "app/", "build/", "gradle/caches/"},
    "forbidden_suffixes": {
        ".7z", ".aar", ".apk", ".aab", ".db", ".dex", ".jar", ".model", ".o", ".so",
        ".sqlite", ".sqlite3", ".tar", ".tgz", ".zip",
    },
    "forbidden_patch_markers": {
        "GIT binary patch", "literal ", "delta ", "new file mode 120000", "new file mode 160000",
        "rename from ", "rename to ", "copy from ", "copy to ",
    },
    "max_patch_count": 3,
    "max_total_patch_bytes": 1_100_000,
    "max_touched_paths": 80,
}


class RouteAError(RuntimeError):
    """The locked Route-A source identity or replay contract was violated."""


def _canonical_json_sha256(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _reject_duplicate_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise RouteAError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, object]:
    if not path.is_file() or path.is_symlink():
        raise RouteAError(f"required JSON file is missing or unsafe: {path.name}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_reject_duplicate_pairs)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise RouteAError(f"invalid JSON: {path.name}") from error
    if not isinstance(value, dict):
        raise RouteAError(f"JSON root must be an object: {path.name}")
    return value


def _exact_keys(value: dict[str, object], expected: set[str], label: str) -> None:
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        raise RouteAError(f"{label} keys mismatch; missing={missing}, extra={extra}")


def _require_string(value: object, label: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or (not allow_empty and not value):
        raise RouteAError(f"{label} must be a non-empty string")
    return value


def _require_int(value: object, label: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise RouteAError(f"{label} must be an integer >= {minimum}")
    return value


def _require_sha(value: object, label: str, pattern: re.Pattern[str] = HEX64) -> str:
    text = _require_string(value, label)
    if pattern.fullmatch(text) is None:
        raise RouteAError(f"{label} is not a full lowercase digest")
    return text


def _validate_https_url(value: object, label: str) -> str:
    text = _require_string(value, label)
    parsed = urlsplit(text)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.port not in (None, 443)
        or parsed.query
        or parsed.fragment
    ):
        raise RouteAError(f"{label} must be a credential-free HTTPS URL without query or fragment")
    return text


def _validate_lock(value: dict[str, object]) -> dict[str, object]:
    _exact_keys(value, {"schema_version", "route_id", "upstream", "components"}, "upstream lock")
    if value["schema_version"] != 1 or value["route_id"] != "route-a":
        raise RouteAError("unsupported upstream lock identity")
    upstream = value["upstream"]
    if not isinstance(upstream, dict):
        raise RouteAError("upstream must be an object")
    _exact_keys(upstream, set(TRUSTED_UPSTREAM), "upstream")
    if upstream != TRUSTED_UPSTREAM:
        raise RouteAError("upstream lock drifted from the ADR-0011 fixed identity")
    _validate_https_url(upstream["remote"], "upstream.remote")
    _validate_https_url(upstream["archive_url"], "upstream.archive_url")

    components = value["components"]
    if not isinstance(components, list) or len(components) != len(TRUSTED_COMPONENTS):
        raise RouteAError("components must be the exact fixed component list")
    if _canonical_json_sha256(components) != TRUSTED_COMPONENTS_CANONICAL_SHA256:
        raise RouteAError("component lock drifted from the fully reviewed fixed inventory")
    component_keys = {"name", "role", "remote", "commit", "tree", "archive_sha256", "license"}
    seen: set[str] = set()
    for index, raw_component in enumerate(components):
        if not isinstance(raw_component, dict):
            raise RouteAError(f"components[{index}] must be an object")
        _exact_keys(raw_component, component_keys, f"components[{index}]")
        name = _require_string(raw_component["name"], f"components[{index}].name")
        if name in seen or name not in TRUSTED_COMPONENTS:
            raise RouteAError(f"unexpected or duplicate component: {name}")
        seen.add(name)
        expected_remote, expected_commit = TRUSTED_COMPONENTS[name]
        if raw_component["remote"] != expected_remote or raw_component["commit"] != expected_commit:
            raise RouteAError(f"component identity drift: {name}")
        _validate_https_url(raw_component["remote"], f"components[{index}].remote")
        _require_string(raw_component["role"], f"components[{index}].role")
        _require_string(raw_component["license"], f"components[{index}].license")
        for field, pattern in (("commit", HEX40), ("tree", HEX40), ("archive_sha256", HEX64)):
            field_value = raw_component[field]
            if field_value is not None:
                _require_sha(field_value, f"components[{index}].{field}", pattern)
    if seen != set(TRUSTED_COMPONENTS):
        raise RouteAError("component set mismatch")
    return upstream


def _validate_boundary(value: dict[str, object]) -> dict[str, object]:
    expected_keys = {
        "schema_version", "route_id", "classification", "allowed_exact_paths", "allowed_prefixes",
        "forbidden_prefixes", "forbidden_suffixes", "forbidden_patch_markers", "max_patch_count",
        "max_total_patch_bytes", "max_touched_paths",
    }
    _exact_keys(value, expected_keys, "source boundary")
    if value["schema_version"] != 1 or value["route_id"] != "route-a":
        raise RouteAError("unsupported source boundary identity")
    for key in (
        "allowed_exact_paths", "allowed_prefixes", "forbidden_prefixes", "forbidden_suffixes",
        "forbidden_patch_markers",
    ):
        raw = value[key]
        if not isinstance(raw, list) or any(not isinstance(item, str) or not item for item in raw):
            raise RouteAError(f"source boundary {key} must be a string list")
        if len(raw) != len(set(raw)):
            raise RouteAError(f"source boundary {key} contains duplicates")
        expected = TRUSTED_BOUNDARY[key]
        if set(raw) != expected:
            raise RouteAError(f"source boundary {key} drifted")
    for key in ("classification", "max_patch_count", "max_total_patch_bytes", "max_touched_paths"):
        if value[key] != TRUSTED_BOUNDARY[key]:
            raise RouteAError(f"source boundary {key} drifted")
    for path in value["allowed_exact_paths"]:
        _safe_relative_path(path, "allowed exact path")
    for prefix in value["allowed_prefixes"] + value["forbidden_prefixes"]:
        if not prefix.endswith("/"):
            raise RouteAError("source-boundary prefixes must end in slash")
        if prefix != ".git/":
            _safe_relative_path(prefix[:-1], "source-boundary prefix")
    return value


def _validate_legal(value: dict[str, object]) -> dict[str, object]:
    _exact_keys(
        value,
        {"schema_version", "route_id", "protected_upstream_files", "new_source_license_roots", "required_provenance", "historical_evidence_only_patch"},
        "legal baseline",
    )
    if value["schema_version"] != 1 or value["route_id"] != "route-a":
        raise RouteAError("unsupported legal baseline identity")
    if _canonical_json_sha256(value) != TRUSTED_LEGAL_CANONICAL_SHA256:
        raise RouteAError("legal baseline drifted from the reviewed inventory")
    protected = value["protected_upstream_files"]
    roots = value["new_source_license_roots"]
    provenance = value["required_provenance"]
    history = value["historical_evidence_only_patch"]
    if not isinstance(protected, list) or not protected:
        raise RouteAError("protected_upstream_files must be a non-empty list")
    for index, item in enumerate(protected):
        if not isinstance(item, dict):
            raise RouteAError("protected upstream entry must be an object")
        _exact_keys(item, {"path", "sha256", "spdx"}, f"protected_upstream_files[{index}]")
        _safe_relative_path(_require_string(item["path"], "protected path"), "protected path")
        _require_sha(item["sha256"], "protected sha256")
        _require_string(item["spdx"], "protected spdx")
    if not isinstance(roots, list) or not isinstance(provenance, list):
        raise RouteAError("legal roots and provenance must be lists")
    for index, item in enumerate(roots):
        if not isinstance(item, dict):
            raise RouteAError("license root must be an object")
        _exact_keys(item, {"prefix", "license_path", "sha256", "spdx"}, f"new_source_license_roots[{index}]")
        prefix = _require_string(item["prefix"], "license root prefix")
        if not prefix.endswith("/"):
            raise RouteAError("license root prefix must end in slash")
        _safe_relative_path(prefix[:-1], "license root prefix")
        if (item["license_path"] is None) != (item["sha256"] is None):
            raise RouteAError("license path and digest must both be null or both be present")
        if item["license_path"] is not None:
            _safe_relative_path(_require_string(item["license_path"], "license path"), "license path")
            _require_sha(item["sha256"], "license digest")
        _require_string(item["spdx"], "license root spdx")
    for index, item in enumerate(provenance):
        if not isinstance(item, dict):
            raise RouteAError("provenance entry must be an object")
        _exact_keys(item, {"path", "token"}, f"required_provenance[{index}]")
        _safe_relative_path(_require_string(item["path"], "provenance path"), "provenance path")
        _require_string(item["token"], "provenance token")
    if not isinstance(history, dict):
        raise RouteAError("historical_evidence_only_patch must be an object")
    _exact_keys(history, {"sha256", "bytes", "committed_queue_input", "reason"}, "historical patch")
    _require_sha(history["sha256"], "historical patch sha256")
    _require_int(history["bytes"], "historical patch bytes", minimum=1)
    if history["committed_queue_input"] is not False:
        raise RouteAError("historical binary patch must remain excluded")
    _require_string(history["reason"], "historical patch reason")
    return value


def _safe_relative_path(value: str, label: str) -> str:
    if not value or value.startswith("/") or "\\" in value:
        raise RouteAError(f"unsafe {label}")
    if value != unicodedata.normalize("NFC", value):
        raise RouteAError(f"non-canonical Unicode in {label}")
    if any(character in CONTROL_OR_BIDI for character in value):
        raise RouteAError(f"control or bidi character in {label}")
    if len(value.encode("utf-8")) > MAX_PATH_BYTES:
        raise RouteAError(f"overlong {label}")
    path = PurePosixPath(value)
    if any(part in ("", ".", "..", ".git") for part in path.parts):
        raise RouteAError(f"unsafe {label}")
    if len(path.parts) > MAX_PATH_DEPTH or any(len(part.encode("utf-8")) > 255 for part in path.parts):
        raise RouteAError(f"overdeep or overlong {label}")
    return path.as_posix()


def _path_allowed(path: str, boundary: dict[str, object]) -> bool:
    if path in boundary["allowed_exact_paths"]:
        allowed = True
    else:
        allowed = any(path.startswith(prefix) for prefix in boundary["allowed_prefixes"])
    if not allowed or any(path.startswith(prefix) for prefix in boundary["forbidden_prefixes"]):
        return False
    lowered = path.casefold()
    return not any(lowered.endswith(suffix.casefold()) for suffix in boundary["forbidden_suffixes"])


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _parse_patch(path: Path, boundary: dict[str, object]) -> list[str]:
    if not path.is_file() or path.is_symlink():
        raise RouteAError(f"patch is missing or unsafe: {path.name}")
    data = path.read_bytes()
    if b"\0" in data:
        raise RouteAError(f"patch contains NUL: {path.name}")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise RouteAError(f"patch is not UTF-8 text: {path.name}") from error
    if any(len(line.encode("utf-8")) > MAX_PATCH_LINE_BYTES for line in text.splitlines()):
        raise RouteAError(f"patch line is too large: {path.name}")
    for marker in boundary["forbidden_patch_markers"]:
        if marker in text:
            raise RouteAError(f"forbidden patch marker in {path.name}: {marker.rstrip()}")
    for marker in ("old mode 120000", "new mode 120000", "old mode 160000", "new mode 160000", "Binary files "):
        if marker in text:
            raise RouteAError(f"forbidden patch semantics in {path.name}")
    for line in text.splitlines():
        if line.startswith("new file mode "):
            if line != "new file mode 100644":
                raise RouteAError(f"forbidden new-file mode in {path.name}")
        elif line.startswith(("old mode ", "new mode ", "deleted file mode ")):
            raise RouteAError(f"mode changes and mode-bearing deletions are forbidden in {path.name}")
    matches: list[re.Match[str]] = []
    for line in text.splitlines():
        if not line.startswith("diff --git"):
            continue
        match = DIFF_HEADER.fullmatch(line)
        if match is None:
            raise RouteAError(f"unparsed or quoted diff header is forbidden: {path.name}")
        matches.append(match)
    if not matches:
        raise RouteAError(f"patch has no diff headers: {path.name}")
    paths: list[str] = []
    folded: set[str] = set()
    for match in matches:
        left, right = match.groups()
        if left != right:
            raise RouteAError(f"rename/copy-like diff is forbidden: {path.name}")
        safe = _safe_relative_path(left, "patch path")
        collision_key = unicodedata.normalize("NFC", safe).casefold()
        if collision_key in folded:
            raise RouteAError(f"duplicate or colliding patch path: {safe}")
        folded.add(collision_key)
        if not _path_allowed(safe, boundary):
            raise RouteAError(f"patch path escapes restricted boundary: {safe}")
        paths.append(safe)
    return paths


def _patch_entry_fields(raw: dict[str, object], index: int) -> tuple[int, str, int, str, str, str, list[str]]:
    # The committed v1 schema uses these exact names; aliases are deliberately unsupported.
    expected = {"order", "id", "task_id", "file", "bytes", "sha256", "input_tree", "output_tree", "touched_paths"}
    _exact_keys(raw, expected, f"patches[{index}]")
    order = _require_int(raw["order"], f"patches[{index}].order", minimum=1)
    patch_id = _require_string(raw["id"], f"patches[{index}].id")
    if re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", patch_id) is None:
        raise RouteAError(f"patches[{index}].id has an unsafe shape")
    if raw["task_id"] != "KSP-011":
        raise RouteAError(f"patches[{index}].task_id must remain KSP-011")
    patch_path = _safe_relative_path(_require_string(raw["file"], f"patches[{index}].file"), "patch path")
    size = _require_int(raw["bytes"], f"patches[{index}].bytes", minimum=1)
    digest = _require_sha(raw["sha256"], f"patches[{index}].sha256")
    input_tree = _require_sha(raw["input_tree"], f"patches[{index}].input_tree", HEX40)
    output_tree = _require_sha(raw["output_tree"], f"patches[{index}].output_tree", HEX40)
    touched = raw["touched_paths"]
    if not isinstance(touched, list) or any(not isinstance(item, str) for item in touched):
        raise RouteAError(f"patches[{index}].touched_paths must be a string list")
    if len(touched) != len(set(touched)) or touched != sorted(touched):
        raise RouteAError(f"patches[{index}].touched_paths must be unique and sorted")
    for item in touched:
        _safe_relative_path(item, "declared touched path")
    return order, patch_path, size, digest, input_tree, output_tree, touched


def _validate_series(value: dict[str, object], repo_root: Path, boundary: dict[str, object], upstream: dict[str, object]) -> tuple[list[dict[str, object]], dict[str, object]]:
    expected_top = {"schema_version", "route_id", "classification", "patches", "expected_final"}
    _exact_keys(value, expected_top, "patch series")
    if value["schema_version"] != 1 or value["route_id"] != "route-a":
        raise RouteAError("unsupported patch series identity")
    if value["classification"] != "evidence-only-source-queue":
        raise RouteAError("patch series classification drifted")
    raw_patches = value["patches"]
    if not isinstance(raw_patches, list) or not raw_patches:
        raise RouteAError("patches must be a non-empty list")
    if len(raw_patches) > boundary["max_patch_count"]:
        raise RouteAError("patch count exceeds the fixed boundary")

    expected_result = value["expected_final"]
    if not isinstance(expected_result, dict):
        raise RouteAError("expected_final must be an object")
    _exact_keys(expected_result, {"tree", "file_count", "license_sha256"}, "expected_final")
    _require_sha(expected_result["tree"], "expected_final.tree", HEX40)
    _require_int(expected_result["file_count"], "expected_final.file_count", minimum=1)
    if expected_result["license_sha256"] != upstream["license"]["sha256"]:
        raise RouteAError("expected_final license digest differs from the locked upstream license")

    expected_patch_names: list[str] = []
    normalized: list[dict[str, object]] = []
    previous_tree = upstream["materialized_tree"]
    total_bytes = 0
    total_touched = 0
    previous_order = 0
    for index, raw_patch in enumerate(raw_patches):
        if not isinstance(raw_patch, dict):
            raise RouteAError(f"patches[{index}] must be an object")
        order, patch_name, size, digest, input_tree, output_tree, declared_touched = _patch_entry_fields(raw_patch, index)
        if order <= previous_order:
            raise RouteAError("patch orders must be strictly increasing")
        if input_tree != previous_tree:
            raise RouteAError(f"patch tree chain is broken at {patch_name}")
        if not patch_name.endswith(".patch") or "/" in patch_name:
            raise RouteAError("patch path must be a direct .patch file in the queue directory")
        patch_path = repo_root / PATCH_DIR_REL / patch_name
        actual_size = patch_path.stat().st_size if patch_path.is_file() and not patch_path.is_symlink() else -1
        if actual_size != size or sha256_file(patch_path) != digest:
            raise RouteAError(f"patch bytes or digest mismatch: {patch_name}")
        actual_touched = _parse_patch(patch_path, boundary)
        if actual_touched != declared_touched:
            raise RouteAError(f"patch touched-path list mismatch: {patch_name}")
        expected_patch_names.append(patch_name)
        total_bytes += size
        total_touched += len(declared_touched)
        normalized.append(dict(raw_patch))
        previous_tree = output_tree
        previous_order = order

    if total_bytes > boundary["max_total_patch_bytes"] or total_touched > boundary["max_touched_paths"]:
        raise RouteAError("patch queue exceeds the fixed size or touched-path budget")
    if previous_tree != expected_result["tree"]:
        raise RouteAError("final patch tree differs from expected_final")

    patch_dir = repo_root / PATCH_DIR_REL
    actual_patch_names = sorted(
        item.name for item in patch_dir.iterdir()
        if item.name.endswith(".patch")
    )
    if actual_patch_names != expected_patch_names:
        raise RouteAError("patch directory exact set/order mismatch")
    for item in patch_dir.iterdir():
        if item.name == SERIES_REL.name or item.name in expected_patch_names:
            continue
        raise RouteAError(f"unexpected file in patch queue: {item.name}")
    if _canonical_json_sha256(value) != TRUSTED_SERIES_CANONICAL_SHA256:
        raise RouteAError("patch series drifted from the fully reviewed finite queue")
    return normalized, expected_result


def load_contract(repo_root: Path) -> tuple[dict[str, object], dict[str, object], dict[str, object], list[dict[str, object]], dict[str, object]]:
    root = repo_root.resolve()
    if not root.is_dir() or root.is_symlink():
        raise RouteAError("repo root must be a regular directory")
    upstream = _validate_lock(load_json(root / LOCK_REL))
    boundary = _validate_boundary(load_json(root / BOUNDARY_REL))
    legal = _validate_legal(load_json(root / LEGAL_REL))
    patches, expected_result = _validate_series(load_json(root / SERIES_REL), root, boundary, upstream)
    return upstream, boundary, legal, patches, expected_result


def _git_environment() -> dict[str, str]:
    environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    environment.update(
        {
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_TERMINAL_PROMPT": "0",
            "LC_ALL": "C",
            "LANG": "C",
            "TZ": "UTC",
        }
    )
    return environment


def _git(root: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    try:
        git_mode = GIT_BINARY.lstat().st_mode
    except OSError as error:
        raise RouteAError("trusted Git executable is unavailable") from error
    if not stat.S_ISREG(git_mode) or not os.access(GIT_BINARY, os.X_OK):
        raise RouteAError("trusted Git executable is not a regular executable")
    command = [
        str(GIT_BINARY),
        "-c", f"core.hooksPath={os.devnull}",
        "-c", "core.fsmonitor=false",
        "-c", "core.untrackedCache=false",
        "-c", "submodule.recurse=false",
        "-c", f"core.attributesFile={os.devnull}",
        "-c", "protocol.file.allow=never",
        *arguments,
    ]
    completed = subprocess.run(
        command,
        cwd=root,
        env=_git_environment(),
        check=False,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and completed.returncode != 0:
        raise RouteAError(f"git command failed: {arguments[0] if arguments else 'git'}")
    return completed


def _git_text(root: Path, *arguments: str) -> str:
    return _git(root, *arguments).stdout.decode("utf-8", errors="strict").strip()


def _dangerous_local_config_key(key: str) -> bool:
    lowered = key.casefold()
    return lowered in DANGEROUS_LOCAL_CONFIG_KEYS or lowered.startswith(DANGEROUS_LOCAL_CONFIG_PREFIXES)


def _validate_attribute_bytes(data: bytes, label: str) -> None:
    if len(data) > MAX_ATTRIBUTE_FILE_BYTES or b"\0" in data:
        raise RouteAError(f"unsafe Git attributes file: {label}")
    for line in data.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(b"#"):
            continue
        first_separator = re.search(rb"[ \t]", stripped)
        if first_separator is None:
            continue
        if DANGEROUS_ATTRIBUTE.search(stripped[first_separator.start():]):
            raise RouteAError(f"dangerous Git attributes are forbidden: {label}")


def _validate_source_git_policy(source: Path) -> None:
    git_dir = source / ".git"
    try:
        git_dir_mode = git_dir.lstat().st_mode
        config_mode = (git_dir / "config").lstat().st_mode
    except OSError as error:
        raise RouteAError("upstream source must have a real Git metadata directory") from error
    if not stat.S_ISDIR(git_dir_mode) or not stat.S_ISREG(config_mode):
        raise RouteAError("upstream source must have a real Git metadata directory and config")

    raw_names = _git(
        source,
        "config", "--local", "--no-includes", "--name-only", "--null", "--list",
    ).stdout
    try:
        config_names = [item.decode("utf-8", errors="strict") for item in raw_names.split(b"\0") if item]
    except UnicodeError as error:
        raise RouteAError("local Git config contains a non-UTF-8 key") from error
    dangerous = sorted(key for key in config_names if _dangerous_local_config_key(key))
    if dangerous:
        raise RouteAError(f"dangerous local Git config is forbidden: {dangerous[0]}")

    info_attributes = git_dir / "info/attributes"
    if info_attributes.exists() or info_attributes.is_symlink():
        if not info_attributes.is_file() or info_attributes.is_symlink():
            raise RouteAError("unsafe Git info attributes file")
        _validate_attribute_bytes(info_attributes.read_bytes(), ".git/info/attributes")

    attribute_count = 0
    for current_root, directory_names, file_names in os.walk(source, topdown=True, followlinks=False):
        current = Path(current_root)
        if current == source and ".git" in directory_names:
            directory_names.remove(".git")
        directory_names[:] = [
            name for name in directory_names if not (current / name).is_symlink()
        ]
        if ".gitattributes" not in file_names:
            continue
        attribute_count += 1
        if attribute_count > MAX_ATTRIBUTE_FILE_COUNT:
            raise RouteAError("too many Git attributes files")
        attributes_path = current / ".gitattributes"
        if not attributes_path.is_file() or attributes_path.is_symlink():
            raise RouteAError("unsafe Git attributes file")
        relative = attributes_path.relative_to(source).as_posix()
        _validate_attribute_bytes(attributes_path.read_bytes(), relative)


def _staged_delta_paths(tree: Path, input_tree: str, boundary: dict[str, object]) -> list[str]:
    raw = _git(
        tree,
        "diff", "--cached", "--name-only", "-z", "--no-renames", "--no-ext-diff", "--no-textconv",
        input_tree, "--",
    ).stdout
    if raw and not raw.endswith(b"\0"):
        raise RouteAError("Git returned a malformed NUL-delimited path list")
    paths: list[str] = []
    folded: set[str] = set()
    for raw_path in raw.split(b"\0"):
        if not raw_path:
            continue
        try:
            decoded = raw_path.decode("utf-8", errors="strict")
        except UnicodeError as error:
            raise RouteAError("runtime patch path is not UTF-8") from error
        safe = _safe_relative_path(decoded, "runtime patch path")
        collision_key = unicodedata.normalize("NFC", safe).casefold()
        if collision_key in folded:
            raise RouteAError("runtime patch paths contain a duplicate or collision")
        folded.add(collision_key)
        if not _path_allowed(safe, boundary):
            raise RouteAError(f"runtime patch path escapes restricted boundary: {safe}")
        paths.append(safe)
    return sorted(paths)


def verify_source(repo_root: Path, upstream_repo: Path) -> dict[str, object]:
    upstream, _, _, _, _ = load_contract(repo_root)
    source = upstream_repo.resolve()
    if not source.is_dir() or upstream_repo.is_symlink():
        raise RouteAError("upstream source must be a regular Git worktree")
    _validate_source_git_policy(source)
    if _git_text(source, "rev-parse", "--is-inside-work-tree") != "true":
        raise RouteAError("upstream source is not a Git worktree")
    top_level = Path(_git_text(source, "rev-parse", "--show-toplevel")).resolve()
    if top_level != source:
        raise RouteAError("upstream source Git worktree root mismatch")
    remote_result = _git(
        source,
        "config", "--local", "--no-includes", "--get", "remote.origin.url",
        check=False,
    )
    if remote_result.returncode != 0:
        raise RouteAError("upstream source lacks a local origin remote")
    remote = remote_result.stdout.decode("utf-8", errors="strict").strip()
    if remote != upstream["remote"]:
        raise RouteAError("upstream source remote mismatch")
    head = _git_text(source, "rev-parse", "--verify", "HEAD^{commit}")
    tree = _git_text(source, "rev-parse", "--verify", "HEAD^{tree}")
    if head != upstream["commit"] or tree != upstream["git_tree"]:
        raise RouteAError("upstream source HEAD or tree mismatch")
    dirty = _git(source, "status", "--porcelain=v1", "--untracked-files=all", "--ignored=matching").stdout
    if dirty:
        raise RouteAError("upstream source is dirty, including untracked or ignored files")
    staged = _git(source, "ls-files", "--stage", "-z").stdout.split(b"\0")
    gitlinks = [entry for entry in staged if entry.startswith(b"160000 ")]
    if gitlinks or upstream["gitlinks"]:
        raise RouteAError("upstream source gitlink set is not the locked empty set")
    file_count = len([entry for entry in _git(source, "ls-files", "-z").stdout.split(b"\0") if entry])
    if file_count != upstream["file_count"]:
        raise RouteAError("upstream source tracked-file count mismatch")
    return {
        "schema_version": 1,
        "route_id": "route-a",
        "source": {"remote": remote, "commit": head, "tree": tree, "file_count": file_count, "clean": True},
    }


def _preflight_archive(archive: Path, upstream: dict[str, object]) -> list[tuple[tarfile.TarInfo, str]]:
    if not archive.is_file() or archive.is_symlink():
        raise RouteAError("archive must be a regular file")
    if archive.stat().st_size != upstream["archive_bytes"] or sha256_file(archive) != upstream["archive_sha256"]:
        raise RouteAError("archive bytes or SHA-256 mismatch")
    accepted: list[tuple[tarfile.TarInfo, str]] = []
    names: set[str] = set()
    folded: set[str] = set()
    expanded = 0
    regular_files = 0
    try:
        with tarfile.open(archive, mode="r:gz") as source:
            members = source.getmembers()
            if not members or len(members) > MAX_ARCHIVE_MEMBERS:
                raise RouteAError("archive member count is invalid")
            for member in members:
                raw_name = member.name.rstrip("/")
                safe_name = _safe_relative_path(raw_name, "archive member")
                root = upstream["archive_root"]
                if safe_name != root and not safe_name.startswith(root + "/"):
                    raise RouteAError("archive contains an unexpected top-level root")
                relative = safe_name[len(root):].lstrip("/")
                if safe_name in names:
                    raise RouteAError("archive contains a duplicate member")
                names.add(safe_name)
                collision = unicodedata.normalize("NFC", safe_name).casefold()
                if collision in folded:
                    raise RouteAError("archive contains a Unicode or case collision")
                folded.add(collision)
                if not (member.isdir() or member.isreg()):
                    raise RouteAError("archive contains a link or special file")
                if member.isreg():
                    if member.size < 0 or member.size > MAX_ARCHIVE_FILE_BYTES:
                        raise RouteAError("archive member exceeds the file-size limit")
                    expanded += member.size
                    regular_files += 1
                    if expanded > MAX_EXPANDED_BYTES:
                        raise RouteAError("archive exceeds the expanded-size limit")
                accepted.append((member, relative))
    except (tarfile.TarError, OSError) as error:
        if isinstance(error, RouteAError):
            raise
        raise RouteAError("archive cannot be parsed safely") from error
    if regular_files != upstream["file_count"]:
        raise RouteAError("archive regular-file count mismatch")
    return accepted


def _extract_archive(archive: Path, destination: Path, members: list[tuple[tarfile.TarInfo, str]]) -> None:
    destination.mkdir(mode=0o700)
    with tarfile.open(archive, mode="r:gz") as source:
        for member, relative in members:
            if not relative:
                continue
            target = destination.joinpath(*PurePosixPath(relative).parts)
            if member.isdir():
                target.mkdir(mode=0o755, parents=True, exist_ok=True)
                continue
            target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
            extracted = source.extractfile(member)
            if extracted is None:
                raise RouteAError("regular archive member has no data")
            with target.open("xb") as output:
                shutil.copyfileobj(extracted, output, length=1024 * 1024)
            if target.stat().st_size != member.size:
                raise RouteAError("archive member size changed during extraction")
            target.chmod(0o755 if member.mode & 0o111 else 0o644)


def _initialize_index(tree: Path) -> str:
    _git(tree, "init", "-q", "--initial-branch=upstream")
    _git(tree, "config", "--local", "user.name", "OpenTypeless Replay")
    _git(tree, "config", "--local", "user.email", "replay@example.invalid")
    _git(tree, "config", "--local", "core.autocrlf", "false")
    _git(tree, "config", "--local", "core.filemode", "true")
    # GitHub source archives can contain files which are tracked upstream but
    # ignored by the repository's own .gitignore. Reconstruct the full archive
    # tree, rather than silently omitting those files.
    _git(tree, "add", "--force", "-A")
    # GitHub archives may contain CRLF working bytes controlled by .gitattributes.
    # Re-materialize from the normalized index before index-aware patch checks.
    _git(tree, "checkout-index", "--force", "--all")
    return _git_text(tree, "write-tree")


def _check_legal(tree: Path, legal: dict[str, object]) -> None:
    for item in legal["protected_upstream_files"]:
        target = tree / item["path"]
        if not target.is_file() or target.is_symlink() or sha256_file(target) != item["sha256"]:
            raise RouteAError(f"protected legal file changed: {item['path']}")
    for item in legal["new_source_license_roots"]:
        if item["license_path"] is None:
            continue
        target = tree / item["license_path"]
        if not target.is_file() or target.is_symlink() or sha256_file(target) != item["sha256"]:
            raise RouteAError(f"new source license is missing or changed: {item['license_path']}")
    for item in legal["required_provenance"]:
        target = tree / item["path"]
        if not target.is_file() or target.is_symlink():
            raise RouteAError(f"required provenance file is missing: {item['path']}")
        try:
            text = target.read_text(encoding="utf-8")
        except UnicodeError as error:
            raise RouteAError(f"required provenance file is not UTF-8: {item['path']}") from error
        if item["token"] not in text:
            raise RouteAError(f"required provenance token is missing: {item['path']}")


def _tracked_file_count(tree: Path) -> int:
    return len([entry for entry in _git(tree, "ls-files", "-z").stdout.split(b"\0") if entry])


def _index_manifest_sha256(tree: Path) -> str:
    return hashlib.sha256(_git(tree, "ls-files", "--stage", "-z").stdout).hexdigest()


def _write_report(report_path: Path, report: dict[str, object]) -> None:
    if report_path.exists() or report_path.is_symlink():
        raise RouteAError("report output must not already exist")
    if not report_path.parent.is_dir() or report_path.parent.is_symlink():
        raise RouteAError("report parent must be an existing regular directory")
    encoded = (json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    with report_path.open("xb") as output:
        output.write(encoded)


def replay(repo_root: Path, archive: Path, output_dir: Path, report_path: Path) -> dict[str, object]:
    upstream, boundary, legal, patches, expected_result = load_contract(repo_root)
    if output_dir.exists() or output_dir.is_symlink():
        raise RouteAError("replay output directory must not already exist")
    if report_path.exists() or report_path.is_symlink():
        raise RouteAError("report output must not already exist")
    output_target = output_dir.resolve(strict=False)
    report_target = report_path.resolve(strict=False)
    if report_target == output_target or output_target in report_target.parents:
        raise RouteAError("report output must be outside the replay output directory")
    if not output_dir.parent.is_dir() or output_dir.parent.is_symlink():
        raise RouteAError("replay output parent must be an existing regular directory")
    output_parent = output_dir.parent.resolve()
    members = _preflight_archive(archive, upstream)
    staging = Path(tempfile.mkdtemp(prefix=".route-a-replay-", dir=output_parent))
    published_output = False
    try:
        extracted = staging / "source"
        _extract_archive(archive, extracted, members)
        base_tree = _initialize_index(extracted)
        if base_tree != upstream["materialized_tree"]:
            raise RouteAError("materialized archive tree mismatch")
        if sha256_file(extracted / upstream["license"]["path"]) != upstream["license"]["sha256"]:
            raise RouteAError("upstream license digest mismatch")

        report_patches: list[dict[str, object]] = []
        current_tree = base_tree
        for entry in patches:
            if current_tree != entry["input_tree"]:
                raise RouteAError(f"runtime patch input tree mismatch: {entry['file']}")
            patch_path = repo_root.resolve() / PATCH_DIR_REL / entry["file"]
            _git(extracted, "apply", "--check", "--index", "--whitespace=error-all", str(patch_path))
            _git(extracted, "apply", "--index", "--whitespace=error-all", str(patch_path))
            actual_touched = _staged_delta_paths(extracted, entry["input_tree"], boundary)
            if actual_touched != entry["touched_paths"]:
                raise RouteAError(f"runtime patch touched-path mismatch: {entry['file']}")
            current_tree = _git_text(extracted, "write-tree")
            if current_tree != entry["output_tree"]:
                raise RouteAError(f"runtime patch output tree mismatch: {entry['file']}")
            report_patches.append(
                {
                    "order": entry["order"],
                    "id": entry["id"],
                    "task_id": entry["task_id"],
                    "file": entry["file"],
                    "bytes": entry["bytes"],
                    "sha256": entry["sha256"],
                    "input_tree": entry["input_tree"],
                    "output_tree": entry["output_tree"],
                    "touched_paths": entry["touched_paths"],
                }
            )

        file_count = _tracked_file_count(extracted)
        if current_tree != expected_result["tree"] or file_count != expected_result["file_count"]:
            raise RouteAError("final replay tree or file count mismatch")
        status_output = _git(extracted, "status", "--porcelain=v1", "--untracked-files=all", "--ignored=matching").stdout
        # Patched tracked changes are expected relative to empty HEAD. Ignored/untracked
        # output is not. Every porcelain entry must therefore be staged-only.
        for raw_line in status_output.splitlines():
            if len(raw_line) < 3 or raw_line[:1] in (b"?", b"!") or raw_line[1:2] != b" ":
                raise RouteAError("replay produced untracked, ignored, or unstaged state")
        _check_legal(extracted, legal)

        report = {
            "schema_version": 1,
            "route_id": "route-a",
            "upstream": {
                "remote": upstream["remote"],
                "commit": upstream["commit"],
                "git_tree": upstream["git_tree"],
                "archive_sha256": upstream["archive_sha256"],
                "materialized_tree": upstream["materialized_tree"],
            },
            "queue": report_patches,
            "result": {
                "tree": current_tree,
                "file_count": file_count,
                "index_manifest_sha256": _index_manifest_sha256(extracted),
                "classification": "evidence-only-restricted-source-boundary",
            },
        }
        # The build export intentionally excludes Git metadata.
        shutil.rmtree(extracted / ".git")
        os.replace(extracted, output_dir)
        published_output = True
        _write_report(report_path, report)
        return report
    except Exception:
        if published_output and output_dir.is_dir() and not output_dir.is_symlink():
            shutil.rmtree(output_dir)
        raise
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def _print_report(value: dict[str, object]) -> None:
    print(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    verify_parser = subparsers.add_parser("verify", help="validate the committed lock and finite patch queue")
    verify_parser.add_argument("--repo-root", type=Path, required=True)

    source_parser = subparsers.add_parser("verify-source", help="validate a clean official upstream Git checkout")
    source_parser.add_argument("--repo-root", type=Path, required=True)
    source_parser.add_argument("--upstream-repo", type=Path, required=True)

    replay_parser = subparsers.add_parser("replay", help="offline replay from the fixed archive")
    replay_parser.add_argument("--repo-root", type=Path, required=True)
    replay_parser.add_argument("--archive", type=Path, required=True)
    replay_parser.add_argument("--output-dir", type=Path, required=True)
    replay_parser.add_argument("--report", type=Path, required=True)

    arguments = parser.parse_args()
    try:
        if arguments.command == "verify":
            upstream, _, _, patches, expected = load_contract(arguments.repo_root)
            _print_report(
                {
                    "schema_version": 1,
                    "route_id": "route-a",
                    "verified": True,
                    "upstream_commit": upstream["commit"],
                    "patch_count": len(patches),
                    "expected_tree": expected["tree"],
                }
            )
        elif arguments.command == "verify-source":
            _print_report(verify_source(arguments.repo_root, arguments.upstream_repo))
        else:
            _print_report(replay(arguments.repo_root, arguments.archive, arguments.output_dir, arguments.report))
    except (RouteAError, OSError, subprocess.SubprocessError) as error:
        print(f"route-a upstream verification failed: {error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

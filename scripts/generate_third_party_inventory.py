#!/usr/bin/env python3
"""Generate deterministic desktop dependency inventory and license material.

The inventory is rooted in package-lock.json and Cargo.lock.  npm development
dependencies and Cargo dev edges are excluded.  Cargo runtime-linked and
build-only crates are deliberately reported separately.

Complete Rust license text extraction is delegated to a pinned cargo-about
version.  The generated output intentionally includes build-only license text
as a conservative distribution superset, without describing those crates as
runtime components.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INVENTORY_OUTPUT = ROOT / "THIRD_PARTY_INVENTORY.md"
LICENSES_OUTPUT = ROOT / "THIRD_PARTY_LICENSES.txt"
CARGO_ABOUT_VERSION = "0.9.1"
MAX_DIFF_LINES = 200
MAX_DIFF_BYTES = 64 * 1024

# These are the desktop targets built by .github/workflows/release.yml.
TARGETS = (
    ("mac-arm64", "aarch64-apple-darwin"),
    ("mac-x64", "x86_64-apple-darwin"),
    ("linux-x64", "x86_64-unknown-linux-gnu"),
    ("linux-arm64", "aarch64-unknown-linux-gnu"),
    ("win-x64", "x86_64-pc-windows-msvc"),
)

# cargo tree correctly keeps resolver-v2 host and target feature sets separate,
# but build/proc-macro dependencies are selected for the machine running Cargo.
# Releases build macOS artifacts on macOS, so normalize the two audited Tauri
# host-build crates that otherwise disappear on Linux/Windows inventory runners.
HOST_BUILD_SUPPLEMENTS = {
    ("base64", "0.21.7"): frozenset({"mac-arm64", "mac-x64"}),
    ("swift-rs", "1.0.7"): frozenset({"mac-arm64", "mac-x64"}),
    ("vswhom", "0.1.0"): frozenset({"win-x64"}),
    ("vswhom-sys", "0.1.3"): frozenset({"win-x64"}),
    ("winapi-util", "0.1.11"): frozenset({"win-x64"}),
    ("windows-link", "0.2.1"): frozenset({"win-x64"}),
    ("windows-sys", "0.59.0"): frozenset({"win-x64"}),
    ("windows-sys", "0.61.2"): frozenset({"win-x64"}),
    ("windows-targets", "0.52.6"): frozenset({"win-x64"}),
    ("windows_x86_64_msvc", "0.52.6"): frozenset({"win-x64"}),
    ("winreg", "0.55.0"): frozenset({"win-x64"}),
}

LICENSE_FILE_RE = re.compile(
    r"^(licen[cs]e|copying|notice|copyright)([._-].*)?$", re.IGNORECASE
)
CARGO_TREE_DEPTH_RE = re.compile(r"^(\d+)(\S+) v([^\s]+)")
CARGO_TREE_PACKAGE_RE = re.compile(r"^(\S+) v([^\s]+)")
SKIPPED_LICENSE_DIRS = {".git", "benches", "examples", "target", "test", "tests"}
TEXT_SUFFIXES = {"", ".html", ".md", ".rst", ".spdx", ".text", ".txt"}

MIT_BODY = """Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the \"Software\"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""


def clean(value: object, fallback: str = "UNKNOWN") -> str:
    text = str(value or "").strip().replace("|", "\\|").replace("\n", " ")
    return text or fallback


def normalize_text(value: str) -> str:
    value = value.replace("\r\n", "\n").replace("\r", "\n").lstrip("\ufeff")
    return "\n".join(line.rstrip() for line in value.split("\n")).strip() + "\n"


def text_sort_key(value: str) -> tuple[str, str]:
    return (value.casefold(), value)


def read_text_file(path: Path) -> str:
    try:
        return normalize_text(path.read_text(encoding="utf-8-sig"))
    except UnicodeDecodeError as error:
        raise RuntimeError(f"license material is not UTF-8: {path}") from error


@dataclass
class Component:
    ecosystem: str
    package_id: str
    name: str
    version: str
    declared_license: str
    source: str
    manifest_path: Path | None = None
    runtime_targets: set[str] = field(default_factory=set)
    build_targets: set[str] = field(default_factory=set)

    @property
    def category(self) -> str:
        if self.ecosystem == "npm":
            return "npm runtime"
        return "cargo runtime" if self.runtime_targets else "cargo build-only"

    @property
    def platforms(self) -> str:
        if self.ecosystem == "npm":
            return "all desktop"
        targets = self.runtime_targets | self.build_targets
        return ", ".join(label for label, _ in TARGETS if label in targets)

    @property
    def display(self) -> str:
        suffix = ""
        if self.ecosystem == "cargo":
            suffix = f"; {self.platforms}"
        return f"[{self.category}{suffix}] {self.name} {self.version}"


@dataclass
class LicenseBlock:
    names: set[str] = field(default_factory=set)
    components: set[str] = field(default_factory=set)
    text: str = ""


def npm_resolution_candidates(current_path: str, dependency: str) -> list[str]:
    candidates: list[str] = []
    if current_path:
        candidates.append(f"{current_path}/node_modules/{dependency}")
        cursor = current_path
        while "/node_modules/" in cursor:
            cursor = cursor.rsplit("/node_modules/", 1)[0]
            if cursor:
                candidates.append(f"{cursor}/node_modules/{dependency}")
    candidates.append(f"node_modules/{dependency}")
    return candidates


def resolve_npm_dependency(
    packages: dict[str, dict[str, object]], current_path: str, dependency: str
) -> str | None:
    return next(
        (
            candidate
            for candidate in npm_resolution_candidates(current_path, dependency)
            if candidate in packages
        ),
        None,
    )


def npm_components() -> dict[str, Component]:
    lock = json.loads((ROOT / "package-lock.json").read_text(encoding="utf-8"))
    packages: dict[str, dict[str, object]] = lock.get("packages", {})
    root = packages.get("", {})
    pending: deque[str] = deque()

    for field_name in ("dependencies", "optionalDependencies"):
        for dependency in root.get(field_name, {}):
            resolved = resolve_npm_dependency(packages, "", dependency)
            if resolved is None:
                if field_name == "optionalDependencies":
                    continue
                raise RuntimeError(f"locked npm runtime dependency is missing: {dependency}")
            pending.append(resolved)

    reachable: set[str] = set()
    while pending:
        package_path = pending.popleft()
        if package_path in reachable:
            continue
        reachable.add(package_path)
        metadata = packages[package_path]
        for field_name, optional in (("dependencies", False), ("optionalDependencies", True)):
            for dependency in metadata.get(field_name, {}):
                resolved = resolve_npm_dependency(packages, package_path, dependency)
                if resolved is not None:
                    pending.append(resolved)
                elif not optional:
                    raise RuntimeError(
                        f"locked npm dependency {dependency} required by {package_path} is missing"
                    )

    result: dict[str, Component] = {}
    for package_path in sorted(reachable):
        metadata = packages[package_path]
        package_json_path = ROOT / package_path / "package.json"
        package_json: dict[str, object] = {}
        if not package_json_path.is_file():
            raise RuntimeError(f"installed npm metadata is missing: {package_json_path}")
        package_json = json.loads(package_json_path.read_text(encoding="utf-8"))

        name = package_path.rsplit("node_modules/", 1)[-1]
        version = clean(metadata.get("version"))
        declared_license = clean(metadata.get("license") or package_json.get("license"))
        if clean(package_json.get("name")) != name:
            raise RuntimeError(f"installed npm package name does not match lock path: {package_path}")
        if clean(package_json.get("version")) != version:
            raise RuntimeError(
                f"installed npm package version does not match lock file: {name} {version}"
            )
        if declared_license == "UNKNOWN":
            raise RuntimeError(f"npm package has no declared license: {name} {version}")
        repository = package_json.get("repository")
        if isinstance(repository, dict):
            repository = repository.get("url")
        source = repository or package_json.get("homepage")
        if not source:
            source = f"https://www.npmjs.com/package/{name}/v/{version}"
        source = str(source).removeprefix("git+")
        if "://" not in source and source.count("/") == 1:
            source = f"https://github.com/{source}"

        result[package_path] = Component(
            ecosystem="npm",
            package_id=package_path,
            name=name,
            version=version,
            declared_license=declared_license,
            source=clean(source),
        )

    return result


def find_cargo() -> str:
    cargo = os.environ.get("CARGO") or shutil.which("cargo")
    if cargo:
        return cargo
    candidates = sorted((Path.home() / ".rustup" / "toolchains").glob("stable-*/bin/cargo"))
    if candidates:
        return str(candidates[0])
    raise RuntimeError("cargo is required to generate third-party material")


def cargo_environment(cargo: str) -> dict[str, str]:
    environment = os.environ.copy()
    environment["PATH"] = str(Path(cargo).parent) + os.pathsep + environment.get("PATH", "")
    return environment


def cargo_metadata(cargo: str, target: str | None = None) -> dict[str, object]:
    command = [
        cargo,
        "metadata",
        "--format-version",
        "1",
        "--locked",
    ]
    if target is not None:
        command.extend(["--filter-platform", target])
    command.extend(["--manifest-path", str(ROOT / "src-tauri" / "Cargo.toml")])
    return json.loads(
        subprocess.check_output(
            command,
            cwd=ROOT,
            env=cargo_environment(cargo),
            text=True,
            encoding="utf-8",
        )
    )


def cargo_non_dev_closure(metadata: dict[str, object]) -> set[str]:
    resolve = metadata.get("resolve")
    if not isinstance(resolve, dict) or not resolve.get("root"):
        raise RuntimeError("Cargo metadata has no resolved root package")

    root_id = str(resolve["root"])
    nodes = {str(node["id"]): node for node in resolve.get("nodes", [])}
    pending: deque[str] = deque([root_id])
    reachable = {root_id}
    while pending:
        package_id = pending.popleft()
        try:
            node = nodes[package_id]
        except KeyError as error:
            raise RuntimeError(
                f"Cargo metadata has no resolve node for {package_id}"
            ) from error
        for dependency in node.get("deps", []):
            if not any(
                dependency_kind.get("kind") != "dev"
                for dependency_kind in dependency.get("dep_kinds", [])
            ):
                continue
            dependency_id = str(dependency["pkg"])
            if dependency_id not in reachable:
                reachable.add(dependency_id)
                pending.append(dependency_id)
    return reachable


def cargo_tree(cargo: str, target: str, edges: str, no_dedupe: bool = False) -> str:
    command = [
        cargo,
        "tree",
        "--locked",
        "--manifest-path",
        str(ROOT / "src-tauri" / "Cargo.toml"),
        "--target",
        target,
        "--edges",
        edges,
        "--prefix",
        "depth" if no_dedupe else "none",
        "--format",
        "{p}",
    ]
    if no_dedupe:
        command.append("--no-dedupe")
    return subprocess.check_output(
        command,
        cwd=ROOT,
        env=cargo_environment(cargo),
        text=True,
        encoding="utf-8",
    )


def cargo_components(cargo: str) -> dict[str, Component]:
    metadata = cargo_metadata(cargo)
    packages_by_key = {
        (package["name"], package["version"]): package for package in metadata["packages"]
    }
    if len(packages_by_key) != len(metadata["packages"]):
        raise RuntimeError(
            "Cargo graph contains duplicate name/version pairs from different sources"
        )
    root_id = metadata["resolve"]["root"]
    root = next(package for package in metadata["packages"] if package["id"] == root_id)
    root_key = (root["name"], root["version"])
    result: dict[str, Component] = {}

    def component_for(key: tuple[str, str]) -> Component:
        try:
            package = packages_by_key[key]
        except KeyError as error:
            raise RuntimeError(f"cargo tree package is absent from locked metadata: {key}") from error
        declared_license = clean(package.get("license"))
        if declared_license == "UNKNOWN":
            raise RuntimeError(f"Cargo package has no declared license: {key[0]} {key[1]}")
        return result.setdefault(
            package["id"],
            Component(
                ecosystem="cargo",
                package_id=package["id"],
                name=clean(package.get("name")),
                version=clean(package.get("version")),
                declared_license=declared_license,
                source=clean(
                    package.get("repository")
                    or package.get("homepage")
                    or package.get("source")
                ),
                manifest_path=Path(package["manifest_path"]),
            ),
        )

    for target_label, target_triple in TARGETS:
        normal_tree = cargo_tree(cargo, target_triple, "normal", no_dedupe=True)
        normal_keys: set[tuple[str, str]] = set()
        context_by_depth: dict[int, str] = {}
        for line in normal_tree.splitlines():
            match = CARGO_TREE_DEPTH_RE.match(line)
            if not match:
                continue
            depth = int(match.group(1))
            key = (match.group(2), match.group(3))
            normal_keys.add(key)
            if key == root_key:
                context_by_depth[depth] = "runtime"
                continue

            package = packages_by_key[key]
            is_proc_macro = any(
                "proc-macro" in target.get("kind", []) for target in package.get("targets", [])
            )
            parent_context = context_by_depth.get(depth - 1, "runtime")
            context = "build" if parent_context == "build" or is_proc_macro else "runtime"
            context_by_depth[depth] = context
            component = component_for(key)
            if context == "runtime":
                component.runtime_targets.add(target_label)
            else:
                component.build_targets.add(target_label)

        all_tree = cargo_tree(cargo, target_triple, "normal,build")
        all_keys = {
            (match.group(1), match.group(2))
            for line in all_tree.splitlines()
            if (match := CARGO_TREE_PACKAGE_RE.match(line))
        }
        for key in all_keys - normal_keys - {root_key}:
            component_for(key).build_targets.add(target_label)

    # Assert the supplement remains exactly scoped in Cargo's target-filtered
    # non-dev graph, then overwrite any current-host leakage with canonical
    # release-host membership.
    supplement_presence = {key: set() for key in HOST_BUILD_SUPPLEMENTS}
    for target_label, target_triple in TARGETS:
        filtered = cargo_metadata(cargo, target_triple)
        filtered_packages = {
            package["id"]: package for package in filtered.get("packages", [])
        }
        closure_keys = {
            (package["name"], package["version"])
            for package_id in cargo_non_dev_closure(filtered)
            if (package := filtered_packages.get(package_id)) is not None
        }
        for key in HOST_BUILD_SUPPLEMENTS:
            if key in closure_keys:
                supplement_presence[key].add(target_label)

    for key, expected_targets in HOST_BUILD_SUPPLEMENTS.items():
        actual_targets = supplement_presence[key]
        if actual_targets != set(expected_targets):
            raise RuntimeError(
                f"audited host-build supplement {key[0]} {key[1]} changed target scope: "
                f"expected {sorted(expected_targets)}, found {sorted(actual_targets)}"
            )
        component = component_for(key)
        unexpected_runtime_targets = component.runtime_targets - set(expected_targets)
        if unexpected_runtime_targets:
            raise RuntimeError(
                f"audited host-build supplement became runtime-linked outside its scope: "
                f"{key[0]} {key[1]} on {sorted(unexpected_runtime_targets)}"
            )
        component.build_targets = set(expected_targets)

    return result


def add_license_block(
    blocks: dict[str, LicenseBlock],
    name: str,
    text: str,
    component_displays: set[str],
) -> None:
    normalized = normalize_text(text)
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
    block = blocks.setdefault(digest, LicenseBlock(text=normalized))
    block.names.add(name)
    block.components.update(component_displays)


def has_full_license_text(texts: list[str]) -> bool:
    combined = "\n".join(texts).lower()
    signatures = (
        "apache license",
        "boost software license",
        "creative commons",
        "isc license",
        "mozilla public license",
        "permission is hereby granted",
        "permission to use, copy, modify",
        "redistribution and use in source and binary forms",
        "this is free and unencumbered software",
        "unicode license",
    )
    return any(signature in combined for signature in signatures)


def npm_attribution(package_dir: Path, package_json: dict[str, object], texts: list[str]) -> str:
    for text in texts:
        match = re.search(r"^PackageCopyrightText:\s*(.+)$", text, re.MULTILINE)
        if match:
            return match.group(1).strip()

    author = package_json.get("author")
    if isinstance(author, dict):
        author = author.get("name")
    if author:
        return str(author).strip()

    authors = package_json.get("contributors")
    if isinstance(authors, list) and authors:
        names = [
            str(item.get("name") if isinstance(item, dict) else item).strip()
            for item in authors
        ]
        return ", ".join(name for name in names if name)

    raise RuntimeError(f"cannot synthesize MIT attribution for {package_dir}")


def collect_npm_license_blocks(
    components: dict[str, Component], blocks: dict[str, LicenseBlock]
) -> None:
    for package_path, component in sorted(components.items()):
        package_dir = ROOT / package_path
        package_json = json.loads((package_dir / "package.json").read_text(encoding="utf-8"))
        files = sorted(
            path
            for path in package_dir.iterdir()
            if path.is_file()
            and LICENSE_FILE_RE.match(path.name)
            and path.suffix.lower() in TEXT_SUFFIXES
        )
        texts = [read_text_file(path) for path in files]
        for path, text in zip(files, texts):
            add_license_block(
                blocks,
                f"npm supplied {path.name}",
                text,
                {component.display},
            )

        if not has_full_license_text(texts):
            if "MIT" not in component.declared_license.upper():
                raise RuntimeError(
                    f"npm package {component.name} {component.version} has no complete local "
                    f"license text for {component.declared_license}"
                )
            attribution = npm_attribution(package_dir, package_json, texts)
            synthesized = f"MIT License\n\nCopyright (c) {attribution}\n\n{MIT_BODY}\n"
            add_license_block(
                blocks,
                "MIT text synthesized from locked npm metadata",
                synthesized,
                {component.display},
            )


def find_cargo_about() -> str:
    candidate = os.environ.get("CARGO_ABOUT") or shutil.which("cargo-about")
    if not candidate:
        home_candidate = Path.home() / ".cargo" / "bin" / "cargo-about"
        candidate = str(home_candidate) if home_candidate.exists() else None
    if not candidate:
        raise RuntimeError(
            "cargo-about 0.9.1 is required; install with: "
            "cargo install cargo-about --version 0.9.1 --locked --features cli"
        )
    version = subprocess.check_output(
        [candidate, "--version"], text=True, encoding="utf-8"
    ).strip()
    if version != f"cargo-about {CARGO_ABOUT_VERSION}":
        raise RuntimeError(
            f"cargo-about {CARGO_ABOUT_VERSION} is required, found: {version}"
        )
    return candidate


def cargo_about_json(cargo: str) -> dict[str, object]:
    cargo_about = find_cargo_about()
    config = """accepted = [
  "MIT",
  "Apache-2.0",
  "Apache-2.0 WITH LLVM-exception",
  "0BSD",
  "BSD-2-Clause",
  "BSD-3-Clause",
  "BSL-1.0",
  "CC0-1.0",
  "ISC",
  "MIT-0",
  "MPL-2.0",
  "Unicode-3.0",
  "Unlicense",
  "Zlib",
]
ignore-dev-dependencies = true
ignore-build-dependencies = false
private = { ignore = true }
workarounds = ["chrono", "cocoa", "gtk", "ring", "rustls", "rustix"]
"""

    with tempfile.TemporaryDirectory(prefix="opentypeless-licenses-") as temp_dir:
        config_path = Path(temp_dir) / "about.toml"
        config_path.write_text(config, encoding="utf-8")
        command = [
            cargo_about,
            "generate",
            "--format",
            "json",
            "--frozen",
            "--fail",
            "--manifest-path",
            str(ROOT / "src-tauri" / "Cargo.toml"),
            "--config",
            str(config_path),
        ]
        # License harvesting uses the complete locked non-dev graph, then the
        # result is filtered to the exact per-target union computed above.
        # cargo-about's simultaneous multi-target filtering can omit crates
        # that are valid for one individual target when cfg expressions from
        # several targets interact.
        return json.loads(
            subprocess.check_output(
                command,
                cwd=ROOT,
                env=cargo_environment(cargo),
                text=True,
                encoding="utf-8",
            )
        )


def collect_cargo_license_blocks(
    cargo: str,
    components: dict[str, Component],
    blocks: dict[str, LicenseBlock],
) -> None:
    locally_covered: set[str] = set()
    # Preserve every packaged license/notice file before cargo-about runs. This
    # also gives host-build supplements a complete local-text fallback when
    # cargo-about's current-host graph does not contain them.
    for component in components.values():
        if component.manifest_path is None:
            continue
        package_dir = component.manifest_path.parent
        texts: list[str] = []
        for path in sorted(package_dir.rglob("*")):
            if not path.is_file() or not LICENSE_FILE_RE.match(path.name):
                continue
            relative = path.relative_to(package_dir)
            if any(
                part.lower() in SKIPPED_LICENSE_DIRS for part in relative.parts[:-1]
            ):
                continue
            if path.suffix.lower() not in TEXT_SUFFIXES:
                continue
            text = read_text_file(path)
            texts.append(text)
            add_license_block(
                blocks,
                f"Cargo supplied {relative.as_posix()}",
                text,
                {component.display},
            )
        if has_full_license_text(texts):
            locally_covered.add(component.package_id)

    about = cargo_about_json(cargo)
    seen_components: set[str] = set()
    for license_info in about["licenses"]:
        displays = {
            components[used_by["crate"]["id"]].display
            for used_by in license_info["used_by"]
            if used_by["crate"]["id"] in components
        }
        if not displays:
            continue
        seen_components.update(
            used_by["crate"]["id"]
            for used_by in license_info["used_by"]
            if used_by["crate"]["id"] in components
        )
        normalized = normalize_text(license_info["text"])
        digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
        existing = blocks.get(digest)
        if existing is not None and any(
            name.startswith("Cargo supplied ") for name in existing.names
        ):
            # cargo-about may choose either of two equivalent packaged files
            # depending on filesystem traversal order. The local source label
            # is stable; retain all component associations without adding the
            # nondeterministic cargo-about label.
            existing.components.update(displays)
        else:
            add_license_block(
                blocks,
                f"cargo-about: {license_info['name']}",
                normalized,
                displays,
            )

    missing = sorted(set(components) - seen_components - locally_covered)
    if missing:
        labels = ", ".join(
            f"{components[package_id].name} {components[package_id].version}"
            for package_id in missing
        )
        raise RuntimeError(
            f"cargo-about and packaged files produced no complete license text for: {labels}"
        )


def render_table(components: list[Component], include_platforms: bool) -> list[str]:
    if include_platforms:
        lines = [
            "| Package | Version | Declared license | Release targets | Source |",
            "| --- | --- | --- | --- | --- |",
        ]
        lines.extend(
            f"| {clean(item.name)} | {clean(item.version)} | "
            f"{clean(item.declared_license)} | {clean(item.platforms)} | {clean(item.source)} |"
            for item in components
        )
        return lines

    lines = [
        "| Package | Version | Declared license | Source |",
        "| --- | --- | --- | --- |",
    ]
    lines.extend(
        f"| {clean(item.name)} | {clean(item.version)} | "
        f"{clean(item.declared_license)} | {clean(item.source)} |"
        for item in components
    )
    return lines


def render_inventory(
    npm: dict[str, Component], cargo: dict[str, Component]
) -> str:
    npm_runtime = sorted(npm.values(), key=lambda item: (item.name.casefold(), item.version))
    cargo_runtime = sorted(
        (item for item in cargo.values() if item.runtime_targets),
        key=lambda item: (item.name.casefold(), item.version),
    )
    cargo_build = sorted(
        (item for item in cargo.values() if not item.runtime_targets),
        key=lambda item: (item.name.casefold(), item.version),
    )
    lines = [
        "# Third-Party Desktop Dependency Inventory",
        "",
        "This file is generated by `scripts/generate_third_party_inventory.py` from ",
        "`package-lock.json`, `src-tauri/Cargo.lock`, and Cargo metadata/tree filtered to the ",
        "five release targets below. Run the generator with `--check` in CI to detect drift.",
        "",
        "Scope rules:",
        "",
        "- npm runtime means the root production dependencies and their locked ",
        "  `dependencies`/`optionalDependencies`; root dev dependencies and type/build tools ",
        "  are excluded. Peer packages appear only when independently reachable from that graph.",
        "- Cargo runtime-linked means at least one path from the application uses normal Cargo ",
        "  dependencies and is not exclusively inside a proc-macro host branch.",
        "- Cargo build-only means every relevant path either crosses a Cargo build-dependency ",
        "  edge or stays inside a proc-macro host branch. Cargo dev edges are excluded. These ",
        "  crates are not described as shipped runtime components, although their license text ",
        "  is bundled conservatively.",
        "- Audited host-build supplements normalize macOS-only tooling when the inventory runs ",
        "  on another host; target-filtered Cargo metadata asserts their release-target scope.",
        "- Release targets: " + ", ".join(f"{label} (`{triple}`)" for label, triple in TARGETS),
        "",
        f"- npm runtime packages: {len(npm_runtime)}",
        f"- Cargo runtime-linked crates: {len(cargo_runtime)}",
        f"- Cargo build-only crates: {len(cargo_build)}",
        "",
        "Full extracted and synthesized license material is in ",
        "[THIRD_PARTY_LICENSES.txt](THIRD_PARTY_LICENSES.txt). Hand-maintained notices for ",
        "bundled native Opus code are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).",
        "",
        "## npm browser-runtime packages",
        "",
    ]
    lines.extend(render_table(npm_runtime, include_platforms=False))
    lines.extend(["", "## Cargo runtime-linked crates", ""])
    lines.extend(render_table(cargo_runtime, include_platforms=True))
    lines.extend(["", "## Cargo build-only crates (not runtime components)", ""])
    lines.extend(render_table(cargo_build, include_platforms=True))
    return "\n".join(line.rstrip() for line in lines) + "\n"


def render_licenses(
    blocks: dict[str, LicenseBlock],
    npm: dict[str, Component],
    cargo: dict[str, Component],
) -> str:
    cargo_runtime = sum(bool(item.runtime_targets) for item in cargo.values())
    cargo_build = len(cargo) - cargo_runtime
    lines = [
        "OpenTypeless Third-Party License Material",
        "========================================",
        "",
        "Generated by scripts/generate_third_party_inventory.py from locked npm and Cargo",
        "metadata, locally installed package sources, and cargo-about 0.9.1.",
        "",
        f"npm browser-runtime packages: {len(npm)}",
        f"Cargo runtime-linked crates: {cargo_runtime}",
        f"Cargo build-only crates: {cargo_build}",
        "",
        "Cargo dev dependencies and npm root dev dependencies are excluded. License text for",
        "Cargo build-only crates is included as a conservative distribution superset; inclusion",
        "does not claim that those crates are linked into an installed executable. See",
        "THIRD_PARTY_INVENTORY.md for exact category and release-target membership, and",
        "THIRD_PARTY_NOTICES.md for hand-maintained bundled-native-code notices.",
        "",
    ]

    ordered = sorted(
        blocks.items(),
        key=lambda item: (sorted(item[1].names, key=text_sort_key)[0].casefold(), item[0]),
    )
    for index, (digest, block) in enumerate(ordered, start=1):
        lines.extend(
            [
                "=" * 80,
                f"LICENSE MATERIAL {index} OF {len(ordered)}",
                f"SHA-256: {digest}",
                "Detected/source labels:",
            ]
        )
        lines.extend(f"- {name}" for name in sorted(block.names, key=text_sort_key))
        lines.append("Used by:")
        lines.extend(f"- {name}" for name in sorted(block.components, key=text_sort_key))
        lines.extend(["", block.text.rstrip(), ""])
    return "\n".join(lines).rstrip() + "\n"


def write_or_check(path: Path, generated: str, check: bool) -> bool:
    if check:
        current = path.read_text(encoding="utf-8") if path.exists() else ""
        if current != generated:
            print(f"{path.name} is stale; regenerate it", file=sys.stderr)
            print(
                f"committed sha256: "
                f"{hashlib.sha256(current.encode('utf-8')).hexdigest()}",
                file=sys.stderr,
            )
            print(
                f"generated sha256: "
                f"{hashlib.sha256(generated.encode('utf-8')).hexdigest()}",
                file=sys.stderr,
            )
            diff = difflib.unified_diff(
                current.splitlines(keepends=True),
                generated.splitlines(keepends=True),
                fromfile=f"{path.name} (committed)",
                tofile=f"{path.name} (generated)",
                n=3,
            )
            emitted_lines = 0
            emitted_bytes = 0
            omitted_lines = 0
            truncated = False
            for line in diff:
                encoded = line.encode("utf-8")
                if (
                    truncated
                    or emitted_lines >= MAX_DIFF_LINES
                    or emitted_bytes + len(encoded) > MAX_DIFF_BYTES
                ):
                    truncated = True
                    omitted_lines += 1
                    continue
                sys.stderr.write(line)
                emitted_lines += 1
                emitted_bytes += len(encoded)
            if truncated:
                print(
                    f"... diff truncated after {emitted_lines} lines / "
                    f"{emitted_bytes} bytes; omitted {omitted_lines} lines",
                    file=sys.stderr,
                )
            return False
        return True
    path.write_text(generated, encoding="utf-8")
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    check_group = parser.add_mutually_exclusive_group()
    check_group.add_argument(
        "--check",
        action="store_true",
        help="fail when committed inventory or license material is stale",
    )
    check_group.add_argument(
        "--check-inventory",
        action="store_true",
        help="check only the dependency inventory without invoking cargo-about",
    )
    arguments = parser.parse_args()

    cargo = find_cargo()
    npm = npm_components()
    cargo_items = cargo_components(cargo)
    inventory = render_inventory(npm, cargo_items)
    if arguments.check_inventory:
        ok = write_or_check(INVENTORY_OUTPUT, inventory, check=True)
        if ok:
            print(
                f"verified inventory: {len(npm)} npm runtime, "
                f"{sum(bool(item.runtime_targets) for item in cargo_items.values())} "
                f"Cargo runtime, "
                f"{sum(not item.runtime_targets for item in cargo_items.values())} "
                f"Cargo build-only"
            )
        return 0 if ok else 1

    blocks: dict[str, LicenseBlock] = {}
    collect_npm_license_blocks(npm, blocks)
    collect_cargo_license_blocks(cargo, cargo_items, blocks)

    licenses = render_licenses(blocks, npm, cargo_items)
    ok = write_or_check(INVENTORY_OUTPUT, inventory, arguments.check)
    ok = write_or_check(LICENSES_OUTPUT, licenses, arguments.check) and ok
    if ok:
        action = "verified" if arguments.check else "generated"
        print(
            f"{action}: {len(npm)} npm runtime, "
            f"{sum(bool(item.runtime_targets) for item in cargo_items.values())} Cargo runtime, "
            f"{sum(not item.runtime_targets for item in cargo_items.values())} Cargo build-only, "
            f"{len(blocks)} unique license/notice texts"
        )
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

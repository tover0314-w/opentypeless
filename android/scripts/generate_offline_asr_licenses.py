#!/usr/bin/env python3
"""Generate the in-app license bundle from the exact native build inputs."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import sys


FILES = {
    "Eigen 5.0.1 / licensing overview": ("eigen-src/COPYING.README", "db640ff2bd90c6abd6a4d3fbb351e0ee4d555417cf840492054d1cbb2ea85644"),
    "Eigen 5.0.1 / MPL-2.0": ("eigen-src/LICENSE", "1f256ecad192880510e84ad60474eab7589218784b9a50bc7ceee34c2b91f1d5"),
    "Eigen 5.0.1 / Apache-2.0 files": ("eigen-src/COPYING.APACHE", "03379001a7b12a2ec997a25554247d985270b353c10d5bafee9ac8d6519820b7"),
    "Eigen 5.0.1 / BSD files": ("eigen-src/COPYING.BSD", "51928dce36213c5333ba3172e847d735d4c6e9b7ff2722a326c49067155b82eb"),
    "Eigen 5.0.1 / MINPACK files": ("eigen-src/COPYING.MINPACK", "c87b7f8ee88f6195e91743820c00354833583aef091b72e2d4a49c8e28e798a0"),
    "nlohmann/json 3.12.0": ("json-src/LICENSE.MIT", "46a65cffd1ea955132d95a8dd921640714a8d6b537d2e4e482d31145ae95b603"),
    "kaldi-decoder 0.3.0": ("kaldi_decoder-src/LICENSE", "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
    "kaldi-native-fbank 1.22.3": ("kaldi_native_fbank-src/LICENSE", "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
    "kaldifst 1.8.0": ("kaldifst-src/LICENSE", "a682d6efd1ee5dee08a8e405c233c2c198ea70ae0718129daa83ab58cfe31c5d"),
    "KISS FFT febd4cae": ("kissfft-src/COPYING", "a2840585f8411be8e6826a31ef15ae65c950bd74a2437a73b013398a934ad0c6"),
    "OpenFST 1.8.5-2026-04-11": ("openfst-src/COPYING", "4300529197035fd3452350718a0b8cee984e9412c9932d7f35fcde849fc97a4b"),
    "simple-sentencepiece 0.7": ("simple-sentencepiece-src/LICENSE", "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"),
}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def generate(deps: Path) -> str:
    grouped: dict[str, tuple[bytes, list[str]]] = {}
    for label, (relative, expected) in FILES.items():
        data = (deps / relative).read_bytes()
        actual = digest(data)
        if actual != expected:
            raise RuntimeError(f"license drift for {label}: expected {expected}, got {actual}")
        if actual not in grouped:
            grouped[actual] = (data, [])
        grouped[actual][1].append(label)

    sections = [
        "OpenTypeless offline ASR native runtime license bundle\n",
        "Generated from the exact dependency sources used to build sherpa-onnx "
        "commit 142807252687d81b40d6315f23470a1512a00de3 with TTS, speaker "
        "diarization, C API, and WebSocket support disabled. Identical license texts "
        "are deduplicated; every consuming component remains listed.\n",
    ]
    ordered = sorted(grouped.items(), key=lambda item: sorted(item[1][1])[0].casefold())
    for sha, (data, labels) in ordered:
        sections.append("=" * 72 + "\n")
        sections.append("Applies to:\n" + "".join(f"- {label}\n" for label in sorted(labels)))
        sections.append(f"License text SHA-256: {sha}\n\n")
        sections.append(data.decode("utf-8").rstrip() + "\n")
    return "\n".join(sections)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--deps-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    generated = generate(args.deps_dir.resolve())
    output = args.output.resolve()
    if args.check:
        if not output.is_file() or output.read_text(encoding="utf-8") != generated:
            raise RuntimeError(f"offline ASR license bundle is stale: {output}")
        print(f"Verified {output}")
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(generated, encoding="utf-8")
        print(f"Wrote {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, UnicodeDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)

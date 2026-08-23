#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import wave
from pathlib import Path
from typing import Iterable, Sequence


GENERATOR_VERSION = "2.0"
MANIFEST_SCHEMA_VERSION = 2
PINK_NOISE_ALGORITHM = "voss-mccartney-int-v1"
DEFAULT_NOISE_SEED = 20260809
DEFAULT_NOISE_SNR_DB = 15.0
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
VOICES = {
    "zh": ("Tingting", "Meijia"),
    "mixed": ("Tingting", "Meijia"),
    "en": ("Samantha", "Daniel"),
}


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate reproducible macOS TTS smoke-test audio for the offline ASR corpus."
    )
    parser.add_argument("--corpus", type=Path, default=Path(__file__).with_name("corpus.jsonl"))
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--voice-set", choices=("primary", "all"), default="all")
    parser.add_argument("--rate", type=int, default=185, help="macOS say speaking rate")
    parser.add_argument(
        "--noise-policy",
        choices=("none", "probes", "controls", "probes-and-controls", "all"),
        default="none",
        help=(
            "Which clean utterances receive a paired pink-noise file: none, corpus "
            "noise_probe cases, hotword controls, their union, or every case"
        ),
    )
    parser.add_argument(
        "--add-noise",
        action="store_true",
        help=(
            "Compatibility alias for --noise-policy probes-and-controls; this pairs "
            "both noise probes and every hotword control with a noisy variant"
        ),
    )
    parser.add_argument(
        "--noise-snr-db",
        type=float,
        default=DEFAULT_NOISE_SNR_DB,
        help="Target whole-utterance speech-to-noise ratio in dB",
    )
    parser.add_argument(
        "--noise-seed",
        type=int,
        default=DEFAULT_NOISE_SEED,
        help="Base seed; each case and voice derives a stable independent seed",
    )
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args(argv)
    if args.add_noise:
        if args.noise_policy != "none":
            parser.error("--add-noise cannot be combined with --noise-policy")
        args.noise_policy = "probes-and-controls"
    if not math.isfinite(args.noise_snr_db):
        parser.error("--noise-snr-db must be finite")
    if args.rate <= 0:
        parser.error("--rate must be positive")
    return args


def validate_case_id(case_id: object, path: Path, line_number: int) -> str:
    if not isinstance(case_id, str) or not SAFE_ID.fullmatch(case_id):
        raise ValueError(
            f"{path}:{line_number} has unsafe id {case_id!r}; IDs must match "
            "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"
        )
    return case_id


def load_corpus(path: Path) -> list[dict]:
    cases = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not raw_line.strip():
            continue
        case = json.loads(raw_line)
        required = {"id", "language", "category", "text", "hotwords", "noise_probe"}
        missing = required - case.keys()
        if missing:
            raise ValueError(f"{path}:{line_number} is missing {sorted(missing)}")
        validate_case_id(case["id"], path, line_number)
        if case["language"] not in VOICES:
            raise ValueError(f"{path}:{line_number} has unsupported language {case['language']!r}")
        cases.append(case)
    if len({case["id"] for case in cases}) != len(cases):
        raise ValueError("Corpus IDs must be unique")
    return cases


def safe_output_path(output_dir: Path, filename: str) -> Path:
    if Path(filename).name != filename or filename in {".", ".."}:
        raise ValueError(f"Unsafe output filename: {filename!r}")
    root = output_dir.resolve()
    candidate = (root / filename).resolve()
    try:
        candidate.relative_to(root)
    except ValueError as exc:
        raise ValueError(f"Output path escapes {output_dir}: {filename!r}") from exc
    return candidate


def prepare_output_dir(output_dir: Path, overwrite: bool) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    existing = list(output_dir.iterdir())
    if existing and not overwrite:
        raise FileExistsError(
            f"{output_dir} is not empty; use --overwrite so references cannot be paired "
            "with stale audio"
        )


def run(command: list[str]) -> None:
    subprocess.run(command, check=True)


def synthesize(text: str, voice: str, rate: int, output: Path, overwrite: bool) -> None:
    if output.exists() and not overwrite:
        raise FileExistsError(f"Refusing to overwrite {output}")
    with tempfile.TemporaryDirectory(prefix="opentypeless-tts-") as temp_dir:
        aiff = Path(temp_dir) / "speech.aiff"
        run(["say", "-v", voice, "-r", str(rate), "-o", str(aiff), text])
        run(
            [
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                str(aiff),
                "-map_metadata",
                "-1",
                "-ar",
                "16000",
                "-ac",
                "1",
                "-c:a",
                "pcm_s16le",
                str(output),
            ]
        )


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def derive_noise_seed(base_seed: int, case_id: str, voice: str) -> int:
    material = f"{base_seed}\0{case_id}\0{voice}\0{PINK_NOISE_ALGORITHM}".encode("utf-8")
    return int.from_bytes(hashlib.sha256(material).digest()[:8], "big")


class StablePrng:
    """Small integer-only PRNG so noise bytes do not depend on random.py internals."""

    def __init__(self, seed: int) -> None:
        self.state = seed & ((1 << 64) - 1)

    def next_u64(self) -> int:
        # SplitMix64 has fully specified 64-bit integer behavior.
        self.state = (self.state + 0x9E3779B97F4A7C15) & ((1 << 64) - 1)
        value = self.state
        value = ((value ^ (value >> 30)) * 0xBF58476D1CE4E5B9) & ((1 << 64) - 1)
        value = ((value ^ (value >> 27)) * 0x94D049BB133111EB) & ((1 << 64) - 1)
        return value ^ (value >> 31)

    def next_signed(self) -> int:
        return int((self.next_u64() >> 32) - (1 << 31))


def pink_noise_samples(sample_count: int, seed: int, rows: int = 16) -> list[int]:
    """Return deterministic integer Voss-McCartney pink-ish noise samples."""
    if sample_count < 0:
        raise ValueError("sample_count must be non-negative")
    if rows <= 0:
        raise ValueError("rows must be positive")
    prng = StablePrng(seed)
    sources = [prng.next_signed() for _ in range(rows)]
    running_sum = sum(sources)
    result: list[int] = []
    for sample_index in range(1, sample_count + 1):
        trailing_zeroes = (sample_index & -sample_index).bit_length() - 1
        if trailing_zeroes < rows:
            running_sum -= sources[trailing_zeroes]
            sources[trailing_zeroes] = prng.next_signed()
            running_sum += sources[trailing_zeroes]
        # Adding an independent white component fills the highest octave.
        result.append(running_sum + prng.next_signed())
    return result


def root_mean_square(samples: Iterable[int | float]) -> float:
    values = list(samples)
    if not values:
        return 0.0
    return math.sqrt(math.fsum(float(value) * float(value) for value in values) / len(values))


def read_pcm16_mono(path: Path) -> tuple[list[int], int]:
    with wave.open(str(path), "rb") as source:
        if source.getnchannels() != 1 or source.getsampwidth() != 2:
            raise ValueError(f"{path} must be mono PCM16 WAV")
        if source.getcomptype() != "NONE":
            raise ValueError(f"{path} must be uncompressed PCM WAV")
        sample_rate = source.getframerate()
        frame_count = source.getnframes()
        raw = source.readframes(frame_count)
    if len(raw) != frame_count * 2:
        raise ValueError(f"{path} contains truncated PCM data")
    samples = list(struct.unpack(f"<{frame_count}h", raw))
    return samples, sample_rate


def write_pcm16_mono(path: Path, samples: Sequence[int], sample_rate: int) -> None:
    payload = struct.pack(f"<{len(samples)}h", *samples)
    with wave.open(str(path), "wb") as destination:
        destination.setnchannels(1)
        destination.setsampwidth(2)
        destination.setframerate(sample_rate)
        destination.setcomptype("NONE", "not compressed")
        destination.writeframes(payload)


def add_pink_noise(
    clean: Path,
    output: Path,
    *,
    seed: int,
    target_snr_db: float,
    overwrite: bool,
) -> dict:
    if output.exists() and not overwrite:
        raise FileExistsError(f"Refusing to overwrite {output}")
    if not math.isfinite(target_snr_db):
        raise ValueError("target_snr_db must be finite")

    speech, sample_rate = read_pcm16_mono(clean)
    if sample_rate != 16000:
        raise ValueError(f"{clean} must use a 16000 Hz sample rate")
    speech_rms = root_mean_square(speech)
    if speech_rms == 0.0:
        raise ValueError(f"{clean} is silent; SNR is undefined")
    raw_noise = pink_noise_samples(len(speech), seed)
    raw_noise_rms = root_mean_square(raw_noise)
    desired_noise_rms = speech_rms / math.pow(10.0, target_snr_db / 20.0)
    noise_scale = desired_noise_rms / raw_noise_rms

    peak = max(abs(sample + noise * noise_scale) for sample, noise in zip(speech, raw_noise))
    mix_gain = min(1.0, 32760.0 / peak) if peak else 1.0
    speech_component = [round(sample * mix_gain) for sample in speech]
    noise_component = [round(noise * noise_scale * mix_gain) for noise in raw_noise]
    mixed = [
        max(-32768, min(32767, speech_sample + noise_sample))
        for speech_sample, noise_sample in zip(speech_component, noise_component)
    ]
    write_pcm16_mono(output, mixed, sample_rate)

    actual_snr_db = 20.0 * math.log10(
        root_mean_square(speech_component) / root_mean_square(noise_component)
    )
    return {
        "kind": "pink",
        "algorithm": PINK_NOISE_ALGORITHM,
        "seed": seed,
        "target_snr_db": target_snr_db,
        "actual_snr_db": round(actual_snr_db, 6),
        "mix_gain_db": round(20.0 * math.log10(mix_gain), 6) if mix_gain else None,
    }


def should_add_noise(case: dict, policy: str) -> bool:
    if policy == "none":
        return False
    if policy == "all":
        return True
    is_probe = bool(case["noise_probe"])
    is_control = case["category"] == "hotword_control"
    if policy == "probes":
        return is_probe
    if policy == "controls":
        return is_control
    if policy == "probes-and-controls":
        return is_probe or is_control
    raise ValueError(f"Unsupported noise policy: {policy}")


def audio_metadata(path: Path) -> dict:
    samples, sample_rate = read_pcm16_mono(path)
    return {
        "sha256": file_sha256(path),
        "bytes": path.stat().st_size,
        "sample_rate_hz": sample_rate,
        "channels": 1,
        "sample_format": "pcm_s16le",
        "frames": len(samples),
    }


def manifest_entry(
    case: dict,
    voice: str,
    condition: str,
    audio_name: str,
    audio_path: Path,
    *,
    rate: int,
    noise: dict | None = None,
) -> dict:
    return {
        "schema_version": MANIFEST_SCHEMA_VERSION,
        "id": case["id"],
        "language": case["language"],
        "category": case["category"],
        "reference": case["text"],
        "tts_text": case.get("tts_text", case["text"]),
        "hotwords": case["hotwords"],
        "bias_phrases": case.get("bias_phrases", case["hotwords"]),
        "correction_aliases": case.get("correction_aliases", []),
        "voice": voice,
        "condition": condition,
        "audio": audio_name,
        "audio_metadata": audio_metadata(audio_path),
        "generation": {
            "generator_version": GENERATOR_VERSION,
            "tts": {
                "engine": "macos-say",
                "voice": voice,
                "rate_wpm": rate,
                "conversion": "ffmpeg pcm_s16le mono 16000Hz metadata-stripped",
            },
            "noise": noise,
        },
    }


def command_first_line(command: Sequence[str]) -> str | None:
    completed = subprocess.run(
        list(command),
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    for line in completed.stdout.splitlines():
        if line.strip():
            return line.strip()
    return None


def collect_provenance(args: argparse.Namespace, corpus_path: Path, entry_count: int) -> dict:
    say_path = shutil.which("say")
    ffmpeg_path = shutil.which("ffmpeg")
    return {
        "schema_version": MANIFEST_SCHEMA_VERSION,
        "generator": {"name": Path(__file__).name, "version": GENERATOR_VERSION},
        "corpus": {"path": str(corpus_path), "sha256": file_sha256(corpus_path)},
        "parameters": {
            "voice_set": args.voice_set,
            "rate_wpm": args.rate,
            "noise_policy": args.noise_policy,
            "noise_seed": args.noise_seed,
            "noise_snr_db": args.noise_snr_db,
            "pink_noise_algorithm": PINK_NOISE_ALGORITHM,
            "entry_count": entry_count,
        },
        "tools": {
            "say": {
                "path": say_path,
                # say has no version flag; it is versioned with the host macOS release.
                "version": f"macOS-bundled/{platform.mac_ver()[0] or platform.release()}",
            },
            "ffmpeg": {
                "path": ffmpeg_path,
                "version": command_first_line([ffmpeg_path, "-version"]) if ffmpeg_path else None,
            },
            "python": {
                "path": sys.executable,
                "version": platform.python_version(),
                "implementation": platform.python_implementation(),
            },
        },
        "platform": {
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
            "macos_version": platform.mac_ver()[0],
        },
    }


def write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )


def write_json_lines(path: Path, entries: Sequence[dict]) -> None:
    path.write_text(
        "".join(
            json.dumps(entry, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
            for entry in entries
        ),
        encoding="utf-8",
    )


def main(argv: Sequence[str] | None = None) -> None:
    args = parse_args(argv)
    if shutil.which("say") is None or shutil.which("ffmpeg") is None:
        raise SystemExit("This generator requires macOS 'say' and ffmpeg")
    cases = load_corpus(args.corpus)
    try:
        prepare_output_dir(args.output_dir, args.overwrite)
    except FileExistsError as exc:
        raise SystemExit(str(exc)) from exc
    entries: list[dict] = []

    for case in cases:
        voices = VOICES[case["language"]]
        if args.voice_set == "primary":
            voices = voices[:1]
        for voice in voices:
            stem = f"{case['id']}__{voice.lower()}"
            clean_name = f"{stem}__clean.wav"
            clean_path = safe_output_path(args.output_dir, clean_name)
            synthesize(
                case.get("tts_text", case["text"]),
                voice,
                args.rate,
                clean_path,
                args.overwrite,
            )
            entries.append(
                manifest_entry(
                    case,
                    voice,
                    "clean",
                    clean_name,
                    clean_path,
                    rate=args.rate,
                )
            )

            if should_add_noise(case, args.noise_policy):
                noisy_name = f"{stem}__pink_noise.wav"
                noisy_path = safe_output_path(args.output_dir, noisy_name)
                noise = add_pink_noise(
                    clean_path,
                    noisy_path,
                    seed=derive_noise_seed(args.noise_seed, case["id"], voice),
                    target_snr_db=args.noise_snr_db,
                    overwrite=args.overwrite,
                )
                entries.append(
                    manifest_entry(
                        case,
                        voice,
                        "pink_noise",
                        noisy_name,
                        noisy_path,
                        rate=args.rate,
                        noise=noise,
                    )
                )

    manifest = safe_output_path(args.output_dir, "manifest.jsonl")
    write_json_lines(manifest, entries)
    provenance = collect_provenance(args, args.corpus, len(entries))
    provenance["outputs"] = {
        "manifest": manifest.name,
        "manifest_sha256": file_sha256(manifest),
    }
    provenance_path = safe_output_path(args.output_dir, "provenance.json")
    write_json(provenance_path, provenance)
    print(f"Generated {len(entries)} audio cases in {args.output_dir}")
    print(f"Manifest: {manifest}")
    print(f"Provenance: {provenance_path}")


if __name__ == "__main__":
    main()

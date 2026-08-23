#!/usr/bin/env python3
"""Prepare pinned ASCEND and FLEURS real-speech benchmark inputs.

The downloader deliberately keeps the public corpora outside the repository.  It
validates immutable upstream identifiers, normalizes every output to mono 16 kHz
PCM16, and installs a completed bundle atomically so interrupted downloads do not
look like valid benchmark inputs.
"""

from __future__ import annotations

import argparse
import array
import hashlib
import json
import math
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import time
import wave
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path, PurePosixPath
from typing import BinaryIO, Dict, Iterable, List, Mapping, Optional, Tuple
from urllib.parse import urlencode, urlparse
from urllib.error import HTTPError
from urllib.request import Request, urlopen


USER_AGENT = "OpenTypeless-offline-ASR-benchmark/1.0"
PRODUCER = "opentypeless.offline_asr.prepare_public_corpora"
BUNDLE_SCHEMA_VERSION = 1

ASCEND_REVISION = "737e9800ae31be9932ba8464c80366559bd28424"
FLEURS_REVISION = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
FLEURS_LANGUAGES = {
    "cmn_hans_cn": {
        "language": "zh",
        "etag": '"cd39a9c9ac596fb561ad90353660889e"',
        "content_length": 2_522_990_658,
        "generation": "1650974174867084",
        "crc32c": "crc32c=kPgbDg==",
    },
    "en_us": {
        "language": "en",
        "etag": '"d1afa0c6f0417a6f8b3c667d29a11749"',
        "content_length": 1_848_241_090,
        "generation": "1650978881821082",
        "crc32c": "crc32c=/wOKqQ==",
    },
}

ASCEND_TEST_ROWS = 1_315
MAX_ASCEND_ROWS = ASCEND_TEST_ROWS
ASCEND_PAGE_SIZE = 100
MAX_FLEURS_ROWS_PER_LANGUAGE = 100
MAX_JSON_BYTES = 8 * 1024 * 1024
MAX_TSV_BYTES = 16 * 1024 * 1024
MAX_AUDIO_BYTES = 64 * 1024 * 1024
# A small validation subset should never need to consume a multi-gigabyte archive.
MAX_FLEURS_STREAM_BYTES = 256 * 1024 * 1024
ASCEND_DOWNLOAD_WORKERS = 8

_SAFE_ASCEND_ID = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")
_SAFE_FLEURS_AUDIO = re.compile(r"[0-9]+\.wav\Z")


class CountingReader:
    """A bounded reader used by tarfile's sequential gzip mode."""

    def __init__(self, stream: BinaryIO, max_bytes: int) -> None:
        self.stream = stream
        self.max_bytes = max_bytes
        self.bytes_read = 0

    def read(self, size: int = -1) -> bytes:
        remaining = self.max_bytes - self.bytes_read
        if remaining < 0:
            raise ValueError("FLEURS compressed stream exceeded the safety limit")
        requested = remaining + 1 if size is None or size < 0 else min(size, remaining + 1)
        data = self.stream.read(requested)
        self.bytes_read += len(data)
        if self.bytes_read > self.max_bytes:
            raise ValueError("FLEURS compressed stream exceeded the safety limit")
        return data


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Download reproducible real-speech subsets for the offline ASR benchmark"
    )
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument(
        "--ascend-count",
        type=int,
        default=100,
        help=f"number of pinned ASCEND test rows (0-{MAX_ASCEND_ROWS})",
    )
    parser.add_argument(
        "--fleurs-count-per-language",
        type=int,
        default=50,
        help="number of pinned FLEURS validation rows per language (0-100)",
    )
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def request(url: str):
    retryable_statuses = {429, 500, 502, 503, 504}
    for attempt in range(4):
        try:
            return urlopen(Request(url, headers={"User-Agent": USER_AGENT}), timeout=120)
        except HTTPError as error:
            if error.code not in retryable_statuses or attempt == 3:
                raise
            retry_after = error.headers.get("Retry-After")
            error.close()
            try:
                delay = float(retry_after) if retry_after is not None else 2**attempt
            except ValueError:
                delay = 2**attempt
            time.sleep(min(max(delay, 0.0), 8.0))
    raise AssertionError("unreachable")


def _headers(response) -> Dict[str, str]:
    normalized: Dict[str, str] = {}
    for key, value in response.headers.items():
        name = str(key).lower()
        text = str(value)
        normalized[name] = f"{normalized[name]},{text}" if name in normalized else text
    return normalized


def _final_url(response, requested_url: str) -> str:
    getter = getattr(response, "geturl", None)
    return str(getter()) if getter is not None else requested_url


def _content_length(headers: Mapping[str, str]) -> Optional[int]:
    value = headers.get("content-length")
    if value is None:
        return None
    try:
        length = int(value)
    except ValueError as error:
        raise ValueError(f"invalid HTTP Content-Length {value!r}") from error
    if length < 0:
        raise ValueError(f"invalid HTTP Content-Length {value!r}")
    return length


def _read_limited(stream: BinaryIO, max_bytes: int) -> bytes:
    data = stream.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise ValueError(f"HTTP response exceeded {max_bytes} bytes")
    return data


def fetch_bytes(url: str, max_bytes: int) -> Tuple[bytes, Dict[str, str], str]:
    response = request(url)
    try:
        headers = _headers(response)
        declared_length = _content_length(headers)
        if declared_length is not None and declared_length > max_bytes:
            raise ValueError(f"HTTP response declared {declared_length} bytes, limit is {max_bytes}")
        data = _read_limited(response, max_bytes)
        if declared_length is not None and len(data) != declared_length:
            raise ValueError(
                f"HTTP response returned {len(data)} bytes, expected {declared_length}"
            )
        return data, headers, _final_url(response, url)
    finally:
        response.close()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_pcm16(path: Path) -> Tuple[int, float]:
    try:
        with wave.open(str(path), "rb") as wav:
            if wav.getcomptype() != "NONE":
                raise ValueError(f"{path} is compressed WAV, expected PCM")
            if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
                raise ValueError(f"{path} is not mono PCM16")
            if wav.getframerate() != 16_000:
                raise ValueError(f"{path} has unexpected sample rate {wav.getframerate()}")
            frames = wav.getnframes()
    except (EOFError, wave.Error) as error:
        raise ValueError(f"{path} is not a valid PCM WAV file") from error
    if frames <= 0:
        raise ValueError(f"{path} contains no audio frames")
    return frames, frames / 16_000


def pcm16_levels(path: Path) -> Tuple[float, float]:
    """Return normalized RMS and absolute peak without changing signal level."""

    validate_pcm16(path)
    square_sum = 0
    sample_count = 0
    peak = 0
    with wave.open(str(path), "rb") as wav:
        while True:
            raw = wav.readframes(65_536)
            if not raw:
                break
            samples = array.array("h")
            samples.frombytes(raw)
            if samples.itemsize != 2:
                raise ValueError("platform does not provide 16-bit signed samples")
            if sys.byteorder != "little":
                samples.byteswap()
            for sample in samples:
                magnitude = abs(sample)
                peak = max(peak, magnitude)
                square_sum += sample * sample
            sample_count += len(samples)
    if sample_count == 0:
        raise ValueError(f"{path} contains no audio samples")
    scale = 32_768.0
    return math.sqrt(square_sum / sample_count) / scale, peak / scale


def inspect_fleurs_float32(path: Path) -> Tuple[int, float, float]:
    """Validate the pinned FLEURS source WAV and return frames, RMS, and peak."""

    file_size = path.stat().st_size
    with path.open("rb") as source:
        header = source.read(12)
        if len(header) != 12 or header[:4] != b"RIFF" or header[8:] != b"WAVE":
            raise ValueError(f"{path} is not a RIFF/WAVE file")
        declared_size = struct.unpack("<I", header[4:8])[0] + 8
        if declared_size != file_size:
            raise ValueError(f"{path} has an inconsistent RIFF size")
        format_details: Optional[Tuple[int, int, int, int, int, int]] = None
        samples: Optional[array.array] = None
        while source.tell() < file_size:
            chunk_header = source.read(8)
            if len(chunk_header) != 8:
                raise ValueError(f"{path} has a truncated WAV chunk header")
            chunk_id = chunk_header[:4]
            chunk_size = struct.unpack("<I", chunk_header[4:])[0]
            if chunk_size > MAX_AUDIO_BYTES or source.tell() + chunk_size > file_size:
                raise ValueError(f"{path} has an unsafe WAV chunk size")
            chunk = source.read(chunk_size)
            if len(chunk) != chunk_size:
                raise ValueError(f"{path} has a truncated WAV chunk")
            if chunk_size % 2:
                if source.read(1) != b"\x00":
                    raise ValueError(f"{path} has invalid WAV chunk padding")
            if chunk_id == b"fmt ":
                if format_details is not None or len(chunk) < 16:
                    raise ValueError(f"{path} has invalid WAV format metadata")
                format_details = struct.unpack("<HHIIHH", chunk[:16])
            elif chunk_id == b"data":
                if samples is not None or chunk_size == 0 or chunk_size % 4:
                    raise ValueError(f"{path} has invalid float32 sample data")
                samples = array.array("f")
                samples.frombytes(chunk)
                if samples.itemsize != 4:
                    raise ValueError("platform does not provide 32-bit float samples")
                if sys.byteorder != "little":
                    samples.byteswap()
    if format_details != (3, 1, 16_000, 64_000, 4, 32) or samples is None:
        raise ValueError(f"{path} is not mono 16 kHz IEEE float32 WAV")
    square_sum = 0.0
    peak = 0.0
    for sample in samples:
        if not math.isfinite(sample):
            raise ValueError(f"{path} contains non-finite float samples")
        square_sum += sample * sample
        peak = max(peak, abs(sample))
    return len(samples), math.sqrt(square_sum / len(samples)), peak


def convert_to_pcm16(source: Path, destination: Path) -> None:
    subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source),
            "-ar",
            "16000",
            "-ac",
            "1",
            "-c:a",
            "pcm_s16le",
            str(destination),
        ],
        check=True,
    )


def _require_https_url(url: str, host: str, path: Optional[str] = None) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != host or parsed.username or parsed.password:
        raise ValueError(f"unexpected download URL {url!r}")
    if parsed.port not in (None, 443):
        raise ValueError(f"unexpected download port in {url!r}")
    if path is not None and parsed.path != path:
        raise ValueError(f"unexpected download path {parsed.path!r}")


def _validate_ascend_features(payload: Mapping[str, object]) -> None:
    features = payload.get("features")
    if not isinstance(features, list):
        raise ValueError("ASCEND response is missing feature metadata")
    by_name = {}
    for feature in features:
        if not isinstance(feature, dict) or not isinstance(feature.get("name"), str):
            raise ValueError("ASCEND response has malformed feature metadata")
        by_name[feature["name"]] = feature.get("type")
    required = {
        "id",
        "audio",
        "transcription",
        "duration",
        "language",
        "original_speaker_id",
        "session_id",
    }
    if not required.issubset(by_name):
        raise ValueError("ASCEND response schema changed")
    audio_type = by_name["audio"]
    if not isinstance(audio_type, dict) or audio_type.get("sampling_rate") != 16_000:
        raise ValueError("ASCEND audio feature is no longer 16 kHz")


def _ascend_audio_url(row_index: int, url: str) -> None:
    parsed = urlparse(url)
    allowed_paths = {
        f"/{asset_root}/CAiRE/ASCEND/--/{ASCEND_REVISION}/--/main/test/"
        f"{row_index}/audio/audio.wav"
        for asset_root in ("assets", "cached-assets")
    }
    _require_https_url(url, "datasets-server.huggingface.co")
    if parsed.path not in allowed_paths:
        raise ValueError(f"unexpected ASCEND audio path {parsed.path!r}")


def _new_dataset_staging(output_dir: Path, prefix: str) -> tempfile.TemporaryDirectory:
    output_dir.mkdir(parents=True, exist_ok=True)
    return tempfile.TemporaryDirectory(prefix=prefix, dir=str(output_dir))


def _ascend_rows_api_url(offset: int, length: int) -> str:
    query = urlencode(
        {
            "dataset": "CAiRE/ASCEND",
            "config": "main",
            "split": "test",
            "offset": offset,
            "length": length,
            "revision": ASCEND_REVISION,
        }
    )
    return "https://datasets-server.huggingface.co/rows?" + query


def _fetch_ascend_page(offset: int, length: int) -> Tuple[List[dict], str]:
    api_url = _ascend_rows_api_url(offset, length)
    payload_bytes, api_headers, final_api_url = fetch_bytes(api_url, MAX_JSON_BYTES)
    _require_https_url(final_api_url, "datasets-server.huggingface.co", "/rows")
    if api_headers.get("x-revision") != ASCEND_REVISION:
        raise ValueError("ASCEND datasets-server did not confirm the pinned revision")
    content_type = api_headers.get("content-type", "").partition(";")[0].strip().lower()
    if content_type != "application/json":
        raise ValueError(f"ASCEND datasets-server returned {content_type or 'no content type'}")
    try:
        payload = json.loads(payload_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("ASCEND datasets-server returned invalid JSON") from error
    if not isinstance(payload, dict):
        raise ValueError("ASCEND datasets-server response is not an object")
    _validate_ascend_features(payload)
    if payload.get("num_rows_total") != ASCEND_TEST_ROWS or payload.get("partial") is True:
        raise ValueError("ASCEND pinned test split size or availability changed")
    payload_rows = payload.get("rows")
    if not isinstance(payload_rows, list):
        raise ValueError("ASCEND response is missing rows")
    if any(
        not isinstance(item, dict)
        or isinstance(item.get("row_idx"), bool)
        or not isinstance(item.get("row_idx"), int)
        or not isinstance(item.get("row"), dict)
        for item in payload_rows
    ):
        raise ValueError("ASCEND response contains a malformed row")
    payload_rows = sorted(payload_rows, key=lambda item: item["row_idx"])
    expected_indices = list(range(offset, offset + length))
    if len(payload_rows) != length or [item["row_idx"] for item in payload_rows] != expected_indices:
        raise ValueError("ASCEND rows API did not return the requested deterministic page")
    return payload_rows, api_headers["x-revision"]


def _ascend_stratified_sample(rows: List[dict], count: int) -> List[dict]:
    """Balance speakers and language labels, then spread picks across each stratum."""

    language_order = {"zh": 0, "en": 1, "mixed": 2}
    groups: Dict[Tuple[int, str], List[dict]] = {}
    for item in rows:
        row = item["row"]
        speaker = row.get("original_speaker_id")
        language = row.get("language")
        if isinstance(speaker, bool) or not isinstance(speaker, int):
            raise ValueError(f"ASCEND row {item['row_idx']} has an invalid speaker id")
        if language not in language_order:
            raise ValueError(f"ASCEND row {item['row_idx']} has unknown language {language!r}")
        groups.setdefault((speaker, language), []).append(item)
    keys = sorted(groups, key=lambda key: (key[0], language_order[key[1]]))
    allocations = {key: 0 for key in keys}
    remaining = count
    while remaining:
        made_progress = False
        for key in keys:
            if remaining == 0:
                break
            if allocations[key] < len(groups[key]):
                allocations[key] += 1
                remaining -= 1
                made_progress = True
        if not made_progress:
            raise ValueError(f"ASCEND test split contains fewer than {count} usable rows")

    selected: List[dict] = []
    for key in keys:
        group = sorted(groups[key], key=lambda item: item["row_idx"])
        take = allocations[key]
        for selected_index in range(take):
            # Midpoints of equally sized bins cover the complete stratum without randomness.
            group_index = ((2 * selected_index + 1) * len(group)) // (2 * take)
            selected.append(group[group_index])
    return sorted(selected, key=lambda item: item["row_idx"])


def _download_ascend_entry(item: dict, staging_dir: Path) -> dict:
    if not isinstance(item, dict) or not isinstance(item.get("row"), dict):
        raise ValueError("ASCEND returned a malformed row")
    row_idx = item["row_idx"]
    row = item["row"]
    row_id = row.get("id")
    if not isinstance(row_id, str) or _SAFE_ASCEND_ID.fullmatch(row_id) is None:
        raise ValueError(f"ASCEND row {row_idx} has unsafe id {row_id!r}")
    language = row.get("language")
    if language not in {"zh", "en", "mixed"}:
        raise ValueError(f"ASCEND row {row_idx} has unknown language {language!r}")
    reference = row.get("transcription")
    if not isinstance(reference, str) or not reference.strip():
        raise ValueError(f"ASCEND row {row_idx} has an empty transcription")
    audio_cell = row.get("audio")
    if not isinstance(audio_cell, list) or len(audio_cell) != 1:
        raise ValueError(f"ASCEND row {row_idx} has unexpected audio metadata")
    audio_meta = audio_cell[0]
    if not isinstance(audio_meta, dict) or not isinstance(audio_meta.get("src"), str):
        raise ValueError(f"ASCEND row {row_idx} has malformed audio metadata")
    if audio_meta.get("type") not in (None, "audio/wav"):
        raise ValueError(f"ASCEND row {row_idx} is no longer WAV audio")
    audio_url = audio_meta["src"]
    _ascend_audio_url(row_idx, audio_url)
    audio_bytes, audio_headers, final_audio_url = fetch_bytes(audio_url, MAX_AUDIO_BYTES)
    _ascend_audio_url(row_idx, final_audio_url)
    audio_etag = audio_headers.get("etag")
    if not audio_etag:
        raise ValueError(f"ASCEND row {row_idx} audio has no ETag")
    filename = f"{row_idx:03d}_{language}_{row_id}.wav"
    audio_path = staging_dir / filename
    audio_path.write_bytes(audio_bytes)
    frames, duration = validate_pcm16(audio_path)
    audio_rms, audio_peak = pcm16_levels(audio_path)
    declared_duration = row.get("duration")
    if (
        isinstance(declared_duration, bool)
        or not isinstance(declared_duration, (int, float))
        or not math.isfinite(float(declared_duration))
        or abs(duration - float(declared_duration)) > 0.001
    ):
        raise ValueError(f"ASCEND row {row_idx} duration does not match its WAV")
    speaker = row.get("original_speaker_id")
    session = row.get("session_id")
    if isinstance(speaker, bool) or not isinstance(speaker, int):
        raise ValueError(f"ASCEND row {row_idx} has an invalid speaker id")
    if isinstance(session, bool) or not isinstance(session, int):
        raise ValueError(f"ASCEND row {row_idx} has an invalid session id")
    return {
        "id": f"ascend_test_{row_idx:03d}_{row_id}",
        "language": language,
        "category": "public_ascend",
        "reference": reference,
        "hotwords": [],
        "voice": str(speaker),
        "condition": "spontaneous_conversation",
        "audio": str(Path("ascend") / filename),
        "source": "CAiRE/ASCEND",
        "source_revision": ASCEND_REVISION,
        "source_split": "test",
        "source_row": row_idx,
        "source_session": session,
        "source_license": "CC-BY-SA-4.0",
        "source_audio_etag": audio_etag,
        "source_duration_seconds": float(declared_duration),
        "audio_sha256": sha256(audio_bytes),
        "audio_frames": frames,
        "duration_seconds": duration,
        "audio_rms": audio_rms,
        "audio_peak": audio_peak,
        "audio_level_processing": "none",
    }


def prepare_ascend(output_dir: Path, count: int) -> Tuple[List[dict], dict]:
    if not 0 <= count <= MAX_ASCEND_ROWS:
        raise ValueError(f"--ascend-count must be between 0 and {MAX_ASCEND_ROWS}")
    if count == 0:
        return [], {
            "requested": 0,
            "downloaded": 0,
            "revision": ASCEND_REVISION,
        }

    target_dir = output_dir / "ascend"
    if target_dir.exists():
        raise FileExistsError(f"{target_dir} already exists")
    rows: List[dict] = []
    api_revisions = set()
    for offset in range(0, ASCEND_TEST_ROWS, ASCEND_PAGE_SIZE):
        length = min(ASCEND_PAGE_SIZE, ASCEND_TEST_ROWS - offset)
        page, api_revision = _fetch_ascend_page(offset, length)
        rows.extend(page)
        api_revisions.add(api_revision)
    if len(rows) != ASCEND_TEST_ROWS or api_revisions != {ASCEND_REVISION}:
        raise ValueError("ASCEND metadata scan was incomplete or mixed revisions")
    selected = _ascend_stratified_sample(rows, count)

    entries: List[dict] = []
    language_counts: Dict[str, int] = {}
    speaker_counts: Dict[str, int] = {}
    stratum_counts: Dict[str, int] = {}
    with _new_dataset_staging(output_dir, ".ascend-staging-") as temporary:
        staging_dir = Path(temporary) / "ascend"
        staging_dir.mkdir()
        executor = ThreadPoolExecutor(max_workers=min(ASCEND_DOWNLOAD_WORKERS, len(selected)))
        futures = {
            executor.submit(_download_ascend_entry, item, staging_dir): item["row_idx"]
            for item in selected
        }
        downloaded_by_row: Dict[int, dict] = {}
        try:
            for completed, future in enumerate(as_completed(futures), start=1):
                row_idx = futures[future]
                downloaded_by_row[row_idx] = future.result()
                if completed % 100 == 0 or completed == len(selected):
                    print(f"ASCEND audio: {completed}/{len(selected)}", flush=True)
        except BaseException:
            for future in futures:
                future.cancel()
            executor.shutdown(wait=True, cancel_futures=True)
            raise
        else:
            executor.shutdown(wait=True)
        for item in selected:
            entry = downloaded_by_row[item["row_idx"]]
            language = entry["language"]
            speaker_key = entry["voice"]
            language_counts[language] = language_counts.get(language, 0) + 1
            speaker_counts[speaker_key] = speaker_counts.get(speaker_key, 0) + 1
            stratum_key = f"speaker={speaker_key},language={language}"
            stratum_counts[stratum_key] = stratum_counts.get(stratum_key, 0) + 1
            entries.append(entry)
        staging_dir.rename(target_dir)

    return entries, {
        "requested": count,
        "downloaded": len(entries),
        "language_counts": dict(sorted(language_counts.items())),
        "speaker_counts": dict(sorted(speaker_counts.items())),
        "stratum_counts": dict(sorted(stratum_counts.items())),
        "revision": ASCEND_REVISION,
        "source_api": "https://datasets-server.huggingface.co/rows",
        "source_api_x_revision": ASCEND_REVISION,
        "source_test_rows": ASCEND_TEST_ROWS,
        "metadata_page_size": ASCEND_PAGE_SIZE,
        "sampling_strategy": "speaker_language_balanced_stratum_midpoints_v1",
        "selected_rows": [item["row_idx"] for item in selected],
        "audio_level_processing": "none",
    }


def _safe_fleurs_filename(filename: str) -> str:
    path = PurePosixPath(filename)
    if (
        len(path.parts) != 1
        or path.name != filename
        or _SAFE_FLEURS_AUDIO.fullmatch(filename) is None
    ):
        raise ValueError(f"unsafe FLEURS audio filename {filename!r}")
    return filename


def _parse_fleurs_tsv(tsv_bytes: bytes, lang_id: str, count: int) -> List[dict]:
    try:
        text = tsv_bytes.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError(f"FLEURS {lang_id} dev.tsv is not UTF-8") from error
    rows: List[dict] = []
    filenames = set()
    for line_number, line in enumerate(text.splitlines(), start=1):
        columns = line.split("\t")
        if len(columns) != 7:
            raise ValueError(
                f"FLEURS {lang_id} dev.tsv line {line_number} has an unexpected schema"
            )
        sentence_id, filename, raw_reference, reference, words, samples, gender = columns
        filename = _safe_fleurs_filename(filename)
        if filename in filenames:
            raise ValueError(f"FLEURS {lang_id} dev.tsv repeats {filename}")
        filenames.add(filename)
        try:
            num_samples = int(samples)
        except ValueError as error:
            raise ValueError(f"FLEURS {lang_id} has invalid samples for {filename}") from error
        if (
            not sentence_id.isdigit()
            or not raw_reference.strip()
            or not reference.strip()
            or not words.strip()
            or num_samples <= 0
            or not gender.strip()
        ):
            raise ValueError(f"FLEURS {lang_id} has invalid metadata for {filename}")
        rows.append(
            {
                "sentence_id": sentence_id,
                "filename": filename,
                "raw_reference": raw_reference,
                "reference": reference,
                "words": words,
                "num_samples": num_samples,
                "gender": gender.lower(),
            }
        )
    if len(rows) < count:
        raise ValueError(
            f"FLEURS {lang_id} dev.tsv has {len(rows)} rows, requested {count}"
        )
    return rows


def _read_tar_member(member: tarfile.TarInfo, source: BinaryIO, destination: Path) -> str:
    if member.size <= 0 or member.size > MAX_AUDIO_BYTES:
        raise ValueError(f"unsafe FLEURS member size for {member.name!r}: {member.size}")
    digest = hashlib.sha256()
    remaining = member.size
    with destination.open("wb") as output:
        while remaining:
            chunk = source.read(min(1024 * 1024, remaining))
            if not chunk:
                raise ValueError(f"truncated FLEURS member {member.name!r}")
            output.write(chunk)
            digest.update(chunk)
            remaining -= len(chunk)
    return digest.hexdigest()


def _has_header_value(headers: Mapping[str, str], name: str, expected: str) -> bool:
    return expected in {part.strip() for part in headers.get(name, "").split(",")}


def prepare_fleurs_language(
    output_dir: Path, lang_id: str, count: int
) -> Tuple[List[dict], dict]:
    if lang_id not in FLEURS_LANGUAGES:
        raise ValueError(f"unknown FLEURS language {lang_id!r}")
    if not 0 <= count <= MAX_FLEURS_ROWS_PER_LANGUAGE:
        raise ValueError(
            "--fleurs-count-per-language must be between 0 and "
            f"{MAX_FLEURS_ROWS_PER_LANGUAGE}"
        )
    expected = FLEURS_LANGUAGES[lang_id]
    if count == 0:
        return [], {
            "requested": 0,
            "downloaded": 0,
            "language": expected["language"],
            "revision": FLEURS_REVISION,
        }

    target_dir = output_dir / "fleurs" / lang_id
    if target_dir.exists():
        raise FileExistsError(f"{target_dir} already exists")
    archive_path = f"/xtreme_translations/FLEURS102/{lang_id}.tar.gz"
    url = f"https://storage.googleapis.com{archive_path}"
    response = request(url)
    reader: Optional[CountingReader] = None
    try:
        _require_https_url(_final_url(response, url), "storage.googleapis.com", archive_path)
        headers = _headers(response)
        if headers.get("etag") != expected["etag"]:
            raise ValueError(f"{lang_id} FLEURS ETag changed; review the upstream artifact")
        if _content_length(headers) != expected["content_length"]:
            raise ValueError(f"{lang_id} FLEURS archive size changed; review the upstream artifact")
        if headers.get("x-goog-generation") != expected["generation"]:
            raise ValueError(
                f"{lang_id} FLEURS object generation changed; review the upstream artifact"
            )
        if not _has_header_value(headers, "x-goog-hash", expected["crc32c"]):
            raise ValueError(f"{lang_id} FLEURS CRC32C changed; review the upstream artifact")

        reader = CountingReader(response, MAX_FLEURS_STREAM_BYTES)
        with _new_dataset_staging(output_dir, f".fleurs-{lang_id}-staging-") as temporary:
            staging_root = Path(temporary)
            raw_dir = staging_root / "raw"
            normalized_dir = staging_root / "normalized"
            raw_dir.mkdir()
            normalized_dir.mkdir()
            metadata_by_filename: Optional[Dict[str, dict]] = None
            selected: List[dict] = []
            source_hashes: Dict[str, str] = {}
            saw_dev_audio = False
            with tarfile.open(fileobj=reader, mode="r|gz") as archive:
                for member in archive:
                    if member.name == f"{lang_id}/dev.tsv":
                        if metadata_by_filename is not None or not member.isfile():
                            raise ValueError(f"FLEURS {lang_id} contains invalid dev.tsv metadata")
                        if member.size <= 0 or member.size > MAX_TSV_BYTES:
                            raise ValueError(f"FLEURS {lang_id} dev.tsv has an unsafe size")
                        source = archive.extractfile(member)
                        if source is None:
                            raise ValueError(f"FLEURS {lang_id} dev.tsv could not be read")
                        tsv_bytes = _read_limited(source, MAX_TSV_BYTES)
                        if len(tsv_bytes) != member.size:
                            raise ValueError(f"FLEURS {lang_id} dev.tsv is truncated")
                        metadata = _parse_fleurs_tsv(tsv_bytes, lang_id, count)
                        metadata_by_filename = {row["filename"]: row for row in metadata}
                    elif member.name.startswith(f"{lang_id}/audio/dev/"):
                        saw_dev_audio = True
                        if metadata_by_filename is None:
                            raise ValueError(
                                f"FLEURS {lang_id} archive layout changed: audio precedes dev.tsv"
                            )
                        if not member.isfile():
                            continue
                        filename = PurePosixPath(member.name).name
                        expected_name = f"{lang_id}/audio/dev/{filename}"
                        if member.name != expected_name:
                            raise ValueError(f"unsafe FLEURS tar member {member.name!r}")
                        _safe_fleurs_filename(filename)
                        if filename not in metadata_by_filename:
                            raise ValueError(f"FLEURS metadata is missing {filename}")
                        if filename in source_hashes:
                            raise ValueError(f"FLEURS archive repeats {filename}")
                        source = archive.extractfile(member)
                        if source is None:
                            raise ValueError(f"FLEURS member {filename} could not be read")
                        source_hashes[filename] = _read_tar_member(
                            member, source, raw_dir / filename
                        )
                        selected.append(metadata_by_filename[filename])
                        if len(selected) == count:
                            break
            if metadata_by_filename is None or not saw_dev_audio or len(selected) != count:
                raise ValueError(f"FLEURS {lang_id} stream did not contain the requested rows")

            entries: List[dict] = []
            for row in selected:
                filename = row["filename"]
                source_path = raw_dir / filename
                destination = normalized_dir / filename
                source_frames, source_rms, source_peak = inspect_fleurs_float32(source_path)
                if source_frames != row["num_samples"]:
                    raise ValueError(f"FLEURS {filename} source frame count changed")
                convert_to_pcm16(source_path, destination)
                frames, duration = validate_pcm16(destination)
                audio_rms, audio_peak = pcm16_levels(destination)
                if frames != row["num_samples"]:
                    raise ValueError(f"FLEURS {filename} frame count changed during conversion")
                utterance_id = PurePosixPath(filename).stem
                entries.append(
                    {
                        "id": f"fleurs_{lang_id}_validation_{utterance_id}",
                        "language": expected["language"],
                        "category": "public_fleurs",
                        "reference": row["reference"],
                        "raw_reference": row["raw_reference"],
                        "hotwords": [],
                        "voice": row["gender"],
                        "condition": "read_speech",
                        "audio": str(Path("fleurs") / lang_id / filename),
                        "source": "google/FLEURS",
                        "source_revision": FLEURS_REVISION,
                        "source_split": "validation",
                        "source_sentence_id": row["sentence_id"],
                        "source_audio_file": filename,
                        "source_license": "CC-BY-4.0",
                        "source_archive_etag": expected["etag"],
                        "source_archive_generation": expected["generation"],
                        "source_archive_crc32c": expected["crc32c"],
                        "audio_source_sha256": source_hashes[filename],
                        "source_audio_rms": source_rms,
                        "source_audio_peak": source_peak,
                        "audio_sha256": file_sha256(destination),
                        "audio_frames": frames,
                        "duration_seconds": duration,
                        "audio_rms": audio_rms,
                        "audio_peak": audio_peak,
                        "audio_level_processing": "none",
                        "source_sample_format": "float32",
                        "output_sample_format": "pcm_s16le",
                    }
                )
            target_dir.parent.mkdir(parents=True, exist_ok=True)
            normalized_dir.rename(target_dir)
    finally:
        response.close()

    assert reader is not None
    return entries, {
        "requested": count,
        "downloaded": len(entries),
        "language": expected["language"],
        "revision": FLEURS_REVISION,
        "source_archive": url,
        "source_archive_etag": expected["etag"],
        "source_archive_generation": expected["generation"],
        "source_archive_crc32c": expected["crc32c"],
        "source_archive_content_length": expected["content_length"],
        "compressed_bytes_consumed": reader.bytes_read,
        "audio_level_processing": "none",
        "source_sample_format": "float32",
        "output_sample_format": "pcm_s16le",
    }


def _write_bundle_metadata(output_dir: Path, entries: Iterable[dict], provenance: dict) -> None:
    ordered_entries = list(entries)
    manifest = output_dir / "manifest.jsonl"
    manifest.write_text(
        "".join(
            json.dumps(entry, ensure_ascii=False, sort_keys=True) + "\n"
            for entry in ordered_entries
        ),
        encoding="utf-8",
    )
    provenance = dict(provenance)
    provenance.update(
        {
            "producer": PRODUCER,
            "schema_version": BUNDLE_SCHEMA_VERSION,
            "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
            "cases": len(ordered_entries),
        }
    )
    (output_dir / "provenance.json").write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _is_managed_bundle(path: Path) -> bool:
    provenance_path = path / "provenance.json"
    if not provenance_path.is_file() or provenance_path.is_symlink():
        return False
    try:
        provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return False
    return (
        isinstance(provenance, dict)
        and provenance.get("producer") == PRODUCER
        and provenance.get("schema_version") == BUNDLE_SCHEMA_VERSION
    )


def _validate_output_target(output_dir: Path, overwrite: bool) -> None:
    resolved = output_dir.resolve()
    dangerous = {Path(resolved.anchor), Path.home().resolve(), Path.cwd().resolve()}
    if resolved in dangerous:
        raise ValueError(f"refusing unsafe output directory {output_dir}")
    if output_dir.is_symlink():
        raise ValueError(f"refusing symlink output directory {output_dir}")
    if output_dir.exists() and not output_dir.is_dir():
        raise ValueError(f"output path is not a directory: {output_dir}")
    if not output_dir.exists() or not any(output_dir.iterdir()):
        return
    if not overwrite:
        raise FileExistsError(f"{output_dir} is not empty; use --overwrite")
    if not _is_managed_bundle(output_dir):
        raise ValueError(f"refusing to overwrite unmanaged directory {output_dir}")


def _install_bundle(staging_dir: Path, output_dir: Path, overwrite: bool) -> None:
    _validate_output_target(output_dir, overwrite)
    backup: Optional[Path] = None
    if output_dir.exists():
        if any(output_dir.iterdir()):
            backup = Path(
                tempfile.mkdtemp(prefix=f".{output_dir.name}-backup-", dir=str(output_dir.parent))
            )
            backup.rmdir()
            output_dir.rename(backup)
        else:
            output_dir.rmdir()
    try:
        staging_dir.rename(output_dir)
    except BaseException:
        if backup is not None and not output_dir.exists():
            backup.rename(output_dir)
        raise
    if backup is not None:
        shutil.rmtree(backup)


def prepare_public_corpora(
    output_dir: Path, ascend_count: int, fleurs_count_per_language: int, overwrite: bool
) -> Path:
    if not 0 <= ascend_count <= MAX_ASCEND_ROWS:
        raise ValueError(f"--ascend-count must be between 0 and {MAX_ASCEND_ROWS}")
    if not 0 <= fleurs_count_per_language <= MAX_FLEURS_ROWS_PER_LANGUAGE:
        raise ValueError(
            "--fleurs-count-per-language must be between 0 and "
            f"{MAX_FLEURS_ROWS_PER_LANGUAGE}"
        )
    output_dir = output_dir.expanduser().absolute()
    _validate_output_target(output_dir, overwrite)
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    staging_dir = Path(
        tempfile.mkdtemp(prefix=f".{output_dir.name}-staging-", dir=str(output_dir.parent))
    )
    try:
        entries, ascend_provenance = prepare_ascend(staging_dir, ascend_count)
        provenance = {"ascend": ascend_provenance, "fleurs": {}}
        for language in sorted(FLEURS_LANGUAGES):
            language_entries, language_provenance = prepare_fleurs_language(
                staging_dir, language, fleurs_count_per_language
            )
            entries.extend(language_entries)
            provenance["fleurs"][language] = language_provenance
        _write_bundle_metadata(staging_dir, entries, provenance)
        _install_bundle(staging_dir, output_dir, overwrite)
    except BaseException:
        if staging_dir.exists():
            shutil.rmtree(staging_dir)
        raise
    return output_dir


def main() -> None:
    args = parse_args()
    if args.fleurs_count_per_language > 0 and shutil.which("ffmpeg") is None:
        raise SystemExit("ffmpeg is required to normalize FLEURS float32 WAV audio")
    try:
        output_dir = prepare_public_corpora(
            args.output_dir,
            args.ascend_count,
            args.fleurs_count_per_language,
            args.overwrite,
        )
    except (FileExistsError, OSError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
    print(f"Prepared public real-speech cases in {output_dir}")
    print(f"Manifest: {output_dir / 'manifest.jsonl'}")


if __name__ == "__main__":
    main()

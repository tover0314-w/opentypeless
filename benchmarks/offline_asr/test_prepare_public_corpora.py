from __future__ import annotations

import io
import json
import os
import struct
import tarfile
import tempfile
import unittest
import wave
from pathlib import Path
from unittest import mock

from benchmarks.offline_asr import prepare_public_corpora as subject


class FakeResponse(io.BytesIO):
    def __init__(self, body: bytes, headers: dict, url: str) -> None:
        super().__init__(body)
        self.headers = headers
        self.url = url

    def geturl(self) -> str:
        return self.url


def pcm16_wav(frames: int = 160, channels: int = 1, rate: int = 16_000) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(channels)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes(b"\x00\x00" * frames * channels)
    return output.getvalue()


def float32_wav(frames: int = 160, amplitude: float = 0.001) -> bytes:
    def chunk(name: bytes, payload: bytes) -> bytes:
        padding = b"\x00" if len(payload) % 2 else b""
        return name + struct.pack("<I", len(payload)) + payload + padding

    fmt = struct.pack("<HHIIHHH", 3, 1, 16_000, 64_000, 4, 32, 0)
    samples = [amplitude if index % 2 else -amplitude for index in range(frames)]
    body = b"WAVE" + chunk(b"fmt ", fmt)
    body += chunk(b"fact", struct.pack("<I", frames))
    body += chunk(b"data", struct.pack(f"<{frames}f", *samples))
    return b"RIFF" + struct.pack("<I", len(body)) + body


def ascend_features() -> list:
    scalar = {"dtype": "string", "_type": "Value"}
    names = [
        "id",
        "transcription",
        "duration",
        "language",
        "original_speaker_id",
        "session_id",
    ]
    features = [
        {"feature_idx": index, "name": name, "type": scalar}
        for index, name in enumerate(names)
    ]
    features.append(
        {
            "feature_idx": len(features),
            "name": "audio",
            "type": {"sampling_rate": 16_000, "_type": "Audio"},
        }
    )
    return features


def ascend_row(
    index: int,
    language: str = "zh",
    row_id: str | None = None,
    speaker: int = 17,
    asset_root: str = "assets",
) -> dict:
    row_id = row_id or f"{index:05d}"
    url = (
        f"https://datasets-server.huggingface.co/{asset_root}/CAiRE/ASCEND/--/"
        f"{subject.ASCEND_REVISION}/--/main/test/{index}/audio/audio.wav?Signature=test"
    )
    return {
        "row_idx": index,
        "row": {
            "id": row_id,
            "audio": [{"src": url, "type": "audio/wav"}],
            "transcription": "测试" if language != "en" else "test speech",
            "duration": 0.01,
            "language": language,
            "original_speaker_id": speaker,
            "session_id": 1,
        },
    }


def ascend_payload(rows: list) -> bytes:
    return json.dumps(
        {
            "dataset": "CAiRE/ASCEND",
            "config": "main",
            "split": "test",
            "features": ascend_features(),
            "rows": rows,
            "num_rows_per_page": len(rows),
            "num_rows_total": len(rows),
            "partial": False,
        }
    ).encode("utf-8")


def fleurs_archive(tsv_lines: list[str], audio_order: list[str], tail_size: int = 0) -> bytes:
    output = io.BytesIO()
    audio = float32_wav()
    with tarfile.open(fileobj=output, mode="w:gz") as archive:
        tsv = ("\n".join(tsv_lines) + "\n").encode("utf-8")
        info = tarfile.TarInfo("test_lang/dev.tsv")
        info.size = len(tsv)
        archive.addfile(info, io.BytesIO(tsv))
        for filename in audio_order:
            info = tarfile.TarInfo(f"test_lang/audio/dev/{filename}")
            info.size = len(audio)
            archive.addfile(info, io.BytesIO(audio))
        if tail_size:
            tail = os.urandom(tail_size)
            info = tarfile.TarInfo("test_lang/audio/train/not-needed.wav")
            info.size = len(tail)
            archive.addfile(info, io.BytesIO(tail))
    return output.getvalue()


def fleurs_expectations(archive: bytes) -> dict:
    return {
        "language": "en",
        "etag": '"fixture-etag"',
        "content_length": len(archive),
        "generation": "1234",
        "crc32c": "crc32c=fixture",
    }


def fleurs_headers(archive: bytes, *, etag: str = '"fixture-etag"') -> dict:
    return {
        "ETag": etag,
        "Content-Length": str(len(archive)),
        "x-goog-generation": "1234",
        "x-goog-hash": "md5=ignored,crc32c=fixture",
    }


class PcmValidationTests(unittest.TestCase):
    def test_accepts_only_nonempty_mono_16khz_pcm16(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            valid = root / "valid.wav"
            valid.write_bytes(pcm16_wav())
            self.assertEqual((160, 0.01), subject.validate_pcm16(valid))
            for name, fixture in (
                ("stereo.wav", pcm16_wav(channels=2)),
                ("wrong-rate.wav", pcm16_wav(rate=8_000)),
                ("empty.wav", pcm16_wav(frames=0)),
                ("invalid.wav", b"not a wav"),
            ):
                with self.subTest(name=name):
                    path = root / name
                    path.write_bytes(fixture)
                    with self.assertRaises(ValueError):
                        subject.validate_pcm16(path)

    def test_repeated_http_hash_headers_are_preserved(self) -> None:
        class RepeatedHeaders:
            @staticmethod
            def items():
                return [("x-goog-hash", "crc32c=fixed"), ("x-goog-hash", "md5=other")]

        response = type("Response", (), {"headers": RepeatedHeaders()})()
        headers = subject._headers(response)
        self.assertTrue(subject._has_header_value(headers, "x-goog-hash", "crc32c=fixed"))
        self.assertTrue(subject._has_header_value(headers, "x-goog-hash", "md5=other"))


class AscendPreparationTests(unittest.TestCase):
    def run_prepare(self, output: Path, rows: list, audio_by_index: dict[int, bytes]):
        payload = ascend_payload(rows)

        def fetch(url: str, _limit: int):
            if "/rows?" in url:
                return (
                    payload,
                    {
                        "content-type": "application/json; charset=utf-8",
                        "x-revision": subject.ASCEND_REVISION,
                    },
                    url,
                )
            index = int(url.split("/main/test/")[1].split("/")[0])
            return audio_by_index[index], {"etag": f'"etag-{index}"'}, url

        with mock.patch.object(subject, "ASCEND_TEST_ROWS", len(rows)), mock.patch.object(
            subject, "fetch_bytes", side_effect=fetch
        ):
            return subject.prepare_ascend(output, len(rows))

    def test_maps_fields_and_sorts_the_pinned_first_rows(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            entries, provenance = self.run_prepare(
                output,
                [ascend_row(1, "en"), ascend_row(0, "zh")],
                {0: pcm16_wav(), 1: pcm16_wav()},
            )

            self.assertEqual([0, 1], [entry["source_row"] for entry in entries])
            self.assertEqual(["zh", "en"], [entry["language"] for entry in entries])
            self.assertEqual('"etag-0"', entries[0]["source_audio_etag"])
            self.assertEqual("17", entries[0]["voice"])
            self.assertEqual(subject.ASCEND_REVISION, provenance["source_api_x_revision"])
            self.assertTrue((output / entries[0]["audio"]).is_file())
            self.assertEqual("none", entries[0]["audio_level_processing"])
            self.assertEqual(0.0, entries[0]["audio_rms"])

    def test_rejects_unsafe_row_ids_without_leaving_partial_dataset(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            with self.assertRaisesRegex(ValueError, "unsafe id"):
                self.run_prepare(output, [ascend_row(0, row_id="../escape")], {})
            self.assertFalse((output / "ascend").exists())
            self.assertFalse((output.parent / "escape.wav").exists())

    def test_invalid_second_audio_rolls_back_the_first(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            with self.assertRaisesRegex(ValueError, "valid PCM WAV"):
                self.run_prepare(
                    output,
                    [ascend_row(0), ascend_row(1)],
                    {0: pcm16_wav(), 1: b"broken"},
                )
            self.assertFalse((output / "ascend").exists())
            self.assertEqual([], list(output.glob(".ascend-staging-*")))

    def test_requires_server_confirmation_of_revision_and_audio_etag(self) -> None:
        payload = ascend_payload([ascend_row(0)])
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            with mock.patch.object(
                subject,
                "fetch_bytes",
                return_value=(
                    payload,
                    {"content-type": "application/json", "x-revision": "changed"},
                    "https://datasets-server.huggingface.co/rows",
                ),
            ), self.assertRaisesRegex(ValueError, "pinned revision"):
                subject.prepare_ascend(output, 1)

    def test_accepts_only_known_revision_pinned_asset_roots(self) -> None:
        for asset_root in ("assets", "cached-assets"):
            with self.subTest(asset_root=asset_root):
                url = ascend_row(15, asset_root=asset_root)["row"]["audio"][0]["src"]
                subject._ascend_audio_url(15, url)
        for asset_root in ("cache-assets", "other", "../assets"):
            with self.subTest(asset_root=asset_root), self.assertRaises(ValueError):
                url = ascend_row(15, asset_root=asset_root)["row"]["audio"][0]["src"]
                subject._ascend_audio_url(15, url)

    def test_stratified_sample_balances_speakers_and_languages(self) -> None:
        rows = [ascend_row(index, "zh", speaker=17) for index in range(8)]
        rows.extend(ascend_row(index, "en", speaker=3) for index in range(8, 10))
        selected = subject._ascend_stratified_sample(rows, 4)
        strata = [
            (item["row"]["original_speaker_id"], item["row"]["language"])
            for item in selected
        ]
        self.assertEqual(2, strata.count((17, "zh")))
        self.assertEqual(2, strata.count((3, "en")))
        self.assertNotEqual([0, 1], [item["row_idx"] for item in selected if item["row"]["language"] == "zh"])


class FleursPreparationTests(unittest.TestCase):
    lines = [
        "77\t100.wav\tRaw first.\tfirst normalized\tf i r s t |\t160\tFEMALE",
        "77\t200.wav\tRaw second.\tsecond normalized\ts e c o n d |\t160\tMALE",
    ]

    def prepare(self, output: Path, archive: bytes, count: int = 2, **header_changes):
        url = "https://storage.googleapis.com/xtreme_translations/FLEURS102/test_lang.tar.gz"
        headers = fleurs_headers(archive)
        headers.update(header_changes)
        response = FakeResponse(archive, headers, url)

        def copy_conversion(_source: Path, destination: Path) -> None:
            destination.write_bytes(pcm16_wav())

        with mock.patch.dict(
            subject.FLEURS_LANGUAGES,
            {"test_lang": fleurs_expectations(archive)},
            clear=True,
        ), mock.patch.object(subject, "request", return_value=response), mock.patch.object(
            subject, "convert_to_pcm16", side_effect=copy_conversion
        ):
            result = subject.prepare_fleurs_language(output, "test_lang", count)
        return result, response

    def test_uses_pinned_tar_order_and_audio_filename_for_unique_case_ids(self) -> None:
        archive = fleurs_archive(self.lines, ["200.wav", "100.wav"], tail_size=1024 * 1024)
        with tempfile.TemporaryDirectory() as temporary:
            (entries, provenance), _ = self.prepare(Path(temporary), archive)

            self.assertEqual(
                [
                    "fleurs_test_lang_validation_200",
                    "fleurs_test_lang_validation_100",
                ],
                [entry["id"] for entry in entries],
            )
            self.assertEqual(["77", "77"], [entry["source_sentence_id"] for entry in entries])
            self.assertEqual(["male", "female"], [entry["voice"] for entry in entries])
            self.assertEqual("second normalized", entries[0]["reference"])
            self.assertEqual("Raw second.", entries[0]["raw_reference"])
            self.assertEqual("none", entries[0]["audio_level_processing"])
            self.assertEqual("float32", entries[0]["source_sample_format"])
            self.assertAlmostEqual(0.001, entries[0]["source_audio_rms"], places=6)
            self.assertAlmostEqual(0.001, entries[0]["source_audio_peak"], places=6)
            self.assertLess(provenance["compressed_bytes_consumed"], len(archive))

    def test_missing_audio_rolls_back_normalized_output(self) -> None:
        archive = fleurs_archive(self.lines, ["100.wav"])
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            with self.assertRaisesRegex(ValueError, "requested rows"):
                self.prepare(output, archive)
            self.assertFalse((output / "fleurs" / "test_lang").exists())
            self.assertEqual([], list(output.glob(".fleurs-test_lang-staging-*")))

    def test_rejects_unsafe_tsv_filename(self) -> None:
        lines = ["77\t../escape.wav\tRaw.\tnormalized\tw |\t160\tFEMALE"]
        archive = fleurs_archive(lines, ["100.wav"])
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            with self.assertRaisesRegex(ValueError, "unsafe FLEURS audio filename"):
                self.prepare(output, archive, count=1)
            self.assertFalse((output.parent / "escape.wav").exists())

    def test_rejects_changed_etag_before_reading_archive(self) -> None:
        archive = fleurs_archive(self.lines, ["100.wav", "200.wav"])
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            with self.assertRaisesRegex(ValueError, "ETag changed"):
                self.prepare(output, archive, ETag='"changed"')
            self.assertFalse((output / "fleurs").exists())


class BundleTransactionTests(unittest.TestCase):
    def test_failed_refresh_preserves_previous_managed_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "public"
            output.mkdir()
            subject._write_bundle_metadata(output, [{"id": "old"}], {"old": True})
            sentinel = output / "sentinel"
            sentinel.write_text("keep", encoding="utf-8")
            old_manifest = (output / "manifest.jsonl").read_bytes()

            with mock.patch.object(
                subject, "prepare_ascend", return_value=([], {"downloaded": 0})
            ), mock.patch.object(
                subject, "prepare_fleurs_language", side_effect=ValueError("network failed")
            ), self.assertRaisesRegex(ValueError, "network failed"):
                subject.prepare_public_corpora(output, 0, 1, overwrite=True)

            self.assertEqual("keep", sentinel.read_text(encoding="utf-8"))
            self.assertEqual(old_manifest, (output / "manifest.jsonl").read_bytes())
            self.assertEqual([], list(root.glob(".public-staging-*")))

    def test_overwrite_refuses_unmanaged_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "unmanaged"
            output.mkdir()
            (output / "important.txt").write_text("keep", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "unmanaged"):
                subject.prepare_public_corpora(output, 0, 0, overwrite=True)
            self.assertEqual("keep", (output / "important.txt").read_text(encoding="utf-8"))

    def test_manifest_and_provenance_are_byte_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first"
            second = root / "second"
            first.mkdir()
            second.mkdir()
            entries = [{"id": "一", "language": "zh"}, {"id": "two", "language": "en"}]
            provenance = {"ascend": {"revision": "fixed"}, "fleurs": {}}
            subject._write_bundle_metadata(first, entries, provenance)
            subject._write_bundle_metadata(second, entries, provenance)
            self.assertEqual(
                (first / "manifest.jsonl").read_bytes(),
                (second / "manifest.jsonl").read_bytes(),
            )
            self.assertEqual(
                (first / "provenance.json").read_bytes(),
                (second / "provenance.json").read_bytes(),
            )


if __name__ == "__main__":
    unittest.main()

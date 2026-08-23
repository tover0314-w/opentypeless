from __future__ import annotations

import argparse
import json
import math
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from benchmarks.offline_asr import generate_macos_tts as generator


class CorpusSafetyTests(unittest.TestCase):
    def corpus_case(self, case_id: str) -> dict:
        return {
            "id": case_id,
            "language": "en",
            "category": "general",
            "text": "A deterministic test.",
            "hotwords": [],
            "noise_probe": False,
        }

    def test_rejects_ids_that_could_escape_output_directory(self) -> None:
        for case_id in ("../escape", "/tmp/escape", "nested/name", "..", ".hidden", ""):
            with self.subTest(case_id=case_id), tempfile.TemporaryDirectory() as temp_dir:
                corpus = Path(temp_dir) / "corpus.jsonl"
                corpus.write_text(json.dumps(self.corpus_case(case_id)) + "\n", encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "unsafe id"):
                    generator.load_corpus(corpus)

    def test_safe_output_path_rejects_traversal_and_symlink_escape(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "out"
            root.mkdir()
            self.assertEqual(generator.safe_output_path(root, "case__clean.wav").parent, root.resolve())
            with self.assertRaises(ValueError):
                generator.safe_output_path(root, "../escape.wav")

            outside = Path(temp_dir) / "outside.wav"
            outside.write_bytes(b"outside")
            (root / "linked.wav").symlink_to(outside)
            with self.assertRaises(ValueError):
                generator.safe_output_path(root, "linked.wav")

    def test_non_empty_output_requires_explicit_overwrite(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "out"
            output.mkdir()
            (output / "unrelated.txt").write_text("present", encoding="utf-8")
            with self.assertRaises(FileExistsError):
                generator.prepare_output_dir(output, overwrite=False)
            generator.prepare_output_dir(output, overwrite=True)


class NoisePolicyTests(unittest.TestCase):
    def test_add_noise_alias_has_explicit_paired_control_semantics(self) -> None:
        args = generator.parse_args(["--output-dir", "/tmp/out", "--add-noise"])
        self.assertEqual(args.noise_policy, "probes-and-controls")

    def test_probes_and_controls_pairs_both_groups(self) -> None:
        probe = {"noise_probe": True, "category": "general"}
        control = {"noise_probe": False, "category": "hotword_control"}
        ordinary = {"noise_probe": False, "category": "general"}
        self.assertTrue(generator.should_add_noise(probe, "probes-and-controls"))
        self.assertTrue(generator.should_add_noise(control, "probes-and-controls"))
        self.assertFalse(generator.should_add_noise(ordinary, "probes-and-controls"))
        self.assertTrue(generator.should_add_noise(ordinary, "all"))


class DeterministicPinkNoiseTests(unittest.TestCase):
    @staticmethod
    def write_clean_fixture(path: Path) -> None:
        samples = [
            round(11000 * math.sin(2 * math.pi * 440 * sample_index / 16000))
            for sample_index in range(16000)
        ]
        generator.write_pcm16_mono(path, samples, 16000)

    def test_same_configuration_produces_byte_identical_wav(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            clean = root / "clean.wav"
            first = root / "first.wav"
            second = root / "second.wav"
            self.write_clean_fixture(clean)
            seed = generator.derive_noise_seed(1234, "en_general_01", "Samantha")

            first_details = generator.add_pink_noise(
                clean,
                first,
                seed=seed,
                target_snr_db=12.5,
                overwrite=False,
            )
            second_details = generator.add_pink_noise(
                clean,
                second,
                seed=seed,
                target_snr_db=12.5,
                overwrite=False,
            )

            self.assertEqual(first_details, second_details)
            self.assertEqual(generator.file_sha256(first), generator.file_sha256(second))
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(first_details["seed"], seed)
            self.assertEqual(first_details["target_snr_db"], 12.5)
            self.assertAlmostEqual(first_details["actual_snr_db"], 12.5, delta=0.01)

    def test_each_sample_seed_is_stable_and_independent(self) -> None:
        first = generator.derive_noise_seed(1234, "case-a", "Samantha")
        repeated = generator.derive_noise_seed(1234, "case-a", "Samantha")
        other_case = generator.derive_noise_seed(1234, "case-b", "Samantha")
        other_voice = generator.derive_noise_seed(1234, "case-a", "Daniel")
        self.assertEqual(first, repeated)
        self.assertEqual(len({first, other_case, other_voice}), 3)

    def test_manifest_records_hash_generation_and_noise_parameters(self) -> None:
        case = {
            "id": "en_general_01",
            "language": "en",
            "category": "general",
            "text": "A deterministic test.",
            "hotwords": [],
            "noise_probe": True,
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            clean = root / "clean.wav"
            noisy = root / "noisy.wav"
            self.write_clean_fixture(clean)
            noise = generator.add_pink_noise(
                clean,
                noisy,
                seed=99,
                target_snr_db=15.0,
                overwrite=False,
            )
            entry = generator.manifest_entry(
                case,
                "Samantha",
                "pink_noise",
                noisy.name,
                noisy,
                rate=185,
                noise=noise,
            )

            self.assertEqual(entry["audio_metadata"]["sha256"], generator.file_sha256(noisy))
            self.assertEqual(entry["audio_metadata"]["sample_rate_hz"], 16000)
            self.assertEqual(entry["generation"]["tts"]["rate_wpm"], 185)
            self.assertEqual(entry["generation"]["noise"]["algorithm"], generator.PINK_NOISE_ALGORITHM)
            self.assertIn("actual_snr_db", entry["generation"]["noise"])


class ProvenanceTests(unittest.TestCase):
    def test_provenance_records_parameters_tool_and_platform_versions(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            corpus = Path(temp_dir) / "corpus.jsonl"
            corpus.write_text("{}\n", encoding="utf-8")
            args = argparse.Namespace(
                voice_set="primary",
                rate=185,
                noise_policy="probes-and-controls",
                noise_seed=1234,
                noise_snr_db=15.0,
            )
            with mock.patch.object(generator.shutil, "which", side_effect=["/usr/bin/say", "/usr/bin/ffmpeg"]), mock.patch.object(
                generator, "command_first_line", return_value="ffmpeg version test"
            ):
                provenance = generator.collect_provenance(args, corpus, 7)

            self.assertEqual(provenance["parameters"]["noise_seed"], 1234)
            self.assertEqual(provenance["parameters"]["entry_count"], 7)
            self.assertIn("version", provenance["tools"]["say"])
            self.assertEqual(provenance["tools"]["ffmpeg"]["version"], "ffmpeg version test")
            self.assertIn("release", provenance["platform"])


if __name__ == "__main__":
    unittest.main()

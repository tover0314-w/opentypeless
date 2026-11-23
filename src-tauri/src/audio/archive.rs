use std::io::Write;
use std::path::{Path, PathBuf};

const MAX_PCM_BYTES: usize = 24 * 1024 * 1024;
pub(crate) const MAX_ARCHIVE_BYTES: usize = MAX_PCM_BYTES + 44;

pub fn decode_archive_wav(wav: &[u8]) -> Result<&[u8], String> {
    if wav.len() < 44 || wav.len() > MAX_ARCHIVE_BYTES {
        return Err("Invalid recording size".to_string());
    }
    let data_length = (wav.len() - 44) as u32;
    let mut expected_header =
        crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[], 16000);
    expected_header[4..8].copy_from_slice(&(36 + data_length).to_le_bytes());
    expected_header[40..44].copy_from_slice(&data_length.to_le_bytes());
    if wav[..44] != expected_header[..44] || !(wav.len() - 44).is_multiple_of(2) {
        return Err("Unsupported or incomplete recording format".to_string());
    }
    Ok(&wav[44..])
}

pub fn should_save_failed_recording(cancelled: bool, expected: u64, current: u64) -> bool {
    !cancelled && (current == expected || current == 0)
}

#[derive(Default)]
pub struct ArchiveBuffer {
    pcm: Vec<u8>,
    overflowed: bool,
}

impl ArchiveBuffer {
    pub fn append(&mut self, chunk: &[u8]) {
        if self.overflowed {
            return;
        }
        if self.pcm.len() + chunk.len() > MAX_PCM_BYTES {
            self.pcm.clear();
            self.overflowed = true;
            return;
        }
        self.pcm.extend_from_slice(chunk);
    }

    pub fn take_wav(&mut self) -> Option<Vec<u8>> {
        if self.overflowed || self.pcm.is_empty() {
            return None;
        }
        Some(
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(
                &std::mem::take(&mut self.pcm),
                16000,
            ),
        )
    }
}

pub fn export_wav(directory: &Path, id: i64, audio: &[u8]) -> std::io::Result<PathBuf> {
    export_wav_with_sync(directory, id, audio, std::fs::File::sync_all)
}

fn export_wav_with_sync(
    directory: &Path,
    id: i64,
    audio: &[u8],
    sync: impl FnOnce(&std::fs::File) -> std::io::Result<()>,
) -> std::io::Result<PathBuf> {
    decode_archive_wav(audio)
        .map_err(|error| std::io::Error::new(std::io::ErrorKind::InvalidData, error))?;
    std::fs::create_dir_all(directory)?;
    let name = format!("OpenTypeless-recording-{id}-{}.wav", uuid::Uuid::new_v4());
    let path = directory.join(name);
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&path)?;
    if let Err(error) = file.write_all(audio).and_then(|()| sync(&file)) {
        drop(file);
        let _ = std::fs::remove_file(&path);
        return Err(error);
    }
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn export_sync_failure_is_reported_and_removes_only_the_new_file() {
        let directory =
            std::env::temp_dir().join(format!("otl-export-sync-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let existing = directory.join("existing.wav");
        std::fs::write(&existing, b"preserved").unwrap();
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let mut sync_called = false;
        let result = export_wav_with_sync(&directory, 1, &audio, |_| {
            sync_called = true;
            Err(std::io::Error::other("synthetic sync failure"))
        });
        let remaining = std::fs::read_dir(&directory).unwrap().count();
        let preserved = std::fs::read(&existing).unwrap();
        std::fs::remove_dir_all(&directory).unwrap();
        assert!(sync_called);
        assert_eq!(result.unwrap_err().to_string(), "synthetic sync failure");
        assert_eq!(remaining, 1);
        assert_eq!(preserved, b"preserved");
    }

    #[test]
    fn archive_validation_does_not_allocate_a_second_pcm_buffer() {
        let source = include_str!("archive.rs");
        let decoder = source
            .split("pub fn decode_archive_wav(")
            .nth(1)
            .unwrap()
            .split("pub fn should_save_failed_recording(")
            .next()
            .unwrap();
        assert!(!decoder.contains("vec![0;"));
        assert!(decoder.contains("build_wav(&[], 16000)"));
    }

    #[test]
    fn decodes_only_complete_archive_wav_payloads() {
        let wav =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        assert_eq!(decode_archive_wav(&wav).unwrap(), &[1, 0, 2, 0]);
        assert!(decode_archive_wav(&wav[..wav.len() - 1]).is_err());
        let mut wrong_rate = wav.clone();
        wrong_rate[24..28].copy_from_slice(&44100_u32.to_le_bytes());
        assert!(decode_archive_wav(&wrong_rate).is_err());
        assert!(decode_archive_wav(b"not audio").is_err());
    }

    #[test]
    fn archive_header_validation_preserves_payload_and_rejects_every_header_mutation() {
        for length in [0, 2, 16, 256, 4096] {
            let pcm = vec![7; length];
            let wav = crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&pcm, 16000);
            let decoded = decode_archive_wav(&wav).unwrap();
            assert_eq!(decoded, pcm);
            assert_eq!(decoded.as_ptr(), wav[44..].as_ptr());
            for offset in 0..44 {
                let mut invalid = wav.clone();
                invalid[offset] ^= 1;
                assert!(decode_archive_wav(&invalid).is_err());
            }
        }
    }

    #[test]
    fn cancelled_or_superseded_recordings_are_not_saved_as_failures() {
        assert!(should_save_failed_recording(false, 7, 7));
        assert!(!should_save_failed_recording(true, 7, 7));
        assert!(!should_save_failed_recording(false, 7, 8));
    }

    #[test]
    fn builds_a_wav_once_and_does_not_export_truncated_audio() {
        let mut buffer = ArchiveBuffer::default();
        assert!(buffer.take_wav().is_none());
        buffer.append(&[1, 0, 2, 0]);
        let wav = buffer.take_wav().unwrap();
        assert_eq!(&wav[..4], b"RIFF");
        assert_eq!(&wav[44..], &[1, 0, 2, 0]);
        assert!(buffer.take_wav().is_none());
        buffer.append(&vec![0; MAX_PCM_BYTES + 1]);
        buffer.append(&[0, 0]);
        assert!(buffer.take_wav().is_none());
    }

    #[test]
    fn exports_to_unique_paths_without_overwriting() {
        let dir =
            std::env::temp_dir().join(format!("opentypeless-export-{}", uuid::Uuid::new_v4()));
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let first = export_wav(&dir, 1, &audio).unwrap();
        let second = export_wav(&dir, 1, &audio).unwrap();
        assert_ne!(first, second);
        assert_eq!(std::fs::read(first).unwrap(), audio);
        assert_eq!(std::fs::read(second).unwrap(), audio);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn invalid_recording_export_does_not_create_a_directory_or_file() {
        let directory = std::env::temp_dir().join(format!(
            "opentypeless-invalid-export-{}",
            uuid::Uuid::new_v4()
        ));
        let result = export_wav(&directory, 1, b"synthetic invalid audio");
        let created_directory = directory.exists();
        if created_directory {
            std::fs::remove_dir_all(&directory).unwrap();
        }
        assert_eq!(result.unwrap_err().kind(), std::io::ErrorKind::InvalidData);
        assert!(!created_directory);
    }
}

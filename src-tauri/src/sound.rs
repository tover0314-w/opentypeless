//! One-shot notification sound played on successful transcription completion.

use std::io::Cursor;

/// The completion chime (freedesktop "bell"), embedded so it ships with the
/// binary and needs no runtime file or external player.
const COMPLETE_SOUND: &[u8] = include_bytes!("../sounds/complete.wav");

/// Play the completion sound on a detached thread. Best-effort: any audio
/// failure (no output device, busy device, decode error) is logged at debug and
/// ignored so it can never affect or block the transcription pipeline.
pub fn play_completion() {
    std::thread::spawn(|| {
        if let Err(e) = play_blocking() {
            tracing::debug!("Completion sound skipped: {e}");
        }
    });
}

fn play_blocking() -> Result<(), Box<dyn std::error::Error>> {
    use rodio::{Decoder, OutputStream, Sink};
    // The stream handle must outlive playback, so keep it on this thread until
    // the sink drains.
    let (_stream, handle) = OutputStream::try_default()?;
    let sink = Sink::try_new(&handle)?;
    sink.append(Decoder::new(Cursor::new(COMPLETE_SOUND))?);
    // The chime is ~0.14s. Wait a bounded time for it to drain rather than
    // `sink.sleep_until_end()`, which can block indefinitely on a misbehaving
    // output device and leak this thread.
    std::thread::sleep(std::time::Duration::from_millis(700));
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Manual smoke test: decodes and plays the embedded chime through the
    /// default output device. Skipped unless OT_AUDIO_TEST is set, since it
    /// needs real audio hardware (would be flaky in CI). Run with:
    ///   OT_AUDIO_TEST=1 cargo test sound::tests::plays_embedded_chime -- --nocapture
    #[test]
    fn plays_embedded_chime() {
        if std::env::var("OT_AUDIO_TEST").is_err() {
            return;
        }
        play_blocking().expect("completion sound should play");
    }
}

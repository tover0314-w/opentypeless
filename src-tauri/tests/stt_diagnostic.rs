use std::time::Instant;

/// Test the SiliconFlow ASR API exactly like the app does.
/// Requires your SiliconFlow API key.
///
/// Usage:
///   SILICONFLOW_KEY=sk-xxx cargo test --test stt_diagnostic -- --nocapture
///
/// To test with a real audio file from the app's recordings:
///   SILICONFLOW_KEY=sk-xxx TEST_AUDIO=/path/to/recording.wav cargo test --test stt_diagnostic -- --nocapture
#[tokio::test]
async fn test_siliconflow_api() {
    let api_key = std::env::var("SILICONFLOW_KEY").expect("Set SILICONFLOW_KEY env var");
    let endpoint = "https://api.siliconflow.cn/v1/audio/transcriptions";
    let model = "FunAudioLLM/SenseVoiceSmall";
    let sample_rate: u32 = 16000;

    let pcm_data: Vec<u8>;

    // Check if a real audio file was provided
    if let Ok(path) = std::env::var("TEST_AUDIO") {
        println!("Reading audio file: {}", path);
        let raw = std::fs::read(&path).expect("read audio file");
        // Strip WAV header (44 bytes) if it's a WAV file
        if path.ends_with(".wav") && raw.len() > 44 && &raw[..4] == b"RIFF" {
            pcm_data = raw[44..].to_vec();
            println!("Stripped WAV header, PCM data: {} bytes ({:.1}s at 16kHz 16bit)",
                pcm_data.len(), pcm_data.len() as f64 / (sample_rate as f64 * 2.0));
        } else {
            pcm_data = raw;
        }
    } else {
        // Generate a 3-second 440Hz sine wave (non-speech but valid audio)
        let duration_secs: u32 = 3;
        let num_samples = (sample_rate * duration_secs) as usize;
        let mut pcm = Vec::with_capacity(num_samples * 2);
        for i in 0..num_samples {
            let t = i as f32 / sample_rate as f32;
            let sample = (t * 440.0 * 2.0 * std::f32::consts::PI).sin() * 0.3;
            let clamped = (sample * 32767.0).clamp(-32768.0, 32767.0) as i16;
            pcm.extend_from_slice(&clamped.to_le_bytes());
        }
        pcm_data = pcm;
        println!("Generated 440Hz sine wave: {} bytes ({:.1}s)", pcm_data.len(), duration_secs);
    }

    // STEP 1: connectivity check (like test_stt_connection does)
    println!("\n--- Step 1: Connectivity check (0.1s silence) ---");
    let silent_pcm = vec![0u8; 3200]; // 0.1s at 16kHz 16-bit mono
    let silent_wav = opentypeless_lib::stt::whisper_compat::WhisperCompatProvider::build_wav(&silent_pcm, sample_rate);
    let silent_file = reqwest::multipart::Part::bytes(silent_wav)
        .file_name("test.wav")
        .mime_str("audio/wav").unwrap();
    let silent_form = reqwest::multipart::Form::new()
        .text("model", model)
        .part("file", silent_file);

    let client = reqwest::Client::new();
    let resp = client
        .post(endpoint)
        .header("Authorization", format!("Bearer {}", api_key))
        .multipart(silent_form)
        .timeout(std::time::Duration::from_secs(15))
        .send()
        .await
        .expect("HTTP request failed");
    println!("Connectivity check: HTTP {}", resp.status());
    if resp.status().is_success() {
        println!("=> API key and endpoint OK");
    } else {
        let body = resp.text().await.unwrap_or_default();
        println!("=> FAILED! Response: {}", &body[..body.len().min(200)]);
        panic!("API connectivity check failed");
    }

    // STEP 2: real transcription test with the actual audio
    println!("\n--- Step 2: Transcription test ({:.1}s audio) ---",
        pcm_data.len() as f64 / (sample_rate as f64 * 2.0));
    let wav_data = opentypeless_lib::stt::whisper_compat::WhisperCompatProvider::build_wav(&pcm_data, sample_rate);
    println!("WAV file: {} bytes", wav_data.len());

    let file_part = reqwest::multipart::Part::bytes(wav_data)
        .file_name("audio.wav")
        .mime_str("audio/wav").unwrap();

    let form = reqwest::multipart::Form::new()
        .text("model", model.to_string())
        .part("file", file_part);

    let t0 = Instant::now();
    let resp = client
        .post(endpoint)
        .header("Authorization", format!("Bearer {}", api_key))
        .multipart(form)
        .timeout(std::time::Duration::from_secs(60))
        .send()
        .await
        .expect("HTTP request failed");

    let elapsed = t0.elapsed();
    let status = resp.status();
    let body = resp.text().await.expect("read response body");

    println!("\n=== SiliconFlow API Response ===");
    println!("HTTP Status: {} ({})", status.as_u16(), status.as_str());
    println!("Response time: {}ms", elapsed.as_millis());
    println!("Raw response body:\n{}", body);
    println!("==============================\n");

    // Parse and analyze
    if let Ok(v) = serde_json::from_str::<serde_json::Value>(&body) {
        println!("JSON keys: {:?}", v.as_object().map(|o| o.keys().collect::<Vec<_>>()));
        println!("Full JSON: {}", serde_json::to_string_pretty(&v).unwrap());

        match v["text"].as_str() {
            Some(text) if !text.trim().is_empty() => {
                println!("\n>>> SUCCESS: Transcribed {} chars", text.len());
            }
            Some(text) => {
                println!("\n>>> WARNING: text field is empty! (value: {:?})", text);
                println!(">>> This matches the bug pattern. Check audio quality / format.");
                println!(">>> If using generated sine wave, this is expected (no speech in audio).");
            }
            None => {
                println!("\n>>> WARNING: No 'text' field in response!");
                println!(">>> The app parses v[\"text\"] which would return None -> empty -> 'No speech detected'");
            }
        }
    } else {
        println!("\n>>> WARNING: Response is NOT valid JSON!");
        println!(">>> Content-Type might be wrong, or API returned something unexpected.");
    }

    assert!(status.is_success(), "HTTP request failed: {}", status);
}

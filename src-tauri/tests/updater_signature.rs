use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::{json, Value};
use tauri_plugin_updater::{Error, Update, UpdaterExt};

const PUBLIC_KEY: &str = "untrusted comment: minisign public key: A49EF4746FA6E6D7\nRWTX5qZvdPSepDGWg2bz8n6ArQnBMwgQK1geVosorcbLzcunD+KJ+cYe";
const SIGNATURE: &str = "untrusted comment: signature from tauri secret key\nRUTX5qZvdPSepPF2zsjvP/L/TQtBLRnWRvaq+4omK1UEugtQp/USrGiI1v762sDy3id3aor7bRC9ETyeot6lMHHrWoZ5hjUM1Qc=\ntrusted comment: timestamp:1791649051\tfile:payload\tversion:2.0.0\nDDq2CLOEl5cQmC2eJRHlnZPVViOZsu1CjpPKQoei4A/XBfkDqGO0B+QMrcrHgEQ5qV3mne75tdFWhJpqUXh9AA==";

fn manifest(version: &str) -> Value {
    json!({
        "version": version,
        "platforms": {
            "test-target": {
                "url": "https://example.invalid/package",
                "signature": STANDARD.encode(SIGNATURE)
            }
        }
    })
}

fn restored_update(metadata: Value) -> Update {
    let mut context = tauri::test::mock_context(tauri::test::noop_assets());
    context.config_mut().plugins.0.insert(
        "updater".into(),
        json!({ "pubkey": STANDARD.encode(PUBLIC_KEY) }),
    );
    let app = tauri::test::mock_builder()
        .plugin(
            tauri_plugin_updater::Builder::new()
                .pubkey(STANDARD.encode(PUBLIC_KEY))
                .build(),
        )
        .build(context)
        .unwrap();
    app.updater_builder()
        .target("test-target")
        .executable_path("runtime-path/app")
        .no_proxy()
        .configure_client(|_| panic!("unexpected network request"))
        .endpoints(vec!["https://example.invalid/update".parse().unwrap()])
        .unwrap()
        .build()
        .unwrap()
        .restore_update(metadata)
        .unwrap()
        .unwrap()
}

#[test]
fn cached_update_accepts_the_public_signed_payload_and_rejects_tampering() {
    let update = restored_update(manifest("2.0.0"));
    update.verify(b"test").unwrap();
    assert!(matches!(
        update.verify(b"tampered"),
        Err(Error::Minisign(_))
    ));
}

#[test]
fn installation_rejects_tampered_bytes_before_platform_installation() {
    let update = restored_update(manifest("2.0.0"));
    assert!(matches!(
        update.install(b"tampered"),
        Err(Error::Minisign(_))
    ));
}

#[test]
fn cached_metadata_cannot_inflate_the_version_of_a_signed_artifact() {
    let update = restored_update(manifest("9.9.9"));
    assert!(matches!(
        update.verify(b"test"),
        Err(Error::SignedVersionMismatch { .. })
    ));
}

#[test]
fn cached_metadata_cannot_override_the_runtime_trust_key() {
    let mut metadata = manifest("2.0.0");
    metadata["config"] = json!({ "pubkey": "untrusted-key" });
    metadata["extract_path"] = json!("untrusted-path");
    let update = restored_update(metadata);
    update.verify(b"test").unwrap();
}

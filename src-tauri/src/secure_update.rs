use serde::Serialize;
use tauri::Manager;
use tauri_plugin_updater::UpdaterExt;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UpdateMetadata {
    rid: tauri::ResourceId,
    current_version: String,
    version: String,
    date: Option<String>,
    body: Option<String>,
    raw_json: serde_json::Value,
}

fn build_secure_updater<R: tauri::Runtime>(
    window: &tauri::WebviewWindow<R>,
) -> Result<tauri_plugin_updater::Updater, String> {
    window
        .updater_builder()
        .version_comparator(|current, release| release.version > current)
        .timeout(std::time::Duration::from_secs(30))
        .configure_client(|client| client.https_only(true))
        .build()
        .map_err(|_| "Update check configuration unavailable".to_string())
}

#[tauri::command]
pub(crate) async fn check_secure_update<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
) -> Result<Option<UpdateMetadata>, String> {
    if window.label() != "main" {
        return Err("Update check requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)?;
    let updater = build_secure_updater(&window)?;
    let Some(update) = updater
        .check()
        .await
        .map_err(|_| "Update network check failed".to_string())?
    else {
        return Ok(None);
    };
    register_update(&window, update).map(Some)
}

fn register_update<R: tauri::Runtime>(
    window: &tauri::WebviewWindow<R>,
    update: tauri_plugin_updater::Update,
) -> Result<UpdateMetadata, String> {
    let release = serde_json::from_value(update.raw_json.clone())
        .map_err(|_| "Invalid update metadata".to_string())?;
    if !crate::updater_release_urls_secure(&release)
        || update.download_url.scheme() != "https"
        || update.download_url.host_str().is_none()
        || !update.download_url.username().is_empty()
        || update.download_url.password().is_some()
        || update.download_url.fragment().is_some()
    {
        return Err("Update signature policy rejected an insecure download URL".into());
    }
    crate::update_install_request::ensure_local_window(window)?;
    let metadata = UpdateMetadata {
        rid: 0,
        current_version: update.current_version.clone(),
        version: update.version.clone(),
        date: update.raw_json["pub_date"].as_str().map(str::to_owned),
        body: update.body.clone(),
        raw_json: update.raw_json.clone(),
    };
    let rid = window.resources_table().add(update);
    Ok(UpdateMetadata { rid, ..metadata })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(unix)]
    #[tokio::test]
    async fn tls_download_reaches_payload_but_never_follows_http_downgrade() {
        use base64::Engine;
        use std::io::{Read, Write};
        use std::sync::Arc;
        use std::time::{Duration, Instant};

        let directory =
            std::env::temp_dir().join(format!("opentypeless-update-tls-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&directory).unwrap();
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&directory, std::fs::Permissions::from_mode(0o700)).unwrap();
        }
        struct FixtureDirectory(std::path::PathBuf);
        impl Drop for FixtureDirectory {
            fn drop(&mut self) {
                let _ = std::fs::remove_dir_all(&self.0);
            }
        }
        let _cleanup = FixtureDirectory(directory.clone());
        std::fs::write(
            directory.join("certificate.cnf"),
            "[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=localhost\n[ext]\nsubjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n",
        )
        .unwrap();
        for arguments in [
            vec![
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-keyout",
                "key.pem",
                "-out",
                "certificate.pem",
                "-days",
                "1",
                "-config",
                "certificate.cnf",
            ],
            vec![
                "x509",
                "-in",
                "certificate.pem",
                "-outform",
                "DER",
                "-out",
                "certificate.der",
            ],
            vec![
                "pkcs8", "-topk8", "-nocrypt", "-in", "key.pem", "-outform", "DER", "-out",
                "key.der",
            ],
        ] {
            let output = std::process::Command::new("openssl")
                .args(arguments)
                .current_dir(&directory)
                .output()
                .unwrap();
            assert!(
                output.status.success(),
                "Temporary TLS fixture generation failed"
            );
        }
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let certificate = rustls::pki_types::CertificateDer::from(
            std::fs::read(directory.join("certificate.der")).unwrap(),
        );
        let key =
            rustls::pki_types::PrivateKeyDer::Pkcs8(rustls::pki_types::PrivatePkcs8KeyDer::from(
                std::fs::read(directory.join("key.der")).unwrap(),
            ));
        let server_config = Arc::new(
            rustls::ServerConfig::builder_with_provider(provider.clone())
                .with_safe_default_protocol_versions()
                .unwrap()
                .with_no_client_auth()
                .with_single_cert(vec![certificate.clone()], key)
                .unwrap(),
        );
        let mut roots = rustls::RootCertStore::empty();
        roots.add(certificate).unwrap();
        let client_config = rustls::ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_root_certificates(roots)
            .with_no_client_auth();

        let encode = |value: &str| base64::engine::general_purpose::STANDARD.encode(value);
        let public_key = "untrusted comment: minisign public key: A49EF4746FA6E6D7\nRWTX5qZvdPSepDGWg2bz8n6ArQnBMwgQK1geVosorcbLzcunD+KJ+cYe";
        let signature = "untrusted comment: signature from tauri secret key\nRUTX5qZvdPSepPF2zsjvP/L/TQtBLRnWRvaq+4omK1UEugtQp/USrGiI1v762sDy3id3aor7bRC9ETyeot6lMHHrWoZ5hjUM1Qc=\ntrusted comment: timestamp:1791649051\tfile:payload\tversion:2.0.0\nDDq2CLOEl5cQmC2eJRHlnZPVViOZsu1CjpPKQoei4A/XBfkDqGO0B+QMrcrHgEQ5qV3mne75tdFWhJpqUXh9AA==";
        let mut context = tauri::test::mock_context(tauri::test::noop_assets());
        context.config_mut().plugins.0.insert(
            "updater".into(),
            serde_json::json!({
                "pubkey": encode(public_key),
                "endpoints": ["https://example.invalid/latest.json"]
            }),
        );
        let app = tauri::test::mock_builder()
            .plugin(crate::secure_updater_plugin())
            .build(context)
            .unwrap();

        for scenario in ["valid", "tampered", "redirect"] {
            let http = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            http.set_nonblocking(true).unwrap();
            let tls = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            tls.set_nonblocking(true).unwrap();
            let tls_address = tls.local_addr().unwrap();
            let http_address = http.local_addr().unwrap();
            let config = server_config.clone();
            let server = std::thread::spawn(move || {
                let deadline = Instant::now() + Duration::from_secs(5);
                let socket = loop {
                    match tls.accept() {
                        Ok((socket, _)) => break socket,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            assert!(Instant::now() < deadline, "TLS client did not connect");
                            std::thread::sleep(Duration::from_millis(10));
                        }
                        Err(error) => panic!("{error}"),
                    }
                };
                socket.set_nonblocking(false).unwrap();
                socket
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                socket
                    .set_write_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                let connection = rustls::ServerConnection::new(config).unwrap();
                let mut stream = rustls::StreamOwned::new(connection, socket);
                let mut request = Vec::new();
                while !request.ends_with(b"\r\n\r\n") {
                    let mut byte = [0_u8; 1];
                    stream.read_exact(&mut byte).unwrap();
                    request.push(byte[0]);
                    assert!(request.len() <= 8192);
                }
                assert!(request.starts_with(b"GET /update "));
                let response = if scenario == "redirect" {
                    format!("HTTP/1.1 302 Found\r\nLocation: http://{http_address}/downgrade\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                } else {
                    let payload = if scenario == "valid" { "test" } else { "fake" };
                    format!("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\n{payload}")
                };
                stream.write_all(response.as_bytes()).unwrap();
                stream.flush().unwrap();
            });
            let client_tls = client_config.clone();
            let updater = app
                .updater_builder()
                .target("synthetic-target")
                .timeout(Duration::from_secs(5))
                .configure_client(move |client| {
                    client
                        .https_only(true)
                        .no_proxy()
                        .use_preconfigured_tls(client_tls.clone())
                })
                .build()
                .unwrap();
            let update = updater
                .restore_update(serde_json::json!({
                    "version": "2.0.0",
                    "url": format!("https://{tls_address}/update"),
                    "signature": encode(signature)
                }))
                .unwrap()
                .unwrap();
            let chunks = std::cell::Cell::new(0);
            let finished = std::cell::Cell::new(false);
            let result = tokio::time::timeout(
                Duration::from_secs(8),
                update.download(|_, _| chunks.set(chunks.get() + 1), || finished.set(true)),
            )
            .await;
            server.join().unwrap();
            let result = result.unwrap();
            if scenario == "redirect" {
                assert!(matches!(
                    result,
                    Err(tauri_plugin_updater::Error::Reqwest(_))
                ));
                assert_eq!(chunks.get(), 0);
                assert!(!finished.get());
            } else {
                if scenario == "valid" {
                    assert_eq!(result.unwrap(), b"test");
                } else {
                    assert!(matches!(
                        result,
                        Err(tauri_plugin_updater::Error::Minisign(_))
                    ));
                }
                assert!(chunks.get() > 0);
                assert!(finished.get());
            }
            assert_eq!(
                http.accept().unwrap_err().kind(),
                std::io::ErrorKind::WouldBlock
            );
        }
    }

    #[test]
    fn update_resource_handoff_preserves_metadata_and_rejects_actual_insecure_url() {
        let mut context = tauri::test::mock_context(tauri::test::noop_assets());
        context.config_mut().plugins.0.insert(
            "updater".into(),
            serde_json::json!({
                "pubkey": "synthetic-unused-key",
                "endpoints": ["https://example.invalid/latest.json"]
            }),
        );
        let app = tauri::test::mock_builder()
            .plugin(crate::secure_updater_plugin())
            .build(context)
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let updater = build_secure_updater(&window).unwrap();
        let manifest = serde_json::json!({
            "version": "99.0.0", "url": "https://example.invalid/update",
            "signature": "synthetic", "notes": "Synthetic release notes"
        });
        let update = updater.restore_update(manifest.clone()).unwrap().unwrap();
        let metadata = register_update(&window, update).unwrap();
        let retained = window
            .resources_table()
            .get::<tauri_plugin_updater::Update>(metadata.rid)
            .unwrap();
        assert_eq!(retained.version, metadata.version);
        assert_eq!(metadata.raw_json, manifest);
        assert_eq!(metadata.body.as_deref(), Some("Synthetic release notes"));
        window.resources_table().close(metadata.rid).unwrap();
        assert!(!window.resources_table().has(metadata.rid));
        let mut unsafe_update = updater.restore_update(manifest).unwrap().unwrap();
        unsafe_update.download_url = "http://example.invalid/update".parse().unwrap();
        assert!(register_update(&window, unsafe_update).is_err());
    }

    #[tokio::test]
    async fn actual_download_refuses_http_before_connecting_or_reporting_progress() {
        if rustls::crypto::CryptoProvider::get_default().is_none() {
            let _ = rustls::crypto::ring::default_provider().install_default();
        }
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let mut context = tauri::test::mock_context(tauri::test::noop_assets());
        context.config_mut().plugins.0.insert(
            "updater".into(),
            serde_json::json!({
                "pubkey": "synthetic-unused-key",
                "endpoints": ["https://example.invalid/latest.json"]
            }),
        );
        let app = tauri::test::mock_builder()
            .plugin(crate::secure_updater_plugin())
            .build(context)
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let updater = build_secure_updater(&window).unwrap();
        let mut update = updater
            .restore_update(serde_json::json!({
                "version": "99.0.0", "url": "https://example.invalid/update",
                "signature": "synthetic-unused-signature"
            }))
            .unwrap()
            .unwrap();
        update.download_url = format!("http://{}/update", listener.local_addr().unwrap())
            .parse()
            .unwrap();
        update.timeout = Some(std::time::Duration::from_secs(2));
        let chunks = std::cell::Cell::new(0);
        let finished = std::cell::Cell::new(false);
        let outcome = tokio::time::timeout(
            std::time::Duration::from_secs(5),
            update.download(|_, _| chunks.set(chunks.get() + 1), || finished.set(true)),
        )
        .await
        .unwrap();
        assert!(
            matches!(outcome, Err(tauri_plugin_updater::Error::Reqwest(error)) if error.is_builder())
        );
        assert_eq!(chunks.get(), 0);
        assert!(!finished.get());
        assert_eq!(
            listener.accept().unwrap_err().kind(),
            std::io::ErrorKind::WouldBlock
        );
    }

    #[tokio::test]
    async fn secure_check_refuses_auxiliary_and_remote_windows_before_updater_access() {
        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let auxiliary = tauri::WebviewWindowBuilder::new(&app, "ask", Default::default())
            .build()
            .unwrap();
        assert!(check_secure_update(auxiliary)
            .await
            .unwrap_err()
            .contains("main window"));
        let remote = tauri::WebviewWindowBuilder::new(
            &app,
            "main",
            tauri::WebviewUrl::External("https://example.invalid/".parse().unwrap()),
        )
        .build()
        .unwrap();
        assert!(check_secure_update(remote)
            .await
            .unwrap_err()
            .contains("local application content"));
    }
}

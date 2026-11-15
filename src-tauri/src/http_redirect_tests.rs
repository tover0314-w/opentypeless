use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::time::{Duration, Instant};

fn read_request(socket: &mut TcpStream) -> String {
    socket
        .set_read_timeout(Some(Duration::from_secs(5)))
        .unwrap();
    let mut request = Vec::new();
    let mut buffer = [0_u8; 4096];
    loop {
        let count = socket.read(&mut buffer).unwrap_or_else(|error| {
            let header_end = request.windows(4).position(|bytes| bytes == b"\r\n\r\n");
            panic!(
                "redirect fixture request read failed: kind={:?}, received_bytes={}, header_end={header_end:?}",
                error.kind(),
                request.len()
            );
        });
        assert!(count > 0, "request ended before its declared body");
        request.extend_from_slice(&buffer[..count]);
        if let Some(header_end) = request.windows(4).position(|bytes| bytes == b"\r\n\r\n") {
            let headers = String::from_utf8_lossy(&request[..header_end]);
            let content_length = headers
                .lines()
                .filter_map(|line| line.split_once(':'))
                .find(|(name, _)| name.eq_ignore_ascii_case("content-length"))
                .map(|(_, value)| value.trim().parse::<usize>().unwrap())
                .unwrap_or(0);
            if request.len() >= header_end + 4 + content_length {
                return String::from_utf8(request).unwrap();
            }
        }
    }
}

fn capture_destination(listener: TcpListener) -> Option<String> {
    listener.set_nonblocking(true).unwrap();
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        match listener.accept() {
            Ok((mut socket, _)) => {
                let request = read_request(&mut socket);
                socket
                    .write_all(
                        b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}",
                    )
                    .unwrap();
                return Some(request);
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                if Instant::now() >= deadline {
                    return None;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
            Err(error) => panic!("destination accept failed: {error}"),
        }
    }
}

#[tokio::test]
async fn production_cross_origin_anthropic_redirects_do_not_forward_credentials_or_body() {
    for status in [307, 308] {
        let source = TcpListener::bind("127.0.0.1:0").unwrap();
        let source_address = source.local_addr().unwrap();
        let destination = TcpListener::bind("127.0.0.1:0").unwrap();
        let destination_address = destination.local_addr().unwrap();
        let destination_server = std::thread::spawn(move || capture_destination(destination));
        let source_server = std::thread::spawn(move || {
            let (mut socket, _) = source.accept().unwrap();
            let request = read_request(&mut socket);
            write!(
                    socket,
                    "HTTP/1.1 {status} Redirect\r\nLocation: http://{destination_address}/target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                )
                .unwrap();
            request
        });
        let client = crate::build_shared_http_client();
        let result = crate::llm::protocol::apply_auth_headers(
            client.post(format!("http://{source_address}/v1/messages")),
            "anthropic",
            "https://api.anthropic.com/v1",
            "synthetic-redirect-key",
        )
        .json(&serde_json::json!({"text": "synthetic-private-transcription"}))
        .timeout(Duration::from_secs(5))
        .send()
        .await;
        let original = source_server.join().unwrap();
        let forwarded = destination_server.join().unwrap();
        assert!(original.contains("synthetic-redirect-key"));
        assert!(original.contains("synthetic-private-transcription"));
        assert!(
            forwarded.is_none(),
            "cross-origin destination received a request"
        );
        assert_eq!(result.unwrap().status().as_u16(), status);
    }
}

#[tokio::test]
async fn production_cross_origin_bearer_redirects_do_not_forward_transcription_body() {
    for status in [307, 308] {
        let started_at = Instant::now();
        let source = TcpListener::bind("127.0.0.1:0").unwrap();
        let source_address = source.local_addr().unwrap();
        let destination = TcpListener::bind("127.0.0.1:0").unwrap();
        let destination_address = destination.local_addr().unwrap();
        let destination_server = std::thread::spawn(move || capture_destination(destination));
        let source_server = std::thread::spawn(move || {
            let (mut socket, _) = source.accept().unwrap();
            let original = read_request(&mut socket);
            write!(
                socket,
                "HTTP/1.1 {status} Redirect\r\nLocation: http://{destination_address}/target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            )
            .unwrap();
            original
        });
        let response = crate::llm::protocol::apply_auth_headers(
            crate::build_shared_http_client()
                .post(format!("http://{source_address}/v1/chat/completions")),
            "openai",
            &format!("http://{source_address}/v1"),
            "synthetic-bearer-key",
        )
        .json(&serde_json::json!({"text": "synthetic-private-transcription"}))
        .timeout(Duration::from_secs(5))
        .send()
        .await;
        eprintln!(
            "bearer redirect fixture status={status} elapsed_ms={} response={:?}",
            started_at.elapsed().as_millis(),
            response.as_ref().map(|result| result.status())
        );
        let original = source_server.join().unwrap();
        let forwarded = destination_server.join().unwrap();
        assert!(original.contains("Bearer synthetic-bearer-key"));
        assert!(
            forwarded.is_none(),
            "cross-origin destination received a request"
        );
        assert_eq!(response.unwrap().status().as_u16(), status);
    }
}

#[tokio::test]
async fn multipart_uploads_are_not_replayed_after_cross_origin_redirects() {
    for status in [307, 308] {
        let source = TcpListener::bind("127.0.0.1:0").unwrap();
        let source_address = source.local_addr().unwrap();
        let destination = TcpListener::bind("127.0.0.1:0").unwrap();
        let destination_address = destination.local_addr().unwrap();
        let destination_server = std::thread::spawn(move || capture_destination(destination));
        let source_server = std::thread::spawn(move || {
            let (mut socket, _) = source.accept().unwrap();
            let original = read_request(&mut socket);
            write!(
                socket,
                "HTTP/1.1 {status} Redirect\r\nLocation: http://{destination_address}/target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            )
            .unwrap();
            original
        });
        let form = reqwest::multipart::Form::new()
            .text("model", "synthetic-model")
            .part(
                "file",
                reqwest::multipart::Part::bytes(b"synthetic-audio-marker".to_vec())
                    .file_name("audio.wav")
                    .mime_str("audio/wav")
                    .unwrap(),
            );
        let response = crate::build_shared_http_client()
            .post(format!("http://{source_address}/audio/transcriptions"))
            .header("Authorization", "Bearer synthetic-upload-key")
            .multipart(form)
            .timeout(Duration::from_secs(5))
            .send()
            .await;
        let original = source_server.join().unwrap();
        let forwarded = destination_server.join().unwrap();
        assert!(original.contains("synthetic-audio-marker"));
        assert!(original.contains("Bearer synthetic-upload-key"));
        assert!(forwarded.is_none());
        assert_eq!(response.unwrap().status().as_u16(), status);
    }
}

#[tokio::test]
async fn production_same_origin_redirects_preserve_auth_and_existing_method_semantics() {
    for status in [301, 302, 303, 307, 308] {
        for method in [reqwest::Method::GET, reqwest::Method::POST] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let server = std::thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                let original = read_request(&mut socket);
                write!(
                    socket,
                    "HTTP/1.1 {status} Redirect\r\nLocation: /target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                )
                .unwrap();
                drop(socket);
                (original, capture_destination(listener))
            });
            let client = crate::build_shared_http_client();
            let request = crate::llm::protocol::apply_auth_headers(
                client.request(method.clone(), format!("http://{address}/source")),
                "anthropic",
                "https://api.anthropic.com/v1",
                "synthetic-redirect-key",
            );
            let request = if method == reqwest::Method::POST {
                request.json(&serde_json::json!({"text": "synthetic-private-transcription"}))
            } else {
                request
            };
            let result = request.timeout(Duration::from_secs(5)).send().await;
            let (original, redirected) = server.join().unwrap();
            let redirected = redirected.expect("same-origin redirect did not reach its target");
            assert!(original.starts_with(&format!("{method} /source HTTP/1.1")));
            assert!(original.contains("synthetic-redirect-key"));
            assert!(redirected.contains("synthetic-redirect-key"));
            let preserves_post = method == reqwest::Method::POST && matches!(status, 307 | 308);
            let expected_method = if preserves_post { "POST" } else { "GET" };
            assert!(
                redirected.starts_with(&format!("{expected_method} /target HTTP/1.1")),
                "unexpected same-origin method for {method}/{status}"
            );
            assert_eq!(
                redirected.contains("synthetic-private-transcription"),
                preserves_post,
                "unexpected same-origin body for {method}/{status}"
            );
            assert_eq!(result.unwrap().status(), reqwest::StatusCode::OK);
        }
    }
}

#[tokio::test]
async fn production_cross_origin_get_redirects_do_not_forward_anthropic_credentials() {
    for status in [301, 302, 303, 307, 308] {
        let source = TcpListener::bind("127.0.0.1:0").unwrap();
        let source_address = source.local_addr().unwrap();
        let destination = TcpListener::bind("127.0.0.1:0").unwrap();
        let destination_address = destination.local_addr().unwrap();
        let destination_server = std::thread::spawn(move || capture_destination(destination));
        let source_server = std::thread::spawn(move || {
            let (mut socket, _) = source.accept().unwrap();
            let original = read_request(&mut socket);
            write!(
                socket,
                "HTTP/1.1 {status} Redirect\r\nLocation: http://{destination_address}/target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            )
            .unwrap();
            original
        });
        let response = crate::llm::protocol::apply_auth_headers(
            crate::build_shared_http_client().get(format!("http://{source_address}/models")),
            "anthropic",
            "https://api.anthropic.com/v1",
            "synthetic-model-key",
        )
        .timeout(Duration::from_secs(5))
        .send()
        .await;
        assert!(source_server
            .join()
            .unwrap()
            .contains("synthetic-model-key"));
        assert!(destination_server.join().unwrap().is_none());
        assert_eq!(response.unwrap().status().as_u16(), status);
    }
}

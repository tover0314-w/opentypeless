use std::time::Duration;

// Includes provider processing, backend authentication/settlement, and transit.
// reqwest's timeout also covers response bodies, including streamed LLM output.
pub const LLM_TIMEOUT: Duration = Duration::from_secs(180);
pub const ASK_TIMEOUT: Duration = Duration::from_secs(75);
pub const STT_MAX_TIMEOUT: Duration = Duration::from_secs(360);

pub async fn read_response_with_limit(
    mut response: reqwest::Response,
    max_bytes: usize,
) -> Result<Vec<u8>, crate::error::AppError> {
    let oversized = || crate::error::AppError::Config("Service response too large".to_string());
    if response
        .content_length()
        .is_some_and(|length| u128::from(length) > max_bytes as u128)
    {
        return Err(oversized());
    }
    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(crate::error::AppError::from)?
    {
        if chunk.len() > max_bytes.saturating_sub(body.len()) {
            return Err(oversized());
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

pub fn stt_timeout(payload_bytes: usize) -> Duration {
    let upload_seconds = (payload_bytes as u64).div_ceil(32_000);
    Duration::from_secs(
        225_u64
            .saturating_add(upload_seconds)
            .min(STT_MAX_TIMEOUT.as_secs()),
    )
}

pub async fn read_json_response_with_limit(
    response: reqwest::Response,
    max_bytes: usize,
) -> Result<serde_json::Value, crate::error::AppError> {
    let bytes = read_response_with_limit(response, max_bytes).await?;
    serde_json::from_slice(&bytes)
        .map_err(|_| crate::error::AppError::Config("Invalid service JSON response".to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn local_response(headers: &str, body: &str) -> reqwest::Response {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let response = format!("HTTP/1.1 200 OK\r\n{headers}\r\nConnection: close\r\n\r\n{body}");
        std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut request = [0_u8; 4096];
            assert!(socket.read(&mut request).unwrap() > 0);
            let _ = socket.write_all(response.as_bytes());
        });
        reqwest::Client::builder()
            .no_proxy()
            .timeout(Duration::from_secs(5))
            .build()
            .unwrap()
            .get(format!("http://{address}"))
            .send()
            .await
            .unwrap()
    }

    #[tokio::test]
    async fn bounded_json_accepts_valid_response_and_rejects_invalid_or_oversized_data() {
        let response = local_response("Content-Length: 11", r#"{"ok":true}"#).await;
        assert_eq!(
            read_json_response_with_limit(response, 11).await.unwrap(),
            serde_json::json!({"ok": true})
        );
        let response = local_response("Content-Length: 12", r#"{"ok":true} "#).await;
        assert!(read_json_response_with_limit(response, 11)
            .await
            .unwrap_err()
            .to_string()
            .contains("too large"));
        let response = local_response("", "not-json").await;
        assert!(read_json_response_with_limit(response, 16).await.is_err());
        let response = local_response(
            "Transfer-Encoding: chunked",
            "3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n",
        )
        .await;
        assert!(read_json_response_with_limit(response, 4)
            .await
            .unwrap_err()
            .to_string()
            .contains("too large"));
    }

    #[tokio::test]
    async fn bounded_response_preserves_network_failure_for_an_incomplete_body() {
        let response = local_response("Content-Length: 8", "short").await;
        let error = read_response_with_limit(response, 16).await.unwrap_err();
        assert!(
            matches!(error, crate::error::AppError::Network(_)),
            "{error:?}"
        );
    }

    #[tokio::test]
    async fn bounded_response_accepts_a_body_at_the_limit() {
        let response = local_response("Content-Length: 4", "test").await;
        assert_eq!(
            read_response_with_limit(response, 4).await.unwrap(),
            b"test"
        );
    }

    #[tokio::test]
    async fn bounded_response_rejects_a_declared_oversized_body() {
        let response = local_response("Content-Length: 5", "tests").await;
        assert!(read_response_with_limit(response, 4)
            .await
            .unwrap_err()
            .to_string()
            .contains("too large"));
    }

    #[tokio::test]
    async fn bounded_response_rejects_chunked_bodies_without_a_length() {
        let response = local_response(
            "Transfer-Encoding: chunked",
            "3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n",
        )
        .await;
        assert!(read_response_with_limit(response, 4)
            .await
            .unwrap_err()
            .to_string()
            .contains("too large"));
    }

    #[test]
    fn budgets_cover_backend_deadlines_and_uploads() {
        assert!(stt_timeout(32_000).as_secs() > 210);
        assert_eq!(stt_timeout(4_000_000).as_secs(), 350);
        assert_eq!(stt_timeout(usize::MAX).as_secs(), 360);
        assert!(LLM_TIMEOUT.as_secs() > 120);
        assert!(ASK_TIMEOUT.as_secs() > 30);
    }

    async fn delayed_http_response(delay_seconds: u64, timeout: Duration) {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut buffer = [0; 4096];
            if socket.read(&mut buffer).unwrap() == 0 {
                return;
            }
            // Timeout must cover the complete body after response headers.
            socket
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n")
                .unwrap();
            std::thread::sleep(Duration::from_secs(delay_seconds));
            socket.write_all(b"ok").unwrap();
        });
        let client = reqwest::Client::builder()
            .timeout(Duration::from_millis(100))
            .build()
            .unwrap();
        let response = client
            .get(format!("http://{address}"))
            .timeout(timeout)
            .send()
            .await
            .unwrap();
        assert_eq!(response.text().await.unwrap(), "ok");
        server.join().unwrap();
    }

    #[tokio::test]
    async fn llm_body_survives_the_former_fifteen_second_deadline() {
        delayed_http_response(16, LLM_TIMEOUT).await;
    }

    #[tokio::test]
    async fn ask_body_survives_the_former_forty_five_second_deadline() {
        delayed_http_response(46, ASK_TIMEOUT).await;
    }

    #[tokio::test]
    async fn stt_body_survives_the_former_sixty_second_deadline() {
        delayed_http_response(61, stt_timeout(32_000)).await;
    }
}

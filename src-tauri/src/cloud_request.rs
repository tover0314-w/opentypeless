use std::time::Duration;

// Includes provider processing, backend authentication/settlement, and transit.
// reqwest's timeout also covers response bodies, including streamed LLM output.
pub const LLM_TIMEOUT: Duration = Duration::from_secs(180);
pub const ASK_TIMEOUT: Duration = Duration::from_secs(75);
pub const STT_MAX_TIMEOUT: Duration = Duration::from_secs(360);

pub fn stt_timeout(payload_bytes: usize) -> Duration {
    let upload_seconds = (payload_bytes as u64).div_ceil(32_000);
    Duration::from_secs(
        225_u64
            .saturating_add(upload_seconds)
            .min(STT_MAX_TIMEOUT.as_secs()),
    )
}

// Includes every retry, backoff, and response-body read. Dropping the future
// on timeout also cancels the in-flight request rather than allowing late output.
pub async fn with_llm_deadline<T>(
    operation: impl std::future::Future<Output = Result<T, crate::error::AppError>>,
) -> Result<T, crate::error::AppError> {
    tokio::time::timeout(LLM_TIMEOUT, operation)
        .await
        .map_err(|_| crate::error::AppError::Timeout(LLM_TIMEOUT))?
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test(start_paused = true)]
    async fn llm_total_deadline_includes_retries_and_cancels_late_output() {
        let result = with_llm_deadline(async {
            tokio::time::sleep(Duration::from_secs(110)).await;
            tokio::time::sleep(Duration::from_secs(110)).await;
            Ok::<_, crate::error::AppError>("late response")
        })
        .await;
        assert!(matches!(
            result,
            Err(crate::error::AppError::Timeout(LLM_TIMEOUT))
        ));
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

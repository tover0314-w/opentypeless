//! Localhost HTTP server for recording playback.
//!
//! WebKitGTK on Linux cannot play media from Tauri's `asset://` scheme (GStreamer
//! has no URI handler for it) or from `blob:` URLs inside the Tauri webview, both
//! of which fail with `MEDIA_ERR_SRC_NOT_SUPPORTED`. It *can* play `http://`
//! sources, so we serve saved recordings over a tiny 127.0.0.1 server and point
//! the `<audio>` element at it. Range requests are supported so seeking works.

use std::path::{Path, PathBuf};

/// Start the server on a random localhost port, serving files out of
/// `recordings_dir`. Returns the bound port. The server runs on its own thread
/// for the lifetime of the app.
pub fn spawn(recordings_dir: PathBuf) -> std::io::Result<u16> {
    let server = tiny_http::Server::http("127.0.0.1:0")
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;
    let port = server
        .server_addr()
        .to_ip()
        .map(|addr| addr.port())
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::Other, "no bound port"))?;

    std::thread::spawn(move || {
        for request in server.incoming_requests() {
            handle(request, &recordings_dir);
        }
    });

    Ok(port)
}

fn content_type(name: &str) -> &'static str {
    match name.rsplit('.').next().map(|e| e.to_ascii_lowercase()).as_deref() {
        Some("mp3") => "audio/mpeg",
        Some("wav") => "audio/wav",
        Some("flac") => "audio/flac",
        Some("ogg" | "opus") => "audio/ogg",
        _ => "application/octet-stream",
    }
}

fn header(field: &str, value: &str) -> tiny_http::Header {
    // Safe to unwrap: all callers pass static ASCII field names and simple values.
    tiny_http::Header::from_bytes(field.as_bytes(), value.as_bytes())
        .expect("valid header")
}

/// Parse a single-range `Range: bytes=...` value into inclusive (start, end).
fn parse_range(raw: &str, len: u64) -> Option<(u64, u64)> {
    let spec = raw.trim().strip_prefix("bytes=")?;
    let (s, e) = spec.split_once('-')?;
    let (start, end) = match (s.trim(), e.trim()) {
        ("", "") => return None,
        ("", suffix) => (len.saturating_sub(suffix.parse().ok()?), len - 1),
        (start, "") => (start.parse().ok()?, len - 1),
        (start, end) => (start.parse().ok()?, end.parse().ok()?),
    };
    if len == 0 || start > end || end >= len {
        return None;
    }
    Some((start, end))
}

fn handle(request: tiny_http::Request, dir: &Path) {
    let name = request.url().trim_start_matches('/').to_string();

    // Only allow a bare filename within the recordings dir — no path traversal.
    if name.is_empty() || name.contains('/') || name.contains('\\') || name.contains("..") {
        let _ = request.respond(tiny_http::Response::empty(403));
        return;
    }

    let data = match std::fs::read(dir.join(&name)) {
        Ok(d) => d,
        Err(_) => {
            let _ = request.respond(tiny_http::Response::empty(404));
            return;
        }
    };
    let len = data.len() as u64;
    let ctype = content_type(&name);

    let range = request
        .headers()
        .iter()
        .find(|h| h.field.equiv("Range"))
        .and_then(|h| parse_range(h.value.as_str(), len));

    let result = match range {
        Some((start, end)) => {
            let slice = data[start as usize..=end as usize].to_vec();
            let response = tiny_http::Response::from_data(slice)
                .with_status_code(206)
                .with_header(header("Content-Type", ctype))
                .with_header(header("Accept-Ranges", "bytes"))
                .with_header(header(
                    "Content-Range",
                    &format!("bytes {start}-{end}/{len}"),
                ));
            request.respond(response)
        }
        None => {
            let response = tiny_http::Response::from_data(data)
                .with_header(header("Content-Type", ctype))
                .with_header(header("Accept-Ranges", "bytes"));
            request.respond(response)
        }
    };
    let _ = result;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_open_ended_range() {
        assert_eq!(parse_range("bytes=100-", 1000), Some((100, 999)));
    }

    #[test]
    fn parses_bounded_range() {
        assert_eq!(parse_range("bytes=0-499", 1000), Some((0, 499)));
    }

    #[test]
    fn rejects_out_of_bounds() {
        assert_eq!(parse_range("bytes=2000-3000", 1000), None);
    }

    #[test]
    fn content_type_by_extension() {
        assert_eq!(content_type("a.mp3"), "audio/mpeg");
        assert_eq!(content_type("a.wav"), "audio/wav");
    }
}

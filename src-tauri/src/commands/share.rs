use std::path::{Path, PathBuf};

use tauri::ipc::{InvokeBody, Request};
use tauri_plugin_clipboard_manager::ClipboardExt;
use tauri_plugin_dialog::DialogExt;

const PNG_SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', b'\r', b'\n', 0x1a, b'\n'];
const MAX_SHARE_IMAGE_BYTES: usize = 32 * 1024 * 1024;
pub const SAVE_CANCELLED: &str = "cancelled";

fn png_body(request: &Request<'_>) -> Result<Vec<u8>, String> {
    let InvokeBody::Raw(bytes) = request.body() else {
        return Err("Expected PNG bytes".to_string());
    };
    if bytes.len() > MAX_SHARE_IMAGE_BYTES || !bytes.starts_with(&PNG_SIGNATURE) {
        return Err("Invalid PNG image".to_string());
    }
    Ok(bytes.clone())
}

pub fn decode_png_rgba(bytes: &[u8]) -> Result<(Vec<u8>, u32, u32), String> {
    let mut decoder = png::Decoder::new(bytes);
    decoder.set_transformations(png::Transformations::normalize_to_color8());
    let mut reader = decoder.read_info().map_err(|error| error.to_string())?;
    let mut buffer = vec![0; reader.output_buffer_size()];
    let info = reader
        .next_frame(&mut buffer)
        .map_err(|error| error.to_string())?;
    buffer.truncate(info.buffer_size());
    let rgba = match info.color_type {
        png::ColorType::Rgba => buffer,
        png::ColorType::Rgb => buffer
            .chunks_exact(3)
            .flat_map(|pixel| [pixel[0], pixel[1], pixel[2], 255])
            .collect(),
        png::ColorType::GrayscaleAlpha => buffer
            .chunks_exact(2)
            .flat_map(|pixel| [pixel[0], pixel[0], pixel[0], pixel[1]])
            .collect(),
        png::ColorType::Grayscale => buffer
            .iter()
            .flat_map(|value| [*value, *value, *value, 255])
            .collect(),
        png::ColorType::Indexed => return Err("Unsupported PNG color type".to_string()),
    };
    Ok((rgba, info.width, info.height))
}

/// Keeps only the final path component so a renderer-supplied name cannot pick a directory.
pub fn sanitize_png_file_name(name: &str) -> String {
    let base = Path::new(name)
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or_default()
        .trim();
    let stem = base.strip_suffix(".png").unwrap_or(base);
    let cleaned: String = stem
        .chars()
        .filter(|ch| ch.is_ascii_alphanumeric() || matches!(ch, '-' | '_'))
        .collect();
    if cleaned.is_empty() {
        "OpenTypeless-insights.png".to_string()
    } else {
        format!("{cleaned}.png")
    }
}

#[tauri::command]
pub async fn copy_png_to_clipboard(
    app: tauri::AppHandle,
    request: Request<'_>,
) -> Result<(), String> {
    let bytes = png_body(&request)?;
    let (rgba, width, height) =
        tauri::async_runtime::spawn_blocking(move || decode_png_rgba(&bytes))
            .await
            .map_err(|error| error.to_string())??;
    app.clipboard()
        .write_image(&tauri::image::Image::new_owned(rgba, width, height))
        .map_err(|error| error.to_string())
}

/// Shows a native save panel; returns the written path, or `cancelled` as an error.
#[tauri::command]
pub async fn save_png_with_dialog(
    app: tauri::AppHandle,
    request: Request<'_>,
) -> Result<String, String> {
    let bytes = png_body(&request)?;
    let file_name = request
        .headers()
        .get("x-file-name")
        .and_then(|value| value.to_str().ok())
        .map(sanitize_png_file_name)
        .unwrap_or_else(|| sanitize_png_file_name(""));
    let mut dialog = app
        .dialog()
        .file()
        .set_file_name(file_name)
        .add_filter("PNG", &["png"]);
    if let Ok(directory) = tauri::Manager::path(&app).download_dir() {
        dialog = dialog.set_directory(directory);
    }
    let (sender, receiver) = tokio::sync::oneshot::channel();
    dialog.save_file(move |path| {
        let _ = sender.send(path);
    });
    let path: PathBuf = receiver
        .await
        .map_err(|error| error.to_string())?
        .ok_or_else(|| SAVE_CANCELLED.to_string())?
        .into_path()
        .map_err(|error| error.to_string())?;
    let written = path.clone();
    tauri::async_runtime::spawn_blocking(move || std::fs::write(&written, bytes))
        .await
        .map_err(|error| error.to_string())?
        .map_err(|error| error.to_string())?;
    Ok(path.to_string_lossy().into_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn encode(color: png::ColorType, width: u32, height: u32, data: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut out, width, height);
            encoder.set_color(color);
            encoder.set_depth(png::BitDepth::Eight);
            let mut writer = encoder.write_header().unwrap();
            writer.write_image_data(data).unwrap();
        }
        out
    }

    #[test]
    fn decodes_rgba_and_rgb_pngs_into_rgba_pixels() {
        let rgba = encode(png::ColorType::Rgba, 2, 1, &[1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(
            decode_png_rgba(&rgba).unwrap(),
            (vec![1, 2, 3, 4, 5, 6, 7, 8], 2, 1)
        );
        let rgb = encode(png::ColorType::Rgb, 1, 1, &[9, 8, 7]);
        assert_eq!(decode_png_rgba(&rgb).unwrap(), (vec![9, 8, 7, 255], 1, 1));
        assert!(decode_png_rgba(b"not a png").is_err());
    }

    #[test]
    fn file_names_cannot_escape_the_chosen_directory() {
        assert_eq!(
            sanitize_png_file_name("OpenTypeless-insights-2026-11-03.png"),
            "OpenTypeless-insights-2026-11-03.png"
        );
        assert_eq!(sanitize_png_file_name("../../etc/passwd"), "passwd.png");
        assert_eq!(sanitize_png_file_name("a b;c"), "abc.png");
        assert_eq!(sanitize_png_file_name(""), "OpenTypeless-insights.png");
    }
}

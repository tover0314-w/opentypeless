/// Input devices for the microphone picker, system default first.
#[tauri::command]
pub async fn list_microphones() -> Result<Vec<crate::audio::InputDeviceInfo>, String> {
    tauri::async_runtime::spawn_blocking(crate::audio::list_input_devices)
        .await
        .map_err(|error| error.to_string())
}

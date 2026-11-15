/// Input devices for the microphone picker, system default first.
#[tauri::command]
pub async fn list_microphones(
    app: tauri::AppHandle,
) -> Result<Vec<crate::audio::InputDeviceInfo>, String> {
    let devices = tauri::async_runtime::spawn_blocking(crate::audio::list_input_devices)
        .await
        .map_err(|error| error.to_string())?;
    if crate::tray::set_microphones(devices.clone()) {
        crate::refresh_tray(&app);
    }
    Ok(devices)
}

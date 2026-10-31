use crate::storage;

#[tauri::command]
pub async fn get_history(
    state: tauri::State<'_, storage::HistoryStore>,
    limit: u32,
    offset: u32,
) -> Result<Vec<storage::HistoryEntry>, String> {
    state.list(limit, offset).await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn clear_history(state: tauri::State<'_, storage::HistoryStore>) -> Result<(), String> {
    state.clear().await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_history_entry(
    state: tauri::State<'_, storage::HistoryStore>,
    id: i64,
) -> Result<bool, String> {
    state.delete(id).await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_usage_stats(
    state: tauri::State<'_, storage::HistoryStore>,
) -> Result<storage::stats::UsageStats, String> {
    state
        .stats(chrono::Local::now().date_naive())
        .await
        .map_err(|e| e.to_string())
}

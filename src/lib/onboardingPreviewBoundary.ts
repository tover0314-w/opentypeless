export async function mountBrowserOnlyPreview(
  environment: object,
  loadPreview: () => Promise<unknown>,
): Promise<boolean> {
  if ('__TAURI_INTERNALS__' in environment || '__TAURI__' in environment) return false
  await loadPreview()
  return true
}

/** Main window → capsule: a background download finished while the user was elsewhere. */
export const UPDATE_READY_EVENT = 'updater:show-downloaded-notify'
/** Capsule → main window: the downloaded package lives in the main webview, so it installs. */
export const UPDATE_INSTALL_EVENT = 'updater:quit-and-install'
/** Capsule shows the notice after the bar has sat idle this long (REA `zi`: 8000ms). */
export const UPDATE_NOTICE_IDLE_DELAY_MS = 8000

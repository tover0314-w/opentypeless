import { CircleUser, Settings as SettingsIcon, PenLine, Info, CircleHelp } from 'lucide-react'

export const SETTINGS_TABS = [
  { id: 'account', labelKey: 'settingsModal.tabs.account', icon: CircleUser },
  { id: 'general', labelKey: 'settingsModal.tabs.settings', icon: SettingsIcon },
  { id: 'personalization', labelKey: 'settingsModal.tabs.personalization', icon: PenLine },
  { id: 'about', labelKey: 'settingsModal.tabs.about', icon: Info },
  { id: 'help', labelKey: 'settingsModal.tabs.help', icon: CircleHelp },
] as const

export type SettingsTabId = (typeof SETTINGS_TABS)[number]['id']
export type PaneId = SettingsTabId

// Older deep links keep working after panes were merged. Model panes now live on the
// sidebar Models page; the router redirects those before Settings opens.
const LEGACY_PANES: Record<string, SettingsTabId> = {
  scenes: 'personalization',
}

export function paneFromHash(): SettingsTabId | null {
  const query = window.location.hash.split('?')[1]
  if (!query) return null
  const requested = new URLSearchParams(query).get('pane')
  if (!requested) return null
  if (requested in LEGACY_PANES) return LEGACY_PANES[requested]
  return SETTINGS_TABS.some((tab) => tab.id === requested) ? (requested as SettingsTabId) : null
}

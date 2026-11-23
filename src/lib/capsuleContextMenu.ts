import { Menu } from '@tauri-apps/api/menu'
import { openCapsuleSettings } from './capsuleNavigation'
import { quitFromCapsule, setCapsuleAutoHide } from './tauri'
import { useAppStore } from '../stores/appStore'

let retainedMenu: { signature: string; menu: Menu } | null = null
let menuGeneration = 0
let menuTask: Promise<void> = Promise.resolve()

export function disposeCapsuleContextMenu(): Promise<void> {
  menuGeneration += 1
  const retained = retainedMenu
  retainedMenu = null
  return retained ? retained.menu.close() : Promise.resolve()
}

export async function showCapsuleContextMenu(translate: (key: string) => string): Promise<void> {
  const generation = menuGeneration
  const task = menuTask
    .catch(() => {})
    .then(async () => {
      if (generation !== menuGeneration) return
      await showRetainedMenu(translate, generation)
    })
  menuTask = task
  return task
}

async function showRetainedMenu(
  translate: (key: string) => string,
  generation: number,
): Promise<void> {
  const hidden = useAppStore.getState().config.capsule_auto_hide
  const signature = JSON.stringify([
    hidden,
    ...[
      'openMainWindow',
      'keepVisible',
      'hideWhenIdle',
      'settings',
      'history',
      'account',
      'upgrade',
      'exit',
    ].map((key) => translate(`capsule.menu.${key}`)),
  ])
  if (retainedMenu?.signature === signature) {
    await popupRetainedMenu(retainedMenu.menu)
    return
  }
  const previous = retainedMenu
  retainedMenu = null
  if (previous) await previous.menu.close()
  if (generation !== menuGeneration) return
  const isCurrent = () => generation === menuGeneration && retainedMenu?.menu === menu
  const navigate = (route: string) => {
    if (!isCurrent()) return
    void openCapsuleSettings(route).catch(() => console.error('Could not open capsule destination'))
  }
  const menu = await Menu.new({
    items: [
      { id: 'main', text: translate('capsule.menu.openMainWindow'), action: () => navigate('/') },
      {
        id: 'visibility',
        text: translate(hidden ? 'capsule.menu.keepVisible' : 'capsule.menu.hideWhenIdle'),
        action: () => {
          if (!isCurrent()) return
          void setCapsuleAutoHide(!hidden).catch(() =>
            console.error('Could not change capsule visibility'),
          )
        },
      },
      {
        id: 'settings',
        text: translate('capsule.menu.settings'),
        action: () => navigate('/settings'),
      },
      {
        id: 'history',
        text: translate('capsule.menu.history'),
        action: () => navigate('/history'),
      },
      {
        id: 'account',
        text: translate('capsule.menu.account'),
        action: () => navigate('/account'),
      },
      {
        id: 'upgrade',
        text: translate('capsule.menu.upgrade'),
        action: () => navigate('/upgrade'),
      },
      {
        id: 'exit',
        text: translate('capsule.menu.exit'),
        action: () => {
          if (!isCurrent()) return
          void quitFromCapsule().catch(() => console.error('Could not exit from capsule'))
        },
      },
    ],
  })
  if (generation !== menuGeneration) {
    await menu.close()
    return
  }
  retainedMenu = { signature, menu }
  await popupRetainedMenu(menu)
}

async function popupRetainedMenu(menu: Menu): Promise<void> {
  try {
    await menu.popup()
  } catch (error) {
    if (retainedMenu?.menu === menu) {
      retainedMenu = null
      await menu.close().catch(() => console.error('Could not release failed capsule menu'))
    }
    throw error
  }
}

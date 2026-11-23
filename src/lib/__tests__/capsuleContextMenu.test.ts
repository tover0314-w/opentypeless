import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { Menu } from '@tauri-apps/api/menu'
import { disposeCapsuleContextMenu, showCapsuleContextMenu } from '../capsuleContextMenu'
import { openCapsuleSettings } from '../capsuleNavigation'
import { quitFromCapsule, setCapsuleAutoHide } from '../tauri'
import { useAppStore } from '../../stores/appStore'

const nativeMenu = vi.hoisted(() => ({
  popup: vi.fn().mockResolvedValue(undefined),
  close: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@tauri-apps/api/menu', () => ({
  Menu: { new: vi.fn().mockResolvedValue(nativeMenu) },
}))
vi.mock('../capsuleNavigation', () => ({
  openCapsuleSettings: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('../tauri', () => ({
  setCapsuleAutoHide: vi.fn().mockResolvedValue(undefined),
  quitFromCapsule: vi.fn().mockResolvedValue(undefined),
}))
beforeEach(() => {
  vi.clearAllMocks()
})
afterEach(async () => {
  await disposeCapsuleContextMenu()
  useAppStore.setState(useAppStore.getInitialState())
})

it('preserves navigation and visibility actions without invoking them on menu creation', async () => {
  useAppStore.setState({
    config: { ...useAppStore.getState().config, capsule_auto_hide: false },
  })
  await showCapsuleContextMenu((key) => key)
  const items = vi.mocked(Menu.new).mock.calls[0][0]!.items! as Array<{
    id: string
    action: (id: string) => void
  }>
  expect(items.map((item) => item.id)).toEqual([
    'main',
    'visibility',
    'settings',
    'history',
    'account',
    'upgrade',
    'exit',
  ])
  expect(openCapsuleSettings).not.toHaveBeenCalled()
  expect(setCapsuleAutoHide).not.toHaveBeenCalled()
  expect(quitFromCapsule).not.toHaveBeenCalled()
  items.find((item) => item.id === 'exit')!.action('exit')
  expect(quitFromCapsule).toHaveBeenCalledOnce()
  items.find((item) => item.id === 'history')!.action('history')
  expect(openCapsuleSettings).toHaveBeenCalledWith('/history')
  items.find((item) => item.id === 'visibility')!.action('visibility')
  expect(setCapsuleAutoHide).toHaveBeenCalledWith(true)
  expect(nativeMenu.popup).toHaveBeenCalledOnce()
  expect(nativeMenu.close).not.toHaveBeenCalled()
  await showCapsuleContextMenu((key) => key)
  expect(Menu.new).toHaveBeenCalledOnce()
  await disposeCapsuleContextMenu()
  expect(nativeMenu.close).toHaveBeenCalledOnce()
})

it('releases a menu created after its owner was disposed without showing it', async () => {
  let finishCreation: (menu: Menu) => void = () => {}
  vi.mocked(Menu.new).mockImplementationOnce(
    () =>
      new Promise<Menu>((resolve) => {
        finishCreation = resolve
      }),
  )
  const opening = showCapsuleContextMenu((key) => key)
  await vi.waitFor(() => expect(Menu.new).toHaveBeenCalledOnce())
  await disposeCapsuleContextMenu()
  finishCreation(nativeMenu as unknown as Menu)
  await opening
  expect(nativeMenu.popup).not.toHaveBeenCalled()
  expect(nativeMenu.close).toHaveBeenCalledOnce()
})

it('ignores actions from a disposed menu', async () => {
  await showCapsuleContextMenu((key) => key)
  const items = vi.mocked(Menu.new).mock.calls[0][0]!.items! as Array<{
    id: string
    action: (id: string) => void
  }>
  await disposeCapsuleContextMenu()
  for (const item of items) item.action(item.id)
  expect(openCapsuleSettings).not.toHaveBeenCalled()
  expect(setCapsuleAutoHide).not.toHaveBeenCalled()
  expect(quitFromCapsule).not.toHaveBeenCalled()
})

it('rebuilds the retained menu when the exit translation changes', async () => {
  await showCapsuleContextMenu((key) => key)
  await showCapsuleContextMenu((key) => (key === 'capsule.menu.exit' ? 'Translated exit' : key))
  expect(Menu.new).toHaveBeenCalledTimes(2)
  expect(nativeMenu.close).toHaveBeenCalledOnce()
  const items = vi.mocked(Menu.new).mock.calls[1][0]!.items! as Array<{
    id: string
    text: string
  }>
  expect(items.find((item) => item.id === 'exit')?.text).toBe('Translated exit')
})

it.each(['new', 'retained'])(
  'releases a %s menu after popup failure and recreates it',
  async (kind) => {
    if (kind === 'retained') await showCapsuleContextMenu((key) => key)
    nativeMenu.popup.mockRejectedValueOnce(new Error('Synthetic popup failure'))
    await expect(showCapsuleContextMenu((key) => key)).rejects.toThrow('Synthetic popup failure')
    expect(nativeMenu.close).toHaveBeenCalledOnce()
    const previousCreations = vi.mocked(Menu.new).mock.calls.length
    await showCapsuleContextMenu((key) => key)
    expect(Menu.new).toHaveBeenCalledTimes(previousCreations + 1)
  },
)

import { afterEach, expect, it, vi } from 'vitest'

const initialize = vi.hoisted(() => vi.fn())
vi.mock('i18next', () => ({
  default: { use: () => ({ init: initialize }), on: vi.fn() },
}))
vi.mock('react-i18next', () => ({ initReactI18next: {} }))

afterEach(() => {
  vi.restoreAllMocks()
  vi.resetModules()
  initialize.mockClear()
})

it('initializes English when reading the non-authoritative language cache is denied', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new DOMException('Synthetic cache restriction', 'SecurityError')
  })
  await expect(import('../index')).resolves.toBeDefined()
  expect(initialize).toHaveBeenCalledWith(expect.objectContaining({ lng: 'en' }))
})

it('keeps a readable saved language for normal startup', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockReturnValue('ja')
  await import('../index')
  expect(initialize).toHaveBeenCalledWith(expect.objectContaining({ lng: 'ja' }))
})

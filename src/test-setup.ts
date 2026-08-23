import '@testing-library/jest-dom/vitest'

// Node 25 exposes an incomplete global localStorage unless --localstorage-file
// points to a real path. Tests run in jsdom, so install a deterministic Storage
// implementation instead of inheriting that process-level shim.
const storage = new Map<string, string>()
const localStorageMock: Storage = {
  get length() {
    return storage.size
  },
  clear: () => storage.clear(),
  getItem: (key) => storage.get(key) ?? null,
  key: (index) => [...storage.keys()][index] ?? null,
  removeItem: (key) => storage.delete(key),
  setItem: (key, value) => storage.set(key, String(value)),
}

Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: localStorageMock,
})

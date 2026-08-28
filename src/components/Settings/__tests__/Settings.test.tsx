/**
 * Settings 组件测试集
 *
 * 覆盖以下范围：
 * 1. Tab 切换 — 点击侧边栏后正确显示对应 Pane 内容
 * 2. 动画结构 — AnimatePresence wrapper 正常渲染
 * 3. appStore.llmModels — 状态提升：初始值、读写、reset
 * 4. LlmPane provider 切换 — 清空 models 缓存
 * 5. LlmPane useEffect skip — 已有缓存时不再触发 debounce fetch
 * 6. DirtyBar — 配置变更后出现，Reset 后消失
 * 7. appStore getInitialState — llmModels 在 reset 后为空数组
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react'
import React from 'react'
import { useAppStore } from '../../../stores/appStore'

// 每个测试后清理 DOM，防止多次 render 的节点积累导致 getByText 找到多个元素
afterEach(() => {
  cleanup()
})

// ─── Mock framer-motion ───────────────────────────────────────────────────────
// 过滤掉所有 framer-motion 专有 prop，避免 React DOM 警告和 getByText 多元素问题
const MOTION_PROPS = new Set([
  'initial',
  'animate',
  'exit',
  'transition',
  'variants',
  'whileHover',
  'whileTap',
  'whileFocus',
  'whileDrag',
  'whileInView',
  'layoutId',
  'layout',
  'drag',
  'dragConstraints',
  'onAnimationComplete',
])
const motionComponentCache = new Map<string, React.ComponentType<any>>()

vi.mock('framer-motion', () => ({
  AnimatePresence: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  motion: new Proxy(
    {},
    {
      get: (_t, tag: string) => {
        if (!motionComponentCache.has(tag)) {
          motionComponentCache.set(tag, ({ children, ...rest }: any) => {
            const domProps: Record<string, unknown> = {}
            for (const [k, v] of Object.entries(rest)) {
              if (!MOTION_PROPS.has(k)) domProps[k] = v
            }
            return React.createElement(tag as string, { 'data-motion': tag, ...domProps }, children)
          })
        }
        return motionComponentCache.get(tag)
      },
    },
  ),
}))

// ─── Mock react-i18next ───────────────────────────────────────────────────────
vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-i18next')>()
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) =>
        ({
          'settings.unsavedChanges': 'Unsaved changes',
          'common.save': 'Save',
          'common.saving': 'Saving...',
          'common.reset': 'Reset',
          'common.connectionFail': 'Connection failed',
        })[key] ?? key,
      i18n: { language: 'en', changeLanguage: vi.fn() },
    }),
  }
})

// ─── Mock Tauri plugins / lib/tauri ──────────────────────────────────────────
vi.mock('../../../lib/tauri', () => ({
  getConfig: vi.fn().mockResolvedValue(null),
  getSttRecordingCapability: vi.fn().mockResolvedValue({
    capability: {
      registryVersion: 1,
      providerId: 'glm-asr',
      transport: 'fileUpload',
      recommendedMaxSeconds: 30,
      hardMaxSeconds: 30,
      maxUploadBytes: 24 * 1024 * 1024,
      source: 'provider',
      explanationKey: 'recordingLimits.reasons.providerDuration',
    },
    mode: 'auto',
    requestedSeconds: 30,
    effectiveMaxSeconds: 30,
  }),
  updateHotkey: vi.fn().mockResolvedValue(undefined),
  updateAskHotkey: vi.fn().mockResolvedValue(undefined),
  askAnything: vi.fn().mockResolvedValue('A concise answer.'),
  showAskWindow: vi.fn().mockResolvedValue(undefined),
  startAskFlow: vi.fn().mockResolvedValue(undefined),
  startAskDictation: vi.fn().mockResolvedValue(undefined),
  stopAskDictation: vi.fn().mockResolvedValue({
    question: 'What is OpenTypeless?',
    answer: 'A concise answer.',
  }),
  abortAskDictation: vi.fn().mockResolvedValue(undefined),
  pauseHotkey: vi.fn().mockResolvedValue(undefined),
  resumeHotkey: vi.fn().mockResolvedValue(undefined),
  checkAccessibilityPermission: vi.fn().mockResolvedValue(true),
  requestAccessibilityPermission: vi.fn().mockResolvedValue(true),
  waitForAccessibilityPermission: vi.fn().mockResolvedValue(true),
  getPlatformCapabilities: vi.fn().mockResolvedValue({
    os: 'macos',
    sessionType: 'unknown',
    globalHotkeyReliable: true,
    keyboardOutputReliable: true,
    clipboardAutoPasteReliable: true,
  }),
  getHotkeyRegistrationError: vi.fn().mockResolvedValue(null),
  getHotkeyStatus: vi.fn().mockResolvedValue({
    dictation: { value: 'Ctrl+/', valid: true },
    ask: { value: 'Ctrl+.', valid: true },
    conflict: false,
    registration_error: null,
    roles: [
      {
        role: 'dictation',
        index: 0,
        display: 'Ctrl+/',
        backend: 'tauriGlobalShortcut',
        valid: true,
        conflictWith: null,
        adapter: 'tauriGlobalShortcut',
        state: 'installed',
        message: null,
        lastError: null,
      },
      {
        role: 'ask',
        index: 0,
        display: 'Ctrl+.',
        backend: 'tauriGlobalShortcut',
        valid: true,
        conflictWith: null,
        adapter: 'tauriGlobalShortcut',
        state: 'installed',
        message: null,
        lastError: null,
      },
    ],
    capability: {
      platform: 'macos',
      sessionType: 'unknown',
      supportsGlobalHotkey: true,
      supportsHoldMode: true,
      supportsReleasedEdge: true,
      supportsSideSpecificModifiers: false,
      requiresAccessibilityPermission: false,
      statusHint: null,
    },
  }),
  getSystemDiagnostics: vi.fn().mockResolvedValue({
    checkedAt: '2026-07-06T00:00:00',
    rows: [
      {
        id: 'microphone',
        status: 'ok',
        message: 'Built-in microphone / 48000 Hz',
        action: null,
        lastCheckedAt: '2026-07-06T00:00:00',
      },
      {
        id: 'hotkey',
        status: 'warning',
        message: 'Global hotkeys may be limited',
        action: null,
        lastCheckedAt: '2026-07-06T00:00:00',
      },
    ],
  }),
  setAutoStart: vi.fn().mockResolvedValue(undefined),
  testSttConnection: vi.fn().mockResolvedValue(true),
  testLlmConnection: vi.fn().mockResolvedValue(true),
  readCredential: vi.fn().mockResolvedValue(null),
  setCredential: vi.fn().mockResolvedValue(undefined),
  fetchLlmModels: vi.fn().mockResolvedValue(['gpt-4o', 'gpt-3.5-turbo']),
  getLlmModelCapability: vi.fn().mockResolvedValue('unknown'),
  addDictionaryEntry: vi.fn().mockResolvedValue(undefined),
  updateDictionaryEntry: vi.fn().mockResolvedValue(undefined),
  removeDictionaryEntry: vi.fn().mockResolvedValue(undefined),
  getDictionary: vi.fn().mockResolvedValue([]),
  addCorrectionRule: vi.fn().mockResolvedValue(undefined),
  updateCorrectionRule: vi.fn().mockResolvedValue(undefined),
  removeCorrectionRule: vi.fn().mockResolvedValue(undefined),
  setCorrectionRuleEnabled: vi.fn().mockResolvedValue(undefined),
  getCorrectionRules: vi.fn().mockResolvedValue([]),
  previewDictionaryImport: vi.fn().mockResolvedValue({
    accepted: 0,
    skippedDuplicates: 0,
    skippedInvalid: 0,
    errors: [],
  }),
  commitDictionaryImport: vi.fn().mockResolvedValue({
    accepted: 0,
    skippedDuplicates: 0,
    skippedInvalid: 0,
    errors: [],
  }),
  exportDictionaryJson: vi.fn().mockResolvedValue('{}'),
  exportDictionaryCsv: vi.fn().mockResolvedValue(''),
  listCustomAppMappings: vi.fn().mockResolvedValue([]),
  setFamilySceneAssignment: vi.fn().mockResolvedValue([]),
  updateConfig: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('../../../components/toast-service', () => ({
  toast: vi.fn(),
}))

// ─── Mock @tauri-apps/plugin-opener ─────────────────────────────────────────
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))

// ─── Mock stores/authStore ────────────────────────────────────────────────────
const mockAuthState = {
  user: null,
  plan: 'free',
  source: 'free',
  cloudWordsLimit: 0,
  licenseStatus: null,
}

vi.mock('../../../stores/authStore', () => ({
  hasManagedCloudAccess: (state: typeof mockAuthState) =>
    state.licenseStatus !== 'refunded' &&
    state.licenseStatus !== 'deactivated' &&
    ((state.source === 'creem' && state.cloudWordsLimit > 0) ||
      (state.source === 'appsumo' &&
        state.cloudWordsLimit > 0 &&
        state.licenseStatus === 'active') ||
      state.plan === 'pro'),
  useAuthStore: (selector: any) =>
    typeof selector === 'function' ? selector(mockAuthState) : mockAuthState,
}))

// ─── Import components AFTER mocks ───────────────────────────────────────────
import { Settings } from '../index'
import {
  checkAccessibilityPermission,
  getConfig,
  getHotkeyRegistrationError,
  listCustomAppMappings,
  setFamilySceneAssignment,
  setAutoStart,
  startAskFlow,
  updateConfig,
} from '../../../lib/tauri'
import { toast } from '../../../components/toast-service'
import type { HotkeyStatus } from '../../../lib/tauri'

// ─── Helpers ─────────────────────────────────────────────────────────────────
function mockHotkeyStatus(overrides: Partial<HotkeyStatus> = {}): HotkeyStatus {
  return {
    dictation: { value: 'Ctrl+/', valid: true },
    ask: { value: 'Ctrl+.', valid: true },
    conflict: false,
    registration_error: null,
    roles: [
      {
        role: 'dictation',
        index: 0,
        display: 'Ctrl+/',
        backend: 'tauriGlobalShortcut',
        valid: true,
        conflictWith: null,
        adapter: 'tauriGlobalShortcut',
        state: 'installed',
        message: null,
        lastError: null,
      },
      {
        role: 'ask',
        index: 0,
        display: 'Ctrl+.',
        backend: 'tauriGlobalShortcut',
        valid: true,
        conflictWith: null,
        adapter: 'tauriGlobalShortcut',
        state: 'installed',
        message: null,
        lastError: null,
      },
    ],
    capability: {
      platform: 'macos',
      sessionType: 'unknown',
      supportsGlobalHotkey: true,
      supportsHoldMode: true,
      supportsReleasedEdge: true,
      supportsSideSpecificModifiers: false,
      requiresAccessibilityPermission: false,
      statusHint: null,
    },
    ...overrides,
  }
}

function resetStore() {
  useAppStore.setState(useAppStore.getInitialState())
}

function seedSavedConfig() {
  const { config } = useAppStore.getState()
  useAppStore.getState().setSavedConfig(config)
}

function renderSettings() {
  return render(<Settings />)
}

// 侧边栏导航按钮：精确匹配 sidebar 内的 <button data-motion="button"> 子元素
function clickSidebarItem(label: string) {
  const spans = screen.getAllByText(label)
  // sidebar button 的直接父链中有 data-motion="button"，且该 button 不含 h2
  const sidebarSpan = spans.find((el) => {
    const btn = el.closest('[data-motion="button"]')
    return btn !== null && btn.querySelector('h2') === null
  })
  const btn = (sidebarSpan ?? spans[0]).closest('[data-motion="button"], button')
  if (btn) fireEvent.click(btn)
  else fireEvent.click(spans[0])
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Tab 切换 — 渲染正确 Pane 内容
// ─────────────────────────────────────────────────────────────────────────────
describe('Settings tab 切换', () => {
  beforeEach(() => {
    resetStore()
    seedSavedConfig()
  })

  it('初始渲染显示简化后的常用设置', () => {
    renderSettings()

    expect(screen.getByText('settings.hotkey')).toBeDefined()
    expect(screen.getByText('settings.dictationHotkey')).toBeDefined()
    expect(screen.getByText('settings.askHotkey')).toBeDefined()
    expect(screen.getByText('settings.translateHotkey')).toBeDefined()
    expect(screen.getByText('settings.dictationMode')).toBeDefined()
    expect(screen.getAllByRole('button', { name: 'settings.shortcutAdd' })).toHaveLength(3)
    expect(screen.queryByText('settings.askAnything')).toBeNull()
    expect(screen.queryByText('settings.askAnythingDesc')).toBeNull()
    expect(screen.getByLabelText('settings.tryAsk')).toBeDefined()
    expect(screen.queryByText('ask.voiceQuestion')).toBeNull()
    expect(screen.getByText('settings.outputMode')).toBeDefined()
    expect(screen.getByText('settings.dictationMode')).toBeDefined()
    expect(screen.queryByText('settings.diagnostics')).toBeNull()
  })

  it('General pane keeps Ask visible and hides low-frequency settings until More is opened', () => {
    renderSettings()

    expect(screen.getByText('settings.hotkey')).toBeDefined()
    expect(screen.getByText('settings.askHotkey')).toBeDefined()
    expect(screen.getByText('settings.outputMode')).toBeDefined()
    expect(screen.queryByText('settings.diagnostics')).toBeNull()
    expect(screen.queryByText('settings.restoreClipboardAfterPaste')).toBeNull()
    expect(screen.queryByText('settings.maxRecordingDuration')).toBeNull()
    expect(screen.queryByText('settings.historyPrivacy')).toBeNull()
    expect(screen.queryByText('settings.launchAtStartup')).toBeNull()

    fireEvent.click(screen.getByText('settings.advancedGeneral'))

    expect(screen.getAllByText('settings.askHotkey')).toHaveLength(1)
    expect(screen.getByText('settings.saveHistory')).toBeDefined()
    expect(screen.getByText('settings.launchAtStartup')).toBeDefined()
    expect(screen.queryByText('settings.diagnostics')).toBeNull()
    expect(screen.queryByText('settings.restoreClipboardAfterPaste')).toBeNull()
    expect(screen.queryByText('settings.maxRecordingDuration')).toBeNull()
    expect(screen.queryByText('settings.historyPrivacy')).toBeNull()
    expect(screen.getByText('settings.hideCapsuleWhenIdle')).toBeDefined()
  })

  it('keeps unsaved drafts dirty after leaving and reopening Settings', () => {
    const firstRender = renderSettings()
    act(() => useAppStore.getState().updateConfig({ auto_start: false }))
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument()

    firstRender.unmount()
    renderSettings()

    expect(screen.getByText('Unsaved changes')).toBeInTheDocument()
    expect(useAppStore.getState().savedConfig?.auto_start).toBe(true)
  })

  it('does not mark an unchanged backend config dirty when object key order differs', () => {
    const { config } = useAppStore.getState()
    const backendOrderedConfig = Object.fromEntries(
      Object.entries(config).reverse(),
    ) as typeof config
    useAppStore.getState().setSavedConfig(backendOrderedConfig)

    renderSettings()

    expect(screen.queryByText('Unsaved changes')).toBeNull()
  })

  it('does not show a Wayland limitation while platform capabilities are still loading', () => {
    useAppStore.getState().setPlatformCapabilities(null)

    renderSettings()

    expect(screen.queryByText('settings.waylandHotkeyLimited')).toBeNull()
  })

  it('General pane starts Ask recording from a lightweight Try Ask entry', async () => {
    renderSettings()

    fireEvent.click(screen.getByLabelText('settings.tryAsk'))

    await waitFor(() => {
      expect(startAskFlow).toHaveBeenCalledTimes(1)
    })
  })

  it('General pane leaves macOS Accessibility to the global permission banner', () => {
    const originalPlatform = window.navigator.platform
    Object.defineProperty(window.navigator, 'platform', {
      value: 'MacIntel',
      configurable: true,
    })
    const mockCheckAccessibilityPermission = vi.mocked(checkAccessibilityPermission)
    mockCheckAccessibilityPermission.mockClear()
    mockCheckAccessibilityPermission.mockResolvedValueOnce(false)

    try {
      renderSettings()

      expect(screen.queryByText('settings.accessibilityPermission')).toBeNull()
      expect(screen.queryByText('settings.accessibilityRequired')).toBeNull()
      expect(screen.queryByText('settings.grantPermission')).toBeNull()
      expect(mockCheckAccessibilityPermission).not.toHaveBeenCalled()
    } finally {
      Object.defineProperty(window.navigator, 'platform', {
        value: originalPlatform,
        configurable: true,
      })
    }
  })

  it('More settings keeps only preference toggles visible', () => {
    renderSettings()
    fireEvent.click(screen.getByText('settings.advancedGeneral'))

    expect(screen.getAllByText('settings.askHotkey')).toHaveLength(1)
    expect(screen.getByText('settings.launchAtStartup')).toBeDefined()
    expect(screen.getByText('settings.saveHistory')).toBeDefined()
    expect(screen.queryByText('settings.startMinimized')).toBeNull()
    expect(screen.getByText('settings.hideCapsuleWhenIdle')).toBeDefined()
    expect(screen.queryByText('settings.outputDetails')).toBeNull()
    expect(screen.queryByText('settings.diagnostics')).toBeNull()
    expect(screen.getByRole('switch', { name: 'settings.launchAtStartup' })).toHaveAttribute(
      'aria-checked',
      'true',
    )

    const historyLabel = screen.getByText('settings.saveHistory')
    fireEvent.click(historyLabel.closest('label') ?? historyLabel)
    expect(useAppStore.getState().config.history_enabled).toBe(false)
  })

  it('General pane shows compact hotkey conflict status', async () => {
    const { getHotkeyStatus } = await import('../../../lib/tauri')
    vi.mocked(getHotkeyStatus).mockResolvedValueOnce(
      mockHotkeyStatus({
        ask: { value: 'Ctrl+/', valid: true },
        conflict: true,
      }),
    )

    renderSettings()

    expect(await screen.findByText('settings.hotkeyConflict')).toBeDefined()
  })

  it('does not duplicate an Accessibility-limited Fn registration failure', () => {
    const originalPlatform = window.navigator.platform
    Object.defineProperty(window.navigator, 'platform', {
      value: 'MacIntel',
      configurable: true,
    })
    useAppStore.getState().setAccessibilityTrusted(false)
    useAppStore
      .getState()
      .setHotkeyRegistrationError(
        'Failed to create macOS native hotkey EventTap; Accessibility permission may be denied',
      )

    try {
      renderSettings()

      expect(screen.queryByText('settings.hotkeyRegistrationFailed')).toBeNull()
    } finally {
      Object.defineProperty(window.navigator, 'platform', {
        value: originalPlatform,
        configurable: true,
      })
    }
  })

  it('keeps unrelated shortcut registration failures visible', () => {
    const originalPlatform = window.navigator.platform
    Object.defineProperty(window.navigator, 'platform', {
      value: 'MacIntel',
      configurable: true,
    })
    useAppStore.getState().setAccessibilityTrusted(false)
    useAppStore.getState().setHotkeyRegistrationError('Shortcut is already registered')

    try {
      renderSettings()

      expect(screen.getByText('settings.hotkeyRegistrationFailed')).toBeDefined()
    } finally {
      Object.defineProperty(window.navigator, 'platform', {
        value: originalPlatform,
        configurable: true,
      })
    }
  })

  it('clears stale shortcut registration failures when current status recovered', async () => {
    const { getHotkeyStatus } = await import('../../../lib/tauri')
    useAppStore.getState().setHotkeyRegistrationError('Shortcut is already registered')
    vi.mocked(getHotkeyStatus).mockResolvedValueOnce(
      mockHotkeyStatus({
        registration_error: null,
      }),
    )

    renderSettings()

    await waitFor(() => {
      expect(useAppStore.getState().hotkeyRegistrationError).toBeNull()
    })
    expect(screen.queryByText('settings.hotkeyRegistrationFailed')).toBeNull()
  })

  it('re-registers failed Fn hotkeys after macOS Accessibility permission is granted', async () => {
    const originalPlatform = window.navigator.platform
    Object.defineProperty(window.navigator, 'platform', {
      value: 'MacIntel',
      configurable: true,
    })
    const { resumeHotkey } = await import('../../../lib/tauri')
    useAppStore.getState().setAccessibilityTrusted(true)
    useAppStore
      .getState()
      .setHotkeyRegistrationError(
        'Failed to create macOS native hotkey EventTap; Accessibility permission may be denied',
      )

    try {
      renderSettings()

      await waitFor(() => {
        expect(resumeHotkey).toHaveBeenCalled()
      })
      expect(useAppStore.getState().hotkeyRegistrationError).toBeNull()
    } finally {
      Object.defineProperty(window.navigator, 'platform', {
        value: originalPlatform,
        configurable: true,
      })
    }
  })

  it('General pane does not expose the optional Ask hotkey disable action', () => {
    renderSettings()

    expect(screen.queryByLabelText('settings.disableAskHotkey')).toBeNull()
    expect(useAppStore.getState().config.ask_hotkey).toBe('Ctrl+.')
  })

  it('renders native single-key dictation hotkeys without marking them invalid', async () => {
    const { getHotkeyStatus } = await import('../../../lib/tauri')
    vi.mocked(getHotkeyStatus).mockResolvedValueOnce(
      mockHotkeyStatus({
        dictation: { value: 'RightAlt', valid: true },
      }),
    )
    useAppStore.getState().updateConfig({ hotkey: 'RightAlt' })
    seedSavedConfig()

    renderSettings()

    expect(await screen.findByText('RightAlt')).toBeDefined()
    expect(screen.queryByText('settings.hotkeyInvalid')).toBeNull()
  })

  it('does not offer Windows RightAlt as a default shortcut chip', async () => {
    const originalPlatform = window.navigator.platform
    Object.defineProperty(window.navigator, 'platform', {
      value: 'Win32',
      configurable: true,
    })
    useAppStore.getState().setPlatformCapabilities({
      os: 'windows',
      sessionType: 'unknown',
      globalHotkeyReliable: true,
      keyboardOutputReliable: true,
      clipboardAutoPasteReliable: true,
    })

    try {
      renderSettings()

      fireEvent.click(screen.getByText('Ctrl+.'))
      expect(screen.queryByRole('button', { name: 'Right Alt' })).toBeNull()
      fireEvent.click(screen.getByText('settings.pressKeyCombination'))

      fireEvent.click(screen.getByText('Ctrl+/'))
      expect(screen.queryByRole('button', { name: 'Right Alt' })).toBeNull()
      expect(useAppStore.getState().config.hotkey).toBe('Ctrl+/')
    } finally {
      Object.defineProperty(window.navigator, 'platform', {
        value: originalPlatform,
        configurable: true,
      })
    }
  })

  it('blocks local Ask hotkey drafts that conflict with Dictation', async () => {
    vi.useFakeTimers()
    try {
      renderSettings()
      fireEvent.click(screen.getByText('Ctrl+.'))
      await act(async () => {
        await Promise.resolve()
      })
      fireEvent.keyDown(window, { key: '/', ctrlKey: true })

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1600)
      })

      expect(useAppStore.getState().config.ask_hotkey).toBe('Ctrl+.')
      expect(screen.getByText('settings.hotkeyConflict')).toBeDefined()
      expect(screen.queryByText('Unsaved changes')).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('blocks local Dictation hotkey drafts that conflict with Ask', async () => {
    vi.useFakeTimers()
    try {
      renderSettings()
      fireEvent.click(screen.getByText('Ctrl+/'))
      await act(async () => {
        await Promise.resolve()
      })
      fireEvent.keyDown(window, { key: '.', ctrlKey: true })

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1600)
      })

      expect(useAppStore.getState().config.hotkey).toBe('Ctrl+/')
      expect(screen.getByText('settings.hotkeyConflict')).toBeDefined()
      expect(screen.queryByText('Unsaved changes')).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('点击 Speech Recognition 后显示 STT provider 字段', () => {
    renderSettings()
    clickSidebarItem('settings.speechRecognition')
    expect(screen.getByText('settings.provider')).toBeDefined()
    // STT pane 含语言选择
    expect(screen.getByText('settings.sttLanguage')).toBeDefined()
  })

  it('点击 AI Polish 后显示 LLM provider 字段', () => {
    renderSettings()
    clickSidebarItem('settings.aiPolish')
    // LLM pane 也含 provider，但还含 enableAiPolish toggle
    expect(screen.getByText('settings.enableAiPolish')).toBeDefined()
    expect(screen.queryByText('settings.askAnything')).toBeNull()
  })

  it('点击 Dictionary 后显示词典输入框 placeholder', () => {
    renderSettings()
    clickSidebarItem('settings.dictionary')
    expect(screen.getByPlaceholderText('dictionary.word')).toBeDefined()
  })

  it('点击 Scenes 后显示本地 scenes 空状态（user=null）', () => {
    renderSettings()
    clickSidebarItem('settings.scenes')
    expect(screen.getByText('scenes.myScenes')).toBeDefined()
    expect(screen.getByText('scenes.noCustomScenes')).toBeDefined()
    expect(screen.getByText('scenes.newScene')).toBeDefined()
    expect(screen.queryByText('scenes.cloudPacks')).toBeNull()
    expect(screen.queryByText('scenes.signInToBrowse')).toBeNull()
  })

  it('点击 About 后显示版本信息区域', () => {
    renderSettings()
    clickSidebarItem('settings.about')
    expect(screen.getByText('settings.openSource')).toBeDefined()
  })

  it('可以在多个 tab 之间来回切换', () => {
    renderSettings()
    clickSidebarItem('settings.aiPolish')
    expect(screen.getByText('settings.enableAiPolish')).toBeDefined()

    clickSidebarItem('settings.general')
    expect(screen.getByText('settings.hotkey')).toBeDefined()
  })

  it('切换到 Scenes 后不会残留上一个设置页内容', () => {
    renderSettings()
    clickSidebarItem('settings.aiPolish')
    expect(screen.getByText('settings.enableAiPolish')).toBeDefined()

    clickSidebarItem('settings.general')
    expect(screen.getByText('settings.hotkey')).toBeDefined()
    expect(screen.getByLabelText('settings.tryAsk')).toBeDefined()

    clickSidebarItem('settings.scenes')

    expect(screen.getByText('scenes.myScenes')).toBeDefined()
    expect(screen.queryByText('settings.askAnything')).toBeNull()
    expect(screen.queryByText('settings.hotkey')).toBeNull()
    expect(screen.queryByText('settings.enableAiPolish')).toBeNull()
  })

  it('切换 tab 后 title bar 更新', () => {
    renderSettings()
    clickSidebarItem('settings.dictionary')
    // title bar 中的 h2 应该显示 settings.dictionary
    const titles = screen.getAllByText('settings.dictionary')
    // 至少出现两次：sidebar nav 和 title bar h2
    expect(titles.length).toBeGreaterThanOrEqual(2)
  })

  it('records macOS Ask hotkey as a local draft without immediate persistence', async () => {
    vi.useFakeTimers()
    try {
      Object.defineProperty(window.navigator, 'platform', {
        value: 'MacIntel',
        configurable: true,
      })
      const { resumeHotkey, updateAskHotkey } = await import('../../../lib/tauri')
      const mockUpdateAskHotkey = vi.mocked(updateAskHotkey)
      const mockResumeHotkey = vi.mocked(resumeHotkey)
      mockUpdateAskHotkey.mockClear()
      mockResumeHotkey.mockClear()
      useAppStore.getState().updateConfig({ ask_hotkey: 'Command+.' })
      seedSavedConfig()

      renderSettings()
      fireEvent.click(screen.getByText('Command+.'))
      await act(async () => {
        await Promise.resolve()
      })
      fireEvent.keyDown(window, { key: ';', metaKey: true })

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1600)
      })

      expect(useAppStore.getState().config.ask_hotkey).toBe('Command+;')
      expect(mockUpdateAskHotkey).not.toHaveBeenCalled()
      expect(mockResumeHotkey).toHaveBeenCalledTimes(1)
      expect(screen.getByText('Unsaved changes')).toBeDefined()
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('Settings Scenes local custom scenes', () => {
  beforeEach(() => {
    resetStore()
    seedSavedConfig()
    vi.mocked(updateConfig).mockClear()
    vi.mocked(listCustomAppMappings).mockReset().mockResolvedValue([])
    vi.mocked(setFamilySceneAssignment).mockReset().mockResolvedValue([])
  })

  it('shows app writing modes with representative app logos and editable scene choices', async () => {
    useAppStore.getState().setConfig({
      ...useAppStore.getState().config,
      custom_scenes: [
        {
          id: 'custom_email',
          name: 'Warm Email',
          description: '',
          prompt_template: 'Use a warm email tone.',
          created_at: '',
          updated_at: '',
        },
      ],
      family_scene_assignments: [{ family: 'email', scene_id: 'custom_email' }],
    })
    seedSavedConfig()

    renderSettings()
    clickSidebarItem('settings.scenes')

    expect(listCustomAppMappings).not.toHaveBeenCalled()
    expect(screen.getByText('scenes.appWritingModes')).toBeInTheDocument()
    expect(screen.getByText('contextFamilies.email')).toBeInTheDocument()
    expect(screen.getByLabelText('Gmail')).toBeInTheDocument()
    expect(screen.getByLabelText('Apple Mail')).toBeInTheDocument()
    expect(screen.getByText('contextFamilies.work_chat')).toBeInTheDocument()
    expect(screen.getByLabelText('Slack')).toBeInTheDocument()
    expect(screen.getByLabelText('Lark')).toBeInTheDocument()

    const emailSelect = screen.getByLabelText('contextFamilies.email scenes.appWritingScene')
    expect(emailSelect).toHaveValue('custom_email')
    const workChatSelect = screen.getByLabelText(
      'contextFamilies.work_chat scenes.appWritingScene',
    ) as HTMLSelectElement
    expect(workChatSelect).toHaveValue('')
    expect(workChatSelect.selectedOptions[0]?.textContent).toBe('scenes.systemModes.work_chat')
    expect(screen.queryByText('scenes.builtInScenes')).not.toBeInTheDocument()
  })

  it('persists app writing mode scene choices without leaving settings dirty', async () => {
    useAppStore.getState().setConfig({
      ...useAppStore.getState().config,
      custom_scenes: [
        {
          id: 'custom_email',
          name: 'Warm Email',
          description: '',
          prompt_template: 'Use a warm email tone.',
          created_at: '',
          updated_at: '',
        },
      ],
    })
    seedSavedConfig()
    const persistedAssignments = [{ family: 'email' as const, scene_id: 'custom_email' }]
    vi.mocked(setFamilySceneAssignment).mockResolvedValue(persistedAssignments)

    renderSettings()
    clickSidebarItem('settings.scenes')

    fireEvent.change(screen.getByLabelText('contextFamilies.email scenes.appWritingScene'), {
      target: { value: 'custom_email' },
    })

    await waitFor(() => {
      expect(setFamilySceneAssignment).toHaveBeenCalledWith('email', 'custom_email')
      expect(useAppStore.getState().config.family_scene_assignments).toEqual(persistedAssignments)
      expect(useAppStore.getState().savedConfig?.family_scene_assignments).toEqual(
        persistedAssignments,
      )
    })
    expect(screen.queryByText('settings.unsavedChanges')).toBeNull()
  })

  it('lets users edit and reset system scenes from My Scenes', async () => {
    renderSettings()
    clickSidebarItem('settings.scenes')

    fireEvent.click(screen.getAllByText('scenes.systemModes.email')[1])
    fireEvent.click(screen.getByText('scenes.edit'))
    expect(screen.queryByLabelText('scenes.sceneName')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('scenes.sceneDescription')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('scenes.promptTemplate'), {
      target: { value: 'Use a warm email body with concise bullets.' },
    })
    fireEvent.click(screen.getAllByText('Save')[0])

    await waitFor(() => expect(vi.mocked(updateConfig)).toHaveBeenCalledTimes(1))
    expect(useAppStore.getState().config.system_scene_overrides).toEqual([
      {
        id: 'system_email',
        prompt_template: 'Use a warm email body with concise bullets.',
      },
    ])
    expect(screen.queryByText('settings.unsavedChanges')).toBeNull()

    fireEvent.click(await screen.findByText('scenes.resetSystemScene'))

    await waitFor(() => expect(vi.mocked(updateConfig)).toHaveBeenCalledTimes(2))
    expect(useAppStore.getState().config.system_scene_overrides).toEqual([])
  })

  it('creates a local scene without exposing global activation', async () => {
    renderSettings()
    clickSidebarItem('settings.scenes')

    fireEvent.click(screen.getByText('scenes.newScene'))
    fireEvent.change(screen.getByLabelText('scenes.sceneName'), {
      target: { value: 'Meeting Notes' },
    })
    fireEvent.change(screen.getByLabelText('scenes.promptTemplate'), {
      target: { value: 'Rewrite as concise meeting notes.' },
    })
    expect(screen.queryByText('scenes.saveAndActivate')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('Save'))

    await waitFor(() => {
      expect(vi.mocked(updateConfig)).toHaveBeenCalledTimes(1)
    })

    const { config, savedConfig } = useAppStore.getState()
    expect(config.custom_scenes).toHaveLength(1)
    expect(config.active_scene).toBeNull()
    expect(savedConfig?.custom_scenes).toHaveLength(1)
    expect(savedConfig?.active_scene).toBeNull()
    expect(screen.queryByText('settings.unsavedChanges')).toBeNull()
  })

  it('persists only scene fields and preserves unrelated unsaved settings', async () => {
    renderSettings()
    act(() => useAppStore.getState().updateConfig({ auto_start: false }))
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument()
    clickSidebarItem('settings.scenes')

    fireEvent.click(screen.getByText('scenes.newScene'))
    fireEvent.change(screen.getByLabelText('scenes.sceneName'), {
      target: { value: 'Focused Notes' },
    })
    fireEvent.change(screen.getByLabelText('scenes.promptTemplate'), {
      target: { value: 'Keep the notes concise.' },
    })
    fireEvent.click(screen.getAllByText('Save')[0])

    await waitFor(() => expect(vi.mocked(updateConfig)).toHaveBeenCalledTimes(1))
    const persistedConfig = vi.mocked(updateConfig).mock.calls[0][0]
    expect(persistedConfig.auto_start).toBe(true)
    expect(useAppStore.getState().config.auto_start).toBe(false)
    expect(useAppStore.getState().savedConfig?.auto_start).toBe(true)
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument()
  })

  it('lets existing users clear a legacy globally active scene', async () => {
    useAppStore.getState().setConfig({
      ...useAppStore.getState().config,
      active_scene: {
        id: 'legacy_scene',
        source: 'custom',
        name: 'Legacy Scene',
        prompt_template: 'Keep using the old scene.',
      },
    })
    seedSavedConfig()

    renderSettings()
    clickSidebarItem('settings.scenes')
    fireEvent.click(screen.getByRole('button', { name: 'scenes.clearActive' }))

    await waitFor(() => expect(useAppStore.getState().config.active_scene).toBeNull())
    expect(useAppStore.getState().savedConfig?.active_scene).toBeNull()
  })

  it('keeps the scene editor open when persistence fails', async () => {
    vi.mocked(updateConfig).mockRejectedValueOnce(new Error('disk full'))
    renderSettings()
    clickSidebarItem('settings.scenes')

    fireEvent.click(screen.getByText('scenes.newScene'))
    fireEvent.change(screen.getByLabelText('scenes.sceneName'), {
      target: { value: 'Meeting Notes' },
    })
    fireEvent.change(screen.getByLabelText('scenes.promptTemplate'), {
      target: { value: 'Keep concise notes.' },
    })
    fireEvent.click(screen.getByText('Save'))

    expect(await screen.findByText('scenes.failedToSave')).toBeInTheDocument()
    expect(screen.getByLabelText('scenes.sceneName')).toHaveValue('Meeting Notes')
    expect(screen.getByLabelText('scenes.promptTemplate')).toHaveValue('Keep concise notes.')
  })

  it('removes family assignments that reference a deleted custom scene', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm')
    useAppStore.getState().setConfig({
      ...useAppStore.getState().config,
      custom_scenes: [
        {
          id: 'custom_email',
          name: 'Warm Email',
          description: '',
          prompt_template: 'Use a warm email tone.',
          created_at: '',
          updated_at: '',
        },
      ],
      family_scene_assignments: [{ family: 'email', scene_id: 'custom_email' }],
    })
    seedSavedConfig()

    renderSettings()
    clickSidebarItem('settings.scenes')
    const sceneName = screen
      .getAllByText('Warm Email')
      .find((element) => element.tagName === 'SPAN')
    expect(sceneName).toBeDefined()
    fireEvent.click(sceneName!.closest('button')!)
    fireEvent.click(screen.getByText('scenes.delete'))

    expect(screen.getByText('scenes.deleteConfirm')).toBeInTheDocument()
    expect(confirmSpy).not.toHaveBeenCalled()
    const deleteActions = screen.getAllByText('scenes.delete')
    fireEvent.click(deleteActions[deleteActions.length - 1])

    await waitFor(() => expect(useAppStore.getState().config.custom_scenes).toEqual([]))
    expect(useAppStore.getState().config.family_scene_assignments).toEqual([])
  })

  it('exports local scenes as a compact JSON file', async () => {
    useAppStore.getState().setConfig({
      ...useAppStore.getState().config,
      custom_scenes: [
        {
          id: 'custom_existing',
          name: 'Support Reply',
          description: 'Reply to support tickets',
          prompt_template: 'Write a concise support reply.',
          created_at: '2026-07-01T00:00:00.000Z',
          updated_at: '2026-07-01T00:00:00.000Z',
        },
      ],
    })
    seedSavedConfig()
    const createObjectUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:scene-export')
    const revokeObjectUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    renderSettings()
    clickSidebarItem('settings.scenes')
    fireEvent.click(screen.getByText('scenes.export'))

    expect(createObjectUrl).toHaveBeenCalledTimes(1)
    expect(click).toHaveBeenCalledTimes(1)

    createObjectUrl.mockRestore()
    revokeObjectUrl.mockRestore()
    click.mockRestore()
  })

  it('imports local scenes from JSON without overwriting existing ids', async () => {
    useAppStore.getState().setConfig({
      ...useAppStore.getState().config,
      custom_scenes: [
        {
          id: 'custom_existing',
          name: 'Existing',
          description: '',
          prompt_template: 'Keep as-is.',
          created_at: '2026-07-01T00:00:00.000Z',
          updated_at: '2026-07-01T00:00:00.000Z',
        },
      ],
    })
    seedSavedConfig()

    renderSettings()
    clickSidebarItem('settings.scenes')

    const file = new File(
      [
        JSON.stringify({
          version: 1,
          scenes: [
            {
              id: 'custom_existing',
              name: 'Imported',
              description: 'Imported scene',
              promptTemplate: 'Rewrite as a crisp note.',
            },
          ],
        }),
      ],
      'scenes.json',
      { type: 'application/json' },
    )

    fireEvent.change(screen.getByLabelText('scenes.import'), {
      target: { files: [file] },
    })

    await waitFor(() => {
      expect(vi.mocked(updateConfig)).toHaveBeenCalledTimes(1)
    })

    const { config, savedConfig } = useAppStore.getState()
    expect(config.custom_scenes).toHaveLength(2)
    expect(config.custom_scenes[0].id).toBe('custom_existing')
    expect(config.custom_scenes[1].id).not.toBe('custom_existing')
    expect(config.custom_scenes[1].name).toBe('Imported')
    expect(savedConfig?.custom_scenes).toHaveLength(2)
    expect(screen.queryByText('settings.unsavedChanges')).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 2. 动画结构 — AnimatePresence wrapper 正常渲染
// ─────────────────────────────────────────────────────────────────────────────
describe('Settings 动画结构', () => {
  beforeEach(() => {
    resetStore()
    seedSavedConfig()
  })

  it('motion wrapper 正常渲染 pane 内容', () => {
    const { container } = renderSettings()
    // 我们的 mock 给 motion 元素打上 data-motion 属性
    expect(container.querySelector('[data-motion]')).not.toBeNull()
  })

  it('切换 tab 后 pane 内容正常更新（无卡死）', () => {
    renderSettings()
    clickSidebarItem('settings.speechRecognition')
    // 仅断言组件没有崩溃，DOM 还在
    expect(document.body).toBeDefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 3. appStore.llmModels — store 层测试
// ─────────────────────────────────────────────────────────────────────────────
describe('appStore.llmModels', () => {
  beforeEach(() => {
    resetStore()
  })

  it('初始值为空数组', () => {
    expect(useAppStore.getState().llmModels).toEqual([])
  })

  it('setLlmModels 正确更新 store', () => {
    useAppStore.getState().setLlmModels(['model-a', 'model-b'])
    expect(useAppStore.getState().llmModels).toEqual(['model-a', 'model-b'])
  })

  it('setLlmModels([]) 可以清空缓存', () => {
    useAppStore.getState().setLlmModels(['model-a'])
    useAppStore.getState().setLlmModels([])
    expect(useAppStore.getState().llmModels).toHaveLength(0)
  })

  it('store 中的 llmModels 不随组件卸载而丢失', () => {
    useAppStore.getState().setLlmModels(['gpt-4o', 'claude-3'])
    // 模拟"切走再切回"：zustand store 不依赖组件生命周期
    const { unmount } = render(<div />)
    unmount()
    expect(useAppStore.getState().llmModels).toEqual(['gpt-4o', 'claude-3'])
  })

  it('setLlmModels 替换而不是合并', () => {
    useAppStore.getState().setLlmModels(['a', 'b', 'c'])
    useAppStore.getState().setLlmModels(['x'])
    expect(useAppStore.getState().llmModels).toEqual(['x'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 4. LlmPane — provider 切换时清空 models 缓存
// ─────────────────────────────────────────────────────────────────────────────
describe('LlmPane provider 切换清空 models', () => {
  beforeEach(() => {
    resetStore()
    seedSavedConfig()
  })

  it('切换 provider 时 store 中的 llmModels 被清空', async () => {
    useAppStore.getState().setLlmModels(['model-x', 'model-y'])

    renderSettings()
    clickSidebarItem('settings.aiPolish')

    // provider select 是当前 pane 中的第一个 combobox
    const selects = screen.getAllByRole('combobox')
    const providerSelect = selects[0]

    await act(async () => {
      fireEvent.change(providerSelect, { target: { value: 'openai' } })
    })

    expect(useAppStore.getState().llmModels).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 5. LlmPane useEffect — 已有缓存时不重复 fetch
// ─────────────────────────────────────────────────────────────────────────────
describe('LlmPane models 缓存：已有缓存时跳过 fetch', () => {
  beforeEach(() => {
    resetStore()
    seedSavedConfig()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('llmModels 已有内容时不触发 fetchLlmModels', async () => {
    const { fetchLlmModels } = await import('../../../lib/tauri')
    const mockFetch = vi.mocked(fetchLlmModels)
    mockFetch.mockClear()

    useAppStore.getState().setLlmModels(['cached-model'])
    useAppStore.getState().updateConfig({
      llm_api_key: 'sk-test',
      llm_base_url: 'https://api.openai.com/v1',
      llm_provider: 'openai',
    })

    renderSettings()
    clickSidebarItem('settings.aiPolish')

    await act(async () => {
      vi.runAllTimers()
    })

    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('llmModels 为空且有 api key/url 时触发 fetchLlmModels', async () => {
    const { fetchLlmModels } = await import('../../../lib/tauri')
    const mockFetch = vi.mocked(fetchLlmModels)
    mockFetch.mockClear()

    useAppStore.getState().setLlmModels([])
    useAppStore.getState().updateConfig({
      llm_api_key: 'sk-test',
      llm_base_url: 'https://api.openai.com/v1',
      llm_provider: 'openai',
    })

    renderSettings()
    clickSidebarItem('settings.aiPolish')

    // runAllTimersAsync 同时推进 fake timer 并 flush 所有 pending microtasks/promises
    await act(async () => {
      await vi.runAllTimersAsync()
    })

    expect(mockFetch).toHaveBeenCalledTimes(1)
  })

  it('fetchLlmModels 完成后 store 中 llmModels 被更新', async () => {
    const { fetchLlmModels } = await import('../../../lib/tauri')
    vi.mocked(fetchLlmModels).mockResolvedValue(['gpt-4o', 'gpt-3.5-turbo'])

    useAppStore.getState().setLlmModels([])
    useAppStore.getState().updateConfig({
      llm_api_key: 'sk-test',
      llm_base_url: 'https://api.openai.com/v1',
      llm_provider: 'openai',
    })

    renderSettings()
    clickSidebarItem('settings.aiPolish')

    await act(async () => {
      await vi.runAllTimersAsync()
    })

    expect(useAppStore.getState().llmModels).toEqual(['gpt-4o', 'gpt-3.5-turbo'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 6. DirtyBar — 配置变更后出现，Reset 后消失
// ─────────────────────────────────────────────────────────────────────────────
describe('DirtyBar 行为', () => {
  beforeEach(() => {
    resetStore()
    seedSavedConfig()
    vi.mocked(updateConfig).mockReset()
    vi.mocked(updateConfig).mockResolvedValue(undefined)
    vi.mocked(getConfig).mockReset()
    vi.mocked(getConfig).mockResolvedValue(useAppStore.getState().config)
    vi.mocked(getHotkeyRegistrationError).mockReset()
    vi.mocked(getHotkeyRegistrationError).mockResolvedValue(null)
    vi.mocked(setAutoStart).mockReset()
    vi.mocked(setAutoStart).mockResolvedValue(undefined)
    vi.mocked(toast).mockClear()
  })

  it('初始状态下 DirtyBar 不显示', () => {
    renderSettings()
    expect(screen.queryByText('Unsaved changes')).toBeNull()
  })

  it('修改 config 后 DirtyBar 出现', async () => {
    renderSettings()
    act(() => {
      useAppStore.getState().updateConfig({ theme: 'dark' })
    })
    await waitFor(() => {
      expect(screen.getByText('Unsaved changes')).toBeDefined()
    })
  })

  it('点击 Reset 后 DirtyBar 消失', async () => {
    renderSettings()
    act(() => {
      useAppStore.getState().updateConfig({ theme: 'dark' })
    })
    await waitFor(() => {
      expect(screen.getByText('Unsaved changes')).toBeDefined()
    })

    fireEvent.click(screen.getByText('Reset'))

    await waitFor(() => {
      expect(screen.queryByText('Unsaved changes')).toBeNull()
    })
  })

  it('DirtyBar 显示 Save 和 Reset 两个按钮', async () => {
    renderSettings()
    act(() => {
      useAppStore.getState().updateConfig({ theme: 'dark' })
    })
    await waitFor(() => {
      expect(screen.getByText('Save')).toBeDefined()
      expect(screen.getByText('Reset')).toBeDefined()
    })
  })

  it('persisted capsule visibility patch does not erase unrelated dirty settings', async () => {
    renderSettings()

    act(() => {
      useAppStore.getState().updateConfig({ theme: 'dark' })
      useAppStore.getState().applyPersistedConfigPatch({ capsule_auto_hide: true })
    })

    expect(useAppStore.getState().config.theme).toBe('dark')
    expect(useAppStore.getState().config.capsule_auto_hide).toBe(true)
  })

  it('保存失败后从后端配置恢复，避免 UI 与 backend 分叉', async () => {
    const backendConfig = {
      ...useAppStore.getState().config,
      theme: 'system' as const,
    }
    vi.mocked(updateConfig).mockRejectedValueOnce(new Error('Shortcut registration failed'))
    vi.mocked(getConfig).mockResolvedValueOnce(backendConfig)

    renderSettings()
    act(() => {
      useAppStore.getState().updateConfig({ theme: 'dark' })
    })
    await waitFor(() => {
      expect(screen.getByText('Unsaved changes')).toBeDefined()
    })

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(vi.mocked(getConfig)).toHaveBeenCalledTimes(1)
    })
    expect(useAppStore.getState().config.theme).toBe('system')
    expect(useAppStore.getState().savedConfig?.theme).toBe('system')
    expect(toast).toHaveBeenCalledWith('Shortcut registration failed', 'error')
    expect(screen.queryByText('Unsaved changes')).toBeNull()
  })

  it('保存失败后刷新后端 hotkey 注册错误状态', async () => {
    const backendConfig = {
      ...useAppStore.getState().config,
      hotkey: 'Ctrl+/',
    }
    vi.mocked(updateConfig).mockRejectedValueOnce(new Error('Shortcut registration failed'))
    vi.mocked(getConfig).mockResolvedValueOnce(backendConfig)
    vi.mocked(getHotkeyRegistrationError).mockResolvedValueOnce('Shortcut registration failed')

    renderSettings()
    act(() => {
      useAppStore.getState().updateConfig({ hotkey: 'Ctrl+Shift+;' })
    })
    await waitFor(() => {
      expect(screen.getByText('Unsaved changes')).toBeDefined()
    })

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(useAppStore.getState().hotkeyRegistrationError).toBe('Shortcut registration failed')
    })
  })

  it('开机启动系统设置失败时不保存 config，并恢复后端真值', async () => {
    const backendConfig = {
      ...useAppStore.getState().config,
      auto_start: true,
    }
    vi.mocked(setAutoStart).mockRejectedValueOnce(new Error('Login item failed'))
    vi.mocked(getConfig).mockResolvedValueOnce(backendConfig)

    renderSettings()
    act(() => {
      useAppStore.getState().updateConfig({ auto_start: false })
    })
    await waitFor(() => {
      expect(screen.getByText('Unsaved changes')).toBeDefined()
    })

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(vi.mocked(setAutoStart)).toHaveBeenCalledWith(false)
    })
    expect(vi.mocked(updateConfig)).not.toHaveBeenCalled()
    expect(useAppStore.getState().config.auto_start).toBe(true)
    expect(useAppStore.getState().savedConfig?.auto_start).toBe(true)
    expect(toast).toHaveBeenCalledWith('Login item failed', 'error')
  })

  it('config 保存失败时回滚已应用的开机启动系统设置', async () => {
    const backendConfig = {
      ...useAppStore.getState().config,
      auto_start: true,
    }
    vi.mocked(updateConfig).mockRejectedValueOnce(new Error('Shortcut registration failed'))
    vi.mocked(getConfig).mockResolvedValueOnce(backendConfig)

    renderSettings()
    act(() => {
      useAppStore.getState().updateConfig({ auto_start: false })
    })
    await waitFor(() => {
      expect(screen.getByText('Unsaved changes')).toBeDefined()
    })

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(vi.mocked(setAutoStart)).toHaveBeenCalledWith(false)
    })
    await waitFor(() => {
      expect(vi.mocked(setAutoStart)).toHaveBeenCalledWith(true)
    })
    expect(vi.mocked(setAutoStart).mock.calls).toEqual([[false], [true]])
    expect(useAppStore.getState().config.auto_start).toBe(true)
    expect(useAppStore.getState().savedConfig?.auto_start).toBe(true)
    expect(toast).toHaveBeenCalledWith('Shortcut registration failed', 'error')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 7. appStore getInitialState — llmModels 包含在初始状态中
// ─────────────────────────────────────────────────────────────────────────────
describe('appStore getInitialState 包含 llmModels', () => {
  it('getInitialState().llmModels 为空数组', () => {
    const initial = useAppStore.getInitialState()
    expect(initial.llmModels).toEqual([])
  })

  it('setState(getInitialState()) 后 llmModels 恢复为空', () => {
    useAppStore.getState().setLlmModels(['stale-model'])
    useAppStore.setState(useAppStore.getInitialState())
    expect(useAppStore.getState().llmModels).toEqual([])
  })

  it('getInitialState 不改变 llmModels 以外的字段', () => {
    const initial = useAppStore.getInitialState()
    expect(initial.config.hotkey).toBe('Ctrl+/')
    expect(initial.pipelineState).toBe('idle')
    expect(initial.dictionary).toEqual([])
  })
})

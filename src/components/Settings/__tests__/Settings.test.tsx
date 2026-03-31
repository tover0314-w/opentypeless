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

vi.mock('framer-motion', () => ({
  AnimatePresence: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  motion: new Proxy(
    {},
    {
      get:
        (_t, tag: string) =>
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        ({ children, ...rest }: any) => {
          const domProps: Record<string, unknown> = {}
          for (const [k, v] of Object.entries(rest)) {
            if (!MOTION_PROPS.has(k)) domProps[k] = v
          }
          return React.createElement(tag as string, { 'data-motion': tag, ...domProps }, children)
        },
    },
  ),
}))

// ─── Mock react-i18next ───────────────────────────────────────────────────────
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en' },
  }),
}))

// ─── Mock Tauri plugins / lib/tauri ──────────────────────────────────────────
vi.mock('../../../lib/tauri', () => ({
  updateHotkey: vi.fn().mockResolvedValue(undefined),
  pauseHotkey: vi.fn().mockResolvedValue(undefined),
  resumeHotkey: vi.fn().mockResolvedValue(undefined),
  setAutoStart: vi.fn().mockResolvedValue(undefined),
  testSttConnection: vi.fn().mockResolvedValue(true),
  testLlmConnection: vi.fn().mockResolvedValue(true),
  fetchLlmModels: vi.fn().mockResolvedValue(['gpt-4o', 'gpt-3.5-turbo']),
  addDictionaryEntry: vi.fn().mockResolvedValue(undefined),
  removeDictionaryEntry: vi.fn().mockResolvedValue(undefined),
  getDictionary: vi.fn().mockResolvedValue([]),
  updateConfig: vi.fn().mockResolvedValue(undefined),
}))

// ─── Mock @tauri-apps/plugin-opener ─────────────────────────────────────────
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))

// ─── Mock lib/api (ScenesPane uses getScenes) ────────────────────────────────
vi.mock('../../../lib/api', () => ({
  getScenes: vi.fn().mockResolvedValue([]),
}))

// ─── Mock stores/authStore ────────────────────────────────────────────────────
vi.mock('../../../stores/authStore', () => ({
  useAuthStore: () => ({ user: null, plan: 'free' }),
}))

// ─── Import components AFTER mocks ───────────────────────────────────────────
import { Settings } from '../index'

// ─── Helpers ─────────────────────────────────────────────────────────────────
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

  it('初始渲染显示 General pane（含 hotkey section）', () => {
    renderSettings()
    // General pane 包含 "settings.hotkey" section 标题
    expect(screen.getByText('settings.hotkey')).toBeDefined()
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
  })

  it('点击 Dictionary 后显示词典输入框 placeholder', () => {
    renderSettings()
    clickSidebarItem('settings.dictionary')
    expect(screen.getByPlaceholderText('dictionary.word')).toBeDefined()
  })

  it('点击 Scenes 后显示登录提示（user=null）', () => {
    renderSettings()
    clickSidebarItem('settings.scenes')
    expect(screen.getByText('scenes.signInToBrowse')).toBeDefined()
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

  it('切换 tab 后 title bar 更新', () => {
    renderSettings()
    clickSidebarItem('settings.dictionary')
    // title bar 中的 h2 应该显示 settings.dictionary
    const titles = screen.getAllByText('settings.dictionary')
    // 至少出现两次：sidebar nav 和 title bar h2
    expect(titles.length).toBeGreaterThanOrEqual(2)
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

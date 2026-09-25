import { createElement } from 'react'
import { act, cleanup, render, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  getCapsuleBottomCenterPosition,
  getCapsuleFocusable,
  getCapsuleRecoveryPosition,
  getCapsuleVisibility,
  isCapsuleVisibleOnAnyMonitor,
  useCapsuleResize,
} from '../useCapsuleResize'
import { useAppStore } from '../../stores/appStore'

const windowApiMocks = vi.hoisted(() => ({
  getCurrentWindow: vi.fn(),
  availableMonitors: vi.fn(),
  currentMonitor: vi.fn(),
  primaryMonitor: vi.fn(),
  setFocusable: vi.fn(),
  setSize: vi.fn(),
  setPosition: vi.fn(),
  outerPosition: vi.fn(),
  outerSize: vi.fn(),
  scaleFactor: vi.fn(),
  show: vi.fn(),
  hide: vi.fn(),
}))

vi.mock('@tauri-apps/api/window', () => {
  class LogicalSize {
    constructor(
      public width: number,
      public height: number,
    ) {}
  }

  class PhysicalPosition {
    constructor(
      public x: number,
      public y: number,
    ) {}
  }

  return {
    getCurrentWindow: windowApiMocks.getCurrentWindow,
    availableMonitors: windowApiMocks.availableMonitors,
    currentMonitor: windowApiMocks.currentMonitor,
    primaryMonitor: windowApiMocks.primaryMonitor,
    LogicalSize,
    PhysicalPosition,
  }
})

const logicalCapsuleSize = { width: 224, height: 60 }

describe('getCapsuleVisibility', () => {
  it('hides idle capsule when auto-hide is enabled', () => {
    expect(
      getCapsuleVisibility({
        capsuleAutoHide: true,
        contextMenuOpen: false,
        capsuleExpanded: false,
        hasError: false,
        pipelineState: 'idle',
      }),
    ).toBe(false)
  })

  it('shows idle capsule when an error appears', () => {
    expect(
      getCapsuleVisibility({
        capsuleAutoHide: true,
        contextMenuOpen: false,
        capsuleExpanded: false,
        hasError: true,
        pipelineState: 'idle',
      }),
    ).toBe(true)
  })

  it('shows active capsule while recording', () => {
    expect(
      getCapsuleVisibility({
        capsuleAutoHide: true,
        contextMenuOpen: false,
        capsuleExpanded: false,
        hasError: false,
        pipelineState: 'recording',
      }),
    ).toBe(true)
  })

  it('keeps capsule visible while preparing even when auto-hide is enabled', () => {
    expect(
      getCapsuleVisibility({
        capsuleAutoHide: true,
        contextMenuOpen: false,
        capsuleExpanded: false,
        hasError: false,
        pipelineState: 'preparing',
      }),
    ).toBe(true)
  })

  it('keeps capsule visible while Ask is recording', () => {
    expect(
      getCapsuleVisibility({
        capsuleAutoHide: true,
        contextMenuOpen: false,
        capsuleExpanded: false,
        hasError: false,
        pipelineState: 'ask_recording',
      }),
    ).toBe(true)
  })

  it('shows idle capsule while the context menu is open', () => {
    expect(
      getCapsuleVisibility({
        capsuleAutoHide: true,
        contextMenuOpen: true,
        capsuleExpanded: false,
        hasError: false,
        pipelineState: 'idle',
      }),
    ).toBe(true)
  })

  it('keeps the capsule overlay from stealing keyboard output focus', () => {
    expect(getCapsuleFocusable()).toBe(false)
  })
})

describe('capsule monitor geometry', () => {
  it.each([
    {
      name: 'primary monitor',
      workArea: { position: { x: 0, y: 0 }, size: { width: 1920, height: 1040 } },
      expected: { x: 848, y: 900 },
    },
    {
      name: 'monitor to the right',
      workArea: { position: { x: 3440, y: 0 }, size: { width: 1920, height: 1040 } },
      expected: { x: 4288, y: 900 },
    },
    {
      name: 'monitor to the left',
      workArea: { position: { x: -1920, y: 0 }, size: { width: 1920, height: 1040 } },
      expected: { x: -1072, y: 900 },
    },
    {
      name: 'monitor above',
      workArea: { position: { x: 0, y: -1080 }, size: { width: 1920, height: 1040 } },
      expected: { x: 848, y: -180 },
    },
  ])('uses the global work-area origin for the $name', ({ workArea, expected }) => {
    expect(
      getCapsuleBottomCenterPosition({ scaleFactor: 1, workArea }, logicalCapsuleSize),
    ).toEqual(expected)
  })

  it('uses physical pixels for mixed-DPI monitors', () => {
    expect(
      getCapsuleBottomCenterPosition(
        {
          scaleFactor: 1.5,
          workArea: { position: { x: 1920, y: 100 }, size: { width: 2560, height: 1400 } },
        },
        logicalCapsuleSize,
      ),
    ).toEqual({ x: 3032, y: 1290 })
  })

  it('supports a 200% scale factor without converting the global origin', () => {
    expect(
      getCapsuleBottomCenterPosition(
        {
          scaleFactor: 2,
          workArea: { position: { x: -3840, y: -200 }, size: { width: 3840, height: 2080 } },
        },
        logicalCapsuleSize,
      ),
    ).toEqual({ x: -2144, y: 1600 })
  })

  it('uses the work area rather than placing the capsule behind the taskbar', () => {
    expect(
      getCapsuleBottomCenterPosition(
        {
          scaleFactor: 1,
          workArea: { position: { x: 0, y: 40 }, size: { width: 1920, height: 1000 } },
        },
        logicalCapsuleSize,
      ),
    ).toEqual({ x: 848, y: 900 })
  })

  it('preserves a capsule that is even partially visible on any monitor', () => {
    const monitorBounds = [
      { position: { x: 0, y: 0 }, size: { width: 1920, height: 1040 } },
      { position: { x: 1920, y: 0 }, size: { width: 1920, height: 1040 } },
    ]

    expect(
      isCapsuleVisibleOnAnyMonitor({ x: 3830, y: 900, width: 224, height: 60 }, monitorBounds),
    ).toBe(true)
  })

  it('reports a capsule as off-screen when it touches no monitor', () => {
    const monitorBounds = [
      { position: { x: -1920, y: 0 }, size: { width: 1920, height: 1040 } },
      { position: { x: 0, y: 0 }, size: { width: 1920, height: 1040 } },
    ]

    expect(
      isCapsuleVisibleOnAnyMonitor({ x: 2500, y: 900, width: 224, height: 60 }, monitorBounds),
    ).toBe(false)
  })

  it('does not recover a user-positioned capsule that remains partially visible', () => {
    const monitors = [
      {
        position: { x: 0, y: 0 },
        size: { width: 1920, height: 1080 },
        scaleFactor: 1,
        workArea: { position: { x: 0, y: 0 }, size: { width: 1920, height: 1040 } },
      },
      {
        position: { x: 1920, y: 0 },
        size: { width: 2560, height: 1440 },
        scaleFactor: 1.5,
        workArea: { position: { x: 1920, y: 0 }, size: { width: 2560, height: 1400 } },
      },
    ]

    expect(
      getCapsuleRecoveryPosition(
        { x: 4400, y: 1200, width: 336, height: 90 },
        monitors,
        monitors[0],
        logicalCapsuleSize,
      ),
    ).toBeNull()
  })

  it('does not recover a capsule visible only in a taskbar or Dock reserved area', () => {
    const monitors = [
      {
        position: { x: 0, y: 0 },
        size: { width: 1920, height: 1080 },
        scaleFactor: 1,
        workArea: { position: { x: 0, y: 0 }, size: { width: 1920, height: 1040 } },
      },
    ]

    expect(
      getCapsuleRecoveryPosition(
        { x: 848, y: 1050, width: 224, height: 60 },
        monitors,
        monitors[0],
        logicalCapsuleSize,
      ),
    ).toBeNull()
  })

  it('recovers a fully off-screen capsule to the primary work area', () => {
    const monitors = [
      {
        position: { x: 0, y: 0 },
        size: { width: 1920, height: 1080 },
        scaleFactor: 1,
        workArea: { position: { x: 0, y: 0 }, size: { width: 1920, height: 1040 } },
      },
      {
        position: { x: 1920, y: 0 },
        size: { width: 2560, height: 1440 },
        scaleFactor: 1.5,
        workArea: { position: { x: 1920, y: 0 }, size: { width: 2560, height: 1400 } },
      },
    ]

    expect(
      getCapsuleRecoveryPosition(
        { x: 5200, y: 1200, width: 336, height: 90 },
        monitors,
        monitors[0],
        logicalCapsuleSize,
      ),
    ).toEqual({ x: 848, y: 900 })
  })
})

function HookHarness() {
  useCapsuleResize()
  return null
}

describe('useCapsuleResize async updates', () => {
  const monitor = {
    position: { x: 0, y: 0 },
    size: { width: 1920, height: 1080 },
    scaleFactor: 1,
    workArea: { position: { x: 0, y: 0 }, size: { width: 1920, height: 1040 } },
  }

  beforeEach(() => {
    useAppStore.setState((state) => ({
      pipelineState: 'idle',
      capsuleExpanded: false,
      pipelineError: null,
      contextMenuOpen: false,
      contextMenuReady: false,
      translationTargetMenuOpen: false,
      config: { ...state.config, capsule_auto_hide: true },
    }))

    windowApiMocks.getCurrentWindow.mockReset().mockReturnValue({
      setFocusable: windowApiMocks.setFocusable,
      setSize: windowApiMocks.setSize,
      setPosition: windowApiMocks.setPosition,
      outerPosition: windowApiMocks.outerPosition,
      outerSize: windowApiMocks.outerSize,
      scaleFactor: windowApiMocks.scaleFactor,
      show: windowApiMocks.show,
      hide: windowApiMocks.hide,
    })
    windowApiMocks.availableMonitors.mockReset().mockResolvedValue([monitor])
    windowApiMocks.currentMonitor.mockReset().mockResolvedValue(monitor)
    windowApiMocks.primaryMonitor.mockReset().mockResolvedValue(monitor)
    windowApiMocks.setFocusable.mockReset().mockResolvedValue(undefined)
    windowApiMocks.setSize.mockReset().mockResolvedValue(undefined)
    windowApiMocks.setPosition.mockReset().mockResolvedValue(undefined)
    windowApiMocks.outerPosition.mockReset().mockResolvedValue({ x: 0, y: 0 })
    windowApiMocks.outerSize.mockReset().mockResolvedValue({ width: 60, height: 60 })
    windowApiMocks.scaleFactor.mockReset().mockResolvedValue(1)
    windowApiMocks.show.mockReset().mockResolvedValue(undefined)
    windowApiMocks.hide.mockReset().mockResolvedValue(undefined)
  })

  afterEach(() => {
    cleanup()
  })

  it('prevents a superseded async resize from overwriting the latest window state', async () => {
    render(createElement(HookHarness))

    await waitFor(() => {
      expect(windowApiMocks.hide).toHaveBeenCalledTimes(1)
    })

    windowApiMocks.currentMonitor.mockReset()
    windowApiMocks.setSize.mockClear()
    windowApiMocks.setPosition.mockClear()
    windowApiMocks.show.mockClear()
    windowApiMocks.hide.mockClear()

    let resolveFirstMonitor: (value: typeof monitor) => void = () => {}
    const delayedFirstMonitor = new Promise<typeof monitor>((resolve) => {
      resolveFirstMonitor = resolve
    })
    windowApiMocks.currentMonitor
      .mockReset()
      .mockImplementationOnce(() => delayedFirstMonitor)
      .mockResolvedValue(monitor)

    act(() => {
      useAppStore.setState({ contextMenuOpen: true })
    })

    await waitFor(() => {
      expect(windowApiMocks.currentMonitor).toHaveBeenCalledTimes(1)
    })

    act(() => {
      useAppStore.setState({ contextMenuOpen: false })
    })

    await waitFor(() => {
      expect(windowApiMocks.setPosition).toHaveBeenCalledTimes(1)
      expect(windowApiMocks.hide).toHaveBeenCalledTimes(1)
    })

    await act(async () => {
      resolveFirstMonitor(monitor)
      await delayedFirstMonitor
    })

    expect(windowApiMocks.setSize.mock.calls.map(([size]) => [size.width, size.height])).toEqual([
      [60, 60],
    ])
    expect(windowApiMocks.setPosition).toHaveBeenCalledTimes(1)
    expect(windowApiMocks.show).not.toHaveBeenCalled()
    expect(windowApiMocks.hide).toHaveBeenCalledTimes(1)
    expect(useAppStore.getState().contextMenuReady).toBe(false)

    windowApiMocks.outerPosition.mockResolvedValue({ x: 100, y: 100 })
    windowApiMocks.outerSize.mockResolvedValue(null)
    windowApiMocks.setPosition.mockClear()

    act(() => {
      useAppStore.setState({ pipelineState: 'recording' })
    })

    await waitFor(() => {
      expect(windowApiMocks.setPosition).toHaveBeenCalledWith(expect.objectContaining({ y: 100 }))
    })
  })
})

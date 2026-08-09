import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AboutPane } from '../AboutPane'

const mocks = vi.hoisted(() => ({
  invoke: vi.fn().mockResolvedValue(undefined),
  openUrl: vi.fn().mockResolvedValue(undefined),
  toast: vi.fn(),
  updateConfig: vi.fn(),
  changeLanguage: vi.fn(),
}))

vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: mocks.openUrl }))
vi.mock('../../../components/toast-service', () => ({ toast: mocks.toast }))
vi.mock('../../../i18n', () => ({
  default: {
    language: 'en',
    changeLanguage: mocks.changeLanguage,
  },
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))
vi.mock('../../../stores/appStore', () => {
  const state = {
    config: { ui_language: 'en' },
    updateConfig: mocks.updateConfig,
  }
  return {
    useAppStore: (selector: (value: typeof state) => unknown) => selector(state),
  }
})

function clickRow(label: string) {
  const button = screen.getByText(label).closest('button')
  expect(button).not.toBeNull()
  fireEvent.click(button!)
}

describe('AboutPane legal documents', () => {
  afterEach(cleanup)

  beforeEach(() => {
    mocks.invoke.mockReset().mockResolvedValue(undefined)
    mocks.openUrl.mockReset().mockResolvedValue(undefined)
    mocks.toast.mockReset()
  })

  it('opens every bundled legal document through the fixed-document Tauri command', async () => {
    render(<AboutPane />)

    clickRow('settings.license')
    clickRow('settings.thirdPartyNotices')
    clickRow('settings.dependencyInventory')
    clickRow('settings.thirdPartyLicenses')

    await waitFor(() => {
      expect(mocks.invoke.mock.calls).toEqual([
        ['open_legal_document', { document: 'projectLicense' }],
        ['open_legal_document', { document: 'thirdPartyNotices' }],
        ['open_legal_document', { document: 'dependencyInventory' }],
        ['open_legal_document', { document: 'thirdPartyLicenses' }],
      ])
    })
    expect(mocks.openUrl).not.toHaveBeenCalled()
  })

  it('keeps GitHub as an online link and reports a local open failure', async () => {
    mocks.invoke.mockRejectedValueOnce(new Error('missing resource'))
    render(<AboutPane />)

    clickRow('settings.thirdPartyNotices')
    await waitFor(() => {
      expect(mocks.toast).toHaveBeenCalledWith('settings.openLegalDocumentFailed', 'error')
    })

    clickRow('settings.github')
    expect(mocks.openUrl).toHaveBeenCalledWith('https://github.com/dengxuezhao/opentypeless')
  })
})

import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { SettingsControlGroup, SettingsRow, SettingsSection } from '../shared/SettingsRow'

afterEach(cleanup)

describe('shared settings layout', () => {
  it('does not reserve an action column for a field without an action', () => {
    render(
      <SettingsControlGroup action={false}>
        <input aria-label="Base URL" />
      </SettingsControlGroup>,
    )
    const group = screen.getByRole('textbox', { name: 'Base URL' }).parentElement?.parentElement
    expect(group).toHaveClass('settings-control-group-single')
    expect(group?.children).toHaveLength(1)
  })

  it('uses one responsive field and control layout within each section', () => {
    render(
      <SettingsSection title="Speech recognition">
        <SettingsRow title="Provider" description="Choose a service">
          <button type="button">Service</button>
        </SettingsRow>
      </SettingsSection>,
    )
    expect(screen.getByRole('region', { name: 'Speech recognition' })).toHaveClass(
      'settings-section',
    )
    expect(screen.getByText('Provider').parentElement?.parentElement).toHaveClass('settings-row')
    expect(screen.getByRole('button', { name: 'Service' }).parentElement).toHaveClass(
      'settings-row-control',
    )
  })

  it('keeps explicitly stacked settings separate from two-column settings', () => {
    render(
      <SettingsRow title="Instructions" stacked>
        <textarea aria-label="Instructions" />
      </SettingsRow>,
    )
    expect(screen.getByText('Instructions').parentElement?.parentElement).toHaveClass(
      'settings-row-stacked',
    )
  })

  it('gives an input and its action independent shrink-safe slots', () => {
    render(
      <SettingsControlGroup action={<button type="button">Test</button>}>
        <input aria-label="API key" />
      </SettingsControlGroup>,
    )
    const inputSlot = screen.getByRole('textbox', { name: 'API key' }).parentElement
    expect(inputSlot).toHaveClass('settings-control-input')
    expect(inputSlot?.parentElement).toHaveClass('settings-control-group')
    expect(screen.getByRole('button', { name: 'Test' }).parentElement).toHaveClass(
      'settings-control-action',
    )
  })
})

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseOption, openSelect, selectedValue } from '../../shared/__tests__/selectTestUtils'
import { AppStyleMappingDialog } from '../AppStyleMappingDialog'
import { ManageAppMappingsDialog } from '../ManageAppMappingsDialog'
import * as tauri from '../../../lib/tauri'

vi.mock('../../../lib/tauri')

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) =>
      ({
        'settings.appStyleDialogTitle': 'Writing style for this app',
        'settings.editAppMappingTitle': 'Edit app writing style',
        'settings.mappingMatcherWeb': 'Exact website host',
        'settings.mappingMatcherNative': 'Desktop app',
        'settings.mappingLabel': 'Name',
        'settings.mappingFamily': 'Context family',
        'settings.mappingScene': 'Writing scene',
        'settings.mappingNoScene': 'Automatic',
        'settings.mappingSave': 'Save',
        'settings.mappingCancel': 'Cancel',
        'settings.manageAppMappingsTitle': 'Manage app mappings',
        'settings.mappingEnabled': 'Enabled',
        'settings.mappingEdit': 'Edit',
        'settings.mappingDelete': 'Delete',
        'settings.mappingReset': 'Reset to automatic',
        'settings.mappingResetConfirm': 'Delete all device-only app mappings?',
        'settings.mappingResetConfirmAction': 'Reset mappings',
        'settings.mappingNoMappings': 'No custom app mappings',
        'contextFamilies.email': 'Email',
        'contextFamilies.work_chat': 'Work chat',
        'contextFamilies.document': 'Document',
        'contextFamilies.general': 'General',
        'scenes.builtin.cleanDictation.name': 'Clean Dictation',
        'scenes.builtin.professionalEmail.name': 'Professional Email',
      })[key] || key,
  }),
}))

const candidate: tauri.MappingCandidateView = {
  generation: 9,
  matcherType: 'exact_web_host',
  displayValue: 'docs.example.com',
  suggestedLabel: 'docs.example.com',
  currentFamily: 'document',
  iconKey: 'general',
}

const context = {
  profileId: 'general.browser',
  family: 'document' as const,
  appLabel: 'Docs',
  iconKey: 'general',
  overrideId: null,
}

const config = {
  custom_scenes: [
    {
      id: 'custom_focus',
      name: 'Focus',
      description: '',
      prompt_template: 'Use short bullets.',
      created_at: '',
      updated_at: '',
    },
  ],
  family_scene_assignments: [],
}

describe('AppStyleMappingDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(tauri.saveCustomAppMapping).mockResolvedValue({
      id: 'mapping-1',
      label: 'Docs',
      matcherType: 'exact_web_host',
      displayValue: 'docs.example.com',
      family: 'document',
      sceneId: null,
      enabled: true,
      iconKey: 'general',
    })
    vi.mocked(tauri.updateCustomAppMapping).mockResolvedValue({
      id: 'mapping-1',
      label: 'Docs',
      matcherType: 'exact_web_host',
      displayValue: 'docs.example.com',
      family: 'document',
      sceneId: null,
      enabled: true,
      iconKey: 'general',
    })
  })

  afterEach(cleanup)

  it('shows only the safe matcher view and caps labels by Unicode scalar', () => {
    render(
      <AppStyleMappingDialog
        candidate={candidate}
        context={context}
        config={config}
        onCancel={vi.fn()}
        onSaved={vi.fn()}
      />,
    )

    expect(screen.getByText('Exact website host')).toBeInTheDocument()
    expect(screen.getByText('docs.example.com')).toBeInTheDocument()
    expect(screen.queryByText(/windowTitle|processId|https:\/\//)).not.toBeInTheDocument()

    const label = screen.getByLabelText('Name')
    fireEvent.change(label, { target: { value: '你'.repeat(45) } })
    expect(label).toHaveValue('你'.repeat(40))
  })

  it('closes an open scene menu on Escape without dismissing the dialog', () => {
    const onCancel = vi.fn()
    render(
      <AppStyleMappingDialog
        candidate={candidate}
        context={context}
        config={config}
        onCancel={onCancel}
        onSaved={vi.fn()}
      />,
    )

    const trigger = screen.getByRole('combobox', { name: 'Writing scene' })
    openSelect(trigger)
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(onCancel).not.toHaveBeenCalled()

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('requires an explicit submit before saving the backend-owned candidate', async () => {
    const onSaved = vi.fn()
    render(
      <AppStyleMappingDialog
        candidate={candidate}
        context={context}
        config={config}
        onCancel={vi.fn()}
        onSaved={onSaved}
      />,
    )

    expect(tauri.saveCustomAppMapping).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Product Docs' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(tauri.saveCustomAppMapping).toHaveBeenCalledWith({
        candidateGeneration: 9,
        label: 'Product Docs',
        family: 'document',
        sceneId: null,
      }),
    )
    expect(onSaved).toHaveBeenCalled()
  })

  it('does not expose family-wide scene assignment from the exact-app override dialog', async () => {
    render(
      <AppStyleMappingDialog
        candidate={candidate}
        context={context}
        config={config}
        onCancel={vi.fn()}
        onSaved={vi.fn()}
      />,
    )

    expect(
      screen.queryByRole('button', { name: 'All apps in this context family' }),
    ).not.toBeInTheDocument()

    expect(screen.queryByRole('option', { name: 'Clean Dictation' })).not.toBeInTheDocument()
    chooseOption(screen.getByRole('combobox', { name: 'Writing scene' }), 'custom_focus')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(tauri.saveCustomAppMapping).toHaveBeenCalledWith({
        candidateGeneration: 9,
        label: 'docs.example.com',
        family: 'document',
        sceneId: 'custom_focus',
      }),
    )
    expect(tauri.setFamilySceneAssignment).not.toHaveBeenCalled()
  })

  it('falls back to automatic when an edited mapping references a deleted scene', async () => {
    const staleMapping: tauri.CustomAppMappingView = {
      id: 'mapping-stale',
      label: 'Old Docs',
      matcherType: 'exact_web_host',
      displayValue: 'docs.example.com',
      family: 'document',
      sceneId: 'custom_deleted',
      enabled: true,
      iconKey: 'general',
    }

    render(
      <AppStyleMappingDialog
        candidate={null}
        mapping={staleMapping}
        context={context}
        config={config}
        onCancel={vi.fn()}
        onSaved={vi.fn()}
      />,
    )

    expect(selectedValue(screen.getByRole('combobox', { name: 'Writing scene' }))).toBe('')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(tauri.updateCustomAppMapping).toHaveBeenCalledWith({
        id: 'mapping-stale',
        label: 'Old Docs',
        family: 'document',
        sceneId: null,
        enabled: true,
      }),
    )
  })
})

describe('ManageAppMappingsDialog', () => {
  const mapping: tauri.CustomAppMappingView = {
    id: 'mapping-1',
    label: 'Work Slack',
    matcherType: 'native_bundle_id',
    displayValue: 'Work Slack · macOS',
    family: 'work_chat',
    sceneId: null,
    enabled: true,
    iconKey: 'slack',
  }

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(tauri.setCustomAppMappingEnabled).mockResolvedValue(undefined)
    vi.mocked(tauri.deleteCustomAppMapping).mockResolvedValue(undefined)
    vi.mocked(tauri.resetCustomAppMappings).mockResolvedValue(undefined)
  })

  afterEach(cleanup)

  it('renders only supplied user mappings and supports disable, edit, and delete', async () => {
    const onEdit = vi.fn()
    render(
      <ManageAppMappingsDialog
        mappings={[mapping]}
        onCancel={vi.fn()}
        onChanged={vi.fn()}
        onEdit={onEdit}
      />,
    )

    expect(screen.getByText('Work Slack')).toBeInTheDocument()
    expect(screen.queryByText('Gmail')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('switch', { name: 'Enabled' }))
    await waitFor(() =>
      expect(tauri.setCustomAppMappingEnabled).toHaveBeenCalledWith('mapping-1', false),
    )

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(onEdit).toHaveBeenCalledWith({ ...mapping, enabled: false })

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(tauri.deleteCustomAppMapping).toHaveBeenCalledWith('mapping-1'))
  })

  it('requires confirmation before resetting device-only mappings', async () => {
    render(
      <ManageAppMappingsDialog
        mappings={[mapping]}
        onCancel={vi.fn()}
        onChanged={vi.fn()}
        onEdit={vi.fn()}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Reset to automatic' }))
    expect(tauri.resetCustomAppMappings).not.toHaveBeenCalled()
    expect(screen.getByText('Delete all device-only app mappings?')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Reset mappings' }))
    await waitFor(() => expect(tauri.resetCustomAppMappings).toHaveBeenCalled())
  })
})

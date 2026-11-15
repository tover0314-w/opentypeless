import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, ChevronDown, Copy, Download, Pencil, Plus, Trash2, Upload } from 'lucide-react'
import {
  useAppStore,
  type AppConfig,
  type ContextFamily,
  type CustomScene,
  type FamilySceneAssignment,
  type SystemSceneOverride,
} from '../../stores/appStore'
import { setFamilySceneAssignment, updateConfig as persistConfig } from '../../lib/tauri'
import { importCustomScenesJson, serializeCustomScenes } from '../../lib/scenes/sceneImportExport'
import { AppLogo } from '../AppLogo'
import { Select } from '../shared/Select'
import { valuesEqual } from '../../lib/configValuesEqual'

import { SceneEditor, type EditorState } from './SceneEditor'

const MAX_SCENE_IMPORT_BYTES = 5 * 1024 * 1024

const APP_WRITING_MODES: Array<{
  family: Exclude<ContextFamily, 'general'>
  systemSceneId: string
  icons: Array<{ label: string; iconKey: string }>
}> = [
  {
    family: 'email',
    systemSceneId: 'system_email',
    icons: [
      { label: 'Gmail', iconKey: 'gmail' },
      { label: 'Apple Mail', iconKey: 'apple-mail' },
    ],
  },
  {
    family: 'work_chat',
    systemSceneId: 'system_work_chat',
    icons: [
      { label: 'Slack', iconKey: 'slack' },
      { label: 'Lark', iconKey: 'lark' },
    ],
  },
  {
    family: 'personal_chat',
    systemSceneId: 'system_personal_chat',
    icons: [
      { label: 'WeChat', iconKey: 'wechat' },
      { label: 'WhatsApp', iconKey: 'whatsapp' },
    ],
  },
  {
    family: 'document',
    systemSceneId: 'system_document',
    icons: [
      { label: 'Google Docs', iconKey: 'google-docs' },
      { label: 'Notion', iconKey: 'notion' },
    ],
  },
  {
    family: 'project_management',
    systemSceneId: 'system_project_management',
    icons: [
      { label: 'Linear', iconKey: 'linear' },
      { label: 'Jira', iconKey: 'jira' },
    ],
  },
  {
    family: 'developer_collaboration',
    systemSceneId: 'system_developer_collaboration',
    icons: [
      { label: 'GitHub', iconKey: 'github' },
      { label: 'GitLab', iconKey: 'gitlab' },
    ],
  },
  {
    family: 'prompt_or_code',
    systemSceneId: 'system_prompt_or_code',
    icons: [
      { label: 'Cursor', iconKey: 'cursor' },
      { label: 'VS Code', iconKey: 'vscode' },
    ],
  },
  {
    family: 'support',
    systemSceneId: 'system_support',
    icons: [
      { label: 'Zendesk', iconKey: 'zendesk' },
      { label: 'Intercom', iconKey: 'intercom' },
    ],
  },
  {
    family: 'social',
    systemSceneId: 'system_social',
    icons: [
      { label: 'X', iconKey: 'x' },
      { label: 'LinkedIn', iconKey: 'linkedin' },
    ],
  },
]

const SYSTEM_SCENE_PROMPTS: Record<string, string> = {
  system_email:
    'Email system mode: produce an email body when there is enough content. Use a greeting when the recipient is spoken, concise body paragraphs, and a light closing when appropriate. Do not generate a subject unless explicitly requested.',
  system_work_chat:
    'Work chat system mode: keep it casual and concise. Use short sentences or simple line breaks when helpful. No greeting or sign-off.',
  system_personal_chat:
    "Personal chat system mode: keep the user's casual voice and short-message rhythm; do not turn it into business writing.",
  system_document:
    'Document system mode: use coherent paragraphs. Use short headings or bullet points when the spoken structure has sections, takeaways, or multiple items.',
  system_project_management:
    'Project update system mode: format as a compact update with bullets for progress, blockers, and next steps when spoken. Do not invent owners, deadlines, or ticket fields.',
  system_developer_collaboration:
    'Engineering note system mode: format as a concise review or engineering note. Use bullets for issue, impact, and suggestion when helpful. Preserve technical identifiers exactly.',
  system_prompt_or_code:
    'Prompt/code system mode: make the spoken request explicit and usable. Use compact bullets for goal, constraints, and output shape when implied, but never invent code or unstated requirements.',
  system_support:
    'Support reply system mode: write a clear, empathetic reply. Use short paragraphs or numbered steps when next actions are spoken. Do not invent policy, refund, or resolution claims.',
  system_social:
    "Social post system mode: keep the user's voice and make it readable as a short post. No hashtags, emoji, or calls to action unless spoken.",
}

function systemFamilyForId(id: string): Exclude<ContextFamily, 'general'> {
  return APP_WRITING_MODES.find((mode) => mode.systemSceneId === id)?.family ?? 'email'
}

const emptyEditor = (): EditorState => ({
  mode: 'create',
  id: null,
  name: '',
  description: '',
  promptTemplate: '',
})

function createSceneId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return `custom_${crypto.randomUUID()}`
  }
  return `custom_${Date.now()}`
}

function nowIso() {
  return new Date().toISOString()
}

function readFileText(file: File): Promise<string> {
  if (typeof file.text === 'function') {
    return file.text()
  }

  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '')
    reader.onerror = () => reject(reader.error)
    reader.readAsText(file)
  })
}

export function ScenesPane() {
  const { t } = useTranslation()
  const config = useAppStore((s) => s.config)
  const setConfig = useAppStore((s) => s.setConfig)
  const setSavedConfig = useAppStore((s) => s.setSavedConfig)
  const applyPersistedConfigPatch = useAppStore((s) => s.applyPersistedConfigPatch)

  const [copiedId, setCopiedId] = useState<string | null>(null)
  const [mergeMsg, setMergeMsg] = useState<string | null>(null)
  const [mergeOk, setMergeOk] = useState(false)
  const [editor, setEditor] = useState<EditorState | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [deleteCandidateId, setDeleteCandidateId] = useState<string | null>(null)
  const importInputRef = useRef<HTMLInputElement | null>(null)
  const importRequestId = useRef(0)
  const editorSavePending = useRef(false)
  const [editorSaving, setEditorSaving] = useState(false)

  useEffect(
    () => () => {
      importRequestId.current += 1
    },
    [],
  )

  const saveConfigPatch = async (patch: Partial<AppConfig>): Promise<boolean> => {
    const previousConfig = useAppStore.getState().config
    const previousSavedConfig = useAppStore.getState().savedConfig
    const nextConfig = { ...previousConfig, ...patch }
    const nextPersistedConfig = { ...(previousSavedConfig ?? previousConfig), ...patch }
    setConfig(nextConfig)
    const submittedConfig = useAppStore.getState().config
    setSaveError(null)
    try {
      await persistConfig(nextPersistedConfig)
      if (previousSavedConfig) {
        const currentSavedConfig = useAppStore.getState().savedConfig ?? previousSavedConfig
        setSavedConfig({ ...currentSavedConfig, ...patch })
      } else setSavedConfig(nextPersistedConfig)
      return true
    } catch {
      const currentConfig = useAppStore.getState().config
      const restoredConfig = { ...currentConfig }
      for (const key of Object.keys(patch) as Array<keyof AppConfig>) {
        if (valuesEqual(currentConfig[key], submittedConfig[key])) {
          Object.assign(restoredConfig, { [key]: previousConfig[key] })
        }
      }
      setConfig(restoredConfig)
      setSaveError(t('scenes.failedToSave'))
      return false
    }
  }

  const sceneOptions = config.custom_scenes.map((scene) => ({ id: scene.id, name: scene.name }))

  const handleFamilySceneChange = async (family: ContextFamily, sceneId: string) => {
    setSaveError(null)
    try {
      const assignments = await setFamilySceneAssignment(family, sceneId || null)
      applyPersistedConfigPatch({ family_scene_assignments: assignments })
    } catch {
      setSaveError(t('scenes.failedToSave'))
    }
  }

  const handleStartCreate = () => {
    setEditor(emptyEditor())
  }

  const handleClearActiveScene = async () => {
    await saveConfigPatch({ active_scene: null })
  }

  const handleStartEdit = (scene: CustomScene) => {
    setEditor({
      mode: 'edit',
      id: scene.id,
      name: scene.name,
      description: scene.description,
      promptTemplate: scene.prompt_template,
    })
  }

  const systemOverrideFor = (id: string) =>
    config.system_scene_overrides.find((scene) => scene.id === id)

  const handleStartEditSystem = (id: string) => {
    setEditor({
      mode: 'system',
      id,
      name: t(`scenes.systemModes.${systemFamilyForId(id)}`),
      description: t('scenes.systemSceneDescription'),
      promptTemplate: systemOverrideFor(id)?.prompt_template ?? SYSTEM_SCENE_PROMPTS[id] ?? '',
    })
  }

  const handleSaveEditor = async () => {
    if (editorSavePending.current) return
    editorSavePending.current = true
    setEditorSaving(true)
    try {
      await saveEditor()
    } finally {
      editorSavePending.current = false
      setEditorSaving(false)
    }
  }

  const saveEditor = async () => {
    if (!editor) return
    const name = editor.name.trim()
    const description = editor.description.trim()
    const promptTemplate = editor.promptTemplate.trim()
    if (!name || !promptTemplate) return

    const timestamp = nowIso()
    if (editor.mode === 'system' && editor.id) {
      const nextOverride: SystemSceneOverride = {
        id: editor.id,
        prompt_template: promptTemplate,
      }
      const nextOverrides = [
        ...config.system_scene_overrides.filter((scene) => scene.id !== editor.id),
        nextOverride,
      ]
      if (await saveConfigPatch({ system_scene_overrides: nextOverrides })) {
        setEditor((current) => (current === editor ? null : current))
      }
      return
    }

    let nextScenes: CustomScene[]
    let savedScene: CustomScene

    if (editor.mode === 'edit' && editor.id) {
      const existing = config.custom_scenes.find((scene) => scene.id === editor.id)
      savedScene = {
        id: editor.id,
        name,
        description,
        prompt_template: promptTemplate,
        created_at: existing?.created_at ?? timestamp,
        updated_at: timestamp,
      }
      nextScenes = config.custom_scenes.map((scene) =>
        scene.id === editor.id ? savedScene : scene,
      )
    } else {
      savedScene = {
        id: createSceneId(),
        name,
        description,
        prompt_template: promptTemplate,
        created_at: timestamp,
        updated_at: timestamp,
      }
      nextScenes = [...config.custom_scenes, savedScene]
    }

    if (await saveConfigPatch({ custom_scenes: nextScenes })) {
      setEditor((current) => (current === editor ? null : current))
    }
  }

  const handleDuplicateCustom = async (scene: CustomScene) => {
    const timestamp = nowIso()
    const copy: CustomScene = {
      ...scene,
      id: createSceneId(),
      name: t('scenes.copyName', { name: scene.name }),
      created_at: timestamp,
      updated_at: timestamp,
    }
    await saveConfigPatch({ custom_scenes: [...config.custom_scenes, copy] })
  }

  const handleDeleteCustom = async (scene: CustomScene) => {
    const saved = await saveConfigPatch({
      custom_scenes: config.custom_scenes.filter((item) => item.id !== scene.id),
      family_scene_assignments: config.family_scene_assignments.filter(
        (assignment) => assignment.scene_id !== scene.id,
      ),
      active_scene:
        config.active_scene?.source === 'custom' && config.active_scene.id === scene.id
          ? null
          : config.active_scene,
    })
    if (saved) setDeleteCandidateId(null)
  }

  const handleResetSystemScene = async (id: string) => {
    await saveConfigPatch({
      system_scene_overrides: config.system_scene_overrides.filter((scene) => scene.id !== id),
    })
  }

  const handleExportCustomScenes = () => {
    const json = serializeCustomScenes(config.custom_scenes)
    const blob = new Blob([json], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `opentypeless-scenes-${new Date().toISOString().slice(0, 10)}.json`
    link.click()
    URL.revokeObjectURL(url)
  }

  const handleImportCustomScenes = async (file: File | undefined) => {
    if (!file) return
    const requestId = ++importRequestId.current
    setSaveError(null)
    setMergeMsg(null)
    setMergeOk(false)

    try {
      if (file.size > MAX_SCENE_IMPORT_BYTES) {
        setSaveError(t('scenes.importFailed'))
        return
      }
      const json = await readFileText(file)
      if (requestId !== importRequestId.current) return
      const currentScenes = useAppStore.getState().config.custom_scenes
      const result = importCustomScenesJson(json, {
        existingIds: new Set(currentScenes.map((scene) => scene.id)),
        createId: createSceneId,
        nowIso,
      })

      if (result.scenes.length === 0) {
        setSaveError(t('scenes.importNothing'))
        return
      }

      const saved = await saveConfigPatch({
        custom_scenes: [...currentScenes, ...result.scenes],
      })
      if (!saved || requestId !== importRequestId.current) return
      const importNotes = [
        result.report.skippedInvalid > 0
          ? t('scenes.importSkippedInvalid', { count: result.report.skippedInvalid })
          : null,
        result.report.skippedLimit > 0
          ? t('scenes.importSkippedLimit', { count: result.report.skippedLimit })
          : null,
        result.report.renamedConflicts > 0
          ? t('scenes.importRenamedConflicts', { count: result.report.renamedConflicts })
          : null,
      ].filter(Boolean)
      setMergeOk(true)
      setMergeMsg(
        [
          t('scenes.importedScenes', { count: result.scenes.length }),
          importNotes.length > 0 ? importNotes.join(' · ') : null,
        ]
          .filter(Boolean)
          .join(' · '),
      )
      setTimeout(() => {
        if (requestId !== importRequestId.current) return
        setMergeMsg(null)
        setMergeOk(false)
      }, 3000)
    } catch {
      if (requestId === importRequestId.current) setSaveError(t('scenes.importFailed'))
    }
  }

  const handleCopyPrompt = async (id: string, promptTemplate: string) => {
    try {
      await navigator.clipboard.writeText(promptTemplate)
      setCopiedId(id)
      setTimeout(() => setCopiedId(null), 2000)
    } catch {
      // Clipboard write failed silently
    }
  }

  return (
    <div className="space-y-6">
      <section className="space-y-3">
        <div>
          <h3 className="text-[13px] font-semibold text-text-primary">
            {t('scenes.appWritingModes')}
          </h3>
          <p className="mt-1 text-[12px] text-text-tertiary">{t('scenes.appWritingModesDesc')}</p>
        </div>
        <AppWritingModes
          assignments={config.family_scene_assignments}
          sceneOptions={sceneOptions}
          onChange={handleFamilySceneChange}
        />
        {config.active_scene && (
          <div className="flex items-center justify-between gap-3 rounded-[8px] border border-border px-3 py-2.5">
            <p className="min-w-0 truncate text-[12px] text-text-secondary">
              {t('scenes.activeScene', { name: config.active_scene.name })}
            </p>
            <button
              type="button"
              onClick={() => void handleClearActiveScene()}
              className="flex-none border-none bg-transparent text-[12px] text-accent hover:opacity-80"
            >
              {t('scenes.clearActive')}
            </button>
          </div>
        )}
      </section>

      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h3 className="text-[13px] font-semibold text-text-primary">{t('scenes.myScenes')}</h3>
          <div className="flex flex-wrap items-center gap-2">
            <button
              onClick={handleExportCustomScenes}
              disabled={config.custom_scenes.length === 0}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-[8px] border border-border bg-transparent text-text-secondary text-[12px] cursor-pointer hover:text-text-primary hover:border-border-focus transition-colors disabled:opacity-45 disabled:cursor-not-allowed"
            >
              <Download size={13} />
              {t('scenes.export')}
            </button>
            <button
              onClick={() => importInputRef.current?.click()}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-[8px] border border-border bg-transparent text-text-secondary text-[12px] cursor-pointer hover:text-text-primary hover:border-border-focus transition-colors"
            >
              <Upload size={13} />
              {t('scenes.import')}
            </button>
            <input
              ref={importInputRef}
              type="file"
              accept="application/json,.json"
              aria-label={t('scenes.import')}
              className="hidden"
              onChange={(event) => {
                const file = event.target.files?.[0]
                event.target.value = ''
                void handleImportCustomScenes(file)
              }}
            />
            <button
              onClick={handleStartCreate}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-[8px] border border-border bg-bg-secondary text-text-primary text-[12px] cursor-pointer hover:border-border-focus transition-colors"
            >
              <Plus size={13} />
              {t('scenes.newScene')}
            </button>
          </div>
        </div>

        {editor && (
          <SceneEditor
            saving={editorSaving}
            editor={editor}
            onChange={setEditor}
            onCancel={() => setEditor(null)}
            onSave={handleSaveEditor}
          />
        )}

        <div className="space-y-2">
          {APP_WRITING_MODES.map((mode) => {
            const override = systemOverrideFor(mode.systemSceneId)
            const promptTemplate =
              override?.prompt_template ?? SYSTEM_SCENE_PROMPTS[mode.systemSceneId] ?? ''
            return (
              <LocalSceneCard
                key={mode.systemSceneId}
                id={mode.systemSceneId}
                name={t(`scenes.systemModes.${mode.family}`)}
                description={t('scenes.systemSceneDescription')}
                promptTemplate={promptTemplate}
                active={false}
                copied={copiedId === mode.systemSceneId}
                onCopy={() => handleCopyPrompt(mode.systemSceneId, promptTemplate)}
                onEdit={() => handleStartEditSystem(mode.systemSceneId)}
                onReset={override ? () => handleResetSystemScene(mode.systemSceneId) : undefined}
              />
            )
          })}
        </div>

        {config.custom_scenes.length === 0 ? (
          <div className="rounded-[8px] border border-dashed border-border px-4 py-6 text-center">
            <p className="text-[13px] text-text-primary font-medium">
              {t('scenes.noCustomScenes')}
            </p>
            <p className="text-[12px] text-text-secondary mt-1">{t('scenes.noCustomScenesDesc')}</p>
          </div>
        ) : (
          <div className="space-y-2">
            {config.custom_scenes.map((scene) => (
              <LocalSceneCard
                key={scene.id}
                id={scene.id}
                name={scene.name}
                description={scene.description}
                promptTemplate={scene.prompt_template}
                active={
                  config.active_scene?.source === 'custom' && config.active_scene.id === scene.id
                }
                copied={copiedId === scene.id}
                onCopy={() => handleCopyPrompt(scene.id, scene.prompt_template)}
                onEdit={() => handleStartEdit(scene)}
                onDuplicate={() => handleDuplicateCustom(scene)}
                onDelete={() => setDeleteCandidateId(scene.id)}
                confirmingDelete={deleteCandidateId === scene.id}
                onCancelDelete={() => setDeleteCandidateId(null)}
                onConfirmDelete={() => handleDeleteCustom(scene)}
              />
            ))}
          </div>
        )}
      </section>

      {mergeMsg && (
        <p className={`text-[12px] ${!mergeOk ? 'text-red-500' : 'text-green-500'}`}>{mergeMsg}</p>
      )}
      {saveError && <p className="text-[12px] text-red-500">{saveError}</p>}
    </div>
  )
}

function AppWritingModes({
  assignments,
  sceneOptions,
  onChange,
}: {
  assignments: FamilySceneAssignment[]
  sceneOptions: Array<{ id: string; name: string }>
  onChange: (family: ContextFamily, sceneId: string) => void
}) {
  const { t } = useTranslation()

  return (
    <div className="space-y-2">
      {APP_WRITING_MODES.map((mode) => {
        const selectedSceneId =
          assignments.find((assignment) => assignment.family === mode.family)?.scene_id ?? ''
        const label = t(`contextFamilies.${mode.family}`)
        const selectLabel = `${label} ${t('scenes.appWritingScene')}`

        return (
          <div
            key={mode.family}
            className="grid grid-cols-1 gap-2.5 rounded-[8px] border border-border px-3 py-2.5 min-[840px]:grid-cols-[minmax(0,1fr)_minmax(140px,180px)] min-[840px]:items-center"
          >
            <div className="flex min-w-0 items-center gap-3">
              <div className="flex flex-none items-center gap-1.5">
                {mode.icons.map((icon) => (
                  <span
                    key={icon.iconKey}
                    role="img"
                    aria-label={icon.label}
                    title={icon.label}
                    className="grid h-6 w-6 place-items-center rounded-[6px] bg-bg-secondary"
                  >
                    <AppLogo iconKey={icon.iconKey} family={mode.family} />
                  </span>
                ))}
              </div>
              <div className="min-w-0">
                <p className="truncate text-[13px] font-medium text-text-primary">{label}</p>
                <p className="truncate text-[11px] text-text-tertiary">
                  {t(`scenes.appModeDescriptions.${mode.family}`)}
                </p>
              </div>
            </div>

            <Select
              aria-label={selectLabel}
              value={selectedSceneId}
              onChange={(value) => onChange(mode.family, value)}
              className="min-w-0"
              options={[
                { value: '', label: t(`scenes.systemModes.${mode.family}`) },
                ...sceneOptions.map((scene) => ({ value: scene.id, label: scene.name })),
              ]}
            />
          </div>
        )
      })}
    </div>
  )
}

function LocalSceneCard({
  id,
  name,
  description,
  promptTemplate,
  active,
  copied,
  onCopy,
  onEdit,
  onDuplicate,
  onDelete,
  confirmingDelete = false,
  onCancelDelete,
  onConfirmDelete,
  onReset,
}: {
  id: string
  name: string
  description: string
  promptTemplate: string
  active: boolean
  copied: boolean
  onCopy: () => void
  onEdit?: () => void
  onDuplicate?: () => void
  onDelete?: () => void
  confirmingDelete?: boolean
  onCancelDelete?: () => void
  onConfirmDelete?: () => void
  onReset?: () => void
}) {
  const { t } = useTranslation()
  const [expanded, setExpanded] = useState(false)

  return (
    <div className="border border-border rounded-[8px] overflow-hidden">
      <button
        type="button"
        aria-expanded={expanded}
        onClick={() => setExpanded((value) => !value)}
        className="w-full flex items-center justify-between gap-3 px-3 py-2.5 bg-transparent border-none text-left cursor-pointer hover:bg-bg-secondary/50 transition-colors"
      >
        <span className="min-w-0">
          <span className="flex items-center gap-2">
            <span className="text-[13px] text-text-primary font-medium truncate">{name}</span>
            {active && (
              <span className="text-[10px] text-accent bg-accent/10 px-1.5 py-0.5 rounded-full">
                {t('scenes.active')}
              </span>
            )}
          </span>
          {description && (
            <span className="block text-[12px] text-text-tertiary truncate mt-0.5">
              {description}
            </span>
          )}
        </span>
        <ChevronDown
          size={14}
          className={`flex-none text-text-tertiary transition-transform ${expanded ? 'rotate-180' : ''}`}
        />
      </button>
      {expanded && (
        <div className="border-t border-border px-3 py-3 space-y-3">
          <pre className="text-[12px] text-text-primary bg-bg-secondary rounded-[8px] px-3 py-2 whitespace-pre-wrap max-h-[140px] overflow-y-auto leading-relaxed">
            {promptTemplate}
          </pre>
          <div className="flex items-center gap-3 flex-wrap">
            <button
              onClick={onCopy}
              aria-label={`Copy prompt for ${name}`}
              className="flex items-center gap-1 text-[12px] text-accent bg-transparent border-none cursor-pointer hover:opacity-80"
            >
              {copied ? <Check size={12} /> : <Copy size={12} />}
              {copied ? t('scenes.copied') : t('scenes.copyPrompt')}
            </button>
            {onDuplicate && (
              <button
                onClick={onDuplicate}
                className="text-[12px] text-accent bg-transparent border-none cursor-pointer hover:opacity-80"
              >
                {t('scenes.duplicate')}
              </button>
            )}
            {onEdit && (
              <button
                onClick={onEdit}
                className="flex items-center gap-1 text-[12px] text-accent bg-transparent border-none cursor-pointer hover:opacity-80"
              >
                <Pencil size={12} />
                {t('scenes.edit')}
              </button>
            )}
            {onReset && (
              <button
                onClick={onReset}
                className="text-[12px] text-text-tertiary bg-transparent border-none cursor-pointer hover:text-text-primary"
              >
                {t('scenes.resetSystemScene')}
              </button>
            )}
            {onDelete && !confirmingDelete && (
              <button
                onClick={onDelete}
                className="flex items-center gap-1 text-[12px] text-red-500 bg-transparent border-none cursor-pointer hover:opacity-80"
              >
                <Trash2 size={12} />
                {t('scenes.delete')}
              </button>
            )}
          </div>
          {confirmingDelete && onCancelDelete && onConfirmDelete && (
            <div className="rounded-[8px] border border-error/20 bg-error/10 px-3 py-2">
              <p className="text-[12px] leading-relaxed text-text-secondary">
                {t('scenes.deleteConfirm')}
              </p>
              <div className="mt-2 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={onCancelDelete}
                  className="rounded-[7px] border border-border bg-transparent px-2.5 py-1 text-[11px] text-text-secondary hover:text-text-primary"
                >
                  {t('common.cancel')}
                </button>
                <button
                  type="button"
                  onClick={onConfirmDelete}
                  className="rounded-[7px] border border-error/30 bg-error/15 px-2.5 py-1 text-[11px] font-medium text-error hover:bg-error/20"
                >
                  {t('scenes.delete')}
                </button>
              </div>
            </div>
          )}
        </div>
      )}
      <span className="sr-only">{id}</span>
    </div>
  )
}

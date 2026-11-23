import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { AppConfig, ContextFamily, ContextProfileSummary } from '../../stores/appStore'
import { saveCustomAppMapping, updateCustomAppMapping } from '../../lib/tauri'
import type { CustomAppMappingView, MappingCandidateView } from '../../lib/tauri'
import { AppLogo } from '../AppLogo'
import { Select } from '../shared/Select'

const CONTEXT_FAMILIES: ContextFamily[] = [
  'email',
  'work_chat',
  'personal_chat',
  'document',
  'project_management',
  'developer_collaboration',
  'prompt_or_code',
  'support',
  'social',
  'general',
]

type MappingConfig = Pick<AppConfig, 'custom_scenes' | 'family_scene_assignments'>

interface AppStyleMappingDialogProps {
  candidate: MappingCandidateView | null
  mapping?: CustomAppMappingView | null
  context: ContextProfileSummary
  config: MappingConfig
  onCancel: () => void
  onSaved: () => void | Promise<void>
}

function clampLabel(value: string) {
  return Array.from(value).slice(0, 40).join('')
}

export function AppStyleMappingDialog({
  candidate,
  mapping = null,
  context,
  config,
  onCancel,
  onSaved,
}: AppStyleMappingDialogProps) {
  const { t } = useTranslation()
  const editing = Boolean(mapping)
  const [label, setLabel] = useState(
    mapping?.label ?? candidate?.suggestedLabel ?? context.appLabel,
  )
  const [family, setFamily] = useState<ContextFamily>(
    mapping?.family ?? candidate?.currentFamily ?? context.family,
  )
  const [sceneId, setSceneId] = useState(
    mapping?.sceneId && config.custom_scenes.some((scene) => scene.id === mapping.sceneId)
      ? mapping.sceneId
      : '',
  )
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const labelRef = useRef<HTMLInputElement>(null)

  const displayValue = mapping?.displayValue ?? candidate?.displayValue ?? context.appLabel
  const iconKey = mapping?.iconKey ?? candidate?.iconKey ?? context.iconKey
  const matcherType = mapping?.matcherType ?? candidate?.matcherType
  const canSave = !saving && Boolean(label.trim()) && Boolean(mapping || candidate)

  const sceneOptions = useMemo(
    () => config.custom_scenes.map((scene) => ({ id: scene.id, label: scene.name })),
    [config.custom_scenes],
  )

  useEffect(() => {
    labelRef.current?.focus()
  }, [])

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || saving) return
      event.preventDefault()
      onCancel()
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onCancel, saving])

  const save = async () => {
    if (!canSave) return
    setSaving(true)
    setError(null)
    try {
      if (mapping) {
        await updateCustomAppMapping({
          id: mapping.id,
          label: label.trim(),
          family,
          sceneId: sceneId || null,
          enabled: mapping.enabled,
        })
      } else if (candidate) {
        await saveCustomAppMapping({
          candidateGeneration: candidate.generation,
          label: label.trim(),
          family,
          sceneId: sceneId || null,
        })
      }
      await onSaved()
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : String(saveError))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/25 px-5">
      <div className="fixed inset-0" onClick={saving ? undefined : onCancel} />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={t(editing ? 'settings.editAppMappingTitle' : 'settings.appStyleDialogTitle')}
        className="relative z-10 w-full max-w-[420px] rounded-[10px] border border-border bg-bg-primary shadow-float"
      >
        <div className="border-b border-border px-4 py-3">
          <h3 className="text-[14px] font-medium text-text-primary">
            {t(editing ? 'settings.editAppMappingTitle' : 'settings.appStyleDialogTitle')}
          </h3>
        </div>

        <div className="space-y-3 px-4 py-3">
          <div className="flex min-w-0 items-center gap-2">
            <AppLogo iconKey={iconKey} family={family} />
            <div className="min-w-0">
              {matcherType && (
                <p className="text-[11px] text-text-tertiary">
                  {t(
                    matcherType === 'exact_web_host'
                      ? 'settings.mappingMatcherWeb'
                      : 'settings.mappingMatcherNative',
                  )}
                </p>
              )}
              <p className="truncate text-[12px] text-text-secondary">{displayValue}</p>
            </div>
          </div>

          <label className="block text-[11px] text-text-secondary">
            {t('settings.mappingLabel')}
            <input
              ref={labelRef}
              value={label}
              onChange={(event) => setLabel(clampLabel(event.target.value))}
              className="mt-1 w-full rounded-[8px] border border-border bg-bg-secondary px-3 py-2 text-[13px] text-text-primary outline-none focus:border-border-focus"
            />
          </label>

          <div className="text-[11px] text-text-secondary">
            <span aria-hidden="true">{t('settings.mappingFamily')}</span>
            <Select
              aria-label={t('settings.mappingFamily')}
              value={family}
              onChange={(value) => setFamily(value as ContextFamily)}
              className="mt-1"
              options={CONTEXT_FAMILIES.map((value) => ({
                value,
                label: t(`contextFamilies.${value}`),
              }))}
            />
          </div>

          <div className="text-[11px] text-text-secondary">
            <span aria-hidden="true">{t('settings.mappingScene')}</span>
            <Select
              aria-label={t('settings.mappingScene')}
              value={sceneId}
              onChange={setSceneId}
              className="mt-1"
              options={[
                { value: '', label: t('settings.mappingNoScene') },
                ...sceneOptions.map((scene) => ({ value: scene.id, label: scene.label })),
              ]}
            />
          </div>

          {error && <p className="text-[11px] text-error">{error}</p>}
        </div>

        <div className="flex justify-end gap-2 border-t border-border px-4 py-3">
          <button
            type="button"
            onClick={onCancel}
            disabled={saving}
            className="rounded-[8px] border border-border bg-transparent px-3 py-1.5 text-[12px] text-text-secondary hover:text-text-primary disabled:opacity-50"
          >
            {t('settings.mappingCancel')}
          </button>
          <button
            type="button"
            onClick={() => void save()}
            disabled={!canSave}
            className="rounded-[8px] border-none bg-accent px-3 py-1.5 text-[12px] text-white hover:bg-accent-hover disabled:opacity-40"
          >
            {t('settings.mappingSave')}
          </button>
        </div>
      </div>
    </div>
  )
}

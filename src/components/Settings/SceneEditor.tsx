import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

export interface EditorState {
  mode: 'create' | 'edit' | 'system'
  id: string | null
  name: string
  description: string
  promptTemplate: string
}

export function SceneEditor({
  saving,
  editor,
  onChange,
  onCancel,
  onSave,
}: {
  saving: boolean
  editor: EditorState
  onChange: (editor: EditorState) => void
  onCancel: () => void
  onSave: () => void
}) {
  const { t } = useTranslation()
  const composingRef = useRef(false)
  const [composing, setComposing] = useState(false)
  const canSave = editor.name.trim().length > 0 && editor.promptTemplate.trim().length > 0
  const updateField = (
    field: 'name' | 'description' | 'promptTemplate',
    value: string,
    limit: number,
  ) => {
    onChange({
      ...editor,
      [field]: composingRef.current ? value : Array.from(value).slice(0, limit).join(''),
    })
  }
  const startComposition = () => {
    composingRef.current = true
    setComposing(true)
  }
  const endComposition = (
    field: 'name' | 'description' | 'promptTemplate',
    value: string,
    limit: number,
  ) => {
    composingRef.current = false
    setComposing(false)
    updateField(field, value, limit)
  }

  return (
    <div className="space-y-3 rounded-[8px] border border-border bg-bg-secondary px-3 py-3">
      {editor.mode === 'system' ? (
        <p className="text-[13px] font-medium text-text-primary">{editor.name}</p>
      ) : (
        <>
          <label className="block text-[12px] text-text-secondary">
            <span className="block mb-1">{t('scenes.sceneName')}</span>
            <input
              value={editor.name}
              onChange={(event) => updateField('name', event.target.value, 80)}
              onCompositionStart={startComposition}
              onCompositionEnd={(event) => endComposition('name', event.currentTarget.value, 80)}
              className="field-control"
            />
          </label>
          <label className="block text-[12px] text-text-secondary">
            <span className="block mb-1">{t('scenes.sceneDescription')}</span>
            <input
              value={editor.description}
              onChange={(event) => updateField('description', event.target.value, 240)}
              onCompositionStart={startComposition}
              onCompositionEnd={(event) =>
                endComposition('description', event.currentTarget.value, 240)
              }
              className="field-control"
            />
          </label>
        </>
      )}
      <label className="block text-[12px] text-text-secondary">
        <span className="block mb-1">{t('scenes.promptTemplate')}</span>
        <textarea
          value={editor.promptTemplate}
          onChange={(event) => updateField('promptTemplate', event.target.value, 4000)}
          onCompositionStart={startComposition}
          onCompositionEnd={(event) =>
            endComposition('promptTemplate', event.currentTarget.value, 4000)
          }
          rows={5}
          className="field-control resize-y"
        />
      </label>
      <div className="flex items-center justify-end gap-2">
        <button
          type="button"
          onClick={onCancel}
          className="btn-outline h-8 cursor-pointer px-3 text-[14px] leading-4"
        >
          {t('common.cancel')}
        </button>
        <button
          type="button"
          onClick={onSave}
          disabled={!canSave || saving || composing}
          className="btn-primary h-8 cursor-pointer px-3 text-[14px] leading-4 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {t('common.save')}
        </button>
      </div>
    </div>
  )
}

import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { TARGET_LANGUAGES } from '../../lib/constants'
import type { TranslationConfig } from '../../stores/appStore'
import { Select } from '../shared/Select'

interface TranslationTargetsProps {
  value: TranslationConfig
  onChange: (value: TranslationConfig) => void
}

// The backend keeps up to five ordered targets; Typeless caps the same picker at three.
const MAX_TRANSLATION_TARGETS = 5
const ADD_PLACEHOLDER = '__add__'

/** Typeless translation targets: one removable chip per language and an "Add language" picker. */
export function TranslationTargets({ value, onChange }: TranslationTargetsProps) {
  const { t } = useTranslation()
  const selected = new Set(value.targets)
  const label = (code: string) => {
    const language = TARGET_LANGUAGES.find((item) => item.value === code)
    if (!language) return code.toUpperCase()
    return language.labelKey ? t(language.labelKey) : language.label
  }

  const add = (code: string) => {
    if (code === ADD_PLACEHOLDER || selected.has(code)) return
    if (value.targets.length >= MAX_TRANSLATION_TARGETS) return
    onChange({ targets: [...value.targets, code], active_target: value.active_target })
  }

  const remove = (index: number) => {
    if (value.targets.length === 1) return
    const removed = value.targets[index]
    const targets = value.targets.filter((_, current) => current !== index)
    const activeTarget =
      removed === value.active_target
        ? targets[Math.min(index, targets.length - 1)]
        : value.active_target
    onChange({ targets, active_target: activeTarget })
  }

  const atLimit = value.targets.length >= MAX_TRANSLATION_TARGETS
  const remaining = TARGET_LANGUAGES.filter((language) => !selected.has(language.value))

  return (
    <div className="flex flex-col gap-2">
      <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
        {value.targets.map((code, index) => (
          <li
            key={code}
            data-testid={`translation-target-${code}`}
            className="flex h-10 items-center justify-between gap-2 rounded-[8px] border border-border px-3 text-[14px] text-text-primary"
          >
            <span className="min-w-0 truncate">{label(code)}</span>
            {value.targets.length > 1 && (
              <button
                type="button"
                onClick={() => remove(index)}
                aria-label={`${t('translationTargets.remove')} ${label(code)}`}
                title={t('translationTargets.remove')}
                className="grid h-[26px] w-[26px] shrink-0 cursor-pointer place-items-center rounded-[4px] border-none bg-transparent text-text-secondary hover:bg-fill-hover"
              >
                <X size={14} />
              </button>
            )}
          </li>
        ))}
      </ul>
      {atLimit ? (
        <p className="m-0 text-[12px] leading-4 text-text-tertiary">
          {t('translationTargets.max')}
        </p>
      ) : (
        <Select
          aria-label={t('translationTargets.addLanguage')}
          value={ADD_PLACEHOLDER}
          options={[
            { value: ADD_PLACEHOLDER, label: t('translationTargets.addLanguage'), disabled: true },
            ...remaining.map((language) => ({
              value: language.value,
              label: label(language.value),
            })),
          ]}
          onChange={add}
        />
      )}
    </div>
  )
}

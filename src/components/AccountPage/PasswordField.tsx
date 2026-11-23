import { useId, useState, type Ref } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { useTranslation } from 'react-i18next'

interface PasswordFieldProps {
  label: string
  value: string
  onChange: (value: string) => void
  autoComplete: 'current-password' | 'new-password'
  showLabel?: boolean
  inputRef?: Ref<HTMLInputElement>
}

export function PasswordField({
  label,
  value,
  onChange,
  autoComplete,
  showLabel = false,
  inputRef,
}: PasswordFieldProps) {
  const id = useId()
  const [visible, setVisible] = useState(false)
  const { t } = useTranslation()
  const visibilityLabel = visible
    ? t('account.hidePassword', { label, defaultValue: `Hide ${label}` })
    : t('account.showPassword', { label, defaultValue: `Show ${label}` })

  return (
    <div className="space-y-1.5">
      {showLabel && (
        <label htmlFor={id} className="block text-[12px] text-text-secondary">
          {label}
        </label>
      )}
      <div className="relative">
        <input
          ref={inputRef}
          id={id}
          type={visible ? 'text' : 'password'}
          aria-label={label}
          autoComplete={autoComplete}
          placeholder={label}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          minLength={autoComplete === 'new-password' ? 8 : undefined}
          maxLength={128}
          required
          className="field-control field-control-with-action"
        />
        <button
          type="button"
          aria-label={visibilityLabel}
          title={visibilityLabel}
          onClick={() => setVisible((current) => !current)}
          className="absolute inset-y-0 right-0 w-9 flex items-center justify-center bg-transparent border-none text-text-tertiary hover:text-text-primary cursor-pointer"
        >
          {visible ? <EyeOff size={14} aria-hidden="true" /> : <Eye size={14} aria-hidden="true" />}
        </button>
      </div>
    </div>
  )
}

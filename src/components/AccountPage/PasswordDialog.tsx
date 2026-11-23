import { useEffect, useRef, useState, type FormEvent, type RefObject } from 'react'
import { Loader2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { CredentialCapability } from '../../stores/authStore'
import { PasswordField } from './PasswordField'

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

interface PasswordDialogProps {
  credentialCapability: Exclude<CredentialCapability, 'unknown'>
  loading: boolean
  returnFocusRef: RefObject<HTMLButtonElement | null>
  onCancel: () => void
  onSubmit: (currentPassword: string | null, newPassword: string) => Promise<void>
}

export function PasswordDialog({
  credentialCapability,
  loading,
  returnFocusRef,
  onCancel,
  onSubmit,
}: PasswordDialogProps) {
  const { t } = useTranslation()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState<
    | 'account.changedDuringPasswordUpdate'
    | 'account.currentPasswordRequired'
    | 'account.passwordChangeFailed'
    | null
  >(null)
  const [submitting, setSubmitting] = useState(false)
  const submissionPending = useRef(false)
  const mounted = useRef(false)
  const formRef = useRef<HTMLFormElement>(null)
  const currentPasswordRef = useRef<HTMLInputElement>(null)
  const newPasswordRef = useRef<HTMLInputElement>(null)
  const actionLabel =
    credentialCapability === 'present'
      ? t('account.changePassword', 'Change password')
      : t('account.setPassword', 'Set password')
  const busy = loading || submitting
  const passwordMismatch = confirmPassword.length > 0 && newPassword !== confirmPassword
  const passwordOutOfRange =
    newPassword.length > 0 && (newPassword.length < 8 || newPassword.length > 128)
  const formValid =
    newPassword.length >= 8 &&
    newPassword.length <= 128 &&
    newPassword === confirmPassword &&
    (credentialCapability === 'none' || currentPassword.length > 0)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  useEffect(() => {
    const returnFocus = returnFocusRef.current
    if (credentialCapability === 'present') currentPasswordRef.current?.focus()
    else newPasswordRef.current?.focus()

    return () => returnFocus?.focus()
  }, [credentialCapability, returnFocusRef])

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.isComposing || event.keyCode === 229) return
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopImmediatePropagation()
        if (busy || submissionPending.current) return
        onCancel()
        return
      }

      if (event.key !== 'Tab') return
      const form = formRef.current
      if (!form) return
      const focusable = Array.from(form.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
      if (focusable.length === 0) return

      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      const active = document.activeElement
      if (!focusable.some((element) => element === active)) {
        event.preventDefault()
        ;(event.shiftKey ? last : first).focus()
      } else if (event.shiftKey && active === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (active === last || !form.contains(active))) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => window.removeEventListener('keydown', onKeyDown, true)
  }, [busy, onCancel])

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (!formValid || busy || submissionPending.current) return

    submissionPending.current = true
    setError(null)
    setSubmitting(true)
    try {
      await onSubmit(credentialCapability === 'present' ? currentPassword : null, newPassword)
    } catch (caught) {
      if (!mounted.current) return
      const message = caught instanceof Error ? caught.message : ''
      const accountChanged = t('account.changedDuringPasswordUpdate')
      const currentRequired = t('account.currentPasswordRequired')
      setError(
        message === accountChanged
          ? 'account.changedDuringPasswordUpdate'
          : message === 'Current password is required' || message === currentRequired
            ? 'account.currentPasswordRequired'
            : 'account.passwordChangeFailed',
      )
      setSubmitting(false)
      submissionPending.current = false
      return
    }

    if (!mounted.current) return
    setSubmitting(false)
    onCancel()
  }

  const cancel = () => {
    if (busy || submissionPending.current) return
    onCancel()
  }

  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/25 px-5">
      <div className="fixed inset-0" onClick={cancel} />
      <form
        ref={formRef}
        role="dialog"
        aria-modal="true"
        aria-label={actionLabel}
        onSubmit={handleSubmit}
        className="relative z-10 w-full max-w-[380px] rounded-[10px] border border-border bg-bg-primary shadow-float"
      >
        <div className="border-b border-border px-4 py-3">
          <h3 className="text-[14px] font-medium text-text-primary">{actionLabel}</h3>
        </div>

        <div className="space-y-3 px-4 py-3">
          {credentialCapability === 'present' && (
            <PasswordField
              inputRef={currentPasswordRef}
              label={t('account.currentPassword', 'Current password')}
              value={currentPassword}
              onChange={setCurrentPassword}
              autoComplete="current-password"
              showLabel
            />
          )}
          <PasswordField
            inputRef={newPasswordRef}
            label={t('account.newPassword', 'New password')}
            value={newPassword}
            onChange={setNewPassword}
            autoComplete="new-password"
            showLabel
          />
          <PasswordField
            label={t('account.confirmPassword', 'Confirm password')}
            value={confirmPassword}
            onChange={setConfirmPassword}
            autoComplete="new-password"
            showLabel
          />

          <div aria-live="polite">
            {passwordOutOfRange && (
              <p className="text-[12px] text-error" role="alert">
                {t('account.passwordTooShort', 'Password must be 8 to 128 characters')}
              </p>
            )}
            {passwordMismatch && (
              <p className="text-[12px] text-error" role="alert">
                {t('account.passwordMismatch', 'Passwords do not match')}
              </p>
            )}
            {error && (
              <p className="text-[12px] text-error [overflow-wrap:anywhere]" role="alert">
                {t(error)}
              </p>
            )}
          </div>
        </div>

        <div className="flex justify-end gap-2 border-t border-border px-4 py-3">
          <button
            type="button"
            onClick={cancel}
            disabled={busy}
            className="rounded-[8px] border border-border bg-transparent px-3 py-1.5 text-[12px] text-text-secondary hover:bg-bg-hover hover:text-text-primary disabled:opacity-50"
          >
            {t('account.cancel', 'Cancel')}
          </button>
          <button
            type="submit"
            disabled={!formValid || busy}
            className="flex min-w-[104px] items-center justify-center gap-1.5 rounded-[8px] border-none bg-accent px-3 py-1.5 text-[12px] text-white hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-40"
          >
            {busy && <Loader2 size={13} className="animate-spin" aria-hidden="true" />}
            {actionLabel}
          </button>
        </div>
      </form>
    </div>
  )
}

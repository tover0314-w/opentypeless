import { useTranslation } from 'react-i18next'
import { AppLogo } from '../AppLogo'
import type { ContextFamily } from '../../stores/appStore'

interface RepresentativeApp {
  label: string
  iconKey: string
  family: ContextFamily
  hideAtMinimum?: boolean
}

const REPRESENTATIVE_APPS: readonly RepresentativeApp[] = [
  { label: 'Gmail', iconKey: 'gmail', family: 'email' },
  { label: 'Slack', iconKey: 'slack', family: 'work_chat' },
  { label: 'Lark', iconKey: 'lark', family: 'work_chat', hideAtMinimum: true },
  { label: 'WeChat', iconKey: 'wechat', family: 'personal_chat' },
  { label: 'Google Docs', iconKey: 'google-docs', family: 'document' },
  { label: 'Notion', iconKey: 'notion', family: 'document', hideAtMinimum: true },
  { label: 'GitHub', iconKey: 'github', family: 'developer_collaboration' },
  { label: 'Cursor', iconKey: 'cursor', family: 'prompt_or_code' },
]

export function ContextAdaptationApps({ disabled }: { disabled: boolean }) {
  const { t } = useTranslation()

  return (
    <div
      role="group"
      aria-label={t('settings.contextAdaptationApps')}
      aria-disabled={disabled}
      className={`mt-2 flex min-w-0 items-center gap-1 transition-opacity ${disabled ? 'opacity-40' : 'opacity-100'}`}
    >
      {REPRESENTATIVE_APPS.map((app) => (
        <span
          key={app.iconKey}
          role="img"
          aria-label={app.label}
          title={app.label}
          className={`grid h-5 w-5 shrink-0 place-items-center rounded-[4px] ${app.hideAtMinimum ? 'context-app--compact-duplicate' : ''}`}
        >
          <AppLogo iconKey={app.iconKey} family={app.family} />
        </span>
      ))}
      <span className="context-app-count--full ml-0.5 flex-none text-[11px] text-text-tertiary">
        +63
      </span>
      <span className="context-app-count--compact ml-0.5 flex-none text-[11px] text-text-tertiary">
        +65
      </span>
    </div>
  )
}

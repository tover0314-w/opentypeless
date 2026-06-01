import { useTranslation } from 'react-i18next'
import { Settings, History, LogOut, CircleUser, Crown, AppWindow } from 'lucide-react'

interface Props {
  onClose: () => void
}

export function CapsuleContextMenu({ onClose }: Props) {
  const { t } = useTranslation()

  const openMainWindow = async (hash: string) => {
    try {
      const { WebviewWindow } = await import('@tauri-apps/api/webviewWindow')
      const { emitTo } = await import('@tauri-apps/api/event')
      const mainWin = await WebviewWindow.getByLabel('main')
      if (mainWin) {
        await mainWin.show()
        await mainWin.setFocus()
        await emitTo('main', 'navigate', hash)
      }
    } catch {
      /* ignore – window may not exist */
    }
  }

  const items = [
    {
      icon: AppWindow,
      label: t('capsule.menu.openMainWindow'),
      onClick: () => {
        openMainWindow('#/')
        onClose()
      },
    },
    { type: 'separator' as const },
    {
      icon: Settings,
      label: t('capsule.menu.settings'),
      onClick: () => {
        openMainWindow('#/settings')
        onClose()
      },
    },
    {
      icon: History,
      label: t('capsule.menu.history'),
      onClick: () => {
        openMainWindow('#/history')
        onClose()
      },
    },
    {
      icon: CircleUser,
      label: t('capsule.menu.account'),
      onClick: () => {
        openMainWindow('#/account')
        onClose()
      },
    },
    {
      icon: Crown,
      label: t('capsule.menu.upgrade'),
      onClick: () => {
        openMainWindow('#/upgrade')
        onClose()
      },
    },
    { type: 'separator' as const },
    {
      icon: LogOut,
      label: t('capsule.menu.exit'),
      onClick: () => {
        import('@tauri-apps/api/core')
          .then(({ invoke }) => invoke('plugin:process|exit', { code: 0 }))
          .catch(() => {})
        onClose()
      },
    },
  ]

  return (
    <>
      <div className="fixed inset-0 z-40" onClick={onClose} />
      <div
        className="relative z-50 min-w-[140px] py-1 rounded-[14px] jelly-card shadow-float"
        role="menu"
      >
        {items.map((item, i) => {
          if ('type' in item && item.type === 'separator') {
            return <div key={i} className="my-1 border-t border-border" />
          }
          const {
            icon: Icon,
            label,
            onClick,
          } = item as { icon: typeof Settings; label: string; onClick: () => void }
          return (
            <button
              key={label}
              onClick={onClick}
              role="menuitem"
              className="flex items-center gap-2.5 w-full px-3 py-1.5 text-[13px] text-text-primary hover:bg-bg-tertiary transition-colors bg-transparent border-none cursor-pointer text-left"
            >
              <Icon size={14} />
              {label}
            </button>
          )
        })}
      </div>
    </>
  )
}

import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { openUrl } from '@tauri-apps/plugin-opener'
import { ChevronDown, Copy, Download, Loader2, Share, X } from 'lucide-react'
import { copyPngToClipboard, savePngWithDialog, type UsageStats } from '../../lib/tauri'
import { toast } from '../toast-service'
import { Modal } from '../shared/Modal'
import { Keycaps } from '../shared/Keycaps'
import {
  SHARE_CARD_KINDS,
  buildShareViewModel,
  shareFileName,
  xShareUrl,
  type ShareCardKind,
} from './shareInsightsModel'
import { canvasToPng, narrowWeekdays, renderShareCard, shareCardSize } from './shareCardCanvas'

type ShareAction = 'copy' | 'download' | 'x'

const THUMBNAIL_ASPECT: Record<ShareCardKind, string> = {
  insights: '85 / 78',
  highlights: '16 / 9',
  activity: '16 / 9',
}

const MENU_CLOSE_DELAY_MS = 120

export function ShareInsightsDialog({
  stats,
  onClose,
}: {
  stats: UsageStats
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const [selected, setSelected] = useState<ShareCardKind>('insights')
  const [busy, setBusy] = useState<ShareAction | null>(null)
  const busyRef = useRef<ShareAction | null>(null)
  const pngCache = useRef(new Map<ShareCardKind, Promise<Uint8Array>>())

  const canvases = useMemo(() => {
    const model = buildShareViewModel(stats, t, i18n.language, new Date())
    const weekdays = narrowWeekdays(i18n.language)
    return Object.fromEntries(
      SHARE_CARD_KINDS.map((kind) => [kind, renderShareCard(kind, model, { weekdays })]),
    ) as Record<ShareCardKind, HTMLCanvasElement>
  }, [stats, t, i18n.language])

  const thumbnails = useMemo(
    () =>
      Object.fromEntries(
        SHARE_CARD_KINDS.map((kind) => [kind, canvases[kind].toDataURL('image/png')]),
      ) as Record<ShareCardKind, string>,
    [canvases],
  )

  const png = (kind: ShareCardKind) => {
    const cached = pngCache.current.get(kind)
    if (cached) return cached
    const pending = canvasToPng(canvases[kind]).catch((error: unknown) => {
      if (pngCache.current.get(kind) === pending) pngCache.current.delete(kind)
      throw error
    })
    pngCache.current.set(kind, pending)
    return pending
  }

  const run = async (action: ShareAction, task: (bytes: Uint8Array) => Promise<void>) => {
    if (busyRef.current !== null) return
    const kind = selected
    busyRef.current = action
    setBusy(action)
    try {
      await task(await png(kind))
    } catch {
      toast.error(t(`home.share.feedback.${action}Failed`))
    } finally {
      busyRef.current = null
      setBusy(null)
    }
  }

  const copyImage = () =>
    run('copy', async (bytes) => {
      await copyPngToClipboard(bytes)
      toast.success(t('home.share.feedback.copySuccess'))
    })

  const downloadImage = () =>
    run('download', async (bytes) => {
      try {
        await savePngWithDialog(bytes, shareFileName(selected, new Date()))
      } catch (error) {
        if (String(error) === 'cancelled') return
        throw error
      }
      toast.success(t('home.share.feedback.downloadSuccess'))
    })

  const shareOnX = () =>
    run('x', async (bytes) => {
      await copyPngToClipboard(bytes)
      await openUrl(xShareUrl(t('home.share.socialText')))
      toast.success(t('home.share.feedback.xSuccess'))
    })

  const { width, height } = shareCardSize(selected)

  return (
    <Modal title={t('home.share.dialogLabel')} onClose={onClose} width={940} bare>
      <div className="flex h-[578px] w-[940px]" data-testid="share-insights-dialog">
        <div className="flex w-[202px] shrink-0 flex-col gap-4 bg-fill-thin p-6">
          {SHARE_CARD_KINDS.map((kind) => {
            const active = kind === selected
            return (
              <button
                key={kind}
                type="button"
                aria-pressed={active}
                data-testid={`share-thumbnail-${kind}`}
                onClick={() => setSelected(kind)}
                className={`flex w-full cursor-pointer flex-col items-center gap-2 rounded-[12px] p-2 transition-colors ${
                  active ? 'bg-fill-selected' : 'hover:bg-fill-hover'
                }`}
              >
                <span
                  className="block w-full overflow-hidden rounded-[6px] border border-border-thin"
                  style={{ aspectRatio: THUMBNAIL_ASPECT[kind] }}
                >
                  <img
                    src={thumbnails[kind]}
                    alt=""
                    draggable={false}
                    className="h-full w-full object-cover"
                  />
                </span>
                <span className="text-[14px] leading-4 text-text-secondary">
                  {t(`home.share.card.${kind}`)}
                </span>
              </button>
            )
          })}
        </div>

        <div className="relative flex w-[738px] flex-col p-6">
          <button
            type="button"
            aria-label={t('common.close')}
            onClick={onClose}
            className="absolute right-3 top-3 flex h-6 w-6 cursor-pointer items-center justify-center rounded-full bg-fill-tough text-text-secondary transition-colors hover:bg-fill-selected"
          >
            <X size={14} />
          </button>
          <div className="flex h-[466px] w-[690px] select-none items-center justify-center">
            <div
              className="shrink-0 overflow-hidden rounded-[16px] shadow-[0_1px_0_var(--color-text-tertiary),0_12px_16px_-4px_rgba(17,17,17,0.08),0_4px_6px_-2px_rgba(17,17,17,0.03)]"
              style={{ width, height }}
            >
              <img
                src={thumbnails[selected]}
                alt={t(`home.share.previewTitle.${selected}`)}
                data-testid="share-card-preview"
                data-card-kind={selected}
                draggable={false}
                style={{ width, height }}
              />
            </div>
          </div>
          <div className="flex justify-center gap-2 pt-6">
            <button
              type="button"
              data-autofocus
              disabled={busy !== null}
              onClick={copyImage}
              className="btn-primary flex h-10 min-w-[146px] cursor-pointer items-center justify-center gap-2 px-4 text-[16px] leading-5 disabled:cursor-default disabled:opacity-60"
            >
              {busy === 'copy' ? (
                <Loader2 size={16} className="animate-spin" />
              ) : (
                <Copy size={13} />
              )}
              {t('home.share.copyImage')}
            </button>
            <ShareMenu disabled={busy !== null} onDownload={downloadImage} onShareX={shareOnX} />
          </div>
        </div>
      </div>
    </Modal>
  )
}

/** Typeless opens this menu on hover and keeps it open while the pointer moves into it. */
function ShareMenu({
  disabled,
  onDownload,
  onShareX,
}: {
  disabled: boolean
  onDownload: () => void
  onShareX: () => void
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const closeTimer = useRef<number | null>(null)

  const cancelClose = () => {
    if (closeTimer.current !== null) {
      window.clearTimeout(closeTimer.current)
      closeTimer.current = null
    }
  }
  const show = () => {
    if (disabled) return
    cancelClose()
    setOpen(true)
  }
  const scheduleClose = () => {
    cancelClose()
    closeTimer.current = window.setTimeout(() => {
      setOpen(false)
      closeTimer.current = null
    }, MENU_CLOSE_DELAY_MS)
  }
  const choose = (action: () => void) => {
    cancelClose()
    setOpen(false)
    action()
  }

  useEffect(() => cancelClose, [])

  const item =
    'flex w-full cursor-pointer items-center gap-2 whitespace-nowrap rounded-[8px] px-3 py-2 text-left text-text-secondary transition-colors hover:bg-fill-hover disabled:cursor-default disabled:opacity-50'

  return (
    <div className="relative" onMouseEnter={show} onMouseLeave={scheduleClose}>
      {open ? (
        <div
          role="menu"
          className="surface-popover absolute bottom-[calc(100%+6px)] left-1/2 z-10 min-w-[172px] -translate-x-1/2 p-1"
        >
          <button
            type="button"
            role="menuitem"
            disabled={disabled}
            onClick={() => choose(onDownload)}
            className={item}
          >
            <Download size={14} className="shrink-0" />
            <span className="text-[14px] leading-[19.6px]">{t('home.share.downloadImage')}</span>
          </button>
          <hr className="my-1 border-border-thin" />
          <button
            type="button"
            role="menuitem"
            disabled={disabled}
            onClick={() => choose(onShareX)}
            className={item}
          >
            <XLogo />
            <span>
              <span className="block text-[14px] leading-[22px]">{t('home.share.shareX')}</span>
              <span className="flex items-center gap-1 text-[12px] leading-[18px] text-text-tertiary">
                {t('home.share.shareXPress')}
                <Keycaps hotkey="Cmd+V" className="share-keycaps text-text-primary" />
                {t('home.share.shareXPaste')}
              </span>
            </span>
          </button>
        </div>
      ) : null}
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={t('home.share.more')}
        disabled={disabled}
        onFocus={show}
        onClick={() => (open ? setOpen(false) : show())}
        className={`flex h-10 w-[66px] cursor-pointer items-center justify-center gap-2 rounded-full px-4 text-text-primary transition-colors hover:bg-fill-selected disabled:cursor-default disabled:opacity-50 ${
          open ? 'bg-fill-selected' : 'bg-fill-tough'
        }`}
      >
        <Share size={16} className="shrink-0" />
        <ChevronDown
          size={10}
          className={`shrink-0 transition-transform ${open ? 'rotate-180' : ''}`}
        />
      </button>
    </div>
  )
}

function XLogo() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true" className="shrink-0">
      <path
        fill="currentColor"
        d="M17.75 3h3.07l-6.7 7.66L22 21h-6.17l-4.83-6.32L5.47 21H2.4l7.17-8.2L2 3h6.33l4.37 5.77L17.75 3Zm-1.08 16.17h1.7L7.4 4.74H5.58l11.1 14.43Z"
      />
    </svg>
  )
}

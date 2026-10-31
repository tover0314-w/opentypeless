import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertCircle, Check, Copy, Mic, Sparkles, X } from 'lucide-react'
import { MarkdownView } from '../../lib/markdown'
import { BrandMark } from '../MainLayout/BrandMark'

// Typeless answer-card geometry (REA: CARD_MIN_HEIGHT 400, CARD_MAX_HEIGHT 700, width 744).
const PROMPT_COLLAPSED_PX = 44
const COPIED_RESET_MS = 3000

function useCopy() {
  const [copied, setCopied] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current)
    },
    [],
  )
  const copy = useCallback((text: string) => {
    navigator.clipboard
      .writeText(text)
      .then(() => {
        setCopied(true)
        if (timer.current) clearTimeout(timer.current)
        timer.current = setTimeout(() => setCopied(false), COPIED_RESET_MS)
      })
      .catch(() => {})
  }, [])
  return { copied, copy }
}

function CopyButton({ text, label }: { text: string; label: string }) {
  const { t } = useTranslation()
  const { copied, copy } = useCopy()
  return (
    <button
      type="button"
      aria-label={label}
      title={copied ? t('ask.copied') : t('ask.copy')}
      onClick={() => copy(text)}
      className={`grid h-[22px] w-[22px] shrink-0 place-items-center rounded-[6px] border-none cursor-pointer transition-colors ${
        copied
          ? 'bg-fill-selected text-success'
          : 'bg-transparent text-text-tertiary hover:bg-fill-hover hover:text-text-primary'
      }`}
    >
      {copied ? <Check size={14} /> : <Copy size={14} />}
    </button>
  )
}

function PromptBlock({ prompt, selectedText }: { prompt: string; selectedText?: string | null }) {
  const { t } = useTranslation()
  const contentRef = useRef<HTMLDivElement>(null)
  const [overflowing, setOverflowing] = useState(false)
  const [expanded, setExpanded] = useState(false)

  useLayoutEffect(() => {
    const node = contentRef.current
    if (node) setOverflowing(node.scrollHeight > PROMPT_COLLAPSED_PX + 1)
  }, [prompt, selectedText])

  const collapsed = overflowing && !expanded
  const copyText = selectedText ? `${prompt}\n${selectedText}` : prompt

  return (
    <div className="flex items-start gap-2 px-2" data-testid="ask-prompt">
      <Mic size={14} className="mt-1 shrink-0 text-text-tertiary" aria-hidden="true" />
      <div className="relative min-w-0 flex-1">
        <div
          ref={contentRef}
          className="overflow-hidden transition-[max-height] duration-300 ease-in-out"
          style={{ maxHeight: collapsed ? PROMPT_COLLAPSED_PX : 2000 }}
        >
          <p className="m-0 whitespace-pre-wrap break-words text-[14px] leading-[22px] text-text-secondary">
            {prompt}
          </p>
          {selectedText && (
            <div className="mt-1 flex gap-2">
              <span className="w-px shrink-0 self-stretch bg-border" aria-hidden="true" />
              <p className="m-0 whitespace-pre-wrap break-words text-[14px] leading-[22px] text-text-tertiary">
                {selectedText}
              </p>
            </div>
          )}
        </div>
        {overflowing && (
          <div
            className={`flex justify-end ${collapsed ? 'absolute bottom-0 right-0 pl-[50px]' : 'mt-1'}`}
            style={
              collapsed
                ? {
                    background:
                      'linear-gradient(to right, transparent, var(--color-bg-bottom) 50px)',
                  }
                : undefined
            }
          >
            <button
              type="button"
              onClick={() => setExpanded((value) => !value)}
              className="rounded-full border-none bg-fill-tough px-2 text-[13px] leading-[22px] text-text-secondary cursor-pointer hover:bg-fill-selected"
            >
              {collapsed ? t('ask.more') : t('ask.showLess')}
            </button>
          </div>
        )}
      </div>
      <CopyButton text={copyText} label={t('ask.copyQuestion')} />
    </div>
  )
}

export interface AskAnswerCardProps {
  prompt?: string | null
  selectedText?: string | null
  answer?: string | null
  error?: string | null
  /** Small status line such as "Opened Google search" for non-answer routes. */
  context?: string | null
  canCopyAnswer?: boolean
  onClose: () => void
  onOpenLink?: (url: string) => void
  /** Reports the card's rendered height so the host window can hug it. */
  onHeightChange?: (height: number) => void
}

/** Typeless-style Ask anything answer card: logo header, spoken prompt, Markdown answer. */
export function AskAnswerCard({
  prompt,
  selectedText,
  answer,
  error,
  context,
  canCopyAnswer = true,
  onClose,
  onOpenLink,
  onHeightChange,
}: AskAnswerCardProps) {
  const { t } = useTranslation()
  const cardRef = useRef<HTMLElement>(null)

  useEffect(() => {
    const node = cardRef.current
    if (!node || !onHeightChange || typeof ResizeObserver === 'undefined') return
    let last = 0
    const observer = new ResizeObserver(() => {
      const height = Math.ceil(node.getBoundingClientRect().height)
      if (Math.abs(height - last) < 10) return
      last = height
      onHeightChange(height)
    })
    observer.observe(node)
    return () => observer.disconnect()
  }, [onHeightChange])

  return (
    <section
      ref={cardRef}
      data-testid="ask-floating-note"
      className="flex max-h-[700px] w-[744px] max-w-full flex-col gap-4 overflow-hidden rounded-[16px] border border-border bg-bg-bottom p-4 shadow-float"
    >
      <header
        className="relative flex h-5 shrink-0 items-center justify-center"
        data-tauri-drag-region
      >
        <span className="flex items-center gap-1.5 text-[15px] font-semibold text-text-primary">
          <BrandMark size={16} />
          {t('app.name')}
        </span>
        <button
          type="button"
          aria-label={t('ask.close')}
          title={`${t('ask.close')} (Esc)`}
          onClick={onClose}
          className="absolute right-0 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded-full border-none bg-transparent p-[5px] text-text-tertiary cursor-pointer hover:bg-fill-hover hover:text-text-primary"
        >
          <X size={14} />
        </button>
      </header>

      <div className="-mr-4 flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto pr-4">
        {prompt && !error && <PromptBlock prompt={prompt} selectedText={selectedText} />}

        {error ? (
          <div className="flex items-start gap-2 rounded-[12px] border border-border-thin bg-bg-primary px-4 py-3">
            <AlertCircle size={16} className="mt-0.5 shrink-0 text-error" aria-hidden="true" />
            <div className="min-w-0">
              <p className="m-0 text-[14px] font-medium text-text-primary">{t('ask.errorTitle')}</p>
              <p className="m-0 mt-1 whitespace-pre-wrap break-words text-[13px] leading-5 text-text-secondary">
                {error}
              </p>
            </div>
          </div>
        ) : (
          answer && (
            <div className="overflow-hidden rounded-[12px] border border-border-thin bg-bg-primary">
              <div className="flex h-[51px] items-center justify-between border-b border-border-thin py-3 pl-4 pr-3">
                <span className="flex items-center gap-2 text-[16px] font-semibold leading-5 text-text-secondary">
                  <Sparkles size={16} aria-hidden="true" />
                  {t('ask.answerTitle')}
                  {context && (
                    <span className="truncate text-[12px] font-normal text-text-tertiary">
                      {context}
                    </span>
                  )}
                </span>
                {canCopyAnswer && <CopyButton text={answer} label={t('ask.copyAnswer')} />}
              </div>
              <div className="select-text px-4 py-3">
                <MarkdownView source={answer} onOpenLink={onOpenLink} className="answer-markdown" />
              </div>
            </div>
          )
        )}
      </div>
    </section>
  )
}

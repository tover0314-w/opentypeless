import { useState, useEffect, useRef } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { CheckCircle2, XCircle, Info } from 'lucide-react'
import { spring } from '../lib/animations'
import { registerToastHandler, type ToastType } from './toast-service'

interface ToastMessage {
  id: number
  text: string
  type: ToastType
}

interface ToastExpiry {
  timer: ReturnType<typeof setTimeout> | null
  remaining: number
  started: number
  hovered: boolean
  focused: boolean
}

const icons: Record<ToastType, typeof Info> = {
  success: CheckCircle2,
  error: XCircle,
  info: Info,
}

const colors: Record<ToastType, string> = {
  success: 'text-success',
  error: 'text-error',
  info: 'text-accent',
}

export function ToastContainer() {
  const [toasts, setToasts] = useState<ToastMessage[]>([])
  const nextId = useRef(0)
  const expiries = useRef(new Map<number, ToastExpiry>())

  const scheduleExpiry = (id: number, expiry: ToastExpiry) => {
    expiry.started = performance.now()
    expiry.timer = setTimeout(() => {
      expiries.current.delete(id)
      setToasts((prev) => prev.filter((message) => message.id !== id))
    }, expiry.remaining)
  }

  const setReading = (id: number, source: 'hovered' | 'focused', reading: boolean) => {
    const expiry = expiries.current.get(id)
    if (!expiry) return
    expiry[source] = reading
    if (expiry.hovered || expiry.focused) {
      if (expiry.timer !== null) {
        clearTimeout(expiry.timer)
        expiry.timer = null
        expiry.remaining = Math.max(0, expiry.remaining - (performance.now() - expiry.started))
      }
    } else if (expiry.timer === null) {
      scheduleExpiry(id, expiry)
    }
  }

  useEffect(() => {
    const currentExpiries = expiries.current
    const unregister = registerToastHandler((text: string, type: ToastType = 'info') => {
      const id = ++nextId.current
      setToasts((prev) => [...prev, { id, text, type }])
      const expiry: ToastExpiry = {
        timer: null,
        remaining: 3000,
        started: 0,
        hovered: false,
        focused: false,
      }
      currentExpiries.set(id, expiry)
      scheduleExpiry(id, expiry)
    })
    return () => {
      unregister()
      currentExpiries.forEach((expiry) => {
        if (expiry.timer !== null) clearTimeout(expiry.timer)
      })
      currentExpiries.clear()
    }
  }, [])

  return (
    <div className="fixed top-4 right-4 z-[9999] flex max-h-[calc(100dvh-32px)] flex-col gap-2 overflow-y-auto pointer-events-auto">
      <AnimatePresence>
        {toasts.map((t) => {
          const Icon = icons[t.type]
          return (
            <motion.div
              key={t.id}
              initial={{ opacity: 0, x: 40, scale: 0.95 }}
              animate={{ opacity: 1, x: 0, scale: 1 }}
              exit={{ opacity: 0, x: 40, scale: 0.95 }}
              transition={spring.jellyGentle}
              className="pointer-events-auto flex shrink-0 items-center gap-2 px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] shadow-lg text-[13px] text-text-primary max-w-[min(300px,calc(100vw-32px))]"
              role="alert"
              onMouseEnter={() => setReading(t.id, 'hovered', true)}
              onMouseLeave={() => setReading(t.id, 'hovered', false)}
              onFocus={() => setReading(t.id, 'focused', true)}
              onBlur={(event) => {
                if (!event.currentTarget.contains(event.relatedTarget)) {
                  setReading(t.id, 'focused', false)
                }
              }}
            >
              <Icon size={14} className={`flex-shrink-0 ${colors[t.type]}`} />
              <span
                tabIndex={0}
                className="min-w-0 max-h-[min(240px,calc(100dvh-56px))] overflow-y-auto overscroll-contain [overflow-wrap:anywhere] focus-visible:outline-2 focus-visible:outline-border-focus"
              >
                {t.text}
              </span>
            </motion.div>
          )
        })}
      </AnimatePresence>
    </div>
  )
}

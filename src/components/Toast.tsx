import { useState, useEffect, useCallback } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { CheckCircle2, XCircle, Info } from 'lucide-react'
import { spring } from '../lib/animations'
import { setToastDispatcher, type ToastType } from './toastApi'

interface ToastMessage {
  id: number
  text: string
  type: ToastType
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

  const remove = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }, [])

  useEffect(() => {
    setToastDispatcher((text: string, type: ToastType = 'info') => {
      const id = Date.now()
      setToasts((prev) => [...prev, { id, text, type }])
      setTimeout(() => remove(id), 3000)
    })
    return () => {
      setToastDispatcher(() => {})
    }
  }, [remove])

  return (
    <div className="fixed top-4 right-4 z-[9999] flex flex-col gap-2 pointer-events-none">
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
              className="pointer-events-auto flex items-center gap-2 px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] shadow-lg text-[13px] text-text-primary max-w-[300px]"
              role="alert"
            >
              <Icon size={14} className={`flex-shrink-0 ${colors[t.type]}`} />
              <span>{t.text}</span>
            </motion.div>
          )
        })}
      </AnimatePresence>
    </div>
  )
}

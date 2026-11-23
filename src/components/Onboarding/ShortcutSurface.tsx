import { motion } from 'framer-motion'
import { hotkeyParts } from '../../lib/hotkeyLabels'

export function ShortcutSurface({ shortcut, pressed }: { shortcut: string; pressed: boolean }) {
  return (
    <div className="flex w-full max-w-[400px] flex-wrap justify-center gap-4 rounded-xl bg-bg-elevated p-5">
      {hotkeyParts(shortcut).map((part, index) => (
        <motion.kbd
          key={`${index}-${part}`}
          animate={{ scale: pressed ? 0.94 : 1 }}
          transition={{ duration: 0.15 }}
          className={`grid h-20 min-w-20 place-items-center rounded-xl border-2 px-3 text-[36px] font-medium leading-none ${
            pressed ? 'border-accent bg-accent text-white' : 'border-text-primary bg-bg-primary'
          }`}
        >
          {part}
        </motion.kbd>
      ))}
    </div>
  )
}

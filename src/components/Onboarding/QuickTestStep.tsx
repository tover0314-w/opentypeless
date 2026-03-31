import { useState, useEffect } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { Mic, FileText, Sparkles, Type, Keyboard, Check } from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { CapsuleLogo } from '../Capsule/CapsuleLogo'

type DemoPhase = 'idle' | 'recording' | 'transcribing' | 'polishing' | 'complete'

const PHASE_SEQUENCE: DemoPhase[] = ['idle', 'recording', 'transcribing', 'polishing', 'complete']
const PHASE_DURATION: Record<DemoPhase, number> = {
  idle: 2000,
  recording: 3000,
  transcribing: 2200,
  polishing: 2200,
  complete: 2000,
}

const STAGES = [
  { icon: Mic, label: 'Record', key: 'recording' },
  { icon: FileText, label: 'STT', key: 'transcribing' },
  { icon: Sparkles, label: 'LLM', key: 'polishing' },
  { icon: Type, label: 'Output', key: 'complete' },
] as const

const DEMO_RAW = 'hello um can you help me with this'
const DEMO_POLISHED = 'Hello, can you help me with this?'

const PHASE_DESC: Record<DemoPhase, string> = {
  idle: 'Press the hotkey to start',
  recording: 'Speaking...',
  transcribing: 'Converting speech to text',
  polishing: 'AI refines your words',
  complete: 'Done — typed into your app',
}

export function QuickTestStep() {
  const config = useAppStore((s) => s.config)
  const [phase, setPhase] = useState<DemoPhase>('idle')
  const [rawChars, setRawChars] = useState(0)
  const [polishedChars, setPolishedChars] = useState(0)
  const [seconds, setSeconds] = useState(0)

  // Auto-advance through demo phases in a loop
  useEffect(() => {
    const idx = PHASE_SEQUENCE.indexOf(phase)
    const next = PHASE_SEQUENCE[(idx + 1) % PHASE_SEQUENCE.length]
    const timer = setTimeout(() => {
      setPhase(next)
      if (next === 'idle') {
        setRawChars(0)
        setPolishedChars(0)
        setSeconds(0)
      }
    }, PHASE_DURATION[phase])
    return () => clearTimeout(timer)
  }, [phase])

  // Recording second counter
  useEffect(() => {
    if (phase !== 'recording') return
    setSeconds(0)
    const timer = setInterval(() => setSeconds((s) => s + 1), 1000)
    return () => clearInterval(timer)
  }, [phase])

  // Typewriter: raw text during transcribing
  useEffect(() => {
    if (phase !== 'transcribing') return
    setRawChars(0)
    const interval = PHASE_DURATION.transcribing / (DEMO_RAW.length + 4)
    const timer = setInterval(() => {
      setRawChars((prev) => {
        if (prev >= DEMO_RAW.length) {
          clearInterval(timer)
          return prev
        }
        return prev + 1
      })
    }, interval)
    return () => clearInterval(timer)
  }, [phase])

  // Typewriter: polished text during polishing
  useEffect(() => {
    if (phase !== 'polishing') return
    setPolishedChars(0)
    const interval = PHASE_DURATION.polishing / (DEMO_POLISHED.length + 4)
    const timer = setInterval(() => {
      setPolishedChars((prev) => {
        if (prev >= DEMO_POLISHED.length) {
          clearInterval(timer)
          return prev
        }
        return prev + 1
      })
    }, interval)
    return () => clearInterval(timer)
  }, [phase])

  const activeIdx = STAGES.findIndex((s) => s.key === phase)

  const isActive = phase !== 'idle'
  const capsuleClass = isActive
    ? 'jelly-capsule-active text-white'
    : 'jelly-capsule text-neutral-700'

  return (
    <div className="space-y-4">
      {/* Hotkey hint — pulses on idle to draw attention */}
      <div className="flex justify-center">
        <motion.div
          className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-bg-secondary rounded-[8px] border border-border"
          animate={phase === 'idle' ? { scale: [1, 1.03, 1] } : { scale: 1 }}
          transition={
            phase === 'idle'
              ? { repeat: Infinity, duration: 1.5, ease: 'easeInOut' }
              : { duration: 0.2 }
          }
        >
          <Keyboard size={12} className="text-text-tertiary" />
          <span className="text-[11px] text-text-secondary">
            {config.hotkey_mode === 'hold' ? 'Hold' : 'Press'}{' '}
            <kbd className="px-1 py-0.5 bg-bg-tertiary rounded-[4px] text-[11px] font-mono text-text-primary font-medium border border-border">
              {config.hotkey}
            </kbd>{' '}
            {config.hotkey_mode === 'hold' ? 'to talk' : 'to start/stop'}
          </span>
        </motion.div>
      </div>

      {/* Capsule preview — mimics the real desktop capsule */}
      <div className="flex flex-col items-center gap-2">
        <div className="relative">
          <motion.div
            className={`relative flex items-center justify-center select-none ${capsuleClass}`}
            style={{ width: 48, height: 48 }}
            animate={
              phase === 'idle'
                ? { scale: [1, 1.03, 1] }
                : phase === 'recording'
                  ? { scale: [1, 1.04, 1] }
                  : {}
            }
            transition={
              phase === 'idle' || phase === 'recording'
                ? {
                    repeat: Infinity,
                    duration: phase === 'idle' ? 3 : 1.5,
                    ease: 'easeInOut',
                  }
                : { type: 'spring', stiffness: 400, damping: 25 }
            }
          >
            <div className="relative z-10">
              {phase === 'complete' ? (
                <motion.div
                  initial={{ scale: 0 }}
                  animate={{ scale: 1 }}
                  transition={{ type: 'spring', stiffness: 500, damping: 20 }}
                >
                  <Check size={20} />
                </motion.div>
              ) : (
                <CapsuleLogo size={20} />
              )}
            </div>
          </motion.div>

          {/* Pulse ring while recording */}
          {phase === 'recording' && (
            <motion.div
              className="absolute inset-0 rounded-full border-2 border-neutral-400/30"
              animate={{ scale: [1, 1.5], opacity: [0.4, 0] }}
              transition={{ repeat: Infinity, duration: 1.2, ease: 'easeOut' }}
            />
          )}
        </div>

        {/* Phase label — crossfade on change */}
        <AnimatePresence mode="wait">
          <motion.p
            key={phase}
            className="text-[12px] text-text-tertiary text-center"
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            transition={{ duration: 0.12 }}
          >
            {PHASE_DESC[phase]}
          </motion.p>
        </AnimatePresence>
      </div>

      {/* Pipeline step indicators */}
      <div className="flex items-center justify-center gap-0.5">
        {STAGES.map((stage, i) => {
          const Icon = stage.icon
          const isCurrent = i === activeIdx
          const isDone = activeIdx > i

          return (
            <div key={stage.key} className="flex items-center">
              <motion.div
                className={`flex flex-col items-center gap-1 px-2.5 py-1.5 rounded-[8px] transition-colors ${
                  isCurrent ? 'bg-accent/10' : isDone ? 'bg-success/10' : 'bg-bg-secondary'
                }`}
                animate={isCurrent ? { scale: [1, 1.05, 1] } : {}}
                transition={isCurrent ? { repeat: Infinity, duration: 1.5 } : {}}
              >
                <Icon
                  size={14}
                  className={
                    isCurrent ? 'text-accent' : isDone ? 'text-success' : 'text-text-tertiary'
                  }
                />
                <span
                  className={`text-[10px] ${
                    isCurrent ? 'text-accent' : isDone ? 'text-success' : 'text-text-tertiary'
                  }`}
                >
                  {stage.label}
                </span>
              </motion.div>
              {i < STAGES.length - 1 && (
                <div className={`w-3 h-[1px] ${isDone ? 'bg-success/40' : 'bg-border'}`} />
              )}
            </div>
          )
        })}
      </div>

      {/* Stage output card — shows what each stage produces */}
      <AnimatePresence mode="wait">
        {phase !== 'idle' && (
          <motion.div
            key={phase}
            className="bg-bg-secondary rounded-[10px] p-3 text-[12px]"
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.15 }}
          >
            {phase === 'recording' && (
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <motion.div
                    className="w-2 h-2 rounded-full bg-red-500"
                    animate={{ opacity: [1, 0.4, 1] }}
                    transition={{ repeat: Infinity, duration: 1.2 }}
                  />
                  <FakeWaveform />
                </div>
                <span className="font-mono text-text-tertiary text-[11px] tabular-nums">
                  0:{String(seconds).padStart(2, '0')}
                </span>
              </div>
            )}

            {phase === 'transcribing' && (
              <div>
                <StageLabel>Transcribing</StageLabel>
                <p className="text-text-secondary">
                  {DEMO_RAW.slice(0, rawChars)}
                  <BlinkingCursor color="bg-text-tertiary" />
                </p>
              </div>
            )}

            {phase === 'polishing' && (
              <div className="space-y-2">
                <div>
                  <StageLabel>Raw</StageLabel>
                  <p className="text-text-tertiary line-through">{DEMO_RAW}</p>
                </div>
                <div>
                  <StageLabel>Polished</StageLabel>
                  <p className="text-[13px] text-text-primary">
                    {DEMO_POLISHED.slice(0, polishedChars)}
                    <BlinkingCursor color="bg-accent" />
                  </p>
                </div>
              </div>
            )}

            {phase === 'complete' && (
              <div className="space-y-2">
                <div>
                  <StageLabel>Raw</StageLabel>
                  <p className="text-text-tertiary line-through">{DEMO_RAW}</p>
                </div>
                <div>
                  <StageLabel>Result</StageLabel>
                  <p className="text-[13px] text-text-primary">{DEMO_POLISHED}</p>
                </div>
              </div>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}

/** Fake waveform bars — fixed heights, staggered animation */
function FakeWaveform() {
  const bars = [6, 12, 8, 15, 5, 11, 4]
  return (
    <div className="flex items-center gap-[2px] h-4">
      {bars.map((maxH, i) => (
        <motion.div
          key={i}
          className="w-[2px] rounded-full bg-text-secondary"
          animate={{ height: [3, maxH, 3] }}
          transition={{
            repeat: Infinity,
            duration: 0.7,
            delay: i * 0.09,
            ease: 'easeInOut',
          }}
        />
      ))}
    </div>
  )
}

/** Blinking text cursor */
function BlinkingCursor({ color }: { color: string }) {
  return (
    <motion.span
      className={`inline-block w-[2px] h-[13px] ${color} align-middle ml-px`}
      animate={{ opacity: [1, 0] }}
      transition={{ repeat: Infinity, duration: 0.6 }}
    />
  )
}

/** Small label above stage output */
function StageLabel({ children }: { children: React.ReactNode }) {
  return <p className="text-[11px] text-text-tertiary mb-0.5">{children}</p>
}

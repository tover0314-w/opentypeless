import { useCallback, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { PracticeOptions } from '../../lib/tauri'
import { Modal } from '../shared/Modal'
import { GuidedPracticeFlow } from './GuidedPracticeFlow'

export function FeatureLesson({
  mode,
  onClose,
}: {
  mode: PracticeOptions['mode']
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [layout, setLayout] = useState<'split' | 'fullWidth'>('fullWidth')
  const [surface, setSurface] = useState<React.ReactNode>(null)
  const back = useRef<(() => void) | null>(null)
  const setBack = useCallback((handler: (() => void) | null) => {
    back.current = handler
  }, [])

  return (
    <Modal
      title={t(`home.modes.${mode}.title`)}
      onClose={onClose}
      closeOnBackdrop={false}
      width={940}
    >
      <div className="max-h-[70vh] overflow-y-auto">
        <button
          type="button"
          onClick={onClose}
          className="float-right cursor-pointer rounded-full border border-border px-4 py-2"
        >
          {t('common.close')}
        </button>
        <button
          type="button"
          onClick={() => back.current?.()}
          className="mb-4 cursor-pointer rounded-full border border-border px-4 py-2"
        >
          {t('onboarding.layout.back')}
        </button>
        <div className={layout === 'split' ? 'grid gap-6 md:grid-cols-2' : ''}>
          <GuidedPracticeFlow
            singleMode
            initialPage={{ mode, stage: 'introduction' }}
            onComplete={onClose}
            onExitBack={onClose}
            onLayoutChange={setLayout}
            onSurfaceChange={setSurface}
            onBackChange={setBack}
          />
          {layout === 'split' && surface && (
            <div className="flex min-w-0 items-center justify-center">{surface}</div>
          )}
        </div>
      </div>
    </Modal>
  )
}

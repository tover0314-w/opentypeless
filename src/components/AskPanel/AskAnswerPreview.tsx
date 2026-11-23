import { useState } from 'react'
import { AskAnswerCard } from './AskAnswerCard'
import { ModalControlsPreview } from '../shared/ModalControlsPreview'

const citations = Array.from({ length: 5 }, (_, index) => ({
  url: `https://example.invalid/source-${index + 1}`,
  title: `Synthetic source ${index + 1}`,
}))

export function AskAnswerPreview() {
  const [notice, setNotice] = useState('')
  return (
    <div data-answer-preview className="flex flex-col items-center gap-3 p-4">
      <ModalControlsPreview />
      <AskAnswerCard
        answer={'## Synthetic answer\n\nThis is local sample content, not a retrieved answer.'}
        citations={citations}
        canCopyAnswer={false}
        onClose={() => setNotice('Preview only: no desktop window was closed.')}
        onOpenLink={() => setNotice('Preview only: the external link was not opened.')}
      />
      <p role="status" className="text-[12px] text-text-secondary">
        {notice || 'Synthetic sources only. No search, recording, account or clipboard access.'}
      </p>
    </div>
  )
}

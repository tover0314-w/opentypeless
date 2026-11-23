interface Props {
  question: string
  answer: string
  citations?: Array<{ url: string; title: string }>
  askIntent?: 'open_question' | 'ask_selection' | null
  citationSupports?: Array<{ start: number; end: number; sourceIndex: number }>
  onClose: () => void
  onOpenLink: (url: string) => void
}

export function SavedAskAnswerDialog({
  question,
  answer,
  citations,
  askIntent,
  citationSupports,
  onClose,
  onOpenLink,
}: Props) {
  const { t } = useTranslation()
  return (
    <Modal
      title={t('history.viewAnswer')}
      onClose={onClose}
      width={744}
      bare
      closeOnBackdrop={false}
    >
      <AskAnswerCard
        prompt={question}
        answer={answer}
        citations={citations}
        citationSupports={citationSupports}
        draggable={false}
        context={
          askIntent === 'ask_selection'
            ? t('history.savedSelectionQuestion')
            : askIntent === 'open_question'
              ? t('history.savedOpenQuestion')
              : null
        }
        onClose={onClose}
        onOpenLink={onOpenLink}
      />
      {askIntent === 'ask_selection' && (
        <p className="m-0 px-5 pb-4 text-[12px] leading-5 text-text-secondary">
          {t('history.savedSelectionNotRetained')}
        </p>
      )}
    </Modal>
  )
}
import { useTranslation } from 'react-i18next'
import { Modal } from '../shared/Modal'
import { AskAnswerCard } from '../AskPanel/AskAnswerCard'

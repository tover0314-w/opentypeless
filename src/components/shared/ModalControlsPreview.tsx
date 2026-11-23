import { useState } from 'react'
import { Modal } from './Modal'
import { Select } from './Select'
import { ModelInput } from './ModelInput'

export function ModalControlsPreview() {
  const [open, setOpen] = useState(false)
  const [longContent, setLongContent] = useState(false)
  const [choice, setChoice] = useState('alpha')
  const [model, setModel] = useState('synthetic-alpha')
  return (
    <>
      <button
        type="button"
        className="btn-secondary"
        onClick={() => {
          setLongContent(false)
          setOpen(true)
        }}
      >
        Preview modal controls
      </button>
      <button
        type="button"
        className="btn-secondary"
        onClick={() => {
          setLongContent(true)
          setOpen(true)
        }}
      >
        Preview long modal content
      </button>
      {open && (
        <Modal title="Synthetic controls" onClose={() => setOpen(false)}>
          <div className="flex flex-col gap-3">
            <Select
              aria-label="Synthetic choice"
              value={choice}
              options={[
                { value: 'alpha', label: 'Alpha' },
                { value: 'beta', label: 'Beta' },
              ]}
              onChange={setChoice}
            />
            <ModelInput
              label="Synthetic model"
              value={model}
              models={['synthetic-alpha', 'synthetic-beta']}
              onChange={setModel}
            />
            <p>Local selection: {choice}</p>
            {longContent &&
              Array.from({ length: 30 }, (_, index) => (
                <p key={index}>Synthetic long content paragraph {index + 1}</p>
              ))}
            <button type="button" onClick={() => setOpen(false)}>
              Close preview controls
            </button>
          </div>
        </Modal>
      )}
    </>
  )
}

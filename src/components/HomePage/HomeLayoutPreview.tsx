import { useState } from 'react'
import { MainLayout } from '../MainLayout'
import { HomePage } from './index'

export function HomeLayoutPreview() {
  const [notice, setNotice] = useState('')
  return (
    <div>
      <div
        data-home-layout-preview
        className="h-[685px] w-full overflow-hidden"
        onClickCapture={(event) => {
          const button = (event.target as HTMLElement).closest('button')
          if (button?.getAttribute('aria-controls') === 'home-ask-panel') return
          event.preventDefault()
          event.stopPropagation()
          setNotice('Preview action blocked. No recording, saving or external navigation.')
        }}
        onChangeCapture={(event) => {
          event.preventDefault()
          event.stopPropagation()
        }}
        onKeyDownCapture={(event) => {
          if (event.key === 'Tab') return
          const button = (event.target as HTMLElement).closest('button')
          if (button?.getAttribute('aria-controls') === 'home-ask-panel') return
          event.preventDefault()
          event.stopPropagation()
        }}
      >
        <MainLayout>
          <HomePage />
        </MainLayout>
      </div>
      <p role="status">
        {notice || 'Isolated home layout. No account initialization or user history.'}
      </p>
    </div>
  )
}

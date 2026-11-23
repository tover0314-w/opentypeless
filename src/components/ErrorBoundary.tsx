import { Component, type ReactNode } from 'react'
import i18n from '../i18n'

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
}

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false }
  }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch() {
    console.error('Application rendering failed')
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="p-8 flex flex-col items-center justify-center h-screen font-sans text-text-primary">
          <h2 className="mb-2">{i18n.t('error.somethingWentWrong')}</h2>
          <p className="text-text-secondary mb-4">{i18n.t('error.unexpectedError')}</p>
          <button
            onClick={() => window.location.reload()}
            className="px-6 py-2 rounded-[6px] border border-border bg-bg-secondary cursor-pointer text-sm"
          >
            {i18n.t('error.reload')}
          </button>
        </div>
      )
    }

    return this.props.children
  }
}

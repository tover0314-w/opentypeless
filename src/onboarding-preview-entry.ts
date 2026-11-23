import { mountBrowserOnlyPreview } from './lib/onboardingPreviewBoundary'
import './styles/globals.css'

const mounted = await mountBrowserOnlyPreview(window, () => import('./onboarding-preview'))
if (!mounted) {
  const root = document.getElementById('root')
  if (root) {
    root.textContent = '此预览只允许在普通浏览器中打开，不会在原生应用中启动。'
  }
}

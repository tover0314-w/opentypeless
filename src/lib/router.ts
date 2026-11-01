import { useState, useEffect, useCallback } from 'react'

export type Page = 'home' | 'history' | 'dictionary' | 'models'
export type Modal = 'settings' | 'account' | 'upgrade'
export type Route = Page | Modal

const PAGES: readonly string[] = ['home', 'history', 'dictionary', 'models']
const MODALS: readonly string[] = ['settings', 'account', 'upgrade']

export interface AppLocation {
  page: Page
  modal: Modal | null
}

// Overlays keep the page they were opened from visible underneath.
let lastPage: Page = 'home'

function routeFromHash(): Route {
  const [path, query] = window.location.hash.replace('#/', '').split('?')
  // Model settings moved from the Settings modal to their own page; keep old deep links working.
  if (
    path === 'settings' &&
    query &&
    ['models', 'stt', 'llm'].includes(new URLSearchParams(query).get('pane') ?? '')
  ) {
    return 'models'
  }
  if (PAGES.includes(path) || MODALS.includes(path)) return path as Route
  return 'home'
}

export function parseLocation(): AppLocation {
  const route = routeFromHash()
  if (MODALS.includes(route)) return { page: lastPage, modal: route as Modal }
  lastPage = route as Page
  return { page: lastPage, modal: null }
}

export function parseHash(): Route {
  return routeFromHash()
}

export function hashFor(route: Route, query?: Record<string, string>): string {
  const base = route === 'home' ? '#/' : `#/${route}`
  const search = query ? new URLSearchParams(query).toString() : ''
  return search ? `${base}?${search}` : base
}

export function useRoute() {
  const [location, setLocation] = useState<AppLocation>(parseLocation)

  useEffect(() => {
    const onHashChange = () => setLocation(parseLocation())
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  const navigate = useCallback((r: Route, query?: Record<string, string>) => {
    window.location.hash = hashFor(r, query)
  }, [])

  const closeModal = useCallback(() => {
    window.location.hash = hashFor(lastPage)
  }, [])

  const route: Route = location.modal ?? location.page

  return { route, page: location.page, modal: location.modal, navigate, closeModal }
}

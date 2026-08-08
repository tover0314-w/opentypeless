import { useState, useEffect, useCallback } from 'react'

export type Route = 'home' | 'settings' | 'history'

function parseHash(): Route {
  const hash = window.location.hash.replace('#/', '')
  if (hash === 'settings' || hash === 'history') return hash
  return 'home'
}

export function useRoute() {
  const [route, setRoute] = useState<Route>(parseHash)

  useEffect(() => {
    const onHashChange = () => setRoute(parseHash())
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  const navigate = useCallback((r: Route) => {
    window.location.hash = r === 'home' ? '#/' : `#/${r}`
  }, [])

  return { route, navigate }
}

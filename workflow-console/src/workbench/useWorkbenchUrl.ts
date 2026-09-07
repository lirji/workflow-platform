import { useEffect, useMemo } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useWorkbenchStore, type WorkbenchFilters } from '../store/workbenchStore'
import { mergeWorkbenchSearch, readWorkbenchFromSearch } from './syncWorkbenchUrl'

function processKeyFromPath(pathname: string) {
  const raw = pathname.match(/^\/process\/([^/]+)/)?.[1]
  return raw ? decodeURIComponent(raw) : undefined
}

/** URL ↔ store 同步。路径上的 /process/:key 也写入 definitionKey。 */
export function useWorkbenchUrl() {
  const location = useLocation()
  const navigate = useNavigate()
  const setFilters = useWorkbenchStore((s) => s.setFilters)

  const filters = useMemo(() => {
    const fromSearch = readWorkbenchFromSearch(new URLSearchParams(location.search))
    const processKey = processKeyFromPath(location.pathname)
    return { ...fromSearch, definitionKey: processKey ?? fromSearch.definitionKey }
  }, [location.search, location.pathname])

  useEffect(() => {
    setFilters(filters)
  }, [filters, setFilters])

  const replaceFilters = (patch: Partial<WorkbenchFilters>) => {
    const merged: WorkbenchFilters = { ...filters, ...patch }
    if (patch.definitionKey !== undefined && patch.definitionKey !== filters.definitionKey) {
      merged.page = undefined
    }
    setFilters(merged)
    const search = mergeWorkbenchSearch(new URLSearchParams(location.search), merged).toString()
    let pathname = location.pathname
    if (location.pathname === '/process' || location.pathname.startsWith('/process/')) {
      pathname = merged.definitionKey ? `/process/${encodeURIComponent(merged.definitionKey)}` : '/process'
    }
    navigate({ pathname, search }, { replace: true })
  }

  return { filters, replaceFilters }
}

export function processNavPath(definitionKey?: string) {
  return definitionKey ? `/process/${encodeURIComponent(definitionKey)}` : '/process'
}

export function mergeLocationSearch(search: string, extra?: WorkbenchFilters) {
  const current = new URLSearchParams(search)
  const fromUrl = readWorkbenchFromSearch(current)
  return mergeWorkbenchSearch(current, { ...fromUrl, ...extra }).toString()
}

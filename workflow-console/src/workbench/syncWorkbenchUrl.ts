import type { WorkbenchFilters } from '../store/workbenchStore'

export function readWorkbenchFromSearch(search: URLSearchParams): WorkbenchFilters {
  const pageRaw = search.get('page')
  const sizeRaw = search.get('size')
  const page = pageRaw ? Number(pageRaw) : undefined
  const size = sizeRaw ? Number(sizeRaw) : undefined
  return {
    definitionKey: search.get('definitionKey')?.trim() || undefined,
    businessKey: search.get('businessKey')?.trim() || undefined,
    phase: search.get('phase')?.trim() || undefined,
    opsTab: search.get('tab')?.trim() || undefined,
    page: Number.isFinite(page) ? page : undefined,
    size: Number.isFinite(size) ? size : undefined,
  }
}

export function mergeWorkbenchSearch(current: URLSearchParams, next: WorkbenchFilters): URLSearchParams {
  const merged = new URLSearchParams(current)
  const write = (key: string, value: string | number | undefined, present: boolean) => {
    if (!present) return
    if (value === undefined || value === '') merged.delete(key)
    else merged.set(key, String(value))
  }
  write('definitionKey', next.definitionKey, 'definitionKey' in next)
  write('businessKey', next.businessKey, 'businessKey' in next)
  write('phase', next.phase, 'phase' in next)
  write('tab', next.opsTab, 'opsTab' in next)
  write('page', next.page, 'page' in next)
  write('size', next.size, 'size' in next)
  return merged
}

export function workbenchSearchString(filters: WorkbenchFilters): string {
  const merged = mergeWorkbenchSearch(new URLSearchParams(), filters)
  const text = merged.toString()
  return text ? `?${text}` : ''
}

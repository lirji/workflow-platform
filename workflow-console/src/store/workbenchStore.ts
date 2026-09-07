import { create } from 'zustand'
import { config } from '../config'

export type WorkbenchFilters = {
  definitionKey?: string
  businessKey?: string
  phase?: string
  opsTab?: string
  page?: number
  size?: number
}

type WorkbenchState = WorkbenchFilters & {
  tenantId: string
  seenDefinitionKeys: string[]
  setFilters: (next: WorkbenchFilters) => void
  rememberDefinitionKeys: (keys: string[]) => void
  clearFilters: () => void
}

export const useWorkbenchStore = create<WorkbenchState>((set) => ({
  tenantId: config.workflowTenant,
  seenDefinitionKeys: [],
  setFilters: (next) => set(next),
  rememberDefinitionKeys: (keys) =>
    set((s) => {
      const next = Array.from(new Set([...s.seenDefinitionKeys, ...keys.filter(Boolean)]))
      if (next.length === s.seenDefinitionKeys.length && next.every((k) => s.seenDefinitionKeys.includes(k))) {
        return s
      }
      return { seenDefinitionKeys: next }
    }),
  clearFilters: () =>
    set({ definitionKey: undefined, businessKey: undefined, phase: undefined, page: undefined }),
}))

/** 请求拦截器同步读取；租户不随流程筛选变化。 */
export function workbenchTenant() {
  return useWorkbenchStore.getState().tenantId
}

import { create } from 'zustand'
import type { ReviewDecision } from '../api/types'

/** 近期办理记录(诚实呈现 202 → 追最终一致落地状态)。 */
export interface RecentReview {
  businessKey: string
  actionId: string
  decision: ReviewDecision
  at: number
}

interface UiState {
  recent: RecentReview[]
  addRecent: (r: Omit<RecentReview, 'at'>) => void
  clearRecent: () => void
}

export const useUiStore = create<UiState>((set) => ({
  recent: [],
  addRecent: (r) => set((s) => ({ recent: [{ ...r, at: Date.now() }, ...s.recent].slice(0, 8) })),
  clearRecent: () => set({ recent: [] }),
}))

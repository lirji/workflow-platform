export const HIS_DEFINITION_KEY = 'hisRxReview'
export const SKU_DEFINITION_KEY = 'benefitSkuGoLive'

const LABELS: Record<string, { label: string; businessKeyLabel: string }> = {
  [HIS_DEFINITION_KEY]: { label: '审方', businessKeyLabel: '就诊' },
  [SKU_DEFINITION_KEY]: { label: 'SKU 上线', businessKeyLabel: '业务键 / SKU' },
}

export function definitionLabel(key?: string | null) {
  if (!key) return '全部流程'
  return LABELS[key]?.label ?? key
}

export function businessKeyLabel(key?: string | null) {
  if (!key) return '业务键'
  return LABELS[key]?.businessKeyLabel ?? '业务键'
}

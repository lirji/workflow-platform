export const HIS_DEFINITION_KEY = 'hisRxReview'
export const SKU_DEFINITION_KEY = 'benefitSkuGoLive'
export const HIS_TASK_KEY = 'pharmacistReview'
export const SKU_TASK_KEY = 'skuGoLiveReview'

export function resolveDefinitionKey(raw?: string | null): string {
  const key = raw?.trim()
  return key || HIS_DEFINITION_KEY
}

export function candidateGroupsFor(definitionKey: string, authorities: string[], authEnabled: boolean): string[] {
  if (definitionKey === SKU_DEFINITION_KEY) {
    if (!authEnabled) return ['BENEFIT_SKU_REVIEWER']
    return authorities.filter((g) => g === 'BENEFIT_SKU_REVIEWER' || g === 'ADMIN')
  }
  if (!authEnabled) return ['PHARMACIST']
  return authorities.filter((g) => g === 'PHARMACIST' || g === 'ADMIN')
}

export function visibleTaskKey(definitionKey: string): string {
  return definitionKey === SKU_DEFINITION_KEY ? SKU_TASK_KEY : HIS_TASK_KEY
}

export function inboxCopy(definitionKey: string) {
  if (definitionKey === SKU_DEFINITION_KEY) {
    return {
      pageDescription: 'SKU 上线待办',
      businessKeyLabel: '业务单',
      drawerTitle: '办理上线审批',
      decisionLabel: '审批决定',
      opinionLabel: '审批意见',
      opinionPlaceholder: '可选:审批意见',
      confirmPass: '确认通过该待办?',
      confirmReject: '确认驳回该待办?',
    }
  }
  return {
    pageDescription: '审方待办',
    businessKeyLabel: '就诊',
    drawerTitle: '办理审方',
    decisionLabel: '审方决定',
    opinionLabel: '审方意见',
    opinionPlaceholder: '可选:审方意见',
    confirmPass: '确认通过审方?',
    confirmReject: '确认驳回审方?',
  }
}

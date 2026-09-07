export const HIS_DEFINITION_KEY = 'hisRxReview'
export const SKU_DEFINITION_KEY = 'benefitSkuGoLive'
export const HIS_TASK_KEY = 'pharmacistReview'
export const SKU_TASK_KEY = 'skuGoLiveReview'

const REVIEW_TASKS = new Set([HIS_TASK_KEY, SKU_TASK_KEY])

export function isReviewTask(taskDefinitionKey?: string | null) {
  return !!taskDefinitionKey && REVIEW_TASKS.has(taskDefinitionKey)
}

/** 鉴权开启时传用户组并集；dev 不传，避免默认 PHARMACIST 滤掉其他流程。 */
export function inboxCandidateGroups(authorities: string[], authEnabled: boolean): string[] | undefined {
  if (!authEnabled) return undefined
  const groups = authorities.filter((g) => g === 'PHARMACIST' || g === 'ADMIN' || g === 'BENEFIT_SKU_REVIEWER')
  return groups.length ? groups : undefined
}

export function inboxCopy(definitionKey?: string | null) {
  if (definitionKey === SKU_DEFINITION_KEY) {
    return {
      pageDescription: 'SKU 上线待办',
      businessKeyLabel: '业务键 / SKU',
      drawerTitle: '办理上线审批',
      decisionLabel: '审批决定',
      opinionLabel: '审批意见',
      opinionPlaceholder: '可选:审批意见',
      confirmPass: '确认通过该待办?',
      confirmReject: '确认驳回该待办?',
    }
  }
  if (definitionKey === HIS_DEFINITION_KEY) {
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
  return {
    pageDescription: '待办',
    businessKeyLabel: '业务键',
    drawerTitle: '办理任务',
    decisionLabel: '决定',
    opinionLabel: '意见',
    opinionPlaceholder: '可选:办理意见',
    confirmPass: '确认通过该待办?',
    confirmReject: '确认驳回该待办?',
  }
}

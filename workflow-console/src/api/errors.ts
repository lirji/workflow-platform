import type { AxiosError } from 'axios'
import type { ApiError } from './types'

/** 读后端错误 body 的 message(评审 C5:不直吐后端串/堆栈),缺失时退回 axios message / 兜底文案。 */
export function errMsg(e: unknown, fallback = '请求失败'): string {
  const ax = e as AxiosError<ApiError> | undefined
  return ax?.response?.data?.message || ax?.message || fallback
}

/** 取 HTTP 状态码(用于 409/400 分支)。 */
export function statusOf(e: unknown): number | undefined {
  return (e as AxiosError | undefined)?.response?.status
}

/**
 * 运维操作错误 → 友好文案。403 越权、5xx 中性兜底(Flowable 未映射异常);
 * 其余(404 死信不存在/已重放、409 冲突)读后端 message(companion 已补齐)。
 */
export function opErrorText(e: unknown): string {
  const s = statusOf(e)
  if (s === 403) return '无权限执行该操作(需 ADMIN)'
  if (s !== undefined && s >= 500) return '操作失败,请刷新后重试'
  return errMsg(e, '操作失败,请刷新后重试')
}

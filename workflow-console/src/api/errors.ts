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

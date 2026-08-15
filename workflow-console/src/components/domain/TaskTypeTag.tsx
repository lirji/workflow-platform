import { Tag } from 'antd'

/** 任务类型标签(区分 taskDefinitionKey;评审 B3:本轮不表示行的语义状态)。 */
export function TaskTypeTag({ taskDefinitionKey }: { taskDefinitionKey: string }) {
  if (taskDefinitionKey === 'pharmacistReview') return <Tag color="processing">药师审方</Tag>
  if (taskDefinitionKey === 'manualRepair') return <Tag color="warning">人工修复</Tag>
  return <Tag>{taskDefinitionKey}</Tag>
}

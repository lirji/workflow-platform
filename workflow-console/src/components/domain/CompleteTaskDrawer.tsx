import { useEffect } from 'react'
import { App, Button, Descriptions, Drawer, Form, Grid, Input, Space, Tag } from 'antd'
import type { TaskView } from '../../api/types'
import { useCompleteTask } from '../../hooks/useTasks'
import { useAuthStore } from '../../store/authStore'
import { errMsg, statusOf } from '../../api/errors'
import { businessKeyLabel } from '../../workbench/definitionLabel'

interface Props {
  open: boolean
  task: TaskView | null
  onClose: () => void
  onSubmitted: (r: { businessKey: string; actionId: string; decision: string; processDefinitionKey: string }) => void
  onSyncStart: () => void
}

interface FormValues {
  outcome: string
  comment?: string
}

/** 非审方任务办理：outcome + comment，走 POST /complete。202 只表示已受理。 */
export default function CompleteTaskDrawer({ open, task, onClose, onSubmitted, onSyncStart }: Props) {
  const [form] = Form.useForm<FormValues>()
  const { message, modal } = App.useApp()
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.lg
  const mutation = useCompleteTask()
  const userId = useAuthStore((s) => s.userId)
  const username = useAuthStore((s) => s.username)

  useEffect(() => {
    if (open) form.setFieldsValue({ outcome: 'APPROVE', comment: '' })
  }, [open, task, form])

  const submit = async () => {
    if (!task) return
    let v: FormValues
    try {
      v = await form.validateFields()
    } catch {
      return
    }
    const confirmed = await new Promise<boolean>((resolve) => {
      modal.confirm({
        title: '确认提交该办理?',
        content: '办理提交后不可撤销,业务落地经异步最终一致。',
        okText: '确认提交',
        cancelText: '再想想',
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      })
    })
    if (!confirmed) return
    try {
      const res = await mutation.mutateAsync({
        taskId: task.taskId,
        body: {
          outcome: v.outcome,
          comment: v.comment,
          actorSub: userId,
          actorUsername: username,
          actorDisplayName: username,
        },
      })
      message.info(`已受理,待业务落地(actionId ${res.actionId.slice(0, 8)}…)`)
      onSubmitted({
        businessKey: task.businessKey,
        actionId: res.actionId,
        decision: v.outcome,
        processDefinitionKey: task.processDefinitionKey,
      })
      onSyncStart()
      onClose()
    } catch (e) {
      if (statusOf(e) === 409) message.warning('该任务已被处理或状态已变更,请刷新后重试')
      else message.error(errMsg(e, '办理失败,请重试(未落地,可重试)'))
    }
  }

  return (
    <Drawer
      title="办理任务"
      open={open}
      onClose={onClose}
      width={isMobile ? '100%' : 520}
      height={isMobile ? '100%' : undefined}
      placement={isMobile ? 'bottom' : 'right'}
      destroyOnClose
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={mutation.isPending} onClick={submit} style={{ minHeight: 44 }}>
            提交
          </Button>
        </Space>
      }
    >
      {task && (
        <>
          <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
            <Descriptions.Item label={`${businessKeyLabel(task.processDefinitionKey)}(businessKey)`}>
              <span className="mono">{task.businessKey}</span>
            </Descriptions.Item>
            <Descriptions.Item label="任务">{task.name}</Descriptions.Item>
            <Descriptions.Item label="流程">{task.processDefinitionKey}</Descriptions.Item>
            <Descriptions.Item label="候选组">
              {task.candidateGroups?.map((g) => <Tag key={g}>{g}</Tag>)}
            </Descriptions.Item>
          </Descriptions>
          <Form form={form} layout="vertical" requiredMark>
            <Form.Item name="outcome" label="结论" rules={[{ required: true, message: '请填写结论' }]}>
              <Input placeholder="如 APPROVE / REJECT / RETURN" />
            </Form.Item>
            <Form.Item name="comment" label="意见">
              <Input.TextArea rows={4} maxLength={500} showCount placeholder="可选:办理意见" />
            </Form.Item>
          </Form>
        </>
      )}
    </Drawer>
  )
}

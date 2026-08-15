import { useEffect } from 'react'
import { App, Button, Descriptions, Drawer, Form, Grid, Input, Radio, Space, Tag } from 'antd'
import type { ReviewDecision, TaskView } from '../../api/types'
import { useCompleteReview } from '../../hooks/useTasks'
import { useAuthStore } from '../../store/authStore'
import { errMsg, statusOf } from '../../api/errors'

interface Props {
  open: boolean
  task: TaskView | null
  onClose: () => void
  /** 办理成功(已受理 202)回调:上报 businessKey/actionId/decision 供近期办理区追踪落地。 */
  onSubmitted: (r: { businessKey: string; actionId: string; decision: ReviewDecision }) => void
  /** 触发列表爆发轮询。 */
  onSyncStart: () => void
}

interface FormValues {
  decision: ReviewDecision
  opinion?: string
}

/** 办理抽屉:PASS/REJECT + 意见(驳回必填);提交前二次确认(办理不可回滚);202 呈现"已受理",绝不"已完成"。 */
export default function ReviewDrawer({ open, task, onClose, onSubmitted, onSyncStart }: Props) {
  const [form] = Form.useForm<FormValues>()
  const { message, modal } = App.useApp()
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.lg
  const mutation = useCompleteReview()
  const userId = useAuthStore((s) => s.userId)
  const username = useAuthStore((s) => s.username)
  const decision = Form.useWatch('decision', form)

  useEffect(() => {
    if (open) form.setFieldsValue({ decision: 'PASS', opinion: '' })
  }, [open, task, form])

  const submit = async () => {
    if (!task) return
    let v: FormValues
    try {
      v = await form.validateFields()
    } catch {
      return // 校验未过(如驳回未填意见):就地红字拦截,不提交
    }
    const confirmed = await new Promise<boolean>((resolve) => {
      modal.confirm({
        title: v.decision === 'PASS' ? '确认通过审方?' : '确认驳回审方?',
        content: '办理提交后不可撤销,业务落地经异步最终一致(可稍后在"近期办理"查看落地状态)。',
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
          decision: v.decision,
          opinion: v.opinion,
          actorSub: userId,
          actorUsername: username,
          actorDisplayName: username,
        },
      })
      message.info(`已受理,待业务落地(actionId ${res.actionId.slice(0, 8)}…)`)
      onSubmitted({ businessKey: task.businessKey, actionId: res.actionId, decision: v.decision })
      onSyncStart()
      onClose()
    } catch (e) {
      if (statusOf(e) === 409) message.warning('该任务已被处理或状态已变更,请刷新后重试')
      else message.error(errMsg(e, '办理失败,请重试(未落地,可重试)'))
    }
  }

  return (
    <Drawer
      title="办理审方"
      open={open}
      onClose={onClose}
      width={isMobile ? '100%' : 520}
      height={isMobile ? '100%' : undefined}
      placement={isMobile ? 'bottom' : 'right'}
      destroyOnClose
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={mutation.isPending} onClick={submit}>
            提交
          </Button>
        </Space>
      }
    >
      {task && (
        <>
          <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
            <Descriptions.Item label="就诊(businessKey)">
              <span className="mono">{task.businessKey}</span>
            </Descriptions.Item>
            <Descriptions.Item label="任务">{task.name}</Descriptions.Item>
            <Descriptions.Item label="候选组">
              {task.candidateGroups?.map((g) => <Tag key={g}>{g}</Tag>)}
            </Descriptions.Item>
          </Descriptions>
          <Form form={form} layout="vertical" requiredMark>
            <Form.Item name="decision" label="审方决定" rules={[{ required: true }]}>
              <Radio.Group>
                <Radio.Button value="PASS">通过</Radio.Button>
                <Radio.Button value="REJECT">驳回</Radio.Button>
              </Radio.Group>
            </Form.Item>
            <Form.Item
              name="opinion"
              label="审方意见"
              rules={decision === 'REJECT' ? [{ required: true, message: '驳回必须填写意见' }] : []}
            >
              <Input.TextArea
                rows={4}
                maxLength={500}
                showCount
                placeholder={decision === 'REJECT' ? '请填写驳回原因(必填)' : '可选:审方意见'}
              />
            </Form.Item>
          </Form>
        </>
      )}
    </Drawer>
  )
}

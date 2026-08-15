import { useMemo, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { App, Button, Form, Input, Modal, Space, Table, Tag } from 'antd'
import { EditOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { EmptyState, ErrorState, PageSkeleton } from '../common/AsyncState'
import {
  useActivateDefinition,
  useDefinitions,
  useDeployDefinition,
  useSuspendDefinition,
} from '../../hooks/useOps'
import { errMsg, opErrorText } from '../../api/errors'
import type { ProcessDefinitionView } from '../../api/types'

/** 流程定义部署/版本管理(Option B):粘贴 BPMN XML 部署 + 列出 + 挂起/恢复。可视化设计器为后续。 */
export default function DefinitionsPanel() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [deployOpen, setDeployOpen] = useState(false)
  const [form] = Form.useForm<{ name: string; bpmnXml: string }>()

  const query = useDefinitions()
  const deployMut = useDeployDefinition()
  const suspendMut = useSuspendDefinition()
  const activateMut = useActivateDefinition()
  const rows = useMemo(() => query.data ?? [], [query.data])

  const submitDeploy = async () => {
    const v = await form.validateFields().catch(() => null)
    if (!v) return
    try {
      const view = await deployMut.mutateAsync({ name: v.name, bpmnXml: v.bpmnXml })
      message.success(`已部署 ${view.key} v${view.version}`)
      setDeployOpen(false)
      form.resetFields()
    } catch (e) {
      message.error(opErrorText(e))
    }
  }

  const toggle = async (r: ProcessDefinitionView) => {
    try {
      if (r.suspended) {
        await activateMut.mutateAsync(r.id)
        message.success('已恢复')
      } else {
        await suspendMut.mutateAsync(r.id)
        message.success('已挂起')
      }
    } catch (e) {
      message.error(opErrorText(e))
      void query.refetch()
    }
  }

  const columns: ColumnsType<ProcessDefinitionView> = [
    { title: '定义 key', dataIndex: 'key', render: (v: string) => <span className="mono">{v}</span> },
    { title: '名称', dataIndex: 'name', render: (v: string | null) => v ?? '-' },
    { title: '版本', dataIndex: 'version' },
    {
      title: '状态',
      dataIndex: 'suspended',
      render: (s: boolean) => (s ? <Tag color="default">已挂起</Tag> : <Tag color="success">生效</Tag>),
    },
    { title: '租户', dataIndex: 'tenantId', render: (v: string | null) => v ?? '-' },
    {
      title: '操作',
      key: 'op',
      fixed: 'right',
      width: 130,
      render: (_: unknown, r) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => navigate(`/designer?key=${encodeURIComponent(r.key)}`)}>
            设计
          </Button>
          <Button type="link" size="small" onClick={() => toggle(r)}>
            {r.suspended ? '恢复' : '挂起'}
          </Button>
        </Space>
      ),
    },
  ]

  let body: ReactNode
  if (query.isLoading) body = <PageSkeleton />
  else if (query.isError) body = <ErrorState message={errMsg(query.error)} onRetry={() => query.refetch()} />
  else if (rows.length === 0) body = <EmptyState description="暂无流程定义" />
  else body = <Table rowKey="id" columns={columns} dataSource={rows} pagination={false} scroll={{ x: 760 }} />

  return (
    <>
      <Space style={{ marginBottom: 16 }} wrap>
        <Button type="primary" icon={<EditOutlined />} onClick={() => navigate('/designer')}>
          可视化新建
        </Button>
        <Button onClick={() => setDeployOpen(true)}>部署 BPMN(粘贴 XML)</Button>
        <Button icon={<ReloadOutlined />} onClick={() => query.refetch()} loading={query.isFetching}>
          刷新
        </Button>
      </Space>
      {body}

      <Modal
        title="部署流程定义(BPMN XML)"
        open={deployOpen}
        okText="部署"
        cancelText="取消"
        confirmLoading={deployMut.isPending}
        onOk={submitDeploy}
        onCancel={() => {
          setDeployOpen(false)
          form.resetFields()
        }}
        width={720}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请填写名称' }]}>
            <Input placeholder="如 hisRefundReview" />
          </Form.Item>
          <Form.Item name="bpmnXml" label="BPMN XML" rules={[{ required: true, message: '请粘贴 BPMN XML' }]}>
            <Input.TextArea rows={12} className="mono" placeholder="粘贴完整 BPMN 2.0 XML" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}

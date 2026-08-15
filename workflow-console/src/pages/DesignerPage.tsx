import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useSearchParams, useBlocker } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Alert, App, Button, Form, Grid, Input, Modal, Space, Tag } from 'antd'
import { CodeOutlined, CloudUploadOutlined, EditOutlined } from '@ant-design/icons'
import { PageHeader } from '../components/layout/PageHeader'
import { ErrorState, PageSkeleton } from '../components/common/AsyncState'
import BpmnModeler, { type BpmnModelerHandle, type ModelerState } from '../components/bpmn/BpmnModeler'
import BpmnViewer from '../components/bpmn/BpmnViewer'
import ModelerToolbar from '../components/bpmn/ModelerToolbar'
import DetailDrawer from '../components/ops/DetailDrawer'
import { validateForDeploy } from '../components/bpmn/bpmnTemplates'
import { getDefinitionXml } from '../api/process'
import { useDefinitions, useDeployDefinition } from '../hooks/useOps'
import { errMsg, opErrorText } from '../api/errors'

/**
 * 可视化流程设计器(ADMIN)。拖拽建模 → 属性面板编辑 → 导出预览 → 部署。
 * ?key= 载入既有定义最新版编辑(部署=发布新版本);缺省=新建空白。
 * 桌面为主;<992 只读降级 + 引导桌面。诚实呈现:部署成功「已部署 key vN」,绝不「已完成/已生效」。
 */
export default function DesignerPage() {
  const { message, modal } = App.useApp()
  const [sp, setSp] = useSearchParams()
  const key = sp.get('key') ?? undefined
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.lg

  const modelerRef = useRef<BpmnModelerHandle>(null)
  const [dirty, setDirty] = useState(false)
  const [canUndo, setCanUndo] = useState(false)
  const [canRedo, setCanRedo] = useState(false)

  const [exportOpen, setExportOpen] = useState(false)
  const [exportXml, setExportXml] = useState('')
  const [deployOpen, setDeployOpen] = useState(false)
  const [pendingXml, setPendingXml] = useState('')
  const [deployForm] = Form.useForm<{ name: string }>()

  const defsQuery = useDefinitions()
  const deployMut = useDeployDefinition()
  const xmlQuery = useQuery({
    queryKey: ['definition-xml', key],
    queryFn: () => getDefinitionXml(key as string),
    enabled: !!key,
    staleTime: 5 * 60_000,
  })

  const onStateChange = (s: ModelerState) => {
    setDirty(s.dirty)
    setCanUndo(s.canUndo)
    setCanRedo(s.canRedo)
  }

  // 未保存改动时,离开路由拦截 + 关标签/刷新兜底。
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) => dirty && currentLocation.pathname !== nextLocation.pathname,
  )
  useEffect(() => {
    if (blocker.state !== 'blocked') return
    modal.confirm({
      title: '离开将丢失未保存的改动?',
      content: '当前流程图尚未部署,离开后草稿不会保留。',
      okText: '仍然离开',
      okButtonProps: { danger: true },
      cancelText: '留在本页',
      onOk: () => blocker.proceed?.(),
      onCancel: () => blocker.reset?.(),
    })
  }, [blocker, modal])
  useEffect(() => {
    if (!dirty) return
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [dirty])

  // 切定义 / 新建前,dirty 则先确认(同页 ?key 变更不触发 useBlocker)。
  const guardDirty = (action: () => void) => {
    if (!dirty) {
      action()
      return
    }
    modal.confirm({
      title: '放弃未保存的改动?',
      content: '当前流程图尚未部署,继续将丢失草稿。',
      okText: '继续',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: action,
    })
  }
  const onNew = () => guardDirty(() => setSp({}, { replace: false }))
  const onLoad = (k: string) => {
    if (k === key) return
    guardDirty(() => setSp({ key: k }))
  }

  const openExport = async () => {
    setExportXml((await modelerRef.current?.getXML()) ?? '')
    setExportOpen(true)
  }
  const openDeploy = async () => {
    const xml = (await modelerRef.current?.getXML()) ?? ''
    const v = validateForDeploy(xml)
    if (!v.ok) {
      message.warning(v.reason ?? '流程图校验未通过')
      return
    }
    setPendingXml(xml)
    deployForm.setFieldsValue({ name: v.processName || v.processId || '' })
    setDeployOpen(true)
  }
  const submitDeploy = async () => {
    const vals = await deployForm.validateFields().catch(() => null)
    if (!vals || !pendingXml) return
    try {
      const view = await deployMut.mutateAsync({ name: vals.name, bpmnXml: pendingXml })
      if (!view || !view.key) {
        message.warning('部署完成但未解析出流程定义,请检查 XML 是否含可执行 process')
      } else {
        message.success(`已部署 ${view.key} v${view.version}`)
        setDirty(false) // 当前画布已发布,视为已保存(下次编辑再置脏)
      }
      setDeployOpen(false)
    } catch (e) {
      message.error(opErrorText(e))
    }
  }

  let body: ReactNode
  if (isMobile) {
    body = (
      <>
        <Alert
          type="info"
          showIcon
          message="流程建模请在桌面端操作"
          description="可视化拖拽建模需要 ≥992px 的屏幕宽度。当前为只读预览,请在桌面端编辑与部署。"
          style={{ marginBottom: 16 }}
        />
        {key && xmlQuery.data ? <BpmnViewer xml={xmlQuery.data} height="60vh" /> : null}
      </>
    )
  } else if (key && xmlQuery.isLoading) {
    body = <PageSkeleton rows={10} />
  } else if (key && xmlQuery.isError) {
    body = <ErrorState message={errMsg(xmlQuery.error, '流程定义 XML 拉取失败')} onRetry={() => xmlQuery.refetch()} />
  } else {
    body = (
      <>
        <ModelerToolbar
          definitions={defsQuery.data ?? []}
          defsLoading={defsQuery.isFetching}
          currentKey={key}
          canUndo={canUndo}
          canRedo={canRedo}
          onNew={onNew}
          onLoad={onLoad}
          onUndo={() => modelerRef.current?.undo()}
          onRedo={() => modelerRef.current?.redo()}
          onZoomFit={() => modelerRef.current?.zoomFit()}
        />
        <BpmnModeler
          ref={modelerRef}
          xml={key ? xmlQuery.data : undefined}
          onStateChange={onStateChange}
          onImportError={(m) => message.error(`流程图解析失败: ${m}`)}
        />
      </>
    )
  }

  return (
    <>
      <PageHeader
        title="流程设计器"
        description={
          <Space size={8} wrap>
            <span>{key ? `编辑 ${key}(部署将发布为新版本)` : '新建流程'}</span>
            {dirty && (
              <Tag icon={<EditOutlined />} color="warning">
                未保存
              </Tag>
            )}
          </Space>
        }
        extra={
          !isMobile && (
            <Space wrap>
              <Button icon={<CodeOutlined />} onClick={openExport}>
                导出 XML
              </Button>
              <Button type="primary" icon={<CloudUploadOutlined />} onClick={openDeploy}>
                部署
              </Button>
            </Space>
          )
        }
      />
      {body}

      <DetailDrawer open={exportOpen} title="BPMN XML 预览" content={exportXml} onClose={() => setExportOpen(false)} />

      <Modal
        title="部署流程定义"
        open={deployOpen}
        okText="确认部署"
        cancelText="取消"
        confirmLoading={deployMut.isPending}
        onOk={submitDeploy}
        onCancel={() => setDeployOpen(false)}
        destroyOnClose
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="流程 key 由画布内 process id 决定"
          description="名称仅作部署包名。同一 process id 再次部署将发布为新版本,旧版本仍保留。"
        />
        <Form form={deployForm} layout="vertical">
          <Form.Item name="name" label="部署名称" rules={[{ required: true, message: '请填写部署名称' }]}>
            <Input placeholder="如 hisRefundReview" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}

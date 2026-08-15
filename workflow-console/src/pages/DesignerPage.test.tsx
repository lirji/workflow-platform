import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithDataRouter } from '../test/renderWithDataRouter'
import { blankTemplate } from '../components/bpmn/bpmnTemplates'
import DesignerPage from './DesignerPage'
import * as admin from '../api/admin'

// 真实 Modeler 在 jsdom 无法渲染 → mock;getXML 返回受控 XML。
const h = vi.hoisted(() => ({ xml: '' }))
vi.mock('../components/bpmn/BpmnModeler', async () => {
  const { forwardRef, useImperativeHandle } = await import('react')
  const Mock = forwardRef((_props: unknown, ref: unknown) => {
    useImperativeHandle(ref as never, () => ({
      getXML: async () => h.xml,
      undo: () => {},
      redo: () => {},
      zoomFit: () => {},
    }))
    return null
  })
  return { default: Mock }
})
// 小屏只读预览的 Viewer 也 mock,避免 jsdom 载入真实 bpmn-js。
vi.mock('../components/bpmn/BpmnViewer', () => ({ default: () => null }))

vi.mock('../api/admin', () => ({
  listDefinitions: vi.fn().mockResolvedValue([]),
  deployDefinition: vi.fn(),
}))

const setViewport = (desktop: boolean) => {
  window.matchMedia = ((q: string) =>
    ({
      matches: desktop,
      media: q,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as unknown as MediaQueryList) as typeof window.matchMedia
}

beforeEach(() => {
  h.xml = ''
})
afterEach(() => {
  vi.clearAllMocks()
  setViewport(false) // 复位为默认(移动态,与 setup 桩一致)
})

describe('DesignerPage 桌面态', () => {
  it('渲染导出/部署入口(桌面)', () => {
    setViewport(true)
    renderWithDataRouter(<DesignerPage />)
    expect(screen.getByRole('button', { name: /导出/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /部\s*署/ })).toBeInTheDocument()
  })

  it('校验不通过(无可执行 process)→ 阻断部署,不调用 deployDefinition', async () => {
    setViewport(true)
    h.xml = '<?xml version="1.0"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"><process id="p" isExecutable="false"/></definitions>'
    const user = userEvent.setup()
    renderWithDataRouter(<DesignerPage />)
    await user.click(screen.getByRole('button', { name: /部\s*署/ }))
    expect(await screen.findByText(/未找到可执行流程/)).toBeInTheDocument()
    expect(admin.deployDefinition).not.toHaveBeenCalled()
  })

  it('校验通过 → 弹部署框 → 确认 → 调用 deployDefinition,提示"已部署 key vN",绝不"已完成/已生效"', async () => {
    setViewport(true)
    h.xml = blankTemplate('hisRefundReview', '退费审核')
    vi.mocked(admin.deployDefinition).mockResolvedValue({
      id: 'def:1',
      key: 'hisRefundReview',
      name: '退费审核',
      version: 3,
      tenantId: 'his',
      suspended: false,
      deploymentId: 'dep:1',
    })
    const user = userEvent.setup()
    renderWithDataRouter(<DesignerPage />)

    await user.click(screen.getByRole('button', { name: /部\s*署/ }))
    const dialog = await screen.findByRole('dialog')
    // 名称预填 process name
    expect(within(dialog).getByDisplayValue('退费审核')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: /确认部署/ }))

    await waitFor(() =>
      expect(admin.deployDefinition).toHaveBeenCalledWith('退费审核', h.xml),
    )
    expect(await screen.findByText('已部署 hisRefundReview v3')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('已完成')
    expect(document.body.textContent).not.toContain('已生效')
  })
})

describe('DesignerPage 移动态', () => {
  it('<992 呈引导桌面,不出现部署按钮', () => {
    setViewport(false)
    renderWithDataRouter(<DesignerPage />)
    expect(screen.getByText(/流程建模请在桌面端操作/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /部\s*署/ })).not.toBeInTheDocument()
  })
})

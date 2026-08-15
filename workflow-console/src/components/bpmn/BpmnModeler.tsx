import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react'
import Modeler from 'bpmn-js/lib/Modeler'
import {
  BpmnPropertiesPanelModule,
  BpmnPropertiesProviderModule,
} from 'bpmn-js-properties-panel'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css'
import '@bpmn-io/properties-panel/dist/assets/properties-panel.css'
import { ErrorState } from '../common/AsyncState'
import { FlowablePropertiesProviderModule } from './flowablePropertiesProvider'
import flowableModdle from './flowableModdle'
import { blankTemplate } from './bpmnTemplates'

export interface BpmnModelerHandle {
  /** 导出当前画布 BPMN XML(格式化)。 */
  getXML: () => Promise<string>
  undo: () => void
  redo: () => void
  zoomFit: () => void
}

export interface ModelerState {
  dirty: boolean
  canUndo: boolean
  canRedo: boolean
}

interface Props {
  /** 有=编辑既有定义 XML;无=新建(载入空白模板)。 */
  xml?: string
  /** 命令栈变化时回调(dirty/undo/redo 态)。 */
  onStateChange?: (s: ModelerState) => void
  /** 导入解析失败回调。 */
  onImportError?: (msg: string) => void
}

interface CommandStack {
  undo: () => void
  redo: () => void
  canUndo: () => boolean
  canRedo: () => boolean
}
interface Canvas {
  zoom: (mode: string) => void
}

/**
 * bpmn-js Modeler 编辑器封装(拖拽建模 + 右侧属性面板)。
 * 实例存 useRef 不进 state(复刻 BpmnViewer,StrictMode 双挂载安全);左画布 + 右属性面板双容器。
 * 属性面板 = vanilla BPMN provider(标准属性/条件表达式)+ 自定义 Flowable provider(候选组/委托表达式)。
 * 被 DesignerPage 懒加载 → bpmn/properties-panel 大块不进待办首屏。
 */
const BpmnModeler = forwardRef<BpmnModelerHandle, Props>(function BpmnModeler(
  { xml, onStateChange, onImportError },
  ref,
) {
  const canvasRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const modelerRef = useRef<InstanceType<typeof Modeler> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useImperativeHandle(ref, () => ({
    getXML: async () => {
      const modeler = modelerRef.current
      if (!modeler) return ''
      const { xml: out } = await modeler.saveXML({ format: true })
      return out ?? ''
    },
    undo: () => (modelerRef.current?.get('commandStack') as CommandStack | undefined)?.undo(),
    redo: () => (modelerRef.current?.get('commandStack') as CommandStack | undefined)?.redo(),
    zoomFit: () => (modelerRef.current?.get('canvas') as Canvas | undefined)?.zoom('fit-viewport'),
  }))

  // 挂载建实例(含属性面板),卸载销毁。仅执行一次。
  useEffect(() => {
    if (!canvasRef.current || !panelRef.current) return
    const modeler = new Modeler({
      container: canvasRef.current,
      propertiesPanel: { parent: panelRef.current },
      moddleExtensions: { flowable: flowableModdle },
      additionalModules: [
        BpmnPropertiesPanelModule,
        BpmnPropertiesProviderModule,
        FlowablePropertiesProviderModule,
      ],
      keyboard: { bindTo: document },
    })
    modelerRef.current = modeler

    const emit = () => {
      const stack = modeler.get('commandStack') as unknown as CommandStack
      // dirty = 是否有可撤销步骤;程序化 importXML 会清栈 → 初次载入不误标未保存(评审 M5)。
      onStateChange?.({ dirty: stack.canUndo(), canUndo: stack.canUndo(), canRedo: stack.canRedo() })
    }
    modeler.on('commandStack.changed', emit)

    return () => {
      modeler.destroy()
      modelerRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // xml 变化时(含首次)导入;无 xml → 空白模板。cancelled 卫防旧 xml 覆盖新图(评审 M4)。
  useEffect(() => {
    const modeler = modelerRef.current
    if (!modeler) return
    let cancelled = false
    const source = xml && xml.trim() ? xml : blankTemplate()
    modeler
      .importXML(source)
      .then(() => {
        if (cancelled) return
        ;(modeler.get('canvas') as unknown as Canvas).zoom('fit-viewport')
        setError(null)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        const msg = e instanceof Error ? e.message : String(e)
        setError(msg)
        onImportError?.(msg)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [xml])

  if (error) return <ErrorState message={`流程图解析失败: ${error}`} />
  return (
    <div style={{ display: 'flex', height: '72vh', width: '100%' }}>
      <div ref={canvasRef} className="bpmn-canvas" style={{ flex: 1, minWidth: 0 }} />
      <div
        ref={panelRef}
        className="bpmn-props"
        style={{ width: 320, flexShrink: 0, borderInlineStart: '1px solid #E6EAF0', overflowY: 'auto' }}
      />
    </div>
  )
})

export default BpmnModeler

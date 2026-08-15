import { useEffect, useRef, useState } from 'react'
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css'
import { ErrorState } from '../common/AsyncState'

export interface BpmnHighlights {
  /** 当前活动节点(primary 描边)。 */
  active?: string[]
  /** 已走过节点(success 描边)。 */
  completed?: string[]
  /** 异常节点(error 描边)。 */
  incident?: string[]
}

interface Props {
  xml: string
  highlights?: BpmnHighlights
  height?: number | string
}

interface Canvas {
  zoom: (mode: string) => void
  addMarker: (id: string, marker: string) => void
}

/**
 * bpmn-js NavigatedViewer 只读封装(平移/缩放,无编辑)。实例存 useRef 不进 state(评审 B1/D5)。
 * 本组件被 ProcessTracePage 懒加载 → bpmn-js 不进待办主路径首屏(评审 C2)。
 */
export default function BpmnViewer({ xml, highlights, height = '70vh' }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<InstanceType<typeof NavigatedViewer> | null>(null)
  const [error, setError] = useState<string | null>(null)

  // 只在挂载时建 viewer,卸载时销毁。
  useEffect(() => {
    if (!containerRef.current) return
    const viewer = new NavigatedViewer({ container: containerRef.current })
    viewerRef.current = viewer
    return () => {
      viewer.destroy()
      viewerRef.current = null
    }
  }, [])

  // xml / 高亮变化时重新导入并打标记。
  useEffect(() => {
    const viewer = viewerRef.current
    if (!viewer || !xml) return
    let cancelled = false
    viewer
      .importXML(xml)
      .then(() => {
        if (cancelled) return
        const canvas = viewer.get('canvas') as unknown as Canvas
        canvas.zoom('fit-viewport')
        const mark = (ids: string[] | undefined, cls: string) =>
          ids?.forEach((id) => {
            try {
              canvas.addMarker(id, cls)
            } catch {
              // 该 id 不在图上,忽略
            }
          })
        mark(highlights?.completed, 'wf-completed')
        mark(highlights?.active, 'wf-active')
        mark(highlights?.incident, 'wf-incident')
        setError(null)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      })
    return () => {
      cancelled = true
    }
  }, [xml, highlights])

  if (error) return <ErrorState message={`流程图解析失败: ${error}`} />
  return <div ref={containerRef} className="bpmn-canvas" style={{ height, width: '100%' }} />
}

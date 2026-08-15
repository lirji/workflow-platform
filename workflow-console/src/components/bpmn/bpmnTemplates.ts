// 新建流程的最小 BPMN 模板 + 部署前校验。
// 模板含可执行 process(id 可控)+ 一个 StartEvent + BPMNDI 图形段(否则画布/轨迹页无法渲染)。
// 声明 flowable 命名空间,便于后续属性面板写入 flowable:candidateGroups 等能正确序列化。

const BPMN_NS = 'http://www.omg.org/spec/BPMN/20100524/MODEL'

/** 生成空白流程模板 XML。processId 作为 <process id> → Flowable 部署后即定义 key。 */
export function blankTemplate(processId = 'newProcess', processName = '新流程'): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<bpmn2:definitions xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL"
    xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
    xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
    xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
    xmlns:flowable="http://flowable.org/bpmn"
    id="Definitions_1" targetNamespace="http://flowable.org/bpmn">
  <bpmn2:process id="${processId}" name="${processName}" isExecutable="true">
    <bpmn2:startEvent id="StartEvent_1" name="开始" />
  </bpmn2:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="${processId}">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1">
        <dc:Bounds x="156" y="100" width="36" height="36" />
      </bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn2:definitions>`
}

export interface DeployValidation {
  ok: boolean
  /** ok=false 时的中文原因(用于 message.warning)。 */
  reason?: string
  /** ok=true 时解析出的唯一可执行流程 id(= 部署后定义 key)。 */
  processId?: string
  /** 可执行流程 name(用于预填部署名称)。 */
  processName?: string
}

/**
 * 部署前强校验:必须有且仅有一个 `<process isExecutable="true">` 且 id 非空。
 * 堵住后端「建了 deployment 但无 ProcessDefinition → toView(null) → 前端显示『已部署 undefined』」的静默失败。
 */
export function validateForDeploy(xml: string): DeployValidation {
  if (!xml || !xml.trim()) return { ok: false, reason: '流程图为空' }
  let doc: Document
  try {
    doc = new DOMParser().parseFromString(xml, 'application/xml')
  } catch {
    return { ok: false, reason: 'BPMN XML 解析失败' }
  }
  if (doc.getElementsByTagName('parsererror').length > 0) {
    return { ok: false, reason: 'BPMN XML 格式错误' }
  }
  const processes = Array.from(doc.getElementsByTagNameNS(BPMN_NS, 'process'))
  const executable = processes.filter((p) => (p.getAttribute('isExecutable') ?? 'false') === 'true')
  if (executable.length === 0) {
    return { ok: false, reason: '未找到可执行流程(需 <process isExecutable="true">),无法部署' }
  }
  if (executable.length > 1) {
    return { ok: false, reason: `存在 ${executable.length} 个可执行流程,仅支持单一流程部署` }
  }
  const proc = executable[0]
  const id = (proc.getAttribute('id') ?? '').trim()
  if (!id) {
    return { ok: false, reason: '流程 process id 为空,请在属性面板设置' }
  }
  return { ok: true, processId: id, processName: proc.getAttribute('name') ?? undefined }
}

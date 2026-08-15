// 自定义 bpmn-js 属性面板 provider(Flowable 语义)。
// 官方 bpmn-js-properties-panel 只自带 Camunda/Zeebe provider(与 Flowable 语义错配),
// 故用 vanilla BpmnPropertiesProviderModule 提供标准 BPMN 属性(id/name/文档/条件表达式等),
// 本 provider 补 Flowable 扩展属性:userTask 候选组/办理人、serviceTask 委托表达式。
//
// entry.component 写成 plain 函数(内部调用 TextFieldEntry(props) 返回 preact vnode),
// 避免在 React 工程里引入 preact JSX pragma。readAttr/writeAttr/getFlowableGroups 独立导出以便单测。

import { TextFieldEntry, isTextFieldEntryEdited } from '@bpmn-io/properties-panel'
import { useService } from 'bpmn-js-properties-panel'
import { is } from 'bpmn-js/lib/util/ModelUtil'

/** 读 businessObject 上的 flowable 属性(带命名空间前缀)。 */
export function readAttr(element: any, attr: string): string {
  return element?.businessObject?.get?.(attr) || ''
}

/** 写 flowable 属性;空串→undefined 以删除该属性(不留空属性)。 */
export function writeAttr(modeling: any, element: any, attr: string, value: string): void {
  modeling.updateProperties(element, { [attr]: value ? value : undefined })
}

/** 生成一个 TextFieldEntry 组件(plain 函数)。 */
function attrEntryComponent(attr: string, label: string, description?: string) {
  return function AttrEntry(props: any) {
    const { element, id } = props
    const modeling = useService('modeling')
    const debounce = useService('debounceInput')
    return TextFieldEntry({
      id,
      element,
      label,
      description,
      debounce,
      getValue: () => readAttr(element, attr),
      setValue: (value: string) => writeAttr(modeling, element, attr, value),
    })
  }
}

/** 按元素类型返回应追加的 Flowable 属性分组(纯数据,便于单测)。 */
export function getFlowableGroups(element: any): any[] {
  const groups: any[] = []
  if (is(element, 'bpmn:UserTask')) {
    groups.push({
      id: 'flowable-userTask',
      label: 'Flowable 用户任务',
      entries: [
        {
          id: 'flowable-candidateGroups',
          component: attrEntryComponent(
            'flowable:candidateGroups',
            '候选组 (candidateGroups)',
            '大写、逗号分隔,如 PHARMACIST,ADMIN。待办中心按候选组过滤,留空则无人可办。',
          ),
          isEdited: isTextFieldEntryEdited,
        },
        {
          id: 'flowable-assignee',
          component: attrEntryComponent('flowable:assignee', '办理人 (assignee)', '指定单一办理人;一般用候选组而非固定人。'),
          isEdited: isTextFieldEntryEdited,
        },
      ],
    })
  }
  if (is(element, 'bpmn:ServiceTask')) {
    groups.push({
      id: 'flowable-serviceTask',
      label: 'Flowable 服务任务',
      entries: [
        {
          id: 'flowable-delegateExpression',
          component: attrEntryComponent(
            'flowable:delegateExpression',
            '委托表达式 (delegateExpression)',
            '如 ${rxReviewActionOutboxDelegate};对应的 Spring bean 需服务端已注册,否则运行时报错。',
          ),
          isEdited: isTextFieldEntryEdited,
        },
      ],
    })
  }
  return groups
}

/** didi provider:把 Flowable 分组追加到属性面板。priority 500 在 vanilla(基础)之后。 */
class FlowablePropertiesProvider {
  static $inject = ['propertiesPanel']

  constructor(propertiesPanel: any) {
    propertiesPanel.registerProvider(500, this)
  }

  getGroups(element: any) {
    return (groups: any[]) => {
      groups.push(...getFlowableGroups(element))
      return groups
    }
  }
}

/** 作为 additionalModule 注册。 */
export const FlowablePropertiesProviderModule = {
  __init__: ['flowablePropertiesProvider'],
  flowablePropertiesProvider: ['type', FlowablePropertiesProvider],
}

export default FlowablePropertiesProviderModule

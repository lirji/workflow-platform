// bpmn-io 系列包未随包提供 TS 声明。仅声明本项目实际用到的导出,其余按 any。
// (bpmn-js 本身无 exports map,子路径 import 被 TS 当 any 放过,无需在此声明。)

declare module 'bpmn-js-properties-panel' {
  export const BpmnPropertiesPanelModule: any
  export const BpmnPropertiesProviderModule: any
  export const CamundaPlatformPropertiesProviderModule: any
  export const ZeebePropertiesProviderModule: any
  /** 属性面板 entry 组件内取 didi 服务(modeling/debounceInput/translate 等)。 */
  export function useService(type: string, strict?: boolean): any
}

declare module '@bpmn-io/properties-panel' {
  /** 文本输入 entry(可作为 plain 函数调用返回 preact vnode)。 */
  export const TextFieldEntry: (props: any) => any
  export function isTextFieldEntryEdited(node: any): boolean
}

// 内联最小 Flowable moddle 描述符。注册到 bpmn-js Modeler 的 moddleExtensions,
// 让 flowable: 命名空间属性能被正确读写与序列化(否则 moddle 不认该命名空间)。
// 关键:candidateGroups/assignee/delegateExpression 都是 XML 属性,必须 isAttr:true,
// 否则会被序列化成子元素 → Flowable 引擎不认(参照 his-rx-review-v1.bpmn20.xml 的属性形态)。

const flowableModdle = {
  name: 'Flowable',
  uri: 'http://flowable.org/bpmn',
  prefix: 'flowable',
  xml: { tagAlias: 'lowerCase' },
  associations: [],
  types: [
    {
      name: 'FlowableUserTask',
      extends: ['bpmn:UserTask'],
      properties: [
        { name: 'candidateGroups', isAttr: true, type: 'String' },
        { name: 'candidateUsers', isAttr: true, type: 'String' },
        { name: 'assignee', isAttr: true, type: 'String' },
        { name: 'formKey', isAttr: true, type: 'String' },
      ],
    },
    {
      name: 'FlowableServiceTask',
      extends: ['bpmn:ServiceTask'],
      properties: [
        { name: 'delegateExpression', isAttr: true, type: 'String' },
        { name: 'class', isAttr: true, type: 'String' },
        { name: 'expression', isAttr: true, type: 'String' },
      ],
    },
  ],
} as const

export default flowableModdle

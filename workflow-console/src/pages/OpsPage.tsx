import { Alert, Tabs } from 'antd'
import { PageHeader } from '../components/layout/PageHeader'
import InstancesPanel from '../components/ops/InstancesPanel'
import DeadLetterPanel from '../components/ops/DeadLetterPanel'
import DlqPanel from '../components/ops/DlqPanel'
import DefinitionsPanel from '../components/ops/DefinitionsPanel'
import { useWorkbenchUrl } from '../workbench/useWorkbenchUrl'

const TABS = ['instances', 'jobs', 'dlq', 'definitions']

/** 运维面板(ADMIN):实例运维 / 死信作业 / DLQ 死信。?tab= 深链,非法值回退 instances。切 Tab 只 merge tab。 */
export default function OpsPage() {
  const { filters, replaceFilters } = useWorkbenchUrl()
  const raw = filters.opsTab ?? 'instances'
  const tab = TABS.includes(raw) ? raw : 'instances'

  return (
    <>
      <PageHeader
        title="运维面板"
        description="流程实例运维 · Flowable 死信作业 · Kafka DLQ 死信(需 ADMIN)。待办理 → 处理中(等业务 ACK) → 已落地"
      />
      <Tabs
        activeKey={tab}
        onChange={(k) => replaceFilters({ opsTab: k })}
        destroyInactiveTabPane
        items={[
          { key: 'instances', label: '实例运维', children: <InstancesPanel /> },
          {
            key: 'jobs',
            label: '死信作业',
            children: (
              <>
                <Alert type="info" showIcon style={{ marginBottom: 16 }} message="本 Tab 不按流程过滤" />
                <DeadLetterPanel />
              </>
            ),
          },
          {
            key: 'dlq',
            label: 'DLQ 死信',
            children: (
              <>
                <Alert type="info" showIcon style={{ marginBottom: 16 }} message="本 Tab 不按流程过滤" />
                <DlqPanel />
              </>
            ),
          },
          { key: 'definitions', label: '流程定义', children: <DefinitionsPanel /> },
        ]}
      />
    </>
  )
}

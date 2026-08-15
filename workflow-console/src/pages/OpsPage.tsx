import { useSearchParams } from 'react-router-dom'
import { Tabs } from 'antd'
import { PageHeader } from '../components/layout/PageHeader'
import InstancesPanel from '../components/ops/InstancesPanel'
import DeadLetterPanel from '../components/ops/DeadLetterPanel'
import DlqPanel from '../components/ops/DlqPanel'
import DefinitionsPanel from '../components/ops/DefinitionsPanel'

const TABS = ['instances', 'jobs', 'dlq', 'definitions']

/** 运维面板(ADMIN):实例运维 / 死信作业 / DLQ 死信。?tab= 深链,非法值回退 instances。 */
export default function OpsPage() {
  const [sp, setSp] = useSearchParams()
  const raw = sp.get('tab') ?? 'instances'
  const tab = TABS.includes(raw) ? raw : 'instances'

  return (
    <>
      <PageHeader title="运维面板" description="流程实例运维 · Flowable 死信作业 · Kafka DLQ 死信(需 ADMIN)" />
      <Tabs
        activeKey={tab}
        onChange={(k) => setSp({ tab: k }, { replace: true })}
        destroyInactiveTabPane
        items={[
          { key: 'instances', label: '实例运维', children: <InstancesPanel /> },
          { key: 'jobs', label: '死信作业', children: <DeadLetterPanel /> },
          { key: 'dlq', label: 'DLQ 死信', children: <DlqPanel /> },
          { key: 'definitions', label: '流程定义', children: <DefinitionsPanel /> },
        ]}
      />
    </>
  )
}

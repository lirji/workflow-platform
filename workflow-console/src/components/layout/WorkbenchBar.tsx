import { useEffect, useMemo, useState } from 'react'
import { App, Button, Input, Select, Space, Tag, Typography } from 'antd'
import { CopyOutlined, SearchOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { config } from '../../config'
import { isAdmin, useAuthStore } from '../../store/authStore'
import { useWorkbenchStore } from '../../store/workbenchStore'
import { useWorkbenchUrl } from '../../workbench/useWorkbenchUrl'
import { businessKeyLabel, definitionLabel } from '../../workbench/definitionLabel'
import { listDefinitions } from '../../api/admin'
import { colors } from '../../theme/colors'

/** 三页共用筛选条：租户只读，流程/业务键写 URL，不改租户头。 */
export default function WorkbenchBar() {
  const { message } = App.useApp()
  const { filters, replaceFilters } = useWorkbenchUrl()
  const tenantId = useWorkbenchStore((s) => s.tenantId)
  const seenKeys = useWorkbenchStore((s) => s.seenDefinitionKeys)
  const authorities = useAuthStore((s) => s.authorities)
  const canListDefs = !config.authEnabled || isAdmin(authorities)
  const [bkDraft, setBkDraft] = useState(filters.businessKey ?? '')
  useEffect(() => {
    setBkDraft(filters.businessKey ?? '')
  }, [filters.businessKey])

  const defsQuery = useQuery({
    queryKey: ['workbench-definitions'],
    queryFn: listDefinitions,
    enabled: canListDefs,
    retry: false,
    staleTime: 60_000,
  })

  const options = useMemo(() => {
    const keys = new Set<string>()
    if (defsQuery.data) {
      for (const d of defsQuery.data) keys.add(d.key)
    } else {
      for (const k of seenKeys) keys.add(k)
      if (filters.definitionKey) keys.add(filters.definitionKey)
    }
    const items = [{ value: '', label: '全部流程' }]
    for (const key of keys) {
      items.push({ value: key, label: definitionLabel(key) })
    }
    return items
  }, [defsQuery.data, seenKeys, filters.definitionKey])

  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(window.location.href)
      message.success('已复制当前链接')
    } catch {
      message.error('复制失败')
    }
  }

  const defsDenied = defsQuery.isError && !seenKeys.length && !filters.definitionKey

  return (
    <div
      style={{
        marginBottom: 16,
        padding: '12px 16px',
        background: '#fff',
        border: `1px solid ${colors.border}`,
        borderRadius: 8,
      }}
    >
      <Space wrap size={12} style={{ width: '100%' }}>
        <Tag color="blue">租户 {tenantId}</Tag>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          会话租户来自环境，不随流程筛选变化。审方要 his，SKU 上线要等于货主；不在同一会话时改环境重建，不要手改请求头。
        </Typography.Text>
        <Select
          aria-label="流程定义"
          style={{ minWidth: 180 }}
          value={filters.definitionKey ?? ''}
          options={options}
          loading={defsQuery.isLoading}
          onChange={(v) => replaceFilters({ definitionKey: v || undefined, businessKey: undefined, page: undefined })}
        />
        <Input
          allowClear
          aria-label={businessKeyLabel(filters.definitionKey)}
          prefix={<SearchOutlined />}
          placeholder={`${businessKeyLabel(filters.definitionKey)} 筛选`}
          value={bkDraft}
          style={{ width: 240 }}
          onChange={(e) => setBkDraft(e.target.value)}
          onPressEnter={() => replaceFilters({ businessKey: bkDraft.trim() || undefined, page: undefined })}
          onBlur={() => {
            if ((bkDraft.trim() || undefined) !== filters.businessKey) {
              replaceFilters({ businessKey: bkDraft.trim() || undefined, page: undefined })
            }
          }}
        />
        <Button icon={<CopyOutlined />} onClick={() => void copyLink()}>
          复制链接
        </Button>
        {defsDenied && (
          <Typography.Text type="secondary">联系管理员确认租户，或先办理一条待办后再筛流程</Typography.Text>
        )}
      </Space>
    </div>
  )
}

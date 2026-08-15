import { Button, Select, Space, Tooltip } from 'antd'
import {
  FileAddOutlined,
  FolderOpenOutlined,
  UndoOutlined,
  RedoOutlined,
  ExpandOutlined,
} from '@ant-design/icons'
import type { ProcessDefinitionView } from '../../api/types'

interface Props {
  definitions: ProcessDefinitionView[]
  defsLoading: boolean
  currentKey?: string
  canUndo: boolean
  canRedo: boolean
  onNew: () => void
  onLoad: (key: string) => void
  onUndo: () => void
  onRedo: () => void
  onZoomFit: () => void
}

/** 设计器二级工具条(展示型):新建 / 载入已部署定义 / 撤销·重做 / 适配窗口。部署·导出在 PageHeader。 */
export default function ModelerToolbar({
  definitions,
  defsLoading,
  currentKey,
  canUndo,
  canRedo,
  onNew,
  onLoad,
  onUndo,
  onRedo,
  onZoomFit,
}: Props) {
  // 同 key 多版本去重,只列最新(定义列表已按 key asc / version desc)。
  const seen = new Set<string>()
  const options = definitions
    .filter((d) => (seen.has(d.key) ? false : (seen.add(d.key), true)))
    .map((d) => ({ value: d.key, label: `${d.key}${d.name ? ` (${d.name})` : ''}` }))

  return (
    <Space style={{ marginBottom: 16 }} wrap>
      <Button icon={<FileAddOutlined />} onClick={onNew}>
        新建流程
      </Button>
      <Select
        placeholder="载入已部署定义"
        style={{ minWidth: 220 }}
        loading={defsLoading}
        options={options}
        value={currentKey}
        onChange={onLoad}
        showSearch
        optionFilterProp="label"
        suffixIcon={<FolderOpenOutlined />}
      />
      <Tooltip title="撤销 (Ctrl+Z)">
        <Button icon={<UndoOutlined />} disabled={!canUndo} onClick={onUndo} />
      </Tooltip>
      <Tooltip title="重做 (Ctrl+Y)">
        <Button icon={<RedoOutlined />} disabled={!canRedo} onClick={onRedo} />
      </Tooltip>
      <Tooltip title="适配窗口">
        <Button icon={<ExpandOutlined />} onClick={onZoomFit} />
      </Tooltip>
    </Space>
  )
}

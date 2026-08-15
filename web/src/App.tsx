import {
  CloudServerOutlined,
  DeleteOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  VideoCameraOutlined,
} from '@ant-design/icons'
import {
  App as AntApp,
  Button,
  Flex,
  Form,
  Input,
  Modal,
  Popconfirm,
  Segmented,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { liveApi, type LiveInstance, type LiveRoom, type LiveSummary } from './api'

const { Text, Title } = Typography

type ViewMode = 'rooms' | 'instances'

function compactId(value: string | null) {
  if (!value) return '-'
  if (value.length <= 28) return value
  return `${value.slice(0, 14)}...${value.slice(-10)}`
}

function formatHeartbeat(value: number) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(new Date(value))
}

export default function App() {
  const { message } = AntApp.useApp()
  const [summary, setSummary] = useState<LiveSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [mutating, setMutating] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [view, setView] = useState<ViewMode>('rooms')
  const [form] = Form.useForm<{ liveId: string }>()

  const refresh = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true)
    try {
      setSummary(await liveApi.summary())
    } catch (error) {
      message.error(error instanceof Error ? error.message : '无法获取监听列表')
    } finally {
      if (!quiet) setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
    const timer = window.setInterval(() => void refresh(true), 10_000)
    return () => window.clearInterval(timer)
  }, [refresh])

  const runAction = async (key: string, action: () => Promise<unknown>, success: string) => {
    setMutating(key)
    try {
      await action()
      message.success(success)
      await refresh(true)
    } catch (error) {
      message.error(error instanceof Error ? error.message : '操作失败')
    } finally {
      setMutating(null)
    }
  }

  const roomColumns = useMemo<ColumnsType<LiveRoom>>(() => [
    {
      title: '直播间', dataIndex: 'liveId', width: 180, fixed: 'left',
      render: (liveId: string) => <Text copyable={{ text: liveId }} strong>{liveId}</Text>,
    },
    {
      title: '状态', dataIndex: 'desiredState', width: 110,
      filters: [{ text: '监听中', value: 'RUNNING' }, { text: '已暂停', value: 'PAUSED' }],
      onFilter: (value, room) => room.desiredState === value,
      render: (state: LiveRoom['desiredState']) => state === 'RUNNING'
        ? <Tag color="success">监听中</Tag>
        : <Tag>已暂停</Tag>,
    },
    {
      title: '管理实例', dataIndex: 'managingInstanceId', width: 240,
      render: (instanceId: string | null, room) => (
        <Space size={6}>
          <Tooltip title={instanceId || '尚未取得租约'}><Text code>{compactId(instanceId)}</Text></Tooltip>
          {room.managedByCurrentInstance && <Tag color="blue">本实例</Tag>}
        </Space>
      ),
    },
    {
      title: '目标实例', dataIndex: 'assignedInstanceId', width: 210,
      render: (instanceId: string | null) => <Tooltip title={instanceId}><Text type="secondary">{compactId(instanceId)}</Text></Tooltip>,
    },
    {
      title: '连接', dataIndex: 'listeningOnThisInstance', width: 100,
      render: (connected: boolean, room) => connected
        ? <Tag color="processing">已连接</Tag>
        : room.desiredState === 'RUNNING' ? <Tag color="warning">远端/迁移中</Tag> : <Text type="secondary">-</Text>,
    },
    {
      title: '操作', key: 'actions', width: 170, fixed: 'right',
      render: (_, room) => (
        <Space size={4}>
          {room.desiredState === 'RUNNING' ? (
            <Tooltip title="暂停监听">
              <Button aria-label="暂停监听" icon={<PauseCircleOutlined />} loading={mutating === `pause:${room.liveId}`}
                onClick={() => void runAction(`pause:${room.liveId}`, () => liveApi.pause(room.liveId), '已暂停监听')} />
            </Tooltip>
          ) : (
            <Tooltip title="恢复监听">
              <Button aria-label="恢复监听" type="primary" ghost icon={<PlayCircleOutlined />} loading={mutating === `resume:${room.liveId}`}
                onClick={() => void runAction(`resume:${room.liveId}`, () => liveApi.resume(room.liveId), '已恢复监听')} />
            </Tooltip>
          )}
          <Popconfirm title="删除直播间" description="删除后将停止监听并移除管理记录。" okText="删除" cancelText="取消"
            onConfirm={() => runAction(`delete:${room.liveId}`, () => liveApi.remove(room.liveId), '已删除直播间')}>
            <Tooltip title="删除"><Button aria-label="删除直播间" danger icon={<DeleteOutlined />} loading={mutating === `delete:${room.liveId}`} /></Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ], [mutating])

  const instanceColumns: ColumnsType<LiveInstance> = [
    { title: '实例', dataIndex: 'instanceId', width: 280, render: (id: string) => <Text copyable code>{id}</Text> },
    { title: '状态', dataIndex: 'online', width: 100, render: (online: boolean) => <Tag color={online ? 'success' : 'error'}>{online ? '在线' : '失联'}</Tag> },
    { title: '监听负载', dataIndex: 'assignedRoomCount', width: 120, sorter: (a, b) => a.assignedRoomCount - b.assignedRoomCount },
    { title: '最后心跳', dataIndex: 'lastHeartbeatEpochMs', width: 180, render: formatHeartbeat },
    { title: '分配房间', dataIndex: 'assignedLiveIds', render: (ids: string[]) => ids.length ? <Space size={[4, 4]} wrap>{ids.map(id => <Tag key={id}>{id}</Tag>)}</Space> : <Text type="secondary">暂无</Text> },
  ]

  const createRoom = async () => {
    const { liveId } = await form.validateFields()
    await runAction(`create:${liveId}`, () => liveApi.start(liveId.trim()), '监听任务已创建')
    form.resetFields()
    setCreateOpen(false)
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand"><VideoCameraOutlined /><span>直播监听管理</span></div>
        <Space>
          <Tooltip title="刷新"><Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()} /></Tooltip>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新增监听</Button>
        </Space>
      </header>

      <main className="content">
        <section className="page-heading">
          <div><Title level={2}>监听任务</Title><Text type="secondary">统一管理 Redis 集群中的直播间与实例负载</Text></div>
          <Tag color={summary?.distributed ? 'blue' : 'default'}>{summary?.distributed ? 'Redis 多实例' : '本地模式'}</Tag>
        </section>

        <section className="stats-band">
          <Statistic title="全部直播间" value={summary?.total ?? 0} />
          <Statistic title="正在监听" value={summary?.running ?? 0} valueStyle={{ color: '#15803d' }} />
          <Statistic title="已暂停" value={summary?.paused ?? 0} />
          <Statistic title="在线实例" value={summary?.instances.filter(item => item.online).length ?? 0} prefix={<CloudServerOutlined />} />
          <Statistic title="本实例连接" value={summary?.localListening ?? 0} />
        </section>

        <section className="table-panel">
          <Flex justify="space-between" align="center" gap={12} wrap="wrap" className="table-toolbar">
            <Segmented value={view} onChange={value => setView(value as ViewMode)} options={[{ label: '直播间', value: 'rooms' }, { label: '实例负载', value: 'instances' }]} />
            <Text type="secondary">每 10 秒自动刷新 · 当前实例 {compactId(summary?.instanceId ?? null)}</Text>
          </Flex>
          {view === 'rooms' ? (
            <Table rowKey="liveId" loading={loading} columns={roomColumns} dataSource={summary?.rooms ?? []} pagination={{ pageSize: 20, showSizeChanger: true, showTotal: total => `共 ${total} 个直播间` }} scroll={{ x: 1130 }} />
          ) : (
            <Table rowKey="instanceId" loading={loading} columns={instanceColumns} dataSource={summary?.instances ?? []} pagination={false} scroll={{ x: 900 }} />
          )}
        </section>
      </main>

      <Modal title="新增直播间监听" open={createOpen} okText="开始监听" cancelText="取消" confirmLoading={mutating?.startsWith('create:')} onOk={() => void createRoom()} onCancel={() => setCreateOpen(false)} destroyOnHidden>
        <Form form={form} layout="vertical" requiredMark={false} className="create-form">
          <Form.Item name="liveId" label="直播间 ID" rules={[{ required: true, whitespace: true, message: '请输入直播间 ID' }]}>
            <Input autoFocus placeholder="例如 5200nono" allowClear />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

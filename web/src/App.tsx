import {
  CloudServerOutlined,
  DeleteOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SettingOutlined,
  InfoCircleOutlined,
  VideoCameraOutlined,
} from '@ant-design/icons'
import {
  App as AntApp,
  Button,
  Empty,
  Flex,
  Form,
  Input,
  InputNumber,
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
import { pbkdf2Async } from '@noble/hashes/pbkdf2.js'
import { sha256 } from '@noble/hashes/sha2.js'
import { bytesToHex } from '@noble/hashes/utils.js'
import type { ColumnsType } from 'antd/es/table'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { liveApi, type LiveInstance, type LiveRoom, type LiveSummary } from './api'

const { Text, Title } = Typography

const settingsPasswordSalt = 'toolsapi-netease-cookie-settings-v1'
const settingsPasswordIterations = 210_000
const settingsPasswordHash = '5a6815d135a467532d1b0095bd55720446450e1ceda6bd76a8a7273de0ff8f66'

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

function roomStateTag(state: LiveRoom['desiredState']) {
  return ({
    RUNNING: <Tag color="success">监听中</Tag>,
    PAUSED: <Tag>已暂停</Tag>,
    FAILED: <Tag color="error">失败</Tag>,
    ENDED: <Tag color="default">已结束</Tag>,
  })[state]
}

export default function App() {
  const { message } = AntApp.useApp()
  const [summary, setSummary] = useState<LiveSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [mutating, setMutating] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [passwordOpen, setPasswordOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [settingsLoading, setSettingsLoading] = useState(false)
  const [failureRoom, setFailureRoom] = useState<LiveRoom | null>(null)
  const [roomDetail, setRoomDetail] = useState<LiveRoom | null>(null)
  const [compactLayout, setCompactLayout] = useState(() => window.matchMedia('(max-width: 760px)').matches)
  const [view, setView] = useState<ViewMode>('rooms')
  const [form] = Form.useForm<{ liveId: string }>()
  const [passwordForm] = Form.useForm<{ password: string }>()
  const [settingsForm] = Form.useForm<{ inactivityMinutes: number }>()

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

  useEffect(() => {
    const media = window.matchMedia('(max-width: 760px)')
    const update = () => setCompactLayout(media.matches)
    media.addEventListener('change', update)
    return () => media.removeEventListener('change', update)
  }, [])

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
      filters: [
        { text: '监听中', value: 'RUNNING' }, { text: '已暂停', value: 'PAUSED' },
        { text: '失败', value: 'FAILED' }, { text: '已结束', value: 'ENDED' },
      ],
      onFilter: (value, room) => room.desiredState === value,
      render: roomStateTag,
    },
    {
      title: '监听实例', dataIndex: 'managingInstanceId', width: 220,
      render: (instanceId: string | null, room) => (
        <Space size={6}>
          <Tooltip title={instanceId || '尚未取得租约'}><Text code>{compactId(instanceId)}</Text></Tooltip>
          {room.managedByCurrentInstance && <Tag color="blue">本实例</Tag>}
        </Space>
      ),
    },
    {
      title: '分配实例', dataIndex: 'assignedInstanceId', width: 190,
      render: (instanceId: string | null) => <Tooltip title={instanceId}><Text type="secondary">{compactId(instanceId)}</Text></Tooltip>,
    },
    {
      title: '运行状态', dataIndex: 'listeningOnThisInstance', width: 120,
      render: (connected: boolean, room) => connected
        ? <Tag color="processing">本实例连接</Tag>
        : room.managingInstanceId ? <Tag color="blue">远端实例管理</Tag>
          : room.desiredState === 'RUNNING' ? <Tag color="warning">等待分配</Tag> : <Text type="secondary">-</Text>,
    },
    {
      title: '不活跃期限', dataIndex: 'expiresAtEpochMs', width: 180,
      render: (expiresAt: number | null, room) => room.desiredState === 'PAUSED' || !expiresAt
        ? <Text type="secondary">-</Text>
        : <Tooltip title={`最后活跃：${formatHeartbeat(room.lastActivityEpochMs!)}`}><Text>{formatHeartbeat(expiresAt)}</Text></Tooltip>,
    },
    {
      title: '操作', key: 'actions', width: 156, fixed: 'right',
      render: (_, room) => (
        <Space size={4} className="room-action-group">
          <Tooltip title="查询状态并刷新不活跃计时">
            <Button aria-label="查询状态" icon={<SearchOutlined />} loading={mutating === `status:${room.liveId}`}
              onClick={() => void queryRoom(room.liveId)} />
          </Tooltip>
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

  const queryRoom = async (liveId: string) => {
    setMutating(`status:${liveId}`)
    try {
      const room = await liveApi.room(liveId)
      setRoomDetail(room)
      await refresh(true)
    } catch (error) {
      message.error(error instanceof Error ? error.message : '无法查询监听状态')
    } finally {
      setMutating(null)
    }
  }

  const verifySettingsPassword = async () => {
    const { password } = await passwordForm.validateFields()
    setSettingsLoading(true)
    try {
      const derived = await pbkdf2Async(sha256, password, settingsPasswordSalt, {
        c: settingsPasswordIterations, dkLen: 32, asyncTick: 8,
      })
      if (bytesToHex(derived) !== settingsPasswordHash) {
        passwordForm.setFields([{ name: 'password', errors: ['密码错误'] }])
        return
      }
      const settings = await liveApi.settings()
      settingsForm.setFieldsValue({ inactivityMinutes: settings.inactivityTimeoutSeconds / 60 })
      passwordForm.resetFields()
      setPasswordOpen(false)
      setSettingsOpen(true)
    } catch (error) {
      message.error(error instanceof Error ? error.message : '无法读取设置')
    } finally {
      setSettingsLoading(false)
    }
  }

  const saveSettings = async () => {
    const { inactivityMinutes } = await settingsForm.validateFields()
    setSettingsLoading(true)
    try {
      await liveApi.updateSettings(Math.round(inactivityMinutes * 60))
      message.success('设置已更新，所有监听任务已按新时长重新计算')
      setSettingsOpen(false)
      await refresh(true)
    } catch (error) {
      message.error(error instanceof Error ? error.message : '设置保存失败')
    } finally {
      setSettingsLoading(false)
    }
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand"><VideoCameraOutlined /><span>直播监听管理</span></div>
        <Space>
          <Tooltip title="设置"><Button aria-label="设置" icon={<SettingOutlined />} onClick={() => setPasswordOpen(true)} /></Tooltip>
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
          <Statistic title="失败/结束" value={(summary?.failed ?? 0) + (summary?.ended ?? 0)} valueStyle={{ color: summary?.failed ? '#dc2626' : undefined }} />
          <Statistic title="在线实例" value={summary?.instances.filter(item => item.online).length ?? 0} prefix={<CloudServerOutlined />} />
          <Statistic title="本实例连接" value={summary?.localListening ?? 0} />
        </section>

        <section className="table-panel">
          <Flex justify="space-between" align="center" gap={12} wrap="wrap" className="table-toolbar">
            <Segmented value={view} onChange={value => setView(value as ViewMode)} options={[{ label: '直播间', value: 'rooms' }, { label: '实例负载', value: 'instances' }]} />
            <Text type="secondary">全部实例任务 · 每 10 秒自动刷新 · 当前实例 {compactId(summary?.instanceId ?? null)}</Text>
          </Flex>
          {view === 'rooms' && compactLayout ? (
            <div className="room-card-list">
              {(summary?.rooms.length ?? 0) === 0 && <Empty description="暂无监听任务" />}
              {(summary?.rooms ?? []).map(room => (
                <article className="room-card" key={room.liveId}>
                  <div className="room-card-head">
                    <Text copyable={{ text: room.liveId }} strong>{room.liveId}</Text>
                    {roomStateTag(room.desiredState)}
                  </div>
                  <div className="room-card-meta">
                    <span>监听实例</span><Tooltip title={room.managingInstanceId}><Text code>{compactId(room.managingInstanceId)}</Text></Tooltip>
                    <span>目标实例</span><Tooltip title={room.assignedInstanceId}><Text>{compactId(room.assignedInstanceId)}</Text></Tooltip>
                    <span>运行状态</span><span>{room.listeningOnThisInstance ? <Tag color="processing">本实例连接</Tag> : room.managingInstanceId ? <Tag color="blue">远端实例管理</Tag> : room.desiredState === 'RUNNING' ? <Tag color="warning">等待分配</Tag> : '-'}</span>
                    <span>记录失效</span><Text>{formatHeartbeat(room.recordExpiresAtEpochMs)}</Text>
                  </div>
                  {room.lastFailureReason && <Button className="room-card-failure" type="link" icon={<InfoCircleOutlined />} onClick={() => setFailureRoom(room)}>查看失败原因（连续 {room.consecutiveFailures} 次）</Button>}
                  <div className="room-card-actions">
                    <Button icon={<SearchOutlined />} loading={mutating === `status:${room.liveId}`} onClick={() => void queryRoom(room.liveId)}>查询状态</Button>
                    {room.desiredState === 'RUNNING' ? (
                      <Button icon={<PauseCircleOutlined />} loading={mutating === `pause:${room.liveId}`} onClick={() => void runAction(`pause:${room.liveId}`, () => liveApi.pause(room.liveId), '已暂停监听')}>暂停</Button>
                    ) : (
                      <Button type="primary" ghost icon={<PlayCircleOutlined />} loading={mutating === `resume:${room.liveId}`} onClick={() => void runAction(`resume:${room.liveId}`, () => liveApi.resume(room.liveId), '已恢复监听')}>恢复</Button>
                    )}
                    <Popconfirm title="删除直播间" description="删除后将停止监听并移除管理记录。" okText="删除" cancelText="取消" onConfirm={() => runAction(`delete:${room.liveId}`, () => liveApi.remove(room.liveId), '已删除直播间')}>
                      <Button danger icon={<DeleteOutlined />} loading={mutating === `delete:${room.liveId}`}>删除</Button>
                    </Popconfirm>
                  </div>
                </article>
              ))}
            </div>
          ) : view === 'rooms' ? (
            <Table rowKey="liveId" loading={loading} columns={roomColumns} dataSource={summary?.rooms ?? []} pagination={{ pageSize: 20, showSizeChanger: true, showTotal: total => `共 ${total} 个直播间` }} scroll={{ x: 1230 }} />
          ) : compactLayout ? (
            <div className="instance-card-list">
              {(summary?.instances.length ?? 0) === 0 && <Empty description="暂无在线实例" />}
              {(summary?.instances ?? []).map(instance => (
                <article className="instance-card" key={instance.instanceId}>
                  <div className="instance-card-head">
                    <Text copyable code>{instance.instanceId}</Text>
                    <Tag color={instance.online ? 'success' : 'error'}>{instance.online ? '在线' : '失联'}</Tag>
                  </div>
                  <div className="room-card-meta">
                    <span>监听负载</span><Text strong>{instance.assignedRoomCount}</Text>
                    <span>最后心跳</span><Text>{formatHeartbeat(instance.lastHeartbeatEpochMs)}</Text>
                    <span>分配房间</span><span>{instance.assignedLiveIds.length ? <Space size={[4, 4]} wrap>{instance.assignedLiveIds.map(id => <Tag key={id}>{id}</Tag>)}</Space> : '-'}</span>
                  </div>
                </article>
              ))}
            </div>
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

      <Modal title="验证设置密码" open={passwordOpen} okText="验证" cancelText="取消" confirmLoading={settingsLoading}
        onOk={() => void verifySettingsPassword()} onCancel={() => { setPasswordOpen(false); passwordForm.resetFields() }} destroyOnHidden>
        <Form form={passwordForm} layout="vertical" requiredMark={false}>
          <Form.Item name="password" label="设置密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password autoFocus autoComplete="current-password" placeholder="请输入设置密码" onPressEnter={() => void verifySettingsPassword()} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title="监听设置" open={settingsOpen} okText="保存" cancelText="取消" confirmLoading={settingsLoading}
        onOk={() => void saveSettings()} onCancel={() => setSettingsOpen(false)} destroyOnHidden>
        <Form form={settingsForm} layout="vertical" requiredMark={false}>
          <Form.Item name="inactivityMinutes" label="不活跃监听时长" extra="从开始监听或最近一次查询该直播间状态起计算。"
            rules={[{ required: true, message: '请输入不活跃监听时长' }, { type: 'number', min: 1, message: '不能少于 1 分钟' }]}>
            <InputNumber min={1} max={10080} precision={0} addonAfter="分钟" style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title="监听失败详情" open={failureRoom !== null} footer={null} onCancel={() => setFailureRoom(null)} destroyOnHidden>
        {failureRoom && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Text>直播间：<Text code>{failureRoom.liveId}</Text></Text>
            <Text>连续失败：<Text strong type="danger">{failureRoom.consecutiveFailures} 次</Text></Text>
            <Text type="secondary">失败原因</Text>
            <Typography.Paragraph copyable style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{failureRoom.lastFailureReason}</Typography.Paragraph>
            <Text type="secondary">记录将在 {formatHeartbeat(failureRoom.recordExpiresAtEpochMs)} 自动失效</Text>
          </Space>
        )}
      </Modal>

      <Modal title="监听状态" open={roomDetail !== null} footer={null} onCancel={() => setRoomDetail(null)} destroyOnHidden>
        {roomDetail && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Flex justify="space-between" align="center"><Text code>{roomDetail.liveId}</Text>{roomStateTag(roomDetail.desiredState)}</Flex>
            <Text>监听实例：<Text code>{roomDetail.managingInstanceId || '-'}</Text></Text>
            <Text>目标实例：<Text code>{roomDetail.assignedInstanceId || '-'}</Text></Text>
            <Text>连接状态：{roomDetail.listeningOnThisInstance ? '本实例已连接' : '未连接或由其他实例管理'}</Text>
            <Text>不活跃期限：{roomDetail.expiresAtEpochMs ? formatHeartbeat(roomDetail.expiresAtEpochMs) : '-'}</Text>
            {roomDetail.lastFailureReason && <>
              <Text type="secondary">失败原因（连续 {roomDetail.consecutiveFailures} 次）</Text>
              <Typography.Paragraph copyable style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{roomDetail.lastFailureReason}</Typography.Paragraph>
            </>}
          </Space>
        )}
      </Modal>
    </div>
  )
}

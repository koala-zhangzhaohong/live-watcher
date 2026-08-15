export type RoomState = 'RUNNING' | 'PAUSED'

export interface LiveRoom {
  liveId: string
  desiredState: RoomState
  assignedInstanceId: string | null
  managingInstanceId: string | null
  managedByCurrentInstance: boolean
  listeningOnThisInstance: boolean
}

export interface LiveInstance {
  instanceId: string
  lastHeartbeatEpochMs: number
  online: boolean
  assignedRoomCount: number
  assignedLiveIds: string[]
}

export interface LiveSummary {
  total: number
  running: number
  paused: number
  localListening: number
  instanceId: string
  distributed: boolean
  rooms: LiveRoom[]
  instances: LiveInstance[]
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/douyin/live${path}`, {
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', ...init?.headers },
    ...init,
  })
  if (!response.ok) {
    const detail = await response.text()
    throw new Error(detail || `请求失败 (${response.status})`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const liveApi = {
  summary: () => request<LiveSummary>(''),
  start: (liveId: string) => request<LiveRoom>('/start', { method: 'POST', body: JSON.stringify({ liveId }) }),
  pause: (liveId: string) => request<LiveRoom>(`/${encodeURIComponent(liveId)}/pause`, { method: 'POST' }),
  resume: (liveId: string) => request<LiveRoom>(`/${encodeURIComponent(liveId)}/resume`, { method: 'POST' }),
  remove: (liveId: string) => request<void>(`/${encodeURIComponent(liveId)}`, { method: 'DELETE' }),
}

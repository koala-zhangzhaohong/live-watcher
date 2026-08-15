export type RoomState = 'RUNNING' | 'PAUSED' | 'FAILED' | 'ENDED'

export interface LiveRoom {
  liveId: string
  desiredState: RoomState
  assignedInstanceId: string | null
  managingInstanceId: string | null
  managedByCurrentInstance: boolean
  listeningOnThisInstance: boolean
  lastActivityEpochMs: number | null
  expiresAtEpochMs: number | null
  consecutiveFailures: number
  lastFailureReason: string | null
  recordExpiresAtEpochMs: number
}

export interface LiveSettings {
  inactivityTimeoutSeconds: number
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
  failed: number
  ended: number
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
  settings: () => request<LiveSettings>('/settings'),
  updateSettings: (inactivityTimeoutSeconds: number) => request<LiveSettings>('/settings', {
    method: 'PUT', body: JSON.stringify({ inactivityTimeoutSeconds }),
  }),
  room: (liveId: string) => request<LiveRoom>(`/${encodeURIComponent(liveId)}`),
  start: (liveId: string) => request<LiveRoom>('/start', { method: 'POST', body: JSON.stringify({ liveId }) }),
  pause: (liveId: string) => request<LiveRoom>(`/${encodeURIComponent(liveId)}/pause`, { method: 'POST' }),
  resume: (liveId: string) => request<LiveRoom>(`/${encodeURIComponent(liveId)}/resume`, { method: 'POST' }),
  remove: (liveId: string) => request<void>(`/${encodeURIComponent(liveId)}`, { method: 'DELETE' }),
}

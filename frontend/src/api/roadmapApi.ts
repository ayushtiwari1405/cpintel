import { apiClient } from './client'
import type { ApiResponse } from '@/types'

export interface RoadmapProblem {
  contestId: number
  index: string
  name: string
  rating: number
  tags: string[]
  solved: boolean
  url: string
}

export interface RoadmapNodeData {
  nodeId: number
  nodeKey: string
  topic: string
  parentTopic: string
  status: 'LOCKED' | 'UNLOCKED' | 'IN_PROGRESS' | 'COMPLETED'
  orderIndex: number
  minDifficulty: number
  maxDifficulty: number
  prereqKeys: string[]
  unlockedAt: string | null
  completedAt: string | null
  problems: RoadmapProblem[]
}

export const roadmapApi = {
  getCurrent: () =>
    apiClient.get<ApiResponse<RoadmapNodeData[]>>('/roadmaps/current').then(r => r.data),

  regenerate: () =>
    apiClient.post<ApiResponse<RoadmapNodeData[]>>('/roadmaps/regenerate').then(r => r.data),

  updateNode: (nodeId: number, status: string) =>
    apiClient.patch<ApiResponse<RoadmapNodeData>>(
      `/roadmaps/nodes/${nodeId}?status=${status}`
    ).then(r => r.data),
}

import { apiClient } from './client'
import type { ApiResponse } from '@/types'

/** One installer in the newest desktop release. */
export interface DesktopAsset {
  name: string
  platform: 'windows' | 'mac' | 'linux'
  arch: 'x64' | 'arm64'
  format: 'exe' | 'dmg' | 'AppImage' | 'deb'
  size: number
  /** Hex SHA-256 GitHub computed for the file; null for very old uploads. */
  sha256: string | null
  /** This server's download route for it. */
  url: string
}

export interface DesktopRelease {
  /** False when this deployment has not been told where its installers are published. */
  configured: boolean
  /** Null before the first release is published. */
  version: string | null
  publishedAt: string | null
  /** Installed apps older than this are told they must update. */
  minimumVersion: string
  assets: DesktopAsset[]
}

export const desktopApi = {
  release: () =>
    apiClient.get<ApiResponse<DesktopRelease>>('/desktop/release').then(r => r.data.data),
}

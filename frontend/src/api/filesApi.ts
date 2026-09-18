import { apiClient } from './client'
import type {
  ApiResponse, ContestRef, FileVault, PersonalFile, PersonalFileContent,
} from '@/types'

/** The contest-scoped prefix, which carries the judge as well as the id. */
const contestBase = (ref: ContestRef) =>
  `/compete/${ref.platform}/${encodeURIComponent(ref.id)}`

/**
 * Personal files — your own templates, notes and reference material.
 *
 * Two ways in. The plain `/files` routes manage the library and are always open; the
 * contest-scoped ones read it through a contest, which is where an admin's rule for that
 * contest decides whether anything comes back. The page uses the contest routes while a
 * contest is open so that rule is actually enforced, rather than checked once and trusted.
 */
export const filesApi = {
  vault: () =>
    apiClient.get<ApiResponse<FileVault>>('/files').then(r => r.data),

  upload: (file: File, opts: { label?: string; replace?: boolean } = {}) => {
    const form = new FormData()
    form.append('file', file)
    if (opts.label) form.append('label', opts.label)
    if (opts.replace) form.append('replace', 'true')
    // No Content-Type here on purpose: axios drops the client's JSON default for a
    // FormData body so the browser can set the header with its own multipart boundary.
    return apiClient.post<ApiResponse<PersonalFile>>('/files', form, { timeout: 120_000 })
      .then(r => r.data)
  },

  content: (fileId: string) =>
    apiClient.get<ApiResponse<PersonalFileContent>>(`/files/${fileId}`).then(r => r.data),

  rename: (fileId: string, body: { name: string; label?: string | null }) =>
    apiClient.patch<ApiResponse<PersonalFile>>(`/files/${fileId}`, body).then(r => r.data),

  remove: (fileId: string) =>
    apiClient.delete<ApiResponse<void>>(`/files/${fileId}`).then(r => r.data),

  /** Raw bytes, for handing to a save dialog or an object URL. */
  download: (fileId: string, contest?: ContestRef) =>
    apiClient.get<Blob>(
      contest
        ? `${contestBase(contest)}/files/${fileId}/download`
        : `/files/${fileId}/download`,
      { responseType: 'blob', timeout: 120_000 }).then(r => r.data),

  // --------------------------------------------------------- through a contest

  contestVault: (contest: ContestRef) =>
    apiClient.get<ApiResponse<FileVault>>(`${contestBase(contest)}/files`).then(r => r.data),

  contestContent: (contest: ContestRef, fileId: string) =>
    apiClient.get<ApiResponse<PersonalFileContent>>(
      `${contestBase(contest)}/files/${fileId}`).then(r => r.data)
}

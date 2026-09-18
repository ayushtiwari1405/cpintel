import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { filesApi } from '@/api/filesApi'
import { useToast } from '@/components/common/Toaster'
import type { ContestRef } from '@/types'

/** Stable per-contest cache key that stays distinct across judges. */
const refKey = (contest?: ContestRef) =>
  contest ? `${contest.platform}:${contest.id}` : null

/**
 * Your library of personal files.
 *
 * `contest` decides which endpoint answers, not what the UI shows: passing one routes the
 * read through the contest so the admin's rule for that contest is enforced on every request
 * rather than trusted from a flag the page read once at load.
 */
export function useFileVault(contest?: ContestRef, enabled = true) {
  return useQuery({
    queryKey: ['files', 'vault', refKey(contest)],
    queryFn: () => (contest
      ? filesApi.contestVault(contest)
      : filesApi.vault()).then(r => r.data),
    enabled,
    staleTime: 60_000,
    retry: false,
  })
}

/**
 * One file's contents.
 *
 * Cached hard: a file changes only when this user replaces it, and the upload invalidates
 * the whole `files` key when they do. Mid-contest that means reopening a template is
 * instant and does not touch the network.
 */
export function useFileContent(fileId: string | null, contest?: ContestRef) {
  return useQuery({
    queryKey: ['files', 'content', fileId, refKey(contest)],
    queryFn: () => (contest
      ? filesApi.contestContent(contest, fileId!)
      : filesApi.content(fileId!)).then(r => r.data),
    enabled: !!fileId,
    staleTime: Infinity,
    gcTime: 30 * 60 * 1000,
    retry: false,
  })
}

export function useUploadFile() {
  const qc = useQueryClient()
  const toast = useToast()

  return useMutation({
    mutationFn: ({ file, label, replace }: { file: File; label?: string; replace?: boolean }) =>
      filesApi.upload(file, { label, replace }),
    onSuccess: (res) => {
      toast.push('success', `${res.data.name} uploaded`)
      qc.invalidateQueries({ queryKey: ['files'] })
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Could not upload that file')
    },
  })
}

export function useDeleteFile() {
  const qc = useQueryClient()
  const toast = useToast()

  return useMutation({
    mutationFn: (fileId: string) => filesApi.remove(fileId),
    onSuccess: () => {
      toast.push('info', 'File deleted')
      qc.invalidateQueries({ queryKey: ['files'] })
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Could not delete that file')
    },
  })
}

export function useRenameFile() {
  const qc = useQueryClient()
  const toast = useToast()

  return useMutation({
    mutationFn: ({ fileId, name, label }: { fileId: string; name: string; label?: string | null }) =>
      filesApi.rename(fileId, { name, label }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['files'] })
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Could not rename that file')
    },
  })
}

/**
 * Saves a file to disk.
 *
 * Goes through the API rather than a plain link because the download needs the bearer token,
 * and during a contest it has to go through the contest route so a closed contest cannot be
 * side-stepped by asking for the bytes directly.
 */
export function useDownloadFile(contest?: ContestRef) {
  const toast = useToast()

  return useMutation({
    mutationFn: async ({ fileId, name }: { fileId: string; name: string }) => {
      const blob = await filesApi.download(fileId, contest)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = name
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
    },
    onError: (err: any) => {
      toast.push('error', err.response?.data?.message ?? 'Could not download that file')
    },
  })
}

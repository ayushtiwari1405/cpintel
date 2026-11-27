import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminClassroomsApi, classroomsApi } from '@/api/classroomsApi'
import { useToast } from '@/components/common/Toaster'
import { useIsAdmin } from '@/hooks/useAdmin'
import type { ClassroomRequest, ClassroomSummary } from '@/types'
import { reloadAfterChange } from '@/utils/queries'

// ------------------------------------------------------------------ student

/** The classrooms this person is enrolled in. */
export function useMyClassrooms() {
  return useQuery({
    queryKey: ['classrooms', 'mine'],
    queryFn: () => classroomsApi.mine().then(r => r.data),
    staleTime: 1000 * 60 * 5,
  })
}

const SELECTED_KEY = 'cpintel.classroom'

/**
 * Which classroom the student is looking at in normal mode, or null for all of them.
 *
 * Remembered in this browser only, as a convenience; losing it just shows every classroom.
 */
export function useSelectedClassroom(): [number | null, (id: number | null) => void] {
  const [selected, setSelected] = useState<number | null>(() => {
    try {
      const raw = localStorage.getItem(SELECTED_KEY)
      return raw ? Number(raw) || null : null
    } catch {
      return null
    }
  })
  useEffect(() => {
    try {
      if (selected == null) localStorage.removeItem(SELECTED_KEY)
      else localStorage.setItem(SELECTED_KEY, String(selected))
    } catch {
      // Private window or blocked storage: the choice simply is not remembered.
    }
  }, [selected])
  return [selected, setSelected]
}

// -------------------------------------------------------------------- admin

export function useAdminClassrooms() {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'classrooms'],
    queryFn: () => adminClassroomsApi.list().then(r => r.data),
    enabled: isAdmin,
  })
}

export function useAdminClassroom(id: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'classroom', id],
    queryFn: () => adminClassroomsApi.detail(id!).then(r => r.data),
    enabled: isAdmin && id != null,
  })
}

/** The class dashboard. Built from stored results, so it is cheap to refetch on focus. */
export function useClassroomDashboard(id: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'classroom', id, 'dashboard'],
    queryFn: () => adminClassroomsApi.dashboard(id!).then(r => r.data),
    enabled: isAdmin && id != null,
    staleTime: 1000 * 30,
  })
}

/** One student's dashboard inside a classroom. */
export function useClassroomStudentDashboard(id: number | null, userId: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'classroom', id, 'student', userId],
    queryFn: () => adminClassroomsApi.studentDashboard(id!, userId!).then(r => r.data),
    enabled: isAdmin && id != null && userId != null,
    staleTime: 1000 * 30,
  })
}

/** Saves a downloaded file under a name made safe for any file system. */
function save(blob: Blob, name: string, suffix: string) {
  const base = name.trim().replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^[._]+|[._]+$/g, '') || 'classroom'
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${base}-${suffix}.xlsx`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

/** The class report: summary, students, labs, events and the detail behind them. */
export function useDownloadClassReport(id: number) {
  const toast = useToast()
  return useMutation({
    mutationFn: async (name: string) => save(await adminClassroomsApi.classReport(id), name, 'report'),
    onError: () => toast.push('error', 'That download did not go through'),
  })
}

/** One event's report. */
export function useDownloadEventReport(id: number) {
  const toast = useToast()
  return useMutation({
    mutationFn: async ({ eventId, name }: { eventId: number; name: string }) =>
      save(await adminClassroomsApi.eventReport(id, eventId), name, 'report'),
    onError: () => toast.push('error', 'That download did not go through'),
  })
}

export function useClassroomMembers(id: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'classroom', id, 'members'],
    queryFn: () => adminClassroomsApi.members(id!).then(r => r.data),
    enabled: isAdmin && id != null,
  })
}

export function useClassroomStaff(id: number | null) {
  const isAdmin = useIsAdmin()
  return useQuery({
    queryKey: ['admin', 'classroom', id, 'staff'],
    queryFn: () => adminClassroomsApi.staff(id!).then(r => r.data),
    enabled: isAdmin && id != null,
  })
}

function errorOf(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback
}

export function useCreateClassroom() {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (body: ClassroomRequest) => adminClassroomsApi.create(body).then(r => r.data),
    onSuccess: room => {
      void reloadAfterChange(qc, ['admin', 'classrooms'])
      toast.push('info', `Created ${room.name}`)
    },
    onError: err => toast.push('error', errorOf(err, 'Could not create the classroom')),
  })
}

export function useUpdateClassroom(id: number) {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (body: ClassroomRequest) => adminClassroomsApi.update(id, body).then(r => r.data),
    onSuccess: room => {
      qc.invalidateQueries({ queryKey: ['admin', 'classrooms'] })
      qc.setQueryData(['admin', 'classroom', id], room)
      toast.push('info', `Saved ${room.name}`)
    },
    onError: err => toast.push('error', errorOf(err, 'Could not save the classroom')),
  })
}

export function useArchiveClassroom(id: number) {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (active: boolean) =>
      active ? adminClassroomsApi.restore(id) : adminClassroomsApi.archive(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin', 'classrooms'] })
      qc.invalidateQueries({ queryKey: ['admin', 'classroom', id] })
    },
    onError: err => toast.push('error', errorOf(err, 'Could not change the classroom')),
  })
}

/** The classroom's Codeforces key and whether its TAs may add questions. */
export function useClassroomSettings(id: number) {
  const qc = useQueryClient()
  const toast = useToast()
  const saved = (message: string) => (room: ClassroomSummary) => {
    qc.invalidateQueries({ queryKey: ['admin', 'classrooms'] })
    qc.setQueryData(['admin', 'classroom', id], room)
    toast.push('info', message)
  }
  return {
    codeforcesKey: useMutation({
      mutationFn: ({ key, secret }: { key: string, secret: string }) =>
        adminClassroomsApi.setCodeforcesKey(id, key, secret).then(r => r.data),
      onSuccess: room => saved(room.codeforcesKeySet ? 'Codeforces key saved' : 'Codeforces key removed')(room),
      onError: err => toast.push('error', errorOf(err, 'Could not save the key')),
    }),
    taQuestions: useMutation({
      mutationFn: (allowed: boolean) => adminClassroomsApi.setTaQuestions(id, allowed).then(r => r.data),
      onSuccess: room => saved(room.tasWriteQuestions ? 'TAs can add questions' : 'TAs can only read questions')(room),
      onError: err => toast.push('error', errorOf(err, 'Could not change it')),
    }),
  }
}

export function useCheckJudge(id: number) {
  return useMutation({
    mutationFn: () => adminClassroomsApi.check(id).then(r => r.data),
  })
}

export function useClassroomMemberActions(id: number) {
  const qc = useQueryClient()
  const toast = useToast()
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['admin', 'classroom', id] })
    qc.invalidateQueries({ queryKey: ['admin', 'classrooms'] })
  }
  const add = useMutation({
    mutationFn: (userId: number) => adminClassroomsApi.addMember(id, userId),
    onSuccess: refresh,
    onError: err => toast.push('error', errorOf(err, 'Could not enrol them')),
  })
  const remove = useMutation({
    mutationFn: (userId: number) => adminClassroomsApi.removeMember(id, userId),
    onSuccess: refresh,
    onError: err => toast.push('error', errorOf(err, 'Could not remove them')),
  })
  return { add, remove }
}

export function useClassroomStaffActions(id: number) {
  const qc = useQueryClient()
  const toast = useToast()
  const refresh = () => qc.invalidateQueries({ queryKey: ['admin', 'classroom', id, 'staff'] })
  const add = useMutation({
    mutationFn: (userId: number) => adminClassroomsApi.addStaff(id, userId),
    onSuccess: refresh,
    onError: err => toast.push('error', errorOf(err, 'Could not add them')),
  })
  const remove = useMutation({
    mutationFn: (userId: number) => adminClassroomsApi.removeStaff(id, userId),
    onSuccess: refresh,
    onError: err => toast.push('error', errorOf(err, 'Could not remove them')),
  })
  return { add, remove }
}

import { describe, expect, it } from 'vitest'
import { filesApi } from './filesApi'
import { fakeServer } from '@/test/server'

describe('file uploads', () => {
  it('send the file as multipart form data, not as JSON', async () => {
    const server = fakeServer({ 'POST /files': { data: { id: 'f1' } } })
    await filesApi.upload(new File(['notes'], 'notes.txt', { type: 'text/plain' }), { label: 'mine' })

    const body = server.calls('POST /files')[0].body
    expect(body).toBeInstanceOf(FormData)
    expect(((body as FormData).get('file') as File).name).toBe('notes.txt')
    expect((body as FormData).get('label')).toBe('mine')
  })
})

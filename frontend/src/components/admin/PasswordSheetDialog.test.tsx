import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { PasswordSheetDialog } from '@/components/admin/PasswordSheetDialog'
import { renderAt } from '@/test/render'
import { fakeServer, type Reply } from '@/test/server'
import { downloadPdf } from '@/utils/pdf'

vi.mock('@/utils/pdf', async original => ({
  ...await original<typeof import('@/utils/pdf')>(),
  downloadPdf: vi.fn(),
}))

const PEOPLE = [
  { userId: 1, username: 'asha', password: 'one-pass', fullName: 'Asha Rao', email: 'asha@x.org' },
  { userId: 2, username: 'ben', password: 'two-pass', fullName: null, email: 'ben@x.org' },
]

const configured: Reply = { data: { configured: true, from: 'exams@x.org' } }

const open = () => renderAt('/',
  <PasswordSheetDialog open onClose={() => {}} kind="exam" fileName="sheets.pdf" people={PEOPLE}
    eventId={3}
    shared={[
      { key: 'exam', label: 'Name', value: 'Midterm' },
      { key: 'start', label: 'Start', value: '1 December' },
      { key: 'end', label: 'End', value: '2 December' },
    ]} />)

const message = () => screen.getByLabelText('Message')

describe('PasswordSheetDialog', () => {
  const download = vi.mocked(downloadPdf)
  beforeEach(() => {
    localStorage.clear()
    download.mockReset()
    fakeServer({ 'GET /admin/users/password-mail': { data: { configured: false, from: null } } })
  })
  afterEach(() => localStorage.clear())

  it('previews each person and downloads one page each', async () => {
    const user = userEvent.setup()
    const { baseElement } = open()
    const preview = () => baseElement.querySelector('svg[viewBox^="0 0 595"]')!.textContent

    expect(preview()).toContain('Midterm')
    expect(preview()).toContain('one-pass')
    await user.click(screen.getByRole('button', { name: 'Next person' }))
    expect(preview()).toContain('two-pass')
    expect(preview()).toContain('Dear ben,')

    await user.click(screen.getByRole('button', { name: /download pdf/i }))
    const [name, pages] = download.mock.calls[0]
    expect(name).toBe('sheets.pdf')
    expect(pages).toHaveLength(2)
    expect(pages[1].map(r => r.text)).toContain('two-pass')
  })

  it('inserts a value where the cursor is and remembers the wording, not the values', async () => {
    const user = userEvent.setup()
    open()

    await user.clear(message())
    await user.type(message(), 'Hello ')
    await user.click(screen.getByRole('button', { name: '{{password}}' }))

    expect(message()).toHaveValue('Hello {{password}}')
    expect(localStorage.getItem('cpintel.passwordSheet.exam')).toBe('Hello {{password}}')
    expect(JSON.stringify(localStorage)).not.toContain('one-pass')

    await user.click(screen.getByRole('button', { name: /restore the original wording/i }))
    expect(localStorage.getItem('cpintel.passwordSheet.exam')).toBeNull()
  })

  it('will not download a message with a misspelt value', async () => {
    const user = userEvent.setup()
    open()

    await user.clear(message())
    await user.type(message(), '{{{{pasword}}')

    expect(screen.getByText(/there is no value called \{\{pasword\}\}/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /download pdf/i })).toBeDisabled()
  })

  it('emails each their own message only once confirmed, and says who it did not reach', async () => {
    const server = fakeServer({
      'GET /admin/users/password-mail': configured,
      'POST /admin/events/3/passwords/mail': () => ({
        data: [
          { userId: 1, sent: true, error: null },
          { userId: 2, sent: false, error: 'The mail server did not accept it.' },
        ],
      }),
    })
    const user = userEvent.setup()
    open()

    await user.click(await screen.findByRole('button', { name: 'Email 2 people' }))
    expect(screen.getByText(/from exams@x\.org/)).toBeInTheDocument()
    expect(screen.getByText(/sign in for the examination as that candidate/i)).toBeInTheDocument()
    expect(server.seen.filter(r => r.method === 'POST')).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Send now' }))

    expect(await screen.findByText('ben: The mail server did not accept it.')).toBeInTheDocument()
    expect(screen.getByText(/1 of 2 handed to the mail server/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Email the 1 not sent' })).toBeEnabled()
    const body = server.seen.find(r => r.method === 'POST')!.body as {
      messages: { userId: number; password: string; subject: string; lines: unknown[] }[]
    }
    expect(body.messages.map(m => [m.userId, m.password, m.subject])).toEqual([
      [1, 'one-pass', 'Midterm: your sign-in password'],
      [2, 'two-pass', 'Midterm: your sign-in password'],
    ])
    expect(JSON.stringify(body.messages[0].lines)).toContain('"one-pass","style":"PASSWORD"')
    expect(JSON.stringify(body.messages[0].lines)).not.toContain('two-pass')
  })

  it('keeps a password out of the subject', async () => {
    fakeServer({ 'GET /admin/users/password-mail': configured })
    const user = userEvent.setup()
    open()

    await user.type(await screen.findByLabelText('Subject'), ' {{{{password}}')

    expect(screen.getByText('Keep the password out of the subject.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Email 2 people' })).toBeDisabled()
  })

  it('offers only the download where the server cannot send mail', async () => {
    open()

    expect(await screen.findByText(/email is not set up on this server/i)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByLabelText('Subject')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: /download pdf/i })).toBeEnabled()
  })
})

import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useLocation } from 'react-router-dom'
import axios from 'axios'
import LoginPage from './LoginPage'
import { fakeServer, gate } from '@/test/server'
import { renderAt, user } from '@/test/render'
import { useAuthStore } from '@/store/authStore'

function Landed() {
  return <h1>{`Landed on ${useLocation().pathname}`}</h1>
}

function renderLogin() {
  return renderAt('/login', (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="*" element={<Landed />} />
    </Routes>
  ))
}

const identifier = () => screen.getByLabelText('Email or username')
const password = () => screen.getByLabelText('Password')

async function signIn(name: string, secret: string) {
  await userEvent.type(identifier(), name)
  await userEvent.type(password(), secret)
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('signing in', () => {
  it('both fields are labelled and required', () => {
    fakeServer({})
    renderLogin()
    expect(identifier()).toBeRequired()
    expect(password()).toBeRequired()
    expect(password()).toHaveAttribute('type', 'password')
    // A username is as good as an address: the field must not insist on an email.
    expect(identifier()).toHaveAttribute('type', 'text')
    expect(screen.getByRole('link', { name: 'Forgot password?' })).toHaveAttribute('href', '/forgot-password')
  })

  it('sends what was typed and lands a student on the dashboard with the token kept', async () => {
    const server = fakeServer({
      'POST /auth/login': { data: { accessToken: 'fresh-token', user: user('USER'), mode: 'NORMAL', examId: null } },
    })
    renderLogin()
    await signIn('alice', 'correct horse')

    expect(await screen.findByText('Landed on /dashboard')).toBeInTheDocument()
    expect(server.calls('POST /auth/login')[0].body).toEqual({ identifier: 'alice', password: 'correct horse' })
    expect(useAuthStore.getState()).toMatchObject({ accessToken: 'fresh-token', isAuthenticated: true, mode: 'NORMAL' })
  })

  it('lands an admin in the console', async () => {
    fakeServer({ 'POST /auth/login': { data: { accessToken: 't', user: user('ADMIN'), mode: 'NORMAL', examId: null } } })
    renderLogin()
    await signIn('admin', 'secret-secret')
    expect(await screen.findByText('Landed on /admin')).toBeInTheDocument()
  })

  it('an examination password opens that paper in examination mode', async () => {
    fakeServer({ 'POST /auth/login': { data: { accessToken: 't', user: user('USER'), mode: 'EXAM', examId: 7 } } })
    renderLogin()
    await signIn('alice', 'ABCD-1234')
    expect(await screen.findByText('Landed on /exam')).toBeInTheDocument()
    expect(useAuthStore.getState()).toMatchObject({ mode: 'EXAM', examId: 7 })
  })

  it('an examination answer without a paper is treated as an ordinary sign-in', async () => {
    fakeServer({ 'POST /auth/login': { data: { accessToken: 't', user: user('USER'), mode: 'EXAM', examId: null } } })
    renderLogin()
    await signIn('alice', 'ABCD-1234')
    expect(await screen.findByText('Landed on /dashboard')).toBeInTheDocument()
    expect(useAuthStore.getState()).toMatchObject({ mode: 'NORMAL', examId: null })
  })

  it('says it is signing in and does not take a second submit meanwhile', async () => {
    const held = gate()
    const server = fakeServer({
      'POST /auth/login': { wait: held.wait, data: { accessToken: 't', user: user('USER'), mode: 'NORMAL', examId: null } },
    })
    renderLogin()
    await signIn('alice', 'correct horse')

    const button = screen.getByRole('button', { name: 'Signing in…' })
    expect(button).toBeDisabled()
    await userEvent.click(button)
    held.open()
    await screen.findByText('Landed on /dashboard')
    expect(server.calls('POST /auth/login')).toHaveLength(1)
  })

  it('a wrong password shows the server\'s message, stays put, and does not try to renew a session', async () => {
    fakeServer({ 'POST /auth/login': { status: 401, body: { code: 'BAD_CREDENTIALS', message: 'Incorrect username or password' } } })
    const renew = vi.spyOn(axios, 'post')
    renderLogin()
    await signIn('alice', 'wrong')

    expect(await screen.findByText('Incorrect username or password')).toBeInTheDocument()
    expect(screen.queryByText(/Landed on/)).not.toBeInTheDocument()
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(renew).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled()
  })

  it('a paper that needs the desktop app says so when signing in here is refused', async () => {
    fakeServer({ 'POST /auth/login': { status: 403, body: { code: 'DESKTOP_APP_REQUIRED', message: 'Use the desktop app' } } })
    renderLogin()
    await signIn('alice', 'ABCD-1234')
    expect(await screen.findByText(/This examination requires the CPIntel desktop app/)).toBeInTheDocument()
  })

  it('explains why the person was signed out when an examination ended', () => {
    window.history.replaceState(null, '', '/login?exam-ended=1')
    try {
      fakeServer({})
      renderLogin()
      expect(screen.getByText(/The examination has ended/)).toBeInTheDocument()
    } finally {
      window.history.replaceState(null, '', '/')
    }
  })

  it('the password can be shown and hidden again', async () => {
    fakeServer({})
    renderLogin()
    await userEvent.click(screen.getByRole('button', { name: 'Show password' }))
    expect(password()).toHaveAttribute('type', 'text')
    await userEvent.click(screen.getByRole('button', { name: 'Hide password' }))
    expect(password()).toHaveAttribute('type', 'password')
  })

  it('can be completed from the keyboard alone', async () => {
    const server = fakeServer({
      'POST /auth/login': { data: { accessToken: 't', user: user('USER'), mode: 'NORMAL', examId: null } },
    })
    renderLogin()
    expect(identifier()).toHaveFocus()
    await userEvent.keyboard('alice')
    await userEvent.tab()
    expect(password()).toHaveFocus()
    await userEvent.keyboard('correct horse{Enter}')
    await waitFor(() => expect(server.calls('POST /auth/login')).toHaveLength(1))
    expect(await screen.findByText('Landed on /dashboard')).toBeInTheDocument()
  })
})

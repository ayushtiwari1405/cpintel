import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ForgotPasswordPage from './ForgotPasswordPage'
import ResetPasswordPage from './ResetPasswordPage'
import ProfilePage from './ProfilePage'
import LoginPage from './LoginPage'
import { fakeServer } from '@/test/server'
import { renderAt, signedIn, user } from '@/test/render'

async function askForLink() {
  await userEvent.type(screen.getByLabelText('Email'), 'alice@cpintel.test')
  await userEvent.click(screen.getByRole('button', { name: 'Send me a link' }))
}

describe('asking for a reset link', () => {
  it('says to check the mail once the server has taken the request', async () => {
    fakeServer({ 'POST /auth/forgot-password': {} })
    renderAt('/forgot-password', <ForgotPasswordPage />)
    await askForLink()
    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument()
  })

  it.each([
    [500, 'An unexpected error occurred'],
    [429, 'Too many requests. Try again in 60 seconds.'],
  ])('keeps the form and says why after a %i', async (status, message) => {
    fakeServer({ 'POST /auth/forgot-password': { status, body: { message } } })
    renderAt('/forgot-password', <ForgotPasswordPage />)
    await askForLink()
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.queryByRole('heading', { name: 'Check your email' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send me a link' })).toBeEnabled()
  })
})

describe('choosing a new password', () => {
  it('names both fields and the show-password button', async () => {
    fakeServer({})
    renderAt('/reset-password?token=abc', <ResetPasswordPage />)
    // The page reads the token from the address bar through the router.
    const password = screen.getByLabelText('New password')
    expect(screen.getByLabelText('Type it again')).toHaveAttribute('type', 'password')
    await userEvent.click(screen.getByRole('button', { name: 'Show password' }))
    expect(password).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: 'Hide password' })).toBeInTheDocument()
  })
})

describe('the profile', () => {
  it('limits the fields and explains a refusal', async () => {
    signedIn(user('USER'))
    fakeServer({
      'GET /users/me': { data: user('USER') },
      'PUT /users/me': {
        status: 400,
        body: {
          message: 'Validation failed',
          errors: { avatarUrl: 'An avatar address has to start with http:// or https://' },
        },
      },
    })
    renderAt('/profile', <ProfilePage />)
    expect(screen.getByLabelText('Full name')).toHaveAttribute('maxlength', '100')
    expect(screen.getByLabelText('Avatar URL')).toHaveAttribute('maxlength', '500')
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('An avatar address has to start with http:// or https://'))
      .toBeInTheDocument()
  })
})

describe('signing in with only spaces', () => {
  it('names the empty field and sends nothing', async () => {
    const server = fakeServer({})
    renderAt('/login', <LoginPage />)
    await userEvent.type(screen.getByLabelText('Email or username'), '   ')
    await userEvent.type(screen.getByLabelText('Password'), '   ')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Enter your email or username.')
    expect(server.calls('POST /auth/login')).toHaveLength(0)
  })

  it('shows the server\'s field messages rather than "Validation failed"', async () => {
    fakeServer({
      'POST /auth/login': {
        status: 400,
        body: { message: 'Validation failed', errors: { password: 'Password is required' } },
      },
    })
    renderAt('/login', <LoginPage />)
    await userEvent.type(screen.getByLabelText('Email or username'), 'alice')
    await userEvent.type(screen.getByLabelText('Password'), 'secret')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Password is required')).toBeInTheDocument()
  })
})

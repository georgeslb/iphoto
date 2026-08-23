import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, fireEvent, screen, waitFor } from '@testing-library/react';
import Login from './Login';
import { localeWrapper } from '../test/testUtils.jsx';

const navigateMock = vi.fn();
vi.mock('react-router-dom', () => ({
  useNavigate: () => navigateMock,
}));

function jsonResponse(body, ok = true) {
  return Promise.resolve({ ok, json: () => Promise.resolve(body) });
}

function submitLoginForm() {
  fireEvent.change(screen.getByLabelText('Username:'), { target: { value: 'alice' } });
  fireEvent.change(screen.getByLabelText('Password:'), { target: { value: 'secret' } });
  fireEvent.click(screen.getByRole('button', { name: 'Log In' }));
}

describe('Login', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    navigateMock.mockClear();
  });

  // Regression: Login used to call navigate('/') directly on a successful POST, relying on
  // useAuth's checkAuthStatus re-running on every pathname change to notice the new session.
  // Once checkAuthStatus only runs on mount (see useAuth.js), that reliance broke login
  // entirely — isAuthenticated stayed false, and the redirect effect bounced straight back to
  // /login. Login must explicitly re-verify the session via the passed-down checkAuthStatus
  // before navigating.
  it('re-verifies the session via checkAuthStatus before navigating home on success', async () => {
    global.fetch = vi.fn(() => jsonResponse({}));
    const checkAuthStatus = vi.fn(() => Promise.resolve());

    render(<Login checkAuthStatus={checkAuthStatus} />, { wrapper: localeWrapper });
    submitLoginForm();

    await waitFor(() => expect(checkAuthStatus).toHaveBeenCalled());
    expect(navigateMock).toHaveBeenCalledWith('/');
    expect(global.fetch).toHaveBeenCalledWith('/api/login', expect.objectContaining({ method: 'POST' }));
  });

  it('shows an error and does not navigate when credentials are rejected', async () => {
    global.fetch = vi.fn(() => jsonResponse({}, false));
    const checkAuthStatus = vi.fn(() => Promise.resolve());

    render(<Login checkAuthStatus={checkAuthStatus} />, { wrapper: localeWrapper });
    submitLoginForm();

    await waitFor(() => expect(screen.getByText('Invalid username or password')).toBeTruthy());
    expect(checkAuthStatus).not.toHaveBeenCalled();
    expect(navigateMock).not.toHaveBeenCalled();
  });
});

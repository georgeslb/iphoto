import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useAuth } from './useAuth';

function jsonResponse(body, ok = true) {
  return Promise.resolve({ ok, json: () => Promise.resolve(body) });
}

describe('useAuth', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('redirects away from /login once authenticated', async () => {
    global.fetch = vi.fn(() => jsonResponse({ role: 'USER' }));
    const navigate = vi.fn();
    const location = { pathname: '/login' };

    const { result } = renderHook(() => useAuth(navigate, location));

    await waitFor(() => expect(result.current.loadingAuth).toBe(false));
    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.userRole).toBe('USER');
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('does not redirect away from /login when already there and not authenticated', async () => {
    global.fetch = vi.fn(() => jsonResponse({}, false));
    const navigate = vi.fn();
    const location = { pathname: '/login' };

    const { result } = renderHook(() => useAuth(navigate, location));

    await waitFor(() => expect(result.current.loadingAuth).toBe(false));
    expect(result.current.isAuthenticated).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('redirects to /login when not authenticated on a protected page', async () => {
    global.fetch = vi.fn(() => jsonResponse({}, false));
    const navigate = vi.fn();
    const location = { pathname: '/albums' };

    const { result } = renderHook(() => useAuth(navigate, location));

    await waitFor(() => expect(result.current.loadingAuth).toBe(false));
    expect(navigate).toHaveBeenCalledWith('/login');
  });

  // Share pages are meant to be reachable without a full session (see specification.md
  // §4.4) — checkAuthStatus must not bounce an anonymous share viewer to /login.
  it('does not redirect to /login when unauthenticated on a /share page', async () => {
    global.fetch = vi.fn(() => jsonResponse({}, false));
    const navigate = vi.fn();
    const location = { pathname: '/share/abc123' };

    const { result } = renderHook(() => useAuth(navigate, location));

    await waitFor(() => expect(result.current.loadingAuth).toBe(false));
    expect(navigate).not.toHaveBeenCalled();
  });

  it('treats a network error the same as an unauthenticated response', async () => {
    global.fetch = vi.fn(() => Promise.reject(new Error('network down')));
    const navigate = vi.fn();
    const location = { pathname: '/albums' };

    const { result } = renderHook(() => useAuth(navigate, location));

    await waitFor(() => expect(result.current.loadingAuth).toBe(false));
    expect(result.current.isAuthenticated).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/login');
  });

  it('logs out: clears auth state and navigates to /login', async () => {
    global.fetch = vi.fn(() => jsonResponse({ role: 'USER' }));
    const navigate = vi.fn();
    const location = { pathname: '/' };

    const { result } = renderHook(() => useAuth(navigate, location));
    await waitFor(() => expect(result.current.isAuthenticated).toBe(true));

    await result.current.handleLogout();

    expect(global.fetch).toHaveBeenCalledWith('/api/logout', { method: 'POST' });
    await waitFor(() => expect(result.current.isAuthenticated).toBe(false));
    expect(navigate).toHaveBeenCalledWith('/login');
  });
});

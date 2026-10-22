import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { initAuthInterceptor } from './authInterceptor';

describe('authInterceptor', () => {
  let originalFetch;

  beforeEach(() => {
    delete window.__iphoto_auth_interceptor_installed;
    originalFetch = window.fetch;
  });

  afterEach(() => {
    delete window.__iphoto_auth_interceptor_installed;
    window.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it('dispatches session-expired event when an API request returns 401', async () => {
    const mockFetch = vi.fn().mockResolvedValue({ status: 401 });
    window.fetch = mockFetch;

    initAuthInterceptor();

    const dispatchSpy = vi.spyOn(window, 'dispatchEvent');

    await window.fetch('/api/photos/123');

    expect(dispatchSpy).toHaveBeenCalled();
    const event = dispatchSpy.mock.calls[0][0];
    expect(event.type).toBe('session-expired');
  });

  it('does not dispatch session-expired for /api/login 401', async () => {
    const mockFetch = vi.fn().mockResolvedValue({ status: 401 });
    window.fetch = mockFetch;

    // Force re-init by overriding fetch directly
    initAuthInterceptor();

    const dispatchSpy = vi.spyOn(window, 'dispatchEvent');

    await window.fetch('/api/login', { method: 'POST' });

    expect(dispatchSpy).not.toHaveBeenCalled();
  });

  it('does not dispatch session-expired for /api/share/ 401', async () => {
    const mockFetch = vi.fn().mockResolvedValue({ status: 401 });
    window.fetch = mockFetch;

    initAuthInterceptor();

    const dispatchSpy = vi.spyOn(window, 'dispatchEvent');

    await window.fetch('/api/share/token123/login', { method: 'POST' });

    expect(dispatchSpy).not.toHaveBeenCalled();
  });

  it('does not dispatch session-expired for successful 200 response', async () => {
    const mockFetch = vi.fn().mockResolvedValue({ status: 200 });
    window.fetch = mockFetch;

    initAuthInterceptor();

    const dispatchSpy = vi.spyOn(window, 'dispatchEvent');

    await window.fetch('/api/photos');

    expect(dispatchSpy).not.toHaveBeenCalled();
  });
});

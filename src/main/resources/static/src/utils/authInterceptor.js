/**
 * Intercepts global fetch calls to detect 401 Unauthorized responses when user session ends.
 * Dispatches a 'session-expired' window event, excluding the login endpoint itself (bad credentials)
 * and guest shared-album access (incorrect album password).
 */
export function initAuthInterceptor() {
  if (typeof window === 'undefined') return;
  if (window.__iphoto_auth_interceptor_installed) return;
  window.__iphoto_auth_interceptor_installed = true;

  const baseFetch = window.fetch;
  window.fetch = async function (...args) {
    const response = await baseFetch.apply(this, args);
    if (response && response.status === 401) {
      const url = typeof args[0] === 'string' ? args[0] : (args[0]?.url || '');
      // Exclude login endpoint and public share password validation
      if (!url.includes('/api/login') && !url.includes('/api/share/')) {
        window.dispatchEvent(new CustomEvent('session-expired'));
      }
    }
    return response;
  };
}

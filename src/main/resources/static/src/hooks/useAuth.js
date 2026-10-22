import { useState, useEffect, useCallback } from 'react';

export function useAuth(navigate, location) {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [userRole, setUserRole] = useState(null);
  const [duplicationRegrouping, setDuplicationRegrouping] = useState(true);
  const [loadingAuth, setLoadingAuth] = useState(true);

  const checkAuthStatus = useCallback(async () => {
    try {
      const response = await fetch('/api/user/me');
      if (response.ok) {
        const userData = await response.json();
        setIsAuthenticated(true);
        setUserRole(userData.role);
        setDuplicationRegrouping(userData.duplicationRegrouping ?? true);
      } else {
        setIsAuthenticated(false);
        setUserRole(null);
        setDuplicationRegrouping(true);
      }
    } catch (error) {
      console.error('Error checking authentication status:', error);
      setIsAuthenticated(false);
      setUserRole(null);
      setDuplicationRegrouping(true);
    } finally {
      setLoadingAuth(false);
    }
  }, []);

  // Verify the session once on mount only. This used to re-run on every `location.pathname`
  // change (i.e. every client-side navigation), which meant any transient hiccup on
  // /api/user/me could flash the Login page mid-navigation — e.g. closing the Lightbox
  // navigates back to the grid, which re-triggered this fetch for no reason tied to auth.
  useEffect(() => {
    checkAuthStatus();
  }, [checkAuthStatus]);

  // Route-based redirect, decided from the already-known auth state — no network call here.
  useEffect(() => {
    if (loadingAuth) return;
    if (isAuthenticated && location.pathname === '/login') {
      navigate('/'); // Redirect from login page if already authenticated
    } else if (!isAuthenticated && location.pathname !== '/login' && !location.pathname.startsWith('/share')) {
      navigate('/login'); // Redirect to login if not authenticated and not on a share page
    }
  }, [isAuthenticated, loadingAuth, location.pathname, navigate]);

  // Session-expiration listener: when protected API calls receive 401, automatically forward to /login
  useEffect(() => {
    const handleSessionExpired = () => {
      setIsAuthenticated(false);
      setUserRole(null);
      if (location.pathname !== '/login' && !location.pathname.startsWith('/share')) {
        navigate('/login');
      }
    };

    window.addEventListener('session-expired', handleSessionExpired);
    return () => window.removeEventListener('session-expired', handleSessionExpired);
  }, [location.pathname, navigate]);

  const handleLogout = useCallback(async () => {
    try {
      await fetch('/api/logout', { method: 'POST' });
      setIsAuthenticated(false);
      setUserRole(null);
      navigate('/login');
    } catch (error) {
      console.error('Error logging out:', error);
    }
  }, [navigate]);

  return { isAuthenticated, userRole, duplicationRegrouping, setDuplicationRegrouping, loadingAuth, handleLogout, checkAuthStatus };
}

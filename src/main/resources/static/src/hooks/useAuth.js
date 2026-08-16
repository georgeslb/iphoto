import { useState, useEffect, useCallback } from 'react';

export function useAuth(navigate, location) {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [userRole, setUserRole] = useState(null);
  const [loadingAuth, setLoadingAuth] = useState(true);

  const checkAuthStatus = useCallback(async () => {
    try {
      const response = await fetch('/api/user/me');
      if (response.ok) {
        const userData = await response.json();
        setIsAuthenticated(true);
        setUserRole(userData.role);
        if (location.pathname === '/login') {
          navigate('/'); // Redirect from login page if already authenticated
        }
      } else {
        setIsAuthenticated(false);
        setUserRole(null);
        if (location.pathname !== '/login' && !location.pathname.startsWith('/share')) {
          navigate('/login'); // Redirect to login if not authenticated and not on a share page
        }
      }
    } catch (error) {
      console.error('Error checking authentication status:', error);
      setIsAuthenticated(false);
      setUserRole(null);
      if (location.pathname !== '/login' && !location.pathname.startsWith('/share')) {
        navigate('/login');
      }
    } finally {
      setLoadingAuth(false);
    }
  }, [navigate, location.pathname]);

  useEffect(() => {
    checkAuthStatus();
  }, [checkAuthStatus]);

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

  return { isAuthenticated, userRole, loadingAuth, handleLogout };
}

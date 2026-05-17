import React, { useState } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { Lock } from 'lucide-react';

export default function ShareLoginPage() {
  const { name } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { shareId } = location.state || {}; // Get shareId from state passed during redirection

  const [password, setPassword] = useState('');
  const [authError, setAuthError] = useState(null);
  const [loading, setLoading] = useState(false);

  if (!shareId) {
    // This should ideally not happen if ShareLinkPage redirects correctly
    return (
      <div className="content-body" style={{ textAlign: 'center', paddingTop: '40px', color: '#ff3b30' }}>
        Error: Share ID not provided for login.
      </div>
    );
  }

  const handlePasswordSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    setAuthError(null);

    try {
      const res = await fetch(`/api/share/${shareId}/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password }),
        credentials: 'include', // Important for session management
      });

      if (!res.ok) {
        if (res.status === 401) {
          setAuthError('Incorrect password.');
        } else {
          const errorData = await res.json();
          setAuthError(errorData.message || `Authentication failed: ${res.statusText}`);
        }
        return;
      }

      // Authentication successful, backend should have set session
      const data = await res.json(); // Expecting ShareResponse
      if (data.albumId) {
        navigate(`/albums/${data.albumId}`); // Redirect to album
      } else {
        setAuthError("Authentication successful, but album ID not received.");
      }

    } catch (err) {
      console.error("Error during share link login:", err);
      setAuthError("An unexpected error occurred during login.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2><Lock size={24} /> Protected Link: {name}</h2>
        <p>This link is password protected. Please enter the password to access.</p>
        {authError && <div className="error-message">{authError}</div>}
        <form onSubmit={handlePasswordSubmit}>
          <input
            type="password"
            placeholder="Enter password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="modal-input"
            required
            disabled={loading}
          />
          <div className="modal-actions">
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? 'Unlocking...' : 'Unlock'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

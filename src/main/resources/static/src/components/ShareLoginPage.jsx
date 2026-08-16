import React, { useState } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { Lock } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export default function ShareLoginPage() {
  const { messages } = useLocale(); // Use the useLocale hook
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
        {messages["errorPrefix"]}{messages["shareIdNotProvided"]}
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
          setAuthError(messages["incorrectPassword"]);
        } else {
          const errorData = await res.json();
          setAuthError(errorData.message || messages["authenticationFailed"](res.statusText));
        }
        return;
      }

      // Authentication successful, backend should have set session
      const data = await res.json(); // Expecting ShareResponse
      if (data.selectedAlbumIds) {
        navigate(`/albums/${data.selectedAlbumIds}`); // Redirect to album
      } else {
        setAuthError(messages["albumIdNotReceived"]);
      }

    } catch (err) {
      console.error("Error during share link login:", err);
      setAuthError(messages["shareLoginUnexpectedError"]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2><Lock size={24} /> {messages["protectedLink"](name)}</h2>
        <p>{messages["passwordProtectedMessage"]}</p>
        {authError && <div className="error-message">{authError}</div>}
        <form onSubmit={handlePasswordSubmit}>
          <input
            type="password"
            placeholder={messages["enterPasswordPlaceholder"]}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="modal-input"
            required
            disabled={loading}
          />
          <div className="modal-actions">
            <button type="submit" className="btn btn-primary" disabled={loading}>
              {loading ? messages["unlockingButton"] : messages["unlockButton"]}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
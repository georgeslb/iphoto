import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Lock } from 'lucide-react'; // Keep Lock icon for potential error display
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export default function ShareLinkPage() {
  const { messages } = useLocale(); // Use the useLocale hook
  const { name } = useParams();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const fetchShareLinkStatus = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/share/${name}`);
      const data = await res.json(); // Backend returns Map<String, Serializable>

      if (!res.ok) {
        // Handle specific error statuses from backend
        if (res.status === 404) {
          setError(messages["shareLinkNotFound"]);
        } else if (res.status === 410) { // Assuming 410 Gone for expired links
          setError(messages["shareLinkExpired"]);
        } else {
          setError(data.message || messages["failedToFetchShareLinkStatus"](res.statusText));
        }
        return;
      }

      if (data.protected === false && data.albumId) {
        // Not protected, redirect directly to album
        navigate(`/albums/${data.albumId}`);
      } else if (data.protected === true && data.shareId) {
        // Protected, redirect to login page for this share link
        navigate(`/share/${name}/login`, { state: { shareId: data.shareId } });
      } else {
        setError(messages["invalidShareLinkResponse"]);
      }
    } catch (err) {
      console.error("Error fetching share link status:", err);
      setError(messages["shareLinkUnexpectedError"]);
    } finally {
      setLoading(false);
    }
  }, [name, navigate, messages]);

  useEffect(() => {
    fetchShareLinkStatus();
  }, [fetchShareLinkStatus]);

  if (loading) {
    return <div className="content-body" style={{ textAlign: 'center', paddingTop: '40px' }}>{messages["loadingShareLink"]}</div>;
  }

  if (error) {
    return <div className="content-body" style={{ textAlign: 'center', paddingTop: '40px', color: '#ff3b30' }}>{messages["errorPrefix"]}{error}</div>;
  }

  // This component should ideally always redirect, so this fallback is mostly for unexpected states
  return <div className="content-body" style={{ textAlign: 'center', paddingTop: '40px', color: '#888' }}>{messages["processingShareLink"]}</div>;
}
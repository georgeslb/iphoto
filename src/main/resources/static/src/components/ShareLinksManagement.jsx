import React, { useState } from 'react';
import './ShareLinksManagement.css';
import { useLocale } from '../LocaleContext.jsx';
import { useMyShares } from '../hooks/useMyShares';

function ShareLinksManagement() {
  const { messages } = useLocale();
  const {
    shares,
    loading,
    error,
    message,
    handleDeleteShare,
    handleRemovePassword,
    handleExtendShare,
  } = useMyShares();

  const [extendingShare, setExtendingShare] = useState(null);
  const [extendDays, setExtendDays] = useState(30);
  const [extendingLoading, setExtendingLoading] = useState(false);
  const [extendError, setExtendError] = useState(null);

  const handleOpenExtendModal = (share) => {
    setExtendingShare(share);
    setExtendDays(30);
    setExtendError(null);
  };

  const handleCloseExtendModal = () => {
    setExtendingShare(null);
    setExtendError(null);
  };

  const handleSubmitExtend = async (e) => {
    if (e) e.preventDefault();
    if (!extendingShare || extendDays <= 0) return;
    setExtendingLoading(true);
    setExtendError(null);
    const success = await handleExtendShare(extendingShare.id, extendDays);
    setExtendingLoading(false);
    if (success) {
      setExtendingShare(null);
    }
  };

  return (
    <div className="share-links-container">
      <h2>{messages["manageShareLinks"]}</h2>
      <p className="share-links-description">{messages["manageShareLinksDescription"]}</p>

      {error && <p className="error-message">{error}</p>}
      {message && <p className="success-message">{message}</p>}

      {loading ? (
        <p>{messages["loadingShareLink"]}</p>
      ) : shares.length === 0 ? (
        <p>{messages["noSharesYet"]}</p>
      ) : (
        <table className="share-links-table">
          <colgroup>
            <col className="col-name" style={{ width: '20%' }} />
            <col className="col-link" style={{ width: '50%' }} />
            <col className="col-status" style={{ width: '10%' }} />
            <col className="col-actions" style={{ width: '20%' }} />
          </colgroup>
          <thead>
            <tr>
              <th className="col-name" style={{ width: '20%' }}>{messages["tableHeaderName"]}</th>
              <th className="col-link" style={{ width: '50%' }}>{messages["shareLinkLabel"]}</th>
              <th className="col-status" style={{ width: '10%' }}>{messages["tableHeaderStatus"]}</th>
              <th className="col-actions" style={{ width: '20%' }}>{messages["tableHeaderActions"]}</th>
            </tr>
          </thead>
          <tbody>
            {shares.map(share => {
              const url = `${window.location.origin}/share/${share.userMd5}/${share.name}`;
              return (
                <tr key={share.id}>
                  <td className="col-name">{share.name}</td>
                  <td className="col-link">
                    <a href={url} target="_blank" rel="noreferrer" className="share-link-url">{url}</a>
                  </td>
                  <td className="col-status">
                    {share.expired
                      ? messages["shareExpiredBadge"]
                      : share.isPasswordProtected
                        ? messages["passwordProtectedBadge"]
                        : messages["publicBadge"]}
                  </td>
                  <td className="col-actions">
                    {share.isPasswordProtected && (
                      <button className="remove-password-btn" onClick={() => handleRemovePassword(share.id)}>{messages["removePasswordButton"]}</button>
                    )}
                    <button className="extend-btn" onClick={() => handleOpenExtendModal(share)}>{messages["extendSharePeriodButton"]}</button>
                    <button className="delete-btn" onClick={() => handleDeleteShare(share.id)}>{messages["deleteButton"]}</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {extendingShare && (
        <div className="modal-overlay" onClick={handleCloseExtendModal}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <h2>{messages["extendShareModalTitle"](extendingShare.name)}</h2>
            {extendError && <div className="error-message">{extendError}</div>}
            <form onSubmit={handleSubmitExtend}>
              <label className="modal-label" htmlFor="extend-days-input">
                {messages["extendDaysLabel"]}
              </label>
              <input
                id="extend-days-input"
                type="number"
                min="1"
                className="modal-input"
                value={extendDays}
                onChange={(e) => setExtendDays(Math.max(1, parseInt(e.target.value, 10) || 1))}
                autoFocus
              />
              <div className="modal-actions" style={{ marginTop: '16px' }}>
                <button
                  type="button"
                  className="btn"
                  onClick={handleCloseExtendModal}
                  disabled={extendingLoading}
                >
                  {messages["cancel"]}
                </button>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={extendingLoading}
                >
                  {extendingLoading ? (messages["loading"] || "...") : messages["extendButton"]}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}

export default ShareLinksManagement;

import React from 'react';
import './UserManagement.css'; // Reused for its generic container/table styling
import { useLocale } from '../LocaleContext.jsx';
import { useMyShares } from '../hooks/useMyShares';

function ShareLinksManagement() {
  const { messages } = useLocale();
  const { shares, loading, error, message, handleDeleteShare, handleRemovePassword } = useMyShares();

  return (
    <div className="user-management-container">
      <h2>{messages["manageShareLinks"]}</h2>
      <p>{messages["manageShareLinksDescription"]}</p>

      {error && <p className="error-message">{error}</p>}
      {message && <p className="success-message">{message}</p>}

      {loading ? (
        <p>{messages["loadingShareLink"]}</p>
      ) : shares.length === 0 ? (
        <p>{messages["noSharesYet"]}</p>
      ) : (
        <table className="users-table">
          <thead>
            <tr>
              <th>{messages["tableHeaderName"]}</th>
              <th>{messages["shareLinkLabel"]}</th>
              <th>{messages["tableHeaderStatus"]}</th>
              <th>{messages["tableHeaderActions"]}</th>
            </tr>
          </thead>
          <tbody>
            {shares.map(share => {
              const url = `${window.location.origin}/share/${share.userMd5}/${share.name}`;
              return (
                <tr key={share.id}>
                  <td>{share.name}</td>
                  <td><a href={url} target="_blank" rel="noreferrer">{url}</a></td>
                  <td>
                    {share.expired
                      ? messages["shareExpiredBadge"]
                      : share.isPasswordProtected
                        ? messages["passwordProtectedBadge"]
                        : messages["publicBadge"]}
                  </td>
                  <td>
                    {share.isPasswordProtected && (
                      <button onClick={() => handleRemovePassword(share.id)}>{messages["removePasswordButton"]}</button>
                    )}
                    <button onClick={() => handleDeleteShare(share.id)}>{messages["deleteButton"]}</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}

export default ShareLinksManagement;

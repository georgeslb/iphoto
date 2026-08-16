import React, { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import { Image as ImageIcon, Library, Map as MapIcon, BookOpen, Copy, Trash2, Link as LinkIcon, Users, Settings, LayoutGrid, Menu, X } from 'lucide-react'; // Added LayoutGrid icon for Spaces
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export default function Sidebar({ pathname, isAuthenticated, userRole, handleLogout, fromGarbage }) {
  const { locale, setLocale, messages } = useLocale(); // Use the useLocale hook
  const [isOpen, setIsOpen] = useState(false);

  // Close the mobile drawer whenever the route changes
  useEffect(() => {
    setIsOpen(false);
  }, [pathname]);

  const handleLocaleChange = (e) => {
    setLocale(e.target.value);
  };

  const dropdownStyle = {
    backgroundColor: '#2c2c2e',
    color: 'white',
    border: '1px solid #444',
    borderRadius: '4px',
    padding: '5px 10px',
    fontSize: '14px',
    width: '100%',
    cursor: 'pointer'
  };

  const isGarbageActive = pathname === '/garbage' || (fromGarbage && pathname.startsWith('/spaces'));

  return (
    <>
      {isAuthenticated && (
        <button
          className="sidebar-toggle"
          onClick={() => setIsOpen(o => !o)}
          aria-label={messages["toggleMenu"]}
        >
          {isOpen ? <X size={22} /> : <Menu size={22} />}
        </button>
      )}
      {isOpen && <div className="sidebar-backdrop" onClick={() => setIsOpen(false)} />}
      <aside className={`sidebar ${isOpen ? 'open' : ''}`}>
      <div className="sidebar-logo"><ImageIcon size={28} color="#007AFF" /> <h2>{messages["iPhotoTitle"]}</h2></div>
      <nav className="nav-section">
        {isAuthenticated && (
          <>
            <div className="nav-item">
              <select value={locale} onChange={handleLocaleChange} style={dropdownStyle}>
                <option value="en">English</option>
                <option value="fr">Français</option>
              </select>
            </div>
            <Link to="/" className={`nav-item ${pathname === '/' ? 'active' : ''}`}><Library size={20} /> {messages["libraryLink"]}</Link>
            <Link to="/map" className={`nav-item ${pathname === '/map' ? 'active' : ''}`}><MapIcon size={20} /> {messages["mapLink"]}</Link>
            <Link to="/albums" className={`nav-item ${pathname.startsWith('/albums') ? 'active' : ''}`}><BookOpen size={20} /> {messages["albumsLink"]}</Link>
            <Link to="/spaces" className={`nav-item ${pathname.startsWith('/spaces') && !fromGarbage ? 'active' : ''}`}><LayoutGrid size={20} /> {messages["spacesLink"]}</Link> {/* New Spaces link */}
            <Link to="/persons" className={`nav-item ${pathname.startsWith('/persons') ? 'active' : ''}`}><Users size={20} /> {messages["personLink"]}</Link>
            <Link to="/duplicates" className={`nav-item ${pathname === '/duplicates' ? 'active' : ''}`}><Copy size={20} /> {messages["duplicatesLink"]}</Link>
            <Link to="/garbage" className={`nav-item ${isGarbageActive ? 'active' : ''}`}><Trash2 size={20} /> {messages["garbageLink"]}</Link>
            <Link to="/share-links" className={`nav-item ${pathname === '/share-links' ? 'active' : ''}`}><LinkIcon size={20} /> {messages["shareLinksLink"]}</Link>
            {userRole === 'ADMIN' && (
              <Link to="/admin/users" className={`nav-item ${pathname === '/admin/users' ? 'active' : ''}`}><Settings size={20} /> {messages["userManagementLink"]}</Link>
            )}
          </>
        )}
      </nav>
      {isAuthenticated && (
        <div className="sidebar-footer">
          <button onClick={handleLogout} className="nav-item logout-button">{messages["logoutButton"]}</button>
        </div>
      )}
      </aside>
    </>
  );
}
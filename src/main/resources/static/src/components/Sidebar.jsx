import React from 'react';
import { Link } from 'react-router-dom';
import { Image as ImageIcon, Library, Map as MapIcon, BookOpen, Copy, Trash2 } from 'lucide-react';

export default function Sidebar({ pathname }) {
  return (
    <aside className="sidebar">
      <div className="sidebar-logo"><ImageIcon size={28} color="#007AFF" /> <h2>iPhoto</h2></div>
      <nav className="nav-section">
        <Link to="/" className={`nav-item ${pathname === '/' ? 'active' : ''}`}><Library size={20} /> Library</Link>
        <Link to="/map" className={`nav-item ${pathname === '/map' ? 'active' : ''}`}><MapIcon size={20} /> Map</Link>
        <Link to="/albums" className={`nav-item ${pathname.startsWith('/albums') ? 'active' : ''}`}><BookOpen size={20} /> Albums</Link>
        <Link to="/duplicates" className={`nav-item ${pathname === '/duplicates' ? 'active' : ''}`}><Copy size={20} /> Duplicates</Link>
        <Link to="/garbage" className={`nav-item ${pathname === '/garbage' ? 'active' : ''}`}><Trash2 size={20} /> Garbage</Link>
      </nav>
    </aside>
  );
}

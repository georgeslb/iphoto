import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import Header from './Header';
import { LocaleProvider } from '../LocaleContext.jsx';

function renderHeader(props = {}) {
  const defaultProps = {
    selectedIds: [],
    setSelectedIds: vi.fn(),
    selectedSpaceIds: [],
    selectedAlbumIds: [],
    setSelectedSpaceIds: vi.fn(),
    setSelectedAlbumIds: vi.fn(),
    menuSpaceId: null,
    menuAlbumId: null,
    pathname: '/',
    handleRestore: vi.fn(),
    handlePermanentDelete: vi.fn(),
    handleRestoreSpaces: vi.fn(),
    handlePermanentDeleteSpaces: vi.fn(),
    handleRemovePhotosFromAlbum: vi.fn(),
    handleMoveToGarbage: vi.fn(),
    setShowAddToAlbumModal: vi.fn(),
    setShowCreateShareLinkModal: vi.fn(),
    handleUpload: vi.fn(),
    handleSetCoverPhoto: vi.fn(),
    navigate: vi.fn(),
    duplicatesPage: 0,
    fetchDuplicates: vi.fn(),
    isLastDuplicateGroup: false,
    handleDeleteAlbums: vi.fn(),
    handleDeleteSpaces: vi.fn(),
    setShowCreateAlbumModal: vi.fn(),
    setShowCreateSpaceModal: vi.fn(),
    fileInputRef: { current: null },
    photoSelection: { menuSelection: {} },
    refreshAndMaintainScroll: vi.fn(),
    fromGarbage: false,
    setShowEditDateModal: vi.fn(),
    handleDownload: vi.fn(),
    mediaTypeFilter: 'all',
    setMediaTypeFilter: vi.fn(),
    duplicationRegrouping: true,
    onToggleDuplicationRegrouping: vi.fn(),
    ...props,
  };

  return {
    ...render(
      <LocaleProvider>
        <Header {...defaultProps} />
      </LocaleProvider>
    ),
    props: defaultProps,
  };
}

describe('Header duplication regrouping toggle', () => {
  it('renders single slash icon in library view when regrouping is enabled to allow deactivating', () => {
    const onToggle = vi.fn();
    renderHeader({ pathname: '/', duplicationRegrouping: true, onToggleDuplicationRegrouping: onToggle });

    const disableBtn = screen.getByTitle('Disable duplicate regrouping');
    expect(disableBtn).toBeTruthy();
    expect(screen.queryByTitle('Enable duplicate regrouping')).toBeNull();

    fireEvent.click(disableBtn);
    expect(onToggle).toHaveBeenCalledWith(false);
  });

  it('renders single non-slash icon in library view when regrouping is disabled to allow activating', () => {
    const onToggle = vi.fn();
    renderHeader({ pathname: '/', duplicationRegrouping: false, onToggleDuplicationRegrouping: onToggle });

    const enableBtn = screen.getByTitle('Enable duplicate regrouping');
    expect(enableBtn).toBeTruthy();
    expect(screen.queryByTitle('Disable duplicate regrouping')).toBeNull();

    fireEvent.click(enableBtn);
    expect(onToggle).toHaveBeenCalledWith(true);
  });

  it('does not render duplication regrouping toggle when in selection mode', () => {
    renderHeader({ pathname: '/', selectedIds: ['photo-1'] });

    expect(screen.queryByTitle('Disable duplicate regrouping')).toBeNull();
    expect(screen.queryByTitle('Enable duplicate regrouping')).toBeNull();
  });

  it('does not render duplication regrouping toggle on non-library routes', () => {
    renderHeader({ pathname: '/albums/album-123', menuAlbumId: 'album-123' });

    expect(screen.queryByTitle('Disable duplicate regrouping')).toBeNull();
    expect(screen.queryByTitle('Enable duplicate regrouping')).toBeNull();
  });
});

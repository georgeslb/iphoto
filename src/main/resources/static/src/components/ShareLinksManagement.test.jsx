import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ShareLinksManagement from './ShareLinksManagement';
import { LocaleProvider } from '../LocaleContext.jsx';

function jsonResponse(body, ok = true) {
  return Promise.resolve({ ok, json: () => Promise.resolve(body) });
}

describe('ShareLinksManagement', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders share links sorted by createdAt desc with relative column widths and styled links', async () => {
    const mockShares = [
      {
        id: 's1',
        name: 'older-share',
        albumId: 'a1',
        isPasswordProtected: false,
        expired: false,
        userMd5: 'md5user',
        createdAt: '2024-01-01T10:00:00',
      },
      {
        id: 's2',
        name: 'newer-share',
        albumId: 'a2',
        isPasswordProtected: true,
        expired: false,
        userMd5: 'md5user',
        createdAt: '2024-05-15T12:00:00',
      },
    ];

    global.fetch = vi.fn((url) => {
      if (url === '/api/share/my-shares') {
        return jsonResponse(mockShares);
      }
      return jsonResponse({});
    });

    render(
      <LocaleProvider>
        <ShareLinksManagement />
      </LocaleProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('newer-share')).toBeInTheDocument();
      expect(screen.getByText('older-share')).toBeInTheDocument();
    });

    // Verify sorting: newer-share should appear before older-share
    const rows = screen.getAllByRole('row');
    // Row 0 is the header, row 1 should be newer-share, row 2 older-share
    expect(rows[1]).toHaveTextContent('newer-share');
    expect(rows[2]).toHaveTextContent('older-share');

    // Verify relative column widths
    const cols = document.querySelectorAll('.share-links-table col');
    expect(cols).toHaveLength(4);
    expect(cols[0].style.width).toBe('20%');
    expect(cols[1].style.width).toBe('50%');
    expect(cols[2].style.width).toBe('10%');
    expect(cols[3].style.width).toBe('20%');

    // Verify links have share-link-url class
    const link = screen.getByRole('link', { name: /newer-share/ });
    expect(link).toHaveClass('share-link-url');
  });

  it('allows removing password and deleting a share', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);

    const mockShares = [
      {
        id: 's1',
        name: 'protected-share',
        albumId: 'a1',
        isPasswordProtected: true,
        expired: false,
        userMd5: 'md5user',
        createdAt: '2024-01-01T10:00:00',
      },
    ];

    global.fetch = vi.fn((url, options) => {
      if (url === '/api/share/my-shares') {
        return jsonResponse(mockShares);
      }
      if (url === '/api/share/s1' && options?.method === 'PUT') {
        return jsonResponse({ id: 's1', isPasswordProtected: false });
      }
      if (url === '/api/share/s1' && options?.method === 'DELETE') {
        return jsonResponse({ message: 'deleted' });
      }
      return jsonResponse({});
    });

    render(
      <LocaleProvider>
        <ShareLinksManagement />
      </LocaleProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('protected-share')).toBeInTheDocument();
    });

    const removePwdBtn = screen.getByRole('button', { name: 'Remove Password' });
    fireEvent.click(removePwdBtn);

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith('/api/share/s1', expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ password: null }),
      }));
    });

    const deleteBtn = screen.getByRole('button', { name: 'Delete' });
    fireEvent.click(deleteBtn);

    await waitFor(() => {
      expect(confirmSpy).toHaveBeenCalled();
      expect(global.fetch).toHaveBeenCalledWith('/api/share/s1', expect.objectContaining({
        method: 'DELETE',
      }));
    });
  });

  it('allows extending the share period via modal with default 30 days', async () => {
    const mockShares = [
      {
        id: 's1',
        name: 'vacation-share',
        albumId: 'a1',
        isPasswordProtected: false,
        expired: true,
        userMd5: 'md5user',
        createdAt: '2024-01-01T10:00:00',
      },
    ];

    global.fetch = vi.fn((url, options) => {
      if (url === '/api/share/my-shares') {
        return jsonResponse(mockShares);
      }
      if (url === '/api/share/s1/extend' && options?.method === 'POST') {
        return jsonResponse({
          id: 's1',
          name: 'vacation-share',
          expired: false,
          availableUntil: '2026-11-21T15:00:00',
        });
      }
      return jsonResponse({});
    });

    render(
      <LocaleProvider>
        <ShareLinksManagement />
      </LocaleProvider>
    );

    await waitFor(() => {
      expect(screen.getByText('vacation-share')).toBeInTheDocument();
    });

    const extendBtn = screen.getByRole('button', { name: 'Extend Period' });
    fireEvent.click(extendBtn);

    // Modal appears with default 30 days
    const input = screen.getByLabelText(/Days to extend/i);
    expect(input).toBeInTheDocument();
    expect(input.value).toBe('30');

    // Submit modal
    const submitBtn = screen.getByRole('button', { name: 'Extend' });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith('/api/share/s1/extend', expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ days: 30 }),
      }));
    });

    // Modal closes and success message is displayed
    await waitFor(() => {
      expect(screen.queryByLabelText(/Days to extend/i)).not.toBeInTheDocument();
      expect(screen.getByText(/Share period extended successfully/i)).toBeInTheDocument();
    });
  });
});

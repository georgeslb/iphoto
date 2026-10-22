import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, fireEvent, screen, waitFor } from '@testing-library/react';
import UserManagement from './UserManagement';
import { localeWrapper } from '../test/testUtils.jsx';

function jsonResponse(body, ok = true) {
  return Promise.resolve({ ok, json: () => Promise.resolve(body) });
}

describe('UserManagement', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders existing users list and handles reset password', async () => {
    const mockUsers = [
      { id: 'u1', username: 'alice', role: 'USER' },
      { id: 'u2', username: 'bob', role: 'USER' },
    ];

    global.fetch = vi.fn((url, options) => {
      if (url === '/api/users' && (!options || options.method === undefined)) {
        return jsonResponse(mockUsers);
      }
      if (url === '/api/admin/heavyTasks') {
        return jsonResponse({ activate: false });
      }
      if (url === '/api/users/u1/reset-password' && options?.method === 'POST') {
        return jsonResponse({ tempPassword: 'new-temp-pass' });
      }
      return jsonResponse({});
    });

    render(<UserManagement />, { wrapper: localeWrapper });

    await waitFor(() => {
      expect(screen.getByText('alice')).toBeInTheDocument();
      expect(screen.getByText('bob')).toBeInTheDocument();
    });

    // Checkbox for duplication regrouping should not exist in the table
    expect(screen.queryByText('Duplication Regrouping')).toBeNull();
    expect(screen.queryByRole('checkbox', { name: 'Duplication regrouping in Library' })).toBeNull();
  });

  it('creates a new user with default role and no regrouping checkbox in form', async () => {
    global.fetch = vi.fn((url, options) => {
      if (url === '/api/users' && (!options || options.method === undefined)) {
        return jsonResponse([]);
      }
      if (url === '/api/admin/heavyTasks') {
        return jsonResponse({ activate: false });
      }
      if (url === '/api/users' && options?.method === 'POST') {
        return jsonResponse({ id: 'u3', username: 'charlie', role: 'USER', duplicationRegrouping: true });
      }
      return jsonResponse({});
    });

    render(<UserManagement />, { wrapper: localeWrapper });

    fireEvent.change(screen.getByLabelText('Username:'), { target: { value: 'charlie' } });
    fireEvent.change(screen.getByLabelText('Password:'), { target: { value: 'pass123' } });

    // Verify there is no create form checkbox for duplication regrouping
    expect(screen.queryByLabelText('Duplication regrouping in Library')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Create New User' }));

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith('/api/users', expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: 'charlie',
          password: 'pass123',
          role: 'USER',
        }),
      }));
    });
  });
});

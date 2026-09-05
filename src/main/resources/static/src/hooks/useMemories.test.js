import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useMemories } from './useMemories';

describe('useMemories', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('fetches /api/photos/memories on mount', async () => {
    const payload = [{ monthsAgo: 12, day: '2024-03-15', photos: [{ id: 'p1', url: '/x' }] }];
    global.fetch = vi.fn(() => Promise.resolve({ json: () => Promise.resolve(payload) }));

    const { result } = renderHook(() => useMemories());

    expect(global.fetch).toHaveBeenCalledWith('/api/photos/memories');
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.memories).toEqual(payload);
  });

  it('falls back to an empty list on a fetch error', async () => {
    global.fetch = vi.fn(() => Promise.reject(new Error('network error')));

    const { result } = renderHook(() => useMemories());

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.memories).toEqual([]);
  });
});

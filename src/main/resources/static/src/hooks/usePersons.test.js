import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { usePersons } from './usePersons';
import { localeWrapper } from '../test/testUtils.jsx';

function pageResponse(content, { number = 0, totalPages = 1 } = {}) {
  return Promise.resolve({
    json: () => Promise.resolve({ content, page: { size: content.length, number, totalElements: content.length, totalPages } }),
  });
}

describe('usePersons', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('fetches page 0 on mount and exposes hasMore from the page envelope', async () => {
    global.fetch = vi.fn(() => pageResponse([{ id: 'p1' }], { number: 0, totalPages: 2 }));

    const { result } = renderHook(() => usePersons(), { wrapper: localeWrapper });

    await waitFor(() => expect(result.current.persons).toHaveLength(1));
    expect(result.current.hasMore).toBe(true);
    expect(global.fetch).toHaveBeenCalledWith('/api/faces/persons?page=0&size=50');
  });

  it('loadMore appends the next page instead of replacing the list', async () => {
    global.fetch = vi.fn()
      .mockReturnValueOnce(pageResponse([{ id: 'p1' }], { number: 0, totalPages: 2 }))
      .mockReturnValueOnce(pageResponse([{ id: 'p2' }], { number: 1, totalPages: 2 }));

    const { result } = renderHook(() => usePersons(), { wrapper: localeWrapper });
    await waitFor(() => expect(result.current.persons).toHaveLength(1));

    await act(async () => {
      await result.current.loadMore();
    });

    expect(result.current.persons.map(p => p.id)).toEqual(['p1', 'p2']);
    expect(global.fetch).toHaveBeenLastCalledWith('/api/faces/persons?page=1&size=50');
    expect(result.current.hasMore).toBe(false);
  });

  it('loadMore is a no-op once hasMore is false', async () => {
    global.fetch = vi.fn(() => pageResponse([{ id: 'p1' }], { number: 0, totalPages: 1 }));

    const { result } = renderHook(() => usePersons(), { wrapper: localeWrapper });
    await waitFor(() => expect(result.current.hasMore).toBe(false));

    const callCountBefore = global.fetch.mock.calls.length;
    await act(async () => {
      await result.current.loadMore();
    });

    expect(global.fetch.mock.calls.length).toBe(callCountBefore);
  });

  it('fetchPersons resets back to page 0 and replaces the list', async () => {
    global.fetch = vi.fn()
      .mockReturnValueOnce(pageResponse([{ id: 'p1' }], { number: 0, totalPages: 2 }))
      .mockReturnValueOnce(pageResponse([{ id: 'p2' }], { number: 1, totalPages: 2 }))
      .mockReturnValueOnce(pageResponse([{ id: 'p1-renamed' }], { number: 0, totalPages: 1 }));

    const { result } = renderHook(() => usePersons(), { wrapper: localeWrapper });
    await waitFor(() => expect(result.current.persons).toHaveLength(1));

    await act(async () => {
      await result.current.loadMore();
    });
    expect(result.current.persons).toHaveLength(2);

    await act(async () => {
      await result.current.fetchPersons();
    });

    expect(result.current.persons.map(p => p.id)).toEqual(['p1-renamed']);
    expect(result.current.hasMore).toBe(false);
  });
});

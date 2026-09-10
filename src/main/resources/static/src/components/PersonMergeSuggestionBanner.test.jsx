import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, fireEvent, screen, waitFor } from '@testing-library/react';
import PersonMergeSuggestionBanner from './PersonMergeSuggestionBanner';
import { localeWrapper } from '../test/testUtils.jsx';

function person(id, name, photoCount = 3) {
  return { id, name, coverPhoto: null, photoCount };
}

function suggestion(id, personA, personB, similarity = 0.82) {
  return { id, personA, personB, similarity };
}

function jsonResponse(body, ok = true) {
  return Promise.resolve({ ok, json: () => Promise.resolve(body) });
}

// Two-endpoint flow: GET .../merge-suggestions/count (cheap, just a number) backs the banner
// text, and GET .../merge-suggestions?page=N&size=1 (one suggestion, with its face thumbnails)
// backs the popup - fetched only once it's actually opened, and again for each Next/after a
// Merge. See usePersonMergeSuggestions.js for why: the popup only ever shows one pair at a time,
// so the old one-flat-list-of-everything endpoint paid for every OTHER pair's face images too.
function mockFetch({ count, pages }) {
  return vi.fn((url) => {
    if (url === '/api/faces/persons/merge-suggestions/count') {
      return jsonResponse({ count: typeof count === 'function' ? count() : count });
    }
    const match = /\/api\/faces\/persons\/merge-suggestions\?page=(\d+)&size=1/.exec(url);
    if (match) {
      const page = Number(match[1]);
      const item = pages[page];
      return jsonResponse({ content: item ? [item] : [] });
    }
    if (url === '/api/faces/persons/merge') return jsonResponse({});
    throw new Error(`unexpected fetch ${url}`);
  });
}

describe('PersonMergeSuggestionBanner', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders nothing when the count is zero', async () => {
    global.fetch = vi.fn(() => jsonResponse({ count: 0 }));
    const { container } = render(<PersonMergeSuggestionBanner />, { wrapper: localeWrapper });

    await waitFor(() => expect(global.fetch).toHaveBeenCalledWith('/api/faces/persons/merge-suggestions/count'));
    expect(container.firstChild).toBeNull();
  });

  it('shows the banner from the count alone, then fetches the first suggestion on Review', async () => {
    const s1 = suggestion('s1', person('a1', 'Alice'), person('a2', null));
    global.fetch = mockFetch({ count: 1, pages: [s1] });

    render(<PersonMergeSuggestionBanner />, { wrapper: localeWrapper });

    await screen.findByText('We found 2 people who might be the same person.');
    // Only the cheap count call so far - no suggestion (and no face thumbnails) fetched yet.
    expect(global.fetch).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: 'Review' }));

    await screen.findByText('Are these the same person?');
    expect(screen.getByText('Alice')).toBeTruthy();
    expect(screen.getByText('82% similar')).toBeTruthy();
    expect(global.fetch).toHaveBeenCalledWith('/api/faces/persons/merge-suggestions?page=0&size=1');
  });

  it('Next fetches the next page without merging', async () => {
    const s1 = suggestion('s1', person('a1', 'Alice'), person('a2', null));
    const s2 = suggestion('s2', person('b1', 'Bob'), person('b2', null));
    global.fetch = mockFetch({ count: 2, pages: [s1, s2] });

    render(<PersonMergeSuggestionBanner />, { wrapper: localeWrapper });
    await screen.findByText('We found 2 pairs of people who might be the same person.');
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    await screen.findByText('Alice');

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    await screen.findByText('Bob');
    expect(global.fetch).toHaveBeenCalledWith('/api/faces/persons/merge-suggestions?page=1&size=1');
    // Merge/dismiss were never called for this to just be "next".
    expect(global.fetch).not.toHaveBeenCalledWith('/api/faces/persons/merge', expect.anything());
  });

  it('Close dismisses the popup without merging or fetching anything else', async () => {
    const s1 = suggestion('s1', person('a1', 'Alice'), person('a2', null));
    global.fetch = mockFetch({ count: 1, pages: [s1] });

    render(<PersonMergeSuggestionBanner />, { wrapper: localeWrapper });
    await screen.findByText('We found 2 people who might be the same person.');
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    await screen.findByText('Are these the same person?');

    const callsBeforeClose = global.fetch.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));

    expect(screen.queryByText('Are these the same person?')).toBeNull();
    // The banner itself is still there - closing the popup isn't a dismissal of the suggestion.
    expect(screen.getByText('We found 2 people who might be the same person.')).toBeTruthy();
    expect(global.fetch.mock.calls.length).toBe(callsBeforeClose);
  });

  it('Merge calls the existing two-person merge endpoint and re-fetches the count/suggestion', async () => {
    const s1 = suggestion('s1', person('a1', 'Alice'), person('a2', null));
    let currentCount = 1;
    global.fetch = mockFetch({ count: () => currentCount, pages: [s1] });

    render(<PersonMergeSuggestionBanner />, { wrapper: localeWrapper });
    await screen.findByText('We found 2 people who might be the same person.');
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    await screen.findByText('Are these the same person?');

    currentCount = 0; // simulates the suggestion being gone server-side after the merge
    fireEvent.click(screen.getByRole('button', { name: 'Merge' }));

    await waitFor(() => expect(global.fetch).toHaveBeenCalledWith('/api/faces/persons/merge', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ personIds: ['a1', 'a2'] }),
    })));
    // Nothing left to suggest - the whole banner/popup disappears.
    await waitFor(() => expect(screen.queryByText('Are these the same person?')).toBeNull());
    await waitFor(() => expect(screen.queryByText(/We found/)).toBeNull());
  });

  it('alerts and keeps the popup open when the merge request fails', async () => {
    const alertMock = vi.spyOn(window, 'alert').mockImplementation(() => {});
    const s1 = suggestion('s1', person('a1', 'Alice'), person('a2', null));
    global.fetch = vi.fn((url) => {
      if (url === '/api/faces/persons/merge-suggestions/count') return jsonResponse({ count: 1 });
      if (url === '/api/faces/persons/merge-suggestions?page=0&size=1') return jsonResponse({ content: [s1] });
      if (url === '/api/faces/persons/merge') return jsonResponse({}, false);
      throw new Error(`unexpected fetch ${url}`);
    });

    render(<PersonMergeSuggestionBanner />, { wrapper: localeWrapper });
    await screen.findByText('We found 2 people who might be the same person.');
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    await screen.findByText('Are these the same person?');
    fireEvent.click(screen.getByRole('button', { name: 'Merge' }));

    await waitFor(() => expect(alertMock).toHaveBeenCalledWith('Failed to merge these people.'));
    expect(screen.getByText('Are these the same person?')).toBeTruthy();
  });
});

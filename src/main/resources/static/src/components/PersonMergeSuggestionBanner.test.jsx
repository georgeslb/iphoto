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

describe('PersonMergeSuggestionBanner', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders nothing when there are no suggestions', async () => {
    global.fetch = vi.fn(() => jsonResponse([]));
    const { container } = render(<PersonMergeSuggestionBanner />, { wrapper: localeWrapper });

    await waitFor(() => expect(global.fetch).toHaveBeenCalledWith('/api/faces/persons/merge-suggestions'));
    expect(container.firstChild).toBeNull();
  });

  it('shows the banner and opens a popup with the first suggestion on Review', async () => {
    const suggestions = [
      suggestion('s1', person('a1', 'Alice'), person('a2', null)),
    ];
    global.fetch = vi.fn(() => jsonResponse(suggestions));

    render(<PersonMergeSuggestionBanner />, { wrapper: localeWrapper });

    await screen.findByText('We found 2 people who might be the same person.');
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));

    expect(screen.getByText('Are these the same person?')).toBeTruthy();
    expect(screen.getByText('Alice')).toBeTruthy();
    expect(screen.getByText('82% similar')).toBeTruthy();
  });

  it('Next advances to the next suggestion without merging', async () => {
    const suggestions = [
      suggestion('s1', person('a1', 'Alice'), person('a2', null)),
      suggestion('s2', person('b1', 'Bob'), person('b2', null)),
    ];
    global.fetch = vi.fn(() => jsonResponse(suggestions));

    render(<PersonMergeSuggestionBanner />, { wrapper: localeWrapper });
    await screen.findByText('We found 2 pairs of people who might be the same person.');
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));

    expect(screen.getByText('Alice')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Bob')).toBeTruthy();
    // Merge/dismiss were never called for this to just be "next".
    expect(global.fetch).toHaveBeenCalledTimes(1);
  });

  it('Close dismisses the popup without merging or fetching anything else', async () => {
    const suggestions = [suggestion('s1', person('a1', 'Alice'), person('a2', null))];
    global.fetch = vi.fn(() => jsonResponse(suggestions));

    render(<PersonMergeSuggestionBanner />, { wrapper: localeWrapper });
    await screen.findByText('We found 2 people who might be the same person.');
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    expect(screen.getByText('Are these the same person?')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByText('Are these the same person?')).toBeNull();
    // The banner itself is still there - closing the popup isn't a dismissal of the suggestion.
    expect(screen.getByText('We found 2 people who might be the same person.')).toBeTruthy();
    expect(global.fetch).toHaveBeenCalledTimes(1);
  });

  it('Merge calls the existing two-person merge endpoint and removes the suggestion', async () => {
    const suggestions = [suggestion('s1', person('a1', 'Alice'), person('a2', null))];
    global.fetch = vi.fn((url) => {
      if (url === '/api/faces/persons/merge-suggestions') return jsonResponse(suggestions);
      if (url === '/api/faces/persons/merge') return jsonResponse({});
      throw new Error(`unexpected fetch ${url}`);
    });

    render(<PersonMergeSuggestionBanner />, { wrapper: localeWrapper });
    await screen.findByText('We found 2 people who might be the same person.');
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    fireEvent.click(screen.getByRole('button', { name: 'Merge' }));

    await waitFor(() => expect(global.fetch).toHaveBeenCalledWith('/api/faces/persons/merge', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ personIds: ['a1', 'a2'] }),
    })));
    // Nothing left to suggest - the whole banner/popup disappears.
    await waitFor(() => expect(screen.queryByText('Are these the same person?')).toBeNull());
    expect(screen.queryByText(/We found/)).toBeNull();
  });

  it('alerts and keeps the popup open when the merge request fails', async () => {
    const alertMock = vi.spyOn(window, 'alert').mockImplementation(() => {});
    const suggestions = [suggestion('s1', person('a1', 'Alice'), person('a2', null))];
    global.fetch = vi.fn((url) => {
      if (url === '/api/faces/persons/merge-suggestions') return jsonResponse(suggestions);
      if (url === '/api/faces/persons/merge') return jsonResponse({}, false);
      throw new Error(`unexpected fetch ${url}`);
    });

    render(<PersonMergeSuggestionBanner />, { wrapper: localeWrapper });
    await screen.findByText('We found 2 people who might be the same person.');
    fireEvent.click(screen.getByRole('button', { name: 'Review' }));
    fireEvent.click(screen.getByRole('button', { name: 'Merge' }));

    await waitFor(() => expect(alertMock).toHaveBeenCalledWith('Failed to merge these people.'));
    expect(screen.getByText('Are these the same person?')).toBeTruthy();
  });
});

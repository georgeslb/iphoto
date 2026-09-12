import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import App from './App';
import { LocaleProvider } from './LocaleContext.jsx';

// Root smoke test - the one thing this app has never had (see TODO.md's "App.jsx selection
// state" item). Its whole purpose is to guard the selection-state WIRING App.jsx does itself
// (selectedIds/selectedAlbumIds/selectedSpaceIds state -> useMenuSelection -> Header props ->
// button/text render, and the path-change effect that resets them) before that state gets
// consolidated into one object - none of the existing hook/component tests
// (selectionCapabilities.test.js, useMenuSelection.test.js, useAlbumManagement.test.jsx,
// PhotoGrid.test.jsx) render App itself, so none of them would catch a wiring mistake introduced
// by the refactor. Deliberately narrow in scope (Library <-> Albums only), not a full click-every-
// route suite - see the TODO item for the reasoning.

// Every endpoint App.jsx's own mount-time hooks can call while sitting on the Library route
// (useAuth, useDayIndexedPhotos, PersonMergeSuggestionBanner/usePersonMergeSuggestions,
// MemoriesBar/useMemories), plus /api/albums for when a test navigates to /albums
// (useAlbumManagement only fetches it once effectivePath === '/albums'). useSpaceManagement's
// spaces/garbage fetches are gated on selectedMenu === 'spaces'/'garbage', so they never fire in
// this test and don't need mocking.
const DAY = '2024-01-01';

function mockFetchRouter(url) {
  if (url === '/api/user/me') {
    return { ok: true, json: async () => ({ role: 'USER' }) };
  }
  if (url === '/api/photos/days') {
    return { ok: true, json: async () => ([{ day: DAY, count: 1 }]) };
  }
  if (url.startsWith(`/api/photos/day/${DAY}`)) {
    return { ok: true, json: async () => ({ content: [{ id: 'photo-1', url: '/photo1.jpg' }], last: true }) };
  }
  if (url === '/api/faces/persons/merge-suggestions/count') {
    return { ok: true, json: async () => ({ count: 0 }) };
  }
  if (url === '/api/photos/memories') {
    return { ok: true, json: async () => ([]) };
  }
  if (url === '/api/albums') {
    return { ok: true, json: async () => ([]) };
  }
  // Safe fallback for anything not explicitly listed above, so an unanticipated call doesn't
  // crash the render with an unhandled rejection - this test doesn't assert on those endpoints.
  return { ok: true, json: async () => ([]) };
}

// jsdom's IntersectionObserver stub (test/setup.js) never actually fires its callback, so
// PhotoGrid.jsx's day-sentinel observer would never "intersect" and the one photo in the mocked
// day would never load. Captures the latest constructed observer's callback so the test can fire
// it manually once the sentinel exists in the DOM - same technique as PhotoGrid.test.jsx's own
// look-ahead-prefetch tests.
let latestObserverCallback = null;
class CapturingObserver {
  constructor(callback) { latestObserverCallback = callback; }
  observe() {}
  unobserve() {}
  disconnect() {}
}

describe('App smoke test', () => {
  let originalIntersectionObserver;

  beforeEach(() => {
    originalIntersectionObserver = global.IntersectionObserver;
    global.IntersectionObserver = CapturingObserver;
    global.fetch = vi.fn((url) => Promise.resolve(mockFetchRouter(url)));
    // jsdom has no real layout engine - scrollIntoView is unimplemented and throws otherwise
    // (PhotoGrid.jsx's TimelineScrubber/seekToDay paths call it, not exercised here, but Element
    // .prototype needs the stub present regardless since some render path may reference it).
    Element.prototype.scrollIntoView = vi.fn();
  });

  afterEach(() => {
    global.IntersectionObserver = originalIntersectionObserver;
    vi.restoreAllMocks();
  });

  function renderApp(initialPath = '/') {
    return render(
      <MemoryRouter initialEntries={[initialPath]}>
        <LocaleProvider>
          <App />
        </LocaleProvider>
      </MemoryRouter>
    );
  }

  // Loads the one mocked day's one photo by manually firing the captured IntersectionObserver
  // callback against that day's sentinel - see CapturingObserver's own comment for why this is
  // necessary under jsdom.
  async function loadLibraryDay() {
    await waitFor(() => {
      expect(document.querySelector(`[data-day-sentinel="${DAY}"]`)).toBeTruthy();
    });
    await act(async () => {
      const sentinel = document.querySelector(`[data-day-sentinel="${DAY}"]`);
      latestObserverCallback([{ isIntersecting: true, target: sentinel }]);
    });
    await waitFor(() => {
      expect(document.querySelector('.photo-card')).toBeTruthy();
    });
  }

  it('selecting a photo in the Library grid shows the count and a bulk action in the Header', async () => {
    renderApp('/');
    await loadLibraryDay();

    fireEvent.click(document.querySelector('.photo-card .select-overlay'));

    // "N selected" text (Header.jsx) proves selectedIds actually reached Header as a prop.
    expect(await screen.findByText('1 selected')).toBeTruthy();
    // A Main Space Library photo can always be moved to garbage - proves photoSelection
    // (derived from that same selectedIds via useMenuSelection) reached Header too, not just the
    // raw count.
    expect(document.querySelector('.header [title="Move to Garbage"]')).toBeTruthy();
  });

  it('navigating from Library to Albums clears the selection (App.jsx\'s path-change effect)', async () => {
    renderApp('/');
    await loadLibraryDay();

    fireEvent.click(document.querySelector('.photo-card .select-overlay'));
    expect(await screen.findByText('1 selected')).toBeTruthy();

    fireEvent.click(document.querySelector('a[href="/albums"]'));

    await waitFor(() => {
      expect(screen.queryByText('1 selected')).toBeNull();
    });
  });
});

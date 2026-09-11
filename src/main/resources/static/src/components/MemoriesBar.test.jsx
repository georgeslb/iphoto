import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import MemoriesBar from './MemoriesBar';
import { localeWrapper } from '../test/testUtils.jsx';

function photo(id, day) {
  return { id, url: `/api/photos/${id}/thumbnail`, fullDateTime: `${day}T10:00:00` };
}

function memoriesResponse() {
  return Promise.resolve({
    ok: true,
    json: () => Promise.resolve([
      {
        monthsAgo: 12,
        photos: [photo('p1', '2025-09-11'), photo('p2', '2025-09-11'), photo('p3', '2025-09-11')],
      },
    ]),
  });
}

const wait = (ms) => new Promise(resolve => setTimeout(resolve, ms));

// Regression test for a real bug: MemoriesBar auto-cycles its own photos every
// AUTO_SWITCH_INTERVAL_MS (3000ms) via setInterval, independent of the Lightbox. Since it stays
// mounted (just visually covered) while the Lightbox is open, that cycling kept silently fetching
// a new, unrelated photo's thumbnail on every tick while the user was looking at a completely
// different asset. isLightboxOpen must pause the cycle - real (not fake) timers here, since
// Testing Library's findBy*/waitFor polling doesn't advance fake ones.
describe('MemoriesBar', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('auto-cycles to the next photo while the Lightbox is closed', async () => {
    global.fetch = vi.fn(() => memoriesResponse());
    render(<MemoriesBar onSeekToDay={() => {}} isLightboxOpen={false} />, { wrapper: localeWrapper });

    const img = await screen.findByAltText('1 year ago');
    expect(img.src).toContain('p1');

    await wait(3200);
    expect(screen.getByAltText('1 year ago').src).toContain('p2');
  }, 10000);

  it('does not cycle (or fetch a new thumbnail) while the Lightbox is open', async () => {
    global.fetch = vi.fn(() => memoriesResponse());
    render(<MemoriesBar onSeekToDay={() => {}} isLightboxOpen={true} />, { wrapper: localeWrapper });

    const img = await screen.findByAltText('1 year ago');
    expect(img.src).toContain('p1');

    await wait(3200);
    expect(screen.getByAltText('1 year ago').src).toContain('p1');
  }, 10000);
});

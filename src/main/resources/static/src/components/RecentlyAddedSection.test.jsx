import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import RecentlyAddedSection from './RecentlyAddedSection';
import { localeWrapper } from '../test/testUtils.jsx';

const BASE_URL = '/api/photos/recent';

function photo(id, contentType = 'image/jpeg') {
  return { id, url: `/api/photos/${id}/thumbnail`, contentType };
}

describe('RecentlyAddedSection', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('is collapsed and fetches nothing on mount', () => {
    global.fetch = vi.fn();
    render(<RecentlyAddedSection baseUrl={BASE_URL} onPhotoClick={() => {}} isLightboxOpen={false} />, { wrapper: localeWrapper });

    expect(global.fetch).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /recently added/i })).toHaveAttribute('aria-expanded', 'false');
  });

  it('fetches page 0 only once the header is expanded', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ content: [photo('a'), photo('b')], last: true }) })
    );
    render(<RecentlyAddedSection baseUrl={BASE_URL} onPhotoClick={() => {}} isLightboxOpen={false} />, { wrapper: localeWrapper });

    fireEvent.click(screen.getByRole('button', { name: /recently added/i }));

    expect(global.fetch.mock.calls[0][0]).toBe(`${BASE_URL}?page=0`);
    // Thumbnails are decorative (alt=""), so they don't carry an accessible "img" role - query by
    // class instead, same as the scroll-pagination test below.
    await waitFor(() => expect(document.querySelectorAll('.recent-assets-item')).toHaveLength(2));
  });

  it('does not refetch on a second expand once already loaded', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ content: [photo('a')], last: true }) })
    );
    render(<RecentlyAddedSection baseUrl={BASE_URL} onPhotoClick={() => {}} isLightboxOpen={false} />, { wrapper: localeWrapper });
    const header = screen.getByRole('button', { name: /recently added/i });

    fireEvent.click(header); // expand -> fetches
    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));
    fireEvent.click(header); // collapse
    fireEvent.click(header); // expand again - already loaded, must not refetch

    expect(global.fetch).toHaveBeenCalledTimes(1);
  });

  it('calls onPhotoClick with the clicked photo', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ content: [photo('a')], last: true }) })
    );
    const onPhotoClick = vi.fn();
    render(<RecentlyAddedSection baseUrl={BASE_URL} onPhotoClick={onPhotoClick} isLightboxOpen={false} />, { wrapper: localeWrapper });

    fireEvent.click(screen.getByRole('button', { name: /recently added/i }));
    await waitFor(() => expect(document.querySelector('.recent-assets-item')).not.toBeNull());
    fireEvent.click(document.querySelector('.recent-assets-item'));

    expect(onPhotoClick).toHaveBeenCalledWith(photo('a'));
  });

  it('loads the next page once the track is scrolled near its right edge', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [photo('a')], last: false }) })
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [photo('b')], last: true }) });
    render(<RecentlyAddedSection baseUrl={BASE_URL} onPhotoClick={() => {}} isLightboxOpen={false} />, { wrapper: localeWrapper });

    fireEvent.click(screen.getByRole('button', { name: /recently added/i }));
    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));

    const track = document.querySelector('.recent-assets-track');
    Object.defineProperty(track, 'scrollWidth', { value: 1000, configurable: true });
    Object.defineProperty(track, 'clientWidth', { value: 300, configurable: true });
    Object.defineProperty(track, 'scrollLeft', { value: 750, configurable: true }); // 1000-750-300 = -50 < threshold
    fireEvent.scroll(track);

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(2));
    expect(global.fetch.mock.calls[1][0]).toBe(`${BASE_URL}?page=1`);
  });

  it('shows a video badge for video items', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ content: [photo('a', 'video/mp4')], last: true }) })
    );
    render(<RecentlyAddedSection baseUrl={BASE_URL} onPhotoClick={() => {}} isLightboxOpen={false} />, { wrapper: localeWrapper });

    fireEvent.click(screen.getByRole('button', { name: /recently added/i }));
    await waitFor(() => expect(document.querySelector('.video-badge')).not.toBeNull());
  });
});

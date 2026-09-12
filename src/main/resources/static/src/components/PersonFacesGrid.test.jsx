import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import PersonFacesGrid from './PersonFacesGrid';
import { localeWrapper } from '../test/testUtils.jsx';

// `image` is FaceDTO's actual field name (see org.iphoto.model.FaceDTO) - matches how every other
// face/cover-photo consumer in this app reads it (Persons.jsx/PersonStrip.jsx's
// `person.coverPhoto.image`). A prior version of this fixture used a made-up `faceThumbnail` key
// that matched the component's own bug rather than the real backend contract - both "passed"
// while rendering nothing but the gray placeholder background in the real app.
const FACES = [
  { id: 'f1', image: 'data:image/jpeg;base64,aaa' },
  { id: 'f2', image: 'data:image/jpeg;base64,bbb' },
];

describe('PersonFacesGrid', () => {
  it('shows the empty-state message when there are no faces and nothing is loading', () => {
    render(<PersonFacesGrid faces={[]} loading={false} hasMore={false} onLoadMore={vi.fn()} onUnlink={vi.fn()} />, { wrapper: localeWrapper });
    expect(screen.getByText(/no faces found/i)).toBeInTheDocument();
  });

  it('renders one card per face with its thumbnail', () => {
    const { container } = render(
      <PersonFacesGrid faces={FACES} loading={false} hasMore={false} onLoadMore={vi.fn()} onUnlink={vi.fn()} />,
      { wrapper: localeWrapper }
    );
    const cards = container.querySelectorAll('.person-face-card');
    expect(cards).toHaveLength(2);
    const images = container.querySelectorAll('.person-face-card img');
    expect(images[0]).toHaveAttribute('src', 'data:image/jpeg;base64,aaa');
    expect(images[1]).toHaveAttribute('src', 'data:image/jpeg;base64,bbb');
  });

  it('calls onUnlink with the right face id when its unlink button is clicked', () => {
    const onUnlink = vi.fn();
    const { container } = render(
      <PersonFacesGrid faces={FACES} loading={false} hasMore={false} onLoadMore={vi.fn()} onUnlink={onUnlink} />,
      { wrapper: localeWrapper }
    );
    const buttons = container.querySelectorAll('.person-face-unlink-btn');
    fireEvent.click(buttons[1]);
    expect(onUnlink).toHaveBeenCalledWith('f2');
    expect(onUnlink).toHaveBeenCalledTimes(1);
  });

  it('shows a "load more" button only when hasMore is true and not currently loading', () => {
    const onLoadMore = vi.fn();
    const { rerender } = render(
      <PersonFacesGrid faces={FACES} loading={false} hasMore={true} onLoadMore={onLoadMore} onUnlink={vi.fn()} />,
      { wrapper: localeWrapper }
    );
    const loadMoreButton = screen.getByRole('button', { name: /load more faces/i });
    fireEvent.click(loadMoreButton);
    expect(onLoadMore).toHaveBeenCalledTimes(1);

    rerender(<PersonFacesGrid faces={FACES} loading={false} hasMore={false} onLoadMore={onLoadMore} onUnlink={vi.fn()} />);
    expect(screen.queryByRole('button', { name: /load more faces/i })).not.toBeInTheDocument();
  });

  it('shows the loading indicator instead of "load more" while a page is in flight', () => {
    render(<PersonFacesGrid faces={FACES} loading={true} hasMore={true} onLoadMore={vi.fn()} onUnlink={vi.fn()} />, { wrapper: localeWrapper });
    expect(screen.getByText(/loading faces/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /load more faces/i })).not.toBeInTheDocument();
  });
});

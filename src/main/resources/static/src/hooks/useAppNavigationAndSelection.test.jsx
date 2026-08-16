import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { useAppNavigationAndSelection } from './useAppNavigationAndSelection';

function wrapper({ children }) {
  return <MemoryRouter initialEntries={['/']}>{children}</MemoryRouter>;
}

describe('useAppNavigationAndSelection', () => {
  // Regression: App.jsx used to call this hook with a hardcoded `null` for
  // saveScrollPosition, so handlePhotoClick's `if (saveScrollPosition) saveScrollPosition()`
  // was silently a no-op and the "restore scroll position after closing the Lightbox"
  // feature never actually saved anything. This locks in that the hook does call it
  // when a real function is supplied.
  it('calls saveScrollPosition before navigating to open the Lightbox', () => {
    const saveScrollPosition = vi.fn();
    const photos = [{ id: 'p1' }, { id: 'p2' }];

    const { result } = renderHook(
      () => useAppNavigationAndSelection(photos, null, null, '/', saveScrollPosition),
      { wrapper }
    );

    act(() => {
      result.current.handlePhotoClick(photos[0]);
    });

    expect(saveScrollPosition).toHaveBeenCalledTimes(1);
  });

  it('does not throw when saveScrollPosition is not provided', () => {
    const photos = [{ id: 'p1' }];

    const { result } = renderHook(
      () => useAppNavigationAndSelection(photos, null, null, '/', null),
      { wrapper }
    );

    expect(() => {
      act(() => {
        result.current.handlePhotoClick(photos[0]);
      });
    }).not.toThrow();
  });
});

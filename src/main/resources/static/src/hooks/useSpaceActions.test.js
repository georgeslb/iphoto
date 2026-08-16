import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useSpaceActions } from './useSpaceActions';
import { localeWrapper } from '../test/testUtils.jsx';

function jsonResponse(body, ok = true, status = 200) {
  return Promise.resolve({ ok, status, json: () => Promise.resolve(body) });
}

function renderSpaceActions(overrides = {}) {
  const refreshAndMaintainScroll = overrides.refreshAndMaintainScroll || vi.fn();
  const setSelectedIds = overrides.setSelectedIds || vi.fn();
  const { result } = renderHook(
    () => useSpaceActions(refreshAndMaintainScroll, setSelectedIds),
    { wrapper: localeWrapper }
  );
  return { result, refreshAndMaintainScroll, setSelectedIds };
}

describe('useSpaceActions', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('handleRestoreSpaces', () => {
    it('clears selection and refreshes on success', async () => {
      global.fetch = vi.fn(() => jsonResponse({}));
      const { result, setSelectedIds, refreshAndMaintainScroll } = renderSpaceActions();

      await act(async () => {
        await result.current.handleRestoreSpaces(['s1']);
      });

      expect(setSelectedIds).toHaveBeenCalledWith([]);
      expect(refreshAndMaintainScroll).toHaveBeenCalled();
    });

    // Regression: this used to fail silently, same class of bug as usePhotoActions.
    it('alerts the user on failure instead of failing silently', async () => {
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      global.fetch = vi.fn(() => jsonResponse({}, false, 500));
      const { result, setSelectedIds } = renderSpaceActions();

      await act(async () => {
        await result.current.handleRestoreSpaces(['s1']);
      });

      expect(alertSpy).toHaveBeenCalled();
      expect(setSelectedIds).not.toHaveBeenCalled();
    });
  });

  describe('handlePermanentDeleteSpaces', () => {
    it('does nothing if the user cancels the confirmation', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(false);
      global.fetch = vi.fn();
      const { result } = renderSpaceActions();

      await act(async () => {
        await result.current.handlePermanentDeleteSpaces(['s1']);
      });

      expect(global.fetch).not.toHaveBeenCalled();
    });

    it('clears selection and refreshes on success', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      global.fetch = vi.fn(() => jsonResponse({}));
      const { result, setSelectedIds, refreshAndMaintainScroll } = renderSpaceActions();

      await act(async () => {
        await result.current.handlePermanentDeleteSpaces(['s1']);
      });

      expect(setSelectedIds).toHaveBeenCalledWith([]);
      expect(refreshAndMaintainScroll).toHaveBeenCalled();
    });

    it('alerts the user on failure instead of failing silently', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      global.fetch = vi.fn(() => jsonResponse({}, false, 500));
      const { result, setSelectedIds } = renderSpaceActions();

      await act(async () => {
        await result.current.handlePermanentDeleteSpaces(['s1']);
      });

      expect(alertSpy).toHaveBeenCalled();
      expect(setSelectedIds).not.toHaveBeenCalled();
    });
  });
});

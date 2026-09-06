import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { usePhotoActions } from './usePhotoActions';
import { localeWrapper } from '../test/testUtils.jsx';

function jsonResponse(body, ok = true, status = 200) {
  return Promise.resolve({ ok, status, json: () => Promise.resolve(body) });
}

function blobResponse(blob, ok = true, status = 200) {
  return Promise.resolve({ ok, status, blob: () => Promise.resolve(blob) });
}

function renderPhotoActions(overrides = {}) {
  const refreshAndMaintainScroll = overrides.refreshAndMaintainScroll || vi.fn();
  const setSelectedPhoto = overrides.setSelectedPhoto || vi.fn();
  const handlePhotoClick = overrides.handlePhotoClick || vi.fn();
  const setSelectedIds = overrides.setSelectedIds || vi.fn();
  const removePhotosLocally = overrides.removePhotosLocally || vi.fn();
  const fetchAlbums = overrides.fetchAlbums || vi.fn();
  const fetchSpaces = overrides.fetchSpaces || vi.fn();
  const { result } = renderHook(
    () => usePhotoActions(refreshAndMaintainScroll, setSelectedPhoto, handlePhotoClick, [], setSelectedIds, removePhotosLocally, fetchAlbums, fetchSpaces),
    { wrapper: localeWrapper }
  );
  return { result, refreshAndMaintainScroll, setSelectedPhoto, handlePhotoClick, setSelectedIds, removePhotosLocally, fetchAlbums, fetchSpaces };
}

describe('usePhotoActions', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('handleMoveToGarbage', () => {
    it('does nothing if the user cancels the confirmation', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(false);
      global.fetch = vi.fn();
      const { result } = renderPhotoActions();

      await act(async () => {
        await result.current.handleMoveToGarbage(['p1']);
      });

      expect(global.fetch).not.toHaveBeenCalled();
    });

    it('clears selection, updates the list in place, and refreshes album/space counts on success', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      global.fetch = vi.fn(() => jsonResponse({}));
      const { result, setSelectedIds, removePhotosLocally, fetchAlbums, fetchSpaces, refreshAndMaintainScroll } = renderPhotoActions();

      await act(async () => {
        await result.current.handleMoveToGarbage(['p1']);
      });

      expect(setSelectedIds).toHaveBeenCalledWith([]);
      expect(removePhotosLocally).toHaveBeenCalledWith(['p1']);
      expect(fetchAlbums).toHaveBeenCalled();
      expect(fetchSpaces).toHaveBeenCalled();
      // Regression: refreshAndMaintainScroll resets the grid to page 0 only, dropping every
      // later page and yanking scroll position - garbage should update the list in place instead
      // (removePhotosLocally, defined in App.jsx - see utils/photoListUpdates.js for the actual
      // removal/duplicate-scrubbing logic and its own tests).
      expect(refreshAndMaintainScroll).not.toHaveBeenCalled();
    });

    // Regression: this used to fail completely silently (no alert, no console.error) —
    // the button looked like it did nothing.
    it('alerts the user on failure instead of failing silently', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      global.fetch = vi.fn(() => jsonResponse({}, false, 500));
      const { result, setSelectedIds, removePhotosLocally } = renderPhotoActions();

      await act(async () => {
        await result.current.handleMoveToGarbage(['p1']);
      });

      expect(alertSpy).toHaveBeenCalled();
      expect(setSelectedIds).not.toHaveBeenCalled();
      expect(removePhotosLocally).not.toHaveBeenCalled();
    });
  });

  describe('handleMoveToMain', () => {
    it('clears selection and refreshes on success', async () => {
      global.fetch = vi.fn(() => jsonResponse({}));
      const { result, setSelectedIds, refreshAndMaintainScroll } = renderPhotoActions();

      await act(async () => {
        await result.current.handleMoveToMain(['p1']);
      });

      expect(global.fetch).toHaveBeenCalledWith('/api/photos/main', expect.objectContaining({ method: 'POST', body: JSON.stringify(['p1']) }));
      expect(setSelectedIds).toHaveBeenCalledWith([]);
      expect(refreshAndMaintainScroll).toHaveBeenCalled();
    });

    it('alerts the user on failure instead of failing silently', async () => {
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      global.fetch = vi.fn(() => jsonResponse({}, false, 500));
      const { result, setSelectedIds } = renderPhotoActions();

      await act(async () => {
        await result.current.handleMoveToMain(['p1']);
      });

      expect(alertSpy).toHaveBeenCalled();
      expect(setSelectedIds).not.toHaveBeenCalled();
    });
  });

  describe('handleRestore', () => {
    it('clears selection and refreshes on success', async () => {
      global.fetch = vi.fn(() => jsonResponse({}));
      const { result, setSelectedIds, refreshAndMaintainScroll } = renderPhotoActions();

      await act(async () => {
        await result.current.handleRestore(['p1']);
      });

      expect(setSelectedIds).toHaveBeenCalledWith([]);
      expect(refreshAndMaintainScroll).toHaveBeenCalled();
    });

    it('alerts the user on failure instead of failing silently', async () => {
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      global.fetch = vi.fn(() => jsonResponse({}, false, 500));
      const { result } = renderPhotoActions();

      await act(async () => {
        await result.current.handleRestore(['p1']);
      });

      expect(alertSpy).toHaveBeenCalled();
    });
  });

  describe('handlePermanentDelete', () => {
    it('does nothing if the user cancels the confirmation', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(false);
      global.fetch = vi.fn();
      const { result } = renderPhotoActions();

      await act(async () => {
        await result.current.handlePermanentDelete(['p1']);
      });

      expect(global.fetch).not.toHaveBeenCalled();
    });

    it('alerts the user on failure instead of failing silently', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      global.fetch = vi.fn(() => jsonResponse({}, false, 500));
      const { result } = renderPhotoActions();

      await act(async () => {
        await result.current.handlePermanentDelete(['p1']);
      });

      expect(alertSpy).toHaveBeenCalled();
    });
  });

  describe('handleUpdateDate', () => {
    it('updates the selected photo and refreshes on success', async () => {
      const updatedPhoto = { id: 'p1', fullDateTime: '2024-01-01T00:00:00' };
      global.fetch = vi.fn(() => jsonResponse(updatedPhoto));
      const { result, setSelectedPhoto, refreshAndMaintainScroll } = renderPhotoActions();

      await act(async () => {
        await result.current.handleUpdateDate('p1', '2024-01-01T00:00');
      });

      expect(setSelectedPhoto).toHaveBeenCalledWith(updatedPhoto);
      expect(refreshAndMaintainScroll).toHaveBeenCalled();
    });

    it('alerts the user on failure', async () => {
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      global.fetch = vi.fn(() => jsonResponse({}, false, 400));
      const { result } = renderPhotoActions();

      await act(async () => {
        await result.current.handleUpdateDate('p1', 'bad-date');
      });

      expect(alertSpy).toHaveBeenCalled();
    });
  });

  describe('handleEditPhoto', () => {
    it('refreshes then navigates to the (same-id) photo when saveAsNew is false', async () => {
      const updatedPhoto = { id: 'p1' };
      global.fetch = vi.fn(() => jsonResponse(updatedPhoto));
      const { result, handlePhotoClick, refreshAndMaintainScroll } = renderPhotoActions();

      const formData = new FormData();
      formData.append('saveAsNew', false);

      await act(async () => {
        await result.current.handleEditPhoto('p1', formData);
      });

      expect(refreshAndMaintainScroll).toHaveBeenCalled();
      expect(handlePhotoClick).toHaveBeenCalledWith(updatedPhoto);
    });

    // Regression: the response body always carries the resulting asset's id (the
    // original id when overwriting, a freshly generated id for "save new"), but the
    // save-as-new branch used to discard it entirely (alert + close lightbox) instead
    // of landing the user on the newly created asset. Using handlePhotoClick (not the
    // raw setSelectedPhoto setter) also keeps the URL (/asset/:id) in sync with the
    // new asset's id, instead of leaving it pointed at the old one.
    it('refreshes then navigates to the newly created photo when saveAsNew is true', async () => {
      const newPhoto = { id: 'new-photo' };
      global.fetch = vi.fn(() => jsonResponse(newPhoto));
      const { result, handlePhotoClick, refreshAndMaintainScroll } = renderPhotoActions();

      const formData = new FormData();
      formData.append('saveAsNew', true);

      await act(async () => {
        await result.current.handleEditPhoto('p1', formData);
      });

      expect(refreshAndMaintainScroll).toHaveBeenCalled();
      expect(handlePhotoClick).toHaveBeenCalledWith(newPhoto);
    });

    it('alerts the user on failure', async () => {
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      global.fetch = vi.fn(() => jsonResponse({}, false, 500));
      const { result } = renderPhotoActions();

      const formData = new FormData();
      formData.append('saveAsNew', false);

      await act(async () => {
        await result.current.handleEditPhoto('p1', formData);
      });

      expect(alertSpy).toHaveBeenCalled();
    });
  });

  describe('handleBulkUpdateDate', () => {
    it('does nothing when there are no ids or no date chosen', async () => {
      global.fetch = vi.fn();
      const { result } = renderPhotoActions();

      await act(async () => {
        await result.current.handleBulkUpdateDate([], '2024-01-01T00:00');
      });
      await act(async () => {
        await result.current.handleBulkUpdateDate(['p1'], '');
      });

      expect(global.fetch).not.toHaveBeenCalled();
    });

    it('applies the date to every selected photo, then clears selection, closes the modal, and refreshes', async () => {
      global.fetch = vi.fn(() => jsonResponse({}));
      const { result, setSelectedIds, refreshAndMaintainScroll } = renderPhotoActions();

      await act(async () => {
        result.current.setShowEditDateModal(true);
      });

      await act(async () => {
        await result.current.handleBulkUpdateDate(['p1', 'p2'], '2024-01-01T00:00');
      });

      expect(global.fetch).toHaveBeenCalledTimes(2);
      expect(global.fetch).toHaveBeenCalledWith('/api/photos/p1/date', expect.objectContaining({
        method: 'PATCH',
        body: JSON.stringify({ newDate: '2024-01-01T00:00' }),
      }));
      expect(global.fetch).toHaveBeenCalledWith('/api/photos/p2/date', expect.objectContaining({ method: 'PATCH' }));
      expect(setSelectedIds).toHaveBeenCalledWith([]);
      expect(result.current.showEditDateModal).toBe(false);
      expect(refreshAndMaintainScroll).toHaveBeenCalled();
    });

    // Regression-shaped: a partial failure (some ids succeed, some don't) must not be
    // treated as a silent success - the user needs to know some photos weren't updated,
    // and the modal/selection should stay intact so they can retry.
    it('alerts on any failure and leaves selection/modal state untouched', async () => {
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      global.fetch = vi.fn()
        .mockReturnValueOnce(jsonResponse({}, true, 200))
        .mockReturnValueOnce(jsonResponse({}, false, 500));
      const { result, setSelectedIds } = renderPhotoActions();

      await act(async () => {
        result.current.setShowEditDateModal(true);
      });

      await act(async () => {
        await result.current.handleBulkUpdateDate(['p1', 'p2'], '2024-01-01T00:00');
      });

      expect(alertSpy).toHaveBeenCalled();
      expect(setSelectedIds).not.toHaveBeenCalled();
      expect(result.current.showEditDateModal).toBe(true);
    });
  });

  describe('handleReorderPhotos', () => {
    it('does nothing when there are no updates', async () => {
      global.fetch = vi.fn();
      const { result } = renderPhotoActions();

      await act(async () => {
        await result.current.handleReorderPhotos([]);
      });
      await act(async () => {
        await result.current.handleReorderPhotos(null);
      });

      expect(global.fetch).not.toHaveBeenCalled();
    });

    it('fires one PATCH per update with its own precomputed date, then refreshes on success', async () => {
      global.fetch = vi.fn(() => jsonResponse({}));
      const { result, refreshAndMaintainScroll } = renderPhotoActions();

      await act(async () => {
        await result.current.handleReorderPhotos([
          { photoId: 'p1', newDate: '2024-01-01T14:00:06.000' },
          { photoId: 'p2', newDate: '2024-01-01T14:00:07.000' },
        ]);
      });

      expect(global.fetch).toHaveBeenCalledTimes(2);
      expect(global.fetch).toHaveBeenCalledWith('/api/photos/p1/date', expect.objectContaining({
        method: 'PATCH',
        body: JSON.stringify({ newDate: '2024-01-01T14:00:06.000' }),
      }));
      expect(global.fetch).toHaveBeenCalledWith('/api/photos/p2/date', expect.objectContaining({
        method: 'PATCH',
        body: JSON.stringify({ newDate: '2024-01-01T14:00:07.000' }),
      }));
      expect(refreshAndMaintainScroll).toHaveBeenCalled();
    });

    // Regression-shaped: unlike handleBulkUpdateDate, a reorder must NOT clear the user's active
    // multi-selection on success - they may still be working with it (e.g. dragging again).
    it('does not clear the selection on success', async () => {
      global.fetch = vi.fn(() => jsonResponse({}));
      const { result, setSelectedIds } = renderPhotoActions();

      await act(async () => {
        await result.current.handleReorderPhotos([{ photoId: 'p1', newDate: '2024-01-01T14:00:06.000' }]);
      });

      expect(setSelectedIds).not.toHaveBeenCalled();
    });

    it('alerts the user on failure instead of failing silently', async () => {
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      global.fetch = vi.fn(() => jsonResponse({}, false, 500));
      const { result, refreshAndMaintainScroll } = renderPhotoActions();

      await act(async () => {
        await result.current.handleReorderPhotos([{ photoId: 'p1', newDate: '2024-01-01T14:00:06.000' }]);
      });

      expect(alertSpy).toHaveBeenCalled();
      expect(refreshAndMaintainScroll).not.toHaveBeenCalled();
    });
  });

  describe('handleDownload', () => {
    it('does nothing when there are no ids', async () => {
      global.fetch = vi.fn();
      const { result } = renderPhotoActions();

      await act(async () => {
        await result.current.handleDownload([]);
      });

      expect(global.fetch).not.toHaveBeenCalled();
    });

    it('navigates the browser directly for a single selected photo (no fetch)', async () => {
      global.fetch = vi.fn();
      const originalLocation = window.location;
      // jsdom's window.location can't be assigned to directly; swap in a plain writable
      // stub for the duration of this test so `window.location.href = ...` (the code path
      // under test) can be observed without triggering a real navigation.
      delete window.location;
      window.location = { href: '' };
      const { result } = renderPhotoActions();

      await act(async () => {
        await result.current.handleDownload(['p1']);
      });

      expect(window.location.href).toBe('/api/photos/p1/download');
      expect(global.fetch).not.toHaveBeenCalled();

      window.location = originalLocation;
    });

    it('fetches a zip and triggers a blob download for multiple selected photos', async () => {
      const blob = new Blob(['zip-bytes']);
      global.fetch = vi.fn(() => blobResponse(blob));
      const createObjectURL = vi.fn(() => 'blob:fake-url');
      const revokeObjectURL = vi.fn();
      global.URL.createObjectURL = createObjectURL;
      global.URL.revokeObjectURL = revokeObjectURL;
      const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
      const { result } = renderPhotoActions();

      await act(async () => {
        await result.current.handleDownload(['p1', 'p2']);
      });

      expect(global.fetch).toHaveBeenCalledWith('/api/photos/download', expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(['p1', 'p2']),
      }));
      expect(createObjectURL).toHaveBeenCalledWith(blob);
      expect(clickSpy).toHaveBeenCalled();
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:fake-url');
    });

    it('alerts the user on failure instead of failing silently', async () => {
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      global.fetch = vi.fn(() => blobResponse(null, false, 500));
      const { result } = renderPhotoActions();

      await act(async () => {
        await result.current.handleDownload(['p1', 'p2']);
      });

      expect(alertSpy).toHaveBeenCalled();
    });
  });
});

import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { usePhotoActions } from './usePhotoActions';
import { localeWrapper } from '../test/testUtils.jsx';

function jsonResponse(body, ok = true, status = 200) {
  return Promise.resolve({ ok, status, json: () => Promise.resolve(body) });
}

function renderPhotoActions(overrides = {}) {
  const refreshAndMaintainScroll = overrides.refreshAndMaintainScroll || vi.fn();
  const setSelectedPhoto = overrides.setSelectedPhoto || vi.fn();
  const handlePhotoClick = overrides.handlePhotoClick || vi.fn();
  const setSelectedIds = overrides.setSelectedIds || vi.fn();
  const setPhotos = overrides.setPhotos || vi.fn();
  const fetchAlbums = overrides.fetchAlbums || vi.fn();
  const fetchSpaces = overrides.fetchSpaces || vi.fn();
  const { result } = renderHook(
    () => usePhotoActions(refreshAndMaintainScroll, setSelectedPhoto, handlePhotoClick, [], setSelectedIds, setPhotos, fetchAlbums, fetchSpaces),
    { wrapper: localeWrapper }
  );
  return { result, refreshAndMaintainScroll, setSelectedPhoto, handlePhotoClick, setSelectedIds, setPhotos, fetchAlbums, fetchSpaces };
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

    it('clears selection and refreshes album/space counts on success, without a full grid reset', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      global.fetch = vi.fn(() => jsonResponse({}));
      const { result, setSelectedIds, setPhotos, fetchAlbums, fetchSpaces, refreshAndMaintainScroll } = renderPhotoActions();

      await act(async () => {
        await result.current.handleMoveToGarbage(['p1']);
      });

      expect(setSelectedIds).toHaveBeenCalledWith([]);
      expect(setPhotos).toHaveBeenCalled();
      expect(fetchAlbums).toHaveBeenCalled();
      expect(fetchSpaces).toHaveBeenCalled();
      // Regression: refreshAndMaintainScroll resets the grid to page 0 only, dropping every
      // later page and yanking scroll position - garbage should update `photos` in place instead.
      expect(refreshAndMaintainScroll).not.toHaveBeenCalled();
    });

    // Regression: garbage-ing a photo from DuplicateClusterModal (whose visible cluster is
    // derived live from `photos`, see PhotoGrid.jsx) used to close the modal and lose the
    // library scroll position, because the old refreshAndMaintainScroll-based refresh reset the
    // whole grid to page 0. The updater passed to setPhotos must both drop the garbage'd photo
    // itself and scrub it out of every remaining photo's own `duplicates` list, since that's
    // what PhotoGrid.jsx's cluster grouping actually reads members from.
    it('removes the garbage-d photo and scrubs it from other photos’ duplicates lists', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      global.fetch = vi.fn(() => jsonResponse({}));
      const { result, setPhotos } = renderPhotoActions();

      await act(async () => {
        await result.current.handleMoveToGarbage(['dup1']);
      });

      const updater = setPhotos.mock.calls[0][0];
      const prev = [
        { id: 'primary', duplicates: [{ id: 'dup1' }, { id: 'dup2' }] },
        { id: 'dup1', duplicates: [{ id: 'primary' }, { id: 'dup2' }] },
        { id: 'dup2', duplicates: [{ id: 'primary' }, { id: 'dup1' }] },
      ];

      const next = updater(prev);

      expect(next.map(p => p.id)).toEqual(['primary', 'dup2']);
      expect(next.find(p => p.id === 'primary').duplicates.map(d => d.id)).toEqual(['dup2']);
      expect(next.find(p => p.id === 'dup2').duplicates.map(d => d.id)).toEqual(['primary']);
    });

    // Regression: this used to fail completely silently (no alert, no console.error) —
    // the button looked like it did nothing.
    it('alerts the user on failure instead of failing silently', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
      global.fetch = vi.fn(() => jsonResponse({}, false, 500));
      const { result, setSelectedIds, setPhotos } = renderPhotoActions();

      await act(async () => {
        await result.current.handleMoveToGarbage(['p1']);
      });

      expect(alertSpy).toHaveBeenCalled();
      expect(setSelectedIds).not.toHaveBeenCalled();
      expect(setPhotos).not.toHaveBeenCalled();
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
});

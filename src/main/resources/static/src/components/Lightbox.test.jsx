import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, waitFor } from '@testing-library/react';
import Lightbox from './Lightbox';
import { localeWrapper } from '../test/testUtils.jsx';

// Targeted regression tests for Lightbox.jsx's trickiest logic, per TODO.md's "large files" item -
// this file had zero test coverage before, despite holding the drag/pan/zoom math, the face-box
// overlay positioning (already the site of one real past bug - see specification.md's "Face-box
// overlay positioning gotcha"), and keyboard nav. Written so a later internal-duplication review
// pass has something to catch a real behavioral regression, not just a lint/render smoke check.

const NO_CAPABILITIES = {
  canBeModified: false,
  canBeMovedToAlbum: false,
  canBeRemovedFromAlbum: false,
  canBeRestored: false,
  canBeMovedToMain: false,
  canBeDeleted: false,
  canBePermanentlyDeleted: false,
};

function makePhoto(id, overrides = {}) {
  return { id, title: `${id}.jpg`, contentType: 'image/jpeg', resolution: '800x400', size: '1MB', ...overrides };
}

function noop() {}

function baseProps(overrides = {}) {
  return {
    onClose: noop,
    extMetadata: null,
    handleMoveToGarbage: noop,
    handleMoveToMain: noop,
    handleRestore: noop,
    handlePermanentDelete: noop,
    onNavigate: vi.fn(),
    onUpdateDate: noop,
    onEditPhoto: noop,
    onShowAddToAlbumModal: noop,
    onShowRemoveFromAlbumModal: noop,
    photoSelection: NO_CAPABILITIES,
    refreshAndMaintainScroll: null,
    ...overrides,
  };
}

// Stubs the real (jsdom-default-zero) layout properties the component's own image-size/pan-clamp
// math reads directly off the DOM nodes - naturalWidth/Height (the <img>'s intrinsic size) and
// clientWidth/Height (the zoom-container's laid-out size). Own-property definitions on the actual
// node shadow the prototype getters and persist across re-renders of the same component instance.
function stubLayout(container, { naturalWidth, naturalHeight, clientWidth, clientHeight }) {
  const img = container.querySelector('.zoom-container img');
  const zoomContainer = container.querySelector('.zoom-container');
  Object.defineProperty(img, 'naturalWidth', { value: naturalWidth, configurable: true });
  Object.defineProperty(img, 'naturalHeight', { value: naturalHeight, configurable: true });
  Object.defineProperty(zoomContainer, 'clientWidth', { value: clientWidth, configurable: true });
  Object.defineProperty(zoomContainer, 'clientHeight', { value: clientHeight, configurable: true });
  zoomContainer.getBoundingClientRect = () => ({
    left: 0, top: 0, right: clientWidth, bottom: clientHeight, width: clientWidth, height: clientHeight, x: 0, y: 0,
  });
  return { img, zoomContainer };
}

describe('Lightbox face-box overlay positioning', () => {
  beforeEach(() => {
    global.fetch = vi.fn((url) => {
      if (url.startsWith('/api/faces/photo/')) {
        // One face at a known natural-image pixel box - see the letterbox math comment below for
        // how its expected on-screen .face-box position is derived.
        return Promise.resolve({ ok: true, json: async () => ([{ id: 'face-1', x: 100, y: 50, width: 60, height: 40 }]) });
      }
      return Promise.resolve({ ok: true, json: async () => ([]) });
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('positions a .face-box using the letterboxed (object-fit: contain) rendered rect, not the raw container box', async () => {
    const photo = makePhoto('p1');
    const { container } = render(
      <Lightbox {...baseProps()} selectedPhoto={photo} photos={[photo]} />,
      { wrapper: localeWrapper }
    );

    // Wait for the async face fetch to populate "Faces Detected" before hovering it into view.
    await waitFor(() => expect(container.querySelector('.face-item')).toBeTruthy());
    fireEvent.mouseEnter(container.querySelector('.extended-meta'));

    // 800x400 natural image (2:1) inside a 1000x1000 container (1:1): image is wider than the
    // container's ratio, so width fills the container (1000) and height is constrained to
    // 1000/2 = 500, letterboxed by 250px top and bottom. scaleX = scaleY = 1000/800 = 1.25.
    const { img } = stubLayout(container, { naturalWidth: 800, naturalHeight: 400, clientWidth: 1000, clientHeight: 1000 });
    fireEvent.load(img);

    const faceBox = await waitFor(() => {
      const el = container.querySelector('.face-box');
      expect(el).toBeTruthy();
      return el;
    });

    // left = offsetX(0) + x(100) * scaleX(1.25) = 125; top = offsetY(250) + y(50) * scaleY(1.25) = 312.5
    expect(faceBox.style.left).toBe('125px');
    expect(faceBox.style.top).toBe('312.5px');
    expect(faceBox.style.width).toBe('75px');   // 60 * 1.25
    expect(faceBox.style.height).toBe('50px');  // 40 * 1.25
  });
});

describe('Lightbox prev/next keyboard navigation', () => {
  beforeEach(() => {
    global.fetch = vi.fn(() => Promise.resolve({ ok: true, json: async () => ([]) }));
  });
  afterEach(() => vi.restoreAllMocks());

  const photos = [makePhoto('p1'), makePhoto('p2'), makePhoto('p3')];

  it('ArrowRight navigates to the next photo, ArrowLeft to the previous', () => {
    const onNavigate = vi.fn();
    render(
      <Lightbox {...baseProps({ onNavigate })} selectedPhoto={photos[1]} photos={photos} />,
      { wrapper: localeWrapper }
    );

    fireEvent.keyDown(window, { key: 'ArrowRight' });
    expect(onNavigate).toHaveBeenCalledWith(photos[2]);

    fireEvent.keyDown(window, { key: 'ArrowLeft' });
    expect(onNavigate).toHaveBeenCalledWith(photos[0]);
  });

  it('does not navigate past either end of the photo list', () => {
    const onNavigate = vi.fn();
    const { rerender } = render(
      <Lightbox {...baseProps({ onNavigate })} selectedPhoto={photos[0]} photos={photos} />,
      { wrapper: localeWrapper }
    );

    fireEvent.keyDown(window, { key: 'ArrowLeft' }); // already first - must not wrap/underflow
    expect(onNavigate).not.toHaveBeenCalled();

    rerender(
      <Lightbox {...baseProps({ onNavigate })} selectedPhoto={photos[2]} photos={photos} />
    );
    fireEvent.keyDown(window, { key: 'ArrowRight' }); // already last - must not wrap/overflow
    expect(onNavigate).not.toHaveBeenCalled();
  });

  it('Escape closes the Lightbox', () => {
    const onClose = vi.fn();
    render(
      <Lightbox {...baseProps({ onClose })} selectedPhoto={photos[0]} photos={photos} />,
      { wrapper: localeWrapper }
    );

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

// Regression tests for the info-panel (.metadata-sidebar) show/hide toggle - added alongside a fix
// for the panel eating the whole screen on mobile with no way to see the full photo/video, and no
// way to reach a video's metadata further down the panel without scrolling being reachable at all.
// The toggle and close controls deliberately live in their own always-rendered
// .lightbox-top-controls group (NOT inside .metadata-sidebar itself) specifically so hiding the
// panel can never also strand the user with no way to close the Lightbox.
describe('Lightbox info panel toggle', () => {
  beforeEach(() => {
    global.fetch = vi.fn(() => Promise.resolve({ ok: true, json: async () => ([]) }));
  });
  afterEach(() => vi.restoreAllMocks());

  it('shows the info panel by default and hides/shows it on toggle click', () => {
    const photo = makePhoto('p1');
    const { container } = render(
      <Lightbox {...baseProps()} selectedPhoto={photo} photos={[photo]} />,
      { wrapper: localeWrapper }
    );

    expect(container.querySelector('.metadata-sidebar')).not.toBeNull();

    const toggleBtn = container.querySelector('.lightbox-top-controls button');
    fireEvent.click(toggleBtn);
    expect(container.querySelector('.metadata-sidebar')).toBeNull();

    fireEvent.click(toggleBtn);
    expect(container.querySelector('.metadata-sidebar')).not.toBeNull();
  });

  it('keeps a working close button reachable even while the info panel is hidden', () => {
    const onClose = vi.fn();
    const photo = makePhoto('p1');
    const { container } = render(
      <Lightbox {...baseProps({ onClose })} selectedPhoto={photo} photos={[photo]} />,
      { wrapper: localeWrapper }
    );

    fireEvent.click(container.querySelector('.lightbox-top-controls button')); // hide the panel
    expect(container.querySelector('.metadata-sidebar')).toBeNull();

    const topControlButtons = container.querySelectorAll('.lightbox-top-controls button');
    fireEvent.click(topControlButtons[topControlButtons.length - 1]); // the persistent close button
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('Lightbox pan/zoom clamping', () => {
  beforeEach(() => {
    global.fetch = vi.fn(() => Promise.resolve({ ok: true, json: async () => ([]) }));
  });
  afterEach(() => vi.restoreAllMocks());

  it('clamps a large drag to the actual overscroll range for the current zoom, not an unbounded pan', () => {
    const photo = makePhoto('p1');
    const { container } = render(
      <Lightbox {...baseProps()} selectedPhoto={photo} photos={[photo]} />,
      { wrapper: localeWrapper }
    );

    // A 1000x1000 natural image in a 1000x1000 container (matching aspect ratios) fills it exactly
    // on both axes with no letterboxing - the cleanest case for reasoning about pan range on both
    // axes at once.
    const { zoomContainer } = stubLayout(container, { naturalWidth: 1000, naturalHeight: 1000, clientWidth: 1000, clientHeight: 1000 });

    // One wheel-zoom-in step from the container's exact center (so the zoom itself introduces no
    // pan offset - see handleWheel's mouse-relative-to-center math) takes zoomScale from 1 to 1.1.
    fireEvent.wheel(zoomContainer, { deltaY: -100, clientX: 500, clientY: 500 });

    // At scale 1.1, the scaled content is 1100x1100 in a 1000x1000 container - only 50px of
    // overscroll is possible on each axis before the container's edge would show empty space.
    const expectedMaxPan = ((1000 * 1.1) - 1000) / 2; // ~50, computed the same way clampPan does

    fireEvent.mouseDown(zoomContainer, { clientX: 0, clientY: 0 });
    fireEvent.mouseMove(zoomContainer, { clientX: 100000, clientY: -100000 }); // wildly past the range on both axes

    const transform = zoomContainer.style.transform;
    const match = transform.match(/translate\(([-\d.]+)px, ([-\d.]+)px\)/);
    expect(match).toBeTruthy();
    const [, panX, panY] = match.map(Number);

    expect(panX).toBeCloseTo(expectedMaxPan, 5);   // clamped to the positive max, not 100000
    expect(panY).toBeCloseTo(-expectedMaxPan, 5);  // clamped to the negative max, not -100000
  });

  it('does not allow any pan at all while still at the default (1x) zoom', () => {
    const photo = makePhoto('p1');
    const { container } = render(
      <Lightbox {...baseProps()} selectedPhoto={photo} photos={[photo]} />,
      { wrapper: localeWrapper }
    );
    const { zoomContainer } = stubLayout(container, { naturalWidth: 1000, naturalHeight: 1000, clientWidth: 1000, clientHeight: 1000 });

    // handleMouseDown itself bails out at zoomScale === 1 (dragging only makes sense once zoomed
    // in), so isDragging never becomes true and this mousemove must be a no-op.
    fireEvent.mouseDown(zoomContainer, { clientX: 0, clientY: 0 });
    fireEvent.mouseMove(zoomContainer, { clientX: 500, clientY: 500 });

    expect(zoomContainer.style.transform).toContain('translate(0px, 0px)');
  });
});

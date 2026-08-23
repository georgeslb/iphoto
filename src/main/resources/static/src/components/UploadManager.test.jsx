import React, { useRef } from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, fireEvent, screen, waitFor } from '@testing-library/react';
import UploadManager from './UploadManager';
import { localeWrapper } from '../test/testUtils.jsx';

function Harness({ refreshAndMaintainScroll = () => {} } = {}) {
  const fileInputRef = useRef(null);
  return (
    <UploadManager refreshAndMaintainScroll={refreshAndMaintainScroll} effectivePath="" fileInputRef={fileInputRef}>
      {({ handleUpload }) => (
        <input data-testid="file-input" type="file" multiple onChange={handleUpload} ref={fileInputRef} />
      )}
    </UploadManager>
  );
}

function renderHarness(overrides) {
  return render(<Harness {...overrides} />, { wrapper: localeWrapper });
}

function selectFiles(files) {
  fireEvent.change(screen.getByTestId('file-input'), { target: { files } });
}

describe('UploadManager retry button', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  // Regression: an unsupported-type file used to be queued with `file: null`, and the
  // Retry button only renders when `item.status === 'FAILED' && item.file` — so a client-
  // rejected file silently lost the Retry option that every other failed upload has.
  it('still shows a Retry button when a file is rejected for an unsupported type', async () => {
    renderHarness();
    const badFile = new File(['not a photo'], 'notes.txt', { type: 'text/plain' });

    selectFiles([badFile]);

    expect(await screen.findByText(/status-text FAILED|FAILED/i)).toBeTruthy();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('shows a Retry button and can re-attempt after a genuine server-side upload failure', async () => {
    global.fetch = vi.fn(() => Promise.resolve({ ok: false, status: 500, text: () => Promise.resolve('boom') }));
    renderHarness();
    const goodFile = new File(['image bytes'], 'photo.jpg', { type: 'image/jpeg' });

    selectFiles([goodFile]);

    const retryButton = await screen.findByRole('button', { name: /retry/i });
    expect(global.fetch).toHaveBeenCalledTimes(1);

    global.fetch.mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({ status: 'chunk-received' }) });
    fireEvent.click(retryButton);

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(2));
  });

  // Regression: the backend can mark a task FAILED with a null errorMessage (e.g. an
  // exception thrown with no message). The Retry button used to live inside the
  // `item.error && (...)` block, so a null error silently hid it even though the item
  // was FAILED — same failure mode as the unsupported-type case above, but reached via
  // task polling instead of client-side rejection.
  it('still shows a Retry button when the task fails via polling with no error message', async () => {
    global.fetch = vi.fn((url) => {
      if (url === '/api/upload/chunk') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ status: 'completed', task: { id: 'task-1', status: 'PENDING', photoId: null } }) });
      }
      if (url === '/api/tasks/task-1') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ id: 'task-1', status: 'FAILED', errorMessage: null }) });
      }
      return Promise.reject(new Error(`unexpected fetch ${url}`));
    });
    renderHarness();
    const goodFile = new File(['image bytes'], 'photo.jpg', { type: 'image/jpeg' });

    selectFiles([goodFile]);

    expect(await screen.findByText('FAILED', {}, { timeout: 6000 })).toBeTruthy();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  }, 12000);
});

describe('UploadManager filename link opens a preview popup', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function mockUploadAndPreviewFetch() {
    return vi.fn((url) => {
      if (url === '/api/upload/chunk') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ status: 'completed', task: { id: 'task-1', status: 'PENDING', photoId: 'photo-1' } }) });
      }
      if (url === '/api/tasks/task-1') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ id: 'task-1', status: 'COMPLETED', photoId: 'photo-1' }) });
      }
      if (url === '/api/photos/photo-1') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ id: 'photo-1', contentType: 'image/jpeg', fileHash: 'abc123', title: 'photo.jpg' }) });
      }
      return Promise.reject(new Error(`unexpected fetch ${url}`));
    });
  }

  it('turns the file name into the trigger (no separate "Show" button), opening a backdrop-less preview popup', async () => {
    global.fetch = mockUploadAndPreviewFetch();
    renderHarness();
    selectFiles([new File(['image bytes'], 'photo.jpg', { type: 'image/jpeg' })]);

    const nameLink = await screen.findByRole('button', { name: 'photo.jpg' }, { timeout: 6000 });
    fireEvent.click(nameLink);

    const previewImg = await screen.findByRole('img');
    expect(previewImg).toHaveAttribute('src', '/api/photos/photo-1/original?v=abc123');
    // Standard-sized popup, page behind stays visible — not the full-screen black Lightbox.
    expect(document.querySelector('.upload-preview-popup')).toBeInTheDocument();
    expect(document.querySelector('.lightbox-overlay')).not.toBeInTheDocument();
    expect(document.querySelector('.metadata-sidebar')).not.toBeInTheDocument();

    // photoId is available immediately (before the queue item even reaches COMPLETED), so
    // the assertions above don't wait for the poll interval to fire at all. Wait for it to
    // actually finish here so it clears itself before the test ends — otherwise the still-
    // pending real setInterval fires mid-way through a *later* test and corrupts it.
    await screen.findByText('COMPLETED', {}, { timeout: 5000 });
  }, 12000);

  it('closes on Escape', async () => {
    global.fetch = mockUploadAndPreviewFetch();
    renderHarness();
    selectFiles([new File(['image bytes'], 'photo.jpg', { type: 'image/jpeg' })]);

    const nameLink = await screen.findByRole('button', { name: 'photo.jpg' }, { timeout: 6000 });
    fireEvent.click(nameLink);
    await screen.findByRole('img');

    fireEvent.keyDown(window, { key: 'Escape' });

    expect(screen.queryByRole('img')).not.toBeInTheDocument();

    // See the comment in the previous test — wait out the poll interval so it self-clears.
    await screen.findByText('COMPLETED', {}, { timeout: 5000 });
  }, 12000);
});

describe('UploadManager preview link eligibility', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  // Regression coverage for the explicit rule: available for every status except PENDING
  // and FAILED.
  it('does not make the file name clickable for a FAILED item', async () => {
    renderHarness();
    const badFile = new File(['not a photo'], 'notes.txt', { type: 'text/plain' });

    selectFiles([badFile]);

    await screen.findByText('FAILED');
    expect(screen.queryByRole('button', { name: 'notes.txt' })).not.toBeInTheDocument();
  });

  it('does not make the file name clickable while the task is still PENDING, but does once it moves past PENDING', async () => {
    let pollCount = 0;
    global.fetch = vi.fn((url) => {
      if (url === '/api/upload/chunk') {
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ status: 'completed', task: { id: 'task-1', status: 'PENDING', photoId: 'photo-1' } }) });
      }
      if (url === '/api/tasks/task-1') {
        pollCount += 1;
        const status = pollCount === 1 ? 'PENDING' : 'COMPLETED';
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ id: 'task-1', status, photoId: 'photo-1' }) });
      }
      return Promise.reject(new Error(`unexpected fetch ${url}`));
    });

    renderHarness();
    selectFiles([new File(['image bytes'], 'photo.jpg', { type: 'image/jpeg' })]);

    await screen.findByText('PENDING', {}, { timeout: 6000 });
    expect(screen.queryByRole('button', { name: 'photo.jpg' })).not.toBeInTheDocument();

    await screen.findByRole('button', { name: 'photo.jpg' }, { timeout: 6000 });
  }, 15000);
});

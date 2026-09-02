package org.iphoto.model;

// Non-exceptional outcomes of ImmichImportService.processSyncFromImmichTask (phase 1) and
// finalizeUploadFromImmich (phase 2) - a thrown exception (network error, unexpected shape, DB/
// disk error, etc.) is the other possibility for either phase, handled generically by
// TaskWorkerManager's existing catch(Throwable) same as any other task type.
public enum ImmichAssetImportOutcome {
    IMPORTED,
    SKIPPED_ALREADY_IMPORTED,
    SKIPPED_DUPLICATE_CONTENT
}

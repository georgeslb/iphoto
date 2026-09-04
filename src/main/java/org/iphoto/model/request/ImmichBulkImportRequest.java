package org.iphoto.model.request;

import java.time.LocalDate;
import java.util.List;

// Body for POST /api/immich/days/import-bulk - the frontend's multi-select "Import Selected"
// action. See ImmichImportService#triggerBulkDayImport for the day-count cap.
public record ImmichBulkImportRequest(List<LocalDate> days) {
}

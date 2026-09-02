package org.iphoto.model;

import java.util.List;

public record ImmichImportOverviewDTO(List<ImmichImportDayDTO> days, ImmichCoverageDTO coverage) {
}

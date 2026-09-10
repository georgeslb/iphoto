package org.iphoto.model.request;

import java.util.List;
import java.util.UUID;

// Admin > Tasks page's mass-selection "Restart Selected" action (AdminController#restartTasks) -
// one bulk request instead of one HTTP round trip per checked row.
public record TaskRestartRequest(List<UUID> taskIds) {}

package org.iphoto.model.request;

import java.util.List;
import java.util.UUID;

// Exactly two person ids to merge. Direction isn't part of the request — the person with fewer
// faces always merges into the one with more (see PersonRecognitionService.mergePersons).
public record PersonMergeRequest(List<UUID> personIds) {}

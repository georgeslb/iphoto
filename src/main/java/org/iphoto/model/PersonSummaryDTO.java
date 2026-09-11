package org.iphoto.model;

import java.util.UUID;

// Lean shape for the Persons grid list (GET /api/faces/persons) - that page only ever renders the
// cover thumbnail and the name (see Persons.jsx), so it doesn't need PersonDTO's `photoCount`
// (used elsewhere - PersonDetail.jsx, the merge-suggestion/rename-collision pickers - but not
// here). Trimming it out of this specific response was requested directly (2026-09-11) alongside
// fixing the actual cause of that endpoint's bloated size/latency - see
// FaceDetectionServiceImpl's face-thumbnail-generation comment.
public record PersonSummaryDTO(
        UUID id,
        String name,
        FaceDTO coverPhoto
) { }

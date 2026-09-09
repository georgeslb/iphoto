package org.iphoto.model;

import java.util.UUID;

// One "these two people might be the same" suggestion - see PersonMergeSuggestionService. `id` is
// the suggestion row's own id (not either person's), used by the frontend purely to key/dedupe the
// list client-side - merging is still done via the existing POST /api/faces/persons/merge with
// [personA.id(), personB.id()], the suggestion itself is never merged/deleted directly by the
// client.
public record PersonMergeSuggestionDTO(
        UUID id,
        PersonDTO personA,
        PersonDTO personB,
        double similarity
) { }

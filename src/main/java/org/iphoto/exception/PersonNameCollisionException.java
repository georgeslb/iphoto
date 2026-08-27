package org.iphoto.exception;

import java.util.UUID;

// Thrown by FaceService.setFacePerson when the requested name already belongs to a DIFFERENT
// person than the one currently linked (or no person at all) and the caller hasn't confirmed the
// merge yet. Carries just the id — the controller re-fetches the DTO via FaceService.getPerson so
// mapping logic stays in one place.
public class PersonNameCollisionException extends RuntimeException {
    private final UUID existingPersonId;

    public PersonNameCollisionException(UUID existingPersonId) {
        this.existingPersonId = existingPersonId;
    }

    public UUID getExistingPersonId() {
        return existingPersonId;
    }
}

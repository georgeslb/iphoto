package org.iphoto.exception;

import java.util.List;
import java.util.UUID;

// Thrown by FaceService.renamePerson (Person page title / Persons grid rename) when the requested
// name already belongs to one or more OTHER people and the caller hasn't confirmed which of them
// (if any) to merge in yet. Plural sibling of PersonNameCollisionException — that one is thrown by
// the single-face rename flow (FaceService.setFacePerson) where at most one collision candidate
// is ever meaningful; a whole-person rename can run into several, since this app doesn't enforce
// name uniqueness at the DB level. Carries just the ids — the controller re-fetches the DTOs via
// FaceService.getPersons so mapping logic stays in one place.
public class PersonNameCollisionsException extends RuntimeException {
    private final List<UUID> existingPersonIds;

    public PersonNameCollisionsException(List<UUID> existingPersonIds) {
        this.existingPersonIds = existingPersonIds;
    }

    public List<UUID> getExistingPersonIds() {
        return existingPersonIds;
    }
}

package org.iphoto.model.request;

import java.util.List;
import java.util.UUID;

// Backs the Person page title / Persons grid rename (FaceService.renamePerson) — distinct from
// FaceRenameRequest, which renames a single face's person link, not the person entity itself.
//   - First attempt: confirmed=false, mergeWithPersonIds=null/empty. If the name collides with one
//     or more OTHER existing people, the request is rejected (409) with that list instead of
//     applying anything, so the caller can prompt "merge with one of these?".
//   - Confirmed attempt: confirmed=true, mergeWithPersonIds holding whichever of the previously
//     returned candidates the user actually chose to fold in — possibly none of them (an empty
//     list here is a deliberate "rename anyway, don't merge with any of these" choice, not "haven't
//     decided yet", which is what confirmed=false is for).
public record PersonRenameRequest(String name, boolean confirmed, List<UUID> mergeWithPersonIds) {}

package org.iphoto.event;

import java.util.UUID;

// Published whenever a photo is added to or effectively removed from a space - upload, Immich
// import, moving to/from garbage (the photo's own space doesn't change, but it stops/starts
// counting toward the space's active total), moving to another space (published twice: -1 on the
// source, +1 on the destination), "save as new" (routes through the same upload path as a plain
// upload, so no separate trigger needed). See SpacePhotoCountListener for the atomic update this
// triggers - deliberately just a spaceId + delta, not the SpaceEntity itself, so publishing this
// never requires a managed entity instance.
public record SpacePhotoCountChangedEvent(UUID spaceId, int delta) {
}

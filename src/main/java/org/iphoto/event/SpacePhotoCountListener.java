package org.iphoto.event;

import lombok.RequiredArgsConstructor;
import org.iphoto.repository.SpaceRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Applies SpacePhotoCountChangedEvent atomically - a single `UPDATE ... SET photo_count =
// photo_count + :delta` (SpaceRepository#adjustPhotoCount), not a Java-side read/increment/save.
// That single UPDATE statement is inherently race-free under concurrent publishers for the same
// space: Postgres serializes concurrent writers to the same row itself, so there's no lost-update
// window to guard against and so no need for a compare-and-swap/optimistic-locking retry loop on
// top of it (nor the in-JVM per-space lock this replaces, which only ever protected exactly this
// read-modify-write and nothing else at any of its call sites).
//
// Deliberately a plain @EventListener, not @TransactionalEventListener: this must run
// SYNCHRONOUSLY, inside the SAME transaction the publisher is already in, so the count update
// commits (or rolls back) atomically together with the photo add/remove that triggered it - Spring
// invokes plain listeners immediately, in the publishing thread, which is exactly that.
@Component
@RequiredArgsConstructor
public class SpacePhotoCountListener {

    private final SpaceRepository spaceRepository;

    @EventListener
    @Transactional
    public void onSpacePhotoCountChanged(SpacePhotoCountChangedEvent event) {
        spaceRepository.adjustPhotoCount(event.spaceId(), event.delta());
    }
}

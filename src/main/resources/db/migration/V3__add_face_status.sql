-- Lets FaceRecognitionServiceImpl.groupFacesForUser tell apart faces it has never analysed yet
-- (NEW) from ones a past run already checked against every existing person (OLD) - without this,
-- a large permanently-unmatched backlog (faces that will never cluster into a person) gets
-- re-compared against every person on every single run, forever. Defaulted to 'NEW' for existing
-- rows too: already-matched faces (person_id not null) are unaffected either way since every query
-- filtering on status also filters person_id IS NULL, and already-unmatched rows simply get one
-- more analysis pass on the first run after this migration, same as any other NEW face, before
-- settling into the new NEW/OLD lifecycle from then on.
alter table faces add column status varchar(10) not null default 'NEW';
alter table faces add constraint ck_faces_status check (status in ('NEW', 'OLD'));

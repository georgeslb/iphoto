-- Backs the "these two people might be the same person" suggestion feature (see
-- PersonMergeSuggestionService/specification.md's Persons View section): an extra step run
-- alongside face recognition that compares every existing person's overall embedding against
-- every other person's, surfacing a suggestion for a human to confirm/dismiss rather than
-- auto-merging. space_id is stored directly (not derived via person_a_id/person_b_id's own
-- space_id at query time) since the writer runs on TaskWorkerManager's heavy-task thread pool,
-- where @MainSpaceLimited would silently be a no-op - same reasoning as people.space_id itself.
create table person_merge_suggestions (
    id uuid not null,
    person_a_id uuid not null,
    person_b_id uuid not null,
    space_id uuid not null,
    similarity double precision not null,
    created_at timestamp,
    constraint person_merge_suggestions_pkey primary key (id),
    constraint uk_person_merge_suggestions_person_a_id_person_b_id unique (person_a_id, person_b_id),
    constraint fk_person_merge_suggestions_person_a_id foreign key (person_a_id) references people (id) on delete cascade,
    constraint fk_person_merge_suggestions_person_b_id foreign key (person_b_id) references people (id) on delete cascade,
    constraint fk_person_merge_suggestions_space_id foreign key (space_id) references spaces (id) on delete cascade
);
-- person_a_id is already covered (leading column of the composite unique above).
create index idx_person_merge_suggestions_person_b_id on person_merge_suggestions (person_b_id);
create index idx_person_merge_suggestions_space_id on person_merge_suggestions (space_id);

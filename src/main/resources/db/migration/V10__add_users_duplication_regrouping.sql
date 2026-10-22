-- Backs the per-user toggle for duplicate regrouping in the Library page (UserEntity.duplicationRegrouping).
-- Default is true so existing and newly created users keep duplicate regrouping enabled by default.
alter table users add column duplication_regrouping boolean not null default true;

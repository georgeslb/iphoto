-- uk_person_embedding_buckets_person_id_bucket_year (person_id, bucket_year) already lets Postgres
-- use person_id as a leftmost-prefix lookup (see V1's own comment on this table), so this isn't
-- needed for correctness - it's a dedicated, narrower index for the hot read path
-- (PersonEmbeddingBucketRepository#findByPersonId/findByPersonIdIn, batch-loaded once per
-- FaceRecognitionService run per PersonRecognitionServiceImpl.addFaceToBucket/
-- removeFaceFromBucket/mergeBuckets) without bucket_year along for the ride.
create index idx_person_embedding_buckets_person_id on person_embedding_buckets (person_id);

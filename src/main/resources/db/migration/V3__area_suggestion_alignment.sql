-- P7 Task 4: Area storage model canonical alignment
-- Rename area_candidate table to area_suggestion
-- Add provider and center_distance_m columns

ALTER TABLE area_candidate RENAME TO area_suggestion;

ALTER INDEX idx_area_candidate_job RENAME TO idx_area_suggestion_job;

ALTER TABLE area_suggestion ADD COLUMN provider TEXT NOT NULL DEFAULT 'KAKAO';
ALTER TABLE area_suggestion ADD COLUMN center_distance_m INT NOT NULL DEFAULT 0;

ALTER TABLE area_suggestion ADD CONSTRAINT ck_area_suggestion_rank CHECK (rank BETWEEN 1 AND 3);

ALTER TABLE area_suggestion
    DROP CONSTRAINT ck_area_suggestion_rank;

ALTER TABLE area_suggestion
    ADD CONSTRAINT ck_area_suggestion_rank CHECK (rank BETWEEN 1 AND 10);

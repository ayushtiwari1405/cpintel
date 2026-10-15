-- Skill ratings: a rating per skill, in Codeforces points, replacing the 0-100 mastery score as
-- what the analytics and the roadmap read.
--
-- skill_rating is null when there is nothing to base one on: no rated rounds and no placement
-- test. Those users are shown what they have practised (coverage) instead of a number.
-- skill_sd is one standard deviation, in rating points. skill_basis records which source the
-- rating came from, so the page can say so.
ALTER TABLE topic_mastery
    ADD COLUMN skill_rating   DOUBLE PRECISION,
    ADD COLUMN skill_sd       DOUBLE PRECISION,
    ADD COLUMN skill_problems INTEGER,
    ADD COLUMN skill_basis    VARCHAR(12),
    -- The hardest problem solved on this skill, in practice or in a round: the "how far have
    -- they got" half of coverage, for people with no rating to show.
    ADD COLUMN max_solved_rating INTEGER;

-- What a person's skill ratings are based on: their Codeforces rated rounds, the placement
-- gauntlet, or AUTO (rounds when there are any, then the gauntlet, then nothing). Somebody whose
-- Codeforces rating does not reflect them — rounds sat with help, an old account — chooses the
-- gauntlet instead.
ALTER TABLE users
    ADD COLUMN skill_basis VARCHAR(12) NOT NULL DEFAULT 'AUTO'
        CHECK (skill_basis IN ('AUTO', 'CODEFORCES', 'GAUNTLET'));

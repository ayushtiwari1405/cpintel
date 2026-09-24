-- LeetCode and CodeChef are dropped; Codeforces is the only platform synced for analytics.
--
-- Their sync was never complete — LeetCode returned the latest hundred accepted submissions,
-- CodeChef none at all — so the analytics built on them described a sample, not the user.
-- What they stored was public data re-fetchable from the judges, so it is removed rather than
-- left for nothing to read. (DOMjudge is untouched: it is a compete judge, not a platform
-- account.)

DELETE FROM sync_jobs          WHERE platform IN ('LEETCODE', 'CODECHEF');
DELETE FROM contest_summaries  WHERE platform IN ('LEETCODE', 'CODECHEF');
DELETE FROM platform_accounts  WHERE platform IN ('LEETCODE', 'CODECHEF');

ALTER TABLE platform_accounts DROP CONSTRAINT chk_platform;
ALTER TABLE platform_accounts ADD  CONSTRAINT chk_platform CHECK (platform = 'CODEFORCES');

-- The unified score was a blend of the three; it is now Codeforces on a 0-1000 scale.
ALTER TABLE unified_scores
    DROP COLUMN lc_score,
    DROP COLUMN cc_score,
    DROP COLUMN lc_weight,
    DROP COLUMN cc_weight;

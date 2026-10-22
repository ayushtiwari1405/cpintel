db = db.getSiblingDB('cpintel');

// Indexes the entities also declare (@CompoundIndex) carry the entities' names. Created here
// under MongoDB's default names, they collided with the backend's own index creation at start
// (IndexOptionsConflict: same keys, another name) and a fresh installation could not start.

db.createCollection('cf_submissions');
db.createCollection('code_submissions');
db.createCollection('contest_snapshots');
db.createCollection('activity_feed');

db.cf_submissions.createIndex({ userId: 1, submittedAt: -1 });
// Unique per user, not globally: two accounts can link the same handle (a re-registration,
// or an admin testing with their own), and a global key made the second one's sync skip every
// submission as "already stored" — under somebody else's userId.
db.cf_submissions.createIndex({ userId: 1, cfSubmissionId: 1 }, { unique: true, name: 'idx_cf_user_submission' });
db.cf_submissions.createIndex({ userId: 1, verdict: 1 }, { name: 'idx_cf_user_verdict' });
db.cf_submissions.createIndex({ userId: 1, tags: 1 });

// The archive of source the user actually wrote. Unlike cf_submissions this cannot be
// re-derived from the platform, so it is never wiped as part of a resync.
db.code_submissions.createIndex(
  { userId: 1, platform: 1, contestId: 1, problemIndex: 1, submittedAt: -1 },
  { name: 'idx_code_user_problem' });
db.code_submissions.createIndex({ userId: 1, platform: 1, externalId: 1 }, { name: 'idx_code_user_external' });
db.code_submissions.createIndex({ userId: 1, submittedAt: -1 });

print('CPIntel MongoDB initialized');

db = db.getSiblingDB('cpintel');

db.createCollection('cf_submissions');
db.createCollection('code_submissions');
db.createCollection('contest_snapshots');
db.createCollection('activity_feed');

db.cf_submissions.createIndex({ userId: 1, submittedAt: -1 });
// Unique per user, not globally: two accounts can link the same handle (a re-registration,
// or an admin testing with their own), and a global key made the second one's sync skip every
// submission as "already stored" — under somebody else's userId.
db.cf_submissions.createIndex({ userId: 1, cfSubmissionId: 1 }, { unique: true });
db.cf_submissions.createIndex({ userId: 1, verdict: 1 });
db.cf_submissions.createIndex({ userId: 1, tags: 1 });

// The archive of source the user actually wrote. Unlike cf_submissions this cannot be
// re-derived from the platform, so it is never wiped as part of a resync.
db.code_submissions.createIndex(
  { userId: 1, platform: 1, contestId: 1, problemIndex: 1, submittedAt: -1 });
db.code_submissions.createIndex({ userId: 1, platform: 1, externalId: 1 });
db.code_submissions.createIndex({ userId: 1, submittedAt: -1 });

print('CPIntel MongoDB initialized');

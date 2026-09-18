db = db.getSiblingDB('cpintel');

db.createCollection('cf_submissions');
db.createCollection('lc_submissions');
db.createCollection('cc_submissions');
db.createCollection('code_submissions');
db.createCollection('contest_snapshots');
db.createCollection('activity_feed');

db.cf_submissions.createIndex({ userId: 1, submittedAt: -1 });
db.cf_submissions.createIndex({ cfSubmissionId: 1 }, { unique: true });
db.cf_submissions.createIndex({ userId: 1, verdict: 1 });
db.cf_submissions.createIndex({ userId: 1, tags: 1 });

// The archive of source the user actually wrote. Unlike cf_submissions this cannot be
// re-derived from the platform, so it is never wiped as part of a resync.
db.code_submissions.createIndex(
  { userId: 1, platform: 1, contestId: 1, problemIndex: 1, submittedAt: -1 });
db.code_submissions.createIndex({ userId: 1, platform: 1, externalId: 1 });
db.code_submissions.createIndex({ userId: 1, submittedAt: -1 });

db.lc_submissions.createIndex({ userId: 1, submittedAt: -1 });
db.lc_submissions.createIndex({ lcSubmissionId: 1 }, { unique: true, sparse: true });
db.lc_submissions.createIndex({ userId: 1, status: 1 });

db.cc_submissions.createIndex({ userId: 1, submittedAt: -1 });
db.cc_submissions.createIndex({ userId: 1, result: 1 });

print('CPIntel MongoDB initialized');

db = db.getSiblingDB('cpintel');

db.createCollection('cf_submissions');
db.createCollection('lc_submissions');
db.createCollection('cc_submissions');
db.createCollection('contest_snapshots');
db.createCollection('activity_feed');

db.cf_submissions.createIndex({ userId: 1, submittedAt: -1 });
db.cf_submissions.createIndex({ cfSubmissionId: 1 }, { unique: true });
db.cf_submissions.createIndex({ userId: 1, verdict: 1 });
db.cf_submissions.createIndex({ userId: 1, tags: 1 });

db.lc_submissions.createIndex({ userId: 1, submittedAt: -1 });
db.lc_submissions.createIndex({ lcSubmissionId: 1 }, { unique: true, sparse: true });
db.lc_submissions.createIndex({ userId: 1, status: 1 });

db.cc_submissions.createIndex({ userId: 1, submittedAt: -1 });
db.cc_submissions.createIndex({ userId: 1, result: 1 });

print('CPIntel MongoDB initialized');

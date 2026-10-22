db = db.getSiblingDB('cpintel');

// Indexes the entities also declare (@CompoundIndex) carry the entities' names. Created here
// under MongoDB's default names, they collided with the backend's own index creation at start
// (IndexOptionsConflict: same keys, another name) and a fresh installation could not start.
// The backend creates the rest itself at start (personal_files, contest_file_rules, ...). This
// runs only on an empty database; an existing one is brought up to date by
// scripts/mongo-index-upgrade.sh (README, "Upgrading an existing MongoDB").

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

// The account the backend connects with: readWrite on this database and nothing else. The root
// account from MONGO_INITDB_ROOT_* stays for administration (backups, restores, this script).
//
// Runs only when the data directory is empty, as every init script does. A database created
// before this account existed gets it from scripts/mongo-app-user.sh, which deploy.sh and
// start.sh run before the backend starts.
const appUser = process.env.MONGO_APP_USER || 'cpintel_app';
const appPassword = process.env.MONGO_APP_PASSWORD;
if (!appPassword) {
  throw new Error('MONGO_APP_PASSWORD is not set: the backend would have no account to connect with.');
}
db.createUser({ user: appUser, pwd: appPassword, roles: [{ role: 'readWrite', db: 'cpintel' }] });

print('CPIntel MongoDB initialized');

// Run by scripts/mongo-index-upgrade.sh inside the MongoDB container, as its root account, with
// `const PARAMS = {...}` prepended. Not meant to be run by hand; see the shell script's --help.
//
// It speaks to the shell script in lines:
//   SAY <text>            shown to the operator
//   EXPORT <file> <json>  appended by the shell script to <out dir>/<file> (never shown)
//   STATUS <word>         the outcome: READY, DUPLICATES, UNSAFE, WRITERS, APPLIED, PLANNED, RENAMED
//
// The definitions below are the backend's (entity annotations) and every earlier mongo-init.js's.
// An index is only ever matched by its exact key - fields, order and direction - and options.

const app = db.getSiblingDB(PARAMS.database);

// What the backend declares (@CompoundIndex) on the collections an upgrade can disturb.
const DESIRED = [
  { coll: 'cf_submissions', name: 'idx_cf_user_submission', key: { userId: 1, cfSubmissionId: 1 }, unique: true },
  { coll: 'cf_submissions', name: 'idx_cf_user_verdict', key: { userId: 1, verdict: 1 } },
  { coll: 'code_submissions', name: 'idx_code_user_problem',
    key: { userId: 1, platform: 1, contestId: 1, problemIndex: 1, submittedAt: -1 } },
  { coll: 'code_submissions', name: 'idx_code_user_external', key: { userId: 1, platform: 1, externalId: 1 } },
  { coll: 'personal_files', name: 'idx_files_user_name', key: { userId: 1, name: 1 }, unique: true },
  { coll: 'contest_file_rules', name: 'idx_file_rule_contest', key: { platform: 1, contestId: 1 }, unique: true },
];

// What earlier mongo-init.js files created under MongoDB's default names. `replaces`: the same key
// as a backend index, which MongoDB will not hold twice - the backend then stops at start with
// IndexOptionsConflict. `retire`: the global unique key of the first releases (6041bf0, 7487e24),
// stricter than the per-user one; dropped only once idx_cf_user_submission exists.
const LEGACY = [
  { coll: 'cf_submissions', name: 'userId_1_cfSubmissionId_1', key: { userId: 1, cfSubmissionId: 1 }, unique: true,
    replaces: 'idx_cf_user_submission' },
  { coll: 'cf_submissions', name: 'userId_1_verdict_1', key: { userId: 1, verdict: 1 }, replaces: 'idx_cf_user_verdict' },
  { coll: 'code_submissions', name: 'userId_1_platform_1_contestId_1_problemIndex_1_submittedAt_-1',
    key: { userId: 1, platform: 1, contestId: 1, problemIndex: 1, submittedAt: -1 }, replaces: 'idx_code_user_problem' },
  { coll: 'code_submissions', name: 'userId_1_platform_1_externalId_1', key: { userId: 1, platform: 1, externalId: 1 },
    replaces: 'idx_code_user_external' },
  { coll: 'cf_submissions', name: 'cfSubmissionId_1', key: { cfSubmissionId: 1 }, unique: true,
    retire: 'idx_cf_user_submission' },
];

// Keeps (userId, cfSubmissionId) unique while its legacy index is swapped for the named one:
// MongoDB refuses a second index on the same key, but the reversed order is a different key that
// enforces the same uniqueness.
const GUARD = { coll: 'cf_submissions', name: 'p7_guard_cf_user_submission', key: { cfSubmissionId: 1, userId: 1 }, unique: true };

// The unique keys whose duplicates would stop index creation, and what may be exported of them.
const UNIQUE = [
  { coll: 'personal_files', index: 'idx_files_user_name', fields: ['userId', 'name'], file: 'personal_files.conflicts.jsonl',
    projection: { content: 0 } },
  { coll: 'contest_file_rules', index: 'idx_file_rule_contest', fields: ['platform', 'contestId'],
    file: 'contest_file_rules.conflicts.jsonl', projection: null },
  { coll: 'cf_submissions', index: 'idx_cf_user_submission', fields: ['userId', 'cfSubmissionId'],
    file: 'cf_submissions.conflicts.jsonl', projection: null },
];

const say = text => print('SAY ' + text);
const exportLine = (file, doc) => print('EXPORT ' + file + ' ' + EJSON.stringify(doc, { relaxed: false }));
const status = word => print('STATUS ' + word);

// Options other than name, key, version and unique: any of them makes a definition "different".
const EXTRA = ['sparse', 'partialFilterExpression', 'collation', 'expireAfterSeconds', 'hidden', 'weights',
  'default_language', 'wildcardProjection'];

function sameKey(a, b) {
  const ka = Object.keys(a), kb = Object.keys(b);
  return ka.length === kb.length && ka.every((k, i) => k === kb[i] && Number(a[k]) === Number(b[k]));
}
function sameDefinition(index, spec) {
  return sameKey(index.key, spec.key) && !!index.unique === !!spec.unique && EXTRA.every(o => !(o in index));
}
function describe(key) {
  return Object.entries(key).map(([f, d]) => (Number(d) === -1 ? f + ' desc' : f)).join(', ');
}
function exists(coll) {
  return app.getCollectionNames().includes(coll);
}
function indexesOf(coll) {
  return exists(coll) ? app.getCollection(coll).getIndexes() : [];
}
function find(coll, name) {
  return indexesOf(coll).find(i => i.name === name);
}

// -- writers ---------------------------------------------------------------------------------------
// Any connection from the backend (any version: the Java driver) or from its own account. Idle
// connections count: a pool that is open can write at any moment.
function writers() {
  const ops = db.getSiblingDB('admin').aggregate([{ $currentOp: { allUsers: true, idleConnections: true } }]).toArray();
  const seen = new Set();
  for (const op of ops) {
    const driver = (((op.clientMetadata || {}).driver) || {}).name || '';
    const users = (op.effectiveUsers || []).map(u => u.user);
    if (/java/i.test(driver) || users.includes(PARAMS.appUser)) {
      seen.add((driver || 'unknown driver') + (users.length ? ' as ' + users.join(',') : ''));
    }
  }
  return [...seen];
}

// -- duplicates -----------------------------------------------------------------------------------
// Grouped as a unique index sees them: a missing field and null are the same key.
function duplicateGroups(u) {
  if (!exists(u.coll)) return [];
  const id = {};
  u.fields.forEach((f, i) => { id['k' + i] = { $ifNull: ['$' + f, null] }; });
  return app.getCollection(u.coll).aggregate([
    { $group: { _id: id, ids: { $push: '$_id' }, n: { $sum: 1 } } },
    { $match: { n: { $gt: 1 } } },
  ], { allowDiskUse: true }).toArray();
}

function duplicateReport(exporting) {
  let total = 0;
  for (const u of UNIQUE) {
    const groups = duplicateGroups(u);
    const docs = groups.reduce((s, g) => s + g.n, 0);
    total += groups.length;
    say(groups.length === 0
      ? `  ${u.coll} (${u.fields.join(', ')}; ${u.index}): no duplicates`
      : `  ${u.coll} (${u.fields.join(', ')}; ${u.index}): ${groups.length} duplicated key(s), ${docs} documents`);
    if (exporting) {
      for (const g of groups) {
        // mongosh's find() takes the projection itself as its second argument.
        for (const doc of app.getCollection(u.coll).find({ _id: { $in: g.ids } }, u.projection || {}).toArray()) {
          exportLine(u.file, doc);
        }
      }
    }
  }
  return total;
}

// -- indexes --------------------------------------------------------------------------------------
// What each managed index needs, without changing anything. UNSAFE entries stop everything.
function indexPlan() {
  const plan = [];
  for (const spec of DESIRED) {
    const all = indexesOf(spec.coll);
    const named = all.find(i => i.name === spec.name);
    const legacy = LEGACY.find(l => l.replaces === spec.name);
    const sameKeyed = all.filter(i => i.name !== spec.name && sameKey(i.key, spec.key));
    const where = `${spec.coll}.${spec.name} (${describe(spec.key)}${spec.unique ? '; unique' : ''})`;
    if (named && sameDefinition(named, spec)) {
      plan.push({ spec, action: 'ok', text: `${where}: in place` });
    } else if (named) {
      plan.push({ spec, action: 'unsafe', text: `${where}: an index of that name exists with another definition (${describe(named.key)}${named.unique ? '; unique' : ''}) - not changed; resolve by hand` });
    } else if (sameKeyed.length === 0) {
      plan.push({ spec, action: 'create', text: `${where}: missing - will be created` });
    } else if (sameKeyed.length === 1 && legacy && sameKeyed[0].name === legacy.name && sameDefinition(sameKeyed[0], legacy)) {
      plan.push({ spec, action: 'replace', legacy, text: `${where}: held as legacy ${legacy.name} - will be renamed (dropped and created${spec.unique ? ', uniqueness kept by a guard index meanwhile' : ''})` });
    } else {
      plan.push({ spec, action: 'unsafe', text: `${where}: the same key is held by ${sameKeyed.map(i => i.name).join(', ')}, which is not a known earlier definition - not changed; resolve by hand` });
    }
  }
  const guard = find(GUARD.coll, GUARD.name);
  if (guard && !sameDefinition(guard, GUARD)) {
    plan.push({ spec: GUARD, action: 'unsafe', text: `${GUARD.coll}.${GUARD.name}: exists with an unexpected definition - not changed` });
  } else if (guard) {
    plan.push({ spec: GUARD, action: 'leftover', text: `${GUARD.coll}.${GUARD.name}: left by an interrupted run - will be dropped once ${'idx_cf_user_submission'} is in place` });
  }
  for (const l of LEGACY.filter(x => x.retire)) {
    const found = find(l.coll, l.name);
    if (found && sameDefinition(found, l)) {
      plan.push({ spec: l, action: 'retire', text: `${l.coll}.${l.name} (${describe(l.key)}; unique, across all users): an earlier release's global key - will be dropped once ${l.retire} is in place` });
    } else if (found) {
      plan.push({ spec: l, action: 'note', text: `${l.coll}.${l.name}: not the definition an earlier release made - left alone` });
    }
  }
  return plan;
}

// The index of that name, if it is exactly the given definition; throws if it is anything else.
function dropExact(spec) {
  const found = find(spec.coll, spec.name);
  if (!found) return false;
  if (!sameDefinition(found, spec)) throw new Error(`${spec.coll}.${spec.name} changed under us; not dropped`);
  app.getCollection(spec.coll).dropIndex(spec.name);
  say(`  dropped ${spec.coll}.${spec.name}`);
  return true;
}
function createExact(spec) {
  const found = find(spec.coll, spec.name);
  if (found) {
    if (!sameDefinition(found, spec)) throw new Error(`${spec.coll}.${spec.name} exists with another definition`);
    return false;
  }
  const options = { name: spec.name };
  if (spec.unique) options.unique = true;
  app.getCollection(spec.coll).createIndex(spec.key, options);
  say(`  created ${spec.coll}.${spec.name}`);
  return true;
}

function apply(plan) {
  for (const step of plan) {
    const spec = step.spec;
    if (step.action === 'create') createExact(spec);
    if (step.action === 'replace') {
      if (spec.unique) createExact(GUARD);
      dropExact(step.legacy);
      createExact(spec);
    }
  }
  // Any guard (made above, or left by an interrupted run) goes once the named index is in place.
  const named = find('cf_submissions', 'idx_cf_user_submission');
  if (named && sameDefinition(named, DESIRED[0])) {
    dropExact(GUARD);
    for (const l of LEGACY.filter(x => x.retire)) dropExact(l);
  }
}

// -- personal file renames ------------------------------------------------------------------------
const MAX_NAME = 120;   // PersonalFileService.MAX_NAME_LENGTH

function splitName(name) {
  const dot = name.lastIndexOf('.');
  const [stem, ext] = dot > 0 ? [name.slice(0, dot), name.slice(dot)] : [name, ''];
  // "notes (2)" counts on from 2 rather than becoming "notes (2) (2)".
  const m = /^(.*\S) \((\d+)\)$/.exec(stem);
  return { base: m ? m[1] : stem, ext };
}
function candidate(base, ext, k) {
  const suffix = ` (${k})`;
  const room = MAX_NAME - suffix.length - ext.length;
  return base.slice(0, Math.max(1, room)) + suffix + ext;
}

// Every duplicate (userId, name) but the first - earliest upload, then lowest _id - gets the next
// free "name (k).ext" among all of that user's files, existing and newly given.
function renamePlan() {
  const plan = [], manual = [];
  for (const g of duplicateGroups(UNIQUE[0])) {
    const [userId, name] = [g._id.k0, g._id.k1];
    if (userId === null || typeof name !== 'string' || name.length === 0) {
      manual.push(g);
      continue;
    }
    const files = app.personal_files.find({ _id: { $in: g.ids } }, { content: 0 })
      .sort({ uploadedAt: 1, _id: 1 }).toArray();
    const taken = new Set(app.personal_files.distinct('name', { userId: userId }));
    const { base, ext } = splitName(name);
    let k = 2;
    for (const f of files.slice(1)) {
      while (taken.has(candidate(base, ext, k))) k++;
      const to = candidate(base, ext, k);
      taken.add(to);
      plan.push({ _id: f._id, userId: userId, from: name, to: to });
    }
  }
  return { plan, manual };
}

// -- main -----------------------------------------------------------------------------------------
function run() {
  const mode = PARAMS.mode;
  if (mode === 'rename-apply') {
    const live = writers();
    if (live.length) { say('Writers are connected: ' + live.join('; ')); return 'WRITERS'; }
    let done = 0;
    // Each record as planned, in Extended JSON, so _id and userId keep their BSON types.
    for (const line of PARAMS.renames) {
      const r = EJSON.parse(line, { relaxed: false });
      if (app.personal_files.findOne({ userId: r.userId, name: r.to })) {
        say(`  skipped record ${r.seq}: its new name is taken now`);
        continue;
      }
      done += app.personal_files.updateOne({ _id: r._id, userId: r.userId, name: r.from },
        { $set: { name: r.to } }).modifiedCount;
    }
    say(`  renamed ${done} of ${PARAMS.renames.length} file(s); a record already renamed is not renamed again`);
    return 'RENAMED';
  }

  say('Duplicates against the unique indexes:');
  const duplicates = duplicateReport(mode === 'export');
  say('Indexes:');
  const plan = indexPlan();
  plan.forEach(p => say('  ' + p.text));
  const unsafe = plan.filter(p => p.action === 'unsafe');
  const live = writers();
  say(live.length ? 'Connected writers: ' + live.join('; ') : 'Connected writers: none');

  if (mode === 'rename-plan') {
    const { plan: renames, manual } = renamePlan();
    renames.forEach((r, i) => exportLine('RENAME', Object.assign({ seq: i + 1 }, r)));
    say(`Renames planned: ${renames.length}` + (manual.length
      ? `; ${manual.length} group(s) without a usable user or name need resolving by hand` : ''));
    return manual.length ? 'UNSAFE' : 'PLANNED';
  }
  if (unsafe.length) return 'UNSAFE';
  if (duplicates) return 'DUPLICATES';
  if (mode !== 'apply') return 'READY';
  if (live.length) return 'WRITERS';

  say('Applying:');
  apply(plan);
  const left = indexPlan().filter(p => !['ok', 'note'].includes(p.action));
  if (left.length) {
    left.forEach(p => say('  still to do: ' + p.text));
    return 'UNSAFE';
  }
  say('  every managed index is in place');
  return 'APPLIED';
}

try {
  status(run());
} catch (e) {
  say('Stopped: ' + (e && e.message ? e.message : String(e)));
  status('UNSAFE');
}

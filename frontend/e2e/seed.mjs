// Seeds the smoke suite's throwaway backend. Credentials come from the environment the runner
// generated for this run; nothing here is a real account.
//
//   node e2e/seed.mjs accounts <out dir>   create the accounts, write <out dir>/mongo-seed.js
//   node e2e/seed.mjs refresh              rebuild the student's analytics from that history
//
// env: E2E_API (backend base, e.g. http://127.0.0.1:18100), E2E_ADMIN_PASSWORD (the bootstrap
// super admin's), E2E_STUDENT_PASSWORD, E2E_ADMIN2_PASSWORD
import fs from 'node:fs'
import path from 'node:path'
import { submissions } from './fixtures.mjs'

const API = `${process.env.E2E_API ?? ''}/api/v1`
const need = name => {
  const v = process.env[name]
  if (!v) { console.error(`${name} is not set`); process.exit(2) }
  return v
}

async function call(method, url, body, token) {
  const res = await fetch(API + url, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let data = null
  try { data = JSON.parse(text) } catch { /* not JSON */ }
  if (!res.ok) throw new Error(`${method} ${url} -> ${res.status} ${data?.message ?? ''}`)
  return data?.data
}

const login = (identifier, password) => call('POST', '/auth/login', { identifier, password }).then(d => d.accessToken)

/**
 * The bootstrap super admin is created by an ApplicationRunner, which runs after the backend logs
 * that it has started, so the first sign-in can arrive before the account exists. Waits for it.
 */
async function bootstrapAdmin(password) {
  for (let i = 0; ; i++) {
    try {
      return await login('superadmin', password)
    } catch (e) {
      if (i >= 30) throw e
      await new Promise(resolve => setTimeout(resolve, 1000))
    }
  }
}

const [cmd, outDir] = process.argv.slice(2)
if (cmd === 'accounts') {
  if (!outDir) { console.error('usage: seed.mjs accounts <out dir>'); process.exit(2) }
  need('E2E_API')
  const admin = await bootstrapAdmin(need('E2E_ADMIN_PASSWORD'))
  const student = await call('POST', '/admin/users', {
    username: 'e2e_student', email: 'e2e_student@cpintel.test', password: need('E2E_STUDENT_PASSWORD'), role: 'USER',
  }, admin)
  await call('POST', '/admin/users', {
    username: 'e2e_admin', email: 'e2e_admin@cpintel.test', password: need('E2E_ADMIN2_PASSWORD'), role: 'ADMIN',
  }, admin)
  const docs = submissions(student.userId, Date.now())
  const js = [
    "db = db.getSiblingDB('cpintel');",
    `db.cf_submissions.insertMany(${JSON.stringify(docs)}.map(d => ({ ...d, submittedAt: new Date(d.submittedAt) })));`,
    `print('seeded ' + db.cf_submissions.countDocuments({ userId: ${student.userId} }) + ' submissions');`,
  ].join('\n')
  fs.writeFileSync(path.join(outDir, 'mongo-seed.js'), js)
  console.log(`accounts created; ${docs.length} submissions ready for user ${student.userId}`)
} else if (cmd === 'refresh') {
  need('E2E_API')
  const token = await login('e2e_student', need('E2E_STUDENT_PASSWORD'))
  await call('POST', '/analytics/refresh', undefined, token)
  console.log('analytics refreshed')
} else {
  console.error('usage: seed.mjs accounts <out dir> | refresh')
  process.exit(2)
}

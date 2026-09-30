-- DOMjudge logins move from Redis into the enrolment they belong to.
--
-- In Redis a login had a fixed 30-day expiry that nothing renewed, so a class imported at the
-- start of a semester lost its logins partway through it, possibly during an examination. Redis
-- was also not in the backups, and under memory pressure could evict a login outright. Here
-- the login lives as long as the student is enrolled, and is backed up with everything else.
--
-- The value is sealed (AES-256-GCM under CPINTEL_DOMJUDGE_CREDENTIAL_KEY, see SecretBox), so a
-- database dump without that key yields nothing, as with a classroom's service password.
-- ClassroomBootstrap moves logins already in Redis across at startup.

ALTER TABLE classroom_members
    ADD COLUMN domjudge_login       TEXT,
    ADD COLUMN domjudge_attached_at TIMESTAMPTZ;

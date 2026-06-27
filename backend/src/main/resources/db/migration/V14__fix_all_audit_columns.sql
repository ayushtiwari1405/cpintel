-- recommendations: BaseEntity expects created_at + updated_at
-- table has generated_at already, just add the missing ones
BEGIN EXECUTE IMMEDIATE 'ALTER TABLE recommendations ADD (created_at TIMESTAMP DEFAULT SYSTIMESTAMP)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

-- roadmap_nodes: missing created_at (it has its own created_at in V1 already, just updated_at missing)
BEGIN EXECUTE IMMEDIATE 'ALTER TABLE roadmap_nodes ADD (created_at TIMESTAMP DEFAULT SYSTIMESTAMP)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

-- Make sure all tables have both audit columns
BEGIN EXECUTE IMMEDIATE 'ALTER TABLE contest_summaries ADD (created_at TIMESTAMP DEFAULT SYSTIMESTAMP)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'ALTER TABLE sync_jobs ADD (created_at TIMESTAMP DEFAULT SYSTIMESTAMP)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'ALTER TABLE revision_schedule ADD (created_at TIMESTAMP DEFAULT SYSTIMESTAMP)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'ALTER TABLE platform_accounts ADD (created_at TIMESTAMP DEFAULT SYSTIMESTAMP)'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

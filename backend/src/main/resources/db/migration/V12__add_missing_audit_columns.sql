-- contest_summaries already has created_at but not updated_at
ALTER TABLE contest_summaries ADD (updated_at TIMESTAMP);

-- sync_jobs already has created_at but not updated_at  
ALTER TABLE sync_jobs ADD (updated_at TIMESTAMP);

-- roadmap_nodes already has created_at but not updated_at
ALTER TABLE roadmap_nodes ADD (updated_at TIMESTAMP);

-- recommendations already has created_at but not updated_at (via BaseEntity)
ALTER TABLE recommendations ADD (updated_at TIMESTAMP);

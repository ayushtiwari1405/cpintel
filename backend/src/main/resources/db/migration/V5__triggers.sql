CREATE OR REPLACE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
BEGIN
    :NEW.updated_at := SYSTIMESTAMP;
END;
/

CREATE OR REPLACE TRIGGER trg_topic_mastery_computed_at
    BEFORE UPDATE ON topic_mastery
    FOR EACH ROW
BEGIN
    :NEW.computed_at := SYSTIMESTAMP;
END;
/

-- Trigger: auto-create unified_scores row when user is created
CREATE OR REPLACE TRIGGER trg_users_create_unified_score
    AFTER INSERT ON users
    FOR EACH ROW
BEGIN
    INSERT INTO unified_scores (user_id)
    VALUES (:NEW.user_id);
END;
/

-- Trigger: auto-create roadmap skeleton when user is created
CREATE OR REPLACE TRIGGER trg_users_create_roadmap
    AFTER INSERT ON users
    FOR EACH ROW
DECLARE
    v_topics SYS.ODCIVARCHAR2LIST := SYS.ODCIVARCHAR2LIST(
        'Arrays','Strings','Binary Search','Two Pointers',
        'Greedy','Dynamic Programming','Graphs','Trees',
        'Segment Trees','Binary Lifting','Number Theory',
        'Bit Manipulation','Tries','Geometry'
    );
BEGIN
    FOR i IN 1..v_topics.COUNT LOOP
        INSERT INTO roadmap_nodes (user_id, topic, status, order_index)
        VALUES (:NEW.user_id, v_topics(i),
                CASE WHEN i <= 4 THEN 'UNLOCKED' ELSE 'LOCKED' END,
                i);
    END LOOP;
END;
/

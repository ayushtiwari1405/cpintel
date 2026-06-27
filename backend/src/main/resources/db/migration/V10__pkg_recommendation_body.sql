CREATE OR REPLACE PACKAGE BODY pkg_recommendation AS

    -- Score how well a problem fits a user (0-100)
    FUNCTION score_problem_fit(p_user_id NUMBER, p_topic VARCHAR2, p_difficulty NUMBER) RETURN NUMBER IS
        v_mastery   NUMBER := 0;
        v_score     NUMBER := 0;
    BEGIN
        BEGIN
            SELECT mastery_score INTO v_mastery
            FROM topic_mastery
            WHERE user_id = p_user_id AND topic = p_topic;
        EXCEPTION WHEN NO_DATA_FOUND THEN v_mastery := 0;
        END;

        -- Ideal difficulty is slightly above mastery (challenge zone: mastery + 10-25)
        DECLARE
            v_target_diff NUMBER := v_mastery + 17;
            v_diff_gap    NUMBER := ABS(p_difficulty - v_target_diff);
        BEGIN
            -- Closer to target difficulty = higher score
            v_score := GREATEST(0, 100 - (v_diff_gap * 1.5));
            -- Boost weak topics
            IF v_mastery < 40 THEN v_score := v_score * 1.2; END IF;
            RETURN ROUND(LEAST(100, v_score), 2);
        END;
    EXCEPTION
        WHEN OTHERS THEN RETURN 50;
    END score_problem_fit;

    -- Generate daily problem sheet (5-7 problems)
    PROCEDURE generate_daily_sheet(p_user_id NUMBER) IS
        v_weak_topic    VARCHAR2(100);
        v_rec_json      CLOB := '[';
        v_first         BOOLEAN := TRUE;

        CURSOR c_weak_topics IS
            SELECT topic, mastery_score
            FROM topic_mastery
            WHERE user_id = p_user_id
            ORDER BY mastery_score ASC
            FETCH FIRST 3 ROWS ONLY;
    BEGIN
        -- Expire old daily sheets
        UPDATE recommendations
        SET is_consumed = 1
        WHERE user_id = p_user_id
          AND rec_type = 'DAILY'
          AND is_consumed = 0;

        -- Build JSON array of recommended topics with target difficulty
        FOR rec IN c_weak_topics LOOP
            IF NOT v_first THEN v_rec_json := v_rec_json || ','; END IF;
            v_rec_json := v_rec_json || '{"topic":"' || rec.topic ||
                '","targetDifficulty":' || ROUND(rec.mastery_score + 200) ||
                ',"reason":"Weak area — mastery ' || ROUND(rec.mastery_score) || '%"}';
            v_first := FALSE;
        END LOOP;
        v_rec_json := v_rec_json || ']';

        INSERT INTO recommendations (user_id, rec_type, problem_list, generated_at, expires_at)
        VALUES (
            p_user_id, 'DAILY', v_rec_json,
            SYSTIMESTAMP,
            TRUNC(SYSTIMESTAMP) + INTERVAL '1' DAY + INTERVAL '6' HOUR
        );

        COMMIT;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            DBMS_OUTPUT.PUT_LINE('generate_daily_sheet error: ' || SQLERRM);
    END generate_daily_sheet;

    -- Generate weekly practice plan
    PROCEDURE generate_weekly_sheet(p_user_id NUMBER) IS
        v_rec_json CLOB := '[';
        v_first    BOOLEAN := TRUE;

        CURSOR c_topics IS
            SELECT topic, mastery_score, decay_score
            FROM topic_mastery
            WHERE user_id = p_user_id
            ORDER BY (mastery_score + decay_score) ASC
            FETCH FIRST 7 ROWS ONLY;
    BEGIN
        UPDATE recommendations
        SET is_consumed = 1
        WHERE user_id = p_user_id
          AND rec_type = 'WEEKLY'
          AND is_consumed = 0;

        FOR rec IN c_topics LOOP
            IF NOT v_first THEN v_rec_json := v_rec_json || ','; END IF;
            v_rec_json := v_rec_json ||
                '{"topic":"' || rec.topic ||
                '","masteryScore":' || ROUND(rec.mastery_score) ||
                ',"decayScore":' || ROUND(rec.decay_score) ||
                ',"priority":"' || CASE
                    WHEN rec.decay_score > 20 THEN 'REVISION'
                    WHEN rec.mastery_score < 30 THEN 'NEW'
                    ELSE 'PRACTICE'
                END || '"}';
            v_first := FALSE;
        END LOOP;
        v_rec_json := v_rec_json || ']';

        INSERT INTO recommendations (user_id, rec_type, problem_list, generated_at, expires_at)
        VALUES (
            p_user_id, 'WEEKLY', v_rec_json,
            SYSTIMESTAMP,
            SYSTIMESTAMP + INTERVAL '7' DAY
        );
        COMMIT;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            DBMS_OUTPUT.PUT_LINE('generate_weekly_sheet error: ' || SQLERRM);
    END generate_weekly_sheet;

    -- Generate spaced-repetition revision schedule
    PROCEDURE generate_revision_schedule(p_user_id NUMBER) IS
        CURSOR c_due IS
            SELECT topic, decay_score, mastery_score
            FROM topic_mastery
            WHERE user_id = p_user_id
              AND decay_score > 10
            ORDER BY decay_score DESC;
        v_next_date     TIMESTAMP;
        v_interval      NUMBER;
        v_ease          NUMBER;
    BEGIN
        FOR rec IN c_due LOOP
            -- SM-2 inspired interval calculation
            v_ease     := GREATEST(1.3, 2.5 - (rec.decay_score / 100));
            v_interval := GREATEST(1, ROUND(rec.mastery_score / 20 * v_ease));

            v_next_date := SYSTIMESTAMP + (v_interval * INTERVAL '1' DAY);

            MERGE INTO revision_schedule rs
            USING (SELECT p_user_id AS uid, rec.topic AS t FROM DUAL) src
            ON (rs.user_id = src.uid AND rs.topic = src.t)
            WHEN MATCHED THEN UPDATE SET
                next_revision_at  = v_next_date,
                decay_score       = rec.decay_score,
                revision_priority = ROUND(rec.decay_score),
                interval_days     = v_interval,
                ease_factor       = v_ease
            WHEN NOT MATCHED THEN INSERT
                (user_id, topic, next_revision_at, revision_priority,
                 decay_score, interval_days, ease_factor)
            VALUES
                (p_user_id, rec.topic, v_next_date, ROUND(rec.decay_score),
                 rec.decay_score, v_interval, v_ease);
        END LOOP;
        COMMIT;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            DBMS_OUTPUT.PUT_LINE('generate_revision_schedule error: ' || SQLERRM);
    END generate_revision_schedule;

END pkg_recommendation;
/

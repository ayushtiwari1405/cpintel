CREATE OR REPLACE PACKAGE BODY pkg_analytics AS

    FUNCTION calculate_mastery(p_user_id NUMBER, p_topic VARCHAR2) RETURN NUMBER IS
        v_solved        NUMBER := 0;
        v_attempted     NUMBER := 0;
        v_accuracy      NUMBER := 0;
        v_recency_days  NUMBER := 999;
        v_mastery       NUMBER := 0;
        v_weight_acc    CONSTANT NUMBER := 0.40;
        v_weight_vol    CONSTANT NUMBER := 0.35;
        v_weight_rec    CONSTANT NUMBER := 0.25;
    BEGIN
        SELECT
            NVL(problems_solved, 0),
            NVL(problems_attempted, 0),
            NVL(ROUND((SYSDATE - CAST(last_practiced_at AS DATE))), 999)
        INTO v_solved, v_attempted, v_recency_days
        FROM topic_mastery
        WHERE user_id = p_user_id AND topic = p_topic;

        IF v_attempted > 0 THEN
            v_accuracy := LEAST(100, (v_solved / v_attempted) * 100);
        END IF;

        DECLARE
            -- Volume now saturates near 100% only after ~1500 solves (log base ~1500
            -- instead of 52), so high-fanout topics like Arrays/Greedy don't auto-max
            -- at the same level as a topic with 50 solves.
            v_vol NUMBER := LEAST(100, LN(GREATEST(v_solved, 1) + 1) / LN(1500) * 100);
        BEGIN
            DECLARE
                v_rec NUMBER := GREATEST(0, 100 - (v_recency_days / 90.0 * 100));
            BEGIN
                v_mastery := (v_accuracy * v_weight_acc)
                           + (v_vol      * v_weight_vol)
                           + (v_rec      * v_weight_rec);
                v_mastery := LEAST(100, GREATEST(0, v_mastery));
            END;
        END;

        RETURN ROUND(v_mastery, 2);
    EXCEPTION
        WHEN NO_DATA_FOUND THEN RETURN 0;
        WHEN OTHERS THEN
            DBMS_OUTPUT.PUT_LINE('calculate_mastery error: ' || SQLERRM);
            RETURN 0;
    END calculate_mastery;

    FUNCTION calculate_accuracy(p_user_id NUMBER, p_platform VARCHAR2) RETURN NUMBER IS
        v_total   NUMBER := 0;
        v_correct NUMBER := 0;
    BEGIN
        SELECT
            COUNT(*),
            SUM(CASE WHEN rating_change > 0 THEN 1 ELSE 0 END)
        INTO v_total, v_correct
        FROM contest_summaries
        WHERE user_id = p_user_id
          AND (p_platform = 'ALL' OR platform = p_platform);

        IF v_total = 0 THEN RETURN 0; END IF;
        RETURN ROUND((v_correct / v_total) * 100, 2);
    EXCEPTION
        WHEN OTHERS THEN RETURN 0;
    END calculate_accuracy;

    FUNCTION calculate_consistency(p_user_id NUMBER) RETURN NUMBER IS
        v_stddev    NUMBER := 0;
        v_count     NUMBER := 0;
        v_score     NUMBER := 0;
    BEGIN
        SELECT
            COUNT(*),
            NVL(STDDEV(rating_change), 0)
        INTO v_count, v_stddev
        FROM contest_summaries
        WHERE user_id = p_user_id;

        IF v_count < 3 THEN RETURN 50; END IF;

        v_score := GREATEST(0, 100 - (v_stddev / 2));
        RETURN ROUND(LEAST(100, v_score), 2);
    EXCEPTION
        WHEN OTHERS THEN RETURN 50;
    END calculate_consistency;

    -- Decay: how much of the mastery score has faded due to inactivity.
    -- 30-day half-life exponential decay from last_practiced_at.
    FUNCTION calculate_decay(p_mastery_id NUMBER) RETURN NUMBER IS
        v_last_practiced    TIMESTAMP;
        v_mastery_score     NUMBER;
        v_days_since        NUMBER;
        v_decay             NUMBER;
        v_half_life_days    CONSTANT NUMBER := 30;
    BEGIN
        SELECT last_practiced_at, mastery_score
        INTO v_last_practiced, v_mastery_score
        FROM topic_mastery
        WHERE mastery_id = p_mastery_id;

        IF v_last_practiced IS NULL THEN RETURN 0; END IF;

        v_days_since := ROUND(SYSDATE - CAST(v_last_practiced AS DATE));
        IF v_days_since <= 0 THEN RETURN 0; END IF;

        v_decay := v_mastery_score * (1 - POWER(0.5, v_days_since / v_half_life_days));
        RETURN ROUND(LEAST(v_mastery_score, GREATEST(0, v_decay)), 2);
    EXCEPTION
        WHEN NO_DATA_FOUND THEN RETURN 0;
        WHEN OTHERS THEN RETURN 0;
    END calculate_decay;

    -- Confidence: how reliable the mastery number is, based on sample size.
    -- A topic with only 5 attempts is "low confidence" even if accuracy is 100%;
    -- a topic with 200+ attempts is high confidence regardless of the score itself.
    FUNCTION calculate_confidence(p_user_id NUMBER, p_topic VARCHAR2) RETURN NUMBER IS
        v_attempted NUMBER := 0;
        v_confidence NUMBER := 0;
    BEGIN
        SELECT NVL(problems_attempted, 0)
        INTO v_attempted
        FROM topic_mastery
        WHERE user_id = p_user_id AND topic = p_topic;

        -- Saturates to 100% confidence at 80 attempts; sqrt curve so early
        -- attempts move confidence quickly, later ones add diminishing certainty.
        v_confidence := LEAST(100, SQRT(GREATEST(v_attempted, 0) / 80) * 100);
        RETURN ROUND(v_confidence, 2);
    EXCEPTION
        WHEN NO_DATA_FOUND THEN RETURN 0;
        WHEN OTHERS THEN RETURN 0;
    END calculate_confidence;

    PROCEDURE refresh_all_mastery(p_user_id NUMBER) IS
        CURSOR c_topics IS
            SELECT mastery_id, topic
            FROM topic_mastery
            WHERE user_id = p_user_id;
        v_new_mastery    NUMBER;
        v_new_decay      NUMBER;
        v_new_confidence NUMBER;
    BEGIN
        FOR rec IN c_topics LOOP
            v_new_mastery    := calculate_mastery(p_user_id, rec.topic);
            v_new_decay      := calculate_decay(rec.mastery_id);
            v_new_confidence := calculate_confidence(p_user_id, rec.topic);

            UPDATE topic_mastery
            SET
                mastery_score    = v_new_mastery,
                decay_score      = v_new_decay,
                confidence_score = v_new_confidence,
                revision_score   = GREATEST(0, v_new_mastery - v_new_decay),
                computed_at      = SYSTIMESTAMP
            WHERE mastery_id = rec.mastery_id;
        END LOOP;

        UPDATE unified_scores
        SET unified_score = pkg_unified_rating.compute_unified_score(p_user_id),
            computed_at   = SYSTIMESTAMP
        WHERE user_id = p_user_id;

        COMMIT;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            DBMS_OUTPUT.PUT_LINE('refresh_all_mastery error: ' || SQLERRM);
    END refresh_all_mastery;

END pkg_analytics;
/

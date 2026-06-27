CREATE OR REPLACE PACKAGE BODY pkg_unified_rating AS

    -- Normalize a platform rating to 0-1000 unified scale using percentile approximation
    FUNCTION normalize_rating(p_platform VARCHAR2, p_rating NUMBER) RETURN NUMBER IS
        v_min       NUMBER;
        v_max       NUMBER;
        v_normalized NUMBER;
    BEGIN
        -- Approximate rating ranges per platform
        CASE p_platform
            WHEN 'CODEFORCES' THEN v_min := 0;    v_max := 4000;
            WHEN 'LEETCODE'   THEN v_min := 1400;  v_max := 4000;
            WHEN 'CODECHEF'   THEN v_min := 0;    v_max := 3500;
            ELSE v_min := 0; v_max := 3000;
        END CASE;

        IF p_rating IS NULL OR p_rating <= 0 THEN RETURN 0; END IF;
        v_normalized := ((p_rating - v_min) / GREATEST(v_max - v_min, 1)) * 1000;
        RETURN ROUND(LEAST(1000, GREATEST(0, v_normalized)), 2);
    END normalize_rating;

    -- Compute unified score for one user using weighted normalized ratings
    FUNCTION compute_unified_score(p_user_id NUMBER) RETURN NUMBER IS
        v_cf_rating     NUMBER := 0;
        v_lc_rating     NUMBER := 0;
        v_cc_rating     NUMBER := 0;
        v_cf_weight     NUMBER;
        v_lc_weight     NUMBER;
        v_cc_weight     NUMBER;
        v_cf_norm       NUMBER := 0;
        v_lc_norm       NUMBER := 0;
        v_cc_norm       NUMBER := 0;
        v_total_weight  NUMBER := 0;
        v_score         NUMBER := 0;
        v_platforms_active NUMBER := 0;
    BEGIN
        -- Get weights from unified_scores table
        SELECT cf_weight, lc_weight, cc_weight
        INTO v_cf_weight, v_lc_weight, v_cc_weight
        FROM unified_scores
        WHERE user_id = p_user_id;

        -- Get current ratings from linked accounts
        BEGIN
            SELECT current_rating INTO v_cf_rating FROM platform_accounts
            WHERE user_id = p_user_id AND platform = 'CODEFORCES' AND is_active = 1;
            v_cf_norm := normalize_rating('CODEFORCES', v_cf_rating);
            v_platforms_active := v_platforms_active + 1;
            v_total_weight := v_total_weight + v_cf_weight;
        EXCEPTION WHEN NO_DATA_FOUND THEN NULL;
        END;

        BEGIN
            SELECT current_rating INTO v_lc_rating FROM platform_accounts
            WHERE user_id = p_user_id AND platform = 'LEETCODE' AND is_active = 1;
            v_lc_norm := normalize_rating('LEETCODE', v_lc_rating);
            v_platforms_active := v_platforms_active + 1;
            v_total_weight := v_total_weight + v_lc_weight;
        EXCEPTION WHEN NO_DATA_FOUND THEN NULL;
        END;

        BEGIN
            SELECT current_rating INTO v_cc_rating FROM platform_accounts
            WHERE user_id = p_user_id AND platform = 'CODECHEF' AND is_active = 1;
            v_cc_norm := normalize_rating('CODECHEF', v_cc_rating);
            v_platforms_active := v_platforms_active + 1;
            v_total_weight := v_total_weight + v_cc_weight;
        EXCEPTION WHEN NO_DATA_FOUND THEN NULL;
        END;

        IF v_platforms_active = 0 OR v_total_weight = 0 THEN RETURN 0; END IF;

        -- Weighted average, re-normalized by active platform weights
        v_score := (
            (v_cf_norm * v_cf_weight) +
            (v_lc_norm * v_lc_weight) +
            (v_cc_norm * v_cc_weight)
        ) / v_total_weight;

        -- Update component scores in unified_scores
        UPDATE unified_scores
        SET cf_score   = v_cf_norm,
            lc_score   = v_lc_norm,
            cc_score   = v_cc_norm,
            unified_score = ROUND(v_score, 2),
            computed_at   = SYSTIMESTAMP
        WHERE user_id = p_user_id;

        RETURN ROUND(v_score, 2);
    EXCEPTION
        WHEN NO_DATA_FOUND THEN RETURN 0;
        WHEN OTHERS THEN
            DBMS_OUTPUT.PUT_LINE('compute_unified_score error: ' || SQLERRM);
            RETURN 0;
    END compute_unified_score;

    -- Batch update all users
    PROCEDURE update_all_scores IS
        CURSOR c_users IS SELECT user_id FROM users WHERE is_active = 1;
        v_score NUMBER;
    BEGIN
        FOR rec IN c_users LOOP
            BEGIN
                v_score := compute_unified_score(rec.user_id);
            EXCEPTION
                WHEN OTHERS THEN
                    DBMS_OUTPUT.PUT_LINE('Failed for user ' || rec.user_id || ': ' || SQLERRM);
            END;
        END LOOP;
        COMMIT;
    END update_all_scores;

END pkg_unified_rating;
/

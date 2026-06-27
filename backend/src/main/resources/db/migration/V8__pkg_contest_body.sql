CREATE OR REPLACE PACKAGE BODY pkg_contest AS

    PROCEDURE analyze_contest(p_user_id NUMBER, p_contest_id NUMBER) IS
        v_rank              NUMBER;
        v_rating_change     NUMBER;
        v_wrong_subs        NUMBER;
        v_first_solve       NUMBER;
        v_solved            NUMBER;
        v_total             NUMBER;
        v_insight           VARCHAR2(1000);
    BEGIN
        SELECT rank, rating_change, wrong_submissions,
               first_solve_mins, problems_solved, total_problems
        INTO v_rank, v_rating_change, v_wrong_subs,
             v_first_solve, v_solved, v_total
        FROM contest_summaries
        WHERE contest_id = p_contest_id AND user_id = p_user_id;

        -- Generate insight text
        IF v_wrong_subs > 3 THEN
            v_insight := 'High penalty count (' || v_wrong_subs || ' wrong submissions). Focus on testing before submitting.';
        ELSIF v_rating_change < -50 THEN
            v_insight := 'Significant rating drop. Review problems you could not solve.';
        ELSIF v_rating_change > 100 THEN
            v_insight := 'Excellent performance! Rating gain of ' || v_rating_change || '.';
        ELSIF v_first_solve > 30 THEN
            v_insight := 'Slow first solve at ' || v_first_solve || ' mins. Practice easier problems faster.';
        ELSE
            v_insight := 'Solid contest. Solved ' || v_solved || '/' || v_total || ' problems.';
        END IF;

        -- Store insight in metadata column (append to recommendations)
        INSERT INTO recommendations (user_id, rec_type, metadata, generated_at, expires_at)
        VALUES (
            p_user_id, 'CONTEST_PREP',
            '{"contest_id":' || p_contest_id || ',"insight":"' || v_insight || '"}',
            SYSTIMESTAMP,
            SYSTIMESTAMP + INTERVAL '7' DAY
        );

        COMMIT;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN NULL;
        WHEN OTHERS THEN
            ROLLBACK;
            DBMS_OUTPUT.PUT_LINE('analyze_contest error: ' || SQLERRM);
    END analyze_contest;

    PROCEDURE update_contest_metrics(p_user_id NUMBER) IS
        CURSOR c_contests IS
            SELECT contest_id FROM contest_summaries
            WHERE user_id = p_user_id
            ORDER BY contest_date DESC;
    BEGIN
        FOR rec IN c_contests LOOP
            analyze_contest(p_user_id, rec.contest_id);
        END LOOP;
    END update_contest_metrics;

    FUNCTION generate_contest_summary(p_contest_id NUMBER) RETURN VARCHAR2 IS
        v_summary   VARCHAR2(4000);
        v_row       contest_summaries%ROWTYPE;
    BEGIN
        SELECT * INTO v_row
        FROM contest_summaries
        WHERE contest_id = p_contest_id;

        v_summary :=
            'Platform: '       || v_row.platform           || CHR(10) ||
            'Contest: '        || v_row.contest_name        || CHR(10) ||
            'Rank: '           || NVL(TO_CHAR(v_row.rank),'N/A') || CHR(10) ||
            'Rating change: '  || NVL(TO_CHAR(v_row.rating_change),'N/A') || CHR(10) ||
            'Solved: '         || v_row.problems_solved || '/' || v_row.total_problems || CHR(10) ||
            'Wrong subs: '     || v_row.wrong_submissions || CHR(10) ||
            'First solve: '    || NVL(TO_CHAR(v_row.first_solve_mins) || ' min','N/A');

        RETURN v_summary;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN RETURN 'Contest not found';
        WHEN OTHERS THEN RETURN 'Error: ' || SQLERRM;
    END generate_contest_summary;

    PROCEDURE detect_behavior_patterns(p_user_id NUMBER) IS
        v_avg_wrong_last_30pct  NUMBER;
        v_total_contests        NUMBER;
        v_pattern               VARCHAR2(500);
    BEGIN
        -- Detect if user loses most penalties in final 30% of contest time
        SELECT
            COUNT(*),
            AVG(CASE
                WHEN wrong_submissions > 2 AND first_solve_mins > avg_solve_mins * 0.7
                THEN wrong_submissions ELSE 0 END)
        INTO v_total_contests, v_avg_wrong_last_30pct
        FROM contest_summaries
        WHERE user_id = p_user_id;

        IF v_total_contests < 5 THEN RETURN; END IF;

        IF v_avg_wrong_last_30pct > 1.5 THEN
            v_pattern := 'You tend to accumulate penalties in the latter portion of contests. Consider slowing down on harder problems.';

            INSERT INTO recommendations (user_id, rec_type, metadata, generated_at, expires_at)
            VALUES (
                p_user_id, 'CONTEST_PREP',
                '{"pattern":"late_penalty","message":"' || v_pattern || '"}',
                SYSTIMESTAMP,
                SYSTIMESTAMP + INTERVAL '14' DAY
            );
            COMMIT;
        END IF;
    EXCEPTION
        WHEN OTHERS THEN
            ROLLBACK;
            DBMS_OUTPUT.PUT_LINE('detect_behavior_patterns error: ' || SQLERRM);
    END detect_behavior_patterns;

END pkg_contest;
/

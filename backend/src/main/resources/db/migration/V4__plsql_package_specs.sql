CREATE OR REPLACE PACKAGE pkg_analytics AS
    FUNCTION calculate_mastery(p_user_id NUMBER, p_topic VARCHAR2) RETURN NUMBER;
    FUNCTION calculate_accuracy(p_user_id NUMBER, p_platform VARCHAR2) RETURN NUMBER;
    FUNCTION calculate_consistency(p_user_id NUMBER) RETURN NUMBER;
    FUNCTION calculate_decay(p_mastery_id NUMBER) RETURN NUMBER;
    PROCEDURE refresh_all_mastery(p_user_id NUMBER);
END pkg_analytics;
/

CREATE OR REPLACE PACKAGE pkg_recommendation AS
    PROCEDURE generate_daily_sheet(p_user_id NUMBER);
    PROCEDURE generate_weekly_sheet(p_user_id NUMBER);
    PROCEDURE generate_revision_schedule(p_user_id NUMBER);
    FUNCTION score_problem_fit(p_user_id NUMBER, p_topic VARCHAR2, p_difficulty NUMBER) RETURN NUMBER;
END pkg_recommendation;
/

CREATE OR REPLACE PACKAGE pkg_contest AS
    PROCEDURE analyze_contest(p_user_id NUMBER, p_contest_id NUMBER);
    PROCEDURE update_contest_metrics(p_user_id NUMBER);
    FUNCTION generate_contest_summary(p_contest_id NUMBER) RETURN VARCHAR2;
    PROCEDURE detect_behavior_patterns(p_user_id NUMBER);
END pkg_contest;
/

CREATE OR REPLACE PACKAGE pkg_unified_rating AS
    FUNCTION compute_unified_score(p_user_id NUMBER) RETURN NUMBER;
    FUNCTION normalize_rating(p_platform VARCHAR2, p_rating NUMBER) RETURN NUMBER;
    PROCEDURE update_all_scores;
END pkg_unified_rating;
/

CREATE OR REPLACE PACKAGE pkg_analytics AS
    FUNCTION calculate_mastery(p_user_id NUMBER, p_topic VARCHAR2) RETURN NUMBER;
    FUNCTION calculate_accuracy(p_user_id NUMBER, p_platform VARCHAR2) RETURN NUMBER;
    FUNCTION calculate_consistency(p_user_id NUMBER) RETURN NUMBER;
    FUNCTION calculate_decay(p_mastery_id NUMBER) RETURN NUMBER;
    FUNCTION calculate_confidence(p_user_id NUMBER, p_topic VARCHAR2) RETURN NUMBER;
    PROCEDURE refresh_all_mastery(p_user_id NUMBER);
END pkg_analytics;
/

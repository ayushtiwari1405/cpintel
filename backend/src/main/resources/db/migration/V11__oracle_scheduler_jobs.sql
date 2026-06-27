-- Oracle DBMS_SCHEDULER jobs for nightly analytics refresh
BEGIN
    -- Nightly unified score refresh (03:00 UTC)
    DBMS_SCHEDULER.CREATE_JOB(
        job_name        => 'JOB_REFRESH_UNIFIED_SCORES',
        job_type        => 'PLSQL_BLOCK',
        job_action      => 'BEGIN pkg_unified_rating.update_all_scores; END;',
        start_date      => SYSTIMESTAMP,
        repeat_interval => 'FREQ=DAILY;BYHOUR=3;BYMINUTE=0',
        enabled         => TRUE,
        comments        => 'Nightly unified score refresh'
    );
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
    -- Materialized view refresh (04:00 UTC)
    DBMS_SCHEDULER.CREATE_JOB(
        job_name        => 'JOB_REFRESH_MVS',
        job_type        => 'PLSQL_BLOCK',
        job_action      => '
            BEGIN
                DBMS_MVIEW.REFRESH(''MV_USER_TOPIC_SUMMARY'', ''C'');
                DBMS_MVIEW.REFRESH(''MV_CONTEST_STATS'', ''C'');
                DBMS_MVIEW.REFRESH(''MV_DAILY_ACTIVITY'', ''C'');
            END;',
        start_date      => SYSTIMESTAMP,
        repeat_interval => 'FREQ=DAILY;BYHOUR=4;BYMINUTE=0',
        enabled         => TRUE,
        comments        => 'Nightly materialized view refresh'
    );
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

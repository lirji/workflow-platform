-- 用途：旧版 writer 回滚期间可能只更新 phase，恢复新版本前用本脚本重新对齐 status。
-- 前置：已停止并排空所有旧版本 writer；禁止新旧 writer 混跑。脚本幂等，可重复执行。
\set ON_ERROR_STOP on

BEGIN;

SELECT count(*) AS drifted_rows_before_repair
FROM wf_process_link
WHERE status IS DISTINCT FROM CASE
                                  WHEN phase IN ('COMPLETED', 'CANCELLED') THEN 'ENDED'
                                  WHEN phase = 'INCIDENT' THEN 'ERROR'
                                  ELSE 'ACTIVE'
                              END;

UPDATE wf_process_link
SET status = CASE
                 WHEN phase IN ('COMPLETED', 'CANCELLED') THEN 'ENDED'
                 WHEN phase = 'INCIDENT' THEN 'ERROR'
                 ELSE 'ACTIVE'
             END,
    updated_at = now()
WHERE status IS DISTINCT FROM CASE
                                  WHEN phase IN ('COMPLETED', 'CANCELLED') THEN 'ENDED'
                                  WHEN phase = 'INCIDENT' THEN 'ERROR'
                                  ELSE 'ACTIVE'
                              END;

SELECT count(*) AS drifted_rows_after_repair
FROM wf_process_link
WHERE status IS DISTINCT FROM CASE
                                  WHEN phase IN ('COMPLETED', 'CANCELLED') THEN 'ENDED'
                                  WHEN phase = 'INCIDENT' THEN 'ERROR'
                                  ELSE 'ACTIVE'
                              END;

COMMIT;

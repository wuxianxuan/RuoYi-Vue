-- 股票模块重构：定时任务调整（Phase 1）
-- 历史脚本：job_id 已在 stock_job_resequence.sql 中重排为 1~10；本文件仅作变更记录，请勿在新环境重复执行。
-- 102/104/107 先暂停，Phase 5 删除；113 改为只维护趋势池；新增 120 解禁日程同步

UPDATE `ry-vue`.sys_job SET status = '1' WHERE job_id IN (102, 104, 107);

UPDATE `ry-vue`.sys_job
SET job_name = '趋势池维护（入池/删除）',
    invoke_target = 'stockRisingTrendTask.execute()',
    cron_expression = '0 40 16 ? * MON-FRI',
    status = '0',
    update_by = 'system',
    update_time = sysdate()
WHERE job_id = 113;

UPDATE `ry-vue`.sys_job
SET job_name = '次日推荐池',
    invoke_target = 'stockRecommendPoolTask.execute()',
    cron_expression = '0 0 17 ? * MON-FRI',
    status = '0',
    update_by = 'system',
    update_time = sysdate()
WHERE job_id = 119;

UPDATE `ry-vue`.sys_job
SET cron_expression = '0 10 17 ? * MON-FRI',
    update_by = 'system',
    update_time = sysdate()
WHERE job_id = 118;

INSERT INTO `ry-vue`.sys_job (
    job_id, job_name, job_group, invoke_target, cron_expression,
    misfire_policy, concurrent, status, create_by, create_time, remark
) VALUES (
    120, '解禁日程同步', 'DEFAULT', 'stockLockupSyncTask.sync()', '0 20 16 ? * MON-FRI',
    '3', '1', '0', 'system', sysdate(), '同步未来120天解禁日程，供119推荐池风险过滤'
)
ON DUPLICATE KEY UPDATE
    invoke_target = VALUES(invoke_target),
    cron_expression = VALUES(cron_expression),
    status = VALUES(status),
    update_by = 'system',
    update_time = sysdate();

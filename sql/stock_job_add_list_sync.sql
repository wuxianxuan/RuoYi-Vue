-- 新增股票列表/新股同步任务
-- 历史脚本：job_id 已在 stock_job_resequence.sql 中重排为 1~10；本文件仅作变更记录，请勿在新环境重复执行。
INSERT INTO `ry-vue`.sys_job (
    job_id, job_name, job_group, invoke_target, cron_expression,
    misfire_policy, concurrent, status, create_by, create_time, remark
) VALUES (
    121, '股票列表/新股同步', 'DEFAULT', 'stockListSyncTask.sync()', '0 5 16 ? * FRI',
    '3', '1', '0', 'system', sysdate(), '每周五同步全市场代码/名称，补齐新上市股票'
)
ON DUPLICATE KEY UPDATE
    invoke_target = VALUES(invoke_target),
    cron_expression = VALUES(cron_expression),
    status = VALUES(status),
    update_by = 'system',
    update_time = sysdate();

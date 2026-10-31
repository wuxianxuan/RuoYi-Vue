-- 新增运维任务：日期分组清理、数据完整性校验
-- 历史脚本：job_id 已在 stock_job_resequence.sql 中重排为 1~10；本文件仅作变更记录，请勿在新环境重复执行。
INSERT INTO `ry-vue`.sys_job (
    job_id, job_name, job_group, invoke_target, cron_expression,
    misfire_policy, concurrent, status, create_by, create_time, remark
) VALUES (
    122, '历史日期分组清理', 'DEFAULT', 'stockGroupCleanTask.clean()', '0 0 18 ? * FRI',
    '3', '1', '0', 'system', sysdate(), '删除超过30天的日期分组，固定分组不动'
)
ON DUPLICATE KEY UPDATE
    invoke_target = VALUES(invoke_target),
    cron_expression = VALUES(cron_expression),
    status = VALUES(status),
    update_by = 'system',
    update_time = sysdate();

INSERT INTO `ry-vue`.sys_job (
    job_id, job_name, job_group, invoke_target, cron_expression,
    misfire_policy, concurrent, status, create_by, create_time, remark
) VALUES (
    123, '股票数据完整性校验', 'DEFAULT', 'stockDataCheckTask.check()', '0 20 17 ? * MON-FRI',
    '3', '1', '0', 'system', sysdate(), '校验当日行情/K线/涨停/解禁数据，异常邮件告警'
)
ON DUPLICATE KEY UPDATE
    invoke_target = VALUES(invoke_target),
    cron_expression = VALUES(cron_expression),
    status = VALUES(status),
    update_by = 'system',
    update_time = sysdate();

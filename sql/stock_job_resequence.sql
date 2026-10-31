-- 股票模块定时任务重排：按业务流编号 1~10，并固定 cron
-- 注意：执行后必须重启后端。RuoYi 启动时 scheduler.clear() 会按新的 job_id 重建 Quartz 触发器；
-- 运行中直接改 job_id 会让旧触发器找不到任务。

DELETE FROM `ry-vue`.sys_job WHERE job_id IN (101, 103, 113, 117, 118, 119, 120, 121, 122, 123);

INSERT INTO `ry-vue`.sys_job
(job_id, job_name, job_group, invoke_target, cron_expression, misfire_policy, concurrent, status, create_by, create_time, remark)
VALUES
(1,  '1.数据同步(日K+分时+行情 15:30)', 'DEFAULT', 'stockKlineSyncTask.syncKline("7")', '0 30 15 ? * MON-FRI', '3', '1', '0', 'system', sysdate(), '腾讯行情+日K+分时K，三步独立失败'),
(2,  '2.涨停采集(16:00)', 'DEFAULT', 'stockLimitUpCollectTask.syncLimitUpData("7")', '0 0 16 ? * MON-FRI', '3', '1', '0', 'system', sysdate(), '财联社涨停记录，供涨停池与连板字段'),
(3,  '3.股票列表同步(周五16:05)', 'DEFAULT', 'stockListSyncTask.sync()', '0 5 16 ? * FRI', '3', '1', '0', 'system', sysdate(), '东财全量代码/名称，补齐新股'),
(4,  '4.解禁日程同步(16:20)', 'DEFAULT', 'stockLockupSyncTask.sync()', '0 20 16 ? * MON-FRI', '3', '1', '0', 'system', sysdate(), '东财 RPT_LIFT_STAGE，未来120天'),
(5,  '5.趋势池维护(16:40)', 'DEFAULT', 'stockRisingTrendTask.execute()', '0 40 16 ? * MON-FRI', '3', '1', '0', 'system', sysdate(), '113：只做入池/删除，写 stock_rising_state'),
(6,  '6.次日推荐池(17:00)', 'DEFAULT', 'stockRecommendPoolTask.execute()', '0 0 17 ? * MON-FRI', '3', '1', '0', 'system', sysdate(), '119：趋势30+涨停30+解禁引导，写 stock_recommend_pool'),
(7,  '7.尾盘推荐(次日14:45)', 'DEFAULT', 'stockTailRecommendTask.execute()', '0 45 14 ? * MON-FRI', '3', '1', '0', 'system', sysdate(), '117：读推荐池+当日分时，固定名额'),
(8,  '8.尾盘回填(17:10)', 'DEFAULT', 'stockTailRecommendEvalTask.execute()', '0 10 17 ? * MON-FRI', '3', '1', '0', 'system', sysdate(), '118：回填次日表现并按 source 统计'),
(9,  '9.数据完整性校验(17:20)', 'DEFAULT', 'stockDataCheckTask.check()', '0 20 17 ? * MON-FRI', '3', '1', '0', 'system', sysdate(), '行情/K线/涨停/解禁缺失邮件告警'),
(10, '10.日期分组清理(周五18:00)', 'DEFAULT', 'stockGroupCleanTask.clean()', '0 0 18 ? * FRI', '3', '1', '0', 'system', sysdate(), '清理超过30天的日期分组');

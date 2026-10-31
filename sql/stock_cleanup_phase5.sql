-- Phase 5：清理废弃结构与任务
-- 0) 分组：回填更新时间、删除排序字段
UPDATE `stock_group` SET `update_time` = `create_time` WHERE `update_time` IS NULL;
ALTER TABLE `stock_group` DROP COLUMN `sort_order`;

-- 1) 删除板块/资金流/涨停统计/技术指标
DROP TABLE IF EXISTS `stock_plate_stock`;
DROP TABLE IF EXISTS `stock_plate`;
DROP TABLE IF EXISTS `stock_fund_flow`;
DROP TABLE IF EXISTS `stock_limit_up_stat`;
DROP TABLE IF EXISTS `stock_indicator`;

-- 2) 删除废弃 job（102 板块同步、104 指标、107 旧行情）
DELETE FROM `sys_job` WHERE `job_id` IN (102, 104, 107);

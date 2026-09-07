-- 股票基础表新增「最新涨停日期」字段
-- 由涨停数据采集任务（stockLimitUpCollectTask）每次同步财联社涨停股票后写入当日涨停股票
ALTER TABLE `stock`
    ADD COLUMN `last_limit_up_date` date DEFAULT NULL COMMENT '最新涨停日期' AFTER `circulating_shares`;

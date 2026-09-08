-- 股票基础表新增「历史最高连板数」字段
-- 由涨停数据采集任务（stockLimitUpCollectTask）每次同步财联社涨停股票后，
-- 解析 up_num（如「8天8板」）取板数，与当前值比较，仅当更大时覆盖
ALTER TABLE `stock`
    ADD COLUMN `max_limit_up_board` int DEFAULT NULL COMMENT '历史最高连板数' AFTER `last_limit_up_date`;

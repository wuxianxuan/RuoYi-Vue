-- 股票基础表新增「历史最高连板数对应交易日」字段
-- 与 max_limit_up_board 配套，由涨停数据采集任务（stockLimitUpCollectTask）写入，
-- 记录达到历史最高连板数当天的交易日期（仅当连板数创新高时同步覆盖）
ALTER TABLE `stock`
    ADD COLUMN `max_limit_up_board_date` date DEFAULT NULL COMMENT '历史最高连板数对应交易日' AFTER `max_limit_up_board`;

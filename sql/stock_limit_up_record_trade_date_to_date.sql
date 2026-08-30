-- 修改 stock_limit_up_record.trade_date 从 varchar(8) 为 date 类型
-- 原因：财联社数据日期为 yyyyMMdd 格式（如 20201214），采集时统一转换为 date 落库
-- 已有数据（合法 yyyyMMdd 字符串）会被 MySQL 自动转换为 date；如存在空串/非法值请先清理

ALTER TABLE `stock_limit_up_record`
    MODIFY COLUMN `trade_date` date NOT NULL COMMENT '交易日期';

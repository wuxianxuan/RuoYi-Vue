-- 为 stock_limit_up_record.secu_code 增加索引
-- 原因：股票列表查询 selectStockList 用到了按 secu_code 取 max(trade_date) 的子查询，
-- 以及「最近一天涨停」的 exists 过滤，原表无 secu_code 前缀索引，导致每次全表扫描（几十秒）。
ALTER TABLE `stock_limit_up_record`
    ADD KEY `idx_secu_code_date` (`secu_code`, `trade_date`);

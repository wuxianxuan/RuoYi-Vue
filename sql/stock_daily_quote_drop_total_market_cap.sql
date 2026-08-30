-- 删除 stock_daily_quote 的「总市值」字段
-- 原因：同花顺个股行情数据源(ths_quotes.json)不提供总市值字段，该列始终为空

ALTER TABLE `stock_daily_quote`
    DROP COLUMN `total_market_cap`;

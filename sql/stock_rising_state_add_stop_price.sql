-- stock_rising_state 增加止损价字段
-- 由 stockRisingTrendTask 每次全量重算时写入：stop_price = max(阶段低点, 阶段高点 - deleteAtr × ATR20)
-- 语义：跌破该价位视为波段结束（趋势破坏），可直接作为持仓止损参考。
ALTER TABLE `stock_rising_state`
    ADD COLUMN `stop_price` decimal(12,3) DEFAULT NULL COMMENT '止损价（阶段低点与2ATR回撤位取高者）' AFTER `enter_date`;

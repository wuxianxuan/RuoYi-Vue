-- 股票限售解禁日程
-- 数据来源：东方财富 RPT_LIFT_STAGE（限售解禁-解禁详情一览）
-- 用途：119 推荐池的解禁风险过滤（未来 N 天内大比例解禁的候选剔除/降权）

CREATE TABLE IF NOT EXISTS `stock_lockup_schedule` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `stock_code`      varchar(20)  NOT NULL COMMENT '股票代码',
  `stock_name`      varchar(50)  DEFAULT NULL COMMENT '股票名称',
  `free_date`       date         NOT NULL COMMENT '解禁日',
  `free_shares`     decimal(20,4) DEFAULT NULL COMMENT '解禁数量(股)',
  `lift_market_cap` decimal(20,4) DEFAULT NULL COMMENT '解禁市值(元)',
  `free_ratio`      decimal(12,8) DEFAULT NULL COMMENT '占流通市值比例(0~1)',
  `total_ratio`     decimal(12,8) DEFAULT NULL COMMENT '占总市值比例(0~1)',
  `shares_type`     varchar(100) DEFAULT NULL COMMENT '限售股类型',
  `holder_num`      int          DEFAULT NULL COMMENT '解禁股东数',
  `source`          varchar(20)  DEFAULT 'eastmoney' COMMENT '数据来源',
  `create_time`     datetime     DEFAULT NULL,
  `update_time`     datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_date_type` (`stock_code`, `free_date`, `shares_type`),
  KEY `idx_free_date` (`free_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股票限售解禁日程';

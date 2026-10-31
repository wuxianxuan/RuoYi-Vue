-- 次日推荐池
-- 119 盘后生成：趋势池（状态=上升）+ 涨停池（首板/二板），并剔除未来大比例解禁的候选
-- 117 次日 14:45 读取本表，按来源套用不同分时规则，产出最终尾盘推荐

CREATE TABLE IF NOT EXISTS `stock_recommend_pool` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `target_date` date         NOT NULL COMMENT '目标交易日',
  `stock_code`  varchar(20)  NOT NULL COMMENT '股票代码',
  `stock_name`  varchar(50)  DEFAULT NULL COMMENT '股票名称',
  `source`      varchar(20)  NOT NULL COMMENT '来源：TREND/LIMIT_UP/LOCKUP_GUIDED',
  `score`       decimal(10,4) DEFAULT NULL COMMENT '池内评分',
  `status`      varchar(20)  DEFAULT NULL COMMENT '波段状态/连板描述',
  `reason`      varchar(200) DEFAULT NULL COMMENT '入池理由',
  `extra`       varchar(500) DEFAULT NULL COMMENT '扩展信息（JSON）',
  `create_time` datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_code_source` (`target_date`, `stock_code`, `source`),
  KEY `idx_target_date` (`target_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='次日推荐池';

-- `ry-vue`.stock 定义

CREATE TABLE `stock` (
                         `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                         `stock_code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '股票代码',
                         `stock_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '股票名称',
                         `market` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '市场：SH / SZ / BJ / HK / US',
                         `pe_ratio` decimal(18,4) DEFAULT NULL COMMENT '市盈率PE',
                         `circ_market_cap` decimal(24,4) DEFAULT NULL COMMENT '流通市值',
                         `current_price` decimal(12,4) DEFAULT NULL COMMENT '现价',
                         `change_rate` decimal(10,4) DEFAULT NULL COMMENT '涨跌幅(%)',
                         `price_change` decimal(10,4) DEFAULT NULL COMMENT '涨跌额',
                         `speed` decimal(10,4) DEFAULT NULL COMMENT '涨速(%)',
                         `turnover_rate` decimal(10,4) DEFAULT NULL COMMENT '换手率(%)',
                         `volume_ratio` decimal(10,4) DEFAULT NULL COMMENT '量比',
                         `amplitude` decimal(10,4) DEFAULT NULL COMMENT '振幅(%)',
                         `turnover_amount` decimal(18,4) DEFAULT NULL COMMENT '成交额',
                         `circulating_shares` decimal(18,4) DEFAULT NULL COMMENT '流通股',
                         `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
                         `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                         `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
                         `update_time` datetime DEFAULT NULL COMMENT '更新时间',
                         `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
                         PRIMARY KEY (`id`),
                         UNIQUE KEY `stock_unique` (`stock_code`)
) ENGINE=InnoDB AUTO_INCREMENT=24099 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='股票基础表';


-- `ry-vue`.stock_daily_quote 定义

CREATE TABLE `stock_daily_quote` (
                                     `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                                     `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
                                     `trade_date` date NOT NULL COMMENT '交易日期',
                                     `current_price` decimal(18,4) DEFAULT NULL COMMENT '现价',
                                     `change_rate` decimal(18,4) DEFAULT NULL COMMENT '涨跌幅(%)',
                                     `price_change` decimal(18,4) DEFAULT NULL COMMENT '涨跌额',
                                     `speed` decimal(18,4) DEFAULT NULL COMMENT '涨速(%)',
                                     `turnover_rate` decimal(18,4) DEFAULT NULL COMMENT '换手率(%)',
                                     `volume_ratio` decimal(18,4) DEFAULT NULL COMMENT '量比',
                                     `amplitude` decimal(18,4) DEFAULT NULL COMMENT '振幅(%)',
                                     `turnover_amount` decimal(18,4) DEFAULT NULL COMMENT '成交额',
                                     `circulating_shares` decimal(18,4) DEFAULT NULL COMMENT '流通股',
                                     `circ_market_cap` decimal(18,4) DEFAULT NULL COMMENT '流通市值',
                                     `pe_ratio` decimal(18,4) DEFAULT NULL COMMENT '市盈率',
                                     PRIMARY KEY (`id`),
                                     UNIQUE KEY `uk_code_date` (`stock_code`,`trade_date`),
                                     KEY `idx_trade_date` (`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='个股每日行情快照';


-- `ry-vue`.stock_group 定义

CREATE TABLE `stock_group` (
                               `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                               `group_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '分组名称',
                               `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
                               `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                               `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
                               `update_time` datetime DEFAULT NULL COMMENT '更新时间',
                               `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
                               PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=106 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='股票分组表';


-- `ry-vue`.stock_group_stock 定义

CREATE TABLE `stock_group_stock` (
                                     `group_id` bigint NOT NULL COMMENT '分组ID',
                                     `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
                                     PRIMARY KEY (`group_id`,`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='股票分组-股票关联表';


-- `ry-vue`.stock_kline 定义

CREATE TABLE `stock_kline` (
                               `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                               `stock_code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '股票代码',
                               `kline_type` char(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'K线类型（D=日 W=周 M=月 1/5/15/30/60=分钟）',
                               `trade_time` datetime NOT NULL COMMENT 'K线时间点',
                               `open_price` decimal(12,4) DEFAULT NULL COMMENT '开盘价',
                               `close_price` decimal(12,4) DEFAULT NULL COMMENT '收盘价',
                               `high_price` decimal(12,4) DEFAULT NULL COMMENT '最高价',
                               `low_price` decimal(12,4) DEFAULT NULL COMMENT '最低价',
                               `volume` bigint DEFAULT NULL COMMENT '成交量（股）',
                               `amount` decimal(18,4) DEFAULT NULL COMMENT '成交额',
                               PRIMARY KEY (`id`),
                               UNIQUE KEY `uk_code_type_time` (`stock_code`,`kline_type`,`trade_time`),
                               KEY `idx_stock_code` (`stock_code`)
) ENGINE=InnoDB AUTO_INCREMENT=12582825 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='K线数据表';


-- `ry-vue`.stock_limit_up_record 定义

CREATE TABLE `stock_limit_up_record` (
                                         `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                                         `trade_date` date NOT NULL COMMENT '交易日期',
                                         `secu_code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '证券代码',
                                         `secu_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '证券名称',
                                         `change_rate` decimal(10,4) DEFAULT NULL COMMENT '涨跌幅',
                                         `last_px` decimal(10,4) DEFAULT NULL COMMENT '最新价',
                                         `cmc` bigint DEFAULT NULL COMMENT '市值',
                                         `time` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '时间',
                                         `up_num` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '涨停数量',
                                         `up_reason` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '涨停原因',
                                         `plate_secu_code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '板块代码',
                                         `plate_secu_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '板块名称',
                                         `plate_change_rate` decimal(10,4) DEFAULT NULL COMMENT '板块涨跌幅',
                                         `plate_up_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '板块涨停原因',
                                         `plate_stock_up_num` int DEFAULT NULL COMMENT '板块涨停数量',
                                         `up_tags` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '标签',
                                         `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
                                         `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                                         `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
                                         `update_time` datetime DEFAULT NULL COMMENT '更新时间',
                                         `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
                                         PRIMARY KEY (`id`),
                                         UNIQUE KEY `uk_date_code_plate` (`trade_date`,`secu_code`,`plate_secu_code`),
                                         KEY `idx_plate_date` (`plate_secu_name`,`trade_date`)
) ENGINE=InnoDB AUTO_INCREMENT=404643 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='股票涨停记录表';


-- `ry-vue`.stock_lockup_schedule 定义

CREATE TABLE `stock_lockup_schedule` (
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


-- `ry-vue`.stock_recommend_pool 定义

CREATE TABLE `stock_recommend_pool` (
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
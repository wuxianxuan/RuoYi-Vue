-- `ry-vue`.stock 定义

CREATE TABLE `stock` (
                         `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                         `stock_code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '股票代码',
                         `stock_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '股票名称',
                         `market` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '市场：SH / SZ / BJ / HK / US',
                         `industry_name` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '行业全路径(冗余)',
                         `pe_ratio` decimal(18,4) DEFAULT NULL COMMENT '市盈率PE',
                         `total_market_cap` decimal(24,4) DEFAULT NULL COMMENT '总市值',
                         `circ_market_cap` decimal(24,4) DEFAULT NULL COMMENT '流通市值',
                         `current_price` decimal(12,4) DEFAULT NULL COMMENT '现价',
                         `change_rate` decimal(10,4) DEFAULT NULL COMMENT '涨跌幅(%)',
                         `price_change` decimal(10,4) DEFAULT NULL COMMENT '涨跌额',
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
                                     `total_market_cap` decimal(18,4) DEFAULT NULL COMMENT '总市值',
                                     PRIMARY KEY (`id`),
                                     UNIQUE KEY `uk_code_date` (`stock_code`,`trade_date`),
                                     KEY `idx_trade_date` (`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='个股每日行情快照';


-- `ry-vue`.stock_group 定义

CREATE TABLE `stock_group` (
                               `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                               `group_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '分组名称',
                               `sort_order` int DEFAULT '0' COMMENT '排序',
                               `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
                               `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                               `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
                               `update_time` datetime DEFAULT NULL COMMENT '更新时间',
                               `remark` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
                               PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=106 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='股票分组表';


-- `ry-vue`.stock_group_stock 定义

CREATE TABLE `stock_group_stock` (
                                     `group_id` bigint NOT NULL,
                                     `stock_id` bigint NOT NULL,
                                     PRIMARY KEY (`group_id`,`stock_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;


-- `ry-vue`.stock_indicator 定义

CREATE TABLE `stock_indicator` (
                                   `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                                   `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
                                   `kline_type` varchar(5) NOT NULL DEFAULT 'D' COMMENT 'K线类型（D=日 W=周 M=月）',
                                   `trade_time` date NOT NULL COMMENT '交易日期',
                                   `ma5` decimal(18,4) DEFAULT NULL COMMENT '5日均线',
                                   `ma10` decimal(18,4) DEFAULT NULL COMMENT '10日均线',
                                   `ma20` decimal(18,4) DEFAULT NULL COMMENT '20日均线',
                                   `ma60` decimal(18,4) DEFAULT NULL COMMENT '60日均线',
                                   `macd_dif` decimal(18,4) DEFAULT NULL COMMENT 'MACD DIF',
                                   `macd_dea` decimal(18,4) DEFAULT NULL COMMENT 'MACD DEA',
                                   `macd_bar` decimal(18,4) DEFAULT NULL COMMENT 'MACD 柱（BAR）',
                                   `rsi6` decimal(18,4) DEFAULT NULL COMMENT '6日RSI',
                                   `rsi14` decimal(18,4) DEFAULT NULL COMMENT '14日RSI',
                                   `vol_ratio` decimal(18,4) DEFAULT NULL COMMENT '量比（当日成交量 / 5日均量）',
                                   `trend_slope` decimal(18,4) DEFAULT NULL COMMENT '趋势斜率（线性回归）',
                                   PRIMARY KEY (`id`),
                                   UNIQUE KEY `uk_code_type_time` (`stock_code`,`kline_type`,`trade_time`),
                                   KEY `idx_stock_code` (`stock_code`)
) ENGINE=InnoDB AUTO_INCREMENT=278384 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='股票技术指标缓存表';


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
                                         `trade_date` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '交易日期(yyyyMMdd)',
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


-- `ry-vue`.stock_plate 定义

CREATE TABLE `stock_plate` (
                               `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                               `plate_name` varchar(100) NOT NULL COMMENT '板块名称',
                               `plate_type` varchar(20) NOT NULL COMMENT '板块类型（INDUSTRY 行业 / CONCEPT 概念）',
                               `parent_id` bigint DEFAULT NULL COMMENT '父板块ID',
                               `ancestors` varchar(500) DEFAULT '' COMMENT '祖级列表',
                               `level` tinyint NOT NULL COMMENT '层级（1/2/3）',
                               `sort_order` int DEFAULT '0' COMMENT '排序',
                               `focus_flag` tinyint NOT NULL DEFAULT 0 COMMENT '是否需要重点关注(1需要 0不需要)',
                               `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
                               `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                               `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
                               `update_time` datetime DEFAULT NULL COMMENT '更新时间',
                               `remark` varchar(500) DEFAULT NULL COMMENT '备注',
                               PRIMARY KEY (`id`),
                               UNIQUE KEY `uk_plate_name_type_parent` (`plate_name`,`plate_type`,`parent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=89 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='股票板块/概念字典表';


-- `ry-vue`.stock_plate_stock 定义

CREATE TABLE `stock_plate_stock` (
                                     `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                                     `plate_id` bigint NOT NULL COMMENT '板块ID',
                                     `stock_id` bigint NOT NULL COMMENT '股票ID',
                                     PRIMARY KEY (`id`),
                                     UNIQUE KEY `uk_plate_stock` (`plate_id`,`stock_id`),
                                     KEY `idx_stock_id` (`stock_id`)
) ENGINE=InnoDB AUTO_INCREMENT=18472 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='板块/概念-股票关联表';


-- `ry-vue`.stock_recommend 定义

CREATE TABLE `stock_recommend` (
                                   `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                                   `recommend_date` date NOT NULL COMMENT '推荐日期',
                                   `stock_id` bigint NOT NULL COMMENT '股票ID',
                                   `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
                                   `stock_name` varchar(50) DEFAULT '' COMMENT '股票名称',
                                   `total_score` decimal(10,4) DEFAULT '0.0000' COMMENT '综合得分',
                                   `trend_score` decimal(10,4) DEFAULT '0.0000' COMMENT '趋势得分',
                                   `plate_score` decimal(10,4) DEFAULT '0.0000' COMMENT '板块得分',
                                   `fund_score` decimal(10,4) DEFAULT '0.0000' COMMENT '资金得分（预留）',
                                   `base_score` decimal(10,4) DEFAULT '0.0000' COMMENT '基本面得分（预留）',
                                   `reason` varchar(500) DEFAULT '' COMMENT '推荐理由',
                                   `status` char(1) DEFAULT '0' COMMENT '状态（0待确认 1已确认 2已驳回）',
                                   `create_by` varchar(64) DEFAULT 'system' COMMENT '创建者',
                                   `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                                   `update_by` varchar(64) DEFAULT 'system' COMMENT '更新者',
                                   `update_time` datetime DEFAULT NULL COMMENT '更新时间',
                                   `remark` varchar(500) DEFAULT NULL COMMENT '备注',
                                   PRIMARY KEY (`id`),
                                   KEY `idx_recommend_date` (`recommend_date`),
                                   KEY `idx_stock_id` (`stock_id`),
                                   KEY `idx_stock_code` (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='股票推荐结果表';
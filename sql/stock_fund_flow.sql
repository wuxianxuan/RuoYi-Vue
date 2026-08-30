-- `ry-vue`.stock_fund_flow 定义

CREATE TABLE `stock_fund_flow` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `plate_type` varchar(20) NOT NULL COMMENT '板块类型（INDUSTRY 行业 / CONCEPT 概念）',
    `trade_date` date NOT NULL COMMENT '交易日期',
    `trade_name` varchar(100) DEFAULT NULL COMMENT '行业或概念名称',
    `code` varchar(20) NOT NULL COMMENT '行业或概念代码',
    `price` decimal(20,4) DEFAULT NULL COMMENT '行业或概念指数',
    `trade_zdf` decimal(10,4) DEFAULT NULL COMMENT '涨跌幅(%)',
    `buy` decimal(20,4) DEFAULT NULL COMMENT '流入资金(亿)',
    `sell` decimal(20,4) DEFAULT NULL COMMENT '流出资金(亿)',
    `je` decimal(20,4) DEFAULT NULL COMMENT '净额(亿)',
    `total` int DEFAULT NULL COMMENT '公司家数',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_type_code_date` (`plate_type`,`code`,`trade_date`),
    KEY `idx_plate_type_date` (`plate_type`,`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行业/概念资金流向表';

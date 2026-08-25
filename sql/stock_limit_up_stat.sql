-- 股票近1年涨停次数统计表（不含最近7天）
-- 由 StockLimitUpCollectTask 在涨停数据采集完成后重建（先清空再统计）

CREATE TABLE `stock_limit_up_stat` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `stock_code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '股票代码',
    `stock_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '股票名称',
    `limit_up_count` int NOT NULL DEFAULT 0 COMMENT '近1年涨停次数（不含最近7天，按交易日去重）',
    `stat_date` date DEFAULT NULL COMMENT '统计日期',
    `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '创建者',
    `create_time` datetime DEFAULT NULL COMMENT '创建时间',
    `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '更新者',
    `update_time` datetime DEFAULT NULL COMMENT '更新时间',
    `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_stock_code` (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='股票近1年涨停次数统计表（不含最近7天）';

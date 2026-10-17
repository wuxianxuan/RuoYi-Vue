-- `ry-vue`.stock_rising_low 定义
-- 记录趋势上涨选股任务每次推荐股票时的最低点交易日（同股票同最低日期仅保留一条）

CREATE TABLE `stock_rising_low` (
                                    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                                    `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
                                    `low_date` date NOT NULL COMMENT '推荐时最低点交易日',
                                    `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                                    PRIMARY KEY (`id`),
                                    UNIQUE KEY `uk_code_date` (`stock_code`,`low_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='趋势上涨选股最低点日期记录表';

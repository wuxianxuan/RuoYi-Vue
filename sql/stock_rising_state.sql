-- `ry-vue`.stock_rising_state 定义
-- 趋势上涨选股波段状态表：每只池内股票一行，记录当前波段状态与阶段高低点，
-- 由 stockRisingTrendTask 每日全量覆盖（先清空后写入），供前端展示与日推荐参考。

CREATE TABLE `stock_rising_state` (
                                      `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
                                      `stock_code` varchar(20) NOT NULL COMMENT '股票代码',
                                      `status` varchar(10) DEFAULT NULL COMMENT '波段状态: 上升/震荡/回调',
                                      `high_price` decimal(12,3) DEFAULT NULL COMMENT '阶段最高收盘价',
                                      `high_date` date DEFAULT NULL COMMENT '阶段最高日期',
                                      `low_price` decimal(12,3) DEFAULT NULL COMMENT '阶段最低收盘价',
                                      `low_date` date DEFAULT NULL COMMENT '阶段最低日期',
                                      `enter_date` date DEFAULT NULL COMMENT '最近一次进池日期',
                                      `stop_price` decimal(12,3) DEFAULT NULL COMMENT '止损价（阶段低点与2ATR回撤位取高者）',
                                      `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                                      `update_time` datetime DEFAULT NULL COMMENT '更新时间',
                                      PRIMARY KEY (`id`),
                                      UNIQUE KEY `uk_stock_code` (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='趋势上涨选股波段状态表';

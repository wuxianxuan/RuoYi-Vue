-- 尾盘推荐实验记录表
-- 目的：把每天 14:45 推荐时的指标快照落库，次日收盘后回填"次日表现"，
-- 用于验证「低涨幅 + 低乖离」这套口径是否真的降低了次日回调，并支持不同参数组合（rule_version）之间对比。
-- 由 stockTailRecommendTask（写）与 stockTailRecommendEvalTask（回填）维护。

-- 1. 推荐明细（每只被推荐的股票一行，含次日表现）
CREATE TABLE IF NOT EXISTS `stock_tail_recommend_record` (
    `id`                     bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trade_date`             date         NOT NULL COMMENT '推荐日',
    `base_date`              date         DEFAULT NULL COMMENT '昨收基准日',
    `rule_version`           varchar(50)  DEFAULT NULL COMMENT '规则版本（用于不同参数组合对比）',
    `stock_code`             varchar(20)  NOT NULL COMMENT '股票代码',
    `stock_name`             varchar(50)  DEFAULT NULL COMMENT '股票名称',
    `picked`                 tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否被推荐：1=推荐，0=仅记录（候选/被挡）',
    `reject_reason`          varchar(50)  DEFAULT NULL COMMENT '淘汰原因（null=通过全部过滤）',
    `rank_no`                int          DEFAULT NULL COMMENT '推荐排名（按综合评分降序）',
    `rec_price`              decimal(12,3) DEFAULT NULL COMMENT '推荐时价格（14:45 最新价）',
    `open_pct`               decimal(8,3) DEFAULT NULL COMMENT '当日开盘涨幅(%)',
    `close_pct`              decimal(8,3) DEFAULT NULL COMMENT '当日涨幅(%)',
    `gain_5d`                decimal(8,3) DEFAULT NULL COMMENT '近5日累计涨幅(%)',
    `bias_ma5`               decimal(8,3) DEFAULT NULL COMMENT '相对MA5乖离(%)',
    `close_pos`              decimal(8,4) DEFAULT NULL COMMENT '收盘位置(0~1)',
    `tail_change`            decimal(8,3) DEFAULT NULL COMMENT '尾盘30分钟涨跌(%)',
    `volume_ratio`           decimal(8,3) DEFAULT NULL COMMENT '量比',
    `turnover_rate`          decimal(8,3) DEFAULT NULL COMMENT '换手率(%)',
    `amount`                 decimal(18,2) DEFAULT NULL COMMENT '当日成交额(元，分时估算)',
    `score`                  decimal(8,4) DEFAULT NULL COMMENT '综合评分',
    `next_trade_date`        date         DEFAULT NULL COMMENT '次日交易日',
    `next_open`              decimal(12,3) DEFAULT NULL COMMENT '次日开盘价',
    `next_high`              decimal(12,3) DEFAULT NULL COMMENT '次日最高价',
    `next_low`               decimal(12,3) DEFAULT NULL COMMENT '次日最低价',
    `next_close`             decimal(12,3) DEFAULT NULL COMMENT '次日收盘价',
    `next_change_pct`        decimal(8,3) DEFAULT NULL COMMENT '次日收盘相对推荐价(%)＝持有到次日收盘收益',
    `next_max_gain_pct`      decimal(8,3) DEFAULT NULL COMMENT '次日最高相对推荐价(%)＝最大浮盈',
    `next_max_drawdown_pct`  decimal(8,3) DEFAULT NULL COMMENT '次日最低相对推荐价(%)＝最大浮亏',
    `eval_status`            varchar(20)  DEFAULT NULL COMMENT '回填状态：NULL=待回填，DONE=已回填，NO_DATA=无次日K线',
    `create_time`            datetime     DEFAULT NULL COMMENT '创建时间',
    `update_time`            datetime     DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trade_code` (`trade_date`, `stock_code`),
    KEY `idx_eval` (`eval_status`),
    KEY `idx_rule_date_picked` (`rule_version`, `trade_date`, `picked`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='尾盘推荐实验记录（含次日表现）';

-- 2. 每次运行的筛选漏斗（用于判断哪条闸门卡得最狠）
CREATE TABLE IF NOT EXISTS `stock_tail_recommend_run` (
    `id`               bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trade_date`       date          NOT NULL COMMENT '推荐日',
    `rule_version`     varchar(50)   DEFAULT NULL COMMENT '规则版本',
    `candidate_count`  int           DEFAULT NULL COMMENT '候选股票数',
    `hit_count`        int           DEFAULT NULL COMMENT '通过全部过滤的股票数',
    `picked_count`     int           DEFAULT NULL COMMENT '实际推荐数（top-n 截断后）',
    `reject_summary`   varchar(1000) DEFAULT NULL COMMENT '各过滤条件淘汰数量，如 涨幅过高=7，近5日已涨透=5',
    `create_time`      datetime      DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_date_version` (`trade_date`, `rule_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='尾盘推荐运行漏斗';

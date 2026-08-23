-- 板块管理表新增「是否需要重点关注」字段
-- 1=需要重点关注，0=不需要，默认 0

ALTER TABLE `stock_plate`
    ADD COLUMN `focus_flag` tinyint NOT NULL DEFAULT 0
    COMMENT '是否需要重点关注(1需要 0不需要)' AFTER `sort_order`;

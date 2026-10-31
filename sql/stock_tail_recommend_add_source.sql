-- 尾盘推荐记录增加来源字段，供 118 按来源分别统计
ALTER TABLE `stock_tail_recommend_record`
  ADD COLUMN `source` varchar(20) DEFAULT NULL COMMENT '来源：TREND/LIMIT_UP/LOCKUP_GUIDED' AFTER `stock_name`;

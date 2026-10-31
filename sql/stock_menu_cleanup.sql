-- 股票模块重构：删除板块管理、K线查询菜单
DELETE FROM `ry-vue`.sys_role_menu WHERE menu_id IN (2004, 2037);
DELETE FROM `ry-vue`.sys_menu WHERE menu_id IN (2004, 2037);

# RuoYi-Vue 股票管理系统

> 完整现状、数据表、任务链路与业务规则见 `docs/股票模块现状与业务逻辑.md`；本文是精简速查。

## 项目结构

```
IdeaProjects/
├── RuoYi-Vue/          ← 后端 (Spring Boot + MyBatis)
│   ├── ruoyi-admin/    # Controller 层
│   │   └── src/main/java/com/ruoyi/web/controller/stock/
│   │       ├── StockController.java          # 股票基础（只读 + 备注/分组受限编辑）
│   │       ├── StockGroupController.java     # 股票分组管理
│   │       └── StockKlineController.java     # K线查询（仅供基础页悬浮窗）
│   ├── ruoyi-system/   # Service + Mapper + Domain
│   │   └── src/main/java/com/ruoyi/system/
│   │       ├── datasource/       # 腾讯行情/腾讯K线/东财解禁
│   │       ├── domain/stock/     # 实体类
│   │       ├── mapper/           # MyBatis Mapper 接口
│   │       ├── service/          # Service 接口 + 实现
│   │       └── resources/mapper/stock/  # MyBatis XML
│   ├── ruoyi-quartz/    # 定时任务
│   │   └── src/main/java/com/ruoyi/quartz/task/
│   │       ├── StockKlineSyncTask.java       # 1 日K + 分时K + 每日行情
│   │       ├── StockLimitUpCollectTask.java  # 2 财联社涨停采集
│   │       ├── StockLockupSyncTask.java      # 4 解禁日程同步
│   │       ├── StockListSyncTask.java        # 3 股票列表/新股同步
│   │       ├── StockRisingTrendTask.java     # 5 趋势池维护
│   │       ├── StockRecommendPoolTask.java   # 6 次日推荐池
│   │       ├── StockTailRecommendTask.java   # 7 尾盘推荐
│   │       ├── StockTailRecommendEvalTask.java # 8 次日表现回填
│   │       ├── StockDataCheckTask.java       # 9 数据完整性校验
│   │       └── StockGroupCleanTask.java      # 10 日期分组清理
│   ├── ruoyi-common/    # 工具类
│   └── ruoyi-framework/ # 框架配置 (RestTemplate Bean 等)
│
└── RuoYi-Vue3/          ← 前端 (Vue3 + Element Plus)
    └── src/
        ├── views/stock/
        │   ├── base/index.vue    # 股票基础（查询 + 走势悬浮窗 + 受限修改）
        │   └── group/index.vue   # 分组管理
        ├── components/StockKlineDialog/index.vue  # 近3个月日K悬浮窗
        └── api/stock/
            ├── base.js           # 股票基础 API
            ├── group.js          # 分组 API
            └── kline.js          # K线 API
```

## 页面与接口

| 页面 | 前端文件 | 后端 Controller | Service |
|---|---|---|---|
| 股票基础 | `views/stock/base/index.vue` | `StockController` (`/stock/base`) | `IStockService` |
| 分组管理 | `views/stock/group/index.vue` | `StockGroupController` (`/stock/group`) | `IStockGroupService` |
| 日K悬浮窗 | `components/StockKlineDialog` | `StockKlineController` (`/stock/kline`) | `IStockKlineService` |

> 板块管理、K线查询页已删除；行业/概念维度已整体移除。

## 定时任务链路

```
15:30  1  数据同步：腾讯行情 + 日K + 分时K
16:00  2  财联社涨停采集
16:05  3  股票列表/新股同步（每周五）
16:20  4  东财解禁同步
16:40  5  趋势池维护（入池/删除）
17:00  6  次日推荐池（趋势30 + 涨停30 + 解禁引导3）
17:10  8  尾盘推荐次日表现回填
17:20  9  数据完整性校验
18:00  10 历史日期分组清理（每周五）
14:45  7  尾盘推荐（趋势5 + 涨停5 + 解禁引导2）
```

## 关键设计规则

### 1. 股票记录由同步任务维护
- 基础页不再提供新增/删除，`stock` 表由行情同步（腾讯）和「股票列表/新股同步」任务维护。
- 页面只允许通过 `PUT /stock/base/profile` 修改 **备注** 和 **所属分组**。
- `StockServiceImpl.updateStockProfile` 在事务内更新 `remark` 并重建 `stock_group_stock`。

### 2. 股票基础页
- 筛选：代码、名称、市场、所属分组、现价、涨跌幅、换手、量比、成交额。
- 列：代码、名称、市场、所属分组、现价、涨跌幅、年初至今、换手、量比、振幅、成交额、流通市值、市盈率、涨停（合并列）、备注、操作。
- 「涨停」列合并展示最新涨停日期 + 历史最高连板数，tooltip 显示最高连板日期。
- 行操作只剩「走势」「修改」；「走势」打开 `StockKlineDialog` 展示近 3 个月日K + MA5/10/20 + 成交量。

### 3. 分组管理
- 新增/修改只保留分组名称、备注，不再有排序字段。
- 列表按 `update_time desc, id desc` 排序；分组加/删股票时通过 `touchGroup` 刷新更新时间。
- 工具栏只保留新增/删除，行内操作保留修改/删除/新增股票/股票列表。
- 股票列表弹窗的「走势」同样打开日K悬浮窗。

### 4. 推荐池（119 → 117）
- `stock_recommend_pool` 按 `(target_date, stock_code, source)` 唯一，`source` 为 `TREND` / `LIMIT_UP` / `LOCKUP_GUIDED`。
- 119 每个来源最多入池：趋势 30、涨停 30、解禁引导 3。
- 趋势来源只取 `stock_rising_state.status = 上升`；涨停来源只取首板/二板。
- 趋势/涨停候选会剔除「未来 20 天内解禁比例 ≥ 5%」的股票（`stock_lockup_schedule` 风险过滤）。
- `LOCKUP_GUIDED` 是解禁前的独立子信号：解禁日 5~45 天内、解禁比例 3%~20%、10 日涨幅 0%~20%、收盘>MA20 且 MA5>MA20、近 10 日上涨天数≥5、5/20 日均量比 0.9~2.5、近 10 日平均振幅≤6%、近 5 日无涨停、距 20 日高点≤8%、换手 0.3%~15%。该来源不套用风险过滤。
- 117 只读推荐池，按来源分别套规则，最终固定名额：趋势 5 + 涨停 5 + 解禁引导 2，不跨来源补位。
- 117 在推荐池缺失或超过 4 天未更新时直接失败，避免用旧池子推荐。
- 118 按 `source` 分开统计推荐组表现（含「推荐组-解禁」）。

### 5. 数据源
- 每日行情：腾讯 `qt.gtimg.cn` 批量接口（GB18030，60 只/批，覆盖沪/深/北）。
- 日K/分时：腾讯 `proxy.finance.qq.com` / `ifzq.gtimg.cn`。
- 涨停：财联社 CLS API。
- 解禁：东方财富 `RPT_LIFT_STAGE`（`datacenter-web.eastmoney.com`）。
- 腾讯不提供「涨速」，`stock.speed` 保留历史字段但页面不再展示。

### 6. 后端 API 参数传递
- `/stock/base/list` 直接接收 `Stock` 对象作为 query params，Spring MVC 自动绑定。
- 新增筛选字段时在 `Stock.java` 加非持久化字段，并在 `StockMapper.xml` 的 `selectStockList` / `selectStocksNotInGroup` 加 `<if>` 条件。

## 数据库脚本

- `sql/stock_lockup_schedule.sql`：解禁日程表
- `sql/stock_recommend_pool.sql`：次日推荐池
- `sql/stock_tail_recommend_add_source.sql`：推荐记录来源字段
- `sql/stock_job_refactor.sql`：任务调整
- `sql/stock_job_add_list_sync.sql`：股票列表同步任务
- `sql/stock_job_add_ops.sql`：分组清理/数据校验任务
- `sql/stock_job_resequence.sql`：任务按业务流重排为 1~10
- `sql/stock_menu_cleanup.sql`：删除板块/K线菜单
- `sql/stock_cleanup_phase5.sql`：删除废弃表与旧 job

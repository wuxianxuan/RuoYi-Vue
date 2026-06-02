# RuoYi-Vue 股票管理系统

## 项目结构

```
IdeaProjects/
├── RuoYi-Vue/          ← 后端 (Spring Boot + MyBatis)
│   ├── ruoyi-admin/    # Controller 层
│   │   └── src/main/java/com/ruoyi/web/controller/stock/
│   │       ├── StockController.java          # 股票基础 CRUD
│   │       ├── StockGroupController.java     # 股票分组管理
│   │       ├── StockPlateController.java     # 板块/概念管理
│   │       ├── StockKlineController.java     # K线查询
│   │       └── StockAnalysisRecordController.java
│   ├── ruoyi-system/   # Service + Mapper + Domain
│   │   └── src/main/java/com/ruoyi/system/
│   │       ├── domain/stock/     # 实体类 (Stock, StockGroup, StockPlate, StockKline...)
│   │       ├── mapper/           # MyBatis Mapper 接口
│   │       ├── service/          # Service 接口 + 实现
│   │       └── resources/mapper/stock/  # MyBatis XML
│   ├── ruoyi-quartz/    # 定时任务
│   │   └── src/main/java/com/ruoyi/quartz/task/
│   │       ├── StockPlateInfoSyncTask.java  # 同花顺板块/概念同步
│   │       ├── StockTrendReversalTask.java  # 趋势反转检测
│   │       ├── StockKlineSyncTask.java      # K线同步
│   │       └── StockDataCollectTask.java    # 数据采集
│   ├── ruoyi-common/    # 工具类
│   └── ruoyi-framework/ # 框架配置 (RestTemplate Bean 等)
│
└── RuoYi-Vue3/          ← 前端 (Vue3 + Element Plus)
    └── src/
        ├── views/stock/          # 页面组件
        │   ├── base/index.vue    # 股票基础管理
        │   ├── group/index.vue   # 分组管理
        │   ├── plate/index.vue   # 板块管理
        │   └── kline/index.vue   # K线查询
        └── api/stock/            # 接口定义
            ├── base.js           # 股票基础 API
            ├── group.js          # 分组 API
            ├── plate.js          # 板块 API
            └── kline.js          # K线 API
```

## 前后端对应关系

| 前端页面 | 前端文件 | 后端 Controller | 后端 Service |
|---------|---------|----------------|-------------|
| 股票基础 | `views/stock/base/index.vue` | `StockController` (`/stock/base`) | `IStockService` |
| 分组管理 | `views/stock/group/index.vue` | `StockGroupController` (`/stock/group`) | `IStockGroupService` |
| 板块管理 | `views/stock/plate/index.vue` | `StockPlateController` (`/stock/plate`) | `IStockPlateService` |
| K线查询 | `views/stock/kline/index.vue` | `StockKlineController` (`/stock/kline`) | `IStockKlineService` |

## 关键设计规则

### 1. 筛选条件一致性
- **规则**: 股票基础页面 (`stock/base/index.vue`) 和分组管理的新增股票弹窗 (`stock/group/index.vue` 的 add-stock dialog) 的筛选条件必须保持一致
- **当前筛选字段**: 股票代码 (stockCode)、股票名称 (stockName)、市场 (market)、行业 (industryId)、概念 (conceptIds)、所属分组 (groupId)
- **数据来源**: 行业树用 `listIndustryTree()` (调用 `/stock/plate/tree`)，概念下拉用 `listPlate({ plateType: 'concept' })`
- **后端支持**: `listExcludeStocks` 的 Controller 方法接收 `Stock` 对象，所有 `Stock` 上的筛选字段自动生效；注意新增字段时必须同步更新 `Stock.java` 和 `StockMapper.xml` 的 `selectStockList` / `selectStocksNotInGroup`

### 2. 行业名称同步
- `stock.industry_name` 字段由 `IStockPlateService.insertPlateStocks()` / `deletePlateStocks()` 自动维护
- 直接操作 `stockPlateMapper` 绕过 Service 层会**不会**更新 `industry_name`
- 定时任务 `StockPlateInfoSyncTask` 已改为使用 `IStockPlateService` 来保证 `industry_name` 同步

### 3. 板块类型
- `INDUSTRY`: 行业板块（同花顺代码以 `881xxx` 或 `884xxx` 开头）
- `CONCEPT`: 概念板块（同花顺代码以 `885xxx` 或 `886xxx` 开头）
- `stock_plate.remark` 字段用于存储同花顺板块代码

### 4. 字段命名注意事项
- `StockPlate` 的板块名称字段是 `plateName`（非 `industryName`）
- 前端 tree-select 的 `:props` 中 label 必须配置为 `plateName`

### 5. 后端 API 参数传递
- 列表查询接口（如 `/stock/base/list`）直接接收 `Stock` 对象作为 query params
- Spring MVC 会自动将 URL 参数绑定到 `Stock` 的属性上
- 新增筛选字段时只需在 `Stock.java` 加字段（作为非持久化参数）并在 `StockMapper.xml` 加 `<if test>` 条件

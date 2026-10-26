package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.util.Date;

/**
 * 趋势上涨选股波段状态对象 stock_rising_state
 *
 * @author ruoyi
 */
@Data
public class StockRisingState {
    /** 主键 */
    private Long id;

    /** 股票代码 */
    private String stockCode;

    /** 波段状态：上升/震荡/回调 */
    private String status;

    /** 阶段最高收盘价 */
    private Double highPrice;

    /** 阶段最高日期（yyyy-MM-dd） */
    private String highDate;

    /** 阶段最低收盘价 */
    private Double lowPrice;

    /** 阶段最低日期（yyyy-MM-dd） */
    private String lowDate;

    /** 最近一次进池日期（yyyy-MM-dd） */
    private String enterDate;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}

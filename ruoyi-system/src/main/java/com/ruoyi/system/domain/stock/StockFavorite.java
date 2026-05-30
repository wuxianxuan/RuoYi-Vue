package com.ruoyi.system.domain.stock;

import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class StockFavorite extends BaseEntity {
    /** 主键 */
    private Long id;

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 市场（0=深市 1=沪市） */
    private String market;

    /** 所属分组ID，多个用逗号分隔（仅用于前端传参） */
    private Long[] groupIds;
}

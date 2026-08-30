package com.ruoyi.system.domain.stock;

import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 板块字典对象 stock_plate
 *
 * @author ruoyi
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StockPlate extends BaseEntity {

    /** 主键 */
    private Long id;

    /** 板块名称 */
    private String plateName;

    /** 板块类型（INDUSTRY 行业 / CONCEPT 概念） */
    private String plateType;

    /** 同花顺板块代码 */
    private String plateCode;

    /** 排序 */
    private Integer sortOrder;

    /** 是否需要重点关注（1需要 0不需要） */
    private Integer focusFlag;
}

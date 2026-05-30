package com.ruoyi.system.domain.stock;

import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class StockGroup extends BaseEntity {

    /** 主键 */
    private Long id;

    /** 分组名称 */
    private String groupName;

    /** 排序 */
    private Integer sortOrder;
}

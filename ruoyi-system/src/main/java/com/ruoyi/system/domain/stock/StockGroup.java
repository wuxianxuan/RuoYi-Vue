package com.ruoyi.system.domain.stock;

import com.ruoyi.common.core.domain.BaseEntity;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class StockGroup extends BaseEntity {

    /** 主键 */
    private Long id;

    /** 分组名称 */
    @NotBlank(message = "分组名称不能为空")
    private String groupName;

    /** 排序 */
    private Integer sortOrder;

    /** 分组包含的股票代码列表（仅用于前端传参） */
    private String[] stockCodes;
}

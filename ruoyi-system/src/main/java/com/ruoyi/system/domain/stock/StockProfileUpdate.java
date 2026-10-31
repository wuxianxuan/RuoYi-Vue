package com.ruoyi.system.domain.stock;

import lombok.Data;

/**
 * 股票基础受限编辑请求：只允许改备注和所属分组
 *
 * @author ruoyi
 */
@Data
public class StockProfileUpdate
{
    /** 股票主键 */
    private Long id;

    /** 备注 */
    private String remark;

    /** 所属分组 ID 数组（空数组表示清空） */
    private Long[] groupIds;
}

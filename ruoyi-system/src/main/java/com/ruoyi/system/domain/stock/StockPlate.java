package com.ruoyi.system.domain.stock;

import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

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

    /** 父板块ID */
    private Long parentId;

    /** 祖级列表 */
    private String ancestors;

    /** 层级 */
    private Integer level;

    /** 排序 */
    private Integer sortOrder;

    /** 子板块列表（非持久化，用于树形展示） */
    private List<StockPlate> children;

    /** 股票ID列表（非持久化，仅用于前端传参） */
    private Long[] stockIds;
}

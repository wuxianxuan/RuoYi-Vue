package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.util.List;

@Data
public class ContinuousLimitUp {
    private Integer height;
    private List<StockInfo> stock_list;
}

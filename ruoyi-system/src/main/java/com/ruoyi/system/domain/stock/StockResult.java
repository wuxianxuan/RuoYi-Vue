package com.ruoyi.system.domain.stock;

import lombok.Data;

@Data
public class StockResult {
    private Integer code;
    private String msg;
    private StockData data;
}

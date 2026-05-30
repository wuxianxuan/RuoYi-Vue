package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.util.List;

@Data
public class StockData {
    private List<ContinuousLimitUp> continuous_limit_up;
    private List<PlateStock> plate_stock;
    private Share share;
}

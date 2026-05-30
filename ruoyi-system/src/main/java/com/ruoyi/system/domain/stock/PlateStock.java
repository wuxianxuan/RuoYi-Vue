package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PlateStock {
    private String secu_code;
    private String secu_name;
    private BigDecimal change;
    private String up_reason;
    private Integer plate_stock_up_num;
    private List<PlateStockInfo> stock_list;
}

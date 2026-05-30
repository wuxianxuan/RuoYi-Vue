package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PlateStockInfo {
    private String secu_code;
    private String secu_name;
    private BigDecimal change;
    private BigDecimal last_px;
    private Long cmc;
    private String time;
    private String up_num;
    private String up_reason;
    private List<String> up_tags;
}
package com.ruoyi.system.domain.stock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StockData {
    private List<PlateStock> plate_stock;
}

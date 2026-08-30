package com.ruoyi.web.controller.stock;

import java.util.List;

import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.service.IStockKlineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;

/**
 * K线查询Controller
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/stock/kline")
public class StockKlineController extends BaseController
{
    @Autowired
    private IStockKlineService stockKlineService;

    @GetMapping("/query")
    public AjaxResult query(String stockCode, String klineType, String startDate, String endDate)
    {
        List<StockKline> list = stockKlineService.getKline(stockCode, klineType, startDate, endDate);
        return success(list);
    }
}

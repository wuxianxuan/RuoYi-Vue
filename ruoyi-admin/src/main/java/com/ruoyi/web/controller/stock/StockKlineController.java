package com.ruoyi.web.controller.stock;

import java.util.List;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.service.IStockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.service.IStockKlineService;

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

    @Autowired
    private IStockService stockService;

    @GetMapping("/query")
    public AjaxResult query(String stockCode, String klineType, String startDate, String endDate)
    {
        // 从股票基础表反查市场
        String market = null;
        Stock stock = new Stock();
        stock.setStockCode(stockCode);
        List<Stock> stocks = stockService.selectStockList(stock);
        if (stocks != null && !stocks.isEmpty())
        {
            market = stocks.get(0).getMarket();
        }

        List<StockKline> list = stockKlineService.getKline(stockCode, market, klineType, startDate, endDate);
        return success(list);
    }
}

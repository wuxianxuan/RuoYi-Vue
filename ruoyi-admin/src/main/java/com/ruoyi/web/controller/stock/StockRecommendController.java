package com.ruoyi.web.controller.stock;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.mapper.StockRecommendMapper;
import com.ruoyi.system.service.IStockRecommendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 股票推荐结果 Controller
 */
@RestController
@RequestMapping("/stock/recommend")
public class StockRecommendController extends BaseController
{
    @Autowired
    private StockRecommendMapper stockRecommendMapper;

    @Autowired
    private IStockRecommendService stockRecommendService;

    /**
     * 查询推荐结果列表（默认查最新日期）
     */
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(required = false) String recommendDate)
    {
        startPage();
        if (recommendDate == null || recommendDate.isEmpty())
        {
            recommendDate = LocalDate.now().toString();
        }
        return getDataTable(stockRecommendMapper.selectByDate(recommendDate));
    }

    /**
     * 手动触发推荐
     */
    @Log(title = "股票推荐", businessType = BusinessType.INSERT)
    @PostMapping("/execute")
    public AjaxResult execute(@RequestParam(defaultValue = "20") int topN)
    {
        String tradeDate = LocalDate.now().toString();
        try
        {
            int count = stockRecommendService.executeRecommend(tradeDate, topN);
            return success("推荐完成，共生成 " + count + " 条推荐");
        }
        catch (Exception e)
        {
            return error("推荐执行失败：" + e.getMessage());
        }
    }
}

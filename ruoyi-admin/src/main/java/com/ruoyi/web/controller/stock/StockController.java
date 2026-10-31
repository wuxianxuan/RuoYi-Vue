package com.ruoyi.web.controller.stock;

import java.util.List;
import java.io.IOException;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockProfileUpdate;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.service.IStockService;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 股票基础Controller
 *
 * 股票记录由同步任务维护，页面只读查询 + 受限编辑（备注/所属分组）。
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/stock/base")
public class StockController extends BaseController
{
    @Autowired
    private IStockService stockService;

    /**
     * 查询股票基础列表
     */
    @GetMapping("/list")
    public TableDataInfo list(Stock stock)
    {
        startPage();
        List<Stock> list = stockService.selectStockList(stock);
        return getDataTable(list);
    }

    /**
     * 导出股票基础列表
     */
    @Log(title = "股票基础", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, Stock stock)
    {
        List<Stock> list = stockService.selectStockList(stock);
        ExcelUtil<Stock> util = new ExcelUtil<Stock>(Stock.class);
        util.exportExcel(response, list, "股票基础数据");
    }

    /**
     * 导出股票代码列表（TXT，一行一个股票代码）
     */
    @Log(title = "股票基础", businessType = BusinessType.EXPORT)
    @PostMapping("/exportTxt")
    public void exportTxt(HttpServletResponse response, Stock stock) throws IOException
    {
        List<Stock> list = stockService.selectStockList(stock);
        StringBuilder sb = new StringBuilder();
        if (list != null)
        {
            for (Stock s : list)
            {
                if (s.getStockCode() != null && !s.getStockCode().isEmpty())
                {
                    sb.append(s.getStockCode()).append("\n");
                }
            }
        }
        response.setContentType("text/plain;charset=utf-8");
        response.setHeader("Content-Disposition", "attachment; filename=stock_codes.txt");
        response.getWriter().write(sb.toString());
    }

    /**
     * 获取股票基础详细信息
     */
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(stockService.selectStockById(id));
    }

    /**
     * 受限编辑：只允许修改备注和所属分组
     */
    @Log(title = "股票基础", businessType = BusinessType.UPDATE)
    @PutMapping("/profile")
    public AjaxResult updateProfile(@RequestBody StockProfileUpdate body)
    {
        if (body == null || body.getId() == null)
        {
            return AjaxResult.error("股票ID不能为空");
        }
        return toAjax(stockService.updateStockProfile(body.getId(), body.getRemark(),
                body.getGroupIds(), getUsername()));
    }

    /**
     * 获取股票所属的分组
     */
    @GetMapping("/{stockCode}/groups")
    public AjaxResult getGroups(@PathVariable String stockCode)
    {
        return success(stockService.selectGroupIdsByStockCode(stockCode));
    }

    /**
     * 将股票关联到分组
     */
    @Log(title = "股票基础", businessType = BusinessType.UPDATE)
    @PostMapping("/{stockCode}/groups")
    public AjaxResult bindGroups(@PathVariable String stockCode, @RequestBody Long[] groupIds)
    {
        return toAjax(stockService.insertStockGroups(stockCode, groupIds));
    }
}

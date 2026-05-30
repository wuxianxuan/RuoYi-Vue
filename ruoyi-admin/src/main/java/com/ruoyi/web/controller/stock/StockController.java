package com.ruoyi.web.controller.stock;

import java.util.List;

import com.ruoyi.system.domain.stock.Stock;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * @author ruoyi
 * @date 2026-05-30
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
     * 获取股票基础详细信息
     */
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(stockService.selectStockById(id));
    }

    /**
     * 新增股票基础
     */
    @Log(title = "股票基础", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Stock stock)
    {
        return toAjax(stockService.insertStock(stock));
    }

    /**
     * 修改股票基础
     */
    @Log(title = "股票基础", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Stock stock)
    {
        return toAjax(stockService.updateStock(stock));
    }

    /**
     * 删除股票基础
     */
    @Log(title = "股票基础", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(stockService.deleteStockByIds(ids));
    }

    /**
     * 获取股票所属的分组
     */
    @GetMapping("/{id}/groups")
    public AjaxResult getGroups(@PathVariable Long id)
    {
        return success(stockService.selectGroupIdsByStockId(id));
    }

    /**
     * 将股票关联到分组
     */
    @Log(title = "股票基础", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/groups")
    public AjaxResult bindGroups(@PathVariable Long id, @RequestBody Long[] groupIds)
    {
        return toAjax(stockService.insertStockGroups(id, groupIds));
    }

    /**
     * 股票代码前缀匹配（仅SH/SZ市场，最多10条，按代码升序）
     */
    @GetMapping("/autocomplete")
    public AjaxResult autocomplete(String keyword)
    {
        List<Stock> list = stockService.autocompleteStockCode(keyword);
        return success(list);
    }
}

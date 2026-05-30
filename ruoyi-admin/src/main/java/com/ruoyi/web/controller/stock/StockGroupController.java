package com.ruoyi.web.controller.stock;

import java.util.List;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.service.IStockGroupService;

/**
 * 股票分组Controller
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/stock/group")
public class StockGroupController extends BaseController
{
    @Autowired
    private IStockGroupService stockGroupService;

    @GetMapping("/list")
    public TableDataInfo list(StockGroup stockGroup)
    {
        startPage();
        List<StockGroup> list = stockGroupService.selectGroupList(stockGroup);
        return getDataTable(list);
    }

    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable Long id)
    {
        return success(stockGroupService.selectGroupById(id));
    }

    @Log(title = "股票分组", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody StockGroup stockGroup)
    {
        stockGroup.setCreateBy(getUsername());
        return toAjax(stockGroupService.insertGroup(stockGroup));
    }

    @Log(title = "股票分组", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody StockGroup stockGroup)
    {
        stockGroup.setUpdateBy(getUsername());
        return toAjax(stockGroupService.updateGroup(stockGroup));
    }

    @Log(title = "股票分组", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(stockGroupService.deleteGroupByIds(ids));
    }

    @GetMapping("/all")
    public AjaxResult listAll()
    {
        List<StockGroup> list = stockGroupService.selectGroupList(new StockGroup());
        return success(list);
    }

    /**
     * 获取分组下的股票列表
     */
    @GetMapping("/{id}/stocks")
    public AjaxResult getStocks(@PathVariable Long id)
    {
        return success(stockGroupService.selectStocksByGroupId(id));
    }

    /**
     * 批量添加股票到分组
     */
    @Log(title = "股票分组", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/stocks")
    public AjaxResult addStocks(@PathVariable Long id, @RequestBody Long[] stockIds)
    {
        return toAjax(stockGroupService.insertGroupStocks(id, stockIds));
    }

    /**
     * 从分组中移除股票
     */
    @Log(title = "股票分组", businessType = BusinessType.UPDATE)
    @DeleteMapping("/{id}/stocks")
    public AjaxResult removeStocks(@PathVariable Long id, @RequestBody Long[] stockIds)
    {
        return toAjax(stockGroupService.deleteGroupStocks(id, stockIds));
    }

    /**
     * 查询不在该分组的股票（用于新增弹窗）
     */
    @GetMapping("/{id}/stocks/exclude")
    public TableDataInfo listExcludeStocks(@PathVariable Long id, Stock stock)
    {
        startPage();
        List<Stock> list = stockGroupService.selectStocksNotInGroup(id, stock);
        return getDataTable(list);
    }
}

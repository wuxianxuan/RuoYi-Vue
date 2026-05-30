package com.ruoyi.web.controller.stock;

import java.util.List;

import com.ruoyi.system.domain.stock.StockFavorite;
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
import com.ruoyi.system.service.IStockFavoriteService;

/**
 * 自选股Controller
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/stock/favorite")
public class StockFavoriteController extends BaseController
{
    @Autowired
    private IStockFavoriteService stockFavoriteService;

    @PreAuthorize("@ss.hasPermi('stock:favorite:list')")
    @GetMapping("/list")
    public TableDataInfo list(StockFavorite stockFavorite)
    {
        startPage();
        List<StockFavorite> list = stockFavoriteService.selectFavoriteList(stockFavorite);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('stock:favorite:list')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable Long id)
    {
        return success(stockFavoriteService.selectFavoriteById(id));
    }

    @PreAuthorize("@ss.hasPermi('stock:favorite:add')")
    @Log(title = "自选股", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody StockFavorite stockFavorite)
    {
        stockFavorite.setCreateBy(getUsername());
        return toAjax(stockFavoriteService.insertFavorite(stockFavorite));
    }

    @PreAuthorize("@ss.hasPermi('stock:favorite:edit')")
    @Log(title = "自选股", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody StockFavorite stockFavorite)
    {
        stockFavorite.setUpdateBy(getUsername());
        return toAjax(stockFavoriteService.updateFavorite(stockFavorite));
    }

    @PreAuthorize("@ss.hasPermi('stock:favorite:remove')")
    @Log(title = "自选股", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(stockFavoriteService.deleteFavoriteByIds(ids));
    }
}

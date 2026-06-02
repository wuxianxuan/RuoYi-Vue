package com.ruoyi.web.controller.stock;

import java.util.List;
import java.util.Map;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockPlate;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.ruoyi.system.service.IStockPlateService;

/**
 * 板块管理Controller
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/stock/plate")
public class StockPlateController extends BaseController {

    @Autowired
    private IStockPlateService stockPlateService;

    /**
     * 查询板块列表
     */
    @GetMapping("/list")
    public TableDataInfo list(StockPlate stockPlate) {
        startPage();
        List<StockPlate> list = stockPlateService.selectPlateList(stockPlate);
        return getDataTable(list);
    }

    /**
     * 查询板块详情
     */
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable Long id) {
        return success(stockPlateService.selectPlateById(id));
    }

    /**
     * 新增板块
     */
    @Log(title = "板块管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody StockPlate stockPlate) {
        stockPlate.setCreateBy(getUsername());
        return toAjax(stockPlateService.insertPlate(stockPlate));
    }

    /**
     * 修改板块
     */
    @Log(title = "板块管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody StockPlate stockPlate) {
        stockPlate.setUpdateBy(getUsername());
        return toAjax(stockPlateService.updatePlate(stockPlate));
    }

    /**
     * 删除板块
     */
    @Log(title = "板块管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        return toAjax(stockPlateService.deletePlateByIds(ids));
    }

    /**
     * 查询行业板块树（返回扁平列表，前端构建树）
     */
    @GetMapping("/tree")
    public AjaxResult industryTree() {
        List<StockPlate> list = stockPlateService.selectIndustryPlateList();
        return success(list);
    }

    /**
     * 获取板块下的股票列表（分页）
     */
    @GetMapping("/{id}/stocks")
    public TableDataInfo getStocks(@PathVariable Long id) {
        startPage();
        List<Stock> list = stockPlateService.selectPlateStocks(id);
        return getDataTable(list);
    }

    /**
     * 批量添加股票到板块
     */
    @Log(title = "板块管理", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/stocks")
    public AjaxResult addStocks(@PathVariable Long id, @RequestBody Long[] stockIds) {
        return toAjax(stockPlateService.insertPlateStocks(id, stockIds));
    }

    /**
     * 从板块中移除股票
     */
    @Log(title = "板块管理", businessType = BusinessType.UPDATE)
    @DeleteMapping("/{id}/stocks")
    public AjaxResult removeStocks(@PathVariable Long id, @RequestBody Long[] stockIds) {
        return toAjax(stockPlateService.deletePlateStocks(id, stockIds));
    }

    /**
     * 解析粘贴的股票代码
     */
    @PostMapping("/parse-codes")
    public AjaxResult parseCodes(@RequestBody Map<String, String> params) {
        String text = params.get("text");
        return success(stockPlateService.parseStockCodes(text));
    }
}

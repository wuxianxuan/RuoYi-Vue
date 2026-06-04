package com.ruoyi.web.controller.stock;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.domain.stock.StockLimitUpRequest;
import com.ruoyi.system.service.IStockLimitUpRecordService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stock")
@Anonymous
public class StockLimitUpRecordController {

    @Resource
    private IStockLimitUpRecordService stockAnalysisRecordService;

    @GetMapping("/downloadAnalysisData")
    public AjaxResult downloadAnalysisData(StockLimitUpRequest request) {
        if (request == null || request.getStartDate() == null || request.getEndDate() == null) {
            return AjaxResult.error("请提供开始日期和结束日期");
        }

        String result = stockAnalysisRecordService.downloadAnalysisData(request);
        return AjaxResult.success(result);
    }
}
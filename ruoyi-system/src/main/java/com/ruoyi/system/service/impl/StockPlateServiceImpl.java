package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockPlate;
import com.ruoyi.system.mapper.StockPlateMapper;
import com.ruoyi.system.service.IStockPlateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 板块字典Service业务层处理
 *
 * @author ruoyi
 */
@Service
public class StockPlateServiceImpl implements IStockPlateService {

    @Autowired
    private StockPlateMapper stockPlateMapper;

    // ==================== 板块 CRUD ====================

    @Override
    public List<StockPlate> selectPlateList(StockPlate stockPlate) {
        return stockPlateMapper.selectPlateList(stockPlate);
    }

    @Override
    public StockPlate selectPlateById(Long id) {
        return stockPlateMapper.selectPlateById(id);
    }

    @Override
    @Transactional
    public int insertPlate(StockPlate stockPlate) {
        return stockPlateMapper.insertPlate(stockPlate);
    }

    @Override
    @Transactional
    public int updatePlate(StockPlate stockPlate) {
        return stockPlateMapper.updatePlate(stockPlate);
    }

    @Override
    @Transactional
    public int deletePlateByIds(Long[] ids) {
        for (Long id : ids) {
            StockPlate plate = stockPlateMapper.selectPlateById(id);
            if (plate != null && plate.getPlateCode() != null) {
                stockPlateMapper.deletePlateStocksByPlateCode(plate.getPlateCode());
            }
        }
        return stockPlateMapper.deletePlateByIds(ids);
    }

    @Override
    public List<StockPlate> selectIndustryPlateList() {
        return stockPlateMapper.selectIndustryPlateList();
    }

    @Override
    public List<StockPlate> selectConceptPlateList() {
        return stockPlateMapper.selectConceptPlateList();
    }

    // ==================== 板块-股票关联管理 ====================

    @Override
    public List<Stock> selectPlateStocks(String plateCode) {
        return stockPlateMapper.selectPlateStocksByPlateCode(plateCode);
    }

    @Override
    @Transactional
    public int insertPlateStocks(String plateCode, String[] stockCodes) {
        if (stockCodes == null || stockCodes.length == 0) {
            return 0;
        }
        return stockPlateMapper.insertPlateStocks(plateCode, stockCodes);
    }

    @Override
    @Transactional
    public int replacePlateStocks(String plateCode, String[] stockCodes) {
        stockPlateMapper.deletePlateStocksByPlateCode(plateCode);
        if (stockCodes == null || stockCodes.length == 0) {
            return 0;
        }
        return stockPlateMapper.insertPlateStocks(plateCode, stockCodes);
    }

    @Override
    @Transactional
    public int deletePlateStocks(String plateCode, String[] stockCodes) {
        if (stockCodes == null || stockCodes.length == 0) {
            return 0;
        }
        return stockPlateMapper.deletePlateStocks(plateCode, stockCodes);
    }

    @Override
    @Transactional
    public int updateFocusFlag(Long[] ids, Integer focusFlag) {
        if (ids == null || ids.length == 0 || focusFlag == null) {
            return 0;
        }
        return stockPlateMapper.updateFocusFlagByIds(ids, focusFlag);
    }

    // ==================== 股票代码解析 ====================

    /** 6位纯数字正则 */
    private static final Pattern CODE_PATTERN = Pattern.compile("(?i)^(?:sh|sz|bj|hk|us)?(\\d{6})$");

    @Override
    public Map<String, Object> parseStockCodes(String text) {
        if (text == null || text.trim().isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("matched", Collections.emptyList());
            result.put("unmatched", Collections.emptyList());
            return result;
        }

        // 按换行和逗号拆分
        String[] segments = text.split("[\\n\\r,]+");
        Set<String> codeSet = new LinkedHashSet<>(); // 去重并保持顺序
        List<String> rawSegments = new ArrayList<>();

        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 再尝试按 Tab 拆分（同花顺格式：代码\t名称）
            String[] parts = trimmed.split("\\t");
            String codePart = parts[0].trim();
            rawSegments.add(codePart);

            // 提取6位纯数字
            Matcher matcher = CODE_PATTERN.matcher(codePart);
            if (matcher.find()) {
                codeSet.add(matcher.group(1));
            } else {
                // 尝试从段中提取任意6位连续数字
                Pattern anySix = Pattern.compile("(\\d{6})");
                Matcher anyMatcher = anySix.matcher(codePart);
                if (anyMatcher.find()) {
                    codeSet.add(anyMatcher.group(1));
                }
            }
        }

        Map<String, Object> result = new HashMap<>();

        if (codeSet.isEmpty()) {
            result.put("matched", Collections.emptyList());
            result.put("unmatched", rawSegments);
            return result;
        }

        // 批量匹配 stock 表
        List<String> codeList = new ArrayList<>(codeSet);
        List<Stock> matchedStocks = stockPlateMapper.selectStocksByCodeBatch(codeList);

        // 构建匹配的代码集合
        Set<String> matchedCodes = matchedStocks.stream()
                .map(Stock::getStockCode)
                .collect(Collectors.toSet());

        // 未匹配的代码
        List<String> unmatched = codeList.stream()
                .filter(code -> !matchedCodes.contains(code))
                .collect(Collectors.toList());

        // 构建匹配结果
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Stock stock : matchedStocks) {
            Map<String, Object> item = new HashMap<>();
            item.put("stockCode", stock.getStockCode());
            item.put("stockName", stock.getStockName());
            matched.add(item);
        }

        result.put("matched", matched);
        result.put("unmatched", unmatched);
        return result;
    }
}

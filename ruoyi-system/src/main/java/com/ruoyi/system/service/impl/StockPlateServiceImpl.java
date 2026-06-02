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
        // 计算 level 和 ancestors
        if ("CONCEPT".equals(stockPlate.getPlateType())) {
            // 概念板块固定为一级
            stockPlate.setParentId(0L);
            stockPlate.setAncestors("0");
            stockPlate.setLevel(1);
        } else {
            // 行业板块
            Long parentId = stockPlate.getParentId();
            if (parentId == null || parentId == 0L) {
                stockPlate.setParentId(0L);
                stockPlate.setAncestors("0");
                stockPlate.setLevel(1);
            } else {
                StockPlate parent = stockPlateMapper.selectPlateById(parentId);
                if (parent == null) {
                    throw new RuntimeException("父级板块不存在");
                }
                int newLevel = parent.getLevel() + 1;
                if (newLevel > 3) {
                    throw new RuntimeException("行业板块最多支持三级");
                }
                stockPlate.setLevel(newLevel);
                stockPlate.setAncestors(parent.getAncestors() + "," + parent.getId());
            }
        }
        return stockPlateMapper.insertPlate(stockPlate);
    }

    @Override
    @Transactional
    public int updatePlate(StockPlate stockPlate) {
        StockPlate oldPlate = stockPlateMapper.selectPlateById(stockPlate.getId());
        if (oldPlate == null) {
            throw new RuntimeException("板块不存在");
        }

        // 如果是行业板块，且父级发生了变化，需要更新层级和ancestors
        if ("INDUSTRY".equals(oldPlate.getPlateType()) && stockPlate.getParentId() != null) {
            Long newParentId = stockPlate.getParentId();
            Long oldParentId = oldPlate.getParentId();

            if (!Objects.equals(newParentId, oldParentId)) {
                // 层级调整
                if (newParentId == null || newParentId == 0L) {
                    stockPlate.setParentId(0L);
                    stockPlate.setAncestors("0");
                    stockPlate.setLevel(1);
                } else {
                    StockPlate newParent = stockPlateMapper.selectPlateById(newParentId);
                    if (newParent == null) {
                        throw new RuntimeException("父级板块不存在");
                    }
                    int newLevel = newParent.getLevel() + 1;
                    if (newLevel > 3) {
                        throw new RuntimeException("行业板块最多支持三级");
                    }
                    stockPlate.setLevel(newLevel);
                    stockPlate.setAncestors(newParent.getAncestors() + "," + newParent.getId());
                }

                // 更新后代板块的 ancestors
                updateDescendantsAncestors(stockPlate.getId(), oldPlate.getAncestors(),
                        stockPlate.getAncestors());

                // 层级调整时同步 industry_name
                syncIndustryNameOnHierarchyChange(stockPlate.getId());
            }
        }

        int rows = stockPlateMapper.updatePlate(stockPlate);

        // 如果行业板块改名，同步 industry_name
        if ("INDUSTRY".equals(oldPlate.getPlateType()) && stockPlate.getPlateName() != null
                && !stockPlate.getPlateName().equals(oldPlate.getPlateName())) {
            syncIndustryNameOnRename(stockPlate.getId());
        }

        return rows;
    }

    @Override
    @Transactional
    public int deletePlateByIds(Long[] ids) {
        for (Long id : ids) {
            StockPlate plate = stockPlateMapper.selectPlateById(id);
            if (plate == null) {
                continue;
            }
            // 检查是否有子级
            if ("INDUSTRY".equals(plate.getPlateType()) && hasChildByParentId(id)) {
                throw new RuntimeException("板块「" + plate.getPlateName() + "」存在子级板块，无法删除");
            }
            // 级联删除关联记录
            if ("INDUSTRY".equals(plate.getPlateType())) {
                // 获取关联的股票，清除 industry_name
                List<Stock> stocks = stockPlateMapper.selectPlateStocksByPlateId(id);
                stockPlateMapper.deletePlateStocksByPlateId(id);
                // 同步 industry_name
                for (Stock stock : stocks) {
                    syncIndustryNameOnRemove(stock.getId());
                }
            } else {
                stockPlateMapper.deletePlateStocksByPlateId(id);
            }
        }
        return stockPlateMapper.deletePlateByIds(ids);
    }

    @Override
    public List<StockPlate> selectIndustryPlateList() {
        return stockPlateMapper.selectIndustryPlateList();
    }

    @Override
    public boolean hasChildByParentId(Long parentId) {
        return stockPlateMapper.hasChildByParentId(parentId) > 0;
    }

    // ==================== 板块-股票关联管理 ====================

    @Override
    public List<Stock> selectPlateStocks(Long plateId) {
        return stockPlateMapper.selectPlateStocksByPlateId(plateId);
    }

    @Override
    @Transactional
    public int insertPlateStocks(Long plateId, Long[] stockIds) {
        if (stockIds == null || stockIds.length == 0) {
            return 0;
        }
        int rows = stockPlateMapper.insertPlateStocks(plateId, stockIds);

        // 如果是行业板块，同步 industry_name
        StockPlate plate = stockPlateMapper.selectPlateById(plateId);
        if (plate != null && "INDUSTRY".equals(plate.getPlateType())) {
            for (Long stockId : stockIds) {
                syncIndustryNameOnAdd(stockId, plateId);
            }
        }

        return rows;
    }

    @Override
    @Transactional
    public int deletePlateStocks(Long plateId, Long[] stockIds) {
        if (stockIds == null || stockIds.length == 0) {
            return 0;
        }
        int rows = stockPlateMapper.deletePlateStocks(plateId, stockIds);

        // 如果是行业板块，同步 industry_name
        StockPlate plate = stockPlateMapper.selectPlateById(plateId);
        if (plate != null && "INDUSTRY".equals(plate.getPlateType())) {
            for (Long stockId : stockIds) {
                syncIndustryNameOnRemove(stockId);
            }
        }

        return rows;
    }

    // ==================== industry_name 同步逻辑 ====================

    /**
     * 构建行业全路径
     * 沿 ancestors 上溯查询所有祖先板块名称，按 level ASC 拼接为 "L1>L2>L3"
     */
    private String buildIndustryPath(Long plateId) {
        StockPlate plate = stockPlateMapper.selectPlateById(plateId);
        if (plate == null) {
            return "";
        }

        // 解析 ancestors 获取祖先 ID 列表
        List<Long> ancestorIds = new ArrayList<>();
        if (plate.getAncestors() != null && !plate.getAncestors().isEmpty()) {
            String[] parts = plate.getAncestors().split(",");
            for (String part : parts) {
                try {
                    long id = Long.parseLong(part.trim());
                    if (id != 0L) {
                        ancestorIds.add(id);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // 查询所有祖先板块
        List<String> namePath = new ArrayList<>();
        if (!ancestorIds.isEmpty()) {
            List<StockPlate> ancestors = stockPlateMapper.selectPlatesByIds(ancestorIds);
            // 按 level 排序
            ancestors.sort(Comparator.comparingInt(StockPlate::getLevel));
            for (StockPlate ancestor : ancestors) {
                namePath.add(ancestor.getPlateName());
            }
        }
        // 加上当前板块名称
        namePath.add(plate.getPlateName());

        return String.join(">", namePath);
    }

    /**
     * 添加股票到行业板块时同步 industry_name
     */
    private void syncIndustryNameOnAdd(Long stockId, Long plateId) {
        String industryPath = buildIndustryPath(plateId);
        stockPlateMapper.updateStockIndustryName(stockId, industryPath);
    }

    /**
     * 从行业板块移除股票时同步 industry_name
     * 检查是否还有其他行业关联，有则更新为新路径，无则置空
     */
    private void syncIndustryNameOnRemove(Long stockId) {
        List<Long> industryPlateIds = stockPlateMapper.selectIndustryPlateIdsByStockId(stockId);
        if (industryPlateIds != null && !industryPlateIds.isEmpty()) {
            // 还有其他行业关联，使用第一个计算全路径
            String industryPath = buildIndustryPath(industryPlateIds.get(0));
            stockPlateMapper.updateStockIndustryName(stockId, industryPath);
        } else {
            // 无行业关联，置空
            stockPlateMapper.updateStockIndustryName(stockId, "");
        }
    }

    /**
     * 行业板块改名时批量更新关联股票的 industry_name
     */
    private void syncIndustryNameOnRename(Long plateId) {
        // 查后代板块 + 关联股票
        List<Long> descendantIds = stockPlateMapper.selectDescendantPlateIds(plateId);
        Set<Long> allPlateIds = new HashSet<>(descendantIds);
        allPlateIds.add(plateId);

        for (Long pid : allPlateIds) {
            List<Stock> stocks = stockPlateMapper.selectPlateStocksByPlateId(pid);
            for (Stock stock : stocks) {
                // 找到该股票关联的行业板块
                List<Long> stockIndustryIds = stockPlateMapper.selectIndustryPlateIdsByStockId(stock.getId());
                if (stockIndustryIds != null && !stockIndustryIds.isEmpty()) {
                    String industryPath = buildIndustryPath(stockIndustryIds.get(0));
                    stockPlateMapper.updateStockIndustryName(stock.getId(), industryPath);
                }
            }
        }
    }

    /**
     * 层级调整时更新后代的 ancestors 并同步 industry_name
     */
    private void updateDescendantsAncestors(Long plateId, String oldAncestors, String newAncestors) {
        List<Long> descendantIds = stockPlateMapper.selectDescendantPlateIds(plateId);
        for (Long descendantId : descendantIds) {
            StockPlate descendant = stockPlateMapper.selectPlateById(descendantId);
            if (descendant != null) {
                String updatedAncestors = descendant.getAncestors().replace(oldAncestors + "," + plateId,
                        newAncestors + "," + plateId);
                descendant.setAncestors(updatedAncestors);
                // 计算新的 level
                int newLevel = updatedAncestors.split(",").length;
                descendant.setLevel(newLevel);
                stockPlateMapper.updatePlate(descendant);
            }
        }
    }

    /**
     * 层级调整时批量刷新受影响股票的 industry_name
     */
    private void syncIndustryNameOnHierarchyChange(Long plateId) {
        List<Long> descendantIds = stockPlateMapper.selectDescendantPlateIds(plateId);
        Set<Long> allPlateIds = new HashSet<>(descendantIds);
        allPlateIds.add(plateId);

        Set<Long> affectedStockIds = new HashSet<>();
        for (Long pid : allPlateIds) {
            List<Stock> stocks = stockPlateMapper.selectPlateStocksByPlateId(pid);
            for (Stock stock : stocks) {
                affectedStockIds.add(stock.getId());
            }
        }

        for (Long stockId : affectedStockIds) {
            List<Long> stockIndustryIds = stockPlateMapper.selectIndustryPlateIdsByStockId(stockId);
            if (stockIndustryIds != null && !stockIndustryIds.isEmpty()) {
                String industryPath = buildIndustryPath(stockIndustryIds.get(0));
                stockPlateMapper.updateStockIndustryName(stockId, industryPath);
            } else {
                stockPlateMapper.updateStockIndustryName(stockId, "");
            }
        }
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
            item.put("id", stock.getId());
            item.put("stockCode", stock.getStockCode());
            item.put("stockName", stock.getStockName());
            matched.add(item);
        }

        result.put("matched", matched);
        result.put("unmatched", unmatched);
        return result;
    }
}

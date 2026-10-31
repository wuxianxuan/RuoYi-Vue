package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.mapper.StockGroupMapper;
import com.ruoyi.system.service.IStockGroupCleanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 股票分组清理实现
 *
 * @author ruoyi
 */
@Service
public class StockGroupCleanServiceImpl implements IStockGroupCleanService
{
    private static final Logger log = LoggerFactory.getLogger(StockGroupCleanServiceImpl.class);

    @Autowired
    private StockGroupMapper stockGroupMapper;

    @Override
    @Transactional
    public int clean(int keepDays)
    {
        int days = keepDays > 0 ? keepDays : 30;
        List<StockGroup> groups = stockGroupMapper.selectExpiredDatedGroups(days);
        if (groups == null || groups.isEmpty())
        {
            log.info("没有超过 {} 天的日期分组需要清理", days);
            return 0;
        }
        int deleted = 0;
        for (StockGroup group : groups)
        {
            stockGroupMapper.deleteGroupStocksByGroupId(group.getId());
            stockGroupMapper.deleteGroupById(group.getId());
            deleted++;
        }
        log.info("日期分组清理完成: 删除 {} 个（保留最近 {} 天）", deleted, days);
        return deleted;
    }
}

package com.ruoyi.system.datasource;

import java.util.List;

import com.ruoyi.system.domain.stock.StockKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class KlineDataSourceRouter
{
    private static final Logger log = LoggerFactory.getLogger(KlineDataSourceRouter.class);

    @Autowired
    private List<KlineDataSource> dataSources;

    public List<StockKline> fetchKline(String stockCode, String market, String klineType,
                                       String startDate, String endDate)
    {
        for (KlineDataSource ds : dataSources)
        {
            try
            {
                log.info("尝试从 {} 获取K线数据: stockCode={}", ds.getName(), stockCode);
                List<StockKline> result = ds.fetchKline(stockCode, market, klineType, startDate, endDate);
                if (result != null && !result.isEmpty())
                {
                    log.info("从 {} 成功获取 {} 条K线数据", ds.getName(), result.size());
                    return result;
                }
                log.warn("从 {} 获取K线数据为空，尝试下一个数据源", ds.getName());
            }
            catch (Exception e)
            {
                log.warn("从 {} 获取K线数据失败: {}", ds.getName(), e.getMessage());
            }
        }
        throw new RuntimeException("所有数据源均无法获取K线数据: stockCode=" + stockCode);
    }
}

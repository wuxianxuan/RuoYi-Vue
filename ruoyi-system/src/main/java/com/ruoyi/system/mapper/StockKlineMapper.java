package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockKline;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockKlineMapper {
    public List<StockKline> selectKlineList(StockKline stockKline);

    public List<StockKline> selectByCodeTypeDateRange(@Param("stockCode") String stockCode,
                                                      @Param("klineType") String klineType,
                                                      @Param("startDate") String startDate,
                                                      @Param("endDate") String endDate);

    public int insertKline(StockKline stockKline);

    public int insertIgnoreKline(StockKline stockKline);

    public int insertIgnoreKlineBatch(List<StockKline> klineList);

    public int deleteKlineByCodeType(String stockCode, String klineType);
}

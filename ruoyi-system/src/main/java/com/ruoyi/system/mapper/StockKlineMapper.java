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

    /**
     * 批量查询多个股票在指定日期范围内的K线数据
     */
    public List<StockKline> selectKlineByCodesAndDays(@Param("stockCodes") List<String> stockCodes,
                                                       @Param("klineType") String klineType,
                                                       @Param("startDate") String startDate,
                                                       @Param("endDate") String endDate);
}

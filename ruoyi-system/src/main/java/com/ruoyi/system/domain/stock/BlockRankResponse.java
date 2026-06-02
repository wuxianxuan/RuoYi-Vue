package com.ruoyi.system.domain.stock;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.*;

/**
 * 同花顺板块排名接口响应对象
 *
 * API: https://d.10jqka.com.cn/v2/blockrank/{plateCode}/{sortField}/d30.js
 * 示例: https://d.10jqka.com.cn/v2/blockrank/881145/199112/d30.js (电力板块按30日涨跌幅排名)
 *
 * @author ruoyi
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlockRankResponse {

    /** 板块信息 */
    private BlockInfo block;

    /** 排名股票列表 */
    private List<BlockRankItem> items;

    // ==================== 板块信息 ====================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BlockInfo {
        /** 板块名称 (field: name) */
        private String name;

        /** 成分股数量 (field: subcodeCount) */
        private Integer subcodeCount;

        /** 额外的动态字段 */
        private Map<String, String> extraFields = new HashMap<>();

        @JsonAnySetter
        public void setExtraField(String key, String value) {
            if (!"name".equals(key) && !"subcodeCount".equals(key)) {
                this.extraFields.put(key, value);
            }
        }
    }

    // ==================== 板块排名股票项 ====================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BlockRankItem {
        /** 所有原始字段 */
        private Map<String, String> fields = new HashMap<>();

        @JsonAnySetter
        public void setField(String key, String value) {
            this.fields.put(key, value);
        }

        // -- 常用字段的便捷 getter --

        /** 股票代码 (field: "5") */
        public String getStockCode() {
            return fields.get("5");
        }

        /** 股票名称 (field: "55") */
        public String getStockName() {
            return fields.get("55");
        }

        /** 最新价 (field: "10") */
        public BigDecimal getPrice() {
            return toBigDecimal(fields.get("10"));
        }

        /** 开盘价 (field: "7") */
        public BigDecimal getOpenPrice() {
            return toBigDecimal(fields.get("7"));
        }

        /** 最高价 (field: "8") */
        public BigDecimal getHighPrice() {
            return toBigDecimal(fields.get("8"));
        }

        /** 最低价 (field: "9") */
        public BigDecimal getLowPrice() {
            return toBigDecimal(fields.get("9"));
        }

        /** 昨收价 (field: "6") */
        public BigDecimal getPreClose() {
            return toBigDecimal(fields.get("6"));
        }

        /** 成交量(手) (field: "13") */
        public BigDecimal getVolume() {
            return toBigDecimal(fields.get("13"));
        }

        /** 成交额(元) (field: "19") */
        public BigDecimal getTurnover() {
            return toBigDecimal(fields.get("19"));
        }

        /** 换手率(%) (field: "2034120") */
        public BigDecimal getTurnoverRate() {
            return toBigDecimal(fields.get("2034120"));
        }

        /** 总市值 (field: "3475914") */
        public BigDecimal getTotalMarketCap() {
            return toBigDecimal(fields.get("3475914"));
        }

        /** 流通市值 (field: "3541450") */
        public BigDecimal getCircMarketCap() {
            return toBigDecimal(fields.get("3541450"));
        }

        /** 市盈率(PE) (field: "1968584") */
        public BigDecimal getPeRatio() {
            return toBigDecimal(fields.get("1968584"));
        }

        /**
         * 获取排序字段的值（涨跌幅等，field code 由 URL 参数 sortField 决定）
         * @param sortField 排序字段代码，如 "199112"=涨跌幅, "264648"=另一个指标
         */
        public BigDecimal getSortValue(String sortField) {
            return toBigDecimal(fields.get(sortField));
        }

        /**
         * 获取任意字段值
         */
        public String getField(String key) {
            return fields.get(key);
        }

        /**
         * 获取任意字段的 BigDecimal 值
         */
        public BigDecimal getFieldAsDecimal(String key) {
            return toBigDecimal(fields.get(key));
        }

        private BigDecimal toBigDecimal(String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /**
         * 判断市场：根据股票代码前两位判断
         */
        public String getMarket() {
            String code = getStockCode();
            if (code == null) return null;
            if (code.startsWith("6") || code.startsWith("9")) return "SH";
            if (code.startsWith("0") || code.startsWith("2") || code.startsWith("3")) return "SZ";
            if (code.startsWith("8") || code.startsWith("4")) return "BJ";
            return null;
        }
    }

    // ==================== 便捷方法 ====================

    /**
     * 获取板块名称
     */
    public String getPlateName() {
        return block != null ? block.getName() : null;
    }

    /**
     * 获取成分股数量
     */
    public Integer getStockCount() {
        return block != null ? block.getSubcodeCount() : 0;
    }

    /**
     * 获取排名列表（保证非 null）
     */
    public List<BlockRankItem> getItems() {
        return items != null ? items : Collections.emptyList();
    }

    /**
     * 按股票代码筛选
     */
    public BlockRankItem findByStockCode(String stockCode) {
        if (items == null || stockCode == null) return null;
        return items.stream()
                .filter(item -> stockCode.equals(item.getStockCode()))
                .findFirst()
                .orElse(null);
    }
}

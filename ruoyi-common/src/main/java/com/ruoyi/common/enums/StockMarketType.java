package com.ruoyi.common.enums;

public enum StockMarketType {

    SH("SH","sh"),
    SZ("SZ","sz"),
    BJ("BJ","bj"),
    HK("HK","hk"),
    US("US","us"),
    ;

    private final String code;
    private final String info;

    StockMarketType(String code, String info)
    {
        this.code = code;
        this.info = info;
    }

    public String getCode()
    {
        return code;
    }

    public String getInfo()
    {
        return info;
    }
}

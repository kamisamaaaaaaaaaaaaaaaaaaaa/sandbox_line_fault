package cn.chinaclear.fault.testlib;

import java.math.BigDecimal;

/** 白名单验证 lib：金额校验（多方法多行） */
public class AmountChecker {

    public boolean isValid(BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        BigDecimal zero = BigDecimal.ZERO;
        int cmp = amount.compareTo(zero);
        return cmp > 0;
    }

    public boolean isLarge(BigDecimal amount) {
        BigDecimal threshold = new BigDecimal("10000");
        boolean nullCheck = amount != null;
        if (!nullCheck) {
            return false;
        }
        int cmp = amount.compareTo(threshold);
        return cmp >= 0;
    }
}

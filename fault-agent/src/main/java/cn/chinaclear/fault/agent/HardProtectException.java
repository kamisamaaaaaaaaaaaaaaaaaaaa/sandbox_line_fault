package cn.chinaclear.fault.agent;

import java.util.List;

/**
 * 硬保护异常：premain 任何阶段超时/异常时抛出，
 * 顶层统一处理：表1 置 failed（仅解析中单元）→ 写表4 → kill 当前进程。
 */
public final class HardProtectException extends RuntimeException {

    /** PARSE | DB | MOUNT */
    public final String phase;
    /** TIMEOUT | EXCEPTION */
    public final String errorType;
    public final String detail;
    /** 相关单元 id（仅用于表4 留痕；表1 不做回退，未完成单元下次启动重新解析） */
    public final List<Long> unitIds;
    public final String bootJar;

    public HardProtectException(String phase, String errorType, String message,
                                String detail, List<Long> unitIds, String bootJar) {
        super(message);
        this.phase = phase;
        this.errorType = errorType;
        this.detail = detail;
        this.unitIds = unitIds;
        this.bootJar = bootJar;
    }

    public static HardProtectException timeout(String phase, String message, String detail,
                                               List<Long> unitIds, String bootJar) {
        return new HardProtectException(phase, "TIMEOUT", message, detail, unitIds, bootJar);
    }

    public static HardProtectException exception(String phase, String message, String detail,
                                                 List<Long> unitIds, String bootJar) {
        return new HardProtectException(phase, "EXCEPTION", message, detail, unitIds, bootJar);
    }
}

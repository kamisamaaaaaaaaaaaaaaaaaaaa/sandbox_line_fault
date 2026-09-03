package cn.chinaclear.fault.common.model;

import lombok.Data;

import java.util.Date;

/** 表3 t_fault_record：故障注入记录（判重键 = tag + bootJar 部署路径 + 类 + 方法 + 行） */
@Data
public class FaultRecord {
    private long unitId;
    /** 故障注入轮次（JVM -Dfault.tag） */
    private String tag;
    private String hostname;
    private String ip;
    /** 应用 bootJar 完整部署路径（判重键：路径即应用标识） */
    private String bootJar;
    /** MD5(bootJar) 前 16 位 hex（索引键） */
    private String bootJarHash;
    private String className;
    private String methodName;
    private int lineNo;
    private String threadName;
    /** 该行该线程本轮的第几次故障（从 1 开始），上限由 inject.fault.times 决定 */
    private int faultSeq;
    private String faultType;
    private Date occurredAt;
}

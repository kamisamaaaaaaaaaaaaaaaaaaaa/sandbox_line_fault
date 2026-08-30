package cn.chinaclear.fault.common.model;

import lombok.Data;

import java.util.Date;

/** 表3 t_fault_record：故障注入记录（集群级抢占命中） */
@Data
public class FaultRecord {
    private long unitId;
    private String hostname;
    private String ip;
    private String className;
    private String methodName;
    private int lineNo;
    private String threadName;
    private String faultType;
    private Date occurredAt;
}

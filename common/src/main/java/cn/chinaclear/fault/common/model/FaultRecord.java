package cn.chinaclear.fault.common.model;

import lombok.Data;

import java.util.Date;

/** 表3 t_fault_record：故障注入记录（判重键 = tag + bootJar 部署路径 + 类 + 方法 + 行 + 线程 + 调用栈摘要 + 第几次故障） */
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
    /**
     * 命中方法的 ASM 描述符（如 (Ljava/lang/String;)V），供排查时区分重载。
     * 纯观测字段、不入索引：(class_name, method_name, line_no) 已能唯一定位一个重载
     * （同一类里两个方法的行号表不重叠）。
     */
    private String methodDesc;
    private int lineNo;
    private String threadName;
    /** 该行该线程该调用栈本轮的第几次故障（从 1 开始），上限由 inject.fault.times 决定 */
    private int faultSeq;
    /**
     * 调用栈摘要：{@link #stackText} 的 MD5 32 位小写 hex，判重键组成部分。
     * 调用栈原文长度不定且远超索引字节预算，故以摘要入索引、原文另存文本列，两者由同一次取栈产生。
     */
    private String stackHash;
    /** 触发故障时的调用栈（已裁剪取栈入口 / sandbox / 本模块帧），帧格式「类.方法(文件:行)」，换行分隔 */
    private String stackText;
    private String faultType;
    private Date occurredAt;
}

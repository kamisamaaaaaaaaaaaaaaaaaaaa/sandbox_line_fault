package cn.chinaclear.fault.common.model;

import lombok.Data;

import java.util.Date;

/** 表4 t_error_record：agent 自身错误记录（写入后进程将被 kill） */
@Data
public class ErrorRecord {
    /** PARSE | DB | MOUNT */
    private String phase;
    /** TIMEOUT | EXCEPTION */
    private String errorType;
    private String message;
    private String detail;
    private String unitIds;
    private String bootJar;
    private String hostname;
    private String ip;
    private Date createdAt;
}

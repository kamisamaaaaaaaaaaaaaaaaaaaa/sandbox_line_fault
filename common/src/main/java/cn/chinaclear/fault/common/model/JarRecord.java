package cn.chinaclear.fault.common.model;

import lombok.Data;

import java.util.Date;

/** 表1 t_jar_record：解析单元记录 */
@Data
public class JarRecord {
    private Long id;
    private String unitType;
    private String sha256;
    private String sourceJar;
    private String bootJar;
    private String hostname;
    private String ip;
    private Integer classCount;
    private Integer methodCount;
    private String status;
    private Date parsedAt;
    private Date updatedAt;
}

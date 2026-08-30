package cn.chinaclear.fault.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 表2 t_class_method：类-方法解析结果 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassMethodInfo {
    /** 表1 t_jar_record 主键；模块间传参/表3 关联均用该短 id，不用长 hash */
    private long unitId;
    /** 完全限定名（点分），如 com.demo.OrderService */
    private String className;
    /** 方法名（onBehavior 按名匹配，天然覆盖重载） */
    private String methodName;
    /** ASM 描述符，如 (Ljava/lang/String;)V，用于唯一索引区分重载 */
    private String methodDesc;
}

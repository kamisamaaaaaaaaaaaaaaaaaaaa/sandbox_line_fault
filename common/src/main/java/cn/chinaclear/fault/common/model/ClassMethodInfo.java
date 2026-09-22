package cn.chinaclear.fault.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 表2 t_class_method：类-方法解析结果 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassMethodInfo {
    /** 表2 主键，模块侧按此游标分批读取 */
    private long id;
    /** 表1 t_jar_record 主键；模块间传参/表3 关联均用该短 id，不用长 hash */
    private long unitId;
    /** 完全限定名（点分），如 com.demo.OrderService */
    private String className;
    /** 方法名（onBehavior 按名匹配，天然覆盖重载） */
    private String methodName;
    /**
     * ASM 描述符完整原文，如 (Ljava/lang/String;)V。超多参数方法的描述符可达 KB 级，
     * 以 TEXT 列完整保留、不入索引（长度不定，入索引会超字节预算）。
     */
    private String methodDesc;
    /**
     * 描述符摘要：描述符的 MD5 前 16 位 hex，唯一索引组成部分，用于区分重载。
     * 与表3 boot_jar_hash 同一模式：摘要入索引、原文另存。
     */
    private String descHash;
    /**
     * 方法有效代码行数：该方法 LineNumberTable 中不同行号的个数。
     * 注释行与空行没有字节码、天然不计入；纯 '}'、单独 else 之类无字节码的行同样不计入
     * （等价 JaCoCo 的「有效代码行」口径）。纯观测字段：不入唯一键、不参与注入。
     * 用包装类型承载 NULL：null 表示该 class 未编译行号信息（如 javac -g:none），
     * 与「真的 0 行」区分；模块侧 watch 注册用不到它，读取时置 null。
     */
    private Integer codeLines;
}

package cn.chinaclear.fault.common;

import java.util.List;

/**
 * 解析过滤参数对象：两类解析单元（classes / libs）各自的名字与内容两维过滤配置，
 * 由调用方从 FaultConfig 组装后传入 {@link BootJarParser#parse}。
 *
 * 过滤语义：同一维度内多个条件是 OR（任一条件命中即通过，命中即早退）；
 * 跨维度是 AND（libs 的 jar 要同时过名字白名单与内容过滤，classes 要同时过 enabled 开关与内容过滤）。
 * 内容过滤未配置（空列表）= 该维不限制，行为与未引入内容过滤前完全一致。
 */
public final class ParseFilter {

    /** 是否解析 BOOT-INF/classes 单元（名字维度开关，原 parse.classes.enabled） */
    public final boolean classesEnabled;
    /** classes 内容过滤正则原文（对 classes 下 .class 条目相对路径全串匹配）；空 = 不限制 */
    public final List<String> classesEntries;
    /** libs 名字过滤正则原文（对 jar 文件名全串匹配，原 lib.whitelist） */
    public final List<String> libWhitelist;
    /** libs 内容过滤正则原文（对 jar 内每个条目完整路径全串匹配）；空 = 不限制 */
    public final List<String> libEntries;

    public ParseFilter(boolean classesEnabled, List<String> classesEntries,
                       List<String> libWhitelist, List<String> libEntries) {
        this.classesEnabled = classesEnabled;
        this.classesEntries = classesEntries;
        this.libWhitelist = libWhitelist;
        this.libEntries = libEntries;
    }
}

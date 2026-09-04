package cn.chinaclear.fault.common.dao;

import cn.chinaclear.fault.common.JdbcHelper;
import cn.chinaclear.fault.common.model.ErrorRecord;

/**
 * 表 t_error_record：agent 自身错误记录。
 *
 * 写入侧统一在本类按列宽兜底：本表有 4 条写入路径（agent 硬保护收口、模块注入失败、
 * 模块缺 tag、beforeLine 异常），其中模块侧 3 条自身不做截断，
 * 且错误消息可能携带超长问题数据（如完整方法描述符，KB 级）——
 * 若不兜底会 Data too long 导致错误记录本身写不进去，只剩本地日志。
 */
public final class ErrorRecordDao {

    /**
     * 与各列容量一致：message / detail 为 MEDIUMTEXT，上限按字符数取 100 万
     * （utf8mb4 最坏 4 字节/字符即 4MB，远低于 MEDIUMTEXT 的 16MB），足以完整保留任何错误消息与异常栈
     */
    private static final int MAX_PHASE = 16;
    private static final int MAX_ERROR_TYPE = 16;
    private static final int MAX_MESSAGE = 1_000_000;
    private static final int MAX_DETAIL = 1_000_000;
    private static final int MAX_UNIT_IDS = 256;
    private static final int MAX_BOOT_JAR = 512;
    private static final int MAX_HOSTNAME = 128;
    private static final int MAX_IP = 64;

    private final JdbcHelper db;

    public ErrorRecordDao(JdbcHelper db) {
        this.db = db;
    }

    public void insert(ErrorRecord er) {
        String sql = "INSERT INTO t_error_record"
                + " (phase, error_type, message, detail, unit_ids, boot_jar, hostname, ip, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,NOW())";
        db.execute(sql, new Object[]{
                fit(er.getPhase(), MAX_PHASE),
                fit(er.getErrorType(), MAX_ERROR_TYPE),
                fit(er.getMessage(), MAX_MESSAGE),
                fit(er.getDetail(), MAX_DETAIL),
                fit(er.getUnitIds(), MAX_UNIT_IDS),
                fit(er.getBootJar(), MAX_BOOT_JAR),
                fit(er.getHostname(), MAX_HOSTNAME),
                fit(er.getIp(), MAX_IP)});
    }

    /**
     * 按列宽截断：超出部分以尾部标记提示。
     * 被截断的字段（尤其是 message）其完整内容通常同时存在于 detail 或本地日志中。
     */
    private static String fit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        String tail = "...[truncated]";
        return value.substring(0, Math.max(0, max - tail.length())) + tail;
    }
}

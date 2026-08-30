package cn.chinaclear.fault.common.dao;

import cn.chinaclear.fault.common.JdbcHelper;
import cn.chinaclear.fault.common.model.ErrorRecord;

/** 表4 t_error_record：agent 自身错误记录 */
public final class ErrorRecordDao {

    private final JdbcHelper db;

    public ErrorRecordDao(JdbcHelper db) {
        this.db = db;
    }

    public void insert(ErrorRecord er) {
        String sql = "INSERT INTO t_error_record"
                + " (phase, error_type, message, detail, unit_ids, boot_jar, hostname, ip, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,NOW())";
        db.execute(sql, new Object[]{er.getPhase(), er.getErrorType(), er.getMessage(),
                er.getDetail(), er.getUnitIds(), er.getBootJar(), er.getHostname(), er.getIp()});
    }
}

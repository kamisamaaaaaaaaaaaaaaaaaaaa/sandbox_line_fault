package cn.chinaclear.fault.common.dao;

import cn.chinaclear.fault.common.JdbcHelper;
import cn.chinaclear.fault.common.MachineInfo;
import cn.chinaclear.fault.common.model.JarRecord;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;

/** 表1 t_jar_record：pending 占位插入、状态机查重与流转 */
public final class JarRecordDao {

    private final JdbcHelper db;

    public JarRecordDao(JdbcHelper db) {
        this.db = db;
    }

    /**
     * 尝试插入 pending 占位行（sha256 唯一键并发防重）。
     * 返回新记录 id；null = 已存在（调用方读状态走状态机分支）。
     */
    public Long tryInsertPending(String unitType, String sha256, String sourceJar, String bootJar) {
        String sql = "INSERT IGNORE INTO t_jar_record"
                + " (unit_type, sha256, source_jar, boot_jar, hostname, ip, status)"
                + " VALUES (?,?,?,?,?,?,'pending')";
        final Long[] newId = new Long[1];
        boolean inserted = db.executeInsertIgnore(sql,
                new Object[]{unitType, sha256, sourceJar, bootJar, MachineInfo.hostname(), MachineInfo.ip()},
                key -> newId[0] = key);
        return inserted ? newId[0] : null;
    }

    public JarRecord findBySha256(String sha256) {
        return db.query("SELECT * FROM t_jar_record WHERE sha256 = ?",
                new Object[]{sha256}, JarRecordDao::map).stream().findFirst().orElse(null);
    }

    /**
     * 抢占重解析：条件 UPDATE 防并发，同时刷新 hostname/ip 为本节点
     * （保证本次重解析若再中断，pending 行可被本机立即识别接管）。
     */
    public boolean claimForReparse(long id, String hostname, String ip) {
        int n = db.execute("UPDATE t_jar_record"
                        + " SET status='pending', parsed_at=NULL, hostname=?, ip=?"
                        + " WHERE id=? AND status IN ('failed','pending')",
                new Object[]{hostname, ip, id});
        return n == 1;
    }

    public void markCompleted(long id, int classCount, int methodCount) {
        db.execute("UPDATE t_jar_record"
                        + " SET status='completed', class_count=?, method_count=?, parsed_at=NOW()"
                        + " WHERE id=?",
                new Object[]{classCount, methodCount, id});
    }

    public void markFailed(long id) {
        db.execute("UPDATE t_jar_record SET status='failed' WHERE id=?", new Object[]{id});
    }

    static JarRecord map(ResultSet rs) throws SQLException {
        JarRecord r = new JarRecord();
        r.setId(rs.getLong("id"));
        r.setUnitType(rs.getString("unit_type"));
        r.setSha256(rs.getString("sha256"));
        r.setSourceJar(rs.getString("source_jar"));
        r.setBootJar(rs.getString("boot_jar"));
        r.setHostname(rs.getString("hostname"));
        r.setIp(rs.getString("ip"));
        r.setClassCount((Integer) rs.getObject("class_count"));
        r.setMethodCount((Integer) rs.getObject("method_count"));
        r.setStatus(rs.getString("status"));
        Timestamp p = rs.getTimestamp("parsed_at");
        r.setParsedAt(p == null ? null : new Date(p.getTime()));
        Timestamp u = rs.getTimestamp("updated_at");
        r.setUpdatedAt(u == null ? null : new Date(u.getTime()));
        return r;
    }
}

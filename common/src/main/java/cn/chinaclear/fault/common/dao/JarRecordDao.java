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
     * 单元登记（sha256 唯一键，裸 INSERT）：返回新记录 id；null = 唯一键冲突（单元已存在，调用方按 id 复用）。
     * 其他 SQL 错误由 JdbcHelper 上抛走硬保护。
     */
    public Long tryInsertPending(String unitType, String sha256, String sourceJar, String bootJar) {
        String sql = "INSERT INTO t_jar_record"
                + " (unit_type, sha256, source_jar, boot_jar, hostname, ip, status)"
                + " VALUES (?,?,?,?,?,?,'pending')";
        final Long[] newId = new Long[1];
        boolean inserted = db.executeInsert(sql,
                new Object[]{unitType, sha256, sourceJar, bootJar, MachineInfo.hostname(), MachineInfo.ip()},
                key -> newId[0] = key);
        return inserted ? newId[0] : null;
    }

    public JarRecord findBySha256(String sha256) {
        return db.query("SELECT * FROM t_jar_record WHERE sha256 = ?",
                new Object[]{sha256}, JarRecordDao::map).stream().findFirst().orElse(null);
    }

    /**
     * 标记解析完成并记录完成节点（多节点重复解析时以最后完成者覆盖，ip/机器名仅作观测）。
     */
    public void markCompleted(long id, int classCount, int methodCount, String hostname, String ip) {
        db.execute("UPDATE t_jar_record"
                        + " SET status='completed', class_count=?, method_count=?, parsed_at=NOW(),"
                        + " hostname=?, ip=?"
                        + " WHERE id=?",
                new Object[]{classCount, methodCount, hostname, ip, id});
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

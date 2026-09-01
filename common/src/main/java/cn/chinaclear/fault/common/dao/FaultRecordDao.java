package cn.chinaclear.fault.common.dao;

import cn.chinaclear.fault.common.JdbcHelper;
import cn.chinaclear.fault.common.model.FaultRecord;

import java.sql.Timestamp;

/** 表3 t_fault_record：故障注入记录（裸 INSERT + 唯一键冲突判集群级抢占，其他 SQL 错误走硬保护） */
public final class FaultRecordDao {

    private final JdbcHelper db;

    public FaultRecordDao(JdbcHelper db) {
        this.db = db;
    }

    /**
     * 抢占式插入：唯一索引 uk(unit_id, class_name, method_name, line_no, tag)，判重仅限轮次内。
     * true  = 插入成功，本节点赢得该行该轮的故障执行权（随后 kill）；
     * false = 唯一键冲突，该行在本轮已被集群内其他节点触发，放行继续执行；
     * 其他 SQL 错误由 JdbcHelper 上抛（KillAdviceListener C6 硬保护）。
     */
    public boolean tryInsert(FaultRecord fr) {
        String sql = "INSERT INTO t_fault_record"
                + " (unit_id, tag, hostname, ip, boot_jar, boot_jar_hash, class_name, method_name, line_no,"
                + " thread_name, fault_type, occurred_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        return db.executeInsert(sql,
                new Object[]{fr.getUnitId(), fr.getTag(), fr.getHostname(), fr.getIp(),
                        fr.getBootJar(), fr.getBootJarHash(), fr.getClassName(),
                        fr.getMethodName(), fr.getLineNo(), fr.getThreadName(),
                        fr.getFaultType(), new Timestamp(fr.getOccurredAt().getTime())},
                null);
    }

    /** 回滚误插入的记录：kill 未生效时调用，避免脏判重数据永久阻止该行本轮注入 */
    public int delete(FaultRecord fr) {
        String sql = "DELETE FROM t_fault_record"
                + " WHERE unit_id=? AND tag=? AND class_name=? AND method_name=? AND line_no=?";
        return db.execute(sql,
                new Object[]{fr.getUnitId(), fr.getTag(), fr.getClassName(),
                        fr.getMethodName(), fr.getLineNo()});
    }
}

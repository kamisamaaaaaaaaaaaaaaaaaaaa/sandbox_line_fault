package cn.chinaclear.fault.common.dao;

import cn.chinaclear.fault.common.JdbcHelper;
import cn.chinaclear.fault.common.model.FaultRecord;

import java.sql.Timestamp;

/** 表3 t_fault_record：故障注入记录（INSERT IGNORE 集群级抢占） */
public final class FaultRecordDao {

    private final JdbcHelper db;

    public FaultRecordDao(JdbcHelper db) {
        this.db = db;
    }

    /**
     * 抢占式插入：唯一索引 uk(unit_id, class_name, method_name, line_no)。
     * true  = 插入成功，本节点赢得该行故障执行权（随后 kill）；
     * false = DuplicateKey，该行已被集群内其他节点触发，放行继续执行。
     */
    public boolean tryInsert(FaultRecord fr) {
        String sql = "INSERT IGNORE INTO t_fault_record"
                + " (unit_id, hostname, ip, class_name, method_name, line_no, thread_name, fault_type, occurred_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)";
        return db.executeInsertIgnore(sql,
                new Object[]{fr.getUnitId(), fr.getHostname(), fr.getIp(), fr.getClassName(),
                        fr.getMethodName(), fr.getLineNo(), fr.getThreadName(),
                        fr.getFaultType(), new Timestamp(fr.getOccurredAt().getTime())},
                null);
    }
}

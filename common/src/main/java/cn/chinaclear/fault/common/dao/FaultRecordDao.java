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
     * 抢占式插入：唯一索引 uk(tag, boot_jar_hash, class_name, method_name, line_no, thread_name, fault_seq, stack_hash)，
     * 判重粒度为「轮次 + 应用部署路径 + 行 + 线程 + 第几次故障 + 调用栈」。
     * true  = 插入成功，本节点赢得该行该线程该调用栈本轮第 fault_seq 次的故障执行权（随后 kill）；
     * false = 唯一键冲突，该次故障已被集群内其他节点触发，放行继续执行；
     * 其他 SQL 错误由 JdbcHelper 上抛（KillAdviceListener C6 硬保护）。
     *
     * 列序与 schema.sql 的 t_fault_record 保持一致（stack_hash / stack_text 紧跟 fault_seq），
     * 换库改写 DDL 时列序同步即可，避免错位。
     */
    public boolean tryInsert(FaultRecord fr) {
        String sql = "INSERT INTO t_fault_record"
                + " (unit_id, tag, hostname, ip, boot_jar, boot_jar_hash, class_name, method_name,"
                + " method_desc, line_no, thread_name, fault_seq, stack_hash, stack_text, fault_type, occurred_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        return db.executeInsert(sql,
                new Object[]{fr.getUnitId(), fr.getTag(), fr.getHostname(), fr.getIp(),
                        fr.getBootJar(), fr.getBootJarHash(), fr.getClassName(),
                        fr.getMethodName(), fr.getMethodDesc(), fr.getLineNo(), fr.getThreadName(),
                        fr.getFaultSeq(),
                        fr.getStackHash(), fr.getStackText(),
                        fr.getFaultType(), new Timestamp(fr.getOccurredAt().getTime())},
                null);
    }

    /**
     * 回滚误插入的记录：kill 未生效时调用，避免脏判重数据永久阻止该次故障。
     * 按完整判重键精确匹配单条——同一行同一线程同一调用栈本轮会有多条（每次故障一条），
     * 条件缺 fault_seq / thread_name / stack_hash 会误删其他次故障或其他调用栈的记录。
     */
    public int delete(FaultRecord fr) {
        String sql = "DELETE FROM t_fault_record"
                + " WHERE unit_id=? AND tag=? AND boot_jar_hash=? AND class_name=? AND method_name=?"
                + " AND line_no=? AND thread_name=? AND fault_seq=? AND stack_hash=?";
        return db.execute(sql,
                new Object[]{fr.getUnitId(), fr.getTag(), fr.getBootJarHash(), fr.getClassName(),
                        fr.getMethodName(), fr.getLineNo(), fr.getThreadName(), fr.getFaultSeq(),
                        fr.getStackHash()});
    }
}

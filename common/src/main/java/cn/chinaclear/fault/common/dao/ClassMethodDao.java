package cn.chinaclear.fault.common.dao;

import cn.chinaclear.fault.common.JdbcHelper;
import cn.chinaclear.fault.common.model.ClassMethodInfo;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** 表2 t_class_method：解析结果批量幂等写入与按单元读取 */
public final class ClassMethodDao {

    private final JdbcHelper db;

    public ClassMethodDao(JdbcHelper db) {
        this.db = db;
    }

    /**
     * 批量裸 INSERT（uk(unit_id,class,method,desc_hash)）：逐行独立提交（防多节点并发解析死锁），
     * 冲突行跳过、其他错误上抛硬保护；部分写入为合法中间态（唯一索引幂等，重解析自动补齐）。
     * 失败行通过 label 带出完整方法信息（类名/方法名/描述符原文与长度），随异常进入硬保护日志与表4。
     */
    public void batchInsertSkipConflict(long unitId, List<ClassMethodInfo> methods) {
        if (methods == null || methods.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO t_class_method"
                + " (unit_id, class_name, method_name, method_desc, desc_hash) VALUES (?,?,?,?,?)";
        List<Object[]> rows = new ArrayList<>(methods.size());
        for (ClassMethodInfo m : methods) {
            rows.add(new Object[]{unitId, m.getClassName(), m.getMethodName(),
                    m.getMethodDesc(), m.getDescHash()});
        }
        db.batchInsertSkipConflict(sql, rows, row -> "class=" + row[1] + " method=" + row[2]
                + " descLen=" + (row[3] == null ? 0 : ((String) row[3]).length())
                + " descHash=" + row[4] + " desc=" + row[3]);
    }

    /** 按单元 id 列表读取全部类-方法（IN 分批） */
    public List<ClassMethodInfo> findByUnitIds(List<Long> unitIds) {
        List<ClassMethodInfo> out = new ArrayList<>();
        for (List<Long> part : partition(unitIds, 500)) {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < part.size(); i++) {
                if (i > 0) {
                    in.append(',');
                }
                in.append('?');
            }
            String sql = "SELECT unit_id, class_name, method_name, method_desc"
                    + " FROM t_class_method WHERE unit_id IN (" + in + ")"
                    + " ORDER BY class_name, method_name";
            out.addAll(db.query(sql, part.toArray(), ClassMethodDao::map));
        }
        return out;
    }

    /**
     * 游标分页读取：按 (class_name, method_name) 升序取严格大于断点的一批
     * （避免 OFFSET 漂移，也避免一次全量读入内存）。
     *
     * 断点取（类名, 方法名）而非主键的原因：sandbox 的 onBehavior(name) 只按方法名匹配，
     * 注册一次即织入同名方法的全部重载，因此表中同一 (类名, 方法名) 的多个重载行（描述符不同）
     * 对注入而言是冗余的——严格大于断点可让每个 (类名, 方法名) 只出现在第一批，
     * 后续同名重载行被自然跳过，既避免跨批重复注册，也省去读取它们。
     * （这些行仍完整保存在表中，供覆盖率统计与排查使用。）
     *
     * @param lastClass  上一批最后一条的类名；首批传空字符串
     * @param lastMethod 上一批最后一条的方法名；首批传空字符串
     * @return 该批数据（按 类名, 方法名 升序），空列表表示已读完
     */
    public List<ClassMethodInfo> findPageByUnitIds(List<Long> unitIds, String lastClass,
                                                   String lastMethod, int batchSize) {
        List<ClassMethodInfo> out = new ArrayList<>();
        if (unitIds == null || unitIds.isEmpty() || batchSize <= 0) {
            return out;
        }
        for (List<Long> part : partition(unitIds, 500)) {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < part.size(); i++) {
                if (i > 0) {
                    in.append(',');
                }
                in.append('?');
            }
            String sql = "SELECT id, unit_id, class_name, method_name, method_desc"
                    + " FROM t_class_method WHERE unit_id IN (" + in + ")"
                    + " AND (class_name, method_name) > (?, ?)"
                    + " ORDER BY class_name, method_name, id LIMIT " + batchSize;
            Object[] params = new Object[part.size() + 2];
            for (int i = 0; i < part.size(); i++) {
                params[i] = part.get(i);
            }
            params[part.size()] = lastClass;
            params[part.size() + 1] = lastMethod;
            out.addAll(db.query(sql, params, ClassMethodDao::map));
        }
        // 多段 IN 合并后重新按游标顺序排列，保证断点推进正确
        out.sort(ClassMethodDao::compareCursor);
        return out.size() > batchSize ? new ArrayList<>(out.subList(0, batchSize)) : out;
    }

    /** 游标排序：类名 → 方法名 → 主键（主键仅用于稳定排序，不参与断点） */
    private static int compareCursor(ClassMethodInfo a, ClassMethodInfo b) {
        int c = a.getClassName().compareTo(b.getClassName());
        if (c != 0) {
            return c;
        }
        c = a.getMethodName().compareTo(b.getMethodName());
        return c != 0 ? c : Long.compare(a.getId(), b.getId());
    }

    /** 模块侧读取：仅用于 watch 注册（按类名+方法名匹配），descHash 不参与故不查询、置 null */
    private static ClassMethodInfo map(ResultSet rs) throws java.sql.SQLException {
        return new ClassMethodInfo(
                rs.getLong("id"),
                rs.getLong("unit_id"),
                rs.getString("class_name"),
                rs.getString("method_name"),
                rs.getString("method_desc"),
                null);
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return parts;
    }
}

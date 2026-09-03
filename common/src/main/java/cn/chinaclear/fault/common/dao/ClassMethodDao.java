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
     * 批量裸 INSERT（uk(unit_id,class,method,desc)）：逐行独立提交（防多节点并发解析死锁），
     * 冲突行跳过、其他错误上抛硬保护；部分写入为合法中间态（唯一索引幂等，重解析自动补齐）。
     */
    public void batchInsertSkipConflict(long unitId, List<ClassMethodInfo> methods) {
        if (methods == null || methods.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO t_class_method"
                + " (unit_id, class_name, method_name, method_desc) VALUES (?,?,?,?)";
        List<Object[]> rows = new ArrayList<>(methods.size());
        for (ClassMethodInfo m : methods) {
            rows.add(new Object[]{unitId, m.getClassName(), m.getMethodName(), m.getMethodDesc()});
        }
        db.batchInsertSkipConflict(sql, rows);
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
     * 游标分页读取：按 id 升序取大于 lastId 的一批（避免 OFFSET 漂移，也避免一次全量读入内存）。
     * @return 该批数据（按 id 升序），空列表表示已读完
     */
    public List<ClassMethodInfo> findPageByUnitIds(List<Long> unitIds, long lastId, int batchSize) {
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
                    + " FROM t_class_method WHERE unit_id IN (" + in + ") AND id > ?"
                    + " ORDER BY id LIMIT " + batchSize;
            Object[] params = new Object[part.size() + 1];
            for (int i = 0; i < part.size(); i++) {
                params[i] = part.get(i);
            }
            params[part.size()] = lastId;
            out.addAll(db.query(sql, params, ClassMethodDao::map));
        }
        // 多段 IN 合并后仍按 id 升序返回，保证游标推进正确
        out.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        return out.size() > batchSize ? new ArrayList<>(out.subList(0, batchSize)) : out;
    }

    private static ClassMethodInfo map(ResultSet rs) throws java.sql.SQLException {
        return new ClassMethodInfo(
                rs.getLong("id"),
                rs.getLong("unit_id"),
                rs.getString("class_name"),
                rs.getString("method_name"),
                rs.getString("method_desc"));
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return parts;
    }
}

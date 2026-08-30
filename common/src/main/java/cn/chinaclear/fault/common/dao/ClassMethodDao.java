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

    /** 批量 INSERT IGNORE：重解析时已有行自动忽略、缺行补齐，幂等 */
    public void batchInsertIgnore(long unitId, List<ClassMethodInfo> methods) {
        if (methods == null || methods.isEmpty()) {
            return;
        }
        String sql = "INSERT IGNORE INTO t_class_method"
                + " (unit_id, class_name, method_name, method_desc) VALUES (?,?,?,?)";
        List<Object[]> rows = new ArrayList<>(methods.size());
        for (ClassMethodInfo m : methods) {
            rows.add(new Object[]{unitId, m.getClassName(), m.getMethodName(), m.getMethodDesc()});
        }
        db.batchInsertIgnore(sql, rows);
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

    private static ClassMethodInfo map(ResultSet rs) throws java.sql.SQLException {
        return new ClassMethodInfo(
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

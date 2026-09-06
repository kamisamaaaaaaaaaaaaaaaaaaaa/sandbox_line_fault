package cn.chinaclear.fault.common;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 正则统一入口：编译与匹配。
 *
 * 编译失败一律抛出、不做「跳过该条继续跑」的降级：正则写错会让名单部分失效，
 * exclude 正则写错更会造成超范围注入——静默降级的代价是覆盖不完整而不自知。
 * 抛出的异常由调用方上层的硬保护收口（见各调用处说明）。
 */
public final class RegexPatterns {

    private RegexPatterns() {
    }

    /**
     * 编译一组正则，任一条非法即抛。
     *
     * @param regexes 正则原文列表（已 trim）
     * @param label   配置来源标识，用于指出是哪一类、第几个块的配置出问题
     */
    public static Pattern[] compile(List<String> regexes, String label) {
        if (regexes == null || regexes.isEmpty()) {
            return new Pattern[0];
        }
        Pattern[] out = new Pattern[regexes.size()];
        for (int i = 0; i < regexes.size(); i++) {
            String regex = regexes.get(i);
            try {
                out[i] = Pattern.compile(regex);
            } catch (Exception e) {
                throw new IllegalStateException("invalid " + label + " regex（第 " + i + " 条）\""
                        + regex + "\": " + e.getMessage(), e);
            }
        }
        return out;
    }

    /** 全串匹配：任一条命中即为 true；空数组恒为 false。 */
    public static boolean matchesAny(Pattern[] patterns, String value) {
        for (Pattern p : patterns) {
            if (p.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }
}

package com.flowdesk.shared.utils;


import java.util.*;
import java.util.stream.Collectors;

/**
 * 字符串工具类（空安全、简洁、常用功能全覆盖）
 * <p>
 * 功能分类：
 * <ul>
 *   <li>判空：isEmpty, isNotEmpty, isBlank, isNotBlank, hasText</li>
 *   <li>去空格：trim, trimToNull, trimToEmpty, strip</li>
 *   <li>截取：substring, left, right, mid</li>
 *   <li>判等：equals, equalsIgnoreCase, compare</li>
 *   <li>包含：contains, containsIgnoreCase, containsAny, containsNone</li>
 *   <li>前缀后缀：startsWith, endsWith, 忽略大小写版本</li>
 *   <li>拼接：join (数组/集合/可变参数)</li>
 *   <li>替换：replace, replaceAll, replaceOnce</li>
 *   <li>分割：split, splitToList, splitByWholeSeparator</li>
 *   <li>大小写转换：toCamelCase, toSnakeCase, capitalize, uncapitalize</li>
 *   <li>默认值：defaultIfNull, defaultIfEmpty, defaultIfBlank</li>
 *   <li>其他：repeat, reverse, remove, removeEnd, abbreviate, padLeft, padRight</li>
 * </ul>
 *
 * @author Crazy-HF
 * @since 1.0
 */
public final class StringUtils {

    private StringUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== 判空 ====================

    /**
     * 判断字符串是否为 null 或长度为 0
     *
     * @param cs 待检查字符串
     * @return true 表示 null 或空字符串
     */
    public static boolean isEmpty(final CharSequence cs) {
        return cs == null || cs.length() == 0;
    }

    /**
     * 判断字符串是否不为 null 且长度大于 0
     */
    public static boolean isNotEmpty(final CharSequence cs) {
        return !isEmpty(cs);
    }

    /**
     * 判断字符串是否为 null、空字符串或仅包含空白字符（空格、制表符、换行等）
     *
     * @param cs 待检查字符串
     * @return true 表示 null 或空白
     */
    public static boolean isBlank(final CharSequence cs) {
        if (cs == null) return true;
        int len = cs.length();
        for (int i = 0; i < len; i++) {
            if (!Character.isWhitespace(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断字符串是否不为 null、非空且至少包含一个非空白字符
     */
    public static boolean isNotBlank(final CharSequence cs) {
        return !isBlank(cs);
    }

    /**
     * 同 isNotBlank，更语义化的命名（Spring 风格）
     */
    public static boolean hasText(final CharSequence cs) {
        return isNotBlank(cs);
    }

    // ==================== 去空格 ====================

    /**
     * 去除字符串首尾空白字符，若为 null 则返回 null
     */
    public static String trim(final String str) {
        return str == null ? null : str.trim();
    }

    /**
     * 去除首尾空白，若为 null 或全空白则返回 null
     */
    public static String trimToNull(final String str) {
        final String trimmed = trim(str);
        return isEmpty(trimmed) ? null : trimmed;
    }

    /**
     * 去除首尾空白，若为 null 或全空白则返回空字符串 ""
     */
    public static String trimToEmpty(final String str) {
        return str == null ? "" : str.trim();
    }

    /**
     * 去除字符串首尾所有空白（包括全角空格等）—— 使用 Java 的 trim 只能去除小于等于空格的字符。
     * 此方法利用 Character.isWhitespace 判断，并返回去掉首尾空白后的新串。
     */
    public static String strip(final String str) {
        if (str == null) return null;
        int start = 0;
        int end = str.length() - 1;
        while (start <= end && Character.isWhitespace(str.charAt(start))) {
            start++;
        }
        while (end >= start && Character.isWhitespace(str.charAt(end))) {
            end--;
        }
        return str.substring(start, end + 1);
    }

    // ==================== 截取 ====================

    /**
     * 安全截取子串，若入参 null 则返回 null，start/end 超出边界自动调整
     *
     * @param str   原字符串
     * @param start 起始索引（包含），负值表示从末尾计数（-1 为最后一个字符）
     * @param end   结束索引（不包含），负值表示从末尾计数
     * @return 截取后的字符串，可能为 null
     */
    public static String substring(final String str, int start, int end) {
        if (str == null) return null;
        if (start < 0) start = str.length() + start;
        if (end < 0) end = str.length() + end;
        if (start < 0) start = 0;
        if (end > str.length()) end = str.length();
        if (start > end) return "";
        return str.substring(start, end);
    }

    /**
     * 从左侧截取指定长度字符
     */
    public static String left(final String str, int len) {
        if (str == null) return null;
        if (len < 0) return "";
        if (len >= str.length()) return str;
        return str.substring(0, len);
    }

    /**
     * 从右侧截取指定长度字符
     */
    public static String right(final String str, int len) {
        if (str == null) return null;
        if (len < 0) return "";
        if (len >= str.length()) return str;
        return str.substring(str.length() - len);
    }

    /**
     * 从指定位置截取到末尾
     */
    public static String mid(final String str, int pos, int len) {
        if (str == null) return null;
        if (len < 0 || pos > str.length()) return "";
        if (pos < 0) pos = 0;
        if (pos + len > str.length()) len = str.length() - pos;
        return str.substring(pos, pos + len);
    }

    // ==================== 判等 ====================

    /**
     * 比较两个字符串是否相等（空安全）
     */
    public static boolean equals(final CharSequence cs1, final CharSequence cs2) {
        if (cs1 == cs2) return true;
        if (cs1 == null || cs2 == null) return false;
        if (cs1.length() != cs2.length()) return false;
        return cs1.equals(cs2);
    }

    /**
     * 比较两个字符串是否相等（忽略大小写）
     */
    public static boolean equalsIgnoreCase(final CharSequence cs1, final CharSequence cs2) {
        if (cs1 == cs2) return true;
        if (cs1 == null || cs2 == null) return false;
        if (cs1.length() != cs2.length()) return false;
        return cs1.toString().equalsIgnoreCase(cs2.toString());
    }

    /**
     * 比较两个字符串大小，null 视为小于非 null（空安全）
     */
    public static int compare(final String str1, final String str2) {
        if (str1 == str2) return 0;
        if (str1 == null) return -1;
        if (str2 == null) return 1;
        return str1.compareTo(str2);
    }

    // ==================== 包含 ====================

    /**
     * 检查是否包含子串（空安全）
     */
    public static boolean contains(final CharSequence seq, final CharSequence searchSeq) {
        if (seq == null || searchSeq == null) return false;
        return seq.toString().contains(searchSeq);
    }

    /**
     * 忽略大小写的包含检查
     */
    public static boolean containsIgnoreCase(final CharSequence seq, final CharSequence searchSeq) {
        if (seq == null || searchSeq == null) return false;
        return seq.toString().toLowerCase(Locale.ROOT)
                .contains(searchSeq.toString().toLowerCase(Locale.ROOT));
    }

    /**
     * 字符串中是否包含任意一个搜索字符
     */
    public static boolean containsAny(final CharSequence seq, final CharSequence... searchSeqs) {
        if (seq == null || searchSeqs == null || searchSeqs.length == 0) return false;
        for (CharSequence s : searchSeqs) {
            if (contains(seq, s)) return true;
        }
        return false;
    }

    /**
     * 字符串中是否不包含任意一个搜索字符
     */
    public static boolean containsNone(final CharSequence seq, final CharSequence... searchSeqs) {
        return !containsAny(seq, searchSeqs);
    }

    // ==================== 前缀/后缀 ====================

    public static boolean startsWith(final CharSequence seq, final CharSequence prefix) {
        if (seq == null || prefix == null) return false;
        return seq.toString().startsWith(prefix.toString());
    }

    public static boolean startsWithIgnoreCase(final CharSequence seq, final CharSequence prefix) {
        if (seq == null || prefix == null) return false;
        return seq.toString().toLowerCase(Locale.ROOT)
                .startsWith(prefix.toString().toLowerCase(Locale.ROOT));
    }

    public static boolean endsWith(final CharSequence seq, final CharSequence suffix) {
        if (seq == null || suffix == null) return false;
        return seq.toString().endsWith(suffix.toString());
    }

    public static boolean endsWithIgnoreCase(final CharSequence seq, final CharSequence suffix) {
        if (seq == null || suffix == null) return false;
        return seq.toString().toLowerCase(Locale.ROOT)
                .endsWith(suffix.toString().toLowerCase(Locale.ROOT));
    }

    // ==================== 拼接 ====================

    /**
     * 将数组元素用分隔符拼接
     */
    public static String join(final Object[] array, final String separator) {
        if (array == null) return null;
        return join(Arrays.asList(array), separator);
    }

    /**
     * 将集合元素用分隔符拼接
     */
    public static String join(final Collection<?> collection, final String separator) {
        if (collection == null) return null;
        return collection.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(separator == null ? "" : separator));
    }

    /**
     * 可变参数拼接（重载）
     */
    public static String join(final String separator, final Object... values) {
        if (values == null) return null;
        return join(Arrays.asList(values), separator);
    }

    // ==================== 替换 ====================

    /**
     * 替换所有匹配的子串（空安全）
     */
    public static String replace(final String text, final String searchString, final String replacement) {
        if (text == null || searchString == null || replacement == null || searchString.isEmpty()) {
            return text;
        }
        return text.replace(searchString, replacement);
    }

    /**
     * 替换第一次出现的子串
     */
    public static String replaceOnce(final String text, final String searchString, final String replacement) {
        if (text == null || searchString == null || replacement == null || searchString.isEmpty()) {
            return text;
        }
        int idx = text.indexOf(searchString);
        if (idx == -1) return text;
        return text.substring(0, idx) + replacement + text.substring(idx + searchString.length());
    }

    /**
     * 使用正则替换全部
     */
    public static String replaceAll(final String text, final String regex, final String replacement) {
        if (text == null || regex == null || replacement == null) return text;
        return text.replaceAll(regex, replacement);
    }

    // ==================== 分割 ====================

    /**
     * 按分隔符分割为字符串数组（空安全）
     */
    public static String[] split(final String str, final String separator) {
        if (str == null) return null;
        if (separator == null || separator.isEmpty()) return new String[]{str};
        return str.split(separator, -1);
    }

    /**
     * 按分隔符分割为 List（自动过滤空白？默认不过滤）
     */
    public static List<String> splitToList(final String str, final String separator) {
        if (str == null) return null;
        if (separator == null || separator.isEmpty()) return Collections.singletonList(str);
        return Arrays.asList(str.split(separator, -1));
    }

    /**
     * 按完整字符串分隔（不同于正则，使用 indexOf 循环）
     */
    public static String[] splitByWholeSeparator(final String str, final String separator) {
        if (str == null) return null;
        if (separator == null || separator.isEmpty()) return new String[]{str};
        List<String> result = new ArrayList<>();
        int start = 0;
        int end;
        while ((end = str.indexOf(separator, start)) != -1) {
            result.add(str.substring(start, end));
            start = end + separator.length();
        }
        result.add(str.substring(start));
        return result.toArray(new String[0]);
    }

    // ==================== 大小写转换 ====================

    /**
     * 将下划线命名转为驼峰命名（例如：user_name -> userName）
     * 默认保留大写，可配置是否首字母大写
     */
    public static String toCamelCase(final String str) {
        return toCamelCase(str, false);
    }

    /**
     * 转换为驼峰，并指定首字母是否大写（如 UserName）
     */
    public static String toCamelCase(final String str, final boolean capitalizeFirst) {
        if (isBlank(str)) return str;
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '_' || c == '-') {
                nextUpper = true;
            } else {
                if (nextUpper) {
                    sb.append(Character.toUpperCase(c));
                    nextUpper = false;
                } else {
                    sb.append(Character.toLowerCase(c));
                }
            }
        }
        String result = sb.toString();
        if (capitalizeFirst && result.length() > 0) {
            return Character.toUpperCase(result.charAt(0)) + result.substring(1);
        }
        if (!capitalizeFirst && result.length() > 0) {
            return Character.toLowerCase(result.charAt(0)) + result.substring(1);
        }
        return result;
    }

    /**
     * 将驼峰命名转为下划线命名（例如：userName -> user_name）
     */
    public static String toSnakeCase(final String str) {
        if (isBlank(str)) return str;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 首字母大写
     */
    public static String capitalize(final String str) {
        if (isEmpty(str)) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * 首字母小写
     */
    public static String uncapitalize(final String str) {
        if (isEmpty(str)) return str;
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }

    // ==================== 默认值 ====================

    /**
     * 如果字符串为 null 则返回默认值，否则返回自身
     */
    public static String defaultIfNull(final String str, final String defaultStr) {
        return str == null ? defaultStr : str;
    }

    /**
     * 如果字符串为 null 或空则返回默认值，否则返回自身
     */
    public static String defaultIfEmpty(final String str, final String defaultStr) {
        return isEmpty(str) ? defaultStr : str;
    }

    /**
     * 如果字符串为 null 或空白则返回默认值，否则返回自身
     */
    public static String defaultIfBlank(final String str, final String defaultStr) {
        return isBlank(str) ? defaultStr : str;
    }

    // ==================== 其他实用功能 ====================

    /**
     * 重复字符串 n 次
     */
    public static String repeat(final String str, final int repeat) {
        if (str == null) return null;
        if (repeat <= 0) return "";
        return String.join("", Collections.nCopies(repeat, str));
    }

    /**
     * 反转字符串
     */
    public static String reverse(final String str) {
        if (str == null) return null;
        return new StringBuilder(str).reverse().toString();
    }

    /**
     * 删除所有出现的子串
     */
    public static String remove(final String text, final String remove) {
        if (text == null || remove == null) return text;
        return text.replace(remove, "");
    }

    /**
     * 删除结尾的指定字符串（若存在）
     */
    public static String removeEnd(final String str, final String remove) {
        if (str == null || remove == null) return str;
        if (str.endsWith(remove)) {
            return str.substring(0, str.length() - remove.length());
        }
        return str;
    }

    /**
     * 删除开头的指定字符串（若存在）
     */
    public static String removeStart(final String str, final String remove) {
        if (str == null || remove == null) return str;
        if (str.startsWith(remove)) {
            return str.substring(remove.length());
        }
        return str;
    }

    /**
     * 缩写字符串，超过最大长度用省略号替换
     */
    public static String abbreviate(final String str, final int maxWidth) {
        if (str == null) return null;
        if (maxWidth < 4) throw new IllegalArgumentException("maxWidth must be at least 4");
        if (str.length() <= maxWidth) return str;
        return str.substring(0, maxWidth - 3) + "...";
    }

    /**
     * 左补齐到指定长度
     */
    public static String padLeft(final String str, final int size, final char padChar) {
        if (str == null) return null;
        int pads = size - str.length();
        if (pads <= 0) return str;
        return repeat(String.valueOf(padChar), pads) + str;
    }

    /**
     * 右补齐到指定长度
     */
    public static String padRight(final String str, final int size, final char padChar) {
        if (str == null) return null;
        int pads = size - str.length();
        if (pads <= 0) return str;
        return str + repeat(String.valueOf(padChar), pads);
    }

    /**
     * 将字符串转为整型，失败返回默认值
     */
    public static int toInt(final String str, final int defaultValue) {
        if (str == null) return defaultValue;
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 将字符串转为 Long，失败返回默认值
     */
    public static long toLong(final String str, final long defaultValue) {
        if (str == null) return defaultValue;
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 将字符串转为 Boolean，忽略大小写，"true"、"yes"、"1" 视为 true，否则 false
     */
    public static boolean toBoolean(final String str) {
        if (str == null) return false;
        String s = str.trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("yes") || s.equals("1") || s.equals("on");
    }
}
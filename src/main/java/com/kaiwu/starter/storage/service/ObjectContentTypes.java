package com.kaiwu.starter.storage.service;

import com.kaiwu.starter.storage.exception.ObjectStorageException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 对象 MIME 类型的规范化与白名单匹配，所有存储实现共用同一套规则。 */
public final class ObjectContentTypes {

    /** 未声明类型时保存的默认值。 */
    public static final String DEFAULT = "application/octet-stream";

    private static final Pattern CONTENT_TYPE =
            Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,63}/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,63}");

    /** 工具类不实例化。 */
    private ObjectContentTypes() {}

    /**
     * 规范化 MIME 类型，拒绝含参数、换行或其他可能注入响应头的值。
     *
     * @param contentType 调用方传入的 MIME 类型，可为空
     * @return 小写的 MIME 类型，为空时返回 {@link #DEFAULT}
     * @throws ObjectStorageException 格式不合法时抛出
     */
    public static String normalize(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return DEFAULT;
        }
        String trimmed = contentType.trim();
        if (!CONTENT_TYPE.matcher(trimmed).matches()) {
            throw new ObjectStorageException("不支持的 Content-Type");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * 校验类型在平台配置的白名单内；白名单支持 {@code image/*} 这类主类型通配。
     *
     * @param contentType 已规范化的 MIME 类型
     * @param allowed 白名单，空表示不限制
     * @throws ObjectStorageException 不在白名单内时抛出
     */
    public static void requireAllowed(String contentType, List<String> allowed) {
        if (allowed.isEmpty()) {
            return;
        }
        for (String candidate : allowed) {
            if (candidate.equals(contentType)
                    || (candidate.endsWith("/*")
                            && contentType.startsWith(candidate.substring(0, candidate.length() - 1)))) {
                return;
            }
        }
        throw new ObjectStorageException("文件类型不在允许范围内：" + contentType);
    }
}

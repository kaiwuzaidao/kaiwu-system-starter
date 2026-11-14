package com.kaiwu.starter.storage.service;

import com.kaiwu.starter.storage.exception.ObjectStorageException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 生成并校验本项目的 object key（ADR 0039）。
 *
 * <p>key 形如 {@code {projectCode}/{业务类型}/{yyyy/MM}/{uuid}.{扩展名}}。用户上传的原始文件名
 * 不进入 key，只取经过白名单过滤的扩展名，避免路径穿越、重名覆盖和不可见字符。
 * 所有存储实现写入前都用 {@link #requireOwned(String)} 复核，保证任何 key 都落在本项目前缀之下。
 */
public final class ObjectKeyPolicy {

    private static final int MAX_KEY_LENGTH = 512;
    private static final Pattern PROJECT_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Pattern BIZ_TYPE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,10}");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final DateTimeFormatter MONTH_PATH = DateTimeFormatter.ofPattern("yyyy/MM", Locale.ROOT);

    private final String projectCode;
    private final Clock clock;

    /**
     * 创建绑定到单个项目的 key 策略。
     *
     * @param projectCode 当前项目编码，即 {@code kaiwu.starter.project-code}
     * @param clock 生成年月目录所用的时钟
     * @throws IllegalStateException 项目编码缺失或含有不能作为路径段的字符时抛出
     */
    public ObjectKeyPolicy(String projectCode, Clock clock) {
        if (projectCode == null || !PROJECT_CODE.matcher(projectCode).matches()) {
            throw new IllegalStateException("对象存储需要合法的 kaiwu.starter.project-code");
        }
        this.projectCode = projectCode;
        this.clock = clock;
    }

    /**
     * 为一次新上传生成唯一 key。
     *
     * @param bizType 业务类型，小写字母、数字、下划线或连字符，最长 32 位，例如 {@code invoice}
     * @param originalFilename 用户上传的原始文件名，可为空；只用于提取扩展名
     * @return 本项目前缀下的新 key
     * @throws ObjectStorageException 业务类型不合法时抛出
     */
    public String generate(String bizType, String originalFilename) {
        if (bizType == null || !BIZ_TYPE.matcher(bizType).matches()) {
            throw new ObjectStorageException("业务类型不合法：只允许小写字母、数字、下划线和连字符，最长 32 位");
        }
        return generateUnder(projectCode + "/" + bizType + "/", originalFilename);
    }

    /** 为可信证据生成位于存储项目前缀下的命名空间目录，供预留和重试校验共用。 */
    String evidencePrefix(String namespace) {
        if (namespace == null || !PROJECT_CODE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("invalid evidence namespace");
        }
        return projectCode + "/evidence/" + namespace + "/";
    }

    /** 核对命名空间和键的层级，避免将年份恰好等于命名空间的历史键误认为新键。 */
    boolean isEvidenceUnder(String prefix, String key) {
        return key.startsWith(prefix) && key.substring(prefix.length()).split("/", -1).length == 3;
    }

    /** 在已校验的证据目录下生成对象键，保持统一年月和随机文件名布局。 */
    String generateEvidence(String evidencePrefix) {
        return generateUnder(evidencePrefix, "evidence.bin");
    }

    /** 使用统一的年月目录、随机名和受控扩展名生成新对象键。 */
    private String generateUnder(String prefix, String originalFilename) {
        String month = LocalDate.now(clock).format(MONTH_PATH);
        return prefix + month + "/" + UUID.randomUUID() + extensionOf(originalFilename);
    }

    /**
     * 校验 key 属于本项目且每一段都是安全的路径段。
     *
     * @param objectKey 待写入或读取的 key
     * @return 原样返回通过校验的 key
     * @throws ObjectStorageException key 为空、过长、越出本项目前缀或含有非法路径段时抛出
     */
    public String requireOwned(String objectKey) {
        if (objectKey == null || objectKey.isEmpty() || objectKey.length() > MAX_KEY_LENGTH) {
            throw new ObjectStorageException("对象 key 为空或超过 " + MAX_KEY_LENGTH + " 个字符");
        }
        String[] segments = objectKey.split("/", -1);
        if (segments.length < 2 || !segments[0].equals(projectCode)) {
            throw new ObjectStorageException("对象 key 不在本项目前缀下：" + objectKey);
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
                throw new ObjectStorageException("对象 key 含有非法路径段：" + objectKey);
            }
        }
        return objectKey;
    }

    /**
     * 从原始文件名提取白名单内的扩展名；不符合白名单时宁可不带扩展名。
     *
     * @param originalFilename 原始文件名，可为空
     * @return 以点开头的小写扩展名，或空字符串
     */
    private static String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        String extension = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXTENSION.matcher(extension).matches() ? "." + extension : "";
    }
}

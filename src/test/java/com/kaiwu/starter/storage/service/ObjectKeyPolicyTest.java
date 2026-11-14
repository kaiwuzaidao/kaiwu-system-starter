package com.kaiwu.starter.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.storage.exception.ObjectStorageException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 验证 object key 只落在本项目前缀下，且不受用户文件名影响。 */
class ObjectKeyPolicyTest {

    private final ObjectKeyPolicy policy =
            new ObjectKeyPolicy("demo", Clock.fixed(Instant.parse("2026-10-29T08:00:00Z"), ZoneOffset.UTC));

    /** 生成的 key 包含项目、业务类型、年月和随机名，只保留白名单内的小写扩展名。 */
    @Test
    void generatesProjectScopedKeyWithSanitizedExtension() {
        String key = policy.generate("invoice", "发票 2026.PDF");

        assertThat(key).matches("demo/invoice/2026/10/[0-9a-f-]{36}\\.pdf");
        assertThat(policy.requireOwned(key)).isEqualTo(key);
    }

    /** 扩展名含路径或异常字符时宁可丢弃，原始文件名永远不进入 key。 */
    @Test
    void dropsUnsafeOrMissingExtensions() {
        assertThat(policy.generate("invoice", "../../etc/passwd")).matches("demo/invoice/2026/10/[0-9a-f-]{36}");
        assertThat(policy.generate("invoice", "a.p/df")).matches("demo/invoice/2026/10/[0-9a-f-]{36}");
        assertThat(policy.generate("invoice", null)).matches("demo/invoice/2026/10/[0-9a-f-]{36}");
        assertThat(policy.generate("invoice", "trailing.")).matches("demo/invoice/2026/10/[0-9a-f-]{36}");
    }

    /** 业务类型参与路径，只允许小写安全字符。 */
    @Test
    void rejectsInvalidBizType() {
        assertThatThrownBy(() -> policy.generate("../x", "a.pdf")).isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> policy.generate("Invoice", "a.pdf")).isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> policy.generate(null, "a.pdf")).isInstanceOf(ObjectStorageException.class);
    }

    /** 越出项目前缀、路径穿越、空段和绝对路径都必须拒绝。 */
    @Test
    void rejectsKeysOutsideTheProjectOrWithUnsafeSegments() {
        for (String key : new String[] {
            "other/invoice/a.pdf",
            "demo",
            "demo/../other/a.pdf",
            "demo/./a.pdf",
            "demo//a.pdf",
            "/demo/a.pdf",
            "demo/a\\b.pdf",
            "demo/a b.pdf",
            "",
            "demo/" + "a".repeat(600)
        }) {
            assertThatThrownBy(() -> policy.requireOwned(key)).as(key).isInstanceOf(ObjectStorageException.class);
        }
        assertThatThrownBy(() -> policy.requireOwned(null)).isInstanceOf(ObjectStorageException.class);
    }

    /** 项目编码缺失时无法确定前缀，必须在启动阶段失败。 */
    @Test
    void requiresUsableProjectCode() {
        assertThatThrownBy(() -> new ObjectKeyPolicy(null, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ObjectKeyPolicy("a/b", Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}

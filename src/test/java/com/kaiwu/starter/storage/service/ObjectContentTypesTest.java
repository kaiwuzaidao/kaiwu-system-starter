package com.kaiwu.starter.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.storage.exception.ObjectStorageException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证 MIME 类型规范化与平台白名单匹配。 */
class ObjectContentTypesTest {

    /** 空值取默认类型，大小写统一为小写，带参数或换行的值被拒绝。 */
    @Test
    void normalizesAndRejectsUnsafeValues() {
        assertThat(ObjectContentTypes.normalize(null)).isEqualTo(ObjectContentTypes.DEFAULT);
        assertThat(ObjectContentTypes.normalize(" Image/PNG ")).isEqualTo("image/png");
        assertThatThrownBy(() -> ObjectContentTypes.normalize("text/html; charset=utf-8"))
                .isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> ObjectContentTypes.normalize("text/plain\r\nX: 1"))
                .isInstanceOf(ObjectStorageException.class);
    }

    /** 空白名单不限制；精确匹配与主类型通配都生效。 */
    @Test
    void matchesWhitelistWithWildcards() {
        ObjectContentTypes.requireAllowed("image/png", List.of());
        ObjectContentTypes.requireAllowed("image/png", List.of("image/*"));
        ObjectContentTypes.requireAllowed("application/pdf", List.of("image/*", "application/pdf"));

        assertThatThrownBy(() -> ObjectContentTypes.requireAllowed("text/html", List.of("image/*")))
                .isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> ObjectContentTypes.requireAllowed("imagex/png", List.of("image/*")))
                .isInstanceOf(ObjectStorageException.class);
    }
}

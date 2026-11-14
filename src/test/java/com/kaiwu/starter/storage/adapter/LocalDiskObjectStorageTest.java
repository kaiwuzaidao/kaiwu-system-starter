package com.kaiwu.starter.storage.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaiwu.starter.storage.exception.ObjectNotFoundException;
import com.kaiwu.starter.storage.exception.ObjectStorageException;
import com.kaiwu.starter.storage.model.StoredObject;
import com.kaiwu.starter.storage.service.ObjectKeyPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证本地磁盘存储的读写语义、大小上限、失败清理与前缀隔离。 */
class LocalDiskObjectStorageTest {

    private static final long MAX_BYTES = 16;

    @TempDir
    Path root;

    /**
     * 创建上限为 16 字节、绑定 demo 项目的存储。
     *
     * @return 待测存储
     */
    private LocalDiskObjectStorage storage() {
        return new LocalDiskObjectStorage(root, new ObjectKeyPolicy("demo", Clock.systemUTC()), MAX_BYTES);
    }

    /**
     * 构造 UTF-8 内容流。
     *
     * @param text 文本内容
     * @return 内容流
     */
    private static InputStream text(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 读取对象内容为 UTF-8 文本。
     *
     * @param storage 存储
     * @param key 对象 key
     * @return 对象文本
     * @throws IOException 读取失败时抛出
     */
    private static String read(LocalDiskObjectStorage storage, String key) throws IOException {
        try (InputStream in = storage.get(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 统计根目录下遗留的临时文件。
     *
     * @return 临时文件数量
     * @throws IOException 遍历失败时抛出
     */
    private long leftoverTempFiles() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .count();
        }
    }

    /**
     * 写入、读取、元数据与覆盖的完整往返。
     *
     * @throws IOException 读取失败时抛出
     */
    @Test
    void writesReadsStatsAndOverwrites() throws IOException {
        LocalDiskObjectStorage storage = storage();

        StoredObject stored = storage.put("demo/invoice/a.txt", text("hello"), 5, "Text/Plain");

        assertThat(stored).isEqualTo(new StoredObject("local", "demo/invoice/a.txt", 5, "text/plain"));
        assertThat(read(storage, "demo/invoice/a.txt")).isEqualTo("hello");
        assertThat(storage.stat("demo/invoice/a.txt")).contains(stored);

        storage.put("demo/invoice/a.txt", text("bye"), -1, null);

        assertThat(read(storage, "demo/invoice/a.txt")).isEqualTo("bye");
        assertThat(storage.stat("demo/invoice/a.txt"))
                .contains(new StoredObject("local", "demo/invoice/a.txt", 3, "application/octet-stream"));
    }

    /** 删除是幂等的，删除后读取报不存在、元数据为空。 */
    @Test
    void deleteIsIdempotentAndMissingObjectsAreReported() {
        LocalDiskObjectStorage storage = storage();
        storage.put("demo/invoice/a.txt", text("hello"), 5, "text/plain");

        storage.delete("demo/invoice/a.txt");
        storage.delete("demo/invoice/a.txt");

        assertThat(storage.stat("demo/invoice/a.txt")).isEmpty();
        assertThatThrownBy(() -> storage.get("demo/invoice/a.txt")).isInstanceOf(ObjectNotFoundException.class);
    }

    /**
     * 声明长度超限时不读取内容直接拒绝；未声明长度时在复制中途拒绝，并且都不留下文件。
     *
     * @throws IOException 遍历失败时抛出
     */
    @Test
    void enforcesSizeLimitWithAndWithoutDeclaredLength() throws IOException {
        LocalDiskObjectStorage storage = storage();
        String tooLarge = "x".repeat((int) MAX_BYTES + 1);

        assertThatThrownBy(() -> storage.put("demo/invoice/big.txt", text(tooLarge), MAX_BYTES + 1, null))
                .isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> storage.put("demo/invoice/big.txt", text(tooLarge), -1, null))
                .isInstanceOf(ObjectStorageException.class);

        assertThat(storage.stat("demo/invoice/big.txt")).isEmpty();
        assertThat(leftoverTempFiles()).isZero();
    }

    /**
     * 实际长度与声明不符说明内容被截断，覆盖写入失败时旧内容必须保持完整。
     *
     * @throws IOException 读取失败时抛出
     */
    @Test
    void rejectsTruncatedContentAndKeepsPreviousVersion() throws IOException {
        LocalDiskObjectStorage storage = storage();
        storage.put("demo/invoice/a.txt", text("old"), 3, null);

        assertThatThrownBy(() -> storage.put("demo/invoice/a.txt", text("new"), 10, null))
                .isInstanceOf(ObjectStorageException.class);

        assertThat(read(storage, "demo/invoice/a.txt")).isEqualTo("old");
        assertThat(leftoverTempFiles()).isZero();
    }

    /** 越出项目前缀或含穿越段的 key 在任何操作上都被拒绝，且不会在根目录外留下文件。 */
    @Test
    void rejectsKeysOutsideTheProject() {
        LocalDiskObjectStorage storage = storage();

        assertThatThrownBy(() -> storage.put("other/a.txt", text("x"), 1, null))
                .isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> storage.put("demo/../../escape.txt", text("x"), 1, null))
                .isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> storage.get("demo/../other/a.txt")).isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> storage.delete("other/a.txt")).isInstanceOf(ObjectStorageException.class);

        assertThat(Files.exists(root.getParent().resolve("escape.txt"))).isFalse();
    }

    /** 带参数或换行的 Content-Type 可能被回写到响应头，必须拒绝。 */
    @Test
    void rejectsUnsafeContentType() {
        LocalDiskObjectStorage storage = storage();

        assertThatThrownBy(() -> storage.put("demo/invoice/a.txt", text("x"), 1, "text/plain\r\nX-Evil: 1"))
                .isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> storage.put("demo/invoice/a.txt", text("x"), 1, "text/html; charset=utf-8"))
                .isInstanceOf(ObjectStorageException.class);
    }

    /** 本地存储明确声明不支持预签名，业务据此回退为后端中转。 */
    @Test
    void presignedUrlsAreUnsupported() {
        LocalDiskObjectStorage storage = storage();

        assertThat(storage.supportsPresignedUrls()).isFalse();
        assertThatThrownBy(() -> storage.presignGet("demo/a.txt", Duration.ofMinutes(5)))
                .isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> storage.presignPut("demo/a.txt", "text/plain", Duration.ofMinutes(5)))
                .isInstanceOf(ObjectStorageException.class);
    }

    /** 非正数上限属于配置错误，构造时失败。 */
    @Test
    void rejectsNonPositiveLimit() {
        ObjectKeyPolicy policy = new ObjectKeyPolicy("demo", Clock.systemUTC());

        assertThatThrownBy(() -> new LocalDiskObjectStorage(root, policy, 0)).isInstanceOf(IllegalStateException.class);
    }
}

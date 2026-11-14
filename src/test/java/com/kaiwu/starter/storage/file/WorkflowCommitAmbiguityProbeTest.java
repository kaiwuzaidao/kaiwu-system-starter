package com.kaiwu.starter.storage.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.kaiwu.starter.storage.model.StoredFile;
import com.kaiwu.starter.storage.repository.StoredFileRepository;
import com.kaiwu.starter.storage.service.ObjectKeyPolicy;
import com.kaiwu.starter.storage.service.StoredFileService;
import com.kaiwu.starter.storage.support.PresigningObjectStorage;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 登记回执不确定时必须保留可能被引用的对象。 */
class WorkflowCommitAmbiguityProbeTest {
    /** 模拟持久化完成后丢失回执，原异常不能授权删除对象。 */
    @Test
    void committedRegistrationMustNotLoseItsObjectWhenReceiptIsLost() {
        var reference = new AtomicReference<StoredFile>();
        var repository = mock(StoredFileRepository.class);
        doAnswer(call -> {
                    reference.set(call.getArgument(0));
                    throw new IllegalStateException("test-only lost receipt");
                })
                .when(repository)
                .insert(any());
        var storage = new PresigningObjectStorage(false);
        var clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);
        var service = new StoredFileService(
                repository,
                storage,
                ref -> storage,
                new ObjectKeyPolicy("sample", clock),
                clock,
                1024,
                Duration.ofMinutes(5));
        assertThatThrownBy(() -> service.upload(
                        "workflow",
                        "approval.pdf",
                        "application/pdf",
                        new ByteArrayInputStream(new byte[] {1}),
                        1,
                        "workflow-worker"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reference.get()).isNotNull();
        assertThat(storage.contains(reference.get().objectKey()))
                .as("committed registry reference must retain object after lost receipt")
                .isTrue();
    }
}

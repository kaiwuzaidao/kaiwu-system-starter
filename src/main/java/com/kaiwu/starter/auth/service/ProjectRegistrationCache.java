package com.kaiwu.starter.auth.service;

import com.kaiwu.starter.ContextVerificationException;
import com.kaiwu.starter.auth.service.TrustedProjectRegistry.Registration;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** 部署内有界缓存，按单调时钟过期，失效与结果采用在同一把锁内协调。 */
public final class ProjectRegistrationCache implements AutoCloseable {

    private static final int MAX_READS = 32;
    private static final long TTL = Duration.ofSeconds(30).toNanos();
    private final TrustedProjectRegistry registry;
    private final long timeout;
    private final LongSupplier nanoTime;
    private final int capacity;
    private final ThreadPoolExecutor readers = new ThreadPoolExecutor(
            0,
            MAX_READS,
            30,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            Thread.ofVirtual().name("kaiwu-registry-", 0).factory());
    private final Set<Read> pending = new HashSet<>();
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    /** 默认容纳 1024 个项目，正负登记共享容量。 */
    public ProjectRegistrationCache(TrustedProjectRegistry registry, Duration timeout) {
        this(registry, timeout, System::nanoTime, 1024);
    }

    /** 注入单调时钟和容量以验证过期与淘汰。 */
    ProjectRegistrationCache(TrustedProjectRegistry registry, Duration timeout, LongSupplier nanoTime, int capacity) {
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException("Starter Registry 查询时限必须大于零且不超过 30 秒");
        }
        this.registry = registry;
        this.timeout = timeout.toNanos();
        this.nanoTime = nanoTime;
        this.capacity = capacity;
    }

    /** 查询登记，超时、过期、失效或读取故障时关闭准入。 */
    public Optional<Registration> find(String projectCode) {
        Read read;
        FutureTask<Optional<Registration>> task;
        synchronized (this) {
            long now = nanoTime.getAsLong();
            Entry cached = entries.get(projectCode);
            if (cached != null && now - cached.startedAt() < TTL) {
                return cached.result();
            }
            entries.remove(projectCode);
            if (pending.size() >= MAX_READS) {
                throw denied();
            }
            read = new Read(projectCode, now);
            task = new FutureTask<>(() -> registry.findByProjectCode(projectCode));
            try {
                readers.execute(task);
            } catch (RejectedExecutionException rejected) {
                throw denied();
            }
            pending.add(read);
        }

        try {
            long remaining = timeout - (nanoTime.getAsLong() - read.startedAt);
            if (remaining <= 0) {
                throw denied();
            }
            Optional<Registration> result = task.get(remaining, TimeUnit.NANOSECONDS);
            synchronized (this) {
                if (read.invalidated || result == null || nanoTime.getAsLong() - read.startedAt >= timeout) {
                    throw denied();
                }
                Entry current = entries.get(projectCode);
                if (current == null || read.startedAt - current.startedAt() >= 0) {
                    entries.put(projectCode, new Entry(read.startedAt, result));
                    if (entries.size() > capacity) {
                        entries.remove(entries.keySet().iterator().next());
                    }
                }
                return result;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw denied();
        } catch (ExecutionException | TimeoutException failure) {
            throw denied();
        } finally {
            task.cancel(true);
            synchronized (this) {
                pending.remove(read);
            }
        }
    }

    /** 登记提交后失效本项目，标记仍在查询的旧代数。 */
    public synchronized void invalidate(String projectCode) {
        entries.remove(projectCode);
        pending.stream().filter(read -> read.projectCode.equals(projectCode)).forEach(read -> read.invalidated = true);
    }

    /** 部署吊销时清除所有登记，并禁止采用正在读取的结果。 */
    public synchronized void invalidateAll() {
        entries.clear();
        pending.forEach(read -> read.invalidated = true);
    }

    /** 应用关闭时取消读取并丢弃缓存，线程池不等待失联的查询。 */
    @Override
    public synchronized void close() {
        readers.shutdownNow();
        invalidateAll();
    }

    /** 登记详情和驱动异常不得出现在鉴权异常里。 */
    private static ContextVerificationException denied() {
        return new ContextVerificationException("Starter Registry 无法确认项目准入");
    }

    /** 读取起始时刻用于计算固定有效期。 */
    private record Entry(long startedAt, Optional<Registration> result) {}

    /** 在途读取捕获的代数标记，失效后该读取不能授权或回填。 */
    private static final class Read {
        private final String projectCode;
        private final long startedAt;
        private boolean invalidated;

        /** 绑定读取开始时的项目及单调时间。 */
        private Read(String projectCode, long startedAt) {
            this.projectCode = projectCode;
            this.startedAt = startedAt;
        }
    }
}

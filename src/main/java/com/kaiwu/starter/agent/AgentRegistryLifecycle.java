package com.kaiwu.starter.agent;

import com.kaiwu.starter.agent.protocol.CapabilityManifest;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.json.JsonMapper;

/** 仅发送显式 manifest Bean；每能力至多一个任务，无重叠心跳。重启后重新注册，停机不撤销共享能力。 */
public final class AgentRegistryLifecycle implements SmartLifecycle, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AgentRegistryLifecycle.class);

    public enum Phase {
        REGISTERING,
        REGISTERED,
        RETRYING,
        REJECTED,
        STOPPED
    }

    private final AgentRegistryTransport transport;
    private final int heartbeatSeconds;
    private final List<Entry> entries;
    private ScheduledThreadPoolExecutor scheduler;
    private boolean running;
    private long generation;
    private boolean credentialRejected;

    private static final class Entry {
        final String code;
        final String version;
        final byte[] body;
        AgentRegistryTransport.Revision revision;
        Phase phase = Phase.STOPPED;
        int failures;

        Entry(CapabilityManifest manifest, byte[] body) {
            code = manifest.code();
            version = manifest.version();
            this.body = body;
        }
    }

    public AgentRegistryLifecycle(
            AgentRegistryTransport transport,
            List<CapabilityManifest> manifests,
            String projectCode,
            int heartbeatSeconds) {
        this.transport = transport;
        if (heartbeatSeconds < 10 || heartbeatSeconds > 60)
            throw new IllegalArgumentException("Agent 心跳间隔必须为 10..60 秒");
        this.heartbeatSeconds = heartbeatSeconds;
        if (projectCode == null
                || !projectCode.matches("[a-z][a-z0-9-]{1,63}")
                || projectCode.equals("system")
                || manifests.isEmpty()
                || manifests.size() > 100) throw new IllegalArgumentException("Agent 需要业务项目与 1..100 个显式能力");
        Set<String> codes = new HashSet<>();
        var json = JsonMapper.builder().build();
        List<Entry> prepared = new ArrayList<>();
        for (CapabilityManifest manifest : manifests) {
            if (!projectCode.equals(manifest.projectCode())
                    || manifest.code() == null
                    || !manifest.code().startsWith(projectCode + ".")
                    || manifest.code().length() > 160
                    || !manifest.code().matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+")
                    || manifest.version() == null
                    || manifest.version().length() > 16
                    || !manifest.version().matches("[0-9]+(?:\\.[0-9]+){0,2}")
                    || !codes.add(manifest.code())) throw new IllegalArgumentException("Agent 能力项目、编码、版本或重复声明非法");
            byte[] body = json.writeValueAsBytes(manifest);
            if (body.length > HttpAgentRegistryTransport.MAX_BYTES)
                throw new IllegalArgumentException("Agent manifest 超过 64 KiB");
            prepared.add(new Entry(manifest, body));
        }
        entries = List.copyOf(prepared);
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        credentialRejected = false;
        long run = ++generation;
        scheduler = new ScheduledThreadPoolExecutor(
                2,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("kaiwu-agent-register-", 0)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        for (Entry entry : entries) {
            entry.revision = null;
            entry.failures = 0;
            entry.phase = Phase.REGISTERING;
            schedule(entry, run, ThreadLocalRandom.current().nextLong(1000));
        }
    }

    private synchronized void schedule(Entry entry, long run, long delayMillis) {
        if (!running || generation != run || credentialRejected || entry.phase == Phase.REJECTED) return;
        var unused = scheduler.schedule(() -> synchronize(entry, run), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void synchronize(Entry entry, long run) {
        AgentRegistryTransport.Revision previous;
        synchronized (this) {
            if (!running || generation != run || credentialRejected) return;
            previous = entry.revision;
        }
        try {
            AgentRegistryTransport.Revision registered = previous;
            if (previous == null) registered = transport.register(entry.code, entry.version, entry.body);
            else transport.heartbeat(previous);
            synchronized (this) {
                if (!running || generation != run || credentialRejected) return;
                entry.revision = registered;
                entry.failures = 0;
                entry.phase = Phase.REGISTERED;
                schedule(
                        entry,
                        run,
                        heartbeatSeconds * 1000L + ThreadLocalRandom.current().nextLong(5001));
            }
        } catch (RuntimeException failure) {
            int status = failure instanceof AgentRegistryException registry ? registry.status() : 0;
            synchronized (this) {
                if (!running || generation != run || credentialRejected) return;
                if (status == 401 || status == 403) {
                    credentialRejected = true;
                    entries.forEach(item -> item.phase = Phase.REJECTED);
                    scheduler.getQueue().clear();
                } else if (status > 0 && status < 500 && status != 429) entry.phase = Phase.REJECTED;
                else {
                    entry.phase = Phase.RETRYING;
                    entry.failures = Math.min(6, entry.failures + 1);
                    schedule(entry, run, retryDelayMillis(entry.failures));
                }
                // Never log remote response bodies or exception messages, which may contain credentials.
                LOG.warn("Agent registration state={} status={}", entry.phase, status);
            }
        }
    }

    static long retryDelayMillis(int failures) {
        long base = Math.min(60000, 1000L << Math.min(6, Math.max(0, failures)));
        return Math.min(60000, base + ThreadLocalRandom.current().nextLong(1001));
    }
    /** 仅为本地运维状态，REGISTERED 不代表 System 已审批或允许执行。 */
    public synchronized Map<String, Phase> states() {
        Map<String, Phase> result = new LinkedHashMap<>();
        entries.forEach(entry -> result.put(entry.code, entry.phase));
        return Map.copyOf(result);
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void stop() {
        running = false;
        generation++;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        entries.forEach(entry -> entry.phase = Phase.STOPPED);
    }

    @Override
    public void close() {
        stop();
        transport.close();
    }
}

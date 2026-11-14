package com.kaiwu.datascope;

import com.kaiwu.starter.KaiwuContext;
import com.kaiwu.starter.StarterContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据范围模块唯一的公开 API：读取当前范围、显式旁路、显式归属转移。
 *
 * <p>不提供注解形式的旁路：注解在 Spring AOP 自调用时会失效，也容易被随手加到类上；
 * Lambda 的作用范围清晰，可被静态扫描统计。{@code reason} 必填，并写入 INFO 日志供审计。</p>
 */
public final class DataScopes {

    private static final Logger log = LoggerFactory.getLogger(DataScopes.class);

    /** 语句的执行模式。 */
    enum Mode {
        UNRESTRICTED,
        REASSIGN
    }

    /**
     * 新的归属：人与部门。
     *
     * @param userId 新归属人，必须是项目有效成员
     * @param deptId 新归属部门，必须是该成员所属部门之一
     */
    public record Owner(long userId, long deptId) {}

    /** 线程上的一层旁路作用域。 */
    record Frame(Mode mode, String reason, Owner owner) {}

    private static final ThreadLocal<Deque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    private static volatile Supplier<KaiwuContext> contextSupplier = StarterContext::current;
    private static volatile MemberDirectory memberDirectory;

    private DataScopes() {}

    /**
     * 装配运行时依赖；由自动配置调用，测试可直接调用。
     *
     * @param supplier  取当前请求 Context 的方式
     * @param directory 成员查询，可为空（此时 {@code reassign} 一律失败）
     */
    public static void configure(Supplier<KaiwuContext> supplier, MemberDirectory directory) {
        contextSupplier = supplier;
        memberDirectory = directory;
    }

    /**
     * 读当前请求的数据范围。
     *
     * @return 范围视图
     * @throws DataScopeViolationException 当前线程没有 Context
     */
    public static DataScope current() {
        return new DataScope(requireContext().dataScope());
    }

    /**
     * 显式旁路：定时任务、对账、平台回调等确实需要全量的场景。作用域内受控表不追加谓词，
     * 也允许 upsert 类语句；仍禁止修改归属列。
     *
     * @param reason 必填，写入审计日志
     * @param body   要执行的逻辑
     * @return body 的返回值
     */
    public static <T> T unrestricted(String reason, Supplier<T> body) {
        return run(new Frame(Mode.UNRESTRICTED, requireReason(reason), null), body);
    }

    /** 同 {@link #unrestricted(String, Supplier)}，无返回值。 */
    public static void unrestricted(String reason, Runnable body) {
        unrestricted(reason, () -> {
            body.run();
            return null;
        });
    }

    /**
     * 显式归属转移：唯一允许改写归属列的入口。源行仍受当前用户范围约束；新归属人必须是项目有效成员，
     * 新归属部门必须是该成员的所属部门之一，两者都不能为 0。
     *
     * @param reason   必填，写入审计日志
     * @param newOwner 新归属
     * @param body     要执行的逻辑
     * @return body 的返回值
     * @throws DataScopeReassignRejectedException 新归属不合法（可翻译成 4xx）
     * @throws DataScopeViolationException 没有 Context、没有配置 {@link MemberDirectory} 或成员查询故障（保持 5xx）
     */
    public static <T> T reassign(String reason, Owner newOwner, Supplier<T> body) {
        requireReason(reason);
        KaiwuContext context = requireContext();
        if (newOwner == null || newOwner.userId() <= 0 || newOwner.deptId() <= 0) {
            throw new DataScopeReassignRejectedException("新归属人和新归属部门都不能为空或 0");
        }
        MemberDirectory directory = memberDirectory;
        if (directory == null) {
            throw new DataScopeViolationException("未配置 MemberDirectory，无法校验新归属");
        }
        String newUser = Long.toString(newOwner.userId());
        MemberDirectory.Placement placement;
        try {
            placement = directory.placement(newUser);
        } catch (RuntimeException failure) {
            throw new DataScopeViolationException("无法确认新归属：成员查询失败", failure);
        }
        if (!placement.active()) {
            throw new DataScopeReassignRejectedException("新归属人不是本项目的有效成员");
        }
        if (!placement.departmentIds().contains(newOwner.deptId())) {
            throw new DataScopeReassignRejectedException("新归属部门不是新归属人的所属部门");
        }
        log.info("归属转移：operator={}, reason={}, newOwner={}", context.userId(), reason, newUser);
        return run(new Frame(Mode.REASSIGN, reason, newOwner), body);
    }

    /** 同 {@link #reassign(String, Owner, Supplier)}，无返回值。 */
    public static void reassign(String reason, Owner newOwner, Runnable body) {
        reassign(reason, newOwner, () -> {
            body.run();
            return null;
        });
    }

    /**
     * 启动自检用：在一个假定的归属下运行 body，不需要 Context，也不做成员校验。
     *
     * @param body 例如让 MetaObjectHandler 对探测实体执行一次插入填充
     */
    static void probe(Runnable body) {
        run(new Frame(Mode.REASSIGN, "startup-probe", new Owner(1L, 1L)), () -> {
            body.run();
            return null;
        });
    }

    /** @return 当前线程最内层的旁路作用域；没有则为 {@code null} */
    static Frame frame() {
        return FRAMES.get().peek();
    }

    /** @return 当前请求 Context；没有则为 {@code null} */
    static KaiwuContext context() {
        return contextSupplier.get();
    }

    /** 取当前请求 Context，没有则失败关闭。 */
    static KaiwuContext requireContext() {
        KaiwuContext context = context();
        if (context == null) {
            throw new DataScopeViolationException("当前线程没有 Kaiwu Context");
        }
        return context;
    }

    private static <T> T run(Frame frame, Supplier<T> body) {
        Deque<Frame> frames = FRAMES.get();
        frames.push(frame);
        if (frame.mode() == Mode.UNRESTRICTED) {
            KaiwuContext context = context();
            log.info(
                    "数据范围旁路：operator={}, reason={}, caller={}",
                    context == null ? "-" : context.userId(),
                    frame.reason(),
                    caller());
        }
        try {
            return body.get();
        } finally {
            frames.pop();
            if (frames.isEmpty()) {
                FRAMES.remove();
            }
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DataScopeViolationException("reason 必填且不能为空白");
        }
        return reason;
    }

    private static String caller() {
        return StackWalker.getInstance()
                .walk(frames -> frames.filter(f -> !f.getClassName().startsWith("com.kaiwu.datascope."))
                        .findFirst()
                        .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
                        .orElse("-"));
    }
}

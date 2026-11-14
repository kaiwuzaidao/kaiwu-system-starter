package com.kaiwu.starter.agent;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 与业务写入共用事务的持久化结果账本。实现必须通过数据库唯一键跨实例去重。
 *
 * <p>{@code result} 仅供核实已经提交的结果；空结果不证明旧请求未执行，调用方不能据此自动重发。
 */
public interface AgentWriteLedger {
    String executeOnce(AgentWriteAction action, Supplier<String> domainMutation);

    Optional<String> result(AgentWriteAction action);
}

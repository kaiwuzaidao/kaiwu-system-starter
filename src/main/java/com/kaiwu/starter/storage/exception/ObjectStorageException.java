package com.kaiwu.starter.storage.exception;

/**
 * 对象存储操作失败。消息只包含 object key 与原因，不包含凭证、签名或存储地址。
 */
public class ObjectStorageException extends RuntimeException {

    /**
     * 创建不带底层原因的异常。
     *
     * @param message 面向开发者的失败原因
     */
    public ObjectStorageException(String message) {
        super(message);
    }

    /**
     * 创建包装底层 I/O 或 SDK 异常的异常。
     *
     * @param message 面向开发者的失败原因
     * @param cause 底层异常
     */
    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

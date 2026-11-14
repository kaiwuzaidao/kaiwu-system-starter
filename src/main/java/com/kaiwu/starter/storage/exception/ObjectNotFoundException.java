package com.kaiwu.starter.storage.exception;

/**
 * 读取的对象不存在。业务通常应转换为 404，而不是 500。
 */
public class ObjectNotFoundException extends ObjectStorageException {

    /**
     * 创建对象不存在异常。
     *
     * @param objectKey 未找到的对象 key
     */
    public ObjectNotFoundException(String objectKey) {
        super("对象不存在：" + objectKey);
    }
}

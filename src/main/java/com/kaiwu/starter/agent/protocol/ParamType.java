package com.kaiwu.starter.agent.protocol;

/** 能力参数的类型，校验器按此判定模型给出的取值是否合法。 */
public enum ParamType {
    STRING,
    INT,
    BOOL,
    DATETIME,
    ENUM,
    /** Bounded JSON array of distinct nonblank strings; min/max count and maxLength per item are required. */
    STRING_LIST
}

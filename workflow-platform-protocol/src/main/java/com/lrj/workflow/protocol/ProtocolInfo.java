package com.lrj.workflow.protocol;

/**
 * 契约版本标识。Phase 1 会在此包下补齐事件 records(EventEnvelopeV1 等)与 REST API records。
 * 当前仅作占位,确保 protocol 模块可独立编译。
 */
public final class ProtocolInfo {

    /** Published Language 契约主版本。 */
    public static final int CONTRACT_VERSION = 1;

    private ProtocolInfo() {
    }
}

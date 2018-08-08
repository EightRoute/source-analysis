package org.apache.commons.pool2;

/**
 * PooledObject的状态
 */
public enum PooledObjectState {
    /**
     * 在队列中，未使用。
     */
    IDLE,

    /**
     * 正在使用。
     */
    ALLOCATED,

    /**
     *在队列中，目前正在验证
     */
    EVICTION,

    /**
     * 不在队列中，目前正在验证
     * 一旦验证通过，它应该返回到队列的头部。
     */
    EVICTION_RETURN_TO_HEAD,

    /**
     * 在队列中，当前正在验证。
     */
    VALIDATION,

    /**
     * 不在队列中，目前正在验证. 一旦验证完成，就应该分配它。
     */
    VALIDATION_PREALLOCATED,

    /**
     * 不在队列中，目前正在验证. 验证通过应将其返回到队列的头部
     */
    VALIDATION_RETURN_TO_HEAD,

    /**
     * 无效的
     */
    INVALID,

    /**
     * 失效
     */
    ABANDONED,

    /**
     * 返回
     */
    RETURNING
}

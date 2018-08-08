package org.apache.commons.pool2;

/**
 * 一个接口，用于定义由{@link ObjectPool}提供服务的实例的生命周期方法。
 */
public interface PooledObjectFactory<T> {
    /**
     * 创建一个可由池提供的实例，并将其包装在由池管理的{@link PooledObject}中。
     */
    PooledObject<T> makeObject() throws Exception;

    /**
     * 销毁池中不再需要的实例。
     */
    void destroyObject(PooledObject<T> p) throws Exception;

    /**
     * 确保池可以安全地返回实例。
     */
    boolean validateObject(PooledObject<T> p);

    /**
     * 重新初始化池返回的实例.
     */
    void activateObject(PooledObject<T> p) throws Exception;

    /**
     * 取消初始化要返回空闲对象池的实例
     */
    void passivateObject(PooledObject<T> p) throws Exception;
}


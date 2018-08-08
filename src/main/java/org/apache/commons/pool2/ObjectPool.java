package org.apache.commons.pool2;

import java.util.NoSuchElementException;


public interface ObjectPool<T> {
    /**
     *  从此池中获取实例。
     */
    T borrowObject() throws Exception, NoSuchElementException,
            IllegalStateException;

    /**
     * 将实例返回池中
     */
    void returnObject(T obj) throws Exception;

    /**
     * 使池中的对象无效。
     */
    void invalidateObject(T obj) throws Exception;

    /**
     * 使用{@link PooledObjectFactory factory}或其实现依赖机制创建一个对象，
     * 然后放入空闲对象池中。
     */
    void addObject() throws Exception, IllegalStateException,
            UnsupportedOperationException;

    /**
     * @return 此池中当前空闲的实例数
     */
    int getNumIdle();

    /**.
     * @return 返回当前从此池中获取的实例数
     */
    int getNumActive();

    /**
     * 清除在池中空闲的任何对象
     */
    void clear() throws Exception, UnsupportedOperationException;

    /**
     * 关闭此池，并释放与其关联的所有资源.
     */
    void close();
}

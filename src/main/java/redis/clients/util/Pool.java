package redis.clients.util;


import java.io.Closeable;
import java.util.NoSuchElementException;

import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;


public abstract class Pool<T> implements Closeable {
    protected GenericObjectPool<T> internalPool;

    /**
     * 使用此构造函数意味着您必须自己设置和初始化internalPool。
     */
    public Pool() {
    }

    public Pool(final GenericObjectPoolConfig poolConfig, PooledObjectFactory<T> factory) {
        initPool(poolConfig, factory);
    }

    /**
     * 关闭池
     */
    @Override
    public void close() {
        destroy();
    }

    /**
     * @return 是否关闭
     */
    public boolean isClosed() {
        return this.internalPool.isClosed();
    }


    /**
     * 初始化
     * @param poolConfig 配置
     * @param factory 创建线程池的工厂
     */
    public void initPool(final GenericObjectPoolConfig poolConfig, PooledObjectFactory<T> factory) {

        if (this.internalPool != null) {
            try {
                closeInternalPool();
            } catch (Exception e) {
            }
        }

        this.internalPool = new GenericObjectPool<T>(factory, poolConfig);
    }

    /**
     * @return 从池中获取一个实例
     */
    public T getResource() {
        try {
            return internalPool.borrowObject();
        } catch (NoSuchElementException nse) {
            throw new JedisException("Could not get a resource from the pool", nse);
        } catch (Exception e) {
            throw new JedisConnectionException("Could not get a resource from the pool", e);
        }
    }

    /**
     * 已废弃
     */
    @Deprecated
    public void returnResourceObject(final T resource) {
        if (resource == null) {
            return;
        }
        try {
            internalPool.returnObject(resource);
        } catch (Exception e) {
            throw new JedisException("Could not return the resource to the pool", e);
        }
    }

    /**
     * 已废弃
     */
    @Deprecated
    public void returnBrokenResource(final T resource) {
        if (resource != null) {
            returnBrokenResourceObject(resource);
        }
    }

    /**
     * 已废弃
     */
    @Deprecated
    public void returnResource(final T resource) {
        if (resource != null) {
            returnResourceObject(resource);
        }
    }

    /**
     * 关闭池
     */
    public void destroy() {
        closeInternalPool();
    }

    protected void returnBrokenResourceObject(final T resource) {
        try {
            internalPool.invalidateObject(resource);
        } catch (Exception e) {
            throw new JedisException("Could not return the resource to the pool", e);
        }
    }

    protected void closeInternalPool() {
        try {
            internalPool.close();
        } catch (Exception e) {
            throw new JedisException("Could not destroy the pool", e);
        }
    }

    /**
     * @return 活跃数量
     */
    public int getNumActive() {
        if (poolInactive()) {
            return -1;
        }

        return this.internalPool.getNumActive();
    }

    /**
     * @return 空闲数量
     */
    public int getNumIdle() {
        if (poolInactive()) {
            return -1;
        }

        return this.internalPool.getNumIdle();
    }


    /**
     * @return 当前阻塞的线程数的估计值，等待来自池中的对象。这仅用于监视，而不用于同步控制。
     */
    public int getNumWaiters() {
        if (poolInactive()) {
            return -1;
        }

        return this.internalPool.getNumWaiters();
    }


    /**
     * @return 线程从池中取对象的平均等待时间
     */
    public long getMeanBorrowWaitTimeMillis() {
        if (poolInactive()) {
            return -1;
        }

        return this.internalPool.getMeanBorrowWaitTimeMillis();
    }

    /**
     * @return 线程曾经从池中取对象的最长等待时间
     */
    public long getMaxBorrowWaitTimeMillis() {
        if (poolInactive()) {
            return -1;
        }

        return this.internalPool.getMaxBorrowWaitTimeMillis();
    }

    /**
     * @return 池是否为null或者是关闭的
     */
    private boolean poolInactive() {
        return this.internalPool == null || this.internalPool.isClosed();
    }

    /**
     * 添加对象到池中
     * @param count 添加的数量
     */
    public void addObjects(int count) {
        try {
            for (int i = 0; i < count; i++) {
                this.internalPool.addObject();
            }
        } catch (Exception e) {
            throw new JedisException("Error trying to add idle objects", e);
        }
    }
}
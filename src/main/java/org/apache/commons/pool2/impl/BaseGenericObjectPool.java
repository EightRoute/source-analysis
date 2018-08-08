package org.apache.commons.pool2.impl;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.util.Deque;
import java.util.Iterator;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.SwallowedExceptionListener;


public abstract class BaseGenericObjectPool<T> {

    // Constants
    public static final int MEAN_TIMING_STATS_CACHE_SIZE = 100;

    // Configuration attributes
    private volatile int maxTotal =
            GenericKeyedObjectPoolConfig.DEFAULT_MAX_TOTAL;
    private volatile boolean blockWhenExhausted =
            BaseObjectPoolConfig.DEFAULT_BLOCK_WHEN_EXHAUSTED;
    private volatile long maxWaitMillis =
            BaseObjectPoolConfig.DEFAULT_MAX_WAIT_MILLIS;
    private volatile boolean lifo = BaseObjectPoolConfig.DEFAULT_LIFO;
    private final boolean fairness;
    private volatile boolean testOnCreate =
            BaseObjectPoolConfig.DEFAULT_TEST_ON_CREATE;
    private volatile boolean testOnBorrow =
            BaseObjectPoolConfig.DEFAULT_TEST_ON_BORROW;
    private volatile boolean testOnReturn =
            BaseObjectPoolConfig.DEFAULT_TEST_ON_RETURN;
    private volatile boolean testWhileIdle =
            BaseObjectPoolConfig.DEFAULT_TEST_WHILE_IDLE;
    private volatile long timeBetweenEvictionRunsMillis =
            BaseObjectPoolConfig.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS;
    private volatile int numTestsPerEvictionRun =
            BaseObjectPoolConfig.DEFAULT_NUM_TESTS_PER_EVICTION_RUN;
    private volatile long minEvictableIdleTimeMillis =
            BaseObjectPoolConfig.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS;
    private volatile long softMinEvictableIdleTimeMillis =
            BaseObjectPoolConfig.DEFAULT_SOFT_MIN_EVICTABLE_IDLE_TIME_MILLIS;
    private volatile EvictionPolicy<T> evictionPolicy;


    // Internal (primarily state) attributes
    final Object closeLock = new Object();
    volatile boolean closed = false;
    final Object evictionLock = new Object();
    private Evictor evictor = null; // @GuardedBy("evictionLock")
    EvictionIterator evictionIterator = null; // @GuardedBy("evictionLock")
    private final WeakReference<ClassLoader> factoryClassLoader;


    // 监控属性(主要为JMX)
    private final ObjectName oname;
    private final String creationStackTrace;
    private final AtomicLong borrowedCount = new AtomicLong(0);
    private final AtomicLong returnedCount = new AtomicLong(0);
    final AtomicLong createdCount = new AtomicLong(0);
    final AtomicLong destroyedCount = new AtomicLong(0);
    final AtomicLong destroyedByEvictorCount = new AtomicLong(0);
    final AtomicLong destroyedByBorrowValidationCount = new AtomicLong(0);
    private final StatsStore activeTimes = new StatsStore(MEAN_TIMING_STATS_CACHE_SIZE);
    private final StatsStore idleTimes = new StatsStore(MEAN_TIMING_STATS_CACHE_SIZE);
    private final StatsStore waitTimes = new StatsStore(MEAN_TIMING_STATS_CACHE_SIZE);
    private final AtomicLong maxBorrowWaitTimeMillis = new AtomicLong(0L);
    private volatile SwallowedExceptionListener swallowedExceptionListener = null;


    /**
     * 处理JMX注册（如果需要）和监视所需的初始化。
     *
     * @param config        Pool配置
     * @param jmxNameBase   除非由配置覆盖，否则新池的默认基本JMX名称
     * @param jmxNamePrefix 用于新池的JMX名称的前缀
     */
    public BaseGenericObjectPool(BaseObjectPoolConfig config,
                                 String jmxNameBase, String jmxNamePrefix) {
        if (config.getJmxEnabled()) {
            //如果开启JMX
            this.oname = jmxRegister(config, jmxNameBase, jmxNamePrefix);
        } else {
            this.oname = null;
        }

        // 填充创建堆栈跟踪
        this.creationStackTrace = getStackTrace(new Exception());

        // 保存当前TCCL（如果有的话）以便稍后由逐出器程序线程使用
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            factoryClassLoader = null;
        } else {
            factoryClassLoader = new WeakReference<ClassLoader>(cl);
        }

        fairness = config.getFairness();
    }


    /**
     * 返回池可以分配的最大对象数
     * 当为负数时，则对象数没有限制。
     *
     * @return 池管理的对象实例的最大数量
     */
    public final int getMaxTotal() {
        return maxTotal;
    }

    /**
     * 设置池可以分配的最大对象数，为负数则没有上限
     */
    public final void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
    }

    /**
     * 池中没有对象时是否阻塞
     */
    public final boolean getBlockWhenExhausted() {
        return blockWhenExhausted;
    }

    /**
     * 设置池中没有对象时是否阻塞
     */
    public final void setBlockWhenExhausted(boolean blockWhenExhausted) {
        this.blockWhenExhausted = blockWhenExhausted;
    }

    /**
     * 返回borrowObject方法在池耗尽时阻塞的最长时间（以毫秒为单位）。
     * 当小于0时，borrowObject方法可能无限期地阻塞。
     * getBlockWhenExhausted()应为true
     *
     * @return 最长的阻塞时间（以毫秒为单位）
     */
    public final long getMaxWaitMillis() {
        return maxWaitMillis;
    }

    /**
     * 设置最长的阻塞时间
     */
    public final void setMaxWaitMillis(long maxWaitMillis) {
        this.maxWaitMillis = maxWaitMillis;
    }

    /**
     * @return 为true则为LIFO，为false为FIFO
     */
    public final boolean getLifo() {
        return lifo;
    }

    /**
     * True表示等待线程的服务就像在FIFO队列中等待一样。
     *
     * @return 是否公平
     */
    public final boolean getFairness() {
        return fairness;
    }

    /**
     * 设置是否为LIFO
     */
    public final void setLifo(boolean lifo) {
        this.lifo = lifo;
    }

    /**
     * 在new之后和调用borrowObject方法之前是否需要验证.
     *
     * @return 在调用borrowObject方法之前是否需要验证.
     */
    public final boolean getTestOnCreate() {
        return testOnCreate;
    }

    /**
     * 设置在new之后和调用borrowObject方法之前是否需要验证.
     */
    public final void setTestOnCreate(boolean testOnCreate) {
        this.testOnCreate = testOnCreate;
    }

    /**
     * 调用borrowObject方法之前是否需要验证.
     */
    public final boolean getTestOnBorrow() {
        return testOnBorrow;
    }

    /**
     * 设置调用borrowObject方法之前是否需要验证.
     */
    public final void setTestOnBorrow(boolean testOnBorrow) {
        this.testOnBorrow = testOnBorrow;
    }

    /**
     * @return 调用returnObject方法时是否需要验证.
     */
    public final boolean getTestOnReturn() {
        return testOnReturn;
    }

    /**
     * 设置调用returnObject方法时是否需要验证.
     */
    public final void setTestOnReturn(boolean testOnReturn) {
        this.testOnReturn = testOnReturn;
    }

    /**
     * 空闲时是否验证
     */
    public final boolean getTestWhileIdle() {
        return testWhileIdle;
    }

    /**
     * 设置空闲时是否验证
     */
    public final void setTestWhileIdle(boolean testWhileIdle) {
        this.testWhileIdle = testWhileIdle;
    }

    /**
     * 驱逐器在池start之后多少毫秒开始run
     */
    public final long getTimeBetweenEvictionRunsMillis() {
        return timeBetweenEvictionRunsMillis;
    }

    /**
     * 设置驱逐器在池start之后多少毫秒开始run
     */
    public final void setTimeBetweenEvictionRunsMillis(
            long timeBetweenEvictionRunsMillis) {
        this.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
        startEvictor(timeBetweenEvictionRunsMillis);
    }

    /**
     * 空闲对象逐出器线程的每次运行期间要检查的最大对象数
     */
    public final int getNumTestsPerEvictionRun() {
        return numTestsPerEvictionRun;
    }

    /**
     * 设置空闲对象逐出器线程的每次运行期间要检查的最大对象数
     */
    public final void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
        this.numTestsPerEvictionRun = numTestsPerEvictionRun;
    }

    public final long getMinEvictableIdleTimeMillis() {
        return minEvictableIdleTimeMillis;
    }


    public final void setMinEvictableIdleTimeMillis(
            long minEvictableIdleTimeMillis) {
        this.minEvictableIdleTimeMillis = minEvictableIdleTimeMillis;
    }

    /**
     * 返回softMinEvictableIdleTimeMillis
     */
    public final long getSoftMinEvictableIdleTimeMillis() {
        return softMinEvictableIdleTimeMillis;
    }

    /**
     * 设置softMinEvictableIdleTimeMillis
     */
    public final void setSoftMinEvictableIdleTimeMillis(
            long softMinEvictableIdleTimeMillis) {
        this.softMinEvictableIdleTimeMillis = softMinEvictableIdleTimeMillis;
    }


    public final String getEvictionPolicyClassName() {
        return evictionPolicy.getClass().getName();
    }


    public final void setEvictionPolicyClassName(
            String evictionPolicyClassName) {
        try {
            Class<?> clazz;
            try {
                clazz = Class.forName(evictionPolicyClassName, true,
                        Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                clazz = Class.forName(evictionPolicyClassName);
            }
            Object policy = clazz.newInstance();
            if (policy instanceof EvictionPolicy<?>) {
                @SuppressWarnings("unchecked") // safe, because we just checked the class
                        EvictionPolicy<T> evicPolicy = (EvictionPolicy<T>) policy;
                this.evictionPolicy = evicPolicy;
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Unable to create EvictionPolicy instance of type " +
                            evictionPolicyClassName, e);
        } catch (InstantiationException e) {
            throw new IllegalArgumentException(
                    "Unable to create EvictionPolicy instance of type " +
                            evictionPolicyClassName, e);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Unable to create EvictionPolicy instance of type " +
                            evictionPolicyClassName, e);
        }
    }


    /**
     * 关闭池，销毁剩余的空闲对象，
     * 如果在JMX中注册，则取消注册。
     */
    public abstract void close();

    /**
     * @return 是否关闭
     */
    public final boolean isClosed() {
        return closed;
    }


    public abstract void evict() throws Exception;

    /**
     * @return 删除策略
     */
    protected EvictionPolicy<T> getEvictionPolicy() {
        return evictionPolicy;
    }

    /**
     * 验证池是否已打开。
     * @throws IllegalStateException 如果池是关闭的
     */
    final void assertOpen() throws IllegalStateException {
        if (isClosed()) {
            throw new IllegalStateException("Pool not open");
        }
    }

    /**
     * 以给定的延迟启动逐出器。
     *
     * 如果在调用此方法时有一个evictor 运行，
     * 它将被停止并替换为具有指定延迟的new evictor
     *
     * @param delay start之后多少毫秒开始run
     */
    final void startEvictor(long delay) {
        synchronized (evictionLock) {
            if (null != evictor) {
                //停止老的
                EvictionTimer.cancel(evictor);
                evictor = null;
                evictionIterator = null;
            }
            if (delay > 0) {
                evictor = new Evictor();
                EvictionTimer.schedule(evictor, delay, delay);
            }
        }
    }


    abstract void ensureMinIdle() throws Exception;


    // Monitoring (primarily JMX) related methods

    /**
     * @return JMX的name
     */
    public final ObjectName getJmxName() {
        return oname;
    }


    public final String getCreationStackTrace() {
        return creationStackTrace;
    }


    public final long getBorrowedCount() {
        return borrowedCount.get();
    }


    public final long getReturnedCount() {
        return returnedCount.get();
    }

    /**
     * @return 创建的对象的数量
     */
    public final long getCreatedCount() {
        return createdCount.get();
    }

    /**
     * @return 销毁对象的数量
     */
    public final long getDestroyedCount() {
        return destroyedCount.get();
    }

    /**
     * @return 被删除策略删除的对象数量
     */
    public final long getDestroyedByEvictorCount() {
        return destroyedByEvictorCount.get();
    }


    public final long getDestroyedByBorrowValidationCount() {
        return destroyedByBorrowValidationCount.get();
    }


    public final long getMeanActiveTimeMillis() {
        return activeTimes.getMean();
    }


    public final long getMeanIdleTimeMillis() {
        return idleTimes.getMean();
    }

    /**
     * 线程从池中取对象的平均等待时间
     */
    public final long getMeanBorrowWaitTimeMillis() {
        return waitTimes.getMean();
    }

    /**
     * 线程曾经从池中取对象的最长等待时间.
     */
    public final long getMaxBorrowWaitTimeMillis() {
        return maxBorrowWaitTimeMillis.get();
    }

    /**
     * @return 池中空闲实例的数量
     */
    public abstract int getNumIdle();


    public final SwallowedExceptionListener getSwallowedExceptionListener() {
        return swallowedExceptionListener;
    }


    public final void setSwallowedExceptionListener(
            SwallowedExceptionListener swallowedExceptionListener) {
        this.swallowedExceptionListener = swallowedExceptionListener;
    }


    final void swallowException(Exception e) {
        SwallowedExceptionListener listener = getSwallowedExceptionListener();

        if (listener == null) {
            return;
        }

        try {
            listener.onSwallowException(e);
        } catch (OutOfMemoryError oome) {
            throw oome;
        } catch (VirtualMachineError vme) {
            throw vme;
        } catch (Throwable t) {
            // Ignore. Enjoy the irony.
        }
    }

    /**
     * 从池中取出对象后更新统计信息。
     * @param p 从池中取出的对象
     * @param waitTime time (in milliseconds) that the borrowing thread had to wait
     */
    final void updateStatsBorrow(PooledObject<T> p, long waitTime) {
        //CAS方式+1
        borrowedCount.incrementAndGet();
        idleTimes.add(p.getIdleTimeMillis());
        waitTimes.add(waitTime);

        // lock-free optimistic-locking maximum
        long currentMax;
        do {
            currentMax = maxBorrowWaitTimeMillis.get();
            if (currentMax >= waitTime) {
                break;
            }
        } while (!maxBorrowWaitTimeMillis.compareAndSet(currentMax, waitTime));
    }

    /**
     * 更新统计信息。
     */
    final void updateStatsReturn(long activeTime) {
        returnedCount.incrementAndGet();
        activeTimes.add(activeTime);
    }


    final void jmxUnregister() {
        if (oname != null) {
            try {
                ManagementFactory.getPlatformMBeanServer().unregisterMBean(
                        oname);
            } catch (MBeanRegistrationException e) {
                swallowException(e);
            } catch (InstanceNotFoundException e) {
                swallowException(e);
            }
        }
    }

    /**
     * 注册pool到MBean server.
     * 注册名称为mxNameBase + jmxNamePrefix + i
     *
     * @return 被注册的ObjectName, 如果注册失败则为null
     */
    private ObjectName jmxRegister(BaseObjectPoolConfig config,
                                   String jmxNameBase, String jmxNamePrefix) {
        ObjectName objectName = null;
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        int i = 1;
        boolean registered = false;
        String base = config.getJmxNameBase();
        if (base == null) {
            base = jmxNameBase;
        }
        while (!registered) {
            try {
                ObjectName objName;
                // Skip the numeric suffix for the first pool in case there is
                // only one so the names are cleaner.
                if (i == 1) {
                    objName = new ObjectName(base + jmxNamePrefix);
                } else {
                    objName = new ObjectName(base + jmxNamePrefix + i);
                }
                mbs.registerMBean(this, objName);
                objectName = objName;
                registered = true;
            } catch (MalformedObjectNameException e) {
                if (BaseObjectPoolConfig.DEFAULT_JMX_NAME_PREFIX.equals(
                        jmxNamePrefix) && jmxNameBase.equals(base)) {
                    // Shouldn't happen. Skip registration if it does.
                    registered = true;
                } else {
                    // Must be an invalid name. Use the defaults instead.
                    jmxNamePrefix =
                            BaseObjectPoolConfig.DEFAULT_JMX_NAME_PREFIX;
                    base = jmxNameBase;
                }
            } catch (InstanceAlreadyExistsException e) {
                // Increment the index and try again
                i++;
            } catch (MBeanRegistrationException e) {
                // Shouldn't happen. Skip registration if it does.
                registered = true;
            } catch (NotCompliantMBeanException e) {
                // Shouldn't happen. Skip registration if it does.
                registered = true;
            }
        }
        return objectName;
    }

    /**
     * 以字符串形式获取异常的堆栈跟踪。
     */
    private String getStackTrace(Exception e) {

        Writer w = new StringWriter();
        PrintWriter pw = new PrintWriter(w);
        e.printStackTrace(pw);
        return w.toString();
    }

    // Inner classes

    /**
     * 驱逐器，定时任务
     */
    class Evictor extends TimerTask {
        /**
         * 定时清理pool .确保可用的最小空闲实例数。
         */
        @Override
        public void run() {
            ClassLoader savedClassLoader =
                    Thread.currentThread().getContextClassLoader();
            try {
                if (factoryClassLoader != null) {
                    ClassLoader cl = factoryClassLoader.get();
                    if (cl == null) {
                        cancel();
                        return;
                    }
                    Thread.currentThread().setContextClassLoader(cl);
                }

                // Evict from the pool
                try {
                    evict();
                } catch(Exception e) {
                    swallowException(e);
                } catch(OutOfMemoryError oome) {
                    oome.printStackTrace(System.err);
                }
                // 从新创建空闲实例
                try {
                    ensureMinIdle();
                } catch (Exception e) {
                    swallowException(e);
                }
            } finally {
                Thread.currentThread().setContextClassLoader(savedClassLoader);
            }
        }
    }


    private class StatsStore {

        private final AtomicLong values[];
        private final int size;
        private int index;

        public StatsStore(int size) {
            this.size = size;
            values = new AtomicLong[size];
            for (int i = 0; i < size; i++) {
                values[i] = new AtomicLong(-1);
            }
        }


        public synchronized void add(long value) {
            values[index].set(value);
            index++;
            if (index == size) {
                index = 0;
            }
        }

        public long getMean() {
            double result = 0;
            int counter = 0;
            for (int i = 0; i < size; i++) {
                long value = values[i].get();
                if (value != -1) {
                    counter++;
                    result = result * ((counter - 1) / (double) counter) +
                            value/(double) counter;
                }
            }
            return (long) result;
        }
    }

    /**
     * 空闲对象迭代器
     */
    class EvictionIterator implements Iterator<PooledObject<T>> {

        private final Deque<PooledObject<T>> idleObjects;
        private final Iterator<PooledObject<T>> idleObjectIterator;


        EvictionIterator(final Deque<PooledObject<T>> idleObjects) {
            this.idleObjects = idleObjects;

            if (getLifo()) {
                idleObjectIterator = idleObjects.descendingIterator();
            } else {
                idleObjectIterator = idleObjects.iterator();
            }
        }


        public Deque<PooledObject<T>> getIdleObjects() {
            return idleObjects;
        }

        @Override
        public boolean hasNext() {
            return idleObjectIterator.hasNext();
        }

        @Override
        public PooledObject<T> next() {
            return idleObjectIterator.next();
        }


        @Override
        public void remove() {
            idleObjectIterator.remove();
        }

    }

    /**
     * 池管理的包装对象。
     *
     * GenericObjectPool和GenericKeyedObjectPool
     * k-v维护对管理下的所*对象的引用。
     * 这个包装器类确保对象可以作为哈希键。
     */
    static class IdentityWrapper<T> {
        /** 包装对象 */
        private final T instance;

        /**
         * 创建一个包装实例
         */
        public IdentityWrapper(T instance) {
            this.instance = instance;
        }


        /**
         * 重写了hashCode方法和equals方法，可以作为key
         */
        @Override
        public int hashCode() {
            return System.identityHashCode(instance);
        }

        @Override
        public boolean equals(Object other) {
            return ((IdentityWrapper) other).instance == instance;
        }

        /**
         * @return 被包装的对象
         */
        public T getObject() {
            return instance;
        }
    }

}

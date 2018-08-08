package org.apache.commons.pool2.impl;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.PoolUtils;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.PooledObjectState;
import org.apache.commons.pool2.SwallowedExceptionListener;
import org.apache.commons.pool2.TrackedUse;
import org.apache.commons.pool2.UsageTracking;


/**
 * 使用了LinkedBlockingDeque来存储空闲对象
 */
public class GenericObjectPool<T> extends BaseGenericObjectPool<T>
        implements ObjectPool<T>, GenericObjectPoolMXBean, UsageTracking<T> {

    /**
     * 使用{@link GenericObjectPoolConfig}中的默认值创建一个新的 GenericObjectPool对象
     *
     * @param factory 用于创建此池使用的对象实例的对象工厂
     */
    public GenericObjectPool(PooledObjectFactory<T> factory) {
        this(factory, new GenericObjectPoolConfig());
    }

    /**
     *使用指定的GenericObjectPoolConfig创建一个新的 GenericObjectPool对象
     */
    public GenericObjectPool(PooledObjectFactory<T> factory,
                             GenericObjectPoolConfig config) {

        super(config, ONAME_BASE, config.getJmxNamePrefix());

        if (factory == null) {
            jmxUnregister(); // tidy up
            throw new IllegalArgumentException("factory may not be null");
        }
        this.factory = factory;

        idleObjects = new LinkedBlockingDeque<PooledObject<T>>(config.getFairness());

        setConfig(config);

        startEvictor(getTimeBetweenEvictionRunsMillis());
    }

    /**
     * 构造器
     */
    public GenericObjectPool(PooledObjectFactory<T> factory,
                             GenericObjectPoolConfig config, AbandonedConfig abandonedConfig) {
        this(factory, config);
        setAbandonedConfig(abandonedConfig);
    }

    /**
     * 最大空闲数
     */
    @Override
    public int getMaxIdle() {
        return maxIdle;
    }

    /**
     * 设置最大空闲数
     */
    public void setMaxIdle(int maxIdle) {
        this.maxIdle = maxIdle;
    }

    /**
     * 最小空闲数
     */
    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
    }

    /**
     * 获取最小空闲数
     */
    @Override
    public int getMinIdle() {
        int maxIdleSave = getMaxIdle();
        if (this.minIdle > maxIdleSave) {
            return maxIdleSave;
        } else {
            return minIdle;
        }
    }

    /**
     * 是否有废弃时的配置
     */
    @Override
    public boolean isAbandonedConfig() {
        return abandonedConfig != null;
    }

    /**
     * 废弃时是否要log
     */
    @Override
    public boolean getLogAbandoned() {
        AbandonedConfig ac = this.abandonedConfig;
        return ac != null && ac.getLogAbandoned();
    }


    @Override
    public boolean getRemoveAbandonedOnBorrow() {
        AbandonedConfig ac = this.abandonedConfig;
        return ac != null && ac.getRemoveAbandonedOnBorrow();
    }


    @Override
    public boolean getRemoveAbandonedOnMaintenance() {
        AbandonedConfig ac = this.abandonedConfig;
        return ac != null && ac.getRemoveAbandonedOnMaintenance();
    }


    @Override
    public int getRemoveAbandonedTimeout() {
        AbandonedConfig ac = this.abandonedConfig;
        return ac != null ? ac.getRemoveAbandonedTimeout() : Integer.MAX_VALUE;
    }


    /**
     * 设置pool的配置
     */
    public void setConfig(GenericObjectPoolConfig conf) {
        setLifo(conf.getLifo());
        setMaxIdle(conf.getMaxIdle());
        setMinIdle(conf.getMinIdle());
        setMaxTotal(conf.getMaxTotal());
        setMaxWaitMillis(conf.getMaxWaitMillis());
        setBlockWhenExhausted(conf.getBlockWhenExhausted());
        setTestOnCreate(conf.getTestOnCreate());
        setTestOnBorrow(conf.getTestOnBorrow());
        setTestOnReturn(conf.getTestOnReturn());
        setTestWhileIdle(conf.getTestWhileIdle());
        setNumTestsPerEvictionRun(conf.getNumTestsPerEvictionRun());
        setMinEvictableIdleTimeMillis(conf.getMinEvictableIdleTimeMillis());
        setTimeBetweenEvictionRunsMillis(
                conf.getTimeBetweenEvictionRunsMillis());
        setSoftMinEvictableIdleTimeMillis(
                conf.getSoftMinEvictableIdleTimeMillis());
        setEvictionPolicyClassName(conf.getEvictionPolicyClassName());
    }

    /**
     * 设置废弃对象移除时的配置
     */
    public void setAbandonedConfig(AbandonedConfig abandonedConfig) throws IllegalArgumentException {
        if (abandonedConfig == null) {
            this.abandonedConfig = null;
        } else {
            this.abandonedConfig = new AbandonedConfig();
            this.abandonedConfig.setLogAbandoned(abandonedConfig.getLogAbandoned());
            this.abandonedConfig.setLogWriter(abandonedConfig.getLogWriter());
            this.abandonedConfig.setRemoveAbandonedOnBorrow(abandonedConfig.getRemoveAbandonedOnBorrow());
            this.abandonedConfig.setRemoveAbandonedOnMaintenance(abandonedConfig.getRemoveAbandonedOnMaintenance());
            this.abandonedConfig.setRemoveAbandonedTimeout(abandonedConfig.getRemoveAbandonedTimeout());
            this.abandonedConfig.setUseUsageTracking(abandonedConfig.getUseUsageTracking());
        }
    }

    /**
     * @return 创建对象的工厂
     */
    public PooledObjectFactory<T> getFactory() {
        return factory;
    }


    @Override
    public T borrowObject() throws Exception {
        return borrowObject(getMaxWaitMillis());
    }

    /**
     * 使用特定的等待时间从池中取出一个对象
     *
     * 如果getBlockWhenExhausted（）为true，
     * 则仅适用如果池中有一个或多个空闲实例，则将根据getLifo()的值选择空闲对象，激活并返回。
     * 如果激活失败，或者testOnBorrow设置为 true 并且验证失败
     * ，则会销毁实例并检查下一个可用实例。直到返回有效实例或没有更多
     * 空闲实例可用。
     * 如果池中没有可用的空闲实例，则行为取决于getMaxTotal()maxTotal，
     * 如果从池中签出的实例数小于 maxTotal，，则会创建一个新的实例，
     * 激活并验证然后返回给调用者。如果验证失败，则抛出NoSuchElementException 。
     *
     * 如果池已用尽，将阻止getBlockWhenExhausted()为true或抛出NoSuchElementException
     * 当池耗尽时，可能同时阻止多个调用线程等待实例可用。
     * 公平算法以确保线程在请求​​到达顺序中接收可用实例。
     *
     * @param borrowMaxWaitMillis 等待时间（以毫秒为单位）
     * @return object instance from the pool
     * @throws NoSuchElementException 如果无法返回实例
     * @throws Exception 如果由于错误而无法返回对象实例
     */
    public T borrowObject(long borrowMaxWaitMillis) throws Exception {
        //验证池是否开启
        assertOpen();

        AbandonedConfig ac = this.abandonedConfig;
        if (ac != null && ac.getRemoveAbandonedOnBorrow() &&
                (getNumIdle() < 2) &&
                (getNumActive() > getMaxTotal() - 3) ) {
            removeAbandoned(ac);
        }

        PooledObject<T> p = null;

        // 池中没有对象时是否阻塞
        boolean blockWhenExhausted = getBlockWhenExhausted();

        boolean create;
        long waitTime = System.currentTimeMillis();

        while (p == null) {
            create = false;
            if (blockWhenExhausted) {
                //阻塞
                p = idleObjects.pollFirst();
                if (p == null) {
                    //如果为null,则尝试创建一个新的池对象。
                    p = create();
                    if (p != null) {
                        create = true;
                    }
                }
                if (p == null) {
                    //如果还为null，则说明数量满啦
                    if (borrowMaxWaitMillis < 0) {
                        //一直等待
                        p = idleObjects.takeFirst();
                    } else {
                        //限时等待
                        p = idleObjects.pollFirst(borrowMaxWaitMillis,
                                TimeUnit.MILLISECONDS);
                    }
                }
                if (p == null) {
                    //如果还为null，超时
                    throw new NoSuchElementException(
                            "Timeout waiting for idle object");
                }
                if (!p.allocate()) {
                    p = null;
                }
            } else {
                //不阻塞
                p = idleObjects.pollFirst();
                if (p == null) {
                    p = create();
                    if (p != null) {
                        create = true;
                    }
                }
                if (p == null) {
                    //耗尽
                    throw new NoSuchElementException("Pool exhausted");
                }
                if (!p.allocate()) {
                    p = null;
                }
            }

            if (p != null) {
                try {
                    //重新初始化池返回的实例.
                    factory.activateObject(p);
                } catch (Exception e) {
                    try {
                        destroy(p);
                    } catch (Exception e1) {
                        // Ignore - activation failure is more important
                    }
                    p = null;
                    if (create) {
                        NoSuchElementException nsee = new NoSuchElementException(
                                "Unable to activate object");
                        nsee.initCause(e);
                        throw nsee;
                    }
                }
                if (p != null && (getTestOnBorrow() || create && getTestOnCreate())) {
                    boolean validate = false;
                    Throwable validationThrowable = null;
                    try {
                        //验证对象,确保池可以安全地返回实例。
                        validate = factory.validateObject(p);
                    } catch (Throwable t) {
                        PoolUtils.checkRethrow(t);
                        validationThrowable = t;
                    }
                    if (!validate) {
                        try {
                            destroy(p);
                            destroyedByBorrowValidationCount.incrementAndGet();
                        } catch (Exception e) {
                            // Ignore - validation failure is more important
                        }
                        p = null;
                        if (create) {
                            NoSuchElementException nsee = new NoSuchElementException(
                                    "Unable to validate object");
                            nsee.initCause(validationThrowable);
                            throw nsee;
                        }
                    }
                }
            }
        }

        updateStatsBorrow(p, System.currentTimeMillis() - waitTime);

        return p.getObject();
    }

    /**
     * 返回对象到池中
     */
    @Override
    public void returnObject(T obj) {
        PooledObject<T> p = allObjects.get(new IdentityWrapper<T>(obj));

        if (p == null) {
            if (!isAbandonedConfig()) {
                throw new IllegalStateException(
                        "Returned object not currently part of this pool");
            } else {
                // 对象被抛弃并删除
                return;
            }
        }

        synchronized(p) {
            final PooledObjectState state = p.getState();
            if (state != PooledObjectState.ALLOCATED) {
                throw new IllegalStateException(
                        "Object has already been returned to this pool or is invalid");
            } else {
                //标记状态为RETURNING;
                p.markReturning(); // Keep from being marked abandoned
            }
        }

        long activeTime = p.getActiveTimeMillis();

        if (getTestOnReturn()) {
            if (!factory.validateObject(p)) {
                try {
                    //验证失败
                    destroy(p);
                } catch (Exception e) {
                    swallowException(e);
                }
                try {
                    //尝试补充一个对象到池中
                    ensureIdle(1, false);
                } catch (Exception e) {
                    swallowException(e);
                }
                updateStatsReturn(activeTime);
                return;
            }
        }

        try {
            factory.passivateObject(p);
        } catch (Exception e1) {
            swallowException(e1);
            try {
                destroy(p);
            } catch (Exception e) {
                swallowException(e);
            }
            try {
                ensureIdle(1, false);
            } catch (Exception e) {
                swallowException(e);
            }
            updateStatsReturn(activeTime);
            return;
        }

        if (!p.deallocate()) {
            throw new IllegalStateException(
                    "Object has already been returned to this pool or is invalid");
        }

        int maxIdleSave = getMaxIdle();
        if (isClosed() || maxIdleSave > -1 && maxIdleSave <= idleObjects.size()) {
            try {
                //关闭了或空闲数量太多
                destroy(p);
            } catch (Exception e) {
                swallowException(e);
            }
        } else {
            //返回池中
            if (getLifo()) {
                idleObjects.addFirst(p);
            } else {
                idleObjects.addLast(p);
            }
            if (isClosed()) {
                // 清理空闲对象
                clear();
            }
        }
        updateStatsReturn(activeTime);
    }

    /**
     * 废弃的方法
     */
    @Override
    public void invalidateObject(T obj) throws Exception {
        PooledObject<T> p = allObjects.get(new IdentityWrapper<T>(obj));
        if (p == null) {
            if (isAbandonedConfig()) {
                return;
            } else {
                throw new IllegalStateException(
                        "Invalidated object not currently part of this pool");
            }
        }
        synchronized (p) {
            if (p.getState() != PooledObjectState.INVALID) {
                destroy(p);
            }
        }
        //保证数量
        ensureIdle(1, false);
    }

    /**
     * 清除池中空闲的所有对象
     */
    @Override
    public void clear() {
        PooledObject<T> p = idleObjects.poll();

        while (p != null) {
            try {
                destroy(p);
            } catch (Exception e) {
                swallowException(e);
            }
            p = idleObjects.poll();
        }
    }

    @Override
    public int getNumActive() {
        return allObjects.size() - idleObjects.size();
    }

    @Override
    public int getNumIdle() {
        return idleObjects.size();
    }

    /**
     * 关闭池。
     *
     * 关闭池后，borrowObject将失败并抛出IllegalStateException，
     * 但returnObject和invalidateObject将继续工作，
     * 返回对象在返回时被摧毁
     */
    @Override
    public void close() {
        if (isClosed()) {
            return;
        }

        synchronized (closeLock) {
            if (isClosed()) {
                return;
            }

            startEvictor(-1L);

            closed = true;
            // 清除空闲对象
            clear();

            jmxUnregister();

            //释放所有等待对象的线程
            idleObjects.interuptTakeWaiters();
        }
    }

    /**
     * 驱逐器，根据timeBetweenEvictionRunsMillis
     */
    @Override
    public void evict() throws Exception {
        assertOpen();

        //判断是否有空闲对象
        if (idleObjects.size() > 0) {

            PooledObject<T> underTest = null;
            EvictionPolicy<T> evictionPolicy = getEvictionPolicy();

            synchronized (evictionLock) {
                //获取配置
                EvictionConfig evictionConfig = new EvictionConfig(
                        getMinEvictableIdleTimeMillis(),
                        getSoftMinEvictableIdleTimeMillis(),
                        getMinIdle());

                boolean testWhileIdle = getTestWhileIdle();

                for (int i = 0, m = getNumTests(); i < m; i++) {
                    if (evictionIterator == null || !evictionIterator.hasNext()) {
                        evictionIterator = new EvictionIterator(idleObjects);
                    }
                    if (!evictionIterator.hasNext()) {
                        // Pool exhausted, nothing to do here
                        return;
                    }

                    try {
                        underTest = evictionIterator.next();
                    } catch (NoSuchElementException nsee) {
                        // Object was borrowed in another thread
                        // Don't count this as an eviction test so reduce i;
                        i--;
                        evictionIterator = null;
                        continue;
                    }

                    if (!underTest.startEvictionTest()) {
                        // Object was borrowed in another thread
                        // Don't count this as an eviction test so reduce i;
                        i--;
                        continue;
                    }

                    // User provided eviction policy could throw all sorts of
                    // crazy exceptions. Protect against such an exception
                    // killing the eviction thread.
                    boolean evict;
                    try {
                        evict = evictionPolicy.evict(evictionConfig, underTest,
                                idleObjects.size());
                    } catch (Throwable t) {
                        // Slightly convoluted as SwallowedExceptionListener
                        // uses Exception rather than Throwable
                        PoolUtils.checkRethrow(t);
                        swallowException(new Exception(t));
                        // Don't evict on error conditions
                        evict = false;
                    }

                    if (evict) {
                        destroy(underTest);
                        destroyedByEvictorCount.incrementAndGet();
                    } else {
                        if (testWhileIdle) {
                            boolean active = false;
                            try {
                                factory.activateObject(underTest);
                                active = true;
                            } catch (Exception e) {
                                destroy(underTest);
                                destroyedByEvictorCount.incrementAndGet();
                            }
                            if (active) {
                                if (!factory.validateObject(underTest)) {
                                    destroy(underTest);
                                    destroyedByEvictorCount.incrementAndGet();
                                } else {
                                    try {
                                        factory.passivateObject(underTest);
                                    } catch (Exception e) {
                                        destroy(underTest);
                                        destroyedByEvictorCount.incrementAndGet();
                                    }
                                }
                            }
                        }
                        if (!underTest.endEvictionTest(idleObjects)) {
                        }
                    }
                }
            }
        }
        AbandonedConfig ac = this.abandonedConfig;
        if (ac != null && ac.getRemoveAbandonedOnMaintenance()) {
            removeAbandoned(ac);
        }
    }

    /**
     * 尝试让池中有getMinIdle的空闲实例可用
     */
    public void preparePool() throws Exception {
        if (getMinIdle() < 1) {
            return;
        }
        ensureMinIdle();
    }

    /**
     * 尝试创建一个新的池对象。
     */
    private PooledObject<T> create() throws Exception {
        int localMaxTotal = getMaxTotal();
        //CAS的方式加1
        long newCreateCount = createCount.incrementAndGet();
        if (localMaxTotal > -1 && newCreateCount > localMaxTotal ||
                newCreateCount > Integer.MAX_VALUE) {
            //数量溢出
            createCount.decrementAndGet();
            return null;
        }

        final PooledObject<T> p;
        try {
            //通过工厂创建一个对象
            p = factory.makeObject();
        } catch (Exception e) {
            //失败啦，则-1
            createCount.decrementAndGet();
            throw e;
        }

        AbandonedConfig ac = this.abandonedConfig;
        if (ac != null && ac.getLogAbandoned()) {
            p.setLogAbandoned(true);
        }

        createdCount.incrementAndGet();
        //放入allObjects
        allObjects.put(new IdentityWrapper<T>(p.getObject()), p);
        return p;
    }

    /**
     * 销毁包装对象
     */
    private void destroy(PooledObject<T> toDestory) throws Exception {
        toDestory.invalidate();
        idleObjects.remove(toDestory);
        allObjects.remove(new IdentityWrapper<T>(toDestory.getObject()));
        try {
            factory.destroyObject(toDestory);
        } finally {
            destroyedCount.incrementAndGet();
            createCount.decrementAndGet();
        }
    }

    @Override
    void ensureMinIdle() throws Exception {
        ensureIdle(getMinIdle(), true);
    }

    /**
     * 确保池中有几个空闲对象
     *
     * @param idleCount 空闲实例数
     * @param always true表示即使池没有线程等待也会创建实例
     */
    private void ensureIdle(int idleCount, boolean always) throws Exception {
        if (idleCount < 1 || isClosed() || (!always && !idleObjects.hasTakeWaiters())) {
            return;
        }

        while (idleObjects.size() < idleCount) {
            PooledObject<T> p = create();
            if (p == null) {
                // Can't create objects, no reason to think another call to
                // create will work. Give up.
                break;
            }
            if (getLifo()) {
                idleObjects.addFirst(p);
            } else {
                idleObjects.addLast(p);
            }
        }
        if (isClosed()) {
            // Pool closed while object was being added to idle objects.
            // Make sure the returned object is destroyed rather than left
            // in the idle object pool (which would effectively be a leak)
            clear();
        }
    }

    /**
     * 添加对象
     */
    @Override
    public void addObject() throws Exception {
        assertOpen();
        if (factory == null) {
            throw new IllegalStateException(
                    "Cannot add objects without a factory.");
        }
        PooledObject<T> p = create();
        addIdleObject(p);
    }

    /**
     * 添加空闲对象
     */
    private void addIdleObject(PooledObject<T> p) throws Exception {
        if (p != null) {
            factory.passivateObject(p);
            if (getLifo()) {
                idleObjects.addFirst(p);
            } else {
                idleObjects.addLast(p);
            }
        }
    }

    /**
     * @return 被验证过的对象数量
     */
    private int getNumTests() {
        int numTestsPerEvictionRun = getNumTestsPerEvictionRun();
        if (numTestsPerEvictionRun >= 0) {
            return Math.min(numTestsPerEvictionRun, idleObjects.size());
        } else {
            return (int) (Math.ceil(idleObjects.size() /
                    Math.abs((double) numTestsPerEvictionRun)));
        }
    }

    /**
     * @param ac 标识已放弃对象的配置
     */
    private void removeAbandoned(AbandonedConfig ac) {
        // Generate a list of abandoned objects to remove
        final long now = System.currentTimeMillis();
        final long timeout = now - (ac.getRemoveAbandonedTimeout() * 1000L);
        ArrayList<PooledObject<T>> remove = new ArrayList<PooledObject<T>>();
        //allObjects是ConcurrentHashMap，是线程安全的
        Iterator<PooledObject<T>> it = allObjects.values().iterator();
        while (it.hasNext()) {
            PooledObject<T> pooledObject = it.next();
            synchronized (pooledObject) {
                if (pooledObject.getState() == PooledObjectState.ALLOCATED &&
                        pooledObject.getLastUsedTime() <= timeout) {
                    pooledObject.markAbandoned();
                    remove.add(pooledObject);
                }
            }
        }

        // Now remove the abandoned objects
        Iterator<PooledObject<T>> itr = remove.iterator();
        while (itr.hasNext()) {
            PooledObject<T> pooledObject = itr.next();
            if (ac.getLogAbandoned()) {
                pooledObject.printStackTrace(ac.getLogWriter());
            }
            try {
                invalidateObject(pooledObject.getObject());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    //--- Usage tracking support -----------------------------------------------

    @Override
    public void use(T pooledObject) {
        AbandonedConfig ac = this.abandonedConfig;
        if (ac != null && ac.getUseUsageTracking()) {
            PooledObject<T> wrapper = allObjects.get(new IdentityWrapper<T>(pooledObject));
            wrapper.use();
        }
    }


    //--- JMX support ----------------------------------------------------------

    private volatile String factoryType = null;

    /**
     * 返回当前阻塞的线程数的估计值，等待来自池中的对象。
     * 这仅用于监视，而不用于同步控制。
     */
    @Override
    public int getNumWaiters() {
        if (getBlockWhenExhausted()) {
            return idleObjects.getTakeQueueLength();
        } else {
            return 0;
        }
    }

    /**
     * @return PooledObjectFactory的类型
     */
    @Override
    public String getFactoryType() {
        // Not thread safe. Accept that there may be multiple evaluations.
        if (factoryType == null) {
            StringBuilder result = new StringBuilder();
            result.append(factory.getClass().getName());
            result.append('<');
            Class<?> pooledObjectType =
                    PoolImplUtils.getFactoryType(factory.getClass());
            result.append(pooledObjectType.getName());
            result.append('>');
            factoryType = result.toString();
        }
        return factoryType;
    }

    /**
     * 提供有关池中所有对象的信息，包括空闲被借用的。
     */
    @Override
    public Set<DefaultPooledObjectInfo> listAllObjects() {
        Set<DefaultPooledObjectInfo> result =
                new HashSet<DefaultPooledObjectInfo>(allObjects.size());
        for (PooledObject<T> p : allObjects.values()) {
            result.add(new DefaultPooledObjectInfo(p));
        }
        return result;
    }

    // --- configuration attributes --------------------------------------------

    private volatile int maxIdle = GenericObjectPoolConfig.DEFAULT_MAX_IDLE;
    private volatile int minIdle = GenericObjectPoolConfig.DEFAULT_MIN_IDLE;
    private final PooledObjectFactory<T> factory;


    // --- internal attributes -------------------------------------------------

    /*
     * 当前与此池关联的所有对象处于任何状态。
     * 但排除了被破坏的物体
     * allObjects的大小始终小于或等于_maxActive
     * 映射键是池对象，值是池内部使用的PooledObject包装器。
     */
    private final Map<IdentityWrapper<T>, PooledObject<T>> allObjects =
            new ConcurrentHashMap<IdentityWrapper<T>, PooledObject<T>>();
    /**
     * 当前创建的对象与正在创建的进程中的对象的组合计数。
     * 在负载下，如果多个线程同时尝试创建一个新对象，
     * 它可能超过_maxActive但create（）将确保永远不会超过
     * _maxActive任何时候创建的对象。
     */
    private final AtomicLong createCount = new AtomicLong(0);
    /**
     * 空闲对象
     */
    private final LinkedBlockingDeque<PooledObject<T>> idleObjects;

    private static final String ONAME_BASE =
            "org.apache.commons.pool2:type=GenericObjectPool,name=";


    private volatile AbandonedConfig abandonedConfig = null;

}

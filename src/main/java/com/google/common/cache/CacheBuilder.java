package com.google.common.cache;



import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.base.Ascii;
import com.google.common.base.Equivalence;
import com.google.common.base.MoreObjects;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Ticker;
import com.google.common.cache.AbstractCache.SimpleStatsCounter;
import com.google.common.cache.AbstractCache.StatsCounter;
import com.google.common.cache.LocalCache.Strength;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.j2objc.annotations.J2ObjCIncompatible;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;


@GwtCompatible(emulated = true)
public final class CacheBuilder<K, V> {
    /*默认初始化容量*/
    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    /*默认并发等级*/
    private static final int DEFAULT_CONCURRENCY_LEVEL = 4;
    private static final int DEFAULT_EXPIRATION_NANOS = 0;
    private static final int DEFAULT_REFRESH_NANOS = 0;

    static final Supplier<? extends StatsCounter> NULL_STATS_COUNTER =
            Suppliers.ofInstance(
                    new StatsCounter() {
                        @Override
                        public void recordHits(int count) {}

                        @Override
                        public void recordMisses(int count) {}

                        @Override
                        public void recordLoadSuccess(long loadTime) {}

                        @Override
                        public void recordLoadException(long loadTime) {}

                        @Override
                        public void recordEviction() {}

                        @Override
                        public CacheStats snapshot() {
                            return EMPTY_STATS;
                        }
                    });
    static final CacheStats EMPTY_STATS = new CacheStats(0, 0, 0, 0, 0, 0);

    static final Supplier<StatsCounter> CACHE_STATS_COUNTER =
            new Supplier<StatsCounter>() {
                @Override
                public StatsCounter get() {
                    return new SimpleStatsCounter();
                }
            };

    enum NullListener implements RemovalListener<Object, Object> {
        INSTANCE;

        @Override
        public void onRemoval(RemovalNotification<Object, Object> notification) {}
    }

    enum OneWeigher implements Weigher<Object, Object> {
        INSTANCE;

        @Override
        public int weigh(Object key, Object value) {
            return 1;
        }
    }

    static final Ticker NULL_TICKER =
            new Ticker() {
                @Override
                public long read() {
                    return 0;
                }
            };

    private static final Logger logger = Logger.getLogger(CacheBuilder.class.getName());

    static final int UNSET_INT = -1;

    boolean strictParsing = true;

    int initialCapacity = UNSET_INT;
    int concurrencyLevel = UNSET_INT;
    long maximumSize = UNSET_INT;
    long maximumWeight = UNSET_INT;
    @MonotonicNonNull Weigher<? super K, ? super V> weigher;

    @MonotonicNonNull Strength keyStrength;
    @MonotonicNonNull Strength valueStrength;

    long expireAfterWriteNanos = UNSET_INT;
    long expireAfterAccessNanos = UNSET_INT;
    long refreshNanos = UNSET_INT;

    @MonotonicNonNull Equivalence<Object> keyEquivalence;
    @MonotonicNonNull Equivalence<Object> valueEquivalence;

    @MonotonicNonNull RemovalListener<? super K, ? super V> removalListener;
    @MonotonicNonNull Ticker ticker;

    Supplier<? extends StatsCounter> statsCounterSupplier = NULL_STATS_COUNTER;

    private CacheBuilder() {}

    /**
     * 创建一个CacheBuilder实例，包括强引用的key和value
     */
    public static CacheBuilder<Object, Object> newBuilder() {
        return new CacheBuilder<>();
    }

    /**
     * 根据CacheBuilderSpec中获取一个经过设置的CacheBuilder对象
     */
    @GwtIncompatible // To be supported
    public static CacheBuilder<Object, Object> from(CacheBuilderSpec spec) {
        return spec.toCacheBuilder().lenientParsing();
    }

    /**
     * 根据String中获取一个经过设置的CacheBuilder对象
     */
    @GwtIncompatible // To be supported
    public static CacheBuilder<Object, Object> from(String spec) {
        return from(CacheBuilderSpec.parse(spec));
    }

    /**
     * 关闭strictParsing
     */
    @GwtIncompatible // To be supported
    CacheBuilder<K, V> lenientParsing() {
        strictParsing = false;
        return this;
    }

    /**
     * 设置自定义的keyEquivalence
     */
    @GwtIncompatible // To be supported
    CacheBuilder<K, V> keyEquivalence(Equivalence<Object> equivalence) {
        checkState(keyEquivalence == null, "key equivalence was already set to %s", keyEquivalence);
        keyEquivalence = checkNotNull(equivalence);
        return this;
    }

    Equivalence<Object> getKeyEquivalence() {
        return MoreObjects.firstNonNull(keyEquivalence, getKeyStrength().defaultEquivalence());
    }

    /**
     * 设置自定义的valueEquivalence
     */
    @GwtIncompatible // To be supported
    CacheBuilder<K, V> valueEquivalence(Equivalence<Object> equivalence) {
        checkState(valueEquivalence == null,
                "value equivalence was already set to %s", valueEquivalence);
        this.valueEquivalence = checkNotNull(equivalence);
        return this;
    }

    Equivalence<Object> getValueEquivalence() {
        return MoreObjects.firstNonNull(valueEquivalence, getValueStrength().defaultEquivalence());
    }

    /**
     * 设置初始化容量
     *
     * @return this {@code CacheBuilder}实例
     * @throws IllegalArgumentException 如果initialCapacity为负数
     * @throws IllegalStateException 如果初始化容量已经被设置
     */
    public CacheBuilder<K, V> initialCapacity(int initialCapacity) {
        checkState(
                this.initialCapacity == UNSET_INT,
                "initial capacity was already set to %s",
                this.initialCapacity);
        checkArgument(initialCapacity >= 0);
        this.initialCapacity = initialCapacity;
        return this;
    }

    int getInitialCapacity() {
        return (initialCapacity == UNSET_INT) ? DEFAULT_INITIAL_CAPACITY : initialCapacity;
    }

    /**
     * 设置并发等级，默认为4
     * 值大啦可能浪费时间和空间，值小啦会导致线程竞争
     *
     * @return {@code CacheBuilder} 实例
     * @throws IllegalArgumentException 如果{@code concurrencyLevel}为负数
     * @throws IllegalStateException 如果concurrencyLevel已经设置过啦
     */
    public CacheBuilder<K, V> concurrencyLevel(int concurrencyLevel) {
        checkState(
                this.concurrencyLevel == UNSET_INT,
                "concurrency level was already set to %s",
                this.concurrencyLevel);
        checkArgument(concurrencyLevel > 0);
        this.concurrencyLevel = concurrencyLevel;
        return this;
    }

    int getConcurrencyLevel() {
        return (concurrencyLevel == UNSET_INT) ? DEFAULT_CONCURRENCY_LEVEL : concurrencyLevel;
    }

    /**
     * 设置缓存中可以包含多少个的值，size和weight二选一
     *
     * @param maximumSize 缓存最大值
     * @return {@code CacheBuilder}实例
     * @throws IllegalArgumentException 如果{@code maximumSize}为负数
     * @throws IllegalStateException 如果已经设置过了size 或 weight
     */
    public CacheBuilder<K, V> maximumSize(long maximumSize) {
        checkState(
                this.maximumSize == UNSET_INT, "maximum size was already set to %s", this.maximumSize);
        checkState(
                this.maximumWeight == UNSET_INT,
                "maximum weight was already set to %s",
                this.maximumWeight);
        checkState(this.weigher == null, "maximum size can not be combined with weigher");
        checkArgument(maximumSize >= 0, "maximum size must not be negative");
        this.maximumSize = maximumSize;
        return this;
    }

    /**
     * 设置缓存中可以包含的weight总量，size和weight二选一
     *
     * @param maximumWeight 缓存中可以包含的weight总量
     * @return {@code CacheBuilder} 实例
     * @throws IllegalArgumentException 如果 {@code maximumWeight} 为负数
     * @throws IllegalStateException 如果已经设置过了size 或 weight
     * @since 11.0
     */
    @GwtIncompatible // To be supported
    public CacheBuilder<K, V> maximumWeight(long maximumWeight) {
        checkState(
                this.maximumWeight == UNSET_INT,
                "maximum weight was already set to %s",
                this.maximumWeight);
        checkState(
                this.maximumSize == UNSET_INT, "maximum size was already set to %s", this.maximumSize);
        this.maximumWeight = maximumWeight;
        checkArgument(maximumWeight >= 0, "maximum weight must not be negative");
        return this;
    }

    /**
     * 设置weigher
     *
     * @throws IllegalArgumentException 如果{@code size}为负数
     * @throws IllegalStateException 如果已经设置过啦
     */
    @GwtIncompatible // To be supported
    public <K1 extends K, V1 extends V> CacheBuilder<K1, V1> weigher(
            Weigher<? super K1, ? super V1> weigher) {
        checkState(this.weigher == null);
        if (strictParsing) {
            checkState(
                    this.maximumSize == UNSET_INT,
                    "weigher can not be combined with maximum size",
                    this.maximumSize);
        }

        // safely limiting the kinds of caches this can produce
        @SuppressWarnings("unchecked")
        CacheBuilder<K1, V1> me = (CacheBuilder<K1, V1>) this;
        me.weigher = checkNotNull(weigher);
        return me;
    }

    long getMaximumWeight() {
        if (expireAfterWriteNanos == 0 || expireAfterAccessNanos == 0) {
            return 0;
        }
        return (weigher == null) ? maximumSize : maximumWeight;
    }

    // Make a safe contravariant cast now so we don't have to do it over and over.
    @SuppressWarnings("unchecked")
    <K1 extends K, V1 extends V> Weigher<K1, V1> getWeigher() {
        return (Weigher<K1, V1>) MoreObjects.firstNonNull(weigher, OneWeigher.INSTANCE);
    }

    /**
     * 将key设为虚引用
     *
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalStateException 如果已经设置过了KeyStrength
     */
    @GwtIncompatible // java.lang.ref.WeakReference
    public CacheBuilder<K, V> weakKeys() {
        return setKeyStrength(Strength.WEAK);
    }

    CacheBuilder<K, V> setKeyStrength(Strength strength) {
        checkState(keyStrength == null, "Key strength was already set to %s", keyStrength);
        keyStrength = checkNotNull(strength);
        return this;
    }

    Strength getKeyStrength() {
        return MoreObjects.firstNonNull(keyStrength, Strength.STRONG);
    }

    /**
     * 将value设为虚引用
     *
     * @throws IllegalStateException 如果已经设置过了
     */
    @GwtIncompatible // java.lang.ref.WeakReference
    public CacheBuilder<K, V> weakValues() {
        return setValueStrength(Strength.WEAK);
    }

    /**
     * 将value设为软引用
     *
     * @throws IllegalStateException 如果已经设置过了
     */
    @GwtIncompatible // java.lang.ref.SoftReference
    public CacheBuilder<K, V> softValues() {
        return setValueStrength(Strength.SOFT);
    }

    CacheBuilder<K, V> setValueStrength(Strength strength) {
        checkState(valueStrength == null, "Value strength was already set to %s", valueStrength);
        valueStrength = checkNotNull(strength);
        return this;
    }

    Strength getValueStrength() {
        return MoreObjects.firstNonNull(valueStrength, Strength.STRONG);
    }

    /**
     * 在写后多久失效
     *
     * @param duration 多久失效
     * @throws IllegalArgumentException 如果{@code duration}是负数
     * @throws IllegalStateException 已经设置过了
     * @throws ArithmeticException durations正负大于292 years
     */
    @J2ObjCIncompatible
    @GwtIncompatible // java.time.Duration
    public CacheBuilder<K, V> expireAfterWrite(java.time.Duration duration) {
        return expireAfterWrite(duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * 在写后多久失效
     *
     * @param duration 多久失效
     * @param unit 单位
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalArgumentException 如果{@code duration}是负数
     * @throws IllegalStateException 已经设置过了
     */
    public CacheBuilder<K, V> expireAfterWrite(long duration, TimeUnit unit) {
        checkState(
                expireAfterWriteNanos == UNSET_INT,
                "expireAfterWrite was already set to %s ns",
                expireAfterWriteNanos);
        checkArgument(duration >= 0, "duration cannot be negative: %s %s", duration, unit);
        this.expireAfterWriteNanos = unit.toNanos(duration);
        return this;
    }

    long getExpireAfterWriteNanos() {
        return (expireAfterWriteNanos == UNSET_INT) ? DEFAULT_EXPIRATION_NANOS : expireAfterWriteNanos;
    }

    /**
     * 在没操作后多久失效
     *
     * @param duration 多久失效
     * @throws IllegalArgumentException 如果{@code duration}是负数
     * @throws IllegalStateException 已经设置过了
     * @throws ArithmeticException durations正负大于292 years
     */
    @J2ObjCIncompatible
    @GwtIncompatible // java.time.Duration
    public CacheBuilder<K, V> expireAfterAccess(java.time.Duration duration) {
        return expireAfterAccess(duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * 在没操作后多久失效
     *
     * @param duration 多久失效
     * @param unit 单位
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalArgumentException 如果{@code duration}是负数
     * @throws IllegalStateException 已经设置过了
     */
    public CacheBuilder<K, V> expireAfterAccess(long duration, TimeUnit unit) {
        checkState(
                expireAfterAccessNanos == UNSET_INT,
                "expireAfterAccess was already set to %s ns",
                expireAfterAccessNanos);
        checkArgument(duration >= 0, "duration cannot be negative: %s %s", duration, unit);
        this.expireAfterAccessNanos = unit.toNanos(duration);
        return this;
    }

    long getExpireAfterAccessNanos() {
        return (expireAfterAccessNanos == UNSET_INT)
                ? DEFAULT_EXPIRATION_NANOS
                : expireAfterAccessNanos;
    }

    /**
     * 写后多久后刷新
     *
     * @param duration 多久刷新
     * @throws IllegalArgumentException 如果{@code duration}是负数
     * @throws IllegalStateException 已经设置过了
     * @throws ArithmeticException durations正负大于292 years
     */
    @J2ObjCIncompatible
    @GwtIncompatible // java.time.Duration
    public CacheBuilder<K, V> refreshAfterWrite(java.time.Duration duration) {
        return refreshAfterWrite(duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * 写后多久后刷新
     *
     * @param duration 多久
     * @param unit 单位
     * @throws IllegalArgumentException 如果{@code duration}是负数
     * @throws IllegalStateException 已经设置过了
     * @throws ArithmeticException durations正负大于292 years
     */
    @GwtIncompatible // To be supported (synchronously).
    public CacheBuilder<K, V> refreshAfterWrite(long duration, TimeUnit unit) {
        checkNotNull(unit);
        checkState(refreshNanos == UNSET_INT, "refresh was already set to %s ns", refreshNanos);
        checkArgument(duration > 0, "duration must be positive: %s %s", duration, unit);
        this.refreshNanos = unit.toNanos(duration);
        return this;
    }

    long getRefreshNanos() {
        return (refreshNanos == UNSET_INT) ? DEFAULT_REFRESH_NANOS : refreshNanos;
    }

    /**
     * 为这个缓存指定一个纳秒精度的时间源. 默认为 {@link System#nanoTime}
     *
     * 方便测试
     *
     * @return this {@code CacheBuilder} instance (for chaining)
     * @throws IllegalStateException 已经设置过啦
     */
    public CacheBuilder<K, V> ticker(Ticker ticker) {
        checkState(this.ticker == null);
        this.ticker = checkNotNull(ticker);
        return this;
    }

    Ticker getTicker(boolean recordsTime) {
        if (ticker != null) {
            return ticker;
        }
        return recordsTime ? Ticker.systemTicker() : NULL_TICKER;
    }

    /**
     * remove时会调用的监听器
     * @throws IllegalStateException 已经设置过啦
     */
    @CheckReturnValue
    public <K1 extends K, V1 extends V> CacheBuilder<K1, V1> removalListener(
            RemovalListener<? super K1, ? super V1> listener) {
        checkState(this.removalListener == null);

        // safely limiting the kinds of caches this can produce
        @SuppressWarnings("unchecked")
        CacheBuilder<K1, V1> me = (CacheBuilder<K1, V1>) this;
        me.removalListener = checkNotNull(listener);
        return me;
    }

    // Make a safe contravariant cast now so we don't have to do it over and over.
    @SuppressWarnings("unchecked")
    <K1 extends K, V1 extends V> RemovalListener<K1, V1> getRemovalListener() {
        return (RemovalListener<K1, V1>)
                MoreObjects.firstNonNull(removalListener, NullListener.INSTANCE);
    }

    /**
     * 记录操作数据
     */
    public CacheBuilder<K, V> recordStats() {
        statsCounterSupplier = CACHE_STATS_COUNTER;
        return this;
    }

    boolean isRecordingStats() {
        return statsCounterSupplier == CACHE_STATS_COUNTER;
    }

    Supplier<? extends StatsCounter> getStatsCounterSupplier() {
        return statsCounterSupplier;
    }

    /**
     * 创建一个LoadingCache
     */
    public <K1 extends K, V1 extends V> LoadingCache<K1, V1> build(
            CacheLoader<? super K1, V1> loader) {
        checkWeightWithWeigher();
        return new LocalCache.LocalLoadingCache<>(this, loader);
    }

    /**
     * 创建一个Cache
     */
    public <K1 extends K, V1 extends V> Cache<K1, V1> build() {
        checkWeightWithWeigher();
        checkNonLoadingCache();
        return new LocalCache.LocalManualCache<>(this);
    }

    private void checkNonLoadingCache() {
        checkState(refreshNanos == UNSET_INT, "refreshAfterWrite requires a LoadingCache");
    }

    private void checkWeightWithWeigher() {
        if (weigher == null) {
            checkState(maximumWeight == UNSET_INT, "maximumWeight requires weigher");
        } else {
            if (strictParsing) {
                checkState(maximumWeight != UNSET_INT, "weigher requires maximumWeight");
            } else {
                if (maximumWeight == UNSET_INT) {
                    logger.log(Level.WARNING, "ignoring weigher specified without maximumWeight");
                }
            }
        }
    }


    @Override
    public String toString() {
        MoreObjects.ToStringHelper s = MoreObjects.toStringHelper(this);
        if (initialCapacity != UNSET_INT) {
            s.add("initialCapacity", initialCapacity);
        }
        if (concurrencyLevel != UNSET_INT) {
            s.add("concurrencyLevel", concurrencyLevel);
        }
        if (maximumSize != UNSET_INT) {
            s.add("maximumSize", maximumSize);
        }
        if (maximumWeight != UNSET_INT) {
            s.add("maximumWeight", maximumWeight);
        }
        if (expireAfterWriteNanos != UNSET_INT) {
            s.add("expireAfterWrite", expireAfterWriteNanos + "ns");
        }
        if (expireAfterAccessNanos != UNSET_INT) {
            s.add("expireAfterAccess", expireAfterAccessNanos + "ns");
        }
        if (keyStrength != null) {
            s.add("keyStrength", Ascii.toLowerCase(keyStrength.toString()));
        }
        if (valueStrength != null) {
            s.add("valueStrength", Ascii.toLowerCase(valueStrength.toString()));
        }
        if (keyEquivalence != null) {
            s.addValue("keyEquivalence");
        }
        if (valueEquivalence != null) {
            s.addValue("valueEquivalence");
        }
        if (removalListener != null) {
            s.addValue("removalListener");
        }
        return s.toString();
    }
}

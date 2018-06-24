package com.webmvc.util.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.Collection;

public class ReentrantReadWriteLock
        implements ReadWriteLock, java.io.Serializable {
    private static final long serialVersionUID = -6992448646407690164L;

    private final ReentrantReadWriteLock.ReadLock readerLock;

    private final ReentrantReadWriteLock.WriteLock writerLock;

    final Sync sync;

    /**
     * 默认为非公平锁
     */
    public ReentrantReadWriteLock() {
        this(false);
    }


    /**
     * @param fair 是否为公平锁
     */
    public ReentrantReadWriteLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
        readerLock = new ReadLock(this);
        writerLock = new WriteLock(this);
    }

    /**
     * 写锁
     */
    public ReentrantReadWriteLock.WriteLock writeLock() {
    	return writerLock;
    }
    
    /**
     * 读锁
     */
    public ReentrantReadWriteLock.ReadLock  readLock()  {
    	return readerLock; 
    }


    /**
     * 拥有公平和非公平两个子类
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 6317671515068378041L;

        //前16位读锁  后16位写锁
        static final int SHARED_SHIFT   = 16;
        static final int SHARED_UNIT    = (1 << SHARED_SHIFT);
        static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;
        static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;

        /** 读锁的状态  */
        static int sharedCount(int c)    { return c >>> SHARED_SHIFT; }
        /** 写锁的状态  */
        static int exclusiveCount(int c) { return c & EXCLUSIVE_MASK; }

        /**
         * 一个线程获取读锁的次数，使用了ThreadLocal
         */
        static final class HoldCounter {
            int count = 0;
            // Use id, not reference, to avoid garbage retention
            final long tid = getThreadId(Thread.currentThread());
        }

        static final class ThreadLocalHoldCounter
            extends ThreadLocal<HoldCounter> {
            public HoldCounter initialValue() {
                return new HoldCounter();
            }
        }

        //保存每个线程持有的读锁计数
        private transient ThreadLocalHoldCounter readHolds;

        //缓存最新获取共享锁的线程的HoldCounter
        private transient HoldCounter cachedHoldCounter;

        //缓存第一个获取共享锁的线程
        private transient Thread firstReader = null;
        //缓存第一个获取共享锁的线程的重入计数
        private transient int firstReaderHoldCount;

        Sync() {
            readHolds = new ThreadLocalHoldCounter();
            setState(getState());
        }

        
   
        abstract boolean readerShouldBlock();

        
        abstract boolean writerShouldBlock();

        /*
         * 是否释放了锁
         */
        protected final boolean tryRelease(int releases) {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int nextc = getState() - releases;
            boolean free = exclusiveCount(nextc) == 0;
            if (free) {
                setExclusiveOwnerThread(null);
            }
            setState(nextc);
            return free;
        }

        /**
         *  尝试获取写锁
         */
        protected final boolean tryAcquire(int acquires) {

            Thread current = Thread.currentThread();
            int c = getState();
            int w = exclusiveCount(c);
            if (c != 0) {
                // w == 0 而 c != 0 说明存在读锁

                if (w == 0 || current != getExclusiveOwnerThread()) {
                    /*
                     * 如果读的状态或写的状态不为0且拥有锁的线程不为当前线程，获取失败
                     */
                    return false;
                }
                if (w + exclusiveCount(acquires) > MAX_COUNT) {
                    throw new Error("Maximum lock count exceeded");
                }

                // 修改state
                setState(c + acquires);
                return true;
            }
            /*
             * writerShouldBlock() 如果是非公平则直接返回false，
             * 如果为公平锁则判断是否有节点在同步队列中等待
             */
            if (writerShouldBlock() ||
                !compareAndSetState(c, c + acquires)) {
                return false;
            }
            setExclusiveOwnerThread(current);
            return true;
        }

        /**
         * 释放共享锁
         */
        protected final boolean tryReleaseShared(int unused) {
            Thread current = Thread.currentThread();
            if (firstReader == current) {
                // assert firstReaderHoldCount > 0;
                if (firstReaderHoldCount == 1) {
                    firstReader = null;
                } else {
                    firstReaderHoldCount--;
                }
            } else {
                HoldCounter rh = cachedHoldCounter;
                if (rh == null || rh.tid != getThreadId(current))
                    rh = readHolds.get();
                int count = rh.count;
                if (count <= 1) {
                    readHolds.remove();
                    if (count <= 0)
                        throw unmatchedUnlockException();
                }
                --rh.count;
            }
            for (;;) {
                int c = getState();
                int nextc = c - SHARED_UNIT;
                if (compareAndSetState(c, nextc)) {
                    // Releasing the read lock has no effect on readers,
                    // but it may allow waiting writers to proceed if
                    // both read and write locks are now free.
                    return nextc == 0;
                }
            }
        }

        private IllegalMonitorStateException unmatchedUnlockException() {
            return new IllegalMonitorStateException(
                "attempt to unlock read lock, not locked by current thread");
        }

        /*
         * 尝试获取共享锁
         */
        protected final int tryAcquireShared(int unused) {

            Thread current = Thread.currentThread();
            int c = getState();
            if (exclusiveCount(c) != 0 &&
                getExclusiveOwnerThread() != current) {
                //如果存在写锁且不是当前线程占用，则获取锁失败
                return -1;
            }
            int r = sharedCount(c);

            // readerShouldBlock 是否需要阻塞线程
            if (!readerShouldBlock() &&
                r < MAX_COUNT &&
                compareAndSetState(c, c + SHARED_UNIT)) {
                //记录每个线程各自获取读锁的次数
                if (r == 0) {
                	//如果之前读状态是0，设置firstReader第一个读线程为当前线程
                    firstReader = current;
                    firstReaderHoldCount = 1;
                } else if (firstReader == current) {
                	//firstReader第一个读线程为当前线程
                    firstReaderHoldCount++;
                } else {
                	//缓存最后一个获得锁的线程
                    HoldCounter rh = cachedHoldCounter;
                    if (rh == null || rh.tid != getThreadId(current)) {
                        cachedHoldCounter = rh = readHolds.get();
                    } else if (rh.count == 0) {
                        readHolds.set(rh);
                    }
                    rh.count++;
                }
                return 1;
            }
            return fullTryAcquireShared(current);
        }

        /**
         * 完整版的尝试获取共享锁
         */
        final int fullTryAcquireShared(Thread current) {
            /*
             * This code is in part redundant with that in
             * tryAcquireShared but is simpler overall by not
             * complicating tryAcquireShared with interactions between
             * retries and lazily reading hold counts.
             */
            HoldCounter rh = null;
            for (;;) {
                int c = getState();
                if (exclusiveCount(c) != 0) {
                    if (getExclusiveOwnerThread() != current) {
                    	//不是当前线程占有写锁
                        return -1;
                    }
                    // else we hold the exclusive lock; blocking here
                    // would cause deadlock.
                } else if (readerShouldBlock()) {
                    // 确保不是重复获取读锁
                    if (firstReader == current) {
                        
                    } else {
                        if (rh == null) {
                            rh = cachedHoldCounter;
                            if (rh == null || rh.tid != getThreadId(current)) {
                                rh = readHolds.get();
                                if (rh.count == 0) {
                                    readHolds.remove();
                                }
                            }
                        }
                        if (rh.count == 0) {
                            return -1;
                        }
                    }
                }
                if (sharedCount(c) == MAX_COUNT) {
                    throw new Error("Maximum lock count exceeded");
                }
                //前16位表示读锁重入的次数
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                	//修改单个线程的重入次数
                    if (sharedCount(c) == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        if (rh == null) {
                            rh = cachedHoldCounter;
                        }
                        if (rh == null || rh.tid != getThreadId(current)) {
                            rh = readHolds.get();
                        } else if (rh.count == 0) {
                            readHolds.set(rh);
                        }
                        rh.count++;
                        cachedHoldCounter = rh; // cache for release
                    }
                    return 1;
                }
            }
        }

        /**
         * 是否可以获取到写锁
         */
        final boolean tryWriteLock() {
            Thread current = Thread.currentThread();
            int c = getState();
            if (c != 0) {
                int w = exclusiveCount(c);
                if (w == 0 || current != getExclusiveOwnerThread()) {
                    return false;
                }
                if (w == MAX_COUNT) {
                    throw new Error("Maximum lock count exceeded");
                }
            }
            if (!compareAndSetState(c, c + 1)) {
                return false;
            }
            setExclusiveOwnerThread(current);
            return true;
        }

        /**
         * 是否可以获取到读锁
         */
        final boolean tryReadLock() {
            Thread current = Thread.currentThread();
            for (;;) {
                int c = getState();
                if (exclusiveCount(c) != 0 &&
                    getExclusiveOwnerThread() != current) {
                	//独占锁被持有，且不是当前线程持有
                    return false;
                }
                int r = sharedCount(c);
                if (r == MAX_COUNT) {
                	//int溢出
                    throw new Error("Maximum lock count exceeded");
                }
                //自旋设置State
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    if (r == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        HoldCounter rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current)) {
                            cachedHoldCounter = rh = readHolds.get();
                        } else if (rh.count == 0) {
                            readHolds.set(rh);
                        }
                        rh.count++;
                    }
                    return true;
                }
            }
        }

        /**
         * 锁是否被当前线程占有
         */
        protected final boolean isHeldExclusively() {
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        /*
         * 创建一个ConditionObject对象
         */
        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        final Thread getOwner() {
            // Must read state before owner to ensure memory consistency
            return ((exclusiveCount(getState()) == 0) ?
                    null :
                    getExclusiveOwnerThread());
        }

        /**
         * @return 读锁重入的次数，非单个线程
         */
        final int getReadLockCount() {
            return sharedCount(getState());
        }

        /**
         * @return 是否为写锁
         */
        final boolean isWriteLocked() {
            return exclusiveCount(getState()) != 0;
        }

        /**
         * @return 写锁重入的次数
         */
        final int getWriteHoldCount() {
            return isHeldExclusively() ? exclusiveCount(getState()) : 0;
        }

        /**
         * @return 当前线程的读锁重入的次数
         */
        final int getReadHoldCount() {
            if (getReadLockCount() == 0) {
                return 0;
            }

            Thread current = Thread.currentThread();
            if (firstReader == current) {
                return firstReaderHoldCount;
            }

            HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == getThreadId(current)) {
                return rh.count;
            }

            int count = readHolds.get().count;
            if (count == 0) {
            	readHolds.remove();
            }
            return count;
        }

        /**
         * 反序列化
         */
        private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            readHolds = new ThreadLocalHoldCounter();
            setState(0); // reset to unlocked state
        }
        /**
         * @return 同步状态
         */
        final int getCount() { return getState(); }
    }

    /**
     * 不公平版本的Sync
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -8159625535654395037L;
        final boolean writerShouldBlock() {
            return false; 
        }
        final boolean readerShouldBlock() {
        	//如果同步队列中的第二个节点为独占模式，则需要阻塞
            return apparentlyFirstQueuedIsExclusive();
        }
    }

    /**
     * 公平版本的Sync
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -2274990926593161451L;
        final boolean writerShouldBlock() {
            return hasQueuedPredecessors();
        }
        final boolean readerShouldBlock() {
            return hasQueuedPredecessors();
        }
    }

    /**
     * 读锁
     */
    public static class ReadLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -5992448646407690164L;
        private final Sync sync;

        
        protected ReadLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        /**
         * 获取读锁，如果获取不到则会阻塞直到可以获取
         *
         */
        public void lock() {
            sync.acquireShared(1);
        }

        /**
         * 获取读锁，且响应中断
         *
         * @throws InterruptedException 当前线程被中断
         */
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireSharedInterruptibly(1);
        }

        /**
         * @return 是否获取到读锁
         */
        public boolean tryLock() {
            return sync.tryReadLock();
        }

        /**
         * 在一段时间内尝试获取读锁
         *
         * @param timeout 等待获取读锁的时间
         * @param unit 时间的单位
         * @return 是否获取到读锁
         * @throws InterruptedException 当前线程被中断
         * @throws NullPointerException 时间单位为null
         */
        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
        }

        /**
         * 释放锁
         */
        public void unlock() {
            sync.releaseShared(1);
        }

        /**
         * 不支持
         */
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }


        public String toString() {
            int r = sync.getReadLockCount();
            return super.toString() +
                "[Read locks = " + r + "]";
        }
    }

    /**
     * 和重入锁基本一致
     */
    public static class WriteLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -4992448646407690164L;
        private final Sync sync;

        protected WriteLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        /**
         * 获取写锁
         */
        public void lock() {
            sync.acquire(1);
        }

        /**
         * 独占模式获取锁
         * 直到获取到锁或者线程被中断
         */
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireInterruptibly(1);
        }

        /**
         * 是否获取到写锁
         */
        public boolean tryLock( ) {
            return sync.tryWriteLock();
        }

        /**
         * 尝试在一段时间内获取锁
         *
         * @param arg the acquire argument.  
         * @param nanosTimeout 最长等待多少纳秒
         * @return 规定时间内是否获取到锁
         */
        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireNanos(1, unit.toNanos(timeout));
        }

        /**
         * 释放锁
         */
        public void unlock() {
            sync.release(1);
        }

        /**
         * @return Condition对象
         */
        public Condition newCondition() {
            return sync.newCondition();
        }

        
        public String toString() {
            Thread o = sync.getOwner();
            return super.toString() + ((o == null) ?
                                       "[Unlocked]" :
                                       "[Locked by thread " + o.getName() + "]");
        }

        /**
         * @return 锁是否被当前线程占有
         */
        public boolean isHeldByCurrentThread() {
            return sync.isHeldExclusively();
        }

        /**
         * @return 写锁被当前线程重入的次数，如果为0，则写锁不被当前线程占有
         */
        public int getHoldCount() {
            return sync.getWriteHoldCount();
        }
    }

    // Instrumentation and status

    /**
     * @return 是否为公平锁
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * @return 当前占有写锁的线程
     */
    protected Thread getOwner() {
        return sync.getOwner();
    }

    /**
     * 当前读锁被获取的次数，同一线程连续获取也计数
     */
    public int getReadLockCount() {
        return sync.getReadLockCount();
    }

    /**
     * 写锁是否被获取
     */
    public boolean isWriteLocked() {
        return sync.isWriteLocked();
    }

    /**
     * @return 写锁是否被当前线程占有
     */
    public boolean isWriteLockedByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * 当前写锁被获取的次数
     */
    public int getWriteHoldCount() {
        return sync.getWriteHoldCount();
    }

    /**
     * 当前线程获取读锁的次数
     */
    public int getReadHoldCount() {
        return sync.getReadHoldCount();
    }

    /**
     * 获取队列中独占模式的线程集合
     * @return 独占模式的线程集合
     */
    protected Collection<Thread> getQueuedWriterThreads() {
        return sync.getExclusiveQueuedThreads();
    }

    /**
     * @return 同步队列中是否有线程在等待锁
     */
    protected Collection<Thread> getQueuedReaderThreads() {
        return sync.getSharedQueuedThreads();
    }

    /**
     * @return 同步队列中是否有线程在等待锁
     */
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
	 * 线程是否在同步队列中
     * @return 参数中的线程是否在队列中
     * @throws NullPointerException 如果线程为null
     */
    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    /**
     * @return 同步队列中大概的线程数量
     */
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    /**
     * 获取同步队列中线程的集合
     * @return 线程的集合
     */
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    /**
     * @param condition 对象
     * @return 是否有线程在condition的等待队列中
     */
    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * @param condition对象
     * @return 等待队列中大概的线程数量
     */
    public int getWaitQueueLength(Condition condition) {
        if (condition == null) {
            throw new NullPointerException();
        }
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject)) {
            throw new IllegalArgumentException("not owner");
        }
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * 获取condition对象等待的线程集合
     * @return 线程的集合
     */
    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null) {
            throw new NullPointerException();
        }
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject)) {
            throw new IllegalArgumentException("not owner");
        }
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject)condition);
    }


    public String toString() {
        int c = sync.getCount();
        int w = Sync.exclusiveCount(c);
        int r = Sync.sharedCount(c);

        return super.toString() +
            "[Write locks = " + w + ", Read locks = " + r + "]";
    }

    /**
     * 获取线程的id
     */
    static final long getThreadId(Thread thread) {
        return UNSAFE.getLongVolatile(thread, TID_OFFSET);
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long TID_OFFSET;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> tk = Thread.class;
            TID_OFFSET = UNSAFE.objectFieldOffset
                (tk.getDeclaredField("tid"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}

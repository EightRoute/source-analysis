package com.webmvc.util.locks;


import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.Collection;

public class ReentrantLock implements Lock, java.io.Serializable {
    private static final long serialVersionUID = 7373984872572414699L;
    /** Synchronizer providing all implementation mechanics */
    private final Sync sync;

    /**
     * 使用AQS的state表示锁被重入的次数
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = -5179523762034025860L;

        /**
         * nonfair更快
         * 公平锁不能直接尝试获取锁而要判断队列中是否有等待的线程
         */
        abstract void lock();

        /*
         * 不公平的获取锁，相比公平锁少了个判断是否等待队列中有元素在等待
         */
        final boolean nonfairTryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {
                if (compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            } else if (current == getExclusiveOwnerThread()) {
            	//如果锁是被当前占有，加1
                int nextc = c + acquires;
                if (nextc < 0) {
                	//int溢出
                    throw new Error("Maximum lock count exceeded");
                }
                setState(nextc);
                return true;
            }
            //没有获取到锁
            return false;
        }

        /*
         * 释放锁
         */
        protected final boolean tryRelease(int releases) {
            int c = getState() - releases;
            if (Thread.currentThread() != getExclusiveOwnerThread()) {
                //锁不由当前线程占有
            	throw new IllegalMonitorStateException();
            }
            boolean free = false;
            if (c == 0) {
                free = true;
                setExclusiveOwnerThread(null);
            }
            setState(c);
            return free;
        }

        /*
         * 当前线程是否占有锁
         */
        protected final boolean isHeldExclusively() {
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        /*
         * 创建一个ConditionObject实例
         */
        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        // Methods relayed from outer class

        /*
         * 获取占有锁的线程
         */
        final Thread getOwner() {
            return getState() == 0 ? null : getExclusiveOwnerThread();
        }

        /*
         * 获取锁被重入的次数
         */
        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }

        /*
         * 锁是否被占有
         */
        final boolean isLocked() {
            return getState() != 0;
        }

        /*
         * 反序列化
         */
        private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            setState(0); // reset to unlocked state
        }
    }

    /**
     * 非公平锁的Sync对象
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = 7316153563782823691L;

        /**
         * 加锁
         */
        final void lock() {
            if (compareAndSetState(0, 1)) {
            	//如果cas成功则设置当前线程为独占线程
                setExclusiveOwnerThread(Thread.currentThread());
            } else {
                acquire(1);
            }
        }

        /**
         * 尝试获取锁
         */
        protected final boolean tryAcquire(int acquires) {
            return nonfairTryAcquire(acquires);
        }
    }

    /**
     * 公平锁的Sync对象
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -3000897897090466540L;

        final void lock() {
            acquire(1);
        }

        /**
         * 公平获取锁
         */
        protected final boolean tryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {
            	//相比非公平锁多了个!hasQueuedPredecessors()
                //如果有则不能获取锁来保证公平
                if (!hasQueuedPredecessors() &&
                    compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            } else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0) {
                	//超出int最大值
                    throw new Error("Maximum lock count exceeded");
                }
                setState(nextc);
                return true;
            }
            return false;
        }
    }

    /**
     * 默认为非公平锁
     */
    public ReentrantLock() {
        sync = new NonfairSync();
    }

    /**
     * @param fair 是否为公平锁
     */
    public ReentrantLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
    }

    /**
     * 获取锁
     *
     * 如果没有被其他线程占用则获取锁并立即返回，设置占有锁的数量为1
     * 如果锁已被当前线程占用则立即返回并将占有锁的数量加1
     * 如果锁被其他线程占有，则当前线程被阻塞直到获取到锁
     */
    public void lock() {
        sync.lock();
    }

    /**
     * 响应中断的获取锁 
     * interrupted会唤醒线程
     * @throws InterruptedException 如果当前线程被interrupted
     */
    public void lockInterruptibly() throws InterruptedException {
        sync.acquireInterruptibly(1);
    }

    /**
     * 尝试获取锁
     * @return 是否获取到锁
     */
    public boolean tryLock() {
        return sync.nonfairTryAcquire(1);
    }

    /**
     * 限时获取锁
     *
     * @param timeout 等待锁的时间lock
     * @param unit 时间的单位
     * @return 如果超过上时间都没有获取到锁则返回false
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

    /**
     * @return 锁被重入的次数
     */
    public int getHoldCount() {
        return sync.getHoldCount();
    }

    /**
     * @return 锁是否被当前线程占有
     */
    public boolean isHeldByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * @return 锁是否被占有
     */
    public boolean isLocked() {
        return sync.isLocked();
    }

    /**
     * @return 石佛与为公平锁
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * @return 占有锁的线程
     */
    protected Thread getOwner() {
        return sync.getOwner();
    }

    /**
     * @return 是否有其他线程在等待锁
     */
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * 该线程是否在队列中等待锁
     *
     * @param thread 被判断的thread
     * @return 该线程是否在队列中等待锁
     */
    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    /**
     * @return 在等待锁的线程数量
     */
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    /**
     * 预估在队列上等待的线程集合
     *
     * @return 线程的集合
     */
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    /**
     * 是否有在condition对象等待的线程
     *
     * @param condition condition对象
     * @return 是否有在condition对象等待的线程
     */
    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * 获取在一个condition对象上预计的等待线程的数量
     * @param condition condition对象
     * @return 预计的等待线程的数量
     */
    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * 获取在一个condition对象等待的线程集合
     * @param condition condition对象
     * @return 线程集合
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
        Thread o = sync.getOwner();
        return super.toString() + ((o == null) ?
                                   "[Unlocked]" :
                                   "[Locked by thread " + o.getName() + "]");
    }
}

package com.webmvc.util.locks;


import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import sun.misc.Unsafe;


public abstract class AbstractQueuedSynchronizer
    extends AbstractOwnableSynchronizer
    implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * 创建一个新的实例，state为0
     */
    protected AbstractQueuedSynchronizer() { }

    /**
     * 同步队列和等待队列共用的节点类
     */
    static final class Node {
        /** 表示节点正处在共享模式下等待的标记 */
        static final Node SHARED = new Node();
        /** 表示节点正在以独占模式等待的标记 */
        static final Node EXCLUSIVE = null;

        /** 线程已取消 因为超时或者中断，Node被设置为取消状态，
         * 被取消的Node不应该去竞争锁，只能保持取消状态不变，
         * 不能转换为其他状态，处于这种状态的Node会被踢出队列，被GC回收 */
        static final int CANCELLED =  1;
        /** 表示这个Node的后继Node被阻塞了，到时需要通知它*/
        static final int SIGNAL    = -1;
        /** 表示这个Node在条件队列中，因为等待某个条件而被阻塞 */
        static final int CONDITION = -2;
        /**
         * 使用在共享模式头Node有可能处于这种状态， 表示当前场景下后续的acquireShared能够得以执行
         *
         * PROPAGATE的引入是为了解决共享锁并发释放导致的线程hang住问题
         */
        static final int PROPAGATE = -3;

        /**
         * 状态字段，仅接受值:
         * 
         * SIGNAL:值为-1 
         *     
         * CANCELLED:值为1
         *
         * CONDITION: 值为-2
         *
         * PROPAGATE: 值为-3
         * 
         * INITIAL: 初始状态值为0 
         */
        volatile int waitStatus;

        /**
         * 队列中某个Node的前驱Node
         */
        volatile Node prev;

        /**
         * 队列中某个Node的后继Node
         */
        volatile Node next;

        /**
         * 这个Node持有的线程，表示等待锁的线程
         */
        volatile Thread thread;

        /**
         * 表示下一个等待condition的Node
         */
        Node nextWaiter;

        /**
         * 是否为SHARED.
         */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * @return 上一个node
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null) {
            	// The null check could be elided, but is present to help the VM
                throw new NullPointerException();
            } else {
                return p;
            } 
        }

        Node() {    // Used to establish initial head or SHARED marker
        }

        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        Node(Thread thread, int waitStatus) { // Used by Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * 同步队列头结点 它不与任何线程关联。其他的节点与等待线程关联，每个节点维护一个等待状态waitStatus
     */
    private transient volatile Node head;

    /**
     * 同步队列尾结点
     */
    private transient volatile Node tail;

    /**
     * 同步状态
     * 0表示未锁，大于0表示重入的次数
     * 读写锁中前16位为读锁的状态
     * 后16位写锁的状态
     */
    private volatile int state;

    /**
     * @return 目前同步状态
     */
    protected final int getState() {
        return state;
    }

    /**
     * 设置同步状态
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * cas操作state
     */
    protected final boolean compareAndSetState(int expect, int update) {
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // Queuing utilities

    /**
     * 线程自旋等待的时间，单位为纳秒,小于这个时间不会挂起线程
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * 将节点插入队列
     * @param node 要被插入的node
     * @return node的前驱node
     */
    private Node enq(final Node node) {
    	//自旋
        for (;;) {
            Node t = tail;
            if (t == null) { 
            	//队列为空，必须初始化，将一个SHARED模式的插入头结点
                if (compareAndSetHead(new Node())) {
                    tail = head;
                }
            } else {
                node.prev = t;
                if (compareAndSetTail(t, node)) {
                    t.next = node;
                    return t;
                }
            }
        }
    }

    /**
     * 根据mode创建一个节点并插入到同步队列的尾部
     * @param mode Node.EXCLUSIVE 独占模式, Node.SHARED 共享模式
     * @return 新的节点
     */
    private Node addWaiter(Node mode) {
        Node node = new Node(Thread.currentThread(), mode);
        // 尝试一次快速入列，直接试一次原子设置tail
        Node pred = tail;
        if (pred != null) {
            node.prev = pred;
            if (compareAndSetTail(pred, node)) {
                pred.next = node;
                return node;
            }
        }
        enq(node);
        return node;
    }

    /**
     * 设置队列的头Node
     */
    private void setHead(Node node) {
        head = node;
        node.thread = null;
        node.prev = null;
    }

    /**
     * 唤起后继Node持有的线程
     */
    private void unparkSuccessor(Node node) {
        int ws = node.waitStatus;
        if (ws < 0) {
            compareAndSetWaitStatus(node, ws, 0);
        }
        Node s = node.next;
        if (s == null || s.waitStatus > 0) {
        	//等于空或被取消时
            s = null;
            //从尾部开始遍历寻找一个没有被取消的后继节点，但只有前驱节点是首节点才可以尝试获取锁
            for (Node t = tail; t != null && t != node; t = t.prev) {
                if (t.waitStatus <= 0) {
                    s = t;
                }
            }
        }
        if (s != null) {
        	//唤醒线程
            LockSupport.unpark(s.thread);
        }
    }

    /**
     * 共享模式下释放锁的动作
     * 共享锁中的核心唤醒函数
     * 后继线程被唤醒后，会尝试获取共享锁，如果成功之后，则又会调用setHeadAndPropagate,将唤醒传播下去
     */
    private void doReleaseShared() {
        for (;;) {
            Node h = head;
            if (h != null && h != tail) {
                // 获取头节点对应的线程的状态
                int ws = h.waitStatus;
                // 如果头节点对应的线程是SIGNAL状态，则意味着“头节点的下一个节点所对应的线程”需要被unpark唤醒。
                if (ws == Node.SIGNAL) {
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0)) {
                        continue;            // loop to recheck cases
                    }
                    //唤醒后继节点
                    unparkSuccessor(h);
                } else if (ws == 0 && !compareAndSetWaitStatus(h, 0, Node.PROPAGATE)) {
                    continue;                // loop on failed CAS
                }
            }
            // 如果头节点发生变化，则继续循环。否则，退出循环。
            if (h == head) {
                break;
            }
        }
    }

    /**
     * 先把当前节点设为为头节点，调用doReleaseShared方法唤醒线程
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // Record old head for check below
        setHead(node);
       
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
            (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            if (s == null || s.isShared()) {
                doReleaseShared();
            }
        }
    }

    // Utilities for various versions of acquire

    /**
     * 取消正在进行的Node获取锁的尝试
     */
    private void cancelAcquire(Node node) {
        if (node == null) {
            return;
        }

        node.thread = null;

        // 跳过被取消的前驱节点
        Node pred = node.prev;
        while (pred.waitStatus > 0) {
            node.prev = pred = pred.prev;
        }

        // 留着CAS用
        Node predNext = pred.next;

        // 将node的waitStatus改为CANCELLED
        node.waitStatus = Node.CANCELLED;

        // 如果node是尾节点，则移除自己
        if (node == tail && compareAndSetTail(node, pred)) {
        	//将前驱节点的下一个节点设为null
            compareAndSetNext(pred, predNext, null);
        } else {
            // 如果前驱节点为首节点啦就要唤醒后继线程
            int ws;
            if (pred != head &&
                ((ws = pred.waitStatus) == Node.SIGNAL ||
                 (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                pred.thread != null) {
                Node next = node.next;
                if (next != null && next.waitStatus <= 0) {
                	//将前驱节点的下一个节点设为next节点
                    compareAndSetNext(pred, predNext, next);
                }
            } else {
                unparkSuccessor(node);
            }

            node.next = node; // help GC
        }
    }

    /**
     * 如果线程需要被阻塞则返回true
     * @param pred node's predecessor holding status
     * @param node the node
     * @return 如果线程需要被阻塞则返回true
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
    	//前驱节点的状态 
    	int ws = pred.waitStatus;
        if (ws == Node.SIGNAL) {
            /*
             * 前驱节点状态为signal,返回true 
             */
            return true;
        }
        // ws > 0，前驱节点状态为CANCELLED 
        if (ws > 0) {
            /*
             * 从队尾向前寻找第一个状态不为CANCELLED的节点 
             */
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node;
        } else {
            /*
             * 将前驱节点的状态设置为SIGNAL  
             */
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    /**
     * 中断当前线程
     */
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * 阻塞当前线程和检查
     * @return 当前线程是否被中断
     */
    private final boolean parkAndCheckInterrupt() {
    	//阻塞当前线程
        LockSupport.park(this);
        return Thread.interrupted();
    }



    /**
     * 不响应中断式的获取锁
     * 
     * @param node the node
     * @param arg the acquire argument
     * @return waiting是否被中断
     */
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
        	//标记线程是否被中断过
            boolean interrupted = false;
            //自旋， 直到获取到同步状态，但只有前驱节点是首节点才可以尝试获取锁
            for (;;) {
                final Node p = node.predecessor();
                // 如果前驱节点为head则当前节点去尝试获取锁
                if (p == head && tryAcquire(arg)) {
                	//成功获取锁会将自己设为头节点
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return interrupted;
                }
                // 判断获取失败后是否可以挂起,若可以则挂起
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt()) {
                	//parkAndCheckInterrupt方法使得当前线程disabled
                    interrupted = true;
                }
            }
        } finally {
            if (failed) {
            	//如果已经获取到锁
                cancelAcquire(node);
            }
        }
    }

    /**
     * 响应中断式的获取锁
     */
    private void doAcquireInterruptibly(int arg)
        throws InterruptedException {
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt()) {
                	//上面的acquireQueued仅仅是 interrupted = true，没有抛出异常
                    throw new InterruptedException();
                }
            }
        } finally {
            if (failed) {
                cancelAcquire(node);
            }
        }
    }

    /**
     * 尝试在一段时间内获取锁
     * @param arg 获取参数
     * @param nanosTimeout 最长等待时间
     * @return 是否获取锁
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L) {
            return false;
        }
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L) {
                    return false;
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                    nanosTimeout > spinForTimeoutThreshold) {
                    LockSupport.parkNanos(this, nanosTimeout);
                }
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        } finally {
            if (failed) {
                cancelAcquire(node);
            }
        }
    }

    /**
     * 共享模式下获取锁，不管中断
     */
    private void doAcquireShared(int arg) {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                //退出自旋的条件为tryAcquireShared(arg)>0
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                    	//如果成功获取锁，就设置head并传播
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        if (interrupted) {
                            selfInterrupt();
                        }
                        failed = false;
                        return;
                    }
                }
                //获取不到锁会阻塞在此，且在阻塞前会将前驱节点的状态设置为SIGNAL 
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt()) {
                    interrupted = true;
                }
            }
        } finally {
            if (failed) {
                cancelAcquire(node); 
            }
        }
    }

    /**
     * 获取共享锁，响应中断
     */
    private void doAcquireSharedInterruptibly(int arg)
        throws InterruptedException {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt()) {
                    throw new InterruptedException();
                }
            }
        } finally {
            if (failed) {
                cancelAcquire(node);
            } 
        }
    }

    /**
     * 超时获取共享锁
     *
     * @param 最长的等待时间， 单位为纳秒
     * @return 是否获取到锁
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L) {
            return false;
        }
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L) {
                    return false;
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                    nanosTimeout > spinForTimeoutThreshold) {
                    LockSupport.parkNanos(this, nanosTimeout);
                }
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
        } finally {
            if (failed) {
                cancelAcquire(node);
            }
        }
    }

    // Main exported methods

    /**
     * 独占获取
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 独占释放
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 共享获取
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 共享释放
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 是否占有锁
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * 独占模式获取锁，不管interrupts
     */
    public final void acquire(int arg) {
    	// tryAcquire(arg)获取锁
    	// acquireQueued(addWaiter(Node.EXCLUSIVE), arg)
        if (!tryAcquire(arg) && acquireQueued(addWaiter(Node.EXCLUSIVE), arg)) {
            selfInterrupt();
        }
    }

    /**
     * 独占模式获取锁
     * 直到获取到锁或者线程被中断
     */
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        //尝试获取锁
        if (!tryAcquire(arg)) {
        	//没有获取到锁
            doAcquireInterruptibly(arg);
        }
    }

    /**
     * 尝试在一段时间内获取锁
     *
     * @param arg the acquire argument.  
     * @param nanosTimeout 最长等待多少纳秒
     * @return 规定时间内是否获取到锁
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return tryAcquire(arg) || doAcquireNanos(arg, nanosTimeout);
    }

    /**
     * 释放
     */
    public final boolean release(int arg) {
        if (tryRelease(arg)) {
            Node h = head;
            if (h != null && h.waitStatus != 0) {
            	//唤醒后继节点
                unparkSuccessor(h);
            }
            return true;
        }
        return false;
    }

    /**
     * 共享模式下获取锁
     * 共享式访问资源时，其他共享式访问均被允许
     */
    public final void acquireShared(int arg) {
    	/*tryAcquireShared
    	 * 返回1为获取锁成功
    	 * 返回-1为获取失败
    	 */
        if (tryAcquireShared(arg) < 0) {
            doAcquireShared(arg);
        }
    }

    /**
     * 获取共享锁，响应中断
     * @throws InterruptedException 当前线程被中断
     */
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (tryAcquireShared(arg) < 0) {
            doAcquireSharedInterruptibly(arg);
        }
    }

    /**
     * 超时获取共享锁
     *
     * @param nanosTimeout 最长的等待时间，单位为纳秒
     * @return true代表获取到锁 false代表超时
     * @throws InterruptedException 当前线程被中断
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquireShared(arg) >= 0 ||
            doAcquireSharedNanos(arg, nanosTimeout);
    }

    /**
     * 共享模式释放锁
     */
    public final boolean releaseShared(int arg) {
        if (tryReleaseShared(arg)) {
            doReleaseShared();
            return true;
        }
        return false;
    }

    // Queue inspection methods

    /**
     * @return 同步队列中是否有线程在等待锁
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * @return 等待队列中是否有node
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * @return 返回在等待队列中等待时间最长的线程，如果没有返回null
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }


    private Thread fullGetFirstQueuedThread() {

        Node h, s;
        Thread st;
        if (((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null) ||
            ((h = head) != null && (s = h.next) != null &&
             s.prev == head && (st = s.thread) != null)) {
            return st;
        }

        /*
         * 验证一遍
         */
        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
    }

    /**
	 * 线程是否在同步队列中
     * @return 参数中的线程是否在队列中
     * @throws NullPointerException 如果线程为null
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.thread == thread) {
                return true;
            }
        return false;
    }

    /**
     * 同步队列中的第二个节点是否为独占模式
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null &&
            (s = h.next)  != null &&
            !s.isShared()         &&
            s.thread != null;
    }

    /**
     * 是否有前驱节点
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = tail; 
        Node h = head;
        Node s;
        return h != t &&
            ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    // Instrumentation and monitoring methods

    /**
     * @return 同步队列中大概的线程数量
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null) {
                ++n;
            }
        }
        return n;
    }

    /**
     * 获取同步队列中线程的集合
     * @return 线程的集合
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null) {
                list.add(t);
            }
        }
        return list;
    }

    /**
     * 获取队列中独占模式的线程集合
     * @return 独占模式的线程集合
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null) {
                    list.add(t);
                }
            }
        }
        return list;
    }

    /**
     * 获取队列中共享模式的线程集合
     * @return 共享模式的线程集合
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null) {
                    list.add(t);
                }
            }
        }
        return list;
    }


    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
            "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions

    /**
     * @return 是否在同步队列中
     */
    final boolean isOnSyncQueue(Node node) {
        if (node.waitStatus == Node.CONDITION || node.prev == null) {
            return false;
        }
        //如果有后继，那一定在同步队列中
        if (node.next != null) 
            return true;
        /*
         * 前驱节点一定不能为null，但CAS可能失败，所以需要确认一遍
         */
        return findNodeFromTail(node);
    }

    /**
     * 从尾部开始查找node是否在同步队列中
     * 只被isOnSyncQueue调用.
     * @return 是否在同步队列中
     */
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (;;) {
            if (t == node) {
                return true;
            }
            if (t == null) {
                return false;
            }
            t = t.prev;
        }
    }

    /**
     * 将节点从等待对列移入同步队列
     * @param node 要被移动的node
     * @return 是否成功将节点从等待对列移入同步队列，如果节点在signal前就被取消会返回false
     */
    final boolean transferForSignal(Node node) {
        /*
         * 如果不能改变状态，这个node已经被取消
         */
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            return false;
        }

        Node p = enq(node);
        int ws = p.waitStatus;
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL)) {
        	/* 如果node在同步对列的前驱不为SIGNAL，
        	 * 那么将唤醒线程清除前面被取消的node,
        	 * 如果清除完后node的前驱节点为首节点，
        	 * 将尝试获取锁，否则线程会再次挂起
        	 */
            LockSupport.unpark(node.thread);
        }
        return true;
    }

    /**
     * 当中断发生时，是否有signal发生
     * 
     * signal会将CONDITION变为0
     * @param node the node
     * @return 当中断发生时，没有signal发生返回true
     */
    final boolean transferAfterCancelledWait(Node node) {
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            enq(node);
            return true;
        }
        /*
         * If we lost out to a signal(), then we can't proceed
         * until it finishes its enq().  Cancelling during an
         * incomplete transfer is both rare and transient, so just
         * spin.
         */
        while (!isOnSyncQueue(node)) {
            Thread.yield();
        }
        return false;
    }

    /**
     * 释放所有的锁
     * @param node 将要开始wait的node
     * @return 释放的锁的重入的次数
     */
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            int savedState = getState();
            if (release(savedState)) {
                failed = false;
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed) {
                node.waitStatus = Node.CANCELLED;
            }
        }
    }

    // Instrumentation methods for conditions

    /**
     * condition对象是否是当前AQS对象创建的
     * @throws NullPointerException condition为null
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * @param condition对象
     * @return 是否有线程在condition的等待队列中
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition)) {
            throw new IllegalArgumentException("Not owner");
        }
        return condition.hasWaiters();
    }

    /**
     * @param condition对象
     * @return 等待队列中大概的线程数量
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition)) {
            throw new IllegalArgumentException("Not owner");
        }
        return condition.getWaitQueueLength();
    }

    /**
     * 获取condition对象等待的线程集合
     * @param condition对象
     * @return 线程的集合
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    public class ConditionObject implements Condition, java.io.Serializable {

		private static final long serialVersionUID = -4633839847275796801L;
		/** 等待队列首节点. */
        private transient Node firstWaiter;
        /** 等待队列尾节点. */
        private transient Node lastWaiter;

        /**
         * 创建一个新的ConditionObject实例
         */
        public ConditionObject() { }

        // Internal methods

        /**
         * 创建一个新的等待节点并加入到队列中
         * @return 新创建的等待节点
         */
        private Node addConditionWaiter() {
            Node t = lastWaiter;
            //如果最后一个等待节点被取消，清理取消的节点
            if (t != null && t.waitStatus != Node.CONDITION) {
                unlinkCancelledWaiters();
                t = lastWaiter;
            }
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            if (t == null) {
                firstWaiter = node;
            } else {
                t.nextWaiter = node;
            }
            lastWaiter = node;
            return node;
        }

        /**
         * 将一个没有被取消的node移动到同步队列，并将其在等待队列上删除，
         * 如果node被取消则找其下一个
         * @param first (不为空的)同步队列的首节点
         */
        private void doSignal(Node first) {
            do {
                if ( (firstWaiter = first.nextWaiter) == null) {
                    lastWaiter = null;
                }
                first.nextWaiter = null;
            } while (!transferForSignal(first) &&
                     (first = firstWaiter) != null);
        }

        /**
         * 删除和移动所有的节点到同步队列中
         * @param 等待队列的首节点
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
            } while (first != null);
        }

        /**
         * 需先获取锁
         * 去除等待队列中被取消的节点
         */
        private void unlinkCancelledWaiters() {
            Node t = firstWaiter;
            Node trail = null;
            while (t != null) {
                Node next = t.nextWaiter;
                if (t.waitStatus != Node.CONDITION) {
                    t.nextWaiter = null;
                    if (trail == null) {
                        firstWaiter = next;
                    } else {
                        trail.nextWaiter = next;
                    }
                    if (next == null) {
                        lastWaiter = trail;
                    }
                } else {
                    trail = t;
                }
                t = next;
            }
        }

        // public methods

        /**
         * 需要获取到锁
         * 将在condition对象的等待队列中等待最长的线程移动到同步队列
         */
        public final void signal() {
            if (!isHeldExclusively()) {
            	//如果没有获取到锁
                throw new IllegalMonitorStateException();
            }
            Node first = firstWaiter;
            if (first != null) {
                doSignal(first);
            }
        }

        /**
         * 需要获取到锁
         * 将所有线程从等待队列移动到同步队列
         *
         * @throws IllegalMonitorStateException 如果没有获取到锁
         */
        public final void signalAll() {
            if (!isHeldExclusively()) {
                throw new IllegalMonitorStateException();
            }
            Node first = firstWaiter;
            if (first != null) {
                doSignalAll(first);
            }
        }

        /**
         * 不响应中断
         * 阻塞线程直到被signalled
         */
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean interrupted = false;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if (Thread.interrupted())
                    interrupted = true;
            }
            if (acquireQueued(node, savedState) || interrupted)
                selfInterrupt();
        }

        /*
         * 如果中断发生在同步队列需要selfInterrupt补一个Interrupt
         * 如果中断发生在等待队列需要抛出InterruptedException
         */
        /** 发生了中断，但在后续我们不抛出中断异常 */
        private static final int REINTERRUPT =  1;
        /** 代表发生了中断，且在后续我们需要抛出中断异常 */
        private static final int THROW_IE    = -1;

        /**
         * 在Waiting时是否被中断过
         *  返回
         *  0 : 没有被中断 
         * -1 : signalled前被中断
         *  1 : signalled后被中断
         * 
         * 如果中断发生在同步队列需要selfInterrupt补一个Interrupt
         * 如果中断发生在等待队列需要抛出InterruptedException
         */
        private int checkInterruptWhileWaiting(Node node) {
            return Thread.interrupted() ?
                (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                0;
        }

        /**
         * 根据interruptMode抛出异常或者interrupt当前线程
         */
        private void reportInterruptAfterWait(int interruptMode)
            throws InterruptedException {
            if (interruptMode == THROW_IE) {
            	//signalled前被中断
                throw new InterruptedException();
            } else if (interruptMode == REINTERRUPT) {
            	//signalled后被中断
                selfInterrupt();
            }
        }

        /**
         *  阻塞线程直到被signalled或者interrupted
         */
        public final void await() throws InterruptedException {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            //创建一个新的等待节点并加入到队列中
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                /*
                 *  0 : 没有被中断
                 *  1 : REINTERRUPT signalled后被中断
                 * -1 : THROW_IE signalled前被中断
                 */
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) {
                    break;
                }
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }
            if (node.nextWaiter != null) {
            	//清理等待队列被取消的节点
                unlinkCancelledWaiters();
            }
            if (interruptMode != 0) {
                reportInterruptAfterWait(interruptMode);
            }
        }

        /**
         * 阻塞线程直到被signalled，interrupted，或者timed out.
         */
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold) {
                    LockSupport.parkNanos(this, nanosTimeout);
                }
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) {
                    break;
                }
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }
            if (node.nextWaiter != null) {
                unlinkCancelledWaiters();
            }
            if (interruptMode != 0) {
                reportInterruptAfterWait(interruptMode); 
            }
            return deadline - System.nanoTime();
        }

        /**
         * 阻塞线程直到被signalled，interrupted，或者到达deadline
         */
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) {
                    break;
                }
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }
            if (node.nextWaiter != null) {
                unlinkCancelledWaiters();
            }
            if (interruptMode != 0) {
                reportInterruptAfterWait(interruptMode);
            }
            return !timedout;
        }

        /**
         * 阻塞线程直到被signalled，interrupted，或者到达deadline
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold) {
                    LockSupport.parkNanos(this, nanosTimeout);
                }
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) {
                    break;
                }
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE) {
                interruptMode = REINTERRUPT;
            }
            if (node.nextWaiter != null) {
                unlinkCancelledWaiters();
            }
            if (interruptMode != 0) {
                reportInterruptAfterWait(interruptMode);
            }
            return !timedout;
        }

        //  support for instrumentation

        /**
         * @return 当前condition是否为参数中的对象创建的
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * @return 是否有在condition上等待的线程
         */
        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }

        /**
         * @return 预计的等待线程的数量
         */
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }

        /**
         * 返回可能等待在Condition上的线程集合
         * @return 线程的集合
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }


    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    
    //获取对象内存中的位置
    static {
        try {
            stateOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                (Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * CAS设置头节点. 只被 enq 使用.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS设置尾节点. 只被 enq 使用.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS设置waitStatus
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                                        expect, update);
    }

    /**
     * CAS设置next节点 
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}


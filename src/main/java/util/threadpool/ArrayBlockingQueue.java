package com.webmvc.util.threadpool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.lang.ref.WeakReference;
import java.util.Spliterators;
import java.util.Spliterator;

/**
 * 1、基于数组的先进先出队列
 * 2、数组的容量不可变
 * 3、可设置ReentrantLock为公平或非公平
 * 4、可迭代
 */
public class ArrayBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, java.io.Serializable {

    private static final long serialVersionUID = -817911632652898426L;

    /** 队列中元素 */
    final Object[] items;

    /** 取出元素的下标 */
    int takeIndex;

    /** 插入元素的下标 */
    int putIndex;

    /** 队列中元素的数量 */
    int count;


    /** 主锁 */
    final ReentrantLock lock;

    /** 等待取元素的Condition对象 */
    private final Condition notEmpty;

    /** 等待插入元素的Condition对象 */
    private final Condition notFull;

    /**
     * Shared state for currently active iterators, or null if there
     * are known not to be any.  Allows queue operations to update
     * iterator state.
     */
    transient Itrs itrs = null;

    /**
     * 循环减少i
     */
    final int dec(int i) {
        return ((i == 0) ? items.length : i) - 1;
    }

    /**
     * 返回下标为i的元素
     */
    @SuppressWarnings("unchecked")
    final E itemAt(int i) {
        return (E) items[i];
    }

    /**
     * 如果参数为null，则抛出NullPointerException
     */
    private static void checkNotNull(Object v) {
        if (v == null) {
            throw new NullPointerException();
        }
    }

    /**
     * 插入元素到putIndex
     * 只有获取锁时才能调用
     */
    private void enqueue(E x) {
        final Object[] items = this.items;
        items[putIndex] = x;
        if (++putIndex == items.length) {
            putIndex = 0;
        }
        count++;
        //唤醒等待取元素的线程
        notEmpty.signal();
    }

    /**
     * 出队，只有获取锁时才能调用
     */
    private E dequeue() {
        final Object[] items = this.items;
        @SuppressWarnings("unchecked")
        E x = (E) items[takeIndex];
        items[takeIndex] = null;
        if (++takeIndex == items.length) {
            takeIndex = 0;
        }
        count--;
        if (itrs != null) {
            itrs.elementDequeued();
        }
        //唤醒等待位置的线程
        notFull.signal();
        return x;
    }

    /**
     * 删除数组下标为removeIndex的元素
     * 只有获取锁时才能调用
     */
    void removeAt(final int removeIndex) {

        final Object[] items = this.items;
        //当移除的元素正好是队列首元素，就是take元素，正常的类似出队列的操作，  
        if (removeIndex == takeIndex) {
            // removing front item; just advance
            items[takeIndex] = null;
            if (++takeIndex == items.length) {
                takeIndex = 0;
            }
            count--;
            if (itrs != null) {
                itrs.elementDequeued();
            }
        } else {
        	//因为是队列中间的值被移除了，所有后面的元素都要挨个迁移  
            final int putIndex = this.putIndex;
            for (int i = removeIndex;;) {
                int next = i + 1;
                if (next == items.length) {
                    next = 0;
                }
                if (next != putIndex) {
                    items[i] = items[next];
                    i = next;
                } else {
                    items[i] = null;
                    this.putIndex = i;
                    break;
                }
            }
            count--;
            if (itrs != null) {
                itrs.removedAt(removeIndex);
            }
        }
        notFull.signal();
    }

    /**
     * @param capacity 队列的容量
     * @throws IllegalArgumentException 如果capacity < 1
     */
    public ArrayBlockingQueue(int capacity) {
        this(capacity, false);
    }

    /**
     * @param capacity 队列的容量
     * @param fair ReentrantLock是否为公平锁
     * @throws IllegalArgumentException 如果capacity < 1
     */
    public ArrayBlockingQueue(int capacity, boolean fair) {
        if (capacity <= 0)
            throw new IllegalArgumentException();
        this.items = new Object[capacity];
        lock = new ReentrantLock(fair);
        notEmpty = lock.newCondition();
        notFull =  lock.newCondition();
    }

    /**
     * @param capacity 队列的容量
     * @param fair ReentrantLock是否为公平锁
     * @param c 要在初始化时加进队列的集合
     * @throws IllegalArgumentException 如果capacity < 1
     * @throws NullPointerException 集合或它的元素为null
     */
    public ArrayBlockingQueue(int capacity, boolean fair,
                              Collection<? extends E> c) {
        this(capacity, fair);

        final ReentrantLock lock = this.lock;
        lock.lock(); // Lock only for visibility, not mutual exclusion
        try {
            int i = 0;
            try {
                for (E e : c) {
                    checkNotNull(e);
                    items[i++] = e;
                }
            } catch (ArrayIndexOutOfBoundsException ex) {
                throw new IllegalArgumentException();
            }
            count = i;
            putIndex = (i == capacity) ? 0 : i;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 如果队列元素的数量没有超过容量，则插入元素到队列尾部，
     * 如果超出队列容量则抛出异常IllegalStateException("Queue full")
     */
    public boolean add(E e) {
        return super.add(e);
    }

    /**
     * 如果队列元素的数量没有超过容量，则插入元素到队列尾部
     * @return 元素是否插入到了队列中
     * @throws NullPointerException 如果元素为空null
     */
    public boolean offer(E e) {
        checkNotNull(e);
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count == items.length)
            	//队列满了
                return false;
            else {
                enqueue(e);
                return true;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 插入元素到队列中，如果队列满啦则挂起线程等待队列中有位置
     */
    public void put(E e) throws InterruptedException {
        checkNotNull(e);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                notFull.await();
            }
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 插入元素到队列中，如果队列满啦则等待一段时间
     * @param timeout 时间长度
     * @param unit 时间单位
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {

        checkNotNull(e);
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(e);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 出队
     */
    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return (count == 0) ? null : dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从队列中获取元素，如果队列为空，则挂起线程等待队列中有元素
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                notEmpty.await();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从队列中获取元素，如果队列为空，则挂起线程等待一段时间
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回要出队的元素
     */
    public E peek() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return itemAt(takeIndex); // 当队列为空时返回null
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return 队列中元素的数量
     */
    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 队列中空余位置的数量
     */
    public int remainingCapacity() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return items.length - count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 删除队列中的一个o对象
     *
     * @param o 要被移除队列的元素
     * @return {@code true} if this queue changed as a result of the call
     */
    public boolean remove(Object o) {
        if (o == null) {
        	return false;
        }
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count > 0) {
                final int putIndex = this.putIndex;
                int i = takeIndex;
                do {
                    if (o.equals(items[i])) {
                        removeAt(i);
                        return true;
                    }
                    if (++i == items.length) {
                        i = 0;
                    }
                } while (i != putIndex);
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param o 对象o是否包含在队列中
     * @return 队列中是否包含对象
     */
    public boolean contains(Object o) {
        if (o == null) return false;
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            if (count > 0) {
                final int putIndex = this.putIndex;
                int i = takeIndex;
                do {
                    if (o.equals(items[i])) {
                        return true;
                    }
                    if (++i == items.length) {
                        i = 0;
                    }
                } while (i != putIndex);
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return 返回一个包含队列所有对象的数组
     */
    public Object[] toArray() {
        Object[] a;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final int count = this.count;
            a = new Object[count];
            int n = items.length - takeIndex;
            if (count <= n) {
                System.arraycopy(items, takeIndex, a, 0, count);
            } else {
                System.arraycopy(items, takeIndex, a, 0, n);
                System.arraycopy(items, 0, a, n, count - n);
            }
        } finally {
            lock.unlock();
        }
        return a;
    }

    /**
     * 返回一个包含队列所有对象的数组
     */
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final int count = this.count;
            final int len = a.length;
            //给的数组容量不够大则创建一个新的
            if (len < count) {
                a = (T[])java.lang.reflect.Array.newInstance(
                    a.getClass().getComponentType(), count);
            }
            int n = items.length - takeIndex;
            if (count <= n)
                System.arraycopy(items, takeIndex, a, 0, count);
            else {
                System.arraycopy(items, takeIndex, a, 0, n);
                System.arraycopy(items, 0, a, n, count - n);
            }
            if (len > count) {
            	//标识作用
                a[count] = null;
            }
        } finally {
            lock.unlock();
        }
        return a;
    }

    public String toString() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int k = count;
            if (k == 0)
                return "[]";

            final Object[] items = this.items;
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = takeIndex; ; ) {
                Object e = items[i];
                sb.append(e == this ? "(this Collection)" : e);
                if (--k == 0)
                    return sb.append(']').toString();
                sb.append(',').append(' ');
                if (++i == items.length)
                    i = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空队列
     */
    public void clear() {
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int k = count;
            if (k > 0) {
                final int putIndex = this.putIndex;
                int i = takeIndex;
                do {
                    items[i] = null;
                    if (++i == items.length)
                        i = 0;
                } while (i != putIndex);
                takeIndex = putIndex;
                count = 0;
                if (itrs != null)
                    itrs.queueIsEmpty();
                for (; k > 0 && lock.hasWaiters(notFull); k--)
                    notFull.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        checkNotNull(c);
        if (c == this)
            throw new IllegalArgumentException();
        if (maxElements <= 0)
            return 0;
        final Object[] items = this.items;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = Math.min(maxElements, count);
            int take = takeIndex;
            int i = 0;
            try {
                while (i < n) {
                    @SuppressWarnings("unchecked")
                    E x = (E) items[take];
                    c.add(x);
                    items[take] = null;
                    if (++take == items.length)
                        take = 0;
                    i++;
                }
                return n;
            } finally {
                // Restore invariants even if c.add() threw
                if (i > 0) {
                    count -= i;
                    takeIndex = take;
                    if (itrs != null) {
                        if (count == 0)
                            itrs.queueIsEmpty();
                        else if (i > take)
                            itrs.takeIndexWrapped();
                    }
                    for (; i > 0 && lock.hasWaiters(notFull); i--)
                        notFull.signal();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an iterator over the elements in this queue in proper sequence.
     * The elements will be returned in order from first (head) to last (tail).
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue in proper sequence
     */
    public Iterator<E> iterator() {
        return new Itr();
    }

    /**
     * Shared data between iterators and their queue, allowing queue
     * modifications to update iterators when elements are removed.
     *
     * This adds a lot of complexity for the sake of correctly
     * handling some uncommon operations, but the combination of
     * circular-arrays and supporting interior removes (i.e., those
     * not at head) would cause iterators to sometimes lose their
     * places and/or (re)report elements they shouldn't.  To avoid
     * this, when a queue has one or more iterators, it keeps iterator
     * state consistent by:
     *
     * (1) keeping track of the number of "cycles", that is, the
     *     number of times takeIndex has wrapped around to 0.
     * (2) notifying all iterators via the callback removedAt whenever
     *     an interior element is removed (and thus other elements may
     *     be shifted).
     *
     * These suffice to eliminate iterator inconsistencies, but
     * unfortunately add the secondary responsibility of maintaining
     * the list of iterators.  We track all active iterators in a
     * simple linked list (accessed only when the queue's lock is
     * held) of weak references to Itr.  The list is cleaned up using
     * 3 different mechanisms:
     *
     * (1) Whenever a new iterator is created, do some O(1) checking for
     *     stale list elements.
     *
     * (2) Whenever takeIndex wraps around to 0, check for iterators
     *     that have been unused for more than one wrap-around cycle.
     *
     * (3) Whenever the queue becomes empty, all iterators are notified
     *     and this entire data structure is discarded.
     *
     * So in addition to the removedAt callback that is necessary for
     * correctness, iterators have the shutdown and takeIndexWrapped
     * callbacks that help remove stale iterators from the list.
     *
     * Whenever a list element is examined, it is expunged if either
     * the GC has determined that the iterator is discarded, or if the
     * iterator reports that it is "detached" (does not need any
     * further state updates).  Overhead is maximal when takeIndex
     * never advances, iterators are discarded before they are
     * exhausted, and all removals are interior removes, in which case
     * all stale iterators are discovered by the GC.  But even in this
     * case we don't increase the amortized complexity.
     *
     * Care must be taken to keep list sweeping methods from
     * reentrantly invoking another such method, causing subtle
     * corruption bugs.
     *  迭代器的集合，链表形式 
     */
    class Itrs {

        /**
         * 虚引用，当成缓存使用的 
         */
        private class Node extends WeakReference<Itr> {
            Node next;

            Node(Itr iterator, Node next) {
                super(iterator);
                this.next = next;
            }
        }

        /** Incremented whenever takeIndex wraps around to 0 */
        int cycles = 0;

        /** Linked list of weak iterator references */
        private Node head;

        /**用于删除老的迭代器 */
        private Node sweeper = null;

        private static final int SHORT_SWEEP_PROBES = 4;
        private static final int LONG_SWEEP_PROBES = 16;

        //初始化函数注册迭代器到迭代器集合里面 
        Itrs(Itr initial) {
            register(initial);
        }

        /**
         * Sweeps itrs, looking for and expunging stale iterators.
         * If at least one was found, tries harder to find more.
         * Called only from iterating thread.
         *
         * @param tryHarder whether to start in try-harder mode, because
         * there is known to be at least one iterator to collect
         * 清理itrs 整理旧的过期的迭代器 所谓过期的迭代器，是被标识为none 或者是Detached就是被取走的 
         * 这个整理动作也是很有意思，普通是循环SHORT_SWEEP_PROBES次数，一旦发现有，那就会多循环LONG_SWEEP_PROBES次数，尽力去寻找 
         */
        void doSomeSweeping(boolean tryHarder) {
            // assert lock.getHoldCount() == 1;
            // assert head != null;
            int probes = tryHarder ? LONG_SWEEP_PROBES : SHORT_SWEEP_PROBES;
            Node o, p;
            final Node sweeper = this.sweeper;
            boolean passedGo;   // to limit search to one full sweep

            if (sweeper == null) {
                o = null;
                p = head;
                passedGo = true;
            } else {
                o = sweeper;
                p = o.next;
                passedGo = false;
            }

            for (; probes > 0; probes--) {
                if (p == null) {
                    if (passedGo)
                        break;
                    o = null;
                    p = head;
                    passedGo = true;
                }
                final Itr it = p.get();
                final Node next = p.next;
                if (it == null || it.isDetached()) {
                    // found a discarded/exhausted iterator
                    probes = LONG_SWEEP_PROBES; // "try harder"
                    // unlink p
                    p.clear();
                    p.next = null;
                    if (o == null) {
                        head = next;
                        if (next == null) {
                            // We've run out of iterators to track; retire
                            itrs = null;
                            return;
                        }
                    }
                    else
                        o.next = next;
                } else {
                    o = p;
                }
                p = next;
            }

            this.sweeper = (p == null) ? null : o;
        }

        /**
         * 在链表的最前面加元素
         */
        void register(Itr itr) {
            // assert lock.getHoldCount() == 1;
            head = new Node(itr, head);
        }

        /**
         * Called whenever takeIndex wraps around to 0.
         *
         * Notifies all iterators, and expunges any that are now stale.
         */
        void takeIndexWrapped() {
            cycles++;
            for (Node o = null, p = head; p != null;) {
                final Itr it = p.get();
                final Node next = p.next;
                if (it == null || it.takeIndexWrapped()) {
                    // unlink p
                    // assert it == null || it.isDetached();
                    p.clear();
                    p.next = null;
                    if (o == null)
                        head = next;
                    else
                        o.next = next;
                } else {
                    o = p;
                }
                p = next;
            }
            if (head == null) {
                itrs = null;
            }
        }

        /**
         * Called whenever an interior remove (not at takeIndex) occurred.
         *
         * Notifies all iterators, and expunges any that are now stale.
         */
        void removedAt(int removedIndex) {
            for (Node o = null, p = head; p != null;) {
                final Itr it = p.get();
                final Node next = p.next;
                if (it == null || it.removedAt(removedIndex)) {
                    // unlink p
                    // assert it == null || it.isDetached();
                    p.clear();
                    p.next = null;
                    if (o == null)
                        head = next;
                    else
                        o.next = next;
                } else {
                    o = p;
                }
                p = next;
            }
            if (head == null)   // no more iterators to track
                itrs = null;
        }

        /**
         * Called whenever the queue becomes empty.
         *
         * Notifies all active iterators that the queue is empty,
         * clears all weak refs, and unlinks the itrs datastructure.
         */
        void queueIsEmpty() {
            // assert lock.getHoldCount() == 1;
            for (Node p = head; p != null; p = p.next) {
                Itr it = p.get();
                if (it != null) {
                    p.clear();
                    it.shutdown();
                }
            }
            head = null;
            itrs = null;
        }

        /**
         * Called whenever an element has been dequeued (at takeIndex).
         */
        void elementDequeued() {
            if (count == 0)
                queueIsEmpty();
            else if (takeIndex == 0)
                takeIndexWrapped();
        }
    }

    /**
     * 迭代ArrayBlockingQueue.
     */
    private class Itr implements Iterator<E> {
        /** Index to look for new nextItem; NONE at end */
        private int cursor;

        /** 下一次调用next()返回的元素; 如果没有则为null*/
        private E nextItem;

        /** nextItem的下标; 如果没有则为NONE, 被删除了则为REMOVED */
        private int nextIndex;

        /** 上一次返回的元素， null if none or not detached. */
        private E lastItem;

        /** lastItem的下标, 如果没有则为NONE, 被删除了则为REMOVED */
        private int lastRet;

        /** 之前takeIndex的值, 被detached后为DETACHED */
        private int prevTakeIndex;

        /** 之前迭代的次数，和Cycles进行比对，就知道有没有再循环过 */
        private int prevCycles;

        /** Special index value indicating "not available" or "undefined" */
        private static final int NONE = -1;

        /**
         * Special index value indicating "removed elsewhere", that is,
         * removed by some operation other than a call to this.remove().
         */
        private static final int REMOVED = -2;

        /** 要从itrs中删除方便gc */
        private static final int DETACHED = -3;

        Itr() {
            lastRet = NONE;
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                if (count == 0) {
                    // assert itrs == null;
                    cursor = NONE;
                    nextIndex = NONE;
                    prevTakeIndex = DETACHED;
                } else {
                    final int takeIndex = ArrayBlockingQueue.this.takeIndex;
                    prevTakeIndex = takeIndex;
                    nextItem = itemAt(nextIndex = takeIndex);
                    cursor = incCursor(takeIndex);
                    //将Itr对象加到Itrs链表中
                    if (itrs == null) {
                        itrs = new Itrs(this);
                    } else {
                        itrs.register(this); // in this order
                        itrs.doSomeSweeping(false);
                    }
                    prevCycles = itrs.cycles;

                }
            } finally {
                lock.unlock();
            }
        }

        boolean isDetached() {
            return prevTakeIndex < 0;
        }

        //index+1
        private int incCursor(int index) {
            if (++index == items.length) {
                index = 0;
            }
            if (index == putIndex) {
                index = NONE;
            }
            return index;
        }

        /**
         * 判断所记录的last，next cursor 还是不是原值如果不是，这个迭代器就判定detach  .
         * @param prevTakeIndex 上一个出队的下标
         * @param dequeues takeIndex的偏移量
         * @param length 存储队列的数组的长度
         * 
         */
        private boolean invalidated(int index, int prevTakeIndex,
                                    long dequeues, int length) {
            if (index < 0) {
                return false;
            }
            int distance = index - prevTakeIndex;
            if (distance < 0) {
                distance += length;
            }
            return dequeues > distance;
        }

        /**
         * 发现元素发生移动，通过判定cycle等信息，然后cursor取值游标就重新从takeIndex开始 
         * 下面如果发现所有记录标志的值发生变化，就直接清理本迭代器了
         */
        private void incorporateDequeues() {
        	
            final int cycles = itrs.cycles;
            final int takeIndex = ArrayBlockingQueue.this.takeIndex;
            final int prevCycles = this.prevCycles;
            final int prevTakeIndex = this.prevTakeIndex;

            if (cycles != prevCycles || takeIndex != prevTakeIndex) {
                final int len = items.length;
                // takeIndex的偏移量
                long dequeues = (cycles - prevCycles) * len
                    + (takeIndex - prevTakeIndex);

                // 判断所记录的last，next cursor 还是不是原值如果不是，这个迭代器就判定detach  
                if (invalidated(lastRet, prevTakeIndex, dequeues, len)) {
                    lastRet = REMOVED;
                }
                if (invalidated(nextIndex, prevTakeIndex, dequeues, len)) {
                    nextIndex = REMOVED;
                }
                if (invalidated(cursor, prevTakeIndex, dequeues, len)) {
                    cursor = takeIndex;
                }

                if (cursor < 0 && nextIndex < 0 && lastRet < 0) {
                    detach();
                } else {
                    this.prevCycles = cycles;
                    this.prevTakeIndex = takeIndex;
                }
            }
        }

        /**
		 * 因为takeIndex等于0了，意味着开始下一个循环了. 
		 * 然后通知所有的迭代器，删除无用的迭代器。 
		 *
		 * @return 是否要将当前itr对象从itrs中删除
		 */
		boolean takeIndexWrapped() {
		    if (isDetached()) {
		        return true;
		    }
		    if (itrs.cycles - prevCycles > 1) {
		        // All the elements that existed at the time of the last
		        // operation are gone, so abandon further iteration.
		        shutdown();
		        return true;
		    }
		    return false;
		}

		/**
         * 将prevTakeIndex变为DETACHED状态，方便gc
         */
        private void detach() {
            if (prevTakeIndex >= 0) {
                // assert itrs != null;
                prevTakeIndex = DETACHED;
                // try to unlink from itrs (but not too hard)
                itrs.doSomeSweeping(true);
            }
        }

        /**
         * 是否还有下一个.
         */
        public boolean hasNext() {
            if (nextItem != null) {
                return true;
            }
            noNext();
            return false;
        }

        /*
         * 如果没有下一个啦，将prevTakeIndex变为DETACHED状态，方便gc
         */
        private void noNext() {
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                if (!isDetached()) {
                    incorporateDequeues(); // 可能修改了lastRet
                    if (lastRet >= 0) {
                        lastItem = itemAt(lastRet);
                        detach();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * 获取下一个元素
         */
        public E next() {
            final E x = nextItem;
            if (x == null) {
                throw new NoSuchElementException();
            }
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                if (!isDetached()) {
                    incorporateDequeues();
                }
                lastRet = nextIndex;
                final int cursor = this.cursor;
                if (cursor >= 0) {
                    nextItem = itemAt(nextIndex = cursor);
                    // assert nextItem != null;
                    this.cursor = incCursor(cursor);
                } else {
                    nextIndex = NONE;
                    nextItem = null;
                }
            } finally {
                lock.unlock();
            }
            return x;
        }

        /**
         * 删除上一次返回的元素
         */
        public void remove() {
            // assert lock.getHoldCount() == 0;
            final ReentrantLock lock = ArrayBlockingQueue.this.lock;
            lock.lock();
            try {
                if (!isDetached()) {
                    incorporateDequeues(); // might update lastRet or detach
                }
                final int lastRet = this.lastRet;
                this.lastRet = NONE;
                if (lastRet >= 0) {
                    if (!isDetached()) {
                        removeAt(lastRet);
                    } else {
                        final E lastItem = this.lastItem;
                        // assert lastItem != null;
                        this.lastItem = null;
                        if (itemAt(lastRet) == lastItem)
                            removeAt(lastRet);
                    }
                } else if (lastRet == NONE)
                    throw new IllegalStateException();
                // else lastRet == REMOVED and the last returned element was
                // previously asynchronously removed via an operation other
                // than this.remove(), so nothing to do.

                if (cursor < 0 && nextIndex < 0) {
                    detach();
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * 将所有的标志位都标记成remove ，null 
         */
        void shutdown() {
            // assert lock.getHoldCount() == 1;
            cursor = NONE;
            if (nextIndex >= 0)
                nextIndex = REMOVED;
            if (lastRet >= 0) {
                lastRet = REMOVED;
                lastItem = null;
            }
            prevTakeIndex = DETACHED;
        }

        private int distance(int index, int prevTakeIndex, int length) {
            int distance = index - prevTakeIndex;
            if (distance < 0)
                distance += length;
            return distance;
        }

        /**
         * @return 是否要将当前itr对象从itrs中删除
         */
        boolean removedAt(int removedIndex) {
            if (isDetached()) {
                return true;
            }

            final int cycles = itrs.cycles;
            final int takeIndex = ArrayBlockingQueue.this.takeIndex;
            final int prevCycles = this.prevCycles;
            final int prevTakeIndex = this.prevTakeIndex;
            final int len = items.length;
            int cycleDiff = cycles - prevCycles;
            if (removedIndex < takeIndex) {
                cycleDiff++;
            }
            final int removedDistance =
                (cycleDiff * len) + (removedIndex - prevTakeIndex);
            int cursor = this.cursor;
            if (cursor >= 0) {
                int x = distance(cursor, prevTakeIndex, len);
                if (x == removedDistance) {
                    if (cursor == putIndex) {
                        this.cursor = cursor = NONE;
                    }
                } else if (x > removedDistance) {
                    this.cursor = cursor = dec(cursor);
                }
            }
            int lastRet = this.lastRet;
            if (lastRet >= 0) {
                int x = distance(lastRet, prevTakeIndex, len);
                if (x == removedDistance) {
                    this.lastRet = lastRet = REMOVED;
                } else if (x > removedDistance) {
                    this.lastRet = lastRet = dec(lastRet);
                }
            }
            int nextIndex = this.nextIndex;
            if (nextIndex >= 0) {
                int x = distance(nextIndex, prevTakeIndex, len);
                if (x == removedDistance) {
                    this.nextIndex = nextIndex = REMOVED;
                } else if (x > removedDistance) {
                    this.nextIndex = nextIndex = dec(nextIndex);
                }
            }
            else if (cursor < 0 && nextIndex < 0 && lastRet < 0) {
                this.prevTakeIndex = DETACHED;
                return true;
            }
            return false;
        }

    }

    public Spliterator<E> spliterator() {
        return Spliterators.spliterator
            (this, Spliterator.ORDERED | Spliterator.NONNULL |
             Spliterator.CONCURRENT);
    }

}


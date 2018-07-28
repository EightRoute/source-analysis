package util.threadpool;

import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;


/**
 * 异步任务,配合线程池使用
 */
public class FutureTask<V> implements RunnableFuture<V> {

    /**
     * 几种可能发生的state值的变化:
     *
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    private volatile int state;
    private static final int NEW          = 0;
    private static final int COMPLETING   = 1;
    private static final int NORMAL       = 2;
    private static final int EXCEPTIONAL  = 3;
    private static final int CANCELLED    = 4;
    private static final int INTERRUPTING = 5;
    private static final int INTERRUPTED  = 6;

    private Callable<V> callable;

    /** 执行的结果 */
    private Object outcome;

    /** 执行callable的线程;run()中cas进去的 */
    private volatile Thread runner;

    /** 等待执行结果的线程的栈*/
    private volatile WaitNode waiters;



    /**
     * 返回执行的result或者throws exception
     * @param s 完成时的state
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        if (s == NORMAL) {
            return (V) x;
        }
        if (s >= CANCELLED) {
            throw new CancellationException();
        }
        //t为Throwable时
        throw new ExecutionException((Throwable)x);
    }

    /**
     * @param  callable 要被执行的任务，可以返回结果
     * @throws NullPointerException 如果runnable为 null
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null) {
            throw new NullPointerException();
        }
        this.callable = callable;
        //确保callable的可见性
        this.state = NEW;
    }

    /**
     * 任务执行成功后将返回result
     *
     * @param runnable 要被执行的任务
     * @param result 成功完成后返回的结果
     * @throws NullPointerException 如果runnable为 null
     */
    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        //确保callable的可见性
        this.state = NEW;
    }

    /**
     * @return 是否被取消
     */
    @Override
    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    /**
     * @return 是否被执行
     */
    @Override
    public boolean isDone() {
        return state != NEW;
    }

    /**
     * 取消任务
     * @param mayInterruptIfRunning 是否抛出中断异常
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!(state == NEW &&
                UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                        mayInterruptIfRunning ? INTERRUPTING : CANCELLED))) {
            //如果state不为NEW
            return false;
        }
        try {
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null) {
                        t.interrupt();
                    }
                } finally {
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            finishCompletion();
        }
        return true;
    }

    /**
     * 获取执行结果,需要等待run方法执行完成
     */
    @Override
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        if (s <= COMPLETING) {
            s = awaitDone(false, 0L);
        }
        return report(s);
    }

    /**
     * 在一段时间内，获取返回值，如果超时抛出TimeoutException
     */
    @Override
    public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null) {
            throw new NullPointerException();
        }
        int s = state;
        if (s <= COMPLETING && (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING) {
            throw new TimeoutException();
        }
        return report(s);
    }

    /**
     * finishCompletion时会调用
     * 子类可扩展
     */
    protected void done() { }

    /**
     * @param v 任务的执行结果
     */
    protected void set(V v) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = v;
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL);
            finishCompletion();
        }
    }

    /**
     * 返回值设置为Throwable
     *
     * @param t  failure的原因
     */
    protected void setException(Throwable t) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = t;
            // final state
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL);
            finishCompletion();
        }
    }


    /**
     * Runnable的实现类,可以开启新的线程
     */
    @Override
    public void run() {
        //将runner设为新开的当前线程
        if (state != NEW || !UNSAFE.compareAndSwapObject(this, runnerOffset,
                        null, Thread.currentThread())) {
            return;
        }
        try {
            Callable<V> c = callable;
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    ran = false;
                    setException(ex);
                }
                if (ran) {
                    //设置执行结果
                    set(result);
                }
            }
        } finally {
            runner = null;
            int s = state;
            if (s >= INTERRUPTING) {
                handlePossibleCancellationInterrupt(s);
            }
        }
    }

    /**
     * 试探性的执行一下
     *
     * 在不设置结果的情况下执行task, 然后
     * 将这个future重新设置为初始状态,
     *
     * @return {@code true} 如果执行成功而且重置了state
     */
    protected boolean runAndReset() {
        if (state != NEW || !UNSAFE.compareAndSwapObject(this, runnerOffset,
                        null, Thread.currentThread())) {
            return false;
        }
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    //不设置result
                    c.call();
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            runner = null;
            s = state;
            if (s >= INTERRUPTING) {
                handlePossibleCancellationInterrupt(s);
            }
        }
        return ran && s == NEW;
    }

    /**
     * 确保中断完成
     */
    private void handlePossibleCancellationInterrupt(int s) {
        //等待线程被中断完成
        if (s == INTERRUPTING) {
            while (state == INTERRUPTING) {
                //让出时间片
                Thread.yield();
            }
        }
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }

    /**
     * 移除WaitNode，唤醒所有等待的线程, 并执行done()
     */
    private void finishCompletion() {
        for (WaitNode q; (q = waiters) != null;) {
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (;;) {
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        //唤醒等待结果的线程
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    if (next == null) {
                        break;
                    }
                    // help gc
                    q.next = null;
                    q = next;
                }
                break;
            }
        }

        done();

        callable = null;
    }

    /**
     * 等待任务执行完返回，超时返回，中断返回
     *
     * @param timed 是否开启超时等待
     * @param nanos 等待多久
     * @return 结果的状态值
     */
    private int awaitDone(boolean timed, long nanos)
            throws InterruptedException {
        //是否开启超时等待
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;
        boolean queued = false;
        //开启自旋
        for (;;) {
            if (Thread.interrupted()) {
                //如果线程被中断
                removeWaiter(q);
                throw new InterruptedException();
            }

            int s = state;
            if (s > COMPLETING) {
                if (q != null) {
                    q.thread = null;
                }
                return s;
            } else if (s == COMPLETING) {
                Thread.yield();
            } else if (q == null) {
                q = new WaitNode();
            } else if (!queued) {
                //入栈
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                        q.next = waiters, q);
            } else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    //移除等待
                    removeWaiter(q);
                    return state;
                }
                //挂起一段时间
                LockSupport.parkNanos(this, nanos);
            } else {
                //挂起线程，等待task执行完成
                LockSupport.park(this);
            }
        }
    }

    /**
     * 移除在等待结果的node
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
                retry:
            for (;;) {
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null) {
                        pred = q;
                    } else if (pred != null) {
                        pred.next = s;
                        // check for race
                        if (pred.thread == null) {
                            continue retry;
                        }
                    }
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                            q, s)) {
                        continue retry;
                    }
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = java.util.concurrent.FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}


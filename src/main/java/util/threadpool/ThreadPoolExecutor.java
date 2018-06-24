package com.webmvc.util.threadpool;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;


public class ThreadPoolExecutor extends AbstractExecutorService {
   
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    private static final int COUNT_BITS = Integer.SIZE - 3;
    private static final int CAPACITY   = (1 << COUNT_BITS) - 1;

    //该状态的线程池会接收新任务，并处理阻塞队列中的任务
    private static final int RUNNING    = -1 << COUNT_BITS;
    //该状态的线程池不会接收新任务，但会处理阻塞队列中的任务
    private static final int SHUTDOWN   =  0 << COUNT_BITS;
    //该状态的线程不会接收新任务，也不会处理阻塞队列中的任务，而且会中断正在运行的任务
    private static final int STOP       =  1 << COUNT_BITS;
    //terminated()执行前
    private static final int TIDYING    =  2 << COUNT_BITS;
    //terminated()完成后
    private static final int TERMINATED =  3 << COUNT_BITS;

    // Packing and unpacking ctl
    // ~ 取反运算
    //取前3位得到runState
    private static int runStateOf(int c)     { return c & ~CAPACITY; }
    //取后29位得到workerCount
    private static int workerCountOf(int c)  { return c & CAPACITY; }
    //根据runState和workerCount打包合并成ctl
    private static int ctlOf(int rs, int wc) { return rs | wc; }

    /*
     * 线程池运行状态c是否小于s
     */
    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    /*
     * 线程池运行状态c是否大于等于s
     */
    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    /*
     * 是否为RUNNING状态
     */
    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    /**
     * cas操作增加 workerCount
     */
    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    /**
     * cas操作减少一个workerCount
     */
    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    /**
     * 减少workerCount.
     */
    private void decrementWorkerCount() {
        do {} while (! compareAndDecrementWorkerCount(ctl.get()));
    }

    /**
     * 用来保存等待被执行的任务的阻塞队列，且任务必须实现Runable接口
     * 1、ArrayBlockingQueue：基于数组结构的有界阻塞队列，按FIFO排序任务；
     * 2、LinkedBlockingQuene：基于链表结构的阻塞队列，按FIFO排序任务，吞吐量通常要高于ArrayBlockingQuene；无界队列，所以创建的线程就不会超过corePoolSize，也因此，maximumPoolSize的值也就无效了
     * 3、SynchronousQuene：一个不存储元素的阻塞队列，每个插入操作必须等到另一个线程调用移除操作，否则插入操作一直处于阻塞状态，吞吐量通常要高于LinkedBlockingQuene；
     * 4、priorityBlockingQuene：具有优先级的无界阻塞队列
     * 5、DelayQueue：有界阻塞延时队列，当队列里的元素延时期未到时，通过take方法不能获取，会被阻塞，直到有元素延时到期为止
     */
    private final BlockingQueue<Runnable> workQueue;

    /**
     * 全局锁.
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * 池中包含所有worker threads的Set集合. 只在mainLock锁中操作
     */
    private final HashSet<Worker> workers = new HashSet<Worker>();

    /**
     * Wait condition to support awaitTermination
     */
    private final Condition termination = mainLock.newCondition();

    /**
     * 池中最大的线程数量. 只在mainLock锁中操作
     */
    private int largestPoolSize;

    /**
     * 完成任务的数量
     */
    private long completedTaskCount;


    /**
     * 创建线程的工厂. 
     */
    private volatile ThreadFactory threadFactory;

    /**
     * 线程超过maximumPoolSize时的饱和处理策略
     */
    private volatile RejectedExecutionHandler handler;

    /**
     * 线程空闲时的存活时间，即当线程没有任务执行时，
     * 继续存活的时间；默认情况下，
     * 该参数只在线程数大于corePoolSize时才有用
     * 
     */
    private volatile long keepAliveTime;

    /**
     * 核心池的线程是否使用keepAliveTime的时间超时等待任务
     * 默认为false
     */
    private volatile boolean allowCoreThreadTimeOut;

    /**
     * 线程池中的核心线程数，当提交一个任务时，线程池创建一个新线程执行任务，直到当前线程数等于corePoolSize；
     * 如果当前线程数为corePoolSize，继续提交的任务被保存到阻塞队列中，等待被执行；
     * 如果执行了线程池的prestartAllCoreThreads()方法，线程池会提前创建并启动所有核心线程
     */
    private volatile int corePoolSize;

    /**
     * 线程池中允许的最大线程数。如果当前阻塞队列满了，且继续提交任务，
     * 则创建新的线程执行任务，前提是当前线程数小于maximumPoolSize
     */
    private volatile int maximumPoolSize;

    /**
     * 默认饱和处理策略
     */
    private static final RejectedExecutionHandler defaultHandler =
        new AbortPolicy();

    private static final RuntimePermission shutdownPerm =
        new RuntimePermission("modifyThread");

    /**
     * Worker继承AQS和Runnable是具体承载任务的对象，
     * Worker继承了AQS自己实现了简单的不可重入独占锁，
     * 其中status=0标示锁未被获取状态也就是未被锁住的状态，
     * state=1标示锁已经被获取的状态也就是锁住的状态
     */
    private final class Worker
        extends AbstractQueuedSynchronizer
        implements Runnable
    {
        private static final long serialVersionUID = 6138294804551838833L;

        /** Thread this worker is running in.  如果线程工厂创建失败则为Null. */
        final Thread thread;
        /** 初始任务. 可能为null. */
        Runnable firstTask;
        /** 完成任务的数量 */
        volatile long completedTasks;

        /**
         * @param firstTask 第一个任务，没有则为null
         */
        Worker(Runnable firstTask) {
        	//runWorker前禁止中断，下面的interruptIfStarted()
            setState(-1); // inhibit interrupts until runWorker
            this.firstTask = firstTask;
            this.thread = getThreadFactory().newThread(this);
        }

        /** 调用runWorker  */
        public void run() {
            runWorker(this);
        }

        // 锁是否被占
        protected boolean isHeldExclusively() {
            return getState() != 0;
        }

        //尝试获取锁
        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        //释放锁
        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        //加锁
        public void lock()        { acquire(1); }
        //是否能获取到锁
        public boolean tryLock()  { return tryAcquire(1); }
        //释放锁
        public void unlock()      { release(1); }
        //是否被锁
        public boolean isLocked() { return isHeldExclusively(); }

        void interruptIfStarted() {
            Thread t;
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }

    /**
     * 增加runState的值为targetState
     */
    private void advanceRunState(int targetState) {
        for (;;) {
            int c = ctl.get();
            if (runStateAtLeast(c, targetState) ||
                ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c)))) {
                //当前状态的值大于targetState或者CAS成功
            	break;
            }
        }
    }

    /**
     * 在以下情况将线程池变为TERMINATED终止状态
     * shutdown 且 正在运行的worker 和 workQueue队列 都empty
     * stop 且  没有正在运行的worker
     * 这个方法必须在任何可能导致线程池终止的情况下被调用，如：
     * 减少worker数量
     * shutdown时从queue中移除任务
     * 这个方法不是私有的，所以允许子类ScheduledThreadPoolExecutor调用
     */
    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();
            if (isRunning(c) ||
                runStateAtLeast(c, TIDYING) ||
                (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty())) {
                return;
            }
            if (workerCountOf(c) != 0) { // Eligible to terminate
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                    	//子类扩展
                        terminated();
                    } finally {
                        ctl.set(ctlOf(TERMINATED, 0));
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }


    /**
     * 权限检查
     */
    private void checkShutdownAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(shutdownPerm);
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                for (Worker w : workers) {
                    security.checkAccess(w.thread);
                }
            } finally {
                mainLock.unlock();
            }
        }
    }

    /**
     * 中断所有线程，即使是活跃的，不理睬SecurityExceptions
     * (在这种情况下,一些线程可能保持uninterrupted).
     */
    private void interruptWorkers() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers) {
                w.interruptIfStarted();
            }
        } finally {
            mainLock.unlock();
        }
    }

    /*
     * 中断在等待任务的线程(没有上锁的)，中断唤醒后，
     * 可以判断线程池状态是否变化来决定是否继续
     *
     * onlyOne如果为true，最多interrupt一个worker
     * 只有当终止流程已经开始，但线程池还有worker线程时,
     * tryTerminate()方法会做调用onlyOne为true的调用
     * （终止流程已经开始指的是：shutdown状态 且 workQueue为空，或者 stop状态）
     * 在这种情况下，最多有一个worker被中断，为了传播shutdown信号，以免所有的线程都在等待
     * 为保证线程池最终能终止，这个操作总是中断一个空闲worker
     * 而shutdown()中断所有空闲worker，来保证空闲线程及时退出
     */
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers) {
                Thread t = w.thread;
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
                }
                if (onlyOne) {
                    break;
                }
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 中断所有空闲线程
     */
    private void interruptIdleWorkers() {
        interruptIdleWorkers(false);
    }

    private static final boolean ONLY_ONE = true;


    /**
     * 当线程数量大于maximumPoolSize时的饱和策略
     * 目前有
     * 1、用调用者所在的线程来执行任务
     * 2、抛异常，默认饱和策略
     * 3、直接丢弃
     * 4、丢弃队列中靠最前的任务，并执行当前任务
     */
    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    /**
     * ScheduledThreadPoolExecutor中有用
     */
    void onShutdown() {
    }

    /**
     * @param shutdownOK SHUTDOWN是否需要返回true
     */
    final boolean isRunningOrShutdown(boolean shutdownOK) {
        int rs = runStateOf(ctl.get());
        return rs == RUNNING || (rs == SHUTDOWN && shutdownOK);
    }

    /**
     * 将任务队列移动到一个新的list集合中
     */
    private List<Runnable> drainQueue() {
        BlockingQueue<Runnable> q = workQueue;
        ArrayList<Runnable> taskList = new ArrayList<Runnable>();
        q.drainTo(taskList);
        //当队列为DelayQueue或者其他可能移动元素失败的队列时，再一次删除他们并添加到list中
        if (!q.isEmpty()) {
            for (Runnable r : q.toArray(new Runnable[0])) {
                if (q.remove(r)) {
                    taskList.add(r);
                }
            }
        }
        return taskList;
    }


    /**
     * 创建新的线程执行任务
     *
     * @param firstTask 新线程首先需要运行的任务
     * @param core 如果为true则使用corePoolSize作为边界否则使用maximumPoolSize
     * @return 是否添加成功
     */
    private boolean addWorker(Runnable firstTask, boolean core) {
        retry:
        for (;;) {
            int c = ctl.get();
            //获得线程池的状态
            int rs = runStateOf(c);

            // 如果线程池的状态值大于或等于SHUTDOWN，则不处理提交的任务，直接返回
            if (rs >= SHUTDOWN && 
            		! (rs == SHUTDOWN && firstTask == null &&! workQueue.isEmpty())) {
                return false;
            }

            for (;;) {
                int wc = workerCountOf(c);
                if (wc >= CAPACITY ||
                    wc >= (core ? corePoolSize : maximumPoolSize)) {
                	//大于最大线程数
                    return false;
                }
                if (compareAndIncrementWorkerCount(c)) {
                    //跳出外层循环
                    break retry;
                }
                c = ctl.get();  // Re-read ctl
                if (runStateOf(c) != rs) {
                	//运行状态改变
                    continue retry;
                }
                // else CAS failed due to workerCount change; retry inner loop
            }
        }

        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker w = null;
        try {
        	//创建新的工作线程
            w = new Worker(firstTask);
            //t为Worker
            final Thread t = w.thread;
            if (t != null) {
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    // 当获取到锁后Recheck 
                	// 当创建线程失败或获取到锁前线程池已经关闭，回滚
                    int rs = runStateOf(ctl.get());

                    if (rs < SHUTDOWN ||
                        (rs == SHUTDOWN && firstTask == null)) {
                        if (t.isAlive()) { 
                        	// 如果线程已经start啦
                            throw new IllegalThreadStateException();
                        }
                        //将新建的worker加入到Set中
                        workers.add(w);
                        int s = workers.size();
                        if (s > largestPoolSize) {
                            largestPoolSize = s;
                        }
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }
                if (workerAdded) {
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            if (! workerStarted) {
                addWorkerFailed(w);
            }
        }
        return workerStarted;
    }

    /**
     * 创建失败，回滚
     */
    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if (w != null) {
            	//如果worker存在，从集合中删掉
                workers.remove(w);
            }
            //减少 worker数量
            decrementWorkerCount();
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * worker线程退出
     * @param w the worker
     * @param completedAbruptly 如果这个worker由于用户的异常导致死亡
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        //worker数量-1
        if (completedAbruptly) {
            decrementWorkerCount();
        }

        final ReentrantLock mainLock = this.mainLock;

        mainLock.lock();
        try {
            //统计整个线程池完成的任务个数
            completedTaskCount += w.completedTasks;
            //从HashSet<Worker>中移除
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }
        //尝试设置线程池状态为TERMINATED，如果当前是shutdonw状态并且工作队列为空
        //或者当前是stop状态当前线程池里面没有活动线程
        tryTerminate();

        //如果当前线程个数小于核心个数，则增加
        int c = ctl.get();
        //如果状态是running、shutdown，即tryTerminate()没有成功终止线程池，尝试再添加一个worker
        if (runStateLessThan(c, STOP)) {
            if (!completedAbruptly) {
                //allowCoreThreadTimeOut默认为false，即min默认为corePoolSize
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                if (min == 0 && ! workQueue.isEmpty()) {
                    min = 1;
                }
                //如果线程数量大于最少数量，直接返回，否则下面至少要addWorker一个
                if (workerCountOf(c) >= min) {
                    return; // replacement not needed
                }
            }
            //添加一个没有firstTask的worker
            addWorker(null, false);
        }
    }

    /**
     * 从队列获取task
     * 以下情况会返回null
     * 1. 有超过maximumPoolSize数量的工作线程(由于调用了setMaximumPoolSize)
     * 2. 线程池已经stop
     * 3. 线程池已经shutdown而且队列为空
     * 4. 工作线程等待任务超时啦，并且超时的工作线程被终止
     * @return  task或者null， 返回null表示这个worker要结束了，这种情况下workerCount-1
     */
    private Runnable getTask() {
    	//最后一次出列是否超时
        boolean timedOut = false; // Did the last poll() time out?

        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);

            // 线程池已经SHUTDOWN且 线程池已经stop或阻塞队列为空，返回null
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                decrementWorkerCount();
                return null;
            }

            int wc = workerCountOf(c);

            // 可以减少workerCount
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

            if ((wc > maximumPoolSize || (timed && timedOut))
                && (wc > 1 || workQueue.isEmpty())) {
                //减少一个workerCount
                if (compareAndDecrementWorkerCount(c)) {
                    return null;
                }
                continue;
            }

            try {
                Runnable r = timed ?
                    workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                    workQueue.take();
                if (r != null) {
                    return r;
                }
                // r == null, poll超时
                timedOut = true;
            } catch (InterruptedException retry) {
                timedOut = false;
            }
        }
    }

    /**
     * 核心方法
     * 反复从队列中获取任务并执行他们
     * getTask返回null时将结束
     *
     * @param w the worker
     */
    final void runWorker(Worker w) {
        Thread wt = Thread.currentThread();
        Runnable task = w.firstTask;
        w.firstTask = null;
        //tryRelease中会setState(0)，只有state>=0后才能中断
        w.unlock();
        boolean completedAbruptly = true;
        try {
            while (task != null || (task = getTask()) != null) {
                w.lock();
                // 如果线程池当前状态至少是stop，则设置中断标志;
                // 如果线程池当前状态是RUNNININ，则重置中断标志，重置后需要重新
                // 检查下线程池状态，因为当重置中断标志时候，Thread.interrupted()
                // 可能调用了线程池的shutdown方法，改变了线程池状态。
                if ((runStateAtLeast(ctl.get(), STOP) || (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP)))
                        && !wt.isInterrupted()) {
                    wt.interrupt();
                }
                try {
                    beforeExecute(wt, task);
                    Throwable thrown = null;
                    try {
                        task.run();
                    } catch (RuntimeException x) {
                        thrown = x; throw x;
                    } catch (Error x) {
                        thrown = x; throw x;
                    } catch (Throwable x) {
                        thrown = x; throw new Error(x);
                    } finally {
                        afterExecute(task, thrown);
                    }
                } finally {
                    task = null;
                    w.completedTasks++;
                    w.unlock();
                }
            }
            completedAbruptly = false;
        } finally {
            processWorkerExit(w, completedAbruptly);
        }
    }

    /**
     * @param corePoolSize 将会被保存在线程池中的核心线程数
     * @param maximumPoolSize 线程池中允许的最大线程数
     * @param keepAliveTime 当线程数多于核心线程池的数量，线程再被终止前最长等待任务的时间
     * @param unit keepAliveTime的单位
     * @param workQueue 阻塞队列
     * @throws IllegalArgumentException 如果有以下几种情况
     *         corePoolSize < 0
     *         keepAliveTime < 0
     *         maximumPoolSize <= 0
     *         maximumPoolSize < corePoolSize
     * @throws NullPointerException 如果workQueue
     *         或threadFactory或handler为 null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             Executors.defaultThreadFactory(), defaultHandler);
    }

    /**
     * @param corePoolSize 将会被保存在线程池中的核心线程数
     * @param maximumPoolSize 线程池中允许的最大线程数
     * @param keepAliveTime 当线程数多于核心线程池的数量，线程再被终止前最长等待任务的时间
     * @param unit keepAliveTime的单位
     * @param workQueue 阻塞队列
     * @param threadFactory 创建线程的工厂
     * @throws IllegalArgumentException 如果有以下几种情况
     *         corePoolSize < 0
     *         keepAliveTime < 0
     *         maximumPoolSize <= 0
     *         maximumPoolSize < corePoolSize
     * @throws NullPointerException 如果workQueue
     *         或threadFactory或handler为 null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             threadFactory, defaultHandler);
    }

    /**
     * @param corePoolSize 将会被保存在线程池中的核心线程数
     * @param maximumPoolSize 线程池中允许的最大线程数
     * @param keepAliveTime 当线程数多于核心线程池的数量，线程再被终止前最长等待任务的时间
     * @param unit keepAliveTime的单位
     * @param workQueue 阻塞队列
     * @param handler 当线程数超过maximumPoolSize时的处理方法
     * @throws IllegalArgumentException 如果有以下几种情况
     *         corePoolSize < 0
     *         keepAliveTime < 0
     *         maximumPoolSize <= 0
     *         maximumPoolSize < corePoolSize
     * @throws NullPointerException 如果workQueue
     *         或threadFactory或handler为 null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             Executors.defaultThreadFactory(), handler);
    }

    /**
     * @param corePoolSize 将会被保存在线程池中的核心线程数
     * @param maximumPoolSize 线程池中允许的最大线程数
     * @param keepAliveTime 当线程数多于核心线程池的数量，线程再被终止前最长等待任务的时间
     * @param unit keepAliveTime的单位
     * @param workQueue 阻塞队列
     * @param threadFactory 创建线程的工厂
     * @param handler 当线程数超过maximumPoolSize时的处理方法
     * @throws IllegalArgumentException 如果有以下几种情况
     *         corePoolSize < 0
     *         keepAliveTime < 0
     *         maximumPoolSize <= 0
     *         maximumPoolSize < corePoolSize
     * @throws NullPointerException 如果workQueue
     *         或threadFactory或handler为 null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory,
                              RejectedExecutionHandler handler) {
        if (corePoolSize < 0 ||
            maximumPoolSize <= 0 ||
            maximumPoolSize < corePoolSize ||
            keepAliveTime < 0)
            throw new IllegalArgumentException();
        if (workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    /**
     * 执行任务
     * 如果任务不能被执行可能是因为线程池已经关闭
     * 或者超过了最大maximumPoolSize时会执行给定的RejectedExecutionHandler策略
     * @param command 要被执行的任务
     * @throws NullPointerException 任务为null
     */
    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException();
        }
        /*
         * 3个步骤：
         * 1. 如果线程数小于corePoolSize,则执行addWorker方法创建新的线程执行任务
         * 2. 如果任务成功放入队列，我们仍需要一个双重校验去确认是否应该新建一个线程（因为可能存在有些线程在我们上次检查后死了） 
         * 或者 从我们进入这个方法后，pool被关闭了， 所以我们需要再次检查state，如果线程池停止了需要回滚入队列，如果池中没有线程了，新开启 一个线程
         * 3. 如果无法将任务入队列（可能队列满了）， 需要新开区一个线程（自己：往maxPoolSize发展）如果失败了，
         * 说明线程池shutdown 或者 饱和了，所以我们拒绝任务
         */
        int c = ctl.get();
        //如果线程数小于corePoolSize,则执行addWorker方法创建新的线程执行任务
        if (workerCountOf(c) < corePoolSize) {
            if (addWorker(command, true)) {
            	//添加成功返回
                return;
            }
            c = ctl.get();
        }

        /*---下面的workerCountOf已经 >= corePoolSize啦---*/

        //如果线程池处于RUNNING状态，且把提交的任务成功放入阻塞队列中
        if (isRunning(c) && workQueue.offer(command)) {
        	//双重校验去确认是否应该新建一个线程
            int recheck = ctl.get();
            //如果线程池已经被关闭，则将任务移出队列并执行饱和策略
            if (! isRunning(recheck) && remove(command)) {
                reject(command);
            } else if (workerCountOf(recheck) == 0) {
            	//线程池没有被关闭，创建一个新的工作线程
                addWorker(null, false);
            }
        } else if (!addWorker(command, false)) {
        	//执行饱和策略
            reject(command);
        }
    }

    /**
     * 关闭线程池，线程池不会接收新任务，但会处理阻塞队列中的任务
     */
    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            //权限检查
            checkShutdownAccess();
            /*
             * SHUTDOWN后将无法addWorker,且getTask在队列为空后会返回null,
             * getTask返回null后，runWorker会退出while循环
             */
            advanceRunState(SHUTDOWN);
            //中断所有空闲线程
            interruptIdleWorkers();
            //ScheduledThreadPoolExecutor中有用
            onShutdown(); 
        } finally {
            mainLock.unlock();
        }
        //尝试状态变为TERMINATED
        tryTerminate();
    }

    /**
     * 尝试停止所有活动的正在执行的任务，停止等待任务的处理，并返回正在等待被执行的任务列表
     * 这个方法不用等到正在执行的任务结束，要等待线程池终止可使用awaitTermination()
     * 除了尽力尝试停止运行中的任务，没有任何保证
     * 取消任务是通过Thread.interrupt()实现的，所以任何响应中断失败的任务可能永远不会结束
     * STOP状态
     */
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            checkShutdownAccess();
            //设置状态为stop后，getTask会直接返回null，runWorker会退出while循环
            advanceRunState(STOP);
            interruptWorkers();
            tasks = drainQueue();
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
        return tasks;
    }

    /**
     * 是否Shutdown
     */
    public boolean isShutdown() {
        return ! isRunning(ctl.get());
    }

    /**
     * @return 是否已经开始终止但没有终止完成
     */
    public boolean isTerminating() {
        int c = ctl.get();
        return ! isRunning(c) && runStateLessThan(c, TERMINATED);
    }

    /**
     * 是否已经终止完成
     */
    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(), TERMINATED);
    }

    /**
     * 等待线程池终止
     * @param timeout 时间长度
     * @param unit 单位
     */
    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
        	//自旋
            for (;;) {
                if (runStateAtLeast(ctl.get(), TERMINATED)) {
                	//已经TERMINATED
                    return true;
                }
                if (nanos <= 0) {
                    return false;
                }
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * gc
     */
    protected void finalize() {
        shutdown();
    }

    /**
     * 设置线程工厂
     */
    public void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null)
            throw new NullPointerException();
        this.threadFactory = threadFactory;
    }

    /**
     * @return 当前的线程工厂
     */
    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    /**
     * 设置当前任务执行不过来的饱和策略
     */
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null) {
            throw new NullPointerException();
        }
        this.handler = handler;
    }

    /**
     * 返回当前任务执行不过来的饱和策略
     * @return 当前的饱和策略
     */
    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    /**
     * 设置核心池的线程数量
     * @param corePoolSize 新的核心池数量
     * @throws IllegalArgumentException 如果 corePoolSize < 0
     */
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0) {
            throw new IllegalArgumentException();
        }
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        if (workerCountOf(ctl.get()) > corePoolSize) {
            interruptIdleWorkers();
        } else if (delta > 0) {
            // We don't really know how many new threads are "needed".
            // As a heuristic, prestart enough new workers (up to new
            // core size) to handle the current number of tasks in
            // queue, but stop if queue becomes empty while doing so.
            int k = Math.min(delta, workQueue.size());
            while (k-- > 0 && addWorker(null, true)) {
                if (workQueue.isEmpty()) {
                    break;
                }
            }
        }
    }

    /**
     * @return 核心池的线程数量
     */
    public int getCorePoolSize() {
        return corePoolSize;
    }

    /**
     * 启动一个core thread，如果线程池的数量大于corePoolSize将返回false
     */
    public boolean prestartCoreThread() {
        return workerCountOf(ctl.get()) < corePoolSize &&
            addWorker(null, true);
    }

    /**
     * 和prestartCoreThread差不多，但即使corePoolSize为0也会启动一个线程
     */
    void ensurePrestart() {
        int wc = workerCountOf(ctl.get());
        if (wc < corePoolSize) {
            addWorker(null, true);
        } else if (wc == 0) {
            addWorker(null, false);
        }
    }

    /**
     * Starts所有的core线程
     *
     * @return 多少个线程被启动
     */
    public int prestartAllCoreThreads() {
        int n = 0;
        while (addWorker(null, true)) {
            ++n;
        }
        return n;
    }

    /**
     * CoreThread是否会超时关闭
     */
    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    /**
     * @param value 是否需要超时终止核心线程
     * @throws IllegalArgumentException  如果keepAliveTime <= 0
     *
     * @since 1.6
     */
    public void allowCoreThreadTimeOut(boolean value) {
        if (value && keepAliveTime <= 0) {
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        }
        //如果allowCoreThreadTimeOut发生了改变
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            if (value) {
                interruptIdleWorkers();
            }
        }
    }

    /**
     * 设置线程池中允许的最大线程数量
     * @param maximumPoolSize 新的maximumPoolSize
     * @throws IllegalArgumentException 如果新的maximum小于等于0, 或
     *         小于核心池的容量
     */
    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
        if (workerCountOf(ctl.get()) > maximumPoolSize)
            interruptIdleWorkers();
    }

    /**
     * @return 线程池中允许的最大线程数量
     */
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    /**
     * 设置KeepAliveTime
     *
     * @param time 等待时间的长短.  
     * @param unit 时间的单位
     * @throws IllegalArgumentException if 时间小于0或时间等于0且核心池允许超时等待
     */
    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0) {
            throw new IllegalArgumentException();
        }
        if (time == 0 && allowsCoreThreadTimeOut()) {
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        }
        long keepAliveTime = unit.toNanos(time);
        long delta = keepAliveTime - this.keepAliveTime;
        this.keepAliveTime = keepAliveTime;
        if (delta < 0) {
            interruptIdleWorkers();
        }
    }

    /**
     * 超过核心池数量的线程存活的时间，在时间内没有任务则终止
     * @param unit 时间的单位
     * @return 多久的时间
     */
    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS);
    }

    /* User-level queue utilities */

    /**
     * @return 任务队列
     */
    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    /**
     * 从阻塞队列中移出任务
     *
     * @param task 要被移除的任务
     * @return 任务是否被移除
     */
    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate(); // In case SHUTDOWN and now empty
        return removed;
    }

    /**
     * 删除队列中所有的被取消的任务
     */
    public void purge() {
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> it = q.iterator();
            while (it.hasNext()) {
                Runnable r = it.next();
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled()) {
                    it.remove();
                }
            }
        } catch (ConcurrentModificationException fallThrough) {
            // Take slow path if we encounter interference during traversal.
            // Make copy for traversal and call remove for cancelled entries.
            // The slow path is more likely to be O(N*N).
            for (Object r : q.toArray())
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled()) {
                    q.remove(r);
                }
        }

        tryTerminate(); // In case SHUTDOWN and now empty
    }

    /* Statistics */

    /**
     * @return 线程池中线程的数量
     */
    public int getPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // Remove rare and surprising possibility of
            // isTerminated() && getPoolSize() > 0
            return runStateAtLeast(ctl.get(), TIDYING) ? 0
                : workers.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * @return 正在执行任务的线程数量
     */
    public int getActiveCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            int n = 0;
            for (Worker w : workers)
                if (w.isLocked()) {
                    ++n;
                }
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * @return 线程池最大时候的线程数量
     */
    public int getLargestPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * @return 任务的总数
     */
    public long getTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers) {
                n += w.completedTasks;
                if (w.isLocked()) {
                    ++n;
                }
            }
            return n + workQueue.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * @return 线程池完成任务的数量
     */
    public long getCompletedTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers) {
                n += w.completedTasks;
            }
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    
    public String toString() {
        long ncompleted;
        int nworkers, nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for (Worker w : workers) {
                ncompleted += w.completedTasks;
                if (w.isLocked())
                    ++nactive;
            }
        } finally {
            mainLock.unlock();
        }
        int c = ctl.get();
        String rs = (runStateLessThan(c, SHUTDOWN) ? "Running" :
                     (runStateAtLeast(c, TERMINATED) ? "Terminated" :
                      "Shutting down"));
        return super.toString() +
            "[" + rs +
            ", pool size = " + nworkers +
            ", active threads = " + nactive +
            ", queued tasks = " + workQueue.size() +
            ", completed tasks = " + ncompleted +
            "]";
    }

    /* Extension hooks */

    /**
     * 给子类扩展，runWorker
     */
    protected void beforeExecute(Thread t, Runnable r) { }

    /**
     * 留给子类扩展，runWorker
     */
    protected void afterExecute(Runnable r, Throwable t) { }

    /**
     * 子类扩展用
     */
    protected void terminated() { }

    /* Predefined RejectedExecutionHandlers */

    /**
     * 用调用者所在的线程来执行任务
     */
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code CallerRunsPolicy}.
         */
        public CallerRunsPolicy() { }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    /**
     * 直接抛出异常，默认的饱和策略
     */
    public static class AbortPolicy implements RejectedExecutionHandler {
        public AbortPolicy() { }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() +
                                                 " rejected from " +
                                                 e.toString());
        }
    }

    /**
     * 直接丢弃任务
     */
    public static class DiscardPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardPolicy}.
         */
        public DiscardPolicy() { }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        }
    }

    /**
     * 丢弃阻塞队列中靠最前的任务，并执行当前任务
     */
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        public DiscardOldestPolicy() { }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
}

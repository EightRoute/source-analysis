package com.webmvc.util.locks;

import java.util.Date;
import java.util.concurrent.TimeUnit;


public interface Condition {

    /**
     * 需要先得到锁
     * 如果一个线程调用await方法，该线程将会释放锁，构造成节点加入到等待队列并进入等待状态
     * 进入等待状态直到被通知或中断
     */
    void await() throws InterruptedException;

    /**
     * 需要先得到锁
     * 进入等待状态直到被通知，对中断不敏感
     */
    void awaitUninterruptibly();

    /**
     * 进入等待状态直到被通知或中断或超时
     */
    boolean await(long time, TimeUnit unit) throws InterruptedException;

    /**
     * 需要先得到锁
     * 进入等待状态直到被通知或中断或到某个时间
     */
    boolean awaitUntil(Date deadline) throws InterruptedException;

    /**
     * 需要先得到锁
     * 唤醒一个等待在Condition上的线程
     */
    void signal();

    /**
     * 需要先得到锁
     * 唤醒所有等待在Condition上的线程
     */
    void signalAll();
}

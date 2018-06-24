package com.webmvc.util.locks;

/**
 * 只有一个变量exclusiveOwnerThread表示占用该锁的线程
 */
public class AbstractOwnableSynchronizer  implements java.io.Serializable {


	private static final long serialVersionUID = 4289216149355104320L;


    protected AbstractOwnableSynchronizer() { }

    //占用该锁的线程
    private transient Thread exclusiveOwnerThread;


    protected final void setExclusiveOwnerThread(Thread thread) {
        exclusiveOwnerThread = thread;
    }

    protected final Thread getExclusiveOwnerThread() {
        return exclusiveOwnerThread;
    }

}

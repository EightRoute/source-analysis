package com.webmvc.util.threadpool;


/**
 * 饱和策略
 */
public interface RejectedExecutionHandler {
	 void rejectedExecution(Runnable r, ThreadPoolExecutor executor);
}

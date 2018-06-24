package com.webmvc.util.set;

import java.io.Serializable;
import java.util.Collection;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;

/**
 * 基本上是使用HashSet的方法，因为HashSet有一个构造函数可以传入参数dummy，
 * 传入后将会创建LinkedHashMap对象而不是HashMap对象
 */
public class LinkedHashSet<E> extends HashSet<E> implements Set<E>, Cloneable, Serializable {

	private static final long serialVersionUID = -2287895743147324610L;

    /**
     * @param initialCapacity 初始化容量
     * @param loadFactor 负载因子
     */
	public LinkedHashSet(int initialCapacity, float loadFactor) {
		super(initialCapacity, loadFactor, true);
	}

    /**
     * @param initialCapacity 初始化容量
     */
	public LinkedHashSet(int initialCapacity) {
		super(initialCapacity, .75f, true);
	}

    /**
     * 默认构造函数，初始化容量为16，负载因子为0.75
     */
	public LinkedHashSet() {
		super(16, .75f, true);
	}

    /**
     * 初始化是将一个集合的元素添加到Set中
     */
	public LinkedHashSet(Collection<? extends E> c) {
		super(Math.max(2*c.size(), 11), .75f, true);
		addAll(c);
	}

	@Override
	public Spliterator<E> spliterator() {
		return Spliterators.spliterator(this, Spliterator.DISTINCT | Spliterator.ORDERED);
	}

}

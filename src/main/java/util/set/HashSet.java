package com.webmvc.util.set;

import com.webmvc.util.map.HashMap;
import com.webmvc.util.map.LinkedHashMap;

import java.io.InvalidObjectException;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.*;

/**
 * HashSet采用HashMap实现，因为HashMap的key不能重复，
 * 所以将元素作为key保存到HashMap中可以保证元素不重复
 */
public class HashSet<E> implements Set<E>, Cloneable, Serializable{

	private static final long serialVersionUID = 2716631280267129568L;

	private transient HashMap<E, Object> map;
	private static final Object PRESENT = new Object();

    /**
     * 默认构造函数
     * 初始化容量为16
     * 初始化负载因子为0.75
     */
	public HashSet() {
		map = new HashMap<>();
	}

    /**
     * 初始化时将一个集合添加set中
     * @param c 要被添加进set的集合
     */
	public HashSet(Collection<E> c) {
		map = new HashMap<>();
		addAll(c);
	}

    /**
     * @param initialCapacity 初始化容量
     * @param loadFactor 负载因子
     */
	public HashSet(int initialCapacity, float loadFactor) {
		map = new HashMap<>(initialCapacity, loadFactor);
	}

    /**
     * @param initialCapacity 初始化容量
     */
	public HashSet(int initialCapacity) {
		map = new HashMap<>(initialCapacity);
	}

    /**
     * @param initialCapacity 初始化容量
     * @param loadFactor 负载因子
     * @param dummy 标识作用,有这个参数则map新建为LinkedHashMap
     */
	HashSet(int initialCapacity, float loadFactor, boolean dummy) {
		map = new LinkedHashMap<>(initialCapacity, loadFactor);
	}

    /**
     * 直接返回HashMap的key的迭代器
     * @return 迭代器
     */
	@Override
	public Iterator<E> iterator() {
        //如果为LinkedHashMap对象，则会调用相对应的方法
		return map.keySet().iterator();
	}

    /**
     * @return 有多少元素
     */
	@Override
	public int size() {
		return map.size();
	}

    /**
     * @return 是否为空
     */
	@Override
	public boolean isEmpty() {
		return map.isEmpty();
	}

    /**
     * @param o o
     * @return 是否包含对象o
     */
	@Override
	public boolean contains(Object o) {
		return map.containsKey(o);
	}

    private static <T> T[] finishToArray(T[] r, Iterator<?> it) {
        int i = r.length;
        while (it.hasNext()) {
            int cap = r.length;
            if (i == cap) {
                int newCap = cap + (cap >> 1) + 1;
                // 如果比MAX_ARRAY_SIZE还大则给MAX_ARRAY_SIZE
                if (newCap - MAX_ARRAY_SIZE > 0) {
                    newCap = hugeCapacity(cap + 1);
                }
                r = Arrays.copyOf(r, newCap);
            }
            r[i++] = (T)it.next();
        }
        return (i == r.length) ? r : Arrays.copyOf(r, i);
    }

    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
    private static int hugeCapacity(int minCapacity) {
        //溢出啦
        if (minCapacity < 0) {
            throw new OutOfMemoryError("Required array size too large");
        }
        return (minCapacity > MAX_ARRAY_SIZE) ? Integer.MAX_VALUE : MAX_ARRAY_SIZE;
    }

    /**
     * @return 将set输出为一个Object数组
     */
	@Override
	public Object[] toArray() {
        Object[] r = new Object[size()];
        Iterator<E> it = iterator();
        for (int i = 0; i < r.length; i++) {
            if (! it.hasNext()) {
                return Arrays.copyOf(r, i);
            }
            r[i] = it.next();
        }
        //如果还有下一个元素
        return it.hasNext() ? finishToArray(r, it) : r;
	}

    /**
     * 转化为数组
     * @param a 传入数组对象,转化的数组将传入该数组
     * @return set转换为的数组
     */
	@Override
	public <T> T[] toArray(T[] a) {
        int size = size();
        //如果a的长度不够则新建一个长度为size数组
        T[] r = a.length >= size ? a :
                (T[]) Array.newInstance(a.getClass().getComponentType(), size);
        Iterator<E> it = iterator();

        for (int i = 0; i < r.length; i++) {
            if (! it.hasNext()) {
                if (a == r) {
                    //给个null作为结束的标识
                    r[i] = null;
                } else if (a.length < i) {
                    return Arrays.copyOf(r, i);
                } else {
                    System.arraycopy(r, 0, a, 0, i);
                    if (a.length > i) {
                        a[i] = null;
                    }
                }
                return a;
            }
            r[i] = (T)it.next();
        }
        //如果还有下一个元素
        return it.hasNext() ? finishToArray(r, it) : r;
	}

    /**
     * 添加一个元素e到set中
     * @param e 要被添加的元素
     * @return 是否添加成功
     */
	@Override
	public boolean add(E e) {
		return map.put(e, PRESENT)==null;
	}

	/**
	 * 添加一个集合的元素到set中
	 */
	@Override
	public boolean addAll(Collection<? extends E> c) {
		boolean modified = false;
		for (E e : c) {
			if (add(e)) {
				modified = true;
			}
		}
		return modified;
	}

    /**
     * 删除一个元素o
     * @param o o
     * @return 是否成功删除
     */
	@Override
	public boolean remove(Object o) {
		return map.remove(o)==PRESENT;
	}

    /**
     * 是否包含一个集合c的所有元素
     * @param c c
     * @return true全部包含 false不全部包含
     */
	@Override
	public boolean containsAll(Collection<?> c) {
        for (Object e : c)
            if (!contains(e)) {
                return false;
            }
        return true;
	}


    /**
     * 除了包含在集合c中的元素，其他的全部删掉
     * @param c 集合c
     * @return 是否成功
     */
	@Override
	public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;
        Iterator<E> it = iterator();
        while (it.hasNext()) {
            if (!c.contains(it.next())) {
                it.remove();
                modified = true;
            }
        }
        return modified;
	}

    /**
     * 删除包含在集合c中的全部元素
     * @param c 集合c
     * @return 是否成功
     */
	@Override
	public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        boolean modified = false;
        Iterator<?> it = iterator();
        while (it.hasNext()) {
            if (c.contains(it.next())) {
                it.remove();
                modified = true;
            }
        }
        return modified;
	}

    /**
     * 清空
     */
	@Override
	public void clear() {
		map.clear();
	}

	@Override
	protected Object clone() throws CloneNotSupportedException {
		try {
			HashSet<E> newSet = (HashSet<E>) super.clone();
			newSet.map = (HashMap<E, Object>) map.clone();
			return newSet;
		} catch (CloneNotSupportedException e) {
			throw new InternalError(e);
		}
	}

	//序列化
	private void writeObject(java.io.ObjectOutputStream s)
			throws java.io.IOException {
		// Write out any hidden serialization magic
		s.defaultWriteObject();

		// Write out HashMap capacity and load factor
		s.writeInt(map.capacity());
		s.writeFloat(map.loadFactor());

		// Write out size
		s.writeInt(map.size());

		// Write out all elements in the proper order.
		for (E e : map.keySet())
			s.writeObject(e);
	}

	//反序列化
	private void readObject(java.io.ObjectInputStream s)
			throws java.io.IOException, ClassNotFoundException {
		// Read in any hidden serialization magic
		s.defaultReadObject();

		// Read capacity and verify non-negative.
		int capacity = s.readInt();
		if (capacity < 0) {
			throw new InvalidObjectException("Illegal capacity: " +
					capacity);
		}

		// Read load factor and verify positive and non NaN.
		float loadFactor = s.readFloat();
		if (loadFactor <= 0 || Float.isNaN(loadFactor)) {
			throw new InvalidObjectException("Illegal load factor: " +
					loadFactor);
		}

		// Read size and verify non-negative.
		int size = s.readInt();
		if (size < 0) {
			throw new InvalidObjectException("Illegal size: " +
					size);
		}

		// Set the capacity according to the size and load factor ensuring that
		// the HashMap is at least 25% full but clamping to maximum capacity.
		capacity = (int) Math.min(size * Math.min(1 / loadFactor, 4.0f),
				HashMap.MAXIMUM_CAPACITY);

		// Create backing HashMap
		map = (((HashSet<?>)this) instanceof LinkedHashSet ?
				new LinkedHashMap<E,Object>(capacity, loadFactor) :
				new HashMap<E,Object>(capacity, loadFactor));

		// Read in all elements in the proper order.
		for (int i=0; i<size; i++) {
			@SuppressWarnings("unchecked")
			E e = (E) s.readObject();
			map.put(e, PRESENT);
		}
	}

	public Spliterator<E> spliterator() {
		return new HashMap.KeySpliterator<E,Object>(map, 0, -1, 0, 0);
	}
}

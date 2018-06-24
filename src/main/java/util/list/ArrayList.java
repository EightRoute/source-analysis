package com.webmvc.util.list;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.Consumer;

/**
 * 用来学习java.util.ArrayList<E>
 * Created by sgz
 * 2018/2/10 19:02
 * RandomAccess属于一种标记接口,一般为数组实现
 */
public class  ArrayList<E> implements List<E>, Serializable, RandomAccess, Cloneable{

    private static final long serialVersionUID = -8938796252048773961L;

    /*
     * 初始化时数组中默认元素的数量
     */
    private static final int DEFAULT_CAPACITY = 10;
    
    protected transient int modCount = 0;

    private static final Object[] EMPTY_ELEMENTDATA = {};
    private static final Object[] DEFAULTCAPACITY_EMPTY_CAPACITY = {};

    /*
     * 用来存放集合元素的数组
     */
    Object[] elementData;

    /*
     *  集合中元素的数量
     */
    private int size;
    
    /*
     * 如果太大有的虚拟机可能会抛出OutOfMemoryError
     */
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * 带有默认初始数量的构造器
     * 初始化数量必须不小于0
     * @param initialCapacity
     */
    public ArrayList(int initialCapacity) {
        if (initialCapacity > 0) {
            this.elementData = new Object[initialCapacity];
        } else if (initialCapacity == 0) {
            this.elementData = EMPTY_ELEMENTDATA;
        } else {
            throw new IllegalArgumentException("初始化数量必须不小于0,当前值为: " + initialCapacity);
        }
    }

    /**
     * 不带初始化数量,数量将为默认的10
     */
    public ArrayList() {
        this.elementData = DEFAULTCAPACITY_EMPTY_CAPACITY;
    }

    /**
     * 使用一个集合初始化,集合中必须为泛型E或E的子类
     * @param c 元素要被复制到List中的集合
     */
    public ArrayList(Collection<? extends E> c) {
        elementData = c.toArray();
        if ((size = elementData.length) != 0) {
        	/*数组能够记住它的元素类型,如果存入一个错误类型会抛出ArrayStoreException*/
        	if (elementData.getClass() != Object[].class) {
        		elementData = Arrays.copyOf(elementData, size, Object[].class);
        	}
        } else {
        	elementData = EMPTY_ELEMENTDATA;
        }
    }


    /**
     * 压缩数组的容量为实际存放元素的数量
     */
    public void trimToSize() {
    	if (size < elementData.length) {
    		elementData = (size == 0) ? EMPTY_ELEMENTDATA : Arrays.copyOf(elementData, size);
    	}
    }
    
    /**
 	 * 保证数组尺寸足够
     */
    public void ensureCapacity(int minCapacity) {
    	int minExpend = (elementData != DEFAULTCAPACITY_EMPTY_CAPACITY) ? 0 : DEFAULT_CAPACITY;
    	if (minCapacity > minExpend) {
    		ensureExplicitCapacity(minCapacity);
    	}
    }
    
    private void ensureCapacityInternal(int minCapacity) {
    	/*如果为首次扩容*/
    	if (elementData == DEFAULTCAPACITY_EMPTY_CAPACITY) {
    		minCapacity = Math.max(minCapacity, DEFAULT_CAPACITY);
    	}
    	ensureExplicitCapacity(minCapacity);
    }
    
    private void ensureExplicitCapacity(int minCapacity) {
    	modCount++;
    	/*如果数组的容量小于设置的最小的容量,则扩容*/
    	if (minCapacity - elementData.length > 0) {
    		grow(minCapacity);
    	}
    }
    /*
     * 数组扩容
     */
    private void grow(int minCapacity) {
    	int oldCapacity = elementData.length;
    	/*每次大约扩容1/2*/
    	int newCapacity = oldCapacity + (oldCapacity >> 1);
    	if (newCapacity < minCapacity) {
    		newCapacity = minCapacity;
    	}
    	if (newCapacity > MAX_ARRAY_SIZE) {
    		newCapacity = hugeCapacity(minCapacity);
    	}
    	elementData = Arrays.copyOf(elementData, newCapacity);
    }
    
    private static int hugeCapacity(int minCapacity) {
    	if (minCapacity < 0) {
    		/*超出Integer最大的值*/
    		throw new OutOfMemoryError("超出Integer最大的值");
    	}
    	return (minCapacity > MAX_ARRAY_SIZE) ? Integer.MAX_VALUE : MAX_ARRAY_SIZE;
    }

    /**
     * 获取数组中元素的数量
     */
    @Override
    public int size() {
        return size;
    }

    /**
     * 判断List是否为空
     */
    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * 判断List是否包含某个元素
     */
    @Override
    public boolean contains(Object o) {
        return indexOf(o) >= 0;
    }

   

    /**
     * 将List转为数组
     */
    @Override
    public Object[] toArray() {
        return Arrays.copyOf(elementData, size);
    }

    /**
     * 将List转换为T[]的数组
     * 如果传入的数组的尺寸小于List的数量将会新建一个新的数组对象
     * 如果传入的数组的length大于size将不会创建新的数组
     */
    @SuppressWarnings("unchecked")
	@Override
    public <T> T[] toArray(T[] a) {
    	/*如果传入的数组的尺寸小于List的数量将会新建一个新的数组对象*/
    	if (a.length < size) {
    		return (T[]) Arrays.copyOf(elementData, size, a.getClass());
    	} 
    	System.arraycopy(elementData, 0, a, 0, size);
    	if (a.length > size) {
    		/*个人感觉只能做个标识作用 ,而且还不保险*/
    		a[size] = null;
    	}
        return a;
    }
    
    
    @SuppressWarnings("unchecked")
	E elementData(int index){
    	return (E) elementData[index];
    }
    
    
    /*
     * 判断数组是否越界
     */
    private void rangeCheck(int index) {
    	if (index > size) {
    		throw new IndexOutOfBoundsException("数组越界,数组最大下标为:" + size + ",当前取值为:" + index);
    	}
    }

    /**
     * 往集合中添加元素
     */
    @Override
    public boolean add(E e) {
    	ensureCapacityInternal(size + 1);
    	elementData[size++] = e;
        return true;
    }

    /**
     * 删除第一个出现的对象 
     */
    @Override
    public boolean remove(Object o) {
    	if (o == null) {
    		for (int i = 0; i < size; i++) {
    			if (elementData[i] == null) {
    				fastRemove(i);
    				return true;
    			}
    		}
    	} else {
    		for (int i = 0; i < size; i++) {
    			if (elementData[i] == o) {
    				fastRemove(i);
    				return true;
    			}
    		}
    	}
        return false;
    }
    
    private void fastRemove(int index) {
    	modCount++;
    	int numMoved = size - index -1;
    	if (numMoved > 0) {
    		System.arraycopy(elementData, index + 1, elementData,
    				index, numMoved);
    		elementData[--size] = null;
    	}
    }

    @Override
    public boolean containsAll(Collection<?> c) {
    	for (Object o : c) {
    		if (!contains(o)) {
    			return false;
    		}
    	}
        return true;
    }

    /**
     * 往集合中添加集合
     */
    @Override
    public boolean addAll(Collection<? extends E> c) {
    	Object[] a = c.toArray();
    	int numNew = a.length;
    	ensureCapacityInternal(size + numNew);
    	/*将数组拼接在后面*/
    	System.arraycopy(a, 0, elementData, size, numNew);
    	size += numNew;
        return numNew != 0;
    }

    /**
     * 添加一个集合到List中，并指定开始的下标index
     * @param index 指定开始的下标
     */
    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
    	rangeCheckForAdd(index);
    	
    	Object[] a = c.toArray();
    	int numNew = a.length;
    	ensureCapacityInternal(numNew + size);
    	
    	int numMoved = size - index;
    	/*如果在中间需要让出位置*/
    	if (numMoved > 0) {
    		System.arraycopy(elementData, index, elementData,
    				index + numNew, numMoved);
    	}
    	
    	System.arraycopy(a, 0, elementData, index, numNew);
    	size += numNew;
        return numNew != 0;
    }

    /**
     * 删除参数中所有的元素
     */
    @Override
    public boolean removeAll(Collection<?> c) {
    	Objects.requireNonNull(c);
        return batchRemove(c, false);
    }
    
    /**
     * 批量删除
     * @param c 
     * @param complement 如果为true则删除不包含在c中的元素,
     * 如果为false则删除包含在c中的元素
     */
    private boolean batchRemove(Collection<?> c, boolean complement) {
    	final Object[] elementData = this.elementData;
    	int r = 0;
    	int w = 0;
    	boolean modified = false;
    	try {
    		for ( ; r < size; r++) {
    			if (c.contains(elementData[r]) == complement) {
    				elementData[w++] = elementData[r];
    			}
    		}
    	} finally {
    		if (r != size) {
    			System.arraycopy(elementData, r, elementData,
    					w, size - r);
    			w += size - r; 
    		}
    		if (w != size) {
    			for (int i = w; i < size; i++) {
    				elementData[i] = null;
    			}
    			modCount += size - w;
    			size = w;
    			modified = true;
    		}
    	}
    	return modified;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
    	Objects.requireNonNull(c);
        return batchRemove(c, true);
    }

    /**
     * 清空集合
     */
    @Override
    public void clear() {
    	for (int i = 0; i < size; i++) {
    		elementData[i] = null;
    	}
    	size = 0;
    }

    /**
     * 根据下标获取集合中的元素
     */
    @Override
    public E get(int index) {
    	rangeCheck(index);
        return elementData(index);
    }

    /**
     * 根据下标设置集合中的元素,并且返回原值
     */
    @SuppressWarnings("unchecked")
	@Override
    public E set(int index, E element) {
    	rangeCheck(index);
    	E oldValue = (E) elementData[index];
    	elementData[index] = element;
        return oldValue;
    }

    private void rangeCheckForAdd(int index) {
    	if (index > size || index < 0) {
    		throw new IndexOutOfBoundsException("数组下标越界,错误的下标为:" + index + ",最小下标应为0,最大为:" +size);
    	}
    }
    
    /**
     * 将元素添加到一个指定的位置
     */
    @Override
    public void add(int index, E element) {
    	rangeCheckForAdd(index);
    	ensureCapacityInternal(size++);
    	/*将数组index下标后面的元素全部后移一位*/
    	System.arraycopy(elementData, index, elementData, index + 1, size - index);
    	elementData[index] = element;
    	size++;
    }

    /**
     * 根据位置删除元素
     */
    @Override
    public E remove(int index) {
    	rangeCheck(index);
    	modCount++;
    	E oldValue = elementData(index);
    	
    	int numMoved = size - index -1;
    	if (numMoved > 0) {
    		System.arraycopy(elementData, index + 1,
    				elementData, index, numMoved);
    	}
    	/*去掉引用,让jvm可以回收*/
    	elementData[--size] = null;
        return oldValue;
    }

    /**
     * 返回元素首次出现的下标,如果不包含则返回-1
     */
    @Override
    public int indexOf(Object o) {
    	if (o == null) {
    		for (int i = 0; i < size; i++) {
    			if (elementData[i] == null) {
    				return i;
    			}
    		}
    	} else {
    		for (int i = 0; i < size; i++) {
    			/*为啥需要重写equals方法*/
    			if (o.equals(elementData[i])) {
    				return i;
    			}
    		}
    	}
        return -1;
    }

    /**
     * 返回元素最后一次出现的下标,如果不包含则返回-1
     */
    @Override
    public int lastIndexOf(Object o) {
    	if (o == null) {
    		for (int i = size -1; i >= 0; i--) {
    			if (elementData[i] == null) {
    				return i;
    			}
    		}
    	} else {
    		for (int i = size -1; i >= 0; i--) {
    			/*为啥需要重写equals方法*/
    			if (o.equals(elementData[i])) {
    				return i;
    			}
    		}
    	}
        return -1;
    }

    /**
     * 返回一个克隆的对象
     */
    @Override
    public Object clone() {
    	try {
			ArrayList<?> list = (ArrayList<?>) super.clone();
			list.elementData = Arrays.copyOf(elementData, size);
			list.modCount = 0;
			return list;
		} catch (CloneNotSupportedException e) {
			throw new InternalError(e);
		}
    }
    
    /**
     * 返回迭代对象
     */
    @Override
    public Iterator<E> iterator() {
        return new Itr();
    }
    
    private class Itr implements Iterator<E> {
    	int cursor; //下一个元素的坐标
    	int lastRet = -1; //最后一个被返回的元素坐标
    	int expectedModCount = modCount;

    	/**
    	 * 是否还有下一个值
    	 */
		@Override
		public boolean hasNext() {
			return cursor != size;
		}

		/**
		 * 取下一个值
		 */
		@SuppressWarnings("unchecked")
		@Override
		public E next() {
			checkForComodification();
			int i = cursor;
			if (i >= size) {
				throw new NoSuchElementException("遍历时index溢出");
			}
			Object[] elementData = ArrayList.this.elementData;
			if (size >= elementData.length) {
				throw new ConcurrentModificationException("遍历时index溢出");
			}
			cursor = i + 1;			
			return (E) elementData[lastRet = i];
		}
		
		/**
		 * 删除最后一个迭代的目标
		 */
		@Override
		public void remove() {
			/*还没有开始操作*/
			if (lastRet < 0) {
				throw new IllegalStateException();
			}
			checkForComodification();
			
			ArrayList.this.remove(lastRet);
			cursor = lastRet;
			lastRet = -1;
			expectedModCount = modCount;
		}
		
		/**
		 * Lambda表达式结合迭代器进行遍历
		 */
		@SuppressWarnings("unchecked")
		@Override
		public void forEachRemaining(Consumer<? super E> action) {
			Objects.requireNonNull(action);
			 final int size = ArrayList.this.size;
			 int i = cursor;
			 if (i >= size) {
	            return;
	         }
			 final Object[] elementData = ArrayList.this.elementData;
	         if (i >= elementData.length) {
	            throw new ConcurrentModificationException();
	         }
	         while (i != size && modCount == expectedModCount) {
	        	 action.accept((E) elementData[i++]);
	         }
	         cursor = i;
	            lastRet = i - 1;
	            checkForComodification();
		}
		
		final void checkForComodification() {
			if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
			}
		}
    	
    }
    
    @Override
    public ListIterator<E> listIterator() {
        return new ListItr(0);
    }
    
    @Override
    public ListIterator<E> listIterator(int index) {
        if (index > size || index < 0) {
        	throw new IndexOutOfBoundsException("下标不正确");
        }
        return new ListItr(index);
    }
   

    
    private class ListItr extends Itr implements ListIterator<E> {
    	public ListItr(int index) {
			super();
			cursor = index;
		}

    	/**
    	 * 是否有上一个
    	 */
		@Override
		public boolean hasPrevious() {
			return cursor != 0;
		}

		/**
		 * @return 返回上一个元素
		 */
		@SuppressWarnings("unchecked")
		@Override
		public E previous() {
			checkForComodification();
            int i = cursor - 1;
            if (i < 0)
                throw new NoSuchElementException("没有这个元素");
            Object[] elementData = ArrayList.this.elementData;
            if (i >= elementData.length)
                throw new ConcurrentModificationException("并发操作异常");
            cursor = i;
            return (E) elementData[lastRet = i];
		}

		@Override
		public int nextIndex() {
			/*将要取而还没取     _ _ _ previous next _ _ _*/
			return cursor;
		}

		@Override
		public int previousIndex() {
			return cursor - 1;
		}

		/**
		 * 将当前坐标的元素设为
		 */
		@Override
		public void set(E e) {
			if (lastRet < 0)
                throw new IllegalStateException();
            checkForComodification();

            try {
                ArrayList.this.set(lastRet, e);
            } catch (IndexOutOfBoundsException ex) {
                throw new ConcurrentModificationException("并发操作异常");
            }
		}

		/**
		 * 添加一个元素到当前坐标下
		 */
		@Override
		public void add(E e) {
			checkForComodification();

            try {
                int i = cursor;
                ArrayList.this.add(i, e);
                cursor = i + 1;
                lastRet = -1;
                expectedModCount = modCount;
            } catch (IndexOutOfBoundsException ex) {
                throw new ConcurrentModificationException("并发操作异常");
            }
		}
    	
    }
    
    
    /**
     * 返回list的一部分的视图
     * **************
     *     *****
     * **************
     */
    @Override
    public List<E> subList(int fromIndex, int toIndex) {
    	List<E> list = new ArrayList<E>();
    	for (int i = 0; i < size; i++) {
    		if (i < toIndex && i >= fromIndex) {
    			list.add(elementData(i));
    		}
    	}
        return list;
    }
    
    /*
     * 在序列化过程中，虚拟机会试图调用对象类里的writeObject() 和readObject()，
	 * 进行用户自定义的序列化和反序列化，如果没有则调用ObjectOutputStream.defaultWriteObject()
	 * 和ObjectInputStream.defaultReadObject()
     */
    private void writeObject(java.io.ObjectOutputStream s)
            throws java.io.IOException{
        // Write out element count, and any hidden stuff
        int expectedModCount = modCount;
        s.defaultWriteObject();

        // Write out size as capacity for behavioural compatibility with clone()
        s.writeInt(size);

        // Write out all elements in the proper order.
        for (int i=0; i<size; i++) {
            s.writeObject(elementData[i]);
        }

        if (modCount != expectedModCount) {
            throw new ConcurrentModificationException();
        }
    }
    
    private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
    	elementData = EMPTY_ELEMENTDATA;

        // Read in size, and any hidden stuff
        s.defaultReadObject();

        // Read in capacity
        s.readInt(); // ignored

        if (size > 0) {
            // be like clone(), allocate array based upon size not capacity
            ensureCapacityInternal(size);

            Object[] a = elementData;
            // Read in all elements in the proper order.
            for (int i=0; i<size; i++) {
                 a[i] = s.readObject();
            }
        }
    }
}

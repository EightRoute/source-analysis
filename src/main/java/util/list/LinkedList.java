package com.webmvc.util.list;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
/**
 * @author sgz
 */
public class LinkedList<E> implements List<E>, Deque<E>, Serializable {

	private static final long serialVersionUID = 1057766311597491141L;

	transient int size = 0;
	
	transient int modCount = 0;
	
	/*第一个node*/
	transient Node<E> first;
	/*最后一个node*/
	transient Node<E> last;
	
	public LinkedList() {
		
	}
	
	public LinkedList(Collection<? extends E> c) {
		this();
		addAll(c);
	}
	
	
	/*LinkedList是由一个一个Node连接起来的链表*/
	private static class Node<E> {
		E item; //当前的Node的element
		Node<E> prev; //上一个Node的引用
		Node<E> next; //下一个Node的引用
		
		Node(Node<E> prev, E element, Node<E> next) {
			this.item = element;
			this.next = next;
			this.prev = prev;
		}
	}
	
	/*将e作为首节点*/
	private void linkFirst(E e) {
		final Node<E> f = first;
		final Node<E> newFirst = new Node<>(null, e, f);
		first = newFirst;
		if (f == null) {
			last = newFirst;
		} else {
			f.prev = newFirst;
		}
		modCount++;
		size++;
	}
	
	/*将e作为末节点*/
	private void linkLasted(E e) {
		final Node<E> l = last;
		final Node<E> newLast = new Node<>(l, e, null);
		last = newLast;
		if (l == null) {
			first = newLast;
		} else {
			l.next = newLast;
		}
		modCount++;
		size++;
	}
	
	/*将e插入到succ前面,且succ不为null*/
	private void linkBefore(E e, Node<E> succ) {
		final Node<E> prev = succ.prev;//获取succ的前一个Node
		final Node<E> newNode = new Node<>(prev, e, succ);
		succ.prev = newNode;
		if (prev == null) {
			first = prev;
		} else {
			prev.next = newNode;
		}
		modCount++;
		size++;
	}
	
	
	/*去掉首节点f,且假设f不为空*/
	private E unlinkFirst(Node<E> f) {
		final E element = f.item;
		final Node<E> next = f.next;
		f.item = null;
		f.next = null; //有利于gc
		if (next == null) {
			last = null;
		} else {
			next.prev = null;
		}		
		size--;
		modCount++;
		return element;
	}
	
	/*去掉末节点l,且假设l不为空*/
	private E unlinkLast(Node<E> l) {
		final Node<E> prev = l.prev;
		final E element = l.item;
		l.prev = null;
		l.item = null;
		if (prev == null) {
			first = null;
		} else {
			prev.next = null;
		}
		size--;
		modCount++;
		return element;
	}
	
	
	/*去掉节点x,且假设x不为空*/
	private E unlink(Node<E> x) {
		final E element = x.item;
		final Node<E> prev = x.prev;
		final Node<E> next = x.next;
		
		if (prev == null) {
			first = next;
		} else {
			prev.next = next; //断开中间的连接
			x.prev = null; //gc
		}
		
		if (next == null) {
			last = prev;
		} else {
			next.prev = prev;
			x.next = null; //gc
		}
		x.item = null; //gc
		size--;
		modCount++;
		return element;
	}
	
	
	/**
	 * 有多少个元素
	 */
	@Override
	public int size() {
		return size;
	}

	/**
	 * 是否为空
	 */
	@Override
	public boolean isEmpty() {
		return size == 0;
	}

	/**
	 * 是否包含对象o
	 */
	@Override
	public boolean contains(Object o) {
		return indexOf(o) != -1;
	}

	@Override
	public Iterator<E> iterator() {	
		return listIterator(0);
	}

	
	/**
	 * 转为数组输出
	 */
	@Override
	public Object[] toArray() {
		//arraylist可能比size少
		Object[] r = new Object[size];
		Iterator<E> it = iterator();
		int i = 0;
		while (it.hasNext()) {
			r[i] = it.next();
			i++;
		}
		return Arrays.copyOf(r, i);
	}

	
	/**
	 * 转化为数组
	 * @param a 传入数组对象,转化的数组将传入该对象
	 */
	@SuppressWarnings("unchecked")
	@Override
	public <T> T[] toArray(T[] a) {
		if (a.length < size) {
			a = (T[]) Array.newInstance
					(a.getClass().getComponentType(), size);
		}
		int i = 0;
		Object[] result = a;
		for (Node<E> x = first; x != null; x = x.next) {
			result[i++] = x.item; //引用传递
		}
		
		if (a.length > size) {
			a[size] = null;
		}
		return a;
	}

	/**
	 * 在末尾添加一个element
	 */
	@Override
	public boolean add(E e) {
		linkLasted(e);
		return true;
	}

	/**
	 * 删除首次出现且为参数o的element
	 */
	@Override
	public boolean remove(Object o) {
		if (o == null) {
			for (Node<E> x = first; x != null; x = x.next) {
				if (x.item == null) {
					unlink(x);
					return true;
				}
			}
		} else {
			for (Node<E> x = first; x != null; x = x.next) {
				if (x.item.equals(o)) {
					unlink(x);
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * List中是否包含一个集合
	 */
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
	 * 在List后面添加一个集合
	 */
	@Override
	public boolean addAll(Collection<? extends E> c) {
		return addAll(size, c);
	}

	
	/**
	 * 在指定的地方加入一个新的集合
	 * @param index 插入的地址
	 * @param c 要被插入的集合
	 */
	@SuppressWarnings("unchecked")
	@Override
	public boolean addAll(int index, Collection<? extends E> c) {
		checkPositionIndex(index);
		
		Object[] a = c.toArray();
		int numNew = a.length;
		if (numNew == 0) {
			return false; //需要添加的集合为空
		}
		
		/*新链表和老链表要对接的两个Node*/
		Node<E> pred, succ;
		if (index == size) {
			succ = null;
			pred = last;
		} else {
			succ = node(index);
			pred = succ.prev;
		}
		/*创建新链表*/
		for (Object o : c) {
			E e = (E) o;
			Node<E> newNode = new Node<>(pred, e, null);
			if (pred == null) {
				first = newNode;
			} else {
				pred.next = newNode;
			}
			pred = newNode;
		}
		
		if (succ == null) {
			last = pred;
		} else {
			/*拼接两个链表*/
			succ.prev = last;
			pred.next = succ;
		}
		
		size += numNew;
		modCount++;
		return true;
	}
	
	/*根据位置寻找Node*/
	private Node<E> node(int index) {
		/*链表不支持随机取值,所以需要找一个近的头开始遍历*/
		if (index < (size >> 1)) {
			Node<E> x = first;
			for (int i = 0; i < index; i++) {
				x = x.next;
			}
			return x;
		} else {
			Node<E> x = last;
			for (int i = size - 1; i > index; i--) {
				x = x.prev;
			}
			return x;
		}
	}

	
	/**
	 * 删除所有在参数中出现的元素
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
	 * 删除所有元素除了参数中出现的
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
	
	
	private void checkElementIndex(int index) {
		if (!isElementIndex(index)) {
			throw new IndexOutOfBoundsException("坐标超出范围");
		}
	}

	private void checkPositionIndex(int index) {
        if (!isPositionIndex(index)) {
            throw new IndexOutOfBoundsException("坐标超出范围");
        }
    }
	
	
	/*因为要反过来迭代所以index <= size*/
	private boolean isPositionIndex(int index) {
        return index >= 0 && index <= size;
    }
	
	
	/**
	 * 清空List
	 */
	@Override
	public void clear() {
		/*为了gc*/
		for (Node<E> x = first; x != null; ) {
			Node<E> next = x.next;
			x.item = null;
			x.prev = null;
			x.next = null;
			x = next;
		}
		first = last = null;
		size = 0; 
		modCount++;
	}

	
	/**
	 * 根据位置获取元素
	 */
	@Override
	public E get(int index) {
		checkElementIndex(index);
		return node(index).item;
	}

	
	/**
	 * 更具位置修改元素
	 */
	@Override
	public E set(int index, E element) {
		checkElementIndex(index);
		Node<E> node = node(index);
		E oldValue = node.item;
		node.item = element;
		return oldValue;
	}

	/**
	 * 将元素放到指定位置
	 */
	@Override
	public void add(int index, E element) {
		checkPositionIndex(index);
		if (index == size) {
			linkLasted(element);
		} else {
			/*linkBefore不能插入到最后*/
			linkBefore(element, node(index));
		}
		
	}

	/**
	 * 删除指定位置的元素
	 */
	@Override
	public E remove(int index) {
		checkElementIndex(index);
		return unlink(node(index));
	}

	/**
	 * 返回链表中第一个出现o的位置,如不存在则为-1
	 */
	@Override
	public int indexOf(Object o) {
		int index = 0;
		if (o == null) {
			for (Node<E> x = first; x != null; x = x.next) {
				if (x.item == null) {
					return index;
				}
				index++;
			}
		} else {
			for (Node<E> x = first; x != null; x = x.next) {
				if (o.equals(x.item)) {
					return index;
				}
				index++;
			}
		}
		return -1;
	}

	/**
	 * 返回链表中最后一次出现o的位置,如不存在则为-1
	 */
	@Override
	public int lastIndexOf(Object o) {
		int index = size;
		if (o == null) {
			for (Node<E> x = last; x != null; x = x.prev) {
				index--; //0开始
				if (null == o) {
					return index;
				}				
			}
		} else {
			for (Node<E> x = last; x != null; x = x.prev) {
				index--;
				if (o.equals(x.item)) {
					return index;
				}		
			}
		}
		return -1;
	}

	@Override
	public ListIterator<E> listIterator() {
		return listIterator(0);
	}

	@Override
	public ListIterator<E> listIterator(int index) {
		checkPositionIndex(index);
		return new ListItr(index);
	}
	
	private class ListItr implements ListIterator<E> {
		private Node<E> lastReturned;
        private Node<E> next;
        private int nextIndex;
        private int expectedModCount = modCount;
		
		public ListItr(int index) {
			/*开始的位置*/
			next = (index == size) ? null : node(index);
	        nextIndex = index;
		}
		
		/**
		 * 是否有下一个
		 */
		@Override
		public boolean hasNext() {
			return nextIndex < size;
		}

		final void checkForComodification() {
            if (modCount != expectedModCount)
                throw new ConcurrentModificationException();
        }
		
		/**
		 * 取下一个
		 */
		@Override
		public E next() {
			checkForComodification();
            if (!hasNext())
                throw new NoSuchElementException("没有下一个啦");

            lastReturned = next;
            next = next.next;
            nextIndex++;
            return lastReturned.item;
		}

		/**
		 * 是否有上一个
		 */
		@Override
		public boolean hasPrevious() {
			return nextIndex > 0;
		}

		/**
		 * 取上一个
		 */
		@Override
		public E previous() {
			checkForComodification();
            if (!hasPrevious())
                throw new NoSuchElementException("没有上一个");
            lastReturned = next = (next == null) ? last : next.prev;
            nextIndex--;
            return lastReturned.item;
		}

		/**
		 * 下一个的地址
		 */
		@Override
		public int nextIndex() {
			return nextIndex;
		}

		/**
		 * 上一个的地址
		 */
		@Override
		public int previousIndex() {
			return nextIndex - 1;
		}

		/**
		 * 删除最后被迭代出的元素
		 */
		@Override
		public void remove() {
			checkForComodification();
			if (lastReturned == null) {
				throw new IllegalStateException("还没有开始迭代");
			}
			Node<E> lastNext = lastReturned.next;
			unlink(lastReturned);
			if (next == lastReturned) {
				next = lastNext;
			} else {
				nextIndex--;
			}
			lastReturned = null;
			expectedModCount++;
		}

		/**
		 * 将迭代的位置设为e
		 */
		@Override
		public void set(E e) {
			checkForComodification();
			if (lastReturned == null) {
				throw new IllegalStateException("还没有开始迭代");
			}
			lastReturned.item = e;
		}

		/**
		 * 在迭代的位置插入e
		 */
		@Override
		public void add(E e) {
			checkForComodification();
            lastReturned = null;
            if (next == null) {
                linkLast(e);
            } else {
                linkBefore(e, next);
            }
            nextIndex++;
            expectedModCount++;
		}
		
	}

	private void linkLast(E e) {
		final Node<E> l = last;
        final Node<E> newNode = new Node<>(l, e, null);
        last = newNode;
        if (l == null) {
            first = newNode;
        } else {
            l.next = newNode;
        }
        size++;
        modCount++;
	}
	
	/**
	 * 截取List
	 * @param fromIndex 开始的位置
	 * @param toIndex   结束的位置
	 * @return  截取后的集合
	 */
	@Override
	public List<E> subList(int fromIndex, int toIndex) {
		if (toIndex > size) {
			throw new IllegalArgumentException("结束的位置太大");
		}
		if (fromIndex > size) {
			throw new IllegalArgumentException("开始的位置太大");
		}
		if (toIndex < size) {
			throw new IllegalArgumentException("结束的位置太小");
		}
		if (fromIndex < size) {
			throw new IllegalArgumentException("开始的位置太小");
		}
		if (fromIndex > toIndex) {
			throw new IllegalArgumentException("开始的位置比结束位置大");
		}
		
		List<E> list = new LinkedList<>();
		int i = 0;
		for (Node<E> x = first; x != null; x = x.next) {
			if (i >= fromIndex && i < toIndex) {
				list.add(x.item);			
			}
			i++;
		}
		return list;
	}

	/**
	 * 添加一个element在链表头
	 */
	@Override
	public void addFirst(E e) {
		linkFirst(e);
	}

	/**
	 * 添加一个element在链表尾
	 */
	@Override
	public void addLast(E e) {
		linkLasted(e);
	}

	/**
	 * 添加一个element在链表头
	 */
	@Override
	public boolean offerFirst(E e) {
		addFirst(e);
		return true;
	}

	/**
	 * 添加一个element在链表尾
	 */
	@Override
	public boolean offerLast(E e) {
		addLast(e);
		return true;
	}

	/**
	 * 删除第一个element
	 */
	@Override
	public E removeFirst() {
		final Node<E> f = first;
		if (f == null){
			 throw new NoSuchElementException("没有这个element");
		}
		return unlinkFirst(f);
	}

	
	/**
	 * 删除最后一个element
	 */
	@Override
	public E removeLast() {
		final Node<E> l =last;
		if (l == null) {
			throw new NoSuchElementException("没有这个element");
		}
		return unlinkLast(l);
	}

	/**
	 * 返还并删除链表中第一个元素
	 */
	@Override
	public E pollFirst() {
		final Node<E> f = first;
	    return (f == null) ? null : unlinkFirst(f);
	}

	
	/**
	 * 返还并删除链表中最后一个元素
	 */
	@Override
	public E pollLast() {
		final Node<E> l = last;
		return (l == null) ? null : unlinkLast(l);
	}

	/**
	 * 获取第一个element
	 */
	@Override
	public E getFirst() {
		final Node<E> f = first;
		if (f == null){
			 throw new NoSuchElementException("没有这个element");
		}
		return f.item;
	}

	/**
	 * 获取最后一个element
	 */
	@Override
	public E getLast() {
		final Node<E> l = last;
		if (l == null) {
			throw new NoSuchElementException("没有这个element");
		}
		return l.item;
	}
	
	/*index是否在集合中*/
	private boolean isElementIndex(int index) {
		return index >= 0 && index < size;
	}
	
	
	/**
	 * 获取头元素
	 */
	@Override
	public E peekFirst() {
		final Node<E> f = first;
        return (f == null) ? null : f.item;
	}

	/**
	 * 获取最后一个元素
	 */
	@Override
	public E peekLast() {
		final Node<E> l = last;
		return (l == null) ? null : l.item;
	}

	/**
	 * 删除首次出现的
	 */
	@Override
	public boolean removeFirstOccurrence(Object o) {
		return remove(o);
	}

	
	/**
	 * 删除最后一次出现的
	 */
	@Override
	public boolean removeLastOccurrence(Object o) {
		if (o == null) {
			for (Node<E> x = last; x != null; x = x.prev) {
				if (x.item == null) {
					unlink(x);
					return true;
				}
			}
		} else {
			for (Node<E> x = last; x != null; x = x.prev) {
				if (x.item.equals(o)) {
					unlink(x);
					return true;
				}
			}
		}
		return false;
	}

	
	/**
	 * 在末尾添加一个element
	 */
	@Override
	public boolean offer(E e) {
		return add(e);
	}

	/**
	 * 删除第一个
	 */
	@Override
	public E remove() {
		return removeFirst();
	}

	/**
	 * 返回并删除链表头
	 */
	@Override
	public E poll() {
		final Node<E> f = first;
        return (f == null) ? null : unlinkFirst(f);
	}

	/**
	 * 返回第一个
	 */
	@Override
	public E element() {
		return getFirst();
	}

	/**
	 * 返回第一个
	 */
	@Override
	public E peek() {
		final Node<E> f = first;
		return f == null ? null : f.item;
	}

	/**
	 * 添加到链表头
	 */
	@Override
	public void push(E e) {
		addFirst(e);
	}

	/**
	 * 返回并删除第一个
	 */
	@Override
	public E pop() {
		return removeFirst();
	}

	/**
	 * 反过来的迭代器
	 */
	@Override
	public Iterator<E> descendingIterator() {
		return new DescendingIterator();
	}
	
	private class DescendingIterator implements Iterator<E>{

		private final ListIterator<E> listItr = new ListItr(size);
		
		@Override
		public boolean hasNext() {
			return listItr.hasPrevious();
		}

		@Override
		public E next() {
			return listItr.previous();
		}
		
		@Override
		public void remove() {
			listItr.remove();
		}
		
	}
	
	/*
	 * 在序列化过程中，虚拟机会试图调用对象类里的writeObject() 和readObject()，
	 * 进行用户自定义的序列化和反序列化，如果没有则调用ObjectOutputStream.defaultWriteObject()
	 * 和ObjectInputStream.defaultReadObject()
	 */
	private void writeObject(java.io.ObjectOutputStream s)
	        throws java.io.IOException {
		// Write out any hidden serialization magic
	    s.defaultWriteObject();

	    // Write out size
	    s.writeInt(size);

	    // Write out all elements in the proper order.
	    for (Node<E> x = first; x != null; x = x.next) {
            s.writeObject(x.item);	
	    }

	}
	
	@SuppressWarnings("unchecked")
	private void readObject(java.io.ObjectInputStream s)
	        throws java.io.IOException, ClassNotFoundException {
	    // Read in any hidden serialization magic
	    s.defaultReadObject();

	    // Read in size
	    int size = s.readInt();

	    // Read in all elements in the proper order.
	    for (int i = 0; i < size; i++) {
	        linkLast((E)s.readObject());
	    }
	}

}

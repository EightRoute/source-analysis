package com.webmvc.util.map;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;



/**
 * java8 HashMap源码
 * @author sgz
 */
public class HashMap<K, V> extends AbstractMap<K, V> 
	implements Map<K, V>, Serializable, Cloneable{
	
	private static final long serialVersionUID = 2772224917432192756L;

	/*初始化容量,1 << 4,必须为2的倍数
	 * 如果length为2的次幂  则length-1 转化为二进制必定是11111……的形式，
	 * 在于h的二进制与操作效率会非常的快，而且空间不浪费
	 */
	static final int DEFAULT_INITIAL_CAPACITY = 16;
	/*最大容量, 1 << 30*/
	public static final int MAXIMUM_CAPACITY = 1073741824;
	/*默认扩容参数,代表了table的填充度有多少*/
	static final float DEFAULT_LOAD_FACTOR = 0.75F;
	
	//当桶中元素个数超过这个值时，需要使用红黑树节点替换链表节点
	//这个值必须为 8，要不然频繁转换效率也不高
	static final int TREEIFY_THRESHOLD = 8;
	
	//一个树的链表还原阈值
	//当扩容时，桶中元素个数小于这个值，就会把树形的桶元素 还原（切分）为链表结构
	//这个值应该比上面那个小，至少为 6，避免频繁转换
	static final int UNTREEIFY_THRESHOLD = 6;
	
	//哈希表的最小树形化容量
	//当哈希表中的容量大于这个值时，表中的桶才能进行树形化
	//否则桶内元素太多时会扩容，而不是树形化
	//为了避免进行扩容、树形化选择的冲突，这个值不能小于 4 * TREEIFY_THRESHOLD
	static final int MIN_TREEIFY_CAPACITY = 64;
	
	static class Node<K, V> implements Map.Entry<K, V> {
		final int hash;
		final K key;
		V value;
		Node<K, V> next;
		
		Node(int hash, K key, V value, Node<K,V> next) {
			this.hash = hash;
			this.key = key;
			this.value = value;
			this.next = next;
		}
		
		@Override
		public final K getKey() {
			return key;
		}

		@Override
		public final V getValue() {
			return value;
		}
		
		@Override
		public final String toString() {
			return key + " = " + value;
		}
		
		@Override
		public final int hashCode() {
			/* ^运算  01 -> 1, 00 -> 0, 11 -> 0 */
			return Objects.hashCode(key) ^ Objects.hashCode(value);
		}

		@Override
		public final V setValue(V newValue) {
			V oldValue = value;
			value = newValue;
			return oldValue;
		}
		
		@Override
		public final boolean equals(Object o) {
			if (o == this)
                return true;
            if (o instanceof Map.Entry) {
                Map.Entry<?,?> e = (Map.Entry<?,?>)o;
                if (Objects.equals(key, e.getKey()) &&
                    Objects.equals(value, e.getValue()))
                    return true;
            }
            return false;
		}
		
	}
	
	static final int hash(Object key) {
		int h;
		/*无符号的右移>>>,按照二进制把数字右移指定数位，高位直接补零，低位移除*/
		// 移位为了分布的更均匀
		//需要重写hashcode原因
		//(h >>> 16)扰动函数 混合原始哈希码的高位和低位，以此来加大低位的随机性
		return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
	}

	/*将桶的容量变为二次幂，无论cap是否为二次幂*/
	static final int tableSizeFor (int cap) {
		int n = cap - 1;
		n |= n >>> 1;
		n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
	}
	
	transient Node<K,V>[] table;
	
	transient Set<Map.Entry<K, V>> entrySet;
	
	transient int size;
	
	transient int modCount;
	/*阈值，等于加载因子*容量，当实际大小超过阈值则进行扩容*/
	int threshold;
	/*扩容参数*/
	final float loadFactor;
	
	public HashMap(int initialCapacity, float loadFactor) {
		if (initialCapacity < 0) {
			throw new IllegalArgumentException("初始化容量不能小于0,当前容量为:" + initialCapacity);
		}
		
		if (initialCapacity > MAXIMUM_CAPACITY) {
			initialCapacity = MAXIMUM_CAPACITY;
		}
		
		if (loadFactor <= 0 || Float.isNaN(loadFactor)) {
			throw new IllegalArgumentException("扩容参数错误,当前为:" + loadFactor);
		}
		
		this.loadFactor = loadFactor;
		this.threshold = tableSizeFor(initialCapacity);
	}
	
	public HashMap(int initialCapacity) {
		this(initialCapacity, DEFAULT_LOAD_FACTOR);
	}
	
	public HashMap() {
		this.loadFactor = DEFAULT_LOAD_FACTOR;
	}
	
	public HashMap(Map<? extends K, ? extends V> m) {
		this.loadFactor = DEFAULT_LOAD_FACTOR;
		putMapEntries(m, false);
	}
	
	/**
	 * 插入一个map
	 */
	final void putMapEntries(Map<? extends K, ? extends V> m, boolean evict) {
		int s = m.size();
		if (s > 0) {
			if (table == null) {
				float ft = ((float)s / loadFactor) + 1.0F;
				int t = ((ft < (float)MAXIMUM_CAPACITY)) ? (int)ft : MAXIMUM_CAPACITY;
				if (t > threshold) {
					threshold = tableSizeFor(t);
				}
			} else if (s > threshold) {
				//大于阈值 扩容
				resize();
			}
			for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
				K key = e.getKey();
				V value = e.getValue();
				putVal(hash(key), key, value, false, evict);
			}
		}
	}
	
	/**
	 * 初始分配或者容量翻倍
	 * @return 新的table
	 */
	final Node<K, V>[] resize() {
		Node<K, V>[] oldTab = table;
		//老的容量
		int oldCap = (oldTab == null) ? 0 : oldTab.length; 
		//老阈值
		int oldThr = threshold;
		int newCap, newThr = 0;
		if (oldCap > 0) {
			//如果已经达到最大容量，则不扩容
			if (oldCap >= MAXIMUM_CAPACITY) {
				threshold = Integer.MAX_VALUE;
				return oldTab;
			} else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY &&
					oldCap >= DEFAULT_INITIAL_CAPACITY) {
				//容量翻倍 ↑, 阈值翻倍↓
				newThr = oldThr << 1;
			}
		} else if (oldThr > 0) {
			//当设置了初始容量的时候,oldCap为0而oldThr将大于0且为2次幂
			newCap = oldThr;
		} else {
			//初始分配
			newCap = DEFAULT_INITIAL_CAPACITY;
			//阈值 = 容量 * 负载因子
			newThr = (int) (DEFAULT_INITIAL_CAPACITY * DEFAULT_LOAD_FACTOR);
		}
		
		if (newThr == 0) {
			float ft = (float)newCap * loadFactor;
			newThr = (newCap < MAXIMUM_CAPACITY && ft < (float)MAXIMUM_CAPACITY ? 
					(int)ft : Integer.MAX_VALUE);
		}
		threshold = newThr;
		
		@SuppressWarnings("unchecked")
		//创建新的表
		Node<K, V>[] newTab = (Node<K,V>[])new Node[newCap]; 
		table = newTab;
		if (oldTab != null) {
			for (int j = 0; j < oldCap; ++j) {
				//遍历老的表,将元素放到新的表中
				Node<K, V> e;
				if ((e = oldTab[j]) != null) {
					oldTab[j] = null; //gc
					if (e.next == null) {
						newTab[e.hash & (newCap - 1)] = e;
					} else if (e instanceof TreeNode) {
						//如果为红黑树
						((TreeNode<K, V>)e).split(this, newTab, j, oldCap);
					} else {
						//不需要改变位置的链表
						Node<K, V> loHead = null, loTail = null;
						//需要改变位置的链表
						Node<K, V> hiHead = null, hiTail = null;
						Node<K, V> next;
						do {
							//遍历链表
							next = e.next;
							//当(e.hash & oldCap) == 0 痛的位置不需要改变
							// oldcap 00010000 oldcap-1 00001111  newcap-1 00011111  
							// hash   11101111          11101111           11101111
							if ((e.hash & oldCap) == 0) {
								if (loTail == null) {
									loHead = e;
								} else {
									loTail.next = e;
								}
								loTail = e;
							} else {
								if (hiTail == null) {
									hiHead = e;
								} else {
									hiTail.next = e;
								}
								hiTail = e;
							}
						} while ((e = next) != null);
						if (loTail != null) {
							loTail.next = null;
							newTab[j] = loHead;
						}
						//假设hash为48 oldcap为16 
						//110000 110000
						//001111 011111 老的位置为0 新的为16
						if (hiTail != null) {
							hiTail.next = null;
							newTab[j + oldCap] = hiHead;
						}
					}
				}
			}
		}
		
		return newTab;
	}
	
	/**
	 * 
	 * @param hash 用来确定数组下标
	 * @param key 键
	 * @param value 值
	 * @param onlyIfAbsent 如果存在值则不改变
	 * @param evict
	 * @return 原来的值或null
	 */
	final V putVal(int hash, K key, V value,
			boolean onlyIfAbsent, boolean evict) {
		Node<K, V>[] tab;
		Node<K, V> p;
		int n, i;
		/*如果为初始状态*/
		if ((tab = table) == null || (n = tab.length) == 0) {
			n = (tab = resize()).length;
		}
		/*如果链表为空,元素将插入到数组的(n - 1) & hash的位置,*/
		// i = (n - 1) & hash  简化了以前的indexFor方法
		//为什么长度要2次幂的原因 2次幂时 length - 1的二进制为1111 &运算时不会浪费数组空间
		if ((p = tab[i = (n - 1) & hash]) == null) {
			tab[i] = newNode(hash, key, value, null);
		} else {
			Node<K, V> e; K k;
			//如果和第一个元素的key就相等
			if (p.hash == hash 
				&& ((k = p.key) == key || (key != null && key.equals(k)))) {
				e = p;
			} else if (p instanceof TreeNode) {
				e = ((TreeNode<K,V>)p).putTreeVal(this, tab, hash, key, value);
			} else {
				/*遍历链表,如果没有找到相同的key则在最后增加一个Node*/
				for (int binCount = 0; ;++binCount) {
					if ((e = p.next) == null) {
						p.next = newNode(hash, key, value, null);
						//遍历table[i]，判断链表长度是否大于TREEIFY_THRESHOLD(默认值为8)，大于8的话把链表转换为红黑树，在红黑树中执行插入操作，否则进行链表的插入操作
						if (binCount >= TREEIFY_THRESHOLD - 1) {
							treeifyBin(tab, hash);
							break;
						}
					}
					//存在相同的key时
					if (e.hash == hash 
							&& ((k = e.key) == key || (key != null && key.equals(k)))) {
						break;
					}
					p = e;
				}		
			}
			if (e != null) {
				V oldValue = e.value;
				//是否覆盖默认值
				if (!onlyIfAbsent || oldValue == null) {
					e.value = value;
				}
				afterNodeAccess(e);
				return oldValue;
			}
		}
		++modCount;
		if (++size > threshold) {
			resize();
		}
		afterNodeInsertion(evict);
		return null;
	}
	
	void reinitialize() {
        table = null;
        entrySet = null;
        keySet = null;
        values = null;
        modCount = 0;
        threshold = 0;
        size = 0;
    }
	
	void afterNodeAccess(Node<K, V> p) {}
	void afterNodeInsertion(boolean evict) {}
	void afterNodeRemoval(Node<K, V> p) {}
	
	Node<K, V> newNode(int hash, K key, V value, Node<K, V> next) {
		return new Node<>(hash, key, value, next);
	}
	
	/*当有一条链条的长度大于TREEIFY_THRESHOLD 
	 * 且桶的长度大于MIN_TREEIFY_CAPACITY时
	 * 将桶内链表节点 替换成 红黑树节点*/
	final void treeifyBin(Node<K, V>[] tab, int hash) {
		int n, index;
		Node<K, V> e;
		//如果桶的长度小于MIN_TREEIFY_CAPACITY则扩容，而不进行树形化
		if (tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY) {
			resize();
		} else if ((e = tab[index = (n - 1) & hash]) != null) {
			//红黑树链表头尾节点
			TreeNode<K, V> hd = null, tl = null;
			do {
				//新建一个树节点,内容和链表节点e相同
				TreeNode<K, V> p = replacementTreeNode(e, null);
				if (tl == null) {
					hd = p;
				} else {
					p.prev = tl;
					tl.next = p;
				}
			} while ((e = e.next) != null);
			//让桶的第一个元素指向新建的红黑树头结点，以后这个桶里的元素就是红黑树而不是链表
			if ((tab[index] = hd) != null) {
				//将红黑树链表转化为红黑树
				hd.treeify(tab);
			}
		}
	}
	
	/**
	 * 将链表节点转换为红黑树节点 
	 */
	TreeNode<K, V> replacementTreeNode(Node<K, V> p, Node<K, V> next) {
		return new TreeNode<K, V>(p.hash, p.key, p.value, next);
	}
	
	/**
	 * 一共都多少对key-value
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
	 * 是否包含某个key
	 */
	@Override
	public boolean containsKey(Object key) {
		return getNode(hash(key), key) != null;
	}

	/**
	 * 是否包含某个value
	 */
	@Override
	public boolean containsValue(Object value) {
		Node<K, V>[] tab;
		V v;
		if ((tab = table) != null && size > 0) {
			for (int i = 0; i < table.length; ++i) {
				for (Node<K, V> e = tab[i]; e != null; e = e.next) {
					if ((v = e.value) == value || (value != null && value.equals(v))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * 根据key取value
	 */
	@Override
	public V get(Object key) {
		Node<K, V> e;
		return (e = getNode(hash(key), key)) == null ? null : e.value;
	}

	
	final Node<K, V> getNode(int hash, Object key) {
		Node<K,V>[] tab; Node<K,V> first, e; int n; K k;
        if ((tab = table) != null && (n = tab.length) > 0 &&
            (first = tab[(n - 1) & hash]) != null) {
            if (first.hash == hash && 
                ((k = first.key) == key || (key != null && key.equals(k)))) {
                //如果第一个就相等
            	return first;
            }
            //如果第一个不相等就要判断是红黑树还是链表
            if ((e = first.next) != null) {
                if (first instanceof TreeNode) {
                	//如果是红黑树
                    return ((TreeNode<K,V>)first).getTreeNode(hash, key);
                }
                //如果是链表，则遍历链表
                do {
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k)))) {
                        return e;
                    }
                } while ((e = e.next) != null);
            }
        }
        return null;
	}
	
	/**
	 * 插入key-value
	 */
	@Override
	public V put(K key, V value) {
		return putVal(hash(key), key, value, false, true);
	}

	/**
	 * 根据key删除
	 */
	@Override
	public V remove(Object key) {
		Node<K, V> e;
		return (e = removeNode(hash(key), key, null, false, true)) == null ?
	            null : e.value;
	}
	
	//删除Node
	final Node<K, V> removeNode(int hash, Object key, Object value,
            boolean matchValue, boolean movable) {
		Node<K, V>[] tab;
		Node<K, V> p;
		int n, index;
		if ((tab = table) != null && (n = tab.length) > 0 &&
		            (p = tab[index = (n - 1) & hash]) != null) {
		            Node<K,V> node = null, e; K k; V v;
		            if (p.hash == hash &&
		                ((k = p.key) == key || (key != null && key.equals(k)))) {
		            	//如果第一个就相同
		                node = p;
		            } else if ((e = p.next) != null) {
		            	//判断是否为红黑树，如不是则遍历链表
		                if (p instanceof TreeNode) {
		                    node = ((TreeNode<K,V>)p).getTreeNode(hash, key);
		                } else {
		                    do {
		                        if (e.hash == hash &&
		                            ((k = e.key) == key ||
		                             (key != null && key.equals(k)))) {
		                            node = e;
		                            break;
		                        }
		                        p = e;
		                    } while ((e = e.next) != null);
		                }
		            }
		            //删除操作
		            if (node != null && (!matchValue || (v = node.value) == value ||
		                                 (value != null && value.equals(v)))) {
		                if (node instanceof TreeNode) {
		                    ((TreeNode<K,V>)node).removeTreeNode(this, tab, movable);
		                } else if (node == p) {
		                    tab[index] = node.next;
		                } else {
		                    p.next = node.next;
		                }
		                ++modCount;
		                --size;
		                afterNodeRemoval(node);
		                return node;
		            }
		        }
		        return null;
	}

	/**
	 * 插入一个map
	 */
	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		putMapEntries(m, true);
	}
	
	

	/**
	 * 清空map
	 */
	@Override
	public void clear() {
		Node<K, V>[] tab;
		if ((tab = table) != null && size > 0) {
			size = 0;
			for (int i = 0; i < tab.length; i++) {
				tab[i] = null;
			}
		}
	}
	
	
	@SuppressWarnings("unchecked")
	@Override
    public Object clone() {
        HashMap<K,V> result;
        try {
            result = (HashMap<K,V>)super.clone();
        } catch (CloneNotSupportedException e) {
            //不可能发生
            throw new InternalError(e);
        }
        result.reinitialize();
        result.putMapEntries(this, false);
        return result;
    }

    public final float loadFactor() { return loadFactor; }
    public final int capacity() {
        return (table != null) ? table.length :
            (threshold > 0) ? threshold :
            DEFAULT_INITIAL_CAPACITY;
    }
	
	void internalWriteEntries(ObjectOutputStream s) throws IOException {
        Node<K,V>[] tab;
        if (size > 0 && (tab = table) != null) {
            for (int i = 0; i < tab.length; ++i) {
                for (Node<K,V> e = tab[i]; e != null; e = e.next) {
                    s.writeObject(e.key);
                    s.writeObject(e.value);
                }
            }
        }
    }
	
	/**
	 * 序列化
	 */
	private void writeObject(ObjectOutputStream s) throws IOException {
		int buckets = capacity();
	    // 写threshold, loadfactor和一些隐藏的东西
	    s.defaultWriteObject();
	    s.writeInt(buckets);
	    s.writeInt(size);
	    internalWriteEntries(s);
	}

	/**
     * 反序列化
     */
    private void readObject(ObjectInputStream s)
        throws IOException, ClassNotFoundException {
        s.defaultReadObject();
        reinitialize();
        if (loadFactor <= 0 || Float.isNaN(loadFactor)) {
            throw new InvalidObjectException("Illegal load factor: " +
                                             loadFactor);
        }
        s.readInt();                
        int mappings = s.readInt(); 
        if (mappings < 0) {
            throw new InvalidObjectException("Illegal mappings count: " +
                                             mappings);
        } else if (mappings > 0) { 
        	// 如果为0使用默认的
            // loadFactor在0.25...4.0之间
            float lf = Math.min(Math.max(0.25f, loadFactor), 4.0f);
            float fc = (float)mappings / lf + 1.0f;
            int cap = ((fc < DEFAULT_INITIAL_CAPACITY) ?
                       DEFAULT_INITIAL_CAPACITY :
                       (fc >= MAXIMUM_CAPACITY) ?
                       MAXIMUM_CAPACITY :
                       tableSizeFor((int)fc));
            float ft = (float)cap * lf;
            threshold = ((cap < MAXIMUM_CAPACITY && ft < MAXIMUM_CAPACITY) ?
                         (int)ft : Integer.MAX_VALUE);
            @SuppressWarnings({"unchecked"})
            Node<K,V>[] tab = (Node<K,V>[])new Node[cap];
            table = tab;

            //读key和value，并加入到HashMap中
            for (int i = 0; i < mappings; i++) {
                @SuppressWarnings("unchecked")
                    K key = (K) s.readObject();
                @SuppressWarnings("unchecked")
                    V value = (V) s.readObject();
                putVal(hash(key), key, value, false, false);
            }
        }
    }    

	@Override
	public Set<K> keySet() {
		Set<K> ks = keySet;
		if (ks == null) {
			ks = new KeySet();
			keySet = ks;
		}
		return ks;
	}
	
	//使用EntrySet遍历Map类集合，而不是KeySet，因为KeySet遍历了两遍而EntrySet只遍历了一遍
	final class KeySet extends AbstractSet<K> {
		
		@Override
		public final Iterator<K> iterator() {
			return new KeyIterator();
		}

		@Override
		public final int size() {
			return size;
		}
		
		@Override
		public final void clear() {
			HashMap.this.clear();
		}
		
		@Override
		public final boolean contains(Object o) {
			return containsKey(o);
		}
		
		@Override
		public final boolean remove(Object key) {
			return removeNode(hash(key), key, null, false, true) != null;
		}
		
		@Override
		public final void forEach(Consumer<? super K> action) {
			Node<K, V>[] tab;
			if (action == null) {
				throw new NullPointerException();
			}
			if (size > 0 && (tab = table) != null) {
				int mc = modCount;
				for (int i = 0; i < tab.length; ++i) {
					for (Node<K, V> e = tab[i]; e != null; e = e.next) {
						action.accept(e.key);
					}
				}
				 if (modCount != mc) {
	                    throw new ConcurrentModificationException();
				 }
			}
			
		}
		
		@Override
		public Spliterator<K> spliterator() {
			// TODO 
			return null;
		}
		
	}

	//得到V的集合
	@Override
	public Collection<V> values() {
		Collection<V> vs = values;
		if (vs == null) {
			vs = new Values();
			values = vs;
		}
		return vs;
	}
	
	final class Values extends AbstractCollection<V> {

		@Override
		public final Iterator<V> iterator() {
			return new ValueIterator();
		}

		@Override
		public final int size() {
			return size;
		}
		
		@Override
		public final void clear() {
			HashMap.this.clear();
		}
		
		@Override
		public final boolean contains(Object o) {
			return containsValue(o);
		}
		
		@Override
		public final void forEach(Consumer<? super V> action) {
			Node<K,V>[] tab;
            if (action == null)
                throw new NullPointerException();
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (int i = 0; i < tab.length; ++i) {
                    for (Node<K,V> e = tab[i]; e != null; e = e.next) {
                        action.accept(e.value);
                    }
                }
                if (modCount != mc) {
                    throw new ConcurrentModificationException();
                }
            }
		}
		
		@Override
		public Spliterator<V> spliterator() {
			//TODO
			return null;
		}
		
	}

	//使用entrySet遍历Map类集合，而不是keySet，因为keySet遍历了两遍而entrySet只遍历了一遍
	@Override
	public Set<Entry<K, V>> entrySet() {
		 Set<Map.Entry<K,V>> es;
	     return (es = entrySet) == null ? (entrySet = new EntrySet()) : es;
	}
	
	final class EntrySet extends AbstractSet<Map.Entry<K,V>> {
        public final int size() { return size; }
        public final void clear() { HashMap.this.clear(); }
        public final Iterator<Map.Entry<K,V>> iterator() {
            return new EntryIterator();
        }
        public final boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?,?> e = (Map.Entry<?,?>) o;
            Object key = e.getKey();
            Node<K,V> candidate = getNode(hash(key), key);
            return candidate != null && candidate.equals(e);
        }
        public final boolean remove(Object o) {
            if (o instanceof Map.Entry) {
                Map.Entry<?,?> e = (Map.Entry<?,?>) o;
                Object key = e.getKey();
                Object value = e.getValue();
                return removeNode(hash(key), key, value, true, true) != null;
            }
            return false;
        }
        public final Spliterator<Map.Entry<K,V>> spliterator() {
        	//TODO
            return null;
        }
        public final void forEach(Consumer<? super Map.Entry<K,V>> action) {
            Node<K,V>[] tab;
            if (action == null) {
                throw new NullPointerException();
            }
            if (size > 0 && (tab = table) != null) {
                int mc = modCount;
                for (int i = 0; i < tab.length; ++i) {
                    for (Node<K,V> e = tab[i]; e != null; e = e.next)
                        action.accept(e);
                }
                if (modCount != mc) {
                    throw new ConcurrentModificationException();
                }
            }
        }
    }
	
	
	abstract class HashIterator {
		Node<K, V> next;
		Node<K, V> current;
		int expectedModCount;  // 快速失败
        int index; 
        
        HashIterator() {
			expectedModCount = modCount;
			Node<K, V>[] t = table;
			current = next = null;
			index = 0;
			if (t != null && size >0) {
				do {
					//去掉为null的
				} while (index < t.length && (next = t[index++]) == null);
			}
		}
        
        public final boolean hasNext() {
        	return next != null;
        }
        
        final Node<K, V> nextNode() {
        	Node<K, V>[] t;
        	Node<K, V> e = next;
        	if (modCount != expectedModCount) {
        		throw new ConcurrentModificationException();
        	}
        	if (e == null) {
        		throw new NoSuchElementException();
        	}
        	if ((next = (current = e).next) == null && (t = table) != null) {
                do {} while (index < t.length && (next = t[index++]) == null);
            }
            return e;
        }
        
        public final void remove() {
        	Node<K, V> p = current;
        	if (p == null) {
        		throw new IllegalStateException();
        	}
        	if (modCount != expectedModCount) {
        		throw new ConcurrentModificationException();
        	}
        	current = null;//gc
        	K key = p.key;
        	removeNode(hash(key), key, null, false, false);
        	expectedModCount = modCount;
        }
	}
	
	final class KeyIterator extends HashIterator implements Iterator<K>{
		@Override
		public K next() {
			return nextNode().key;
		}		
	}
	final class ValueIterator extends HashIterator implements Iterator<V> {
		@Override
		public V next() {
			return nextNode().value;
		}	
	}
	//既可以得到key又可以得到value
	final class EntryIterator extends HashIterator implements Iterator<Map.Entry<K,V>> {
		@Override
		public java.util.Map.Entry<K, V> next() {
			return nextNode();
		}	
	}
	
	/*
	 * 并行遍历
	 * 把多个任务分配到不同核上并行执行，能最大发挥多核的能力
	 */
	static class HashMapSpliterator<K,V> {
        final HashMap<K,V> map;
        Node<K,V> current;          // current node
        int index;                  // current index, modified on advance/split
        int fence;                  // one past last index
        int est;                    // size estimate
        int expectedModCount;       // for comodification checks

        HashMapSpliterator(HashMap<K,V> m, int origin,
                           int fence, int est,
                           int expectedModCount) {
            this.map = m;
            this.index = origin;
            this.fence = fence;
            this.est = est;
            this.expectedModCount = expectedModCount;
        }

        final int getFence() { // initialize fence and size on first use
            int hi;
            if ((hi = fence) < 0) {
                HashMap<K,V> m = map;
                est = m.size;
                expectedModCount = m.modCount;
                Node<K,V>[] tab = m.table;
                hi = fence = (tab == null) ? 0 : tab.length;
            }
            return hi;
        }
        //用于估算还剩下多少个元素需要遍历
        public final long estimateSize() {
            getFence(); 
            return (long) est;
        }
    }
	
	public static final class KeySpliterator<K,V> extends HashMapSpliterator<K,V>
    	implements Spliterator<K> {
		
		public KeySpliterator(HashMap<K,V> m, int origin, int fence, int est,
                   int expectedModCount) {
			super(m, origin, fence, est, expectedModCount);
		}
		//把当前元素划分一部分出去创建一个新的Spliterator作为返回，两个Spliterator变会并行执行，如果元素个数小到无法划分则返回null
		public KeySpliterator<K,V> trySplit() {
			int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
                return (lo >= mid || current != null) ? null :
                	new KeySpliterator<>(map, lo, index = mid, est >>>= 1,
                                    expectedModCount);
		}

		public void forEachRemaining(Consumer<? super K> action) {
			int i, hi, mc;
			if (action == null)
				throw new NullPointerException();
			HashMap<K,V> m = map;
			Node<K,V>[] tab = m.table;
			if ((hi = fence) < 0) {
				mc = expectedModCount = m.modCount;
				hi = fence = (tab == null) ? 0 : tab.length;
			}
			else
				mc = expectedModCount;
			if (tab != null && tab.length >= hi &&
					(i = index) >= 0 && (i < (index = hi) || current != null)) {
				Node<K,V> p = current;
				current = null;
				do {
					if (p == null)
						p = tab[i++];
					else {
						action.accept(p.key);
						p = p.next;
					}
				} while (p != null || i < hi);
				if (m.modCount != mc)
					throw new ConcurrentModificationException();
			}
		}
		
		//
		public boolean tryAdvance(Consumer<? super K> action) {
			int hi;
			if (action == null)
				throw new NullPointerException();
			Node<K,V>[] tab = map.table;
			if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
				while (current != null || index < hi) {
					if (current == null)
						current = tab[index++];
					else {
						K k = current.key;
						current = current.next;
						action.accept(k);
						if (map.modCount != expectedModCount)
							throw new ConcurrentModificationException();
						return true;
					}
				}
			}
			return false;
		}
		//表示该Spliterator有哪些特性，用于可以更好控制和优化Spliterator的使用
		public int characteristics() {
			return (fence < 0 || est == map.size ? Spliterator.SIZED : 0) |
					Spliterator.DISTINCT;
		}
	}
    
    static final class ValueSpliterator<K,V> extends HashMapSpliterator<K,V>
    	implements Spliterator<V> {
    	
    	ValueSpliterator(HashMap<K,V> m, int origin, int fence, int est,
                     int expectedModCount) {
    		super(m, origin, fence, est, expectedModCount);
    	}
    	//把当前元素划分一部分出去创建一个新的Spliterator作为返回，两个Spliterator变会并行执行，如果元素个数小到无法划分则返回null
    	public ValueSpliterator<K,V> trySplit() {
    		int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
				return (lo >= mid || current != null) ? null :
					new ValueSpliterator<>(map, lo, index = mid, est >>>= 1,
                                      expectedModCount);
    	}

    	public void forEachRemaining(Consumer<? super V> action) {
    		int i, hi, mc;
    		if (action == null)
    			throw new NullPointerException();
    		HashMap<K,V> m = map;
    		Node<K,V>[] tab = m.table;
    		if ((hi = fence) < 0) {
    			mc = expectedModCount = m.modCount;
    			hi = fence = (tab == null) ? 0 : tab.length;
    		}
    		else
    			mc = expectedModCount;
    		if (tab != null && tab.length >= hi &&
    				(i = index) >= 0 && (i < (index = hi) || current != null)) {
    			Node<K,V> p = current;
    			current = null;
    			do {
    				if (p == null)
    					p = tab[i++];
    				else {
    					action.accept(p.value);
    					p = p.next;
    				}
    			} while (p != null || i < hi);
    			if (m.modCount != mc)
    				throw new ConcurrentModificationException();
    		}
    	}
    	//顺序处理每个元素
    	public boolean tryAdvance(Consumer<? super V> action) {
    		int hi;
    		if (action == null)
    			throw new NullPointerException();
    		Node<K,V>[] tab = map.table;
    		if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
    			while (current != null || index < hi) {
    				if (current == null)
    					current = tab[index++];
    				else {
    					V v = current.value;
    					current = current.next;
    					action.accept(v);
    					if (map.modCount != expectedModCount)
    						throw new ConcurrentModificationException();
    					return true;
    				}
    			}
    		}
    		return false;
    	}
    	//表示该Spliterator有哪些特性，用于可以更好控制和优化Spliterator的使用
    	public int characteristics() {
    		return (fence < 0 || est == map.size ? Spliterator.SIZED : 0);
    	}
    }

    static final class EntrySpliterator<K,V> extends HashMapSpliterator<K,V>
    	implements Spliterator<Map.Entry<K,V>> {
    	EntrySpliterator(HashMap<K,V> m, int origin, int fence, int est,
                     	int expectedModCount) {
    		super(m, origin, fence, est, expectedModCount);
    	}
    	//把当前元素划分一部分出去创建一个新的Spliterator作为返回，两个Spliterator变会并行执行，如果元素个数小到无法划分则返回null
    	public EntrySpliterator<K,V> trySplit() {
    		int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
    			return (lo >= mid || current != null) ? null :
    				new EntrySpliterator<>(map, lo, index = mid, est >>>= 1,
                                      expectedModCount);
    	}

    	public void forEachRemaining(Consumer<? super Map.Entry<K,V>> action) {
    		int i, hi, mc;
    		if (action == null)
    			throw new NullPointerException();
    		HashMap<K,V> m = map;
    		Node<K,V>[] tab = m.table;
    		if ((hi = fence) < 0) {
    			mc = expectedModCount = m.modCount;
    			hi = fence = (tab == null) ? 0 : tab.length;
    		}
    		else
    			mc = expectedModCount;
    		if (tab != null && tab.length >= hi &&
    				(i = index) >= 0 && (i < (index = hi) || current != null)) {
    			Node<K,V> p = current;
    			current = null;
    			do {
    				if (p == null)
    					p = tab[i++];
    				else {
    					action.accept(p);
    					p = p.next;
    				}
    			} while (p != null || i < hi);
    			if (m.modCount != mc)
    				throw new ConcurrentModificationException();
    		}
    	}
    	//顺序处理每个元素
    	public boolean tryAdvance(Consumer<? super Map.Entry<K,V>> action) {
    		int hi;
    		if (action == null)
    			throw new NullPointerException();
    		Node<K,V>[] tab = map.table;
    		if (tab != null && tab.length >= (hi = getFence()) && index >= 0) {
    			while (current != null || index < hi) {
    				if (current == null)
    					current = tab[index++];
    				else {
    					Node<K,V> e = current;
    					current = current.next;
    					action.accept(e);
    					if (map.modCount != expectedModCount)
    						throw new ConcurrentModificationException();
    					return true;
    				}
    			}
    		}
    		return false;
    	}
    	//表示该Spliterator有哪些特性，用于可以更好控制和优化Spliterator的使用
    	public int characteristics() {
    		return (fence < 0 || est == map.size ? Spliterator.SIZED : 0) |
    				Spliterator.DISTINCT;
    	}
    
    }

    
	/**
	 * @return 是否实现了Comparable接口
	 */
	static Class<?> comparableClassFor(Object x) {
		//如果key实现了Comparable接口
		if (x instanceof Comparable) {
            Class<?> c; Type[] ts, as; Type t; ParameterizedType p;
            if ((c = x.getClass()) == String.class) {
                return c;
            }
            if ((ts = c.getGenericInterfaces()) != null) {
                for (int i = 0; i < ts.length; ++i) {
                    if (((t = ts[i]) instanceof ParameterizedType) &&
                        ((p = (ParameterizedType)t).getRawType() ==
                         Comparable.class) &&
                        (as = p.getActualTypeArguments()) != null &&
                        as.length == 1 && as[0] == c) {
                        return c;
                    }
                }
            }
        }
        return null;
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" } )
	static int compareComparables(Class<?> kc, Object k, Object x) {
		return (x == null || x.getClass() != kc ? 0 :
            ((Comparable)k).compareTo(x));
	}
	
	/**
	 * 转换成链表节点
	 */
	Node<K,V> replacementNode(Node<K,V> p, Node<K,V> next) {
        return new Node<>(p.hash, p.key, p.value, next);
    }
	
	TreeNode<K,V> newTreeNode(int hash, K key, V value, Node<K,V> next) {
        return new TreeNode<>(hash, key, value, next);
    }
	
	/*
	 * 红黑树特性
	 * 1根节点为黑色
	 * 2节点要么是红色 要么是黑色
	 * 3对于每个节点，从该点至null（树尾端）的任何路径，都含有相同个数的黑色节点
	 * 4红色节点不能连续
	 * 5新增的节点都是红色
	 */
	static final class TreeNode<K, V> extends LinkedHashMap.Entry<K, V> {
		TreeNode<K, V> parent;
		TreeNode<K, V> left;
		TreeNode<K, V> right;
		TreeNode<K, V> prev; //需要分开下删除
		boolean red;
		
		TreeNode(int hash, K key, V value, Node<K, V> next) {
			super(hash, key, value, next);
		}
		
		//返回树的根节点
		final TreeNode<K, V> root() {
			//是否存在父节点
			for (TreeNode<K, V> r = this,p ;;) {
				if ((p = r.parent) == null) {
					return r;
				}
				r = p;
			}
		}
		
		//将树的根节点放进桶中
		static <K, V> void moveRootToFront(Node<K, V>[] tab, TreeNode<K, V> root){
			int n;
			if (root != null && tab != null && (n = tab.length) > 0) {
				int index = (n - 1) & root.hash;
				TreeNode<K, V> first = (TreeNode<K, V>) tab[index];
				if (first != root) {
					Node<K, V> rn;
					tab[index] = root;
					TreeNode<K, V> rp = root.prev;
					if ((rn = root.next) != null) {
						((TreeNode<K, V>)rn).prev = rp;
					}
					if (rp != null) {
						rp.next = rn;
					}
					if (first != null) {
						first.prev = root;
					}
					root.next = first;
					root.prev = null;
				}
				//如果要开启断言检查，则需要用开关-enableassertions或-ea来开启
				assert checkInvariants(root);
			}
		}
		
		/**
		 * @param h hash
		 * @param k key
		 * @return 根据hash和key获取的树节点
		 */
		final TreeNode<K, V> find(int h, Object k, Class<?> kc) {
			TreeNode<K, V> p = this;
			do {
				int ph, dir;
				K pk;
				TreeNode<K, V> pl = p.left, pr = p.right, q;
				if ((ph = p.hash) > h) {
					p = pl;
				} else if (ph < h) {
					p = pr;
				} else if ((pk = p.key) == k || (k != null && k.equals(pk))) {
					return p;
				} else if (pl == null) {
					p = pr;
				} else if (pr == null) {
					p = pl;
				} else if ((kc != null ||
                          (kc = comparableClassFor(k)) != null) &&
                         (dir = compareComparables(kc, k, pk)) != 0) {
					p = (dir < 0) ? pl : pr;
				} else if ((q = pr.find(h, k, kc)) != null) {
					return q;
				} else {
					p = pl;
				}
				
			} while (p != null);
			return null;
		}
		
		/**
		 * @param h hash
		 * @param k key
		 * @return 根据hash和key获取的树节点
		 */
		final TreeNode<K, V> getTreeNode(int h, Object k) {
			return ((parent != null) ? root() : this).find(h, k, null);
		}
		
		static int tieBreakOrder(Object a, Object b) {
			int d;
            if (a == null || b == null ||
                (d = a.getClass().getName().
                 compareTo(b.getClass().getName())) == 0)
                d = (System.identityHashCode(a) <= System.identityHashCode(b) ?
                     -1 : 1);
            return d;
		}
 		
		
		final TreeNode<K,V> putTreeVal(HashMap<K,V> map, Node<K,V>[] tab,
	            int h, K k, V v) { 
			Class<?> kc = null;
            boolean searched = false;
            TreeNode<K,V> root = (parent != null) ? root() : this;
            for (TreeNode<K,V> p = root;;) {
                int dir, ph; K pk;
                if ((ph = p.hash) > h) {
                    dir = -1;
                } else if (ph < h) {
                    dir = 1;
                } else if ((pk = p.key) == k || (k != null && k.equals(pk))) {
                    //存在相同key时
                	return p;
                } else if ((kc == null &&
                          (kc = comparableClassFor(k)) == null) ||
                         (dir = compareComparables(kc, k, pk)) == 0) {
                    if (!searched) {
                        TreeNode<K,V> q, ch;
                        searched = true;
                        if (((ch = p.left) != null &&
                             (q = ch.find(h, k, kc)) != null) ||
                            ((ch = p.right) != null &&
                             (q = ch.find(h, k, kc)) != null)) {
                            return q;
                        }
                    }
                    dir = tieBreakOrder(k, pk);
                }
                
                
                //插入新的树节点
                TreeNode<K,V> xp = p;
                if ((p = (dir <= 0) ? p.left : p.right) == null) {
                    Node<K,V> xpn = xp.next;
                    TreeNode<K,V> x = map.newTreeNode(h, k, v, xpn);
                    if (dir <= 0) {
                        xp.left = x;
                    } else {
                        xp.right = x;
                    }
                    xp.next = x;
                    x.parent = x.prev = xp;
                    if (xpn != null) {
                        ((TreeNode<K,V>)xpn).prev = x;
                    }
                    moveRootToFront(tab, balanceInsertion(root, x));
                    return null;
                }
            }
		}
		
		//将红黑树节点链表转换成红黑树
		final void treeify(Node<K, V>[] tab) {
			TreeNode<K,V> root = null;
			//遍历链表
            for (TreeNode<K,V> x = this, next; x != null; x = next) {
                next = (TreeNode<K,V>)x.next;
                x.left = x.right = null;
                //将头结点设为红黑树的根节点
                if (root == null) {
                    x.parent = null;
                    x.red = false;
                    root = x;
                } else {
                    K k = x.key;
                    int h = x.hash;
                    Class<?> kc = null;
                    //判断是左孩子还是右孩子
                    for (TreeNode<K,V> p = root;;) {
                        int dir, ph;
                        K pk = p.key;
                        if ((ph = p.hash) > h) {
                            dir = -1;
                        } else if (ph < h) {
                            dir = 1;
                        } else if ((kc == null &&
                                  (kc = comparableClassFor(k)) == null) ||
                                 (dir = compareComparables(kc, k, pk)) == 0) {
                            dir = tieBreakOrder(k, pk);
                        }
                        TreeNode<K,V> xp = p;
                        if ((p = (dir <= 0) ? p.left : p.right) == null) {
                            x.parent = xp;
                            if (dir <= 0) {
                                xp.left = x;
                            } else {
                                xp.right = x;
                            }
                            //恢复红黑树特性
                            root = balanceInsertion(root, x);
                            break;
                        }
                    }
                }
            }
            //增加一个节点后， 红黑树的根节点可能改变
            moveRootToFront(tab, root);
		}
		
		//将红黑树转换成链表
		final Node<K, V> untreeify(HashMap<K, V> map) {
			Node<K,V> hd = null, tl = null;
            for (Node<K,V> q = this; q != null; q = q.next) {
                Node<K,V> p = map.replacementNode(q, null);
                if (tl == null) {
                    hd = p;
                } else {
                    tl.next = p;
                }
                tl = p;
            }
            return hd;
		}
		
		/* 
		 * 1如果删除点p的左右子树都为空，或者只有一棵子树非空。
		 * 直接将p删除（左右子树都为空时），或者用非空子树替代p（只有一棵子树非空时）
		 * 
		 * 2如果删除点p的左右子树都非空。可以用p的后继s代替p，
		 * 然后使用情况1删除s
		 * 
		 * 后继 successor
		 * t的右子树不空，则t的后继是其右子树中最小的那个元素。
		 * t的右孩子为空，则t的后继是其第一个向左走的祖先。
		 */
		final void removeTreeNode(HashMap<K, V> map, Node<K, V>[] tab, boolean movable) {
			int n;
            if (tab == null || (n = tab.length) == 0) {
                return;
            }
            int index = (n - 1) & hash;
            TreeNode<K,V> first = (TreeNode<K,V>)tab[index], root = first, rl;
            //successor 给定节点t，其后继是大于t的最小的那个元素
            TreeNode<K,V> succ = (TreeNode<K,V>)next, pred = prev;
            if (pred == null) {
                tab[index] = first = succ;
            } else {
                pred.next = succ;
            }
            if (succ != null) {
                succ.prev = pred;
            }
            if (first == null) {
                return;
            }
            if (root.parent != null) {
                root = root.root();
            }
            if (root == null || root.right == null ||
                (rl = root.left) == null || rl.left == null) {
            	//too small 转换为链表
                tab[index] = first.untreeify(map);  
                return;
            }
            TreeNode<K,V> p = this, pl = left, pr = right, replacement;
            //t的右子树不空，则t的后继是其右子树中最小的那个元素。
            //t的右孩子为空，则t的后继是其第一个向左走的祖先。
            if (pl != null && pr != null) {
                TreeNode<K,V> s = pr, sl;
                while ((sl = s.left) != null) {
                    s = sl;
                }
                boolean c = s.red; s.red = p.red; p.red = c; // swap colors
                TreeNode<K,V> sr = s.right;
                TreeNode<K,V> pp = p.parent;
                if (s == pr) { 
                    p.parent = s;
                    s.right = p;
                } else {
                    TreeNode<K,V> sp = s.parent;
                    if ((p.parent = sp) != null) {
                        if (s == sp.left) {
                            sp.left = p;
                        } else {
                            sp.right = p;
                        }
                    }
                    if ((s.right = pr) != null) {
                        pr.parent = s;
                    }
                }
                p.left = null;
                if ((p.right = sr) != null) {
                    sr.parent = p;
                }
                if ((s.left = pl) != null) {
                    pl.parent = s;
                }
                if ((s.parent = pp) == null) {
                    root = s;
                } else if (p == pp.left) {
                    pp.left = s;
                } else {
                    pp.right = s;
                }
                if (sr != null) {
                    replacement = sr;
                } else {
                    replacement = p;
                }
            } else if (pl != null) {
                replacement = pl;
            } else if (pr != null) {
                replacement = pr;
            } else {
                replacement = p;
            }
            if (replacement != p) {
                TreeNode<K,V> pp = replacement.parent = p.parent;
                if (pp == null) {
                    root = replacement;
                } else if (p == pp.left) {
                    pp.left = replacement;
                } else {
                    pp.right = replacement;
                }
                p.left = p.right = p.parent = null;
            }

            TreeNode<K,V> r = p.red ? root : balanceDeletion(root, replacement);

            if (replacement == p) {  
                TreeNode<K,V> pp = p.parent;
                p.parent = null;
                if (pp != null) {
                    if (p == pp.left) {
                        pp.left = null;
                    } else if (p == pp.right) {
                        pp.right = null;
                    }
                }
            }
            if (movable) {
            	//红黑树删除后的调整可能会改变根节点
                moveRootToFront(tab, r);
            }
		}
		//扩容时需要将拆开红黑树分别放进新的桶中
		final void split(HashMap<K, V> map, Node<K, V>[] tab, int index, int bit) {
			TreeNode<K,V> b = this;
            TreeNode<K,V> loHead = null, loTail = null;
            TreeNode<K,V> hiHead = null, hiTail = null;
            int lc = 0, hc = 0;
            //遍历树
            for (TreeNode<K,V> e = b, next; e != null; e = next) {
                next = (TreeNode<K,V>)e.next;
                e.next = null;
                //e.hash & bit则位置不需要移动
                if ((e.hash & bit) == 0) {
                    if ((e.prev = loTail) == null) {
                        loHead = e;
                    } else {
                        loTail.next = e;
                    }
                    loTail = e;
                    ++lc;
                }
                else {
                    if ((e.prev = hiTail) == null) {
                        hiHead = e;
                    } else {
                        hiTail.next = e;
                    }
                    hiTail = e;
                    ++hc;
                }
            }

            if (loHead != null) {
                if (lc <= UNTREEIFY_THRESHOLD) {
                	//节点太少时转换成链表结构
                    tab[index] = loHead.untreeify(map);
                } else {
                    tab[index] = loHead;
                    if (hiHead != null) {
                    	//将红黑树节点链表转换成红黑树
                        loHead.treeify(tab);
                    }
                }
            }
            //需要移动到index + bit
            if (hiHead != null) {
                if (hc <= UNTREEIFY_THRESHOLD) {
                	//节点太少时转换成链表结构
                    tab[index + bit] = hiHead.untreeify(map);
                } else {
                    tab[index + bit] = hiHead;
                    if (loHead != null) {
                    	//将红黑树节点链表转换成红黑树
                        hiHead.treeify(tab);
                    }
                }
            }
		}
		
		//左旋
		static <K, V> TreeNode<K, V> rotateLeft(TreeNode<K, V> root, TreeNode<K, V> p) {
			TreeNode<K,V> r, pp, rl;
            if (p != null && (r = p.right) != null) {
                if ((rl = p.right = r.left) != null) {
                    rl.parent = p;
                }
                if ((pp = r.parent = p.parent) == null) {
                    (root = r).red = false;
                } else if (pp.left == p) {
                    pp.left = r;
                } else {
                    pp.right = r;
                }
                r.left = p;
                p.parent = r;
            }
            return root;
		}
		
		//右旋
		static <K, V> TreeNode<K, V> rotateRight(TreeNode<K, V> root, TreeNode<K, V> p) {
			TreeNode<K,V> l, pp, lr;
            if (p != null && (l = p.left) != null) {
                if ((lr = p.left = l.right) != null) {
                    lr.parent = p;
                }
                if ((pp = l.parent = p.parent) == null) {
                    (root = l).red = false;
                } else if (pp.right == p) {
                    pp.right = l;
                } else {
                    pp.left = l;
                }
                l.right = p;
                p.parent = l;
            }
            return root;
		}
		
		//插入新节点后恢复红黑树特性
		//几种情况的操作是对称的
		static <K, V> TreeNode<K, V> balanceInsertion(TreeNode<K, V> root, TreeNode<K, V> x) {
			 x.red = true;
	            for (TreeNode<K,V> xp, xpp, xppl, xppr;;) {
	                if ((xp = x.parent) == null) {
	                    x.red = false;
	                    return x;
	                } else if (!xp.red || (xpp = xp.parent) == null) {
	                    return root;
	                }
	                if (xp == (xppl = xpp.left)) {
	                    if ((xppr = xpp.right) != null && xppr.red) {
	                        xppr.red = false;
	                        xp.red = false;
	                        xpp.red = true;
	                        x = xpp;
	                    } else {
	                        if (x == xp.right) {
	                            root = rotateLeft(root, x = xp);
	                            xpp = (xp = x.parent) == null ? null : xp.parent;
	                        }
	                        if (xp != null) {
	                            xp.red = false;
	                            if (xpp != null) {
	                                xpp.red = true;
	                                root = rotateRight(root, xpp);
	                            }
	                        }
	                    }
	                } else {
	                    if (xppl != null && xppl.red) {
	                        xppl.red = false;
	                        xp.red = false;
	                        xpp.red = true;
	                        x = xpp;
	                    } else {
	                        if (x == xp.left) {
	                            root = rotateRight(root, x = xp);
	                            xpp = (xp = x.parent) == null ? null : xp.parent;
	                        }
	                        if (xp != null) {
	                            xp.red = false;
	                            if (xpp != null) {
	                                xpp.red = true;
	                                root = rotateLeft(root, xpp);
	                            }
	                        }
	                    }
	                }
	            }
		}
		
		//删除节点后恢复红黑树特性
		//几种情况的操作是对称的
		static <K, V> TreeNode<K, V> balanceDeletion(TreeNode<K, V> root, TreeNode<K, V> x) {
			for (TreeNode<K,V> xp, xpl, xpr;;)  {
                if (x == null || x == root) {
                    return root;
                } else if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                } else if (x.red) {
                    x.red = false;
                    return root;
                } else if ((xpl = xp.left) == x) {
                    if ((xpr = xp.right) != null && xpr.red) {
                        xpr.red = false;
                        xp.red = true;
                        root = rotateLeft(root, xp);
                        xpr = (xp = x.parent) == null ? null : xp.right;
                    }
                    if (xpr == null) {
                        x = xp;
                    } else {
                        TreeNode<K,V> sl = xpr.left, sr = xpr.right;
                        if ((sr == null || !sr.red) &&
                            (sl == null || !sl.red)) {
                            xpr.red = true;
                            x = xp;
                        } else {
                            if (sr == null || !sr.red) {
                                if (sl != null)
                                    sl.red = false;
                                xpr.red = true;
                                root = rotateRight(root, xpr);
                                xpr = (xp = x.parent) == null ?
                                    null : xp.right;
                            }
                            if (xpr != null) {
                                xpr.red = (xp == null) ? false : xp.red;
                                if ((sr = xpr.right) != null)
                                    sr.red = false;
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateLeft(root, xp);
                            }
                            x = root;
                        }
                    }
                } else { 
                    if (xpl != null && xpl.red) {
                        xpl.red = false;
                        xp.red = true;
                        root = rotateRight(root, xp);
                        xpl = (xp = x.parent) == null ? null : xp.left;
                    }
                    if (xpl == null) {
                        x = xp;
                    } else {
                        TreeNode<K,V> sl = xpl.left, sr = xpl.right;
                        if ((sl == null || !sl.red) &&
                            (sr == null || !sr.red)) {
                            xpl.red = true;
                            x = xp;
                        } else {
                            if (sl == null || !sl.red) {
                                if (sr != null) {
                                    sr.red = false;
                                }
                                xpl.red = true;
                                root = rotateLeft(root, xpl);
                                xpl = (xp = x.parent) == null ?
                                    null : xp.left;
                            }
                            if (xpl != null) {
                                xpl.red = (xp == null) ? false : xp.red;
                                if ((sl = xpl.left) != null) {
                                    sl.red = false;
                                }
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateRight(root, xp);
                            }
                            x = root;
                        }
                    }
                }
            }
		}
		
		//检查是否符合红黑树的特性
		static <K, V> boolean checkInvariants(TreeNode<K, V> t) {
			TreeNode<K,V> tp = t.parent, tl = t.left, tr = t.right,
	                tb = t.prev, tn = (TreeNode<K,V>)t.next;
	            if (tb != null && tb.next != t) {
	                return false;
	            }
	            if (tn != null && tn.prev != t) {
	                return false;
	            }
	            if (tp != null && t != tp.left && t != tp.right) {
	                return false;
	            }
	            if (tl != null && (tl.parent != t || tl.hash > t.hash)) {
	                return false;
	            }
	            if (tr != null && (tr.parent != t || tr.hash < t.hash)) {
	                return false;
	            }
	            if (t.red && tl != null && tl.red && tr != null && tr.red) {
	                return false;
	            }
	            if (tl != null && !checkInvariants(tl)) {
	                return false;
	            }
	            if (tr != null && !checkInvariants(tr)) {
	                return false;
	            }
	            return true;
	        }
		}
				
}

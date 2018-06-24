package com.webmvc.util.map;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * @author sgz
 */
public abstract class AbstractMap<K, V> implements Map<K, V>{

	protected AbstractMap() {	
	}
	
	/**
	 * @return Map中一共有多少对元素
	 */
	@Override
	public int size() {
		return entrySet().size();
	}
	
	
	/**
	 * @return Map是否为空
	 */
	@Override
	public boolean isEmpty() {
		return size() == 0;
	}
	
	/**
	 * @param value
	 * @return Map中的值是否包含参数value
	 */
	@Override
	public boolean containsValue(Object value) {
		Iterator<Entry<K, V>> i = entrySet().iterator();
		if (value == null) {
			while (i.hasNext()) {
				Entry<K, V> e = i.next();
				if (e.getValue() == null) {
					return true;
				}			
			}
		} else {
			while (i.hasNext()) {
				Entry<K, V> e = i.next();
				if (e.getValue().equals(value)) {
					return true;
				}
			}
		}
		return false;
	}
	
	/**
	 * @param key
	 * @return Map中的键是否包含参数key
	 */
	@Override
	public boolean containsKey(Object key) {
		Iterator<Entry<K, V>> i = entrySet().iterator();
		if (key == null) {
			while (i.hasNext()) {
				Entry<K, V> e = i.next();
				if (e.getKey() == null) {
					return true;
				}			
			}
		} else {
			while (i.hasNext()) {
				Entry<K, V> e = i.next();
				if (e.getKey().equals(key)) {
					return true;
				}			
			}
		}
		return false;
	}
	
	/**
	 * 根据参数key从Map中获取value
	 */
	@Override
	public V get(Object key) {
		Iterator<Entry<K, V>> i = entrySet().iterator();
		if (key == null) {
			while (i.hasNext()) {
				Entry<K, V> e = i.next();
				if (e.getKey() == null) {
					return e.getValue();
				}
			}
		}
		return null;
	}
	
	@Override
	public V put(K key, V value) {
		/*如子类没重写则抛出异常*/
		throw new UnsupportedOperationException();
	}
	
	@Override
	public V remove(Object key) {
		Iterator<Entry<K, V>> i = entrySet().iterator();
		Entry<K, V> correctEntry = null;
		if (key == null) {
			while (correctEntry == null && i.hasNext()) {
				Entry<K, V> e = i.next();
				if (e.getKey() == null) {
					correctEntry = e;
				}
			}
		} else {
			while (correctEntry == null && i.hasNext()) {
				Entry<K, V> e = i.next();
				if (e.getKey().equals(key)) {
					correctEntry = e;
				}
			}
		}
		
		V oldValue = null;
		if (correctEntry != null) {
			oldValue = correctEntry.getValue();
			i.remove();
		}
 		return oldValue;
	}
	
	/*PECS*/
	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
			put(e.getKey(), e.getValue());
		}
	}
	
	/**
	 * 清空Map
	 */
	@Override
	public void clear() {
		entrySet().clear();
	}
	
	
	transient Set<K> keySet;
	transient Collection<V> values;
	
	/**
	 * @return 键的Set
	 */
	@Override
	public Set<K> keySet() {
		Set<K> ks = keySet;
		if (ks == null) {
			ks = new AbstractSet<K>() {

				@Override
				public Iterator<K> iterator() {
					return new Iterator<K>() {
						private Iterator<Entry<K, V>> i = entrySet().iterator();
						
						@Override
						public boolean hasNext() {
							return i.hasNext();
						}

						@Override
						public K next() {
							return i.next().getKey();
						}
						
						@Override
						public void remove() {
							i.remove();
						}
					};
				}

				@Override
				public int size() {
					return AbstractMap.this.size();
				}
				
				@Override
				public boolean isEmpty() {
					return AbstractMap.this.isEmpty();
				}
				
				@Override
				public void clear() {
					AbstractMap.this.clear();
				}
				
				@Override
				public boolean contains(Object o) {
					return AbstractMap.this.containsKey(o);
				}
			};
			keySet = ks;
		}
		return ks;
	}
	
	/**
	 * @return 值的集合
	 */
	@Override
	public Collection<V> values() {
		Collection<V> vals = values;
		if (vals == null) {
			vals = new AbstractCollection<V>() {

				@Override
				public Iterator<V> iterator() {
					return new Iterator<V>() {
						
						private Iterator<Entry<K, V>> i = entrySet().iterator();

						@Override
						public boolean hasNext() {
							return i.hasNext();
						}

						@Override
						public V next() {
							return i.next().getValue();
						}
						
						@Override
						public void remove() {
							i.remove();
						}
					};
				}

				@Override
				public int size() {
					return AbstractMap.this.size();
				}
				
				@Override
				public boolean isEmpty() {
					return AbstractMap.this.isEmpty();
				}
				
				@Override
				public void clear() {
					AbstractMap.this.clear();
				}
				
				@Override
				public boolean contains(Object o) {
					// TODO Auto-generated method stub
					return AbstractMap.this.containsValue(o);
				}
			};
			values = vals;
		}
		return vals;
	}
	
	
	
	@Override
	public boolean equals(Object o) {
        if (o == this)
            return true;

        if (!(o instanceof Map))
            return false;
        Map<?,?> m = (Map<?,?>) o;
        if (m.size() != size())
            return false;

        try {
            Iterator<Entry<K,V>> i = entrySet().iterator();
            while (i.hasNext()) {
                Entry<K,V> e = i.next();
                K key = e.getKey();
                V value = e.getValue();
                if (value == null) {
                    if (!(m.get(key)==null && m.containsKey(key)))
                        return false;
                } else {
                    if (!value.equals(m.get(key)))
                        return false;
                }
            }
        } catch (ClassCastException unused) {
            return false;
        } catch (NullPointerException unused) {
            return false;
        }

        return true;
    }

	@Override
	public int hashCode() {
        int h = 0;
        Iterator<Entry<K,V>> i = entrySet().iterator();
        while (i.hasNext())
            h += i.next().hashCode();
        return h;
    }
	
	@Override
	public String toString() {
        Iterator<Entry<K,V>> i = entrySet().iterator();
        if (! i.hasNext())
            return "{}";

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (;;) {
            Entry<K,V> e = i.next();
            K key = e.getKey();
            V value = e.getValue();
            sb.append(key   == this ? "(this Map)" : key);
            sb.append('=');
            sb.append(value == this ? "(this Map)" : value);
            if (! i.hasNext())
                return sb.append('}').toString();
            sb.append(',').append(' ');
        }
    }
	
	@Override
	public abstract Set<Entry<K, V>> entrySet();
}

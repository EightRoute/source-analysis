package com.google.common.cache;


import com.google.common.annotations.GwtCompatible;


@GwtCompatible
public enum RemovalCause {
    /**
     * 键值被用户手动删除，当用户调用invalidate，invalidateAll，remove时， 会发生这种情况
     */
    EXPLICIT {
        @Override
        boolean wasEvicted() {
            return false;
        }
    },

    /**
     * 键值发生替换。当用户调用put，refresh，putAll， replace时， 会发生这种情况 。
     */
    REPLACED {
        @Override
        boolean wasEvicted() {
            return false;
        }
    },

    /**
     * 垃圾回收引起键值被自动清除，在使用weakKeys，weakValues 或 softValues时， 会发生这种情况
     */
    COLLECTED {
        @Override
        boolean wasEvicted() {
            return true;
        }
    },

    /**
     * 键值过期，在使用expireAfterAccess 或 expireAfterWrite时，会发生这种情况。
     */
    EXPIRED {
        @Override
        boolean wasEvicted() {
            return true;
        }
    },

    /**
     * 缓存大小限制引起键值被清除，在使用maximumSize 或 maximumWeight时，会发生这种情况。
     */
    SIZE {
        @Override
        boolean wasEvicted() {
            return true;
        }
    };


    abstract boolean wasEvicted();
}

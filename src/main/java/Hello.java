import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * Created by sgz
 * 2018/6/23 13:11
 */
public class Hello  {


    private static class MyRemovalListener implements RemovalListener<Integer, Integer> {
        @Override
        public void onRemoval(RemovalNotification<Integer, Integer> notification) {
            String tips = String.format("key=%s,value=%s,reason=%s", notification.getKey(), notification.getValue(), notification.getCause());
            System.out.println(tips);
        }
    }

    public static void main(String[] args) {

        // 创建一个带有RemovalListener监听的缓存
        Cache<Integer, Integer> cache = CacheBuilder.newBuilder().removalListener(new MyRemovalListener()).build();

        cache.put(1, 1);

        // 手动清除
        cache.invalidate(1);

        System.out.println(cache.getIfPresent(1)); // null

    }
}

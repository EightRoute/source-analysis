package redis.clients.jedis;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import redis.clients.jedis.exceptions.InvalidURIException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.util.JedisURIHelper;


/**
 * 创建jedis对象的工厂，类实现了org.apache.commons.pool2.PooledObjectFactory接口
 */
class JedisFactory implements PooledObjectFactory<Jedis> {
    private final AtomicReference<HostAndPort> hostAndPort = new AtomicReference();
    private final int connectionTimeout;
    private final int soTimeout;
    private final String password;
    private final int database;
    private final String clientName;
    private final boolean ssl;
    private final SSLSocketFactory sslSocketFactory;
    private SSLParameters sslParameters;
    private HostnameVerifier hostnameVerifier;

    public JedisFactory(String host, int port, int connectionTimeout, int soTimeout, String password,
                        int database, String clientName, boolean ssl,
                        SSLSocketFactory sslSocketFactory, SSLParameters sslParameters,
                        HostnameVerifier hostnameVerifier) {
        this.hostAndPort.set(new HostAndPort(host, port));
        this.connectionTimeout = connectionTimeout;
        this.soTimeout = soTimeout;
        this.password = password;
        this.database = database;
        this.clientName = clientName;
        this.ssl = ssl;
        this.sslSocketFactory = sslSocketFactory;
        this.sslParameters = sslParameters;
        this.hostnameVerifier = hostnameVerifier;
    }

    public JedisFactory(URI uri, int connectionTimeout, int soTimeout, String clientName, boolean ssl,
                        SSLSocketFactory sslSocketFactory, SSLParameters sslParameters,
                        HostnameVerifier hostnameVerifier) {
        if (!JedisURIHelper.isValid(uri)) {
            throw new InvalidURIException(String.format("Cannot open Redis connection due invalid URI. %s", uri.toString()));
        } else {
            this.hostAndPort.set(new HostAndPort(uri.getHost(), uri.getPort()));
            this.connectionTimeout = connectionTimeout;
            this.soTimeout = soTimeout;
            this.password = JedisURIHelper.getPassword(uri);
            this.database = JedisURIHelper.getDBIndex(uri);
            this.clientName = clientName;
            this.ssl = ssl;
            this.sslSocketFactory = sslSocketFactory;
            this.sslParameters = sslParameters;
            this.hostnameVerifier = hostnameVerifier;
        }
    }

    public void setHostAndPort(HostAndPort hostAndPort) {
        this.hostAndPort.set(hostAndPort);
    }

    /**
     * 重新初始化池返回的实例.
     */
    @Override
    public void activateObject(PooledObject<Jedis> pooledJedis) throws Exception {
        BinaryJedis jedis = (BinaryJedis)pooledJedis.getObject();
        if (jedis.getDB() != (long)this.database) {
            jedis.select(this.database);
        }

    }


    /**
     * 销毁池中不再需要的实例。
     */
    @Override
    public void destroyObject(PooledObject<Jedis> pooledJedis) throws Exception {
        BinaryJedis jedis = (BinaryJedis)pooledJedis.getObject();
        if (jedis.isConnected()) {
            try {
                try {
                    jedis.quit();
                } catch (Exception var4) {
                    ;
                }

                jedis.disconnect();
            } catch (Exception var5) {
                ;
            }
        }

    }


    /**
     * 创建一个实例
     */
    @Override
    public PooledObject<Jedis> makeObject() throws Exception {
        HostAndPort hostAndPort = (HostAndPort)this.hostAndPort.get();
        Jedis jedis = new Jedis(hostAndPort.getHost(), hostAndPort.getPort(),
                this.connectionTimeout, this.soTimeout, this.ssl, this.sslSocketFactory,
                this.sslParameters, this.hostnameVerifier);

        try {
            jedis.connect();
            if (null != this.password) {
                jedis.auth(this.password);
            }

            if (this.database != 0) {
                jedis.select(this.database);
            }

            if (this.clientName != null) {
                jedis.clientSetname(this.clientName);
            }
        } catch (JedisException e) {
            jedis.close();
            throw e;
        }

        return new DefaultPooledObject(jedis);
    }

    /**
     * 取消初始化要返回空闲对象池的实例
     */
    @Override
    public void passivateObject(PooledObject<Jedis> pooledJedis) throws Exception {
    }

    /**
     * 验证对象,确保池可以安全地返回实例。
     */
    @Override
    public boolean validateObject(PooledObject<Jedis> pooledJedis) {
        BinaryJedis jedis = (BinaryJedis)pooledJedis.getObject();

        try {
            HostAndPort hostAndPort = (HostAndPort)this.hostAndPort.get();
            String connectionHost = jedis.getClient().getHost();
            int connectionPort = jedis.getClient().getPort();
            return hostAndPort.getHost().equals(connectionHost) && hostAndPort.getPort() == connectionPort
                    && jedis.isConnected() && jedis.ping().equals("PONG");
        } catch (Exception var6) {
            return false;
        }
    }
}

package it.ratelim.client;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

public class ApiClientTest {
  private ApiClient apiClient;

  @Before
  public void setup() throws IOException {
    net.spy.memcached.MemcachedClient memcachedClient = new net.spy.memcached.MemcachedClient(
        new InetSocketAddress("localhost", 11211));

    apiClient = new ApiClient.Builder()
        .setApikey("1|test")
        .setPort(8080)
        .setHost("127.0.0.1")
        .setMemcachedClientIF(memcachedClient)
        .build();
  }

  @Test
  public void isPass() throws Exception {

  }

}

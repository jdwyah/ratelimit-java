package it.ratelim.client.util;

import net.spy.memcached.MemcachedClientIF;

public class MemcachedWrapper implements Cache {
  private final MemcachedClientIF memcachedClientIF;

  public MemcachedWrapper(MemcachedClientIF memcachedClientIF) {
    this.memcachedClientIF = memcachedClientIF;
  }

  @Override
  public byte[] get(String s) {
    return (byte[]) memcachedClientIF.get(s);
  }

  @Override
  public void set(String key, int expiryInSeconds, byte[] bytes) {
    memcachedClientIF.set(key, expiryInSeconds, bytes);
  }
}

package it.ratelim.client.util;

import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.SetArgs;
import com.lambdaworks.redis.api.async.RedisAsyncCommands;

import java.util.concurrent.ExecutionException;

public class RedisWrapper implements Cache {
  final RedisAsyncCommands<String, byte[]> redis;

  public RedisWrapper(RedisClient redisClient) {
    redis = redisClient.connect(new BinaryRedisCodec()).async();
  }

  @Override
  public byte[] get(String s) throws ExecutionException, InterruptedException {
    return redis.get(s).get();
  }

  @Override
  public void set(String key, int expiryInSeconds, byte[] bytes) {
    redis.set(key, bytes, SetArgs.Builder.ex(expiryInSeconds));
  }
}

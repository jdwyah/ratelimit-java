package it.ratelim.client.util;

import com.lambdaworks.redis.codec.RedisCodec;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

import static java.nio.charset.CoderResult.OVERFLOW;

public class BinaryRedisCodec implements RedisCodec<String, byte[]> {
  private Charset charset;
  private CharsetDecoder decoder;
  private CharBuffer chars;

  public BinaryRedisCodec() {
    charset = Charset.forName("UTF-8");
    decoder = charset.newDecoder();
    chars = CharBuffer.allocate(1024);
  }

  @Override
  public String decodeKey(ByteBuffer bytes) {
    return decodeString(bytes);
  }

  @Override
  public byte[] decodeValue(ByteBuffer bytes) {
    return decodeBytes(bytes);
  }

  @Override
  public ByteBuffer encodeKey(String key) {
    return ByteBuffer.wrap(key.getBytes());
  }

  @Override
  public ByteBuffer encodeValue(byte[] value) {
    return ByteBuffer.wrap(value);
  }

  private byte[] decodeBytes(ByteBuffer bytes) {
    try {
      byte[] ba = new byte[bytes.remaining()];
      bytes.get(ba);
      return ba;
    } catch (Exception e) {
      return null;
    }
  }

  private String decodeString(ByteBuffer bytes) {
    chars.clear();
    bytes.mark();

    decoder.reset();
    while (decoder.decode(bytes, chars, true) == OVERFLOW || decoder.flush(chars) == OVERFLOW) {
      chars = CharBuffer.allocate(chars.capacity() * 2);
      bytes.reset();
    }

    return chars.flip().toString();
  }

}

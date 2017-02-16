package it.ratelim.client;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import it.ratelim.data.RateLimitProtos;

import java.util.List;
import java.util.Optional;

public class FeatureFlagProcessor {
  private static final HashFunction hash = Hashing.murmur3_32();
  private static final long UNSIGNED_INT_MAX = Integer.MAX_VALUE + (long) Integer.MAX_VALUE;

  public static boolean isOnFor(RateLimitProtos.FeatureFlag featureFlag, Optional<String> key, List<String> attributes) {

    if (key.isPresent()) {
      attributes.add(key.get());
    }
    attributes.retainAll(featureFlag.getWhitelistedList());
    if (!attributes.isEmpty()) {
      return true;
    }

    if (key.isPresent()) {
      final String toHash = String.format("%d%s%s", featureFlag.getAccountId(), featureFlag.getFeature(), key.get());
      return getUserPct(toHash) < featureFlag.getPct();
    }

    return featureFlag.getPct() > .999;
  }

  static double getUserPct(String toHash) {
    final HashCode hashCode = hash.hashBytes(toHash.getBytes());
    return pct(hashCode.asInt());
  }

  private static double pct(int signedInt) {
    long y = signedInt & 0x00000000ffffffffL;
    return y / (double) (UNSIGNED_INT_MAX);
  }
}

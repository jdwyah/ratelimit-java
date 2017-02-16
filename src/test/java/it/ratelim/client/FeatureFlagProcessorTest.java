package it.ratelim.client;

import com.google.common.collect.Lists;
import it.ratelim.data.RateLimitProtos;
import org.junit.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class FeatureFlagProcessorTest {

  @Test
  public void testHashing() {

    assertThat(FeatureFlagProcessor.getUserPct("1aaa1")).isEqualTo(0.2726196128747517);
    assertThat(FeatureFlagProcessor.getUserPct("1aab1")).isEqualTo(0.4034631137752268);
    assertThat(FeatureFlagProcessor.getUserPct("1aac1")).isEqualTo(0.41195494584364584);
    assertThat(FeatureFlagProcessor.getUserPct("1123123ddddd123123")).isEqualTo(0.2100869543897393);
  }

  @Test
  public void isAvailableForComplexWhitelists(){
    final int accountId = 111111;
    final RateLimitProtos.FeatureFlag feat = RateLimitProtos.FeatureFlag.newBuilder()
        .setAccountId(accountId)
        .setFeature("feat")
        .setPct(0)
        .addWhitelisted("user:123_t1")
        .addWhitelisted("user:456_t1")
        .addWhitelisted("user:786_t2")
        .addWhitelisted("team:3")
        .build();

    assertThat(FeatureFlagProcessor.isOnFor(feat, Optional.empty(), Lists.newArrayList())).isFalse();
    assertThat(FeatureFlagProcessor.isOnFor(feat, Optional.of("a"), Lists.newArrayList())).isFalse();
    assertThat(FeatureFlagProcessor.isOnFor(feat, Optional.of("user:123_t1"), Lists.newArrayList())).isTrue();
    assertThat(FeatureFlagProcessor.isOnFor(feat, Optional.of("XXX"), Lists.newArrayList("user:786_t2"))).isTrue();
    assertThat(FeatureFlagProcessor.isOnFor(feat, Optional.of("XXX"), Lists.newArrayList("user:XXX", "team:1"))).isFalse();
    assertThat(FeatureFlagProcessor.isOnFor(feat, Optional.of("XXX"), Lists.newArrayList("user:XXX", "team:3"))).isTrue();

  }
}

package it.ratelim.client;

import com.google.common.collect.Lists;
import it.ratelim.data.RateLimitProtos;
import org.junit.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class FeatureFlagProcessorTest {

  @Test
  public void testHashing() {
    FeatureFlagWrapper ffw = new FeatureFlagWrapper(null);
    assertThat(ffw.getUserPct("1aaa1")).isEqualTo(0.2726196128747517);
    assertThat(ffw.getUserPct("1aab1")).isEqualTo(0.4034631137752268);
    assertThat(ffw.getUserPct("1aac1")).isEqualTo(0.41195494584364584);
    assertThat(ffw.getUserPct("1123123ddddd123123")).isEqualTo(0.2100869543897393);
  }

  @Test
  public void isAvailableForComplexWhitelists() {
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

    FeatureFlagWrapper ffw = new FeatureFlagWrapper(feat);


    assertThat(ffw.isOnFor(Optional.empty(), Lists.newArrayList())).isFalse();
    assertThat(ffw.isOnFor(Optional.of("a"), Lists.newArrayList())).isFalse();
    assertThat(ffw.isOnFor(Optional.of("user:123_t1"), Lists.newArrayList())).isTrue();
    assertThat(ffw.isOnFor(Optional.of("XXX"), Lists.newArrayList("user:786_t2"))).isTrue();
    assertThat(ffw.isOnFor(Optional.of("XXX"), Lists.newArrayList("user:XXX", "team:1"))).isFalse();
    assertThat(ffw.isOnFor(Optional.of("XXX"), Lists.newArrayList("user:XXX", "team:3"))).isTrue();

  }

  @Test
  public void random() {
    final int accountId = 111111;
    final RateLimitProtos.FeatureFlag feat = RateLimitProtos.FeatureFlag.newBuilder()
        .setAccountId(accountId)
        .setFeature("feat")
        .setPct(.9)
        .build();
    FeatureFlagWrapper ffw = new FeatureFlagWrapper(feat);

    ffw.setRandomProvider(() -> .6);
    assertThat(ffw.isOnFor(Optional.empty(), Lists.newArrayList())).isTrue();

    ffw.setRandomProvider(() -> .97);
    assertThat(ffw.isOnFor(Optional.empty(), Lists.newArrayList())).isFalse();

  }
}

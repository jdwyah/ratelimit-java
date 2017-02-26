package it.ratelim.client;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.annotation.Timed;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import it.ratelim.client.util.Cache;
import it.ratelim.client.util.MemcachedWrapper;
import it.ratelim.data.RateLimitProtos;
import net.spy.memcached.MemcachedClientIF;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.google.common.cache.CacheBuilder.newBuilder;

public class ApiClient implements Closeable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ApiClient.class);

  private final CloseableHttpClient httpClient;
  private final String urlBase;
  private final String accountId;
  private final ApiClientMetrics apiClientMetrics;
  private Optional<Cache> distributedCache = Optional.empty();
  private Optional<String> featureFlagCacheKey = Optional.empty();

  private LoadingCache<String, Optional<RateLimitProtos.FeatureFlag>> inProcessFlagCache;
  private final int featureFlagMemcachedSecs;
  private final long featureFlagRefetchBuffer;
  private final int featureFlagInProcessCacheSecs;

  private Executor background = Executors.newSingleThreadExecutor();

  public ApiClient(Builder builder) {

    this.distributedCache = builder.getDistributedCache();
    this.featureFlagMemcachedSecs = builder.getFeatureFlagMemcachedSecs();
    this.featureFlagRefetchBuffer = builder.getFeatureFlagRefetchBuffer();
    this.featureFlagInProcessCacheSecs = builder.getFeatureFlagInProcessCacheSecs();

    this.apiClientMetrics = new ApiClientMetrics(builder.getMetricRegistry());

    String password;
    if (builder.getApikey() == null) {
      LOGGER.warn("Misconfigured RateLimitAPIClient. No API KEY. Set RATELIMIT_API_KEY");
      accountId = "";
      password = "";
    } else {
      final String[] apikeyparts = builder.getApikey().split("\\|");
      this.accountId = apikeyparts[0];
      password = apikeyparts[1];
    }
    this.urlBase = String.format("%s://%s:%d/api/v1/",
        builder.getPort() == 443 ? "https" : "http",
        builder.getHost(),
        builder.getPort());

    httpClient = setupHttpClient(builder, password);

    inProcessFlagCache = newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(featureFlagInProcessCacheSecs, TimeUnit.SECONDS)
        .build(
            new CacheLoader<String, Optional<RateLimitProtos.FeatureFlag>>() {
              public Optional<RateLimitProtos.FeatureFlag> load(String feature) throws IOException {
                return findFlag(feature);
              }
            });
  }

  private CloseableHttpClient setupHttpClient(Builder builder, String pass) {
    CredentialsProvider credsProvider = new BasicCredentialsProvider();

    final UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(accountId, pass);
    credsProvider.setCredentials(
        new AuthScope(builder.getHost(), builder.getPort()),
        credentials);

    Header header = new BasicHeader(HttpHeaders.CONTENT_TYPE, Consts.PROTO_BUF_CONTENT_TYPE);
    List<Header> headers = new ArrayList<>();
    headers.add(header);
    headers.add(BasicScheme.authenticate(credentials, "UTF8", false));//pre-emptive auth

    return HttpClients.custom()
        .setDefaultHeaders(headers)
        .setDefaultCredentialsProvider(credsProvider)
        .build();
  }


  public boolean isPass(String key) {
    final RateLimitProtos.LimitResponse limitResponse = limitCheck(RateLimitProtos.LimitRequest.newBuilder().addGroups(key).build());
    return limitResponse.getPassed();
  }

  public RateLimitProtos.LimitResponse limitCheck(RateLimitProtos.LimitRequest limitRequest) {
    return limitCheck(limitRequest, RateLimitProtos.OnFailure.LOG_AND_PASS);
  }

  @Timed
  public RateLimitProtos.LimitResponse limitCheck(RateLimitProtos.LimitRequest limitRequest, RateLimitProtos.OnFailure onFailure) {

    HttpPost httppost = new HttpPost(getUrl("limitcheck"));
    ByteArrayEntity entity = new ByteArrayEntity(limitRequest.toByteArray());
    httppost.setEntity(entity);

    ResponseHandler<RateLimitProtos.LimitResponse> responseHandler = response -> {
      int status = response.getStatusLine().getStatusCode();
      if (status >= 200 && status < 300) {
        final RateLimitProtos.LimitResponse limitResponse = RateLimitProtos.LimitResponse.parseFrom(EntityUtils.toByteArray(response.getEntity()));
        if (limitResponse.getPassed()) {
          apiClientMetrics.mark(ApiClientMetrics.METRICS.IT_RATELIM_LIMIT_CHECK_PASS);
        } else {
          apiClientMetrics.mark(ApiClientMetrics.METRICS.IT_RATELIM_LIMIT_CHECK_HIT);
        }
        return limitResponse;
      } else {
        throw new ClientProtocolException("Unexpected response status: " + status);
      }
    };
    try {
      return httpClient.execute(httppost, responseHandler);
    } catch (IOException e) {
      return handleError(e, Optional.of(limitRequest), onFailure);
    }
  }

  private RateLimitProtos.LimitResponse handleError(Exception e, Optional<RateLimitProtos.LimitRequest> limitRequest, RateLimitProtos.OnFailure onFailure) {
    String errorMsg = "Problem";
    if (limitRequest.isPresent()) {
      errorMsg = "Problem " + limitRequest.get().getGroupsList();
    }
    switch (onFailure) {
      case LOG_AND_FAIL:
        LOGGER.warn(errorMsg, e);
        return RateLimitProtos.LimitResponse.newBuilder().setPassed(false).build();
      case LOG_AND_PASS:
        LOGGER.warn(errorMsg, e);
        return RateLimitProtos.LimitResponse.newBuilder().setPassed(true).build();
      case THROW:
        LOGGER.warn(errorMsg, e);
        throw new RateLimitException(e);
    }
    throw new RuntimeException("Unknown Failure Handing State");
  }

  private String getUrl(String endpoint) {
    return urlBase + endpoint;
  }

  public Collection<RateLimitProtos.LimitDefinition> limitGetAll() throws IOException {
    HttpGet req = new HttpGet(getUrl("limits"));

    ResponseHandler<RateLimitProtos.LimitDefinitions> responseHandler = response -> {
      int status = response.getStatusLine().getStatusCode();
      if (status >= 200 && status < 300) {
        return RateLimitProtos.LimitDefinitions.parseFrom(EntityUtils.toByteArray(response.getEntity()));
      } else {
        throw new ClientProtocolException("Unexpected response status: " + status);
      }
    };
    return httpClient.execute(req, responseHandler).getDefinitionsList();
  }

  @Timed
  public void limitReturn(RateLimitProtos.LimitResponse limitResponse) throws IOException {
    HttpPost httppost = new HttpPost(getUrl("limitreturn"));
    httppost.setEntity(new ByteArrayEntity(limitResponse.toByteArray()));
    httpClient.execute(httppost);
  }

  /**
   * create only, don't overwrite if it exists
   *
   * @param limitDefinition
   * @throws IOException
   */
  @Timed
  public void limitCreate(RateLimitProtos.LimitDefinition limitDefinition) throws IOException {
    HttpPost httppost = new HttpPost(getUrl("limits"));
    ByteArrayEntity entity = new ByteArrayEntity(limitDefinition.toByteArray());
    httppost.setEntity(entity);
    final CloseableHttpResponse execute = httpClient.execute(httppost);
  }

  /**
   * create or replace
   *
   * @param limitDefinition
   * @throws IOException
   */
  @Timed
  public void limitUpsert(RateLimitProtos.LimitDefinition limitDefinition) throws IOException {
    HttpPut httpPut = new HttpPut(getUrl("limits"));
    ByteArrayEntity entity = new ByteArrayEntity(limitDefinition.toByteArray());
    httpPut.setEntity(entity);
    final CloseableHttpResponse execute = httpClient.execute(httpPut);
  }


  public boolean featureIsOn(String feature) {
    return featureIsOnFor(feature, Optional.empty(), Lists.newArrayList());
  }

  public boolean featureIsOnFor(String feature, String lookupKey) {
    return featureIsOnFor(feature, Optional.of(lookupKey), Lists.newArrayList());
  }

  @Timed
  public boolean featureIsOnFor(String feature, Optional<String> lookupKey, List<String> attributes) {
    try {
      final Optional<RateLimitProtos.FeatureFlag> featureFlag = inProcessFlagCache.get(feature);

      if (!featureFlag.isPresent()) {
        return false;
      }
      return new FeatureFlagWrapper(featureFlag.get()).isOnFor(lookupKey, attributes);

    } catch (ExecutionException e) {
      handleError(e, Optional.empty(), RateLimitProtos.OnFailure.LOG_AND_PASS);
    }

    return false;
  }

  private Optional<RateLimitProtos.FeatureFlag> findFlag(String feature) {
    return getAllFlags().stream()
        .filter((ff) -> ff.getFeature().equals(feature))
        .findFirst();
  }

  @Timed
  public Collection<RateLimitProtos.FeatureFlag> getAllFlags() {
    try {
      if (distributedCache.isPresent()) {
        if (!featureFlagCacheKey.isPresent()) {
          featureFlagCacheKey = Optional.of(String.format("it.ratelim.java.%s.featureflags", accountId));
        }

        final byte[] bytes = distributedCache.get().get(featureFlagCacheKey.get());
        RateLimitProtos.FeatureFlags featureFlags;
        if (bytes == null) {
          featureFlags = getAllFlagsApiRequest();
          distributedCache.get().set(featureFlagCacheKey.get(), featureFlagMemcachedSecs, featureFlags.toByteArray());
          return featureFlags.getFlagsList();
        } else {
          featureFlags = RateLimitProtos.FeatureFlags.parseFrom(bytes);

          fetchAndCacheFlagsAsyncIfNecessary(featureFlags);

          return featureFlags.getFlagsList();
        }
      } else {
        return getAllFlagsApiRequest().getFlagsList();
      }
    } catch (IOException | ExecutionException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * if it's almost time for memcache to expire, reload all flags (add some jitter to help avoid dogpiling)
   *
   * @param featureFlags
   */
  private void fetchAndCacheFlagsAsyncIfNecessary(RateLimitProtos.FeatureFlags featureFlags) {
    if (featureFlags.getCacheExpiry() < DateTime.now().getMillis() + featureFlagRefetchBuffer * Math.random()) {
      background.execute(() -> {
        try {
          final RateLimitProtos.FeatureFlags newFeatureFlags = getAllFlagsApiRequest();
          distributedCache.get().set(featureFlagCacheKey.get(), featureFlagMemcachedSecs, newFeatureFlags.toByteArray());
        } catch (IOException e) {
          LOGGER.warn("Exception trying background feature flag sync", e);
        }
      });
    }
  }

  @Timed
  RateLimitProtos.FeatureFlags getAllFlagsApiRequest() throws IOException {
    HttpGet req = new HttpGet(getUrl("featureflags"));
    ResponseHandler<RateLimitProtos.FeatureFlags> responseHandler = response -> {
      int status = response.getStatusLine().getStatusCode();
      if (status >= 200 && status < 300) {
        final RateLimitProtos.FeatureFlags featureFlags = RateLimitProtos.FeatureFlags.parseFrom(EntityUtils.toByteArray(response.getEntity()));
        return featureFlags.toBuilder()
            .setCacheExpiry(DateTime.now().getMillis() + featureFlagMemcachedSecs)
            .build();
      } else {
        throw new ClientProtocolException("Unexpected response status: " + status);
      }
    };
    return httpClient.execute(req, responseHandler);
  }

  @Override
  public void close() throws IOException {
    httpClient.close();
  }


  public static class Builder {
    private String host = "www.ratelim.it";
    private int port = 443;
    private String apikey;
    private Optional<Cache> distributedCache = Optional.empty();
    private Optional<MetricRegistry> metricRegistry = Optional.empty();
    private int featureFlagMemcachedSecs = 60;
    private long featureFlagRefetchBuffer = 10;
    private int featureFlagInProcessCacheSecs = 50;


    public Builder() {
      this.apikey = System.getenv("RATELIMIT_API_KEY");
    }

    public int getPort() {
      return port;
    }

    public Builder setPort(int port) {
      this.port = port;
      return this;
    }

    public Optional<Cache> getDistributedCache() {
      return distributedCache;
    }

    public Builder setMemcachedClientIF(MemcachedClientIF memcachedClientIF) {
      this.distributedCache = Optional.of(new MemcachedWrapper(memcachedClientIF));
      return this;
    }

    public Builder setDistributedCache(Cache distributedCache) {
      this.distributedCache = Optional.of(distributedCache);
      return this;
    }

    public String getHost() {
      return host;
    }

    public Builder setHost(String host) {
      this.host = host;
      return this;
    }

    public Builder setApikey(String apikey) {
      this.apikey = apikey;
      return this;
    }

    public String getApikey() {
      return apikey;
    }

    public Optional<MetricRegistry> getMetricRegistry() {
      return metricRegistry;
    }

    public Builder setMetricRegistry(MetricRegistry metricRegistry) {
      this.metricRegistry = Optional.of(metricRegistry);
      return this;
    }

    public int getFeatureFlagMemcachedSecs() {
      return featureFlagMemcachedSecs;
    }

    public Builder setFeatureFlagMemcachedSecs(int featureFlagMemcachedSecs) {
      this.featureFlagMemcachedSecs = featureFlagMemcachedSecs;
      return this;
    }

    public long getFeatureFlagRefetchBuffer() {
      return featureFlagRefetchBuffer;
    }

    public Builder setFeatureFlagRefetchBuffer(long featureFlagRefetchBuffer) {
      this.featureFlagRefetchBuffer = featureFlagRefetchBuffer;
      return this;
    }

    public int getFeatureFlagInProcessCacheSecs() {
      return featureFlagInProcessCacheSecs;
    }

    public Builder setFeatureFlagInProcessCacheSecs(int featureFlagInProcessCacheSecs) {
      this.featureFlagInProcessCacheSecs = featureFlagInProcessCacheSecs;
      return this;
    }

    public ApiClient build() {
      return new ApiClient(this);
    }
  }
}

package it.ratelim.client;

import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import it.ratelim.data.RateLimitProtos;
import net.spy.memcached.MemcachedClientIF;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.google.common.cache.CacheBuilder.newBuilder;

public class ApiClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(ApiClient.class);

  private final CloseableHttpClient httpClient;
  private final String urlBase;
  private final String accountId;

  private Optional<MemcachedClientIF> memcachedClientIF = Optional.empty();
  private Optional<String> featureFlagCacheKey = Optional.empty();


  private LoadingCache<String, Optional<RateLimitProtos.FeatureFlag>> flagCache;


  public ApiClient(Builder builder) {

    this.urlBase = String.format("%s://%s:%d/api/v1/",
        builder.getPort() == 443 ? "https" : "http",
        builder.getHost(),
        builder.getPort());
    this.memcachedClientIF = builder.getMemcachedClientIF();

    CredentialsProvider credsProvider = new BasicCredentialsProvider();
    final String[] apikeyparts = builder.getApikey().split("\\|");

    this.accountId = apikeyparts[0];

    credsProvider.setCredentials(
        new AuthScope(builder.getHost(), builder.getPort()),
        new UsernamePasswordCredentials(accountId, apikeyparts[1]));

    Header header = new BasicHeader(HttpHeaders.CONTENT_TYPE, Consts.PROTO_BUF_CONTENT_TYPE);
    List<Header> headers = new ArrayList<>();
    headers.add(header);

    httpClient = HttpClients.custom()
        .setDefaultHeaders(headers)
        .setDefaultCredentialsProvider(credsProvider)
        .build();

    flagCache = newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build(
            new CacheLoader<String, Optional<RateLimitProtos.FeatureFlag>>() {
              public Optional<RateLimitProtos.FeatureFlag> load(String feature) throws IOException {
                return getFlagInternal(feature);
              }
            });
  }


  public boolean isPass(String key) {
    final RateLimitProtos.LimitResponse limitResponse = limitCheck(RateLimitProtos.LimitRequest.newBuilder().addGroups(key).build());
    return limitResponse.getPassed();
  }

  public RateLimitProtos.LimitResponse limitCheck(RateLimitProtos.LimitRequest limitRequest) {
    return limitCheck(limitRequest, RateLimitProtos.OnFailure.LOG_AND_PASS);
  }

  public RateLimitProtos.LimitResponse limitCheck(RateLimitProtos.LimitRequest limitRequest, RateLimitProtos.OnFailure onFailure) {

    HttpPost httppost = new HttpPost(getUrl("limitcheck"));
    ByteArrayEntity entity = new ByteArrayEntity(limitRequest.toByteArray());
    httppost.setEntity(entity);

    ResponseHandler<RateLimitProtos.LimitResponse> responseHandler = response -> {
      int status = response.getStatusLine().getStatusCode();
      if (status >= 200 && status < 300) {
        return RateLimitProtos.LimitResponse.parseFrom(EntityUtils.toByteArray(response.getEntity()));
      } else {
        throw new ClientProtocolException("Unexpected response status: " + status);
      }
    };
    try {
      return httpClient.execute(httppost, responseHandler);
    } catch (IOException e) {
      return handleError(e, limitRequest, onFailure);
    }
  }

  private RateLimitProtos.LimitResponse handleError(Exception e, RateLimitProtos.LimitRequest limitRequest, RateLimitProtos.OnFailure onFailure) {
    switch (onFailure) {
      case LOG_AND_FAIL:
        LOGGER.warn("Problem {}", limitRequest.getGroupsList());
        return RateLimitProtos.LimitResponse.newBuilder().setPassed(false).build();
      case LOG_AND_PASS:
        LOGGER.warn("Problem {}", limitRequest.getGroupsList());
        return RateLimitProtos.LimitResponse.newBuilder().setPassed(true).build();
      case THROW:
        LOGGER.warn("Problem {}", limitRequest.getGroupsList());
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
  public void limitCreate(RateLimitProtos.LimitDefinition limitDefinition) throws IOException {
    HttpPost httppost = new HttpPost(getUrl("limits"));
    ByteArrayEntity entity = new ByteArrayEntity(limitDefinition.toByteArray());
    httppost.setEntity(entity);
    httpClient.execute(httppost);
  }

  /**
   * create or replace
   *
   * @param limitDefinition
   * @throws IOException
   */
  public void limitUpsert(RateLimitProtos.LimitDefinition limitDefinition) throws IOException {
    HttpPut httpPut = new HttpPut(getUrl("limits"));
    ByteArrayEntity entity = new ByteArrayEntity(limitDefinition.toByteArray());
    httpPut.setEntity(entity);
    httpClient.execute(httpPut);
  }


  public boolean featureIsOn(String feature) {
    return featureIsOnFor(feature, Optional.empty(), Lists.newArrayList());
  }

  public boolean featureIsOnFor(String feature, String lookupKey) {
    return featureIsOnFor(feature, Optional.of(lookupKey), Lists.newArrayList());
  }

  public boolean featureIsOnFor(String feature, Optional<String> lookupKey, List<String> attributes) {


    try {
      final Optional<RateLimitProtos.FeatureFlag> featureFlag = flagCache.get(feature);

      if (!featureFlag.isPresent()) {
        return false;
      }
      return FeatureFlagProcessor.isOnFor(featureFlag.get(), lookupKey, attributes);

    } catch (ExecutionException e) {
      handleError(e, null, RateLimitProtos.OnFailure.LOG_AND_PASS);
    }

    return false;
  }

  private Optional<RateLimitProtos.FeatureFlag> getFlagInternal(String feature) throws IOException {
    return getAllFlags().stream()
        .filter((ff) -> ff.getFeature().equals(feature))
        .findFirst();
  }


  public Collection<RateLimitProtos.FeatureFlag> getAllFlags() throws IOException {
    if (memcachedClientIF.isPresent()) {
      if (!featureFlagCacheKey.isPresent()) {
        featureFlagCacheKey = Optional.of(String.format("it.ratelim.java.%s.featureflags", accountId));
      }

      final byte[] bytes = (byte[]) memcachedClientIF.get().get(featureFlagCacheKey.get());
      RateLimitProtos.FeatureFlags featureFlags;
      if (bytes == null) {
        featureFlags = getAllFlagsReq();
        memcachedClientIF.get().set(featureFlagCacheKey.get(), 60, featureFlags.toByteArray());
        return featureFlags.getFlagsList();
      } else {
        featureFlags = RateLimitProtos.FeatureFlags.parseFrom(bytes);
        return featureFlags.getFlagsList();
      }
    } else {
      return getAllFlagsReq().getFlagsList();
    }
  }

  private RateLimitProtos.FeatureFlags getAllFlagsReq() throws IOException {
    HttpGet req = new HttpGet(getUrl("featureflags"));
    ResponseHandler<RateLimitProtos.FeatureFlags> responseHandler = response -> {
      int status = response.getStatusLine().getStatusCode();
      if (status >= 200 && status < 300) {
        return RateLimitProtos.FeatureFlags.parseFrom(EntityUtils.toByteArray(response.getEntity()));
      } else {
        throw new ClientProtocolException("Unexpected response status: " + status);
      }
    };
    return httpClient.execute(req, responseHandler);
  }


  public static class Builder {
    private String host = "www.ratelim.it";
    private int port = 443;
    private String apikey;
    private Optional<net.spy.memcached.MemcachedClientIF> memcachedClientIF = Optional.empty();

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

    public Optional<MemcachedClientIF> getMemcachedClientIF() {
      return memcachedClientIF;
    }

    public Builder setMemcachedClientIF(MemcachedClientIF memcachedClientIF) {
      this.memcachedClientIF = Optional.of(memcachedClientIF);
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

    public ApiClient build() {
      return new ApiClient(this);
    }
  }
}

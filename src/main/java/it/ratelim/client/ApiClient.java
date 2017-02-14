package it.ratelim.client;

import it.ratelim.data.RateLimitProtos;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ApiClient {

  private final CloseableHttpClient httpClient;
  private final String urlBase;

  public ApiClient(Builder builder) {

    this.urlBase = String.format("%s://%s:%d/api/v1/",
        builder.getPort() == 443 ? "https" : "http",
        builder.getHost(),
        builder.getPort());

    CredentialsProvider credsProvider = new BasicCredentialsProvider();
    final String[] apikeyparts = builder.getApikey().split("\\|");

    credsProvider.setCredentials(
        new AuthScope(builder.getHost(), builder.getPort()),
        new UsernamePasswordCredentials(apikeyparts[0], apikeyparts[1]));

    Header header = new BasicHeader(HttpHeaders.CONTENT_TYPE, Consts.PROTO_BUF_CONTENT_TYPE);
    List<Header> headers = new ArrayList<>();
    headers.add(header);

    httpClient = HttpClients.custom()
        .setDefaultHeaders(headers)
        .setDefaultCredentialsProvider(credsProvider)
        .build();
  }

  public RateLimitProtos.LimitResponse limitCheck(RateLimitProtos.LimitRequest limitRequest) throws IOException {

    HttpPost httppost = new HttpPost(getUrl("limitcheck"));
    ByteArrayEntity entity = new ByteArrayEntity(limitRequest.toByteArray());
    httppost.setEntity(entity);
    System.out.println("Executing request " + httppost.getRequestLine());

    // Create a custom response handler
    ResponseHandler<RateLimitProtos.LimitResponse> responseHandler = new ResponseHandler<RateLimitProtos.LimitResponse>() {
      @Override
      public RateLimitProtos.LimitResponse handleResponse(
          final HttpResponse response) throws ClientProtocolException, IOException {
        int status = response.getStatusLine().getStatusCode();
        if (status >= 200 && status < 300) {
          return RateLimitProtos.LimitResponse.parseFrom(EntityUtils.toByteArray(response.getEntity()));
        } else {
          throw new ClientProtocolException("Unexpected response status: " + status);
        }
      }

    };
    return httpClient.execute(httppost, responseHandler);
  }

  private String getUrl(String endpoint) {
    return urlBase + endpoint;
  }

  public Collection<RateLimitProtos.LimitDefinition> getLimits() throws IOException {
    HttpGet req = new HttpGet(getUrl("limits"));

    // Create a custom response handler
    ResponseHandler<RateLimitProtos.LimitDefinitions> responseHandler = new ResponseHandler<RateLimitProtos.LimitDefinitions>() {
      @Override
      public RateLimitProtos.LimitDefinitions handleResponse(
          final HttpResponse response) throws ClientProtocolException, IOException {
        int status = response.getStatusLine().getStatusCode();
        if (status >= 200 && status < 300) {
          return RateLimitProtos.LimitDefinitions.parseFrom(EntityUtils.toByteArray(response.getEntity()));
        } else {
          throw new ClientProtocolException("Unexpected response status: " + status);
        }
      }

    };
    return httpClient.execute(req, responseHandler).getDefinitionsList();
  }


  public static class Builder {
    private String host = "www.ratelim.it";
    private int port = 443;
    private String apikey;

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

    public String getHost() {
      return host;
    }

    public Builder setHost(String host) {
      this.host = host;
      return this;
    }

    public String getApikey() {
      return apikey;
    }

    public ApiClient build() {
      return new ApiClient(this);
    }
  }

  public static void main(String[] args) throws IOException {

    ApiClient apiClient = new ApiClient.Builder()
        .setPort(8080)
        .setHost("localhost")
        .build();

    final Collection<RateLimitProtos.LimitDefinition> limits = apiClient.getLimits();
    System.out.println(limits);

    final RateLimitProtos.LimitResponse burst = apiClient.limitCheck(RateLimitProtos.LimitRequest.newBuilder()
        .addGroups("burst").build());

    System.out.println(burst);
  }
}

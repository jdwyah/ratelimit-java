# ratelimit-java

Rate Limit your Java app using http://www.ratelim.it

```java
//export RATELIMIT_API_KEY= "ACCT_ID|APIKEY"
ApiClient apiClient = new ApiClient.Builder()
        .build();

apiClient.limitCreate(RateLimitProtos.LimitDefinition.newBuilder()
        .setGroup("pageload")
        .setLimit(1)
        .setPolicyName(RateLimitProtos.LimitResponse.LimitPolicyNames.HOURLY_ROLLING)
        .build());

if(apiClient.isPass("pageload")){
        System.out.println("do hourly thing");
}
```
See full documentation http://www.ratelim.it/documentation


## Supports

* [RateLimits](http://www.ratelim.it/documentation/basic_rate_limits)
* Millions of individual limits sharing the same policies
* WebUI for tweaking limits & feature flags
* Logging to help you debug
* [Concurrency](http://www.ratelim.it/documentation/concurrency) & Semaphores
* Infinite retention for [deduplication workflows](http://www.ratelim.it/documentation/once_and_only_once)
* [FeatureFlags](http://www.ratelim.it/documentation/feature_flags) as a Service

## Options and Defaults
```java
ApiClient apiClient = new ApiClient.Builder()
        .setMemcachedClientIF(memcachedClient)
        .build();

 apiClient.limitCreate(RateLimitProtos.LimitDefinition.newBuilder()
        .setGroup("event:pageload")
        .setLimit(1)
        .setPolicyName(RateLimitProtos.LimitResponse.LimitPolicyNames.HOURLY_ROLLING)
        .build());
 
 apiClient.limitCreate(RateLimitProtos.LimitDefinition.newBuilder()
        .setGroup("event:activation")
        .setLimit(1)
        .setPolicyName(RateLimitProtos.LimitResponse.LimitPolicyNames.INFINITE)
        .build());
 
 
 public void trackEvent(String event, String userId) {
     if (apiClient.featureIsOnFor("Services::RateLimit", userId)) {
       if (apiClient.isPass(String.format("event:%s:%s", event, userId))) {
         actuallyTrackEvent(event);
       }
     }
   }



track_event("pageload:home_page", "1"); // will track
track_event("pageload:home_page", "1"); // will skip for the next hour
track_event("activation", "1"); // will track
track_event("activation", "1"); // will skip forever


```

## Contributing to ratelimit-java
 
* Check out the latest master to make sure the feature hasn't been implemented or the bug hasn't been fixed yet.
* Check out the issue tracker to make sure someone already hasn't requested it and/or contributed it.
* Fork the project.
* Start a feature/bugfix branch.
* Commit and push until you are happy with your contribution.
* Make sure to add tests for it. This is important so I don't break it in a future version unintentionally.
* Please try not to mess with the Rakefile, version, or history. If you want to have your own version, or is otherwise necessary, that is fine, but please isolate to its own commit so I can cherry-pick around it.

## Copyright

Copyright (c) 2017 Jeff Dwyer. See LICENSE.txt for
further details.


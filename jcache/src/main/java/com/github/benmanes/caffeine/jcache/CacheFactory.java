/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.jcache;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.cache.CacheManager;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.Factory;
import javax.cache.expiry.EternalExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.Weigher;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;
import com.github.benmanes.caffeine.jcache.event.JCacheEvictionListener;
import com.github.benmanes.caffeine.jcache.integration.JCacheLoaderAdapter;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;

/**
 * A factory for creating a cache from the configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CacheFactory {

  private CacheFactory() {}

  /**
   * Returns if the cache definition is found in the external settings file.
   *
   * @param cacheManager the owner
   * @param cacheName the name of the cache
   * @return {@code true} if a definition exists
   */
  public static boolean isDefinedExternally(CacheManager cacheManager, String cacheName) {
    return TypesafeConfigurator.cacheNames(rootConfig(cacheManager)).contains(cacheName);
  }

  /**
   * Returns a newly created cache instance if a definition is found in the external settings file.
   *
   * @param cacheManager the owner
   * @param cacheName the name of the cache
   * @return a new cache instance or null if the named cache is not defined in the settings file
   */
  @SuppressWarnings("resource")
  public static <K, V> @Nullable CacheProxy<K, V> tryToCreateFromExternalSettings(
      CacheManager cacheManager, String cacheName) {
    return TypesafeConfigurator.<K, V>from(rootConfig(cacheManager), cacheName)
        .map(configuration -> createCache(cacheManager, cacheName, configuration))
        .orElse(null);
  }

  /**
   * Returns a fully constructed cache based on the cache
   *
   * @param cacheManager the owner of the cache instance
   * @param cacheName the name of the cache
   * @param configuration the full cache definition
   * @return a newly constructed cache instance
   */
  public static <K, V> CacheProxy<K, V> createCache(CacheManager cacheManager,
      String cacheName, Configuration<K, V> configuration) {
    CaffeineConfiguration<K, V> config = resolveConfigurationFor(cacheManager, configuration);
    return new Builder<>(cacheManager, cacheName, config).build();
  }

  /** Returns the resolved configuration. */
  private static Config rootConfig(CacheManager cacheManager) {
    return requireNonNull(TypesafeConfigurator.configSource().get(
        cacheManager.getURI(), cacheManager.getClassLoader()));
  }

  /** Copies the configuration and overlays it on top of the default settings. */
  private static <K, V> CaffeineConfiguration<K, V> resolveConfigurationFor(
      CacheManager cacheManager, Configuration<K, V> configuration) {
    if (configuration instanceof CaffeineConfiguration<?, ?>) {
      return new CaffeineConfiguration<>((CaffeineConfiguration<K, V>) configuration);
    }

    CaffeineConfiguration<K, V> template = TypesafeConfigurator.defaults(rootConfig(cacheManager));
    if (configuration instanceof CompleteConfiguration<?, ?>) {
      var complete = (CompleteConfiguration<K, V>) configuration;
      template.setReadThrough(complete.isReadThrough());
      template.setWriteThrough(complete.isWriteThrough());
      template.setManagementEnabled(complete.isManagementEnabled());
      template.setStatisticsEnabled(complete.isStatisticsEnabled());
      template.getCacheEntryListenerConfigurations()
          .forEach(template::removeCacheEntryListenerConfiguration);
      complete.getCacheEntryListenerConfigurations()
          .forEach(template::addCacheEntryListenerConfiguration);
      template.setCacheLoaderFactory(complete.getCacheLoaderFactory());
      template.setCacheWriterFactory(complete.getCacheWriterFactory());
      template.setExpiryPolicyFactory(complete.getExpiryPolicyFactory());
    }

    template.setTypes(configuration.getKeyType(), configuration.getValueType());
    template.setStoreByValue(configuration.isStoreByValue());
    return template;
  }

  /** A one-shot builder for creating a cache instance. */
  private static final class Builder<K, V> {
    final Ticker ticker;
    final String cacheName;
    final Executor executor;
    final Scheduler scheduler;
    final CacheManager cacheManager;
    final ExpiryPolicy expiryPolicy;
    final EventDispatcher<K, V> dispatcher;
    final JCacheStatisticsMXBean statistics;
    final Caffeine<Object, Object> caffeine;
    final CaffeineConfiguration<K, V> config;

    Builder(CacheManager cacheManager, String cacheName, CaffeineConfiguration<K, V> config) {
      this.config = config;
      this.cacheName = cacheName;
      this.cacheManager = cacheManager;
      this.caffeine = Caffeine.newBuilder();
      this.statistics = new JCacheStatisticsMXBean();
      this.ticker = config.getTickerFactory().create();
      this.executor = config.getExecutorFactory().create();
      this.scheduler = config.getSchedulerFactory().create();
      this.expiryPolicy = config.getExpiryPolicyFactory().create();
      this.dispatcher = new EventDispatcher<>(executor);

      caffeine.ticker(ticker);
      caffeine.executor(executor);
      caffeine.scheduler(scheduler);
      config.getCacheEntryListenerConfigurations().forEach(dispatcher::register);
    }

    /** Creates a configured cache. */
    public CacheProxy<K, V> build() {
      @Var boolean evicts = false;
      evicts |= configureMaximumSize();
      evicts |= configureMaximumWeight();

      @Var boolean expires = false;
      expires |= configureExpireAfterWrite();
      expires |= configureExpireAfterAccess();
      expires |= configureExpireVariably();
      if (!expires) {
        expires = configureJCacheExpiry();
      }

      if (config.isNativeStatisticsEnabled()) {
        caffeine.recordStats();
      }

      @Var JCacheEvictionListener<K, V> evictionListener = null;
      if (evicts || expires) {
        evictionListener = new JCacheEvictionListener<>(dispatcher, statistics);
        caffeine.evictionListener(evictionListener);
      }

      CacheProxy<K, V> cache;
      if (isReadThrough()) {
        configureRefreshAfterWrite();
        cache = newLoadingCacheProxy();
      } else {
        cache = newCacheProxy();
      }

      if (evictionListener != null) {
        evictionListener.setCache(cache);
      }
      return cache;
    }

    /** Determines if the cache should operate in read through mode. */
    private boolean isReadThrough() {
      return config.isReadThrough() && (config.getCacheLoaderFactory() != null);
    }

    /** Creates a cache that does not read through on a cache miss. */
    private CacheProxy<K, V> newCacheProxy() {
      var cacheLoaderFactory = config.getCacheLoaderFactory();
      var cacheLoader = (cacheLoaderFactory == null) ? null : cacheLoaderFactory.create();
      return new CacheProxy<>(cacheName, executor, cacheManager, config, caffeine.build(),
          dispatcher, Optional.ofNullable(cacheLoader), expiryPolicy, ticker, statistics);
    }

    /** Creates a cache that reads through on a cache miss. */
    private CacheProxy<K, V> newLoadingCacheProxy() {
      var cacheLoader = requireNonNull(config.getCacheLoaderFactory()).create();
      var adapter = new JCacheLoaderAdapter<>(
          cacheLoader, dispatcher, expiryPolicy, ticker, statistics);
      var cache = caffeine.build(adapter);
      var jcache = new LoadingCacheProxy<>(cacheName, executor, cacheManager, config,
          cache, dispatcher, cacheLoader, expiryPolicy, ticker, statistics);
      adapter.setCache(jcache);
      return jcache;
    }

    /** Configures the maximum size and returns if set. */
    private boolean configureMaximumSize() {
      if (config.getMaximumSize().isPresent()) {
        caffeine.maximumSize(config.getMaximumSize().getAsLong());
      }
      return config.getMaximumSize().isPresent();
    }

    /** Configures the maximum weight and returns if set. */
    private boolean configureMaximumWeight() {
      if (config.getMaximumWeight().isPresent()) {
        caffeine.maximumWeight(config.getMaximumWeight().getAsLong());
        Weigher<K, V> weigher = config.getWeigherFactory().map(Factory::create)
            .orElseThrow(() -> new IllegalStateException("Weigher not configured"));
        caffeine.weigher((K key, Expirable<V> expirable) -> {
          return weigher.weigh(key, expirable.get());
        });
      }
      return config.getMaximumWeight().isPresent();
    }

    /** Configures write expiration and returns if set. */
    private boolean configureExpireAfterWrite() {
      if (config.getExpireAfterWrite().isEmpty()) {
        return false;
      }
      caffeine.expireAfterWrite(Duration.ofNanos(config.getExpireAfterWrite().getAsLong()));
      return true;
    }

    /** Configures access expiration and returns if set. */
    private boolean configureExpireAfterAccess() {
      if (config.getExpireAfterAccess().isEmpty()) {
        return false;
      }
      caffeine.expireAfterAccess(Duration.ofNanos(config.getExpireAfterAccess().getAsLong()));
      return true;
    }

    /** Configures the custom expiration and returns if set. */
    private boolean configureExpireVariably() {
      if (config.getExpiryFactory().isEmpty()) {
        return false;
      }
      caffeine.expireAfter(new ExpiryAdapter<>(config.getExpiryFactory().orElseThrow().create()));
      return true;
    }

    private boolean configureJCacheExpiry() {
      if (expiryPolicy instanceof EternalExpiryPolicy) {
        return false;
      }
      caffeine.expireAfter(new ExpirableToExpiry<>(ticker));
      return true;
    }

    private void configureRefreshAfterWrite() {
      if (config.getRefreshAfterWrite().isPresent()) {
        caffeine.refreshAfterWrite(Duration.ofNanos(config.getRefreshAfterWrite().getAsLong()));
      }
    }
  }

  private static final class ExpiryAdapter<K, V> implements Expiry<K, Expirable<V>> {
    private final Expiry<K, V> expiry;

    public ExpiryAdapter(Expiry<K, V> expiry) {
      this.expiry = requireNonNull(expiry);
    }
    @Override public long expireAfterCreate(K key, Expirable<V> expirable, long currentTime) {
      return expiry.expireAfterCreate(key, expirable.get(), currentTime);
    }
    @Override public long expireAfterUpdate(K key, Expirable<V> expirable,
        long currentTime, long currentDuration) {
      return expiry.expireAfterUpdate(key, expirable.get(), currentTime, currentDuration);
    }
    @Override public long expireAfterRead(K key, Expirable<V> expirable,
        long currentTime, long currentDuration) {
      return expiry.expireAfterRead(key, expirable.get(), currentTime, currentDuration);
    }
  }

  private static final class ExpirableToExpiry<K, V> implements Expiry<K, Expirable<V>> {
    private final Ticker ticker;

    public ExpirableToExpiry(Ticker ticker) {
      this.ticker = requireNonNull(ticker);
    }
    @Override public long expireAfterCreate(K key, Expirable<V> expirable, long currentTime) {
      return toNanos(expirable);
    }
    @Override public long expireAfterUpdate(K key, Expirable<V> expirable,
        long currentTime, long currentDuration) {
      return toNanos(expirable);
    }
    @Override public long expireAfterRead(K key, Expirable<V> expirable,
        long currentTime, long currentDuration) {
      return toNanos(expirable);
    }
    private long toNanos(Expirable<V> expirable) {
      if (expirable.getExpireTimeMillis() == 0L) {
        return -1L;
      } else if (expirable.isEternal()) {
        return Long.MAX_VALUE;
      }
      return TimeUnit.MILLISECONDS.toNanos(expirable.getExpireTimeMillis()) - ticker.read();
    }
  }
}

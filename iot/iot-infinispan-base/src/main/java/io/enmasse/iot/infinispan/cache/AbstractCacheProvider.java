/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.iot.infinispan.cache;

import java.util.Optional;
import java.util.function.Supplier;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.configuration.ServerConfigurationBuilder;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.query.remote.client.ProtobufMetadataManagerConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.enmasse.iot.infinispan.config.InfinispanProperties;

public abstract class AbstractCacheProvider implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AbstractCacheProvider.class);

    protected final InfinispanProperties properties;

    protected RemoteCacheManager remoteCacheManager;

    public AbstractCacheProvider(final InfinispanProperties properties) {
        this.properties = properties;
        log.info("Using Infinispan - host: {}, port: {}, user: {}, tls: {}", properties.getHost(), properties.getPort(), properties.getUsername(), properties.isUseTls());
        log.info("             TLS - use: {}, trustStore: {}", properties.isUseTls(), properties.getTrustStorePath());
        log.info("            SASL - server: {}, realm: {}", properties.getSaslServerName(), properties.getSaslRealm());
        log.info("          Caches - adapter: {}, devices: {}, states: {}",
                properties.getCacheNames().getAdapterCredentials(),
                properties.getCacheNames().getDevices(),
                properties.getCacheNames().getDeviceConnections());
    }

    @PostConstruct
    public void start() throws Exception {

        var config = new ConfigurationBuilder()
                .addServer()
                .host(this.properties.getHost())
                .port(this.properties.getPort());

        if (this.properties.isUseTls()) {
            config.security()
                    .ssl()
                    .enable()
                    .sniHostName(sniHostName(this.properties.getHost()))

                    .trustStorePath(this.properties.getTrustStorePath());
        }

        if (this.properties.getUsername() != null) {
            config
                    .security()
                    .authentication()
                    .realm(this.properties.getSaslRealm())
                    .serverName(this.properties.getSaslServerName())
                    .username(this.properties.getUsername())
                    .password(this.properties.getPassword());
        }

        customizeServerConfiguration(config);

        this.remoteCacheManager = new RemoteCacheManager(config.build());

    }

    protected void customizeServerConfiguration(final ServerConfigurationBuilder configuration) {}

    @PreDestroy
    public void stop() throws Exception {
        if (this.remoteCacheManager != null) {
            this.remoteCacheManager.close();
            this.remoteCacheManager = null;
        }
    }

    @Override
    public void close() throws Exception {
        stop();
    }

    protected void uploadSchema(final String fileName, final String schema) {

        if (!this.properties.isUploadSchema()) {
            return;
        }

        var cache = this.remoteCacheManager
                .getCache(ProtobufMetadataManagerConstants.PROTOBUF_METADATA_CACHE_NAME);

        if (this.properties.isOverrideSchema()) {
            log.info("Uploading (put) schema - name: {}, schema: {}", fileName, schema);
            cache.put(fileName, schema);
        } else {
            log.info("Uploading (putIfAbsent) schema - name: {}, schema: {}", fileName, schema);
            cache.putIfAbsent(fileName, schema);
        }

    }

    /**
     * Make it a proper SNI hostname.
     * <br>
     * SNI hostnames must, even if fully qualified, not end with a dot.
     *
     * @param host The original hostname
     * @return The SNI compatible hostname
     */
    private static String sniHostName(final String host) {
        return host.replaceAll("\\.$", "");
    }

    protected <K, V> Optional<RemoteCache<K, V>> getCache(final String cacheName) {
        return Optional.ofNullable(this.remoteCacheManager.getCache(cacheName));
    }

    protected <K, V> RemoteCache<K, V> getOrCreateCache(final String cacheName, final Supplier<Configuration> configurationSupplier) {

        if (this.properties.isTryCreate()) {

            final Configuration configuration = configurationSupplier.get();
            log.debug("CacheConfig - {}\n{}", cacheName, configuration.toXMLString(cacheName));
            return this.remoteCacheManager
                    .administration()
                    .getOrCreateCache(cacheName, configuration);

        } else {

            return this.<K, V>getCache(cacheName)
                    .orElseThrow(() -> new IllegalStateException(String.format("Cache '%s' not found, and not requested to create", cacheName)));

        }

    }

    protected <K, V> RemoteCache<K, V> getOrCreateTestCache(final String cacheName, final Configuration configuration) {

        log.debug("CacheConfig - {}\n{}", cacheName, configuration.toXMLString(cacheName));

        return this.remoteCacheManager
                .administration()
                .getOrCreateCache(cacheName, configuration);

    }
}

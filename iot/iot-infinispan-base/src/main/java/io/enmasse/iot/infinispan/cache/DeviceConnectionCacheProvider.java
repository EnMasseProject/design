/*
 * Copyright 2019, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.iot.infinispan.cache;

import java.util.Optional;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.configuration.ServerConfigurationBuilder;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.Index;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.annotations.ProtoSchemaBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import io.enmasse.iot.infinispan.config.InfinispanProperties;
import io.enmasse.iot.infinispan.devcon.DeviceConnectionKey;

@Component
@Lazy
public class DeviceConnectionCacheProvider extends AbstractCacheProvider {

    private static final String GENERATED_PROTOBUF_FILE_NAME = "deviceConnection.proto";

    @Autowired
    public DeviceConnectionCacheProvider(final InfinispanProperties properties) throws Exception {
        super(properties);
    }

    @Override
    protected void customizeServerConfiguration(ServerConfigurationBuilder configuration) {
        configuration.marshaller(ProtoStreamMarshaller.class);
    }

    @Override
    public void start() throws Exception {
        super.start();
        uploadSchema();
    }

    private void uploadSchema() throws Exception {
        final String generatedSchema = generateSchema();
        uploadSchema(GENERATED_PROTOBUF_FILE_NAME, generatedSchema);
    }

    private String generateSchema() throws Exception {
        final SerializationContext serCtx = ProtoStreamMarshaller.getSerializationContext(this.remoteCacheManager);

        return new ProtoSchemaBuilder()

                .addClass(DeviceConnectionKey.class)
                .packageName(DeviceConnectionKey.class.getPackageName())
                .fileName(GENERATED_PROTOBUF_FILE_NAME)
                .build(serCtx);

    }

    public org.infinispan.configuration.cache.Configuration buildConfiguration() {
        return new org.infinispan.configuration.cache.ConfigurationBuilder()

                .indexing()
                .index(Index.NONE)

                .clustering()
                .cacheMode(CacheMode.DIST_SYNC)
                .hash()
                .numOwners(1)

                .build();
    }

    public RemoteCache<DeviceConnectionKey, String> getOrCreateDeviceStateCache() {
        return getOrCreateCache(properties.getCacheNames().getDeviceConnections(), this::buildConfiguration);
    }

    public Optional<RemoteCache<DeviceConnectionKey, String>> getDeviceStateCache() {
        return getCache(properties.getCacheNames().getDeviceConnections());
    }

    public RemoteCache<DeviceConnectionKey, String> getDeviceStateTestCache() {
        return getOrCreateTestCache(properties.getCacheNames().getDeviceConnections(), buildConfiguration());
    }
}

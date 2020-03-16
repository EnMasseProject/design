/*
 * Copyright 2019-2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.iot.registry.device;

import static io.enmasse.iot.registry.device.DeviceKey.deviceKey;
import static io.enmasse.iot.utils.MoreFutures.finishHandler;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.eclipse.hono.service.management.Id;
import org.eclipse.hono.service.management.OperationResult;
import org.eclipse.hono.service.management.Result;
import org.eclipse.hono.service.management.device.Device;
import org.eclipse.hono.service.management.device.DeviceManagementService;
import org.springframework.beans.factory.annotation.Autowired;

import io.enmasse.iot.registry.tenant.TenantInformationService;
import io.opentracing.Span;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;

public abstract class AbstractDeviceManagementService implements DeviceManagementService {

    private final Supplier<String> deviceIdGenerator = () -> UUID.randomUUID().toString();

    @Autowired
    protected TenantInformationService tenantInformationService;

    public void setTenantInformationService(final TenantInformationService tenantInformationService) {
        this.tenantInformationService = tenantInformationService;
    }

    protected abstract Future<OperationResult<Id>> processCreateDevice(DeviceKey key, Device device, Span span);

    protected abstract Future<OperationResult<Device>> processReadDevice(DeviceKey key, Span span);

    protected abstract Future<OperationResult<Id>> processUpdateDevice(DeviceKey key, Device device, Optional<String> resourceVersion, Span span);

    protected abstract Future<Result<Void>> processDeleteDevice(DeviceKey key, Optional<String> resourceVersion, Span span);

    @Override
    public void createDevice(String tenantId, Optional<String> deviceId, Device device, Span span, Handler<AsyncResult<OperationResult<Id>>> resultHandler) {
        finishHandler(() -> processCreateDevice(tenantId, deviceId, device, span), resultHandler);
    }

    protected Future<OperationResult<Id>> processCreateDevice(final String tenantId, final Optional<String> optionalDeviceId, final Device device, final Span span) {

        final String deviceId = optionalDeviceId.orElseGet(this.deviceIdGenerator);

        return this.tenantInformationService
                .tenantExists(tenantId, HTTP_NOT_FOUND, span.context())
                .flatMap(tenantHandle -> processCreateDevice(deviceKey(tenantHandle, deviceId), device, span));

    }

    @Override
    public void readDevice(final String tenantId, final String deviceId, final Span span, final Handler<AsyncResult<OperationResult<Device>>> resultHandler) {
        finishHandler(() -> processReadDevice(tenantId, deviceId, span), resultHandler);
    }

    protected Future<OperationResult<Device>> processReadDevice(String tenantId, String deviceId, Span span) {

        return this.tenantInformationService
                .tenantExists(tenantId, HTTP_NOT_FOUND, span.context())
                .flatMap(tenantHandle -> processReadDevice(deviceKey(tenantHandle, deviceId), span));

    }

    @Override
    public void updateDevice(String tenantId, String deviceId, Device device, Optional<String> resourceVersion, Span span,
            Handler<AsyncResult<OperationResult<Id>>> resultHandler) {
        finishHandler(() -> processUpdateDevice(tenantId, deviceId, device, resourceVersion, span), resultHandler);
    }

    protected Future<OperationResult<Id>> processUpdateDevice(String tenantId, String deviceId, Device device, Optional<String> resourceVersion, Span span) {

        return this.tenantInformationService
                .tenantExists(tenantId, HTTP_NOT_FOUND, span.context())
                .flatMap(tenantHandle -> processUpdateDevice(deviceKey(tenantHandle, deviceId), device, resourceVersion, span));

    }

    @Override
    public void deleteDevice(String tenantId, String deviceId, Optional<String> resourceVersion, Span span, Handler<AsyncResult<Result<Void>>> resultHandler) {
        finishHandler(() -> processDeleteDevice(tenantId, deviceId, resourceVersion, span), resultHandler);
    }

    protected Future<Result<Void>> processDeleteDevice(String tenantId, String deviceId, Optional<String> resourceVersion, Span span) {

        return this.tenantInformationService
                .tenantExists(tenantId, HTTP_NOT_FOUND, span.context())
                .flatMap(tenantHandle -> processDeleteDevice(deviceKey(tenantHandle, deviceId), resourceVersion, span));

    }

}

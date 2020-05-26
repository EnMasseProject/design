/*
 * Copyright 2018-2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.apiclients;

import static java.net.HttpURLConnection.HTTP_OK;

import java.net.HttpURLConnection;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;

import io.enmasse.systemtest.Endpoint;
import io.enmasse.systemtest.VertxFactory;
import io.enmasse.systemtest.logs.CustomLogger;
import io.enmasse.systemtest.platform.Kubernetes;
import io.fabric8.zjsonpatch.internal.guava.Strings;
import io.vertx.core.AsyncResult;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

public abstract class ApiClient implements AutoCloseable {
    private static final Logger log = CustomLogger.getLogger();

    private WebClient client;

    protected Vertx vertx;
    protected Endpoint endpoint;
    protected Supplier<Endpoint> endpointSupplier;
    protected String authzString;
    protected String apiVersion;
    protected String token;

    protected abstract String apiClientName();

    protected ApiClient(final Supplier<Endpoint> endpointSupplier, String apiVersion) {
        // connect() may be overridden, and must not be called in the constructor
        this.vertx = VertxFactory.create();
        this.authzString = String.format("Bearer %s", Strings.isNullOrEmpty(token) ? Kubernetes.getInstance().getApiToken() : token);
        this.endpoint = endpointSupplier.get();
        this.endpointSupplier = endpointSupplier;
        this.apiVersion = apiVersion;
        this.token = Strings.isNullOrEmpty(token) ? Kubernetes.getInstance().getApiToken() : token;
    }

    protected void reconnect() {
        closeClient();
        getClient();
    }

    /**
     * Get the client, create a new one if necessary.
     *
     * @return The client to use.
     */
    protected WebClient getClient() {
        if (this.client == null) {
            this.client = createClient();
        }
        return this.client;
    }

    @Override
    public void close() {
        closeClient();
        this.vertx.close();
    }

    private void closeClient() {
        if (client != null) {
            this.client.close();
            this.client = null;
        }
    }

    protected WebClient createClient() {
        return WebClient.create(vertx, new WebClientOptions()
                .setSsl(true)
                // TODO: Fetch CA and use
                .setTrustAll(true)
                .setVerifyHost(false));
    }

    protected <T> void responseHandler(AsyncResult<HttpResponse<T>> ar, CompletableFuture<T> promise, int expectedCode,
            String warnMessage) {
        responseHandler(ar, promise, expectedCode, warnMessage, true);
    }

    protected <T> void responseHandler(AsyncResult<HttpResponse<T>> ar, CompletableFuture<T> promise, int expectedCode,
            String warnMessage, boolean throwException) {
        responseHandler(ar, promise, (responseCode) -> responseCode == expectedCode, String.valueOf(expectedCode), warnMessage, throwException);
    }

    protected <T> void responseHandler(AsyncResult<HttpResponse<T>> ar, CompletableFuture<T> promise, Predicate<Integer> expectedCodePredicate,
            String warnMessage, boolean throwException) {

        responseHandler(ar, promise, expectedCodePredicate, expectedCodePredicate.toString(), warnMessage, throwException);

    }

    protected <T> void responseHandler(AsyncResult<HttpResponse<T>> ar, CompletableFuture<T> promise, Predicate<Integer> expectedCodePredicate,
            String expectedCodeOrCodes, String warnMessage, boolean throwException) {
        try {
            if (ar.succeeded()) {
                HttpResponse<T> response = ar.result();
                T body = response.body();
                if (expectedCodePredicate.negate().test(response.statusCode())) {
                    log.error("expected-code: {}, response-code: {}, body: {}, op: {}", expectedCodeOrCodes, response.statusCode(), response.body(), warnMessage);
                    promise.completeExceptionally(new RuntimeException("Status " + response.statusCode() + " body: " + (body != null ? body.toString() : null)));
                } else if (response.statusCode() < HTTP_OK || response.statusCode() >= HttpURLConnection.HTTP_MULT_CHOICE) {
                    if (throwException) {
                        promise.completeExceptionally(new RuntimeException(body == null ? "null" : body.toString()));
                    } else {
                        promise.complete(ar.result().body());
                    }
                } else {
                    promise.complete(ar.result().body());
                }
            } else {
                log.warn("Request failed: {}", warnMessage, ar.cause());
                promise.completeExceptionally(ar.cause());
            }
        } catch (io.vertx.core.json.DecodeException decEx) {
            if (ar.result().bodyAsString().toLowerCase().contains("application is not available")) {
                log.warn("'{}' is not available.", apiClientName(), ar.cause());
                throw new IllegalStateException(String.format("'%s' is not available.", apiClientName()));
            } else {
                log.warn("Unexpected object received", ar.cause());
                throw new IllegalStateException("JsonObject expected, but following object was received: " + ar.result().bodyAsString());
            }
        }
    }

    protected <T> T doRequestNTimes(int retry, Callable<T> fn, Optional<Supplier<Endpoint>> endpointFn, Optional<Runnable> reconnect) throws Exception {
        return doRequestNTimes(retry, () -> {
            endpointFn.ifPresent(supplier -> endpoint = supplier.get());
            return fn.call();
        }, reconnect);
    }

    /**
     * Repeat request n-times in a row
     *
     * @param retry count of remaining retries
     * @param fn request function
     * @return
     */
    private <T> T doRequestNTimes(int retry, Callable<T> fn, Optional<Runnable> reconnect) throws Exception {
        try {
            return fn.call();
        } catch (Exception ex) {
            if (ex.getCause() instanceof VertxException && ex.getCause().getMessage().contains("Connection was closed")) {
                if (reconnect.isPresent()) {
                    log.warn("connection was closed, trying to reconnect...");
                    reconnect.get().run();
                }
            }
            if (ex.getCause() instanceof UnknownHostException && retry > 0) {
                try {
                    log.info("{} remaining iterations", retry);
                    return doRequestNTimes(retry - 1, fn, reconnect);
                } catch (Exception ex2) {
                    throw ex2;
                }
            } else {
                if (ex.getCause() != null) {
                    ex.getCause().printStackTrace();
                } else {
                    ex.printStackTrace();
                }
                throw ex;
            }
        }
    }
}

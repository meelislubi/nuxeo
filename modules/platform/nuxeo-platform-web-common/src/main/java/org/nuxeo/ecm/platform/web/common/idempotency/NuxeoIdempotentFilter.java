/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
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
 *
 * Contributors:
 *     Anahide Tchertchian
 */
package org.nuxeo.ecm.platform.web.common.idempotency;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpTrace;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.kv.KeyValueStore;
import org.nuxeo.runtime.services.config.ConfigurationService;

/**
 * Filter handling an idempotency key in POST requests.
 * <p>
 * If {@link #HEADER_KEY} is found in the request header, will intercept request handling to:
 * <ul>
 * <li>mark the request as being processed
 * <li>capture the response when request was processed without any error and store it
 * <li>return the stored response if a subsequent request with the same key is processed again
 * <li>return a conflict response if a request with the same key is processed while the first request is still in
 * progress.
 * </ul>
 *
 * @since 11.5
 */
public class NuxeoIdempotentFilter implements Filter {

    private static final Logger log = LogManager.getLogger(NuxeoIdempotentFilter.class);

    public static final String HEADER_KEY = "Idempotency-Key";

    public static final String STORE_PROPERTY = "org.nuxeo.request.idempotency.keyvaluestore.name";

    public static final String DEFAULT_STORE = "idempotentrequest";

    protected static final long DEFAULT_TTL_SECONDS = Duration.ofDays(1).toSeconds();

    public static final String TTL_SECONDS_PROPERTY = "org.nuxeo.request.idempotency.ttl.seconds";

    public static final String INPROGRESS_MARKER = "IDEMPOTENCY_INPROGRESS_MARKER";

    public static final String INFO_SUFFIX = "_info";

    protected static final int DEFERRED_OUTPUT_STREAM_THRESHOLD = 1024 * 1024; // 1 MB

    // safe methods according to RFC 7231 4.2.1
    protected static final Set<String> SAFE_METHODS = Set.of(HttpGet.METHOD_NAME, HttpHead.METHOD_NAME,
            HttpOptions.METHOD_NAME, HttpTrace.METHOD_NAME);

    // idempotent methods according to RFC 7231 4.2.2
    protected static final Set<String> IDEMPOTENT_METHODS = Set.of(HttpPut.METHOD_NAME, HttpDelete.METHOD_NAME);

    protected long getTTLSeconds() {
        ConfigurationService cs = Framework.getService(ConfigurationService.class);
        if (cs != null) {
            return cs.getLong(TTL_SECONDS_PROPERTY, DEFAULT_TTL_SECONDS);
        }
        return DEFAULT_TTL_SECONDS;
    }

    protected String getStoreName() {
        ConfigurationService cs = Framework.getService(ConfigurationService.class);
        if (cs != null) {
            return cs.getString(STORE_PROPERTY, DEFAULT_STORE);
        }
        return DEFAULT_STORE;
    }

    protected boolean isIdempotentMethod(String method) {
        return SAFE_METHODS.contains(method) || IDEMPOTENT_METHODS.contains(method);
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String method = request.getMethod();
        if (isIdempotentMethod(method)) {
            log.debug("No idempotent processing done: method is already idempotent: " + method);
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(NuxeoIdempotentFilter.HEADER_KEY);
        if (key == null) {
            log.debug("No idempotent processing done: no Idempotent-Key present");
            chain.doFilter(request, response);
            return;
        }
        log.debug("Idempotent request key: " + key);
        KeyValueService kvs = Framework.getService(KeyValueService.class);
        if (kvs == null) {
            log.debug("KeyValueService not present");
            chain.doFilter(request, response);
            return;
        }
        KeyValueStore store = kvs.getKeyValueStore(getStoreName());
        String status = store.getString(key + NuxeoIdempotentFilter.INFO_SUFFIX);
        if (status == null) {
            log.debug("Handle new request for key: " + key);
            long ttl = getTTLSeconds();
            store.put(key + NuxeoIdempotentFilter.INFO_SUFFIX, NuxeoIdempotentFilter.INPROGRESS_MARKER, ttl);
            try {
                CopyingResponseWrapper wrapper = new CopyingResponseWrapper(DEFERRED_OUTPUT_STREAM_THRESHOLD, response);
                chain.doFilter(request, wrapper);
                store.put(key, wrapper.getCaptureAsBytes(), ttl);
                store.put(key + NuxeoIdempotentFilter.INFO_SUFFIX, NuxeoIdempotentResponse.write(wrapper), ttl);
                log.debug("Stored response for key: " + key);
            } catch (IOException | ServletException e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                throw e;
            } finally {
                if (response.getStatus() >= HttpServletResponse.SC_BAD_REQUEST) {
                    // error request: cleanup store
                    store.put(key, (String) null);
                    store.put(key + NuxeoIdempotentFilter.INFO_SUFFIX, (String) null);
                    log.debug("Cleanup store: error for key: " + key);
                }
            }
        } else if (NuxeoIdempotentFilter.INPROGRESS_MARKER.equals(status)) {
            // request already in progress -> conflict
            // Don't call response.sendError, because it commits the response
            // which prevents NuxeoExceptionFilter from returning a custom error page.
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            log.debug("Conflict response for key: " + key);
        } else {
            // request already done: return stored result
            response.getOutputStream().write(store.get(key));
            NuxeoIdempotentResponse.restore(response, store.get(key + NuxeoIdempotentFilter.INFO_SUFFIX));
            log.debug("Returning stored response for key: " + key);
        }
    }

}

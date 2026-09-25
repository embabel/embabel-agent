/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.typesafe.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Streaming limit for both declared and chunked response bodies. */
final class BoundedResponse {
    private static final Logger logger = LoggerFactory.getLogger(BoundedResponse.class);

    private BoundedResponse() {}

    static ClientHttpRequestInterceptor interceptor(int maximumBytes) {
        return (request, body, execution) ->
                new LimitedResponse(execution.execute(request, body), maximumBytes);
    }

    private static final class LimitedResponse implements ClientHttpResponse {
        private final ClientHttpResponse response;
        private final int maximumBytes;
        private InputStream limited;

        private LimitedResponse(ClientHttpResponse response, int maximumBytes) {
            this.response = response;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return response.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return response.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return response.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            if (limited == null) {
                if (response.getHeaders().getContentLength() > maximumBytes) {
                    throw exceeded();
                }
                limited = bounded(response.getBody(), maximumBytes);
            }
            return limited;
        }

        @Override
        public void close() {
            // Close the raw stream first so URLConnection cannot drain a rejected body.
            try {
                closeBody();
            } finally {
                closeResponse();
            }
        }

        /** Contains transport cleanup failures so they cannot replace the request outcome. */
        private void closeResponse() {
            try {
                response.close();
            } catch (RuntimeException ignored) {
                logger.debug("TypeSafe response cleanup failed");
            }
        }

        /**
         * Closes the raw stream before transport cleanup can drain a rejected response. Opening the
         * body here is required when status or content length caused early rejection.
         */
        private void closeBody() {
            try {
                InputStream body = limited;
                if (body == null) {
                    body = response.getBody();
                }
                body.close();
            } catch (IOException | RuntimeException ignored) {
                logger.debug("TypeSafe response body cleanup failed");
            }
        }

        /**
         * Reads at most one byte beyond the limit to detect oversized chunked bodies. Delegating
         * close releases the underlying connection even after decoding has failed.
         */
        private static InputStream bounded(InputStream source, int maximumBytes) {
            return new InputStream() {
                private long remaining = maximumBytes;

                @Override
                public int read() throws IOException {
                    int value = source.read();
                    if (value != -1 && --remaining < 0) {
                        throw exceeded();
                    }
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    Objects.checkFromIndexSize(offset, length, bytes.length);
                    if (length == 0) {
                        return 0;
                    }
                    int count = source.read(bytes, offset, (int) Math.min(length, remaining + 1));
                    if (count > 0) {
                        remaining -= count;
                        if (remaining < 0) {
                            throw exceeded();
                        }
                    }
                    return count;
                }

                @Override
                public void close() throws IOException {
                    source.close();
                }
            };
        }

        private static IOException exceeded() {
            return new IOException("TypeSafe response exceeds configured byte limit");
        }
    }
}

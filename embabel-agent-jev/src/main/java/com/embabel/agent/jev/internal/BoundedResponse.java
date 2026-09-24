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
package com.embabel.agent.jev.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Streaming limit for both declared and chunked response bodies. */
final class BoundedResponse {
    private BoundedResponse() { }

    static ClientHttpRequestInterceptor interceptor(int maximumBytes) {
        return (request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            // Leave the body unopened until decoding: HTTP failures need only their status.
            return new ClientHttpResponse() {
                private InputStream limited;
                public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                public String getStatusText() throws IOException { return response.getStatusText(); }
                public HttpHeaders getHeaders() { return response.getHeaders(); }
                public InputStream getBody() throws IOException {
                    if (limited == null) {
                        if (response.getHeaders().getContentLength() > maximumBytes) throw exceeded();
                        limited = bounded(response.getBody(), maximumBytes);
                    }
                    return limited;
                }
                public void close() {
                    // Close before delegating: URLConnection response cleanup otherwise drains
                    // unread bytes, defeating early rejection of oversized and error responses.
                    try {
                        (limited != null ? limited : response.getBody()).close();
                    } catch (IOException | RuntimeException ignored) {
                        // Cleanup must not replace the primary failure or expose transport data.
                    } finally {
                        try {
                            response.close();
                        } catch (RuntimeException ignored) {
                            // Preserve the result even when an application transport cannot close.
                        }
                    }
                }
            };
        };
    }

    private static InputStream bounded(InputStream source, int maximumBytes) {
        return new InputStream() {
            private long remaining = maximumBytes;
            public int read() throws IOException {
                int value = source.read();
                if (value != -1 && --remaining < 0) throw exceeded();
                return value;
            }
            public int read(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                if (length == 0) return 0;
                int count = source.read(bytes, offset, (int) Math.min(length, remaining + 1));
                if (count > 0 && (remaining -= count) < 0) throw exceeded();
                return count;
            }
            public void close() throws IOException { source.close(); }
        };
    }

    private static IOException exceeded() { return new IOException("Jev response exceeds configured byte limit"); }
}

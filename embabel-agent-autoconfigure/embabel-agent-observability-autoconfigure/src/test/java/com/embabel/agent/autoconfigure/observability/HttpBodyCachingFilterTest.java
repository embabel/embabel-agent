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
package com.embabel.agent.autoconfigure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link HttpBodyCachingFilter}.
 * Verifies that the filter wraps request/response for body caching
 * and runs at the correct priority in the filter chain.
 */
class HttpBodyCachingFilterTest {

    private final HttpBodyCachingFilter filter = new HttpBodyCachingFilter();

    @Nested
    class Ordering {

        // Must run before Spring's ServerHttpObservationFilter so that
        // request/response are already wrapped when the observation is created
        @Test
        void shouldRunBeforeSpringObservationFilter() {
            assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        }
    }

    @Nested
    class WrapsBehavior {

        // Verifies that a plain request and response are wrapped in
        // ContentCaching* wrappers before being passed down the chain
        @Test
        void shouldWrapRequestWithContentCachingWrapper() throws ServletException, IOException {
            var request = new MockHttpServletRequest();
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(
                    any(ContentCachingRequestWrapper.class),
                    any(ContentCachingResponseWrapper.class)
            );
        }

        // The ContentCachingResponseWrapper buffers the body internally.
        // copyBodyToResponse() must be called in the finally block so that
        // the client actually receives the response content.
        @Test
        void shouldCopyResponseBodyAfterFiltering() throws ServletException, IOException {
            var request = new MockHttpServletRequest();
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);
            doAnswer(invocation -> {
                var resp = (ContentCachingResponseWrapper) invocation.getArgument(1);
                resp.getWriter().write("hello");
                resp.getWriter().flush();
                return null;
            }).when(chain).doFilter(any(), any());

            filter.doFilterInternal(request, response, chain);

            assertThat(response.getContentAsString()).isEqualTo("hello");
        }

        // If the request is already a ContentCachingRequestWrapper (e.g. another filter
        // already wrapped it), the filter should pass it through as-is to avoid double wrapping
        @Test
        void shouldNotRewrapAlreadyWrappedRequest() throws ServletException, IOException {
            var request = new ContentCachingRequestWrapper(new MockHttpServletRequest());
            var response = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(eq(request), any(ContentCachingResponseWrapper.class));
        }

        // Same as above but for the response: if already wrapped, reuse the existing wrapper
        @Test
        void shouldNotRewrapAlreadyWrappedResponse() throws ServletException, IOException {
            var request = new MockHttpServletRequest();
            var wrappedResponse = new ContentCachingResponseWrapper(new MockHttpServletResponse());
            var chain = mock(FilterChain.class);

            filter.doFilterInternal(request, wrappedResponse, chain);

            verify(chain).doFilter(any(ContentCachingRequestWrapper.class), eq(wrappedResponse));
        }
    }
}

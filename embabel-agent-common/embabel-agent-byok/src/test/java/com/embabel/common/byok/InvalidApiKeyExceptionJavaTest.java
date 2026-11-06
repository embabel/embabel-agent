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
package com.embabel.common.byok;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Checks that Java code can create an {@link InvalidApiKeyException} and read its status code.
 * In Kotlin the status code is an {@code Int?}. In Java it is an {@link Integer}, which is null
 * when there is no status code.
 */
class InvalidApiKeyExceptionJavaTest {

    @Test
    void javaReadsTheStatusCodeAsAnInteger() {
        var cause = new IllegalStateException("provider refused the key");

        var e = new InvalidApiKeyException("Invalid API key", cause, 401);

        Integer statusCode = e.getStatusCode();
        assertEquals(401, statusCode);
        assertSame(cause, e.getCause());
    }

    @Test
    void javaReadsANullStatusCodeAsNull() {
        var e = new InvalidApiKeyException("Invalid API key", null, null);

        assertNull(e.getStatusCode());
    }

    @Test
    void javaCanUseTheConstructorsThatTakeNoStatusCode() {
        var cause = new IllegalStateException("connection refused");

        assertNull(new InvalidApiKeyException("Invalid API key").getStatusCode());
        assertNull(new InvalidApiKeyException("Invalid API key", cause).getStatusCode());
    }
}

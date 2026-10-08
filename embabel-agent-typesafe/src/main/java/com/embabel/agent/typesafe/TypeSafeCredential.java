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
package com.embabel.agent.typesafe;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Says how the TypeSafe adapter authenticates: with an API key, or not at all.
 *
 * <p>The TypeSafe cloud needs a key, and {@link #of(Supplier)} is the normal choice. Some servers
 * speak the same wire protocol without asking for one, such as a self-hosted Jev listening on a
 * local port. {@link #none()} is for those: requests go out with no {@code Authorization} header.
 *
 * <p>Anonymous mode has to be asked for by name. A key that turns out to be null, blank or full of
 * control characters is still a configuration error, so a missing secret can never quietly turn
 * into unauthenticated calls against the cloud endpoint.
 */
@ApiStatus.Experimental
public final class TypeSafeCredential {
    private static final TypeSafeCredential ANONYMOUS = new TypeSafeCredential(null);

    private final @Nullable Supplier<String> key;

    private TypeSafeCredential(@Nullable Supplier<String> key) {
        this.key = key;
    }

    /**
     * Sends a bearer key read from the supplier on every request, so rotated keys take effect.
     *
     * @param key source of the API key, asked once per request
     * @return a keyed credential
     * @throws NullPointerException if key is null
     */
    public static TypeSafeCredential of(Supplier<String> key) {
        return new TypeSafeCredential(Objects.requireNonNull(key, "key"));
    }

    /**
     * Sends no credential at all, for compatible servers that don't check authorization.
     *
     * @return the anonymous credential
     */
    public static TypeSafeCredential none() {
        return ANONYMOUS;
    }

    /**
     * Tells whether requests go out without an {@code Authorization} header.
     *
     * @return true only for {@link #none()}
     */
    public boolean isAnonymous() {
        return key == null;
    }

    /**
     * Reads the current key and checks that it can go in a header.
     *
     * @return the usable key
     * @throws IllegalArgumentException if the key is null, blank or contains control characters
     * @throws IllegalStateException if this credential is anonymous
     */
    public String resolve() {
        if (key == null) {
            throw new IllegalStateException("TypeSafe credential is anonymous");
        }
        String value = key.get();
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("TypeSafe credential unavailable");
        }
        return value;
    }

    /**
     * Reads the key without checking it, so BYOK validation can apply its own rule first and
     * report problems the way it always has.
     */
    @Nullable
    String unchecked() {
        if (key == null) {
            throw new IllegalStateException("TypeSafe credential is anonymous");
        }
        return key.get();
    }

    @Override
    public String toString() {
        return isAnonymous() ? "TypeSafeCredential[anonymous]" : "TypeSafeCredential[keyed]";
    }
}

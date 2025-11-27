/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.models;

/**
 * Well-known models from Google Gemini.
 * Updated with latest Gemini 2.5 and 3.0 models as of 2025.
 */
public final class GeminiModels {

    private GeminiModels() {
        // Utility class - prevent instantiation
    }

    // Gemini 3.0 Family (Latest)
    public static final String GEMINI_3_PRO_PREVIEW = "gemini-3-pro-preview";

    // Gemini 2.5 Family (Current Generation)
    public static final String GEMINI_2_5_PRO = "gemini-2.5-pro";
    public static final String GEMINI_2_5_FLASH = "gemini-2.5-flash";
    public static final String GEMINI_2_5_FLASH_LITE = "gemini-2.5-flash-lite";


    // Gemini 2.0 Family (Previous Generation)
    public static final String GEMINI_2_0_FLASH = "gemini-2.0-flash";
    public static final String GEMINI_2_0_FLASH_LITE = "gemini-2.0-flash-lite";


    public static final String PROVIDER = "Google";

    public static final String TEXT_EMBEDDING_004 = "text-embedding-004";
    public static final String DEFAULT_TEXT_EMBEDDING_MODEL = TEXT_EMBEDDING_004;
}
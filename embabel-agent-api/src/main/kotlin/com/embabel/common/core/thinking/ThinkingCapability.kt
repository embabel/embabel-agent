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
package com.embabel.common.core.thinking

/**
 * Marker interface for thinking capabilities.
 *
 * This is a tag interface that indicates a prompt runner implementation
 * supports thinking extraction and processing. Similar to StreamingCapability,
 * it enables polymorphic capability detection without defining specific methods.
 *
 * Implementations that extend this interface can extract thinking blocks
 * (like `<think>...</think>`) from LLM responses and provide thinking-aware
 * operations that return ResponseWithThinking<T> objects.
 *
 * Note: Thinking and streaming capabilities are mutually exclusive.
 * StreamingPromptRunner implementations should not extend this interface.
 *
 * @see com.embabel.common.core.streaming.StreamingCapability
 */
interface ThinkingCapability

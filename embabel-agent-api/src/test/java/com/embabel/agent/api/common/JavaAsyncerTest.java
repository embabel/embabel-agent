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
package com.embabel.agent.api.common;

import com.embabel.agent.spi.support.ExecutorAsyncer;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Check that mapper functionality works as expected in Java.
 */
class JavaAsyncerTest {

   @Test
   void mappingFromJava() {

      // Prepare
      var executor = Executors.newFixedThreadPool(10);
      var asyncer = new ExecutorAsyncer(executor);
      var things = List.of("a", "b", "c");

      // Execute
      var mapped = asyncer.parallelMap(things, 10, String::toUpperCase);

      // Verify
      assertEquals(things.size(), mapped.size());
      for (final String thing : things) {
         assertTrue(mapped.contains(thing.toUpperCase()));
      }
   }
}
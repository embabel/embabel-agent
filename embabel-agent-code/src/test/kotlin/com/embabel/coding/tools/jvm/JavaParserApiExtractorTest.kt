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
package com.embabel.coding.tools.jvm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JavaParserApiExtractorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var extractor: JavaParserApiExtractor
    private lateinit var sourceDir: Path

    @BeforeEach
    fun setUp() {
        extractor = JavaParserApiExtractor()
        sourceDir = tempDir.resolve("src")
        Files.createDirectories(sourceDir)
    }

    @Test
    fun `should extract simple class with methods`() {
        // Create a simple Java class
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        val javaFile = packageDir.resolve("SimpleService.java")
        Files.write(javaFile, """
            package com.example;

            import java.util.List;

            public class SimpleService {

                public String getName() {
                    return "service";
                }

                public void processData(String data, int count) {
                    // implementation
                }

                public List<String> getItems() {
                    return null;
                }

                private void privateMethod() {
                    // should not be included
                }
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory("test-api", sourceDir)

        assertEquals("test-api", api.name)
        assertEquals(1, api.totalClasses)
        assertEquals(3, api.totalMethods) // Only public methods

        val apiClass = api.classes.first()
        assertEquals("SimpleService", apiClass.name)
        assertEquals("com.example", apiClass.packageName)
        assertEquals("class", apiClass.type)
        assertEquals(3, apiClass.methods.size)

        // Check specific methods
        val getName = apiClass.methods.find { it.name == "getName" }
        assertNotNull(getName)
        assertEquals("String", getName?.returnType)
        assertEquals(0, getName?.parameters?.size)

        val processData = apiClass.methods.find { it.name == "processData" }
        assertNotNull(processData)
        assertEquals("void", processData?.returnType)
        assertEquals(2, processData?.parameters?.size)
        assertEquals("data: String", processData?.parameters?.get(0))
        assertEquals("count: int", processData?.parameters?.get(1))
    }

    @Test
    fun `should extract interface with methods`() {
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        val javaFile = packageDir.resolve("UserRepository.java")
        Files.write(javaFile, """
            package com.example;

            import java.util.Optional;

            public interface UserRepository {
                Optional<User> findById(Long id);
                void save(User user);
                void deleteById(Long id);
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory("test-api", sourceDir)

        assertEquals(1, api.totalClasses)
        val apiClass = api.classes.first()
        assertEquals("UserRepository", apiClass.name)
        assertEquals("interface", apiClass.type)
        assertEquals(3, apiClass.methods.size)
    }

    @Test
    fun `should extract enum`() {
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        val javaFile = packageDir.resolve("Status.java")
        Files.write(javaFile, """
            package com.example;

            public enum Status {
                ACTIVE, INACTIVE, PENDING;

                public boolean isActive() {
                    return this == ACTIVE;
                }
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory("test-api", sourceDir)

        assertEquals(1, api.totalClasses)
        val apiClass = api.classes.first()
        assertEquals("Status", apiClass.name)
        assertEquals("enum", apiClass.type)
        assertEquals(1, apiClass.methods.size) // Only public methods
        assertEquals("isActive", apiClass.methods.first().name)
    }

    @Test
    fun `should extract annotation`() {
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        val javaFile = packageDir.resolve("MyAnnotation.java")
        Files.write(javaFile, """
            package com.example;

            public @interface MyAnnotation {
                String value() default "";
                int timeout() default 30;
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory("test-api", sourceDir)

        assertEquals(1, api.totalClasses)
        val apiClass = api.classes.first()
        assertEquals("MyAnnotation", apiClass.name)
        assertEquals("annotation", apiClass.type)
        assertEquals(2, apiClass.methods.size)

        val valueMember = apiClass.methods.find { it.name == "value" }
        assertNotNull(valueMember)
        assertEquals("String", valueMember?.returnType)
    }

    @Test
    fun `should extract class with annotations and inheritance`() {
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        val javaFile = packageDir.resolve("UserService.java")
        Files.write(javaFile, """
            package com.example;

            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            @Service
            public class UserService extends BaseService implements UserOperations {

                @Transactional
                public void createUser(String name) {
                    // implementation
                }

                public String getServiceName() {
                    return "UserService";
                }
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory("test-api", sourceDir)

        assertEquals(1, api.totalClasses)
        val apiClass = api.classes.first()
        assertEquals("UserService", apiClass.name)
        assertEquals("class", apiClass.type)

        // Check annotations
        assertEquals(1, apiClass.annotations.size)
        assertEquals("Service", apiClass.annotations.first())

        // Check inheritance
        assertEquals(2, apiClass.superTypes.size)
        assertTrue(apiClass.superTypes.contains("BaseService"))
        assertTrue(apiClass.superTypes.contains("UserOperations"))

        // Check method annotations
        val createUser = apiClass.methods.find { it.name == "createUser" }
        assertNotNull(createUser)
        assertEquals(1, createUser?.annotations?.size)
        assertEquals("Transactional", createUser?.annotations?.first())
    }

    @Test
    fun `should filter by accepted packages`() {
        // Create classes in different packages
        val packageDir1 = sourceDir.resolve("com/example/service")
        val packageDir2 = sourceDir.resolve("com/other")
        Files.createDirectories(packageDir1)
        Files.createDirectories(packageDir2)

        Files.write(packageDir1.resolve("ServiceA.java"), """
            package com.example.service;
            public class ServiceA {
                public void methodA() {}
            }
        """.trimIndent().toByteArray())

        Files.write(packageDir2.resolve("ServiceB.java"), """
            package com.other;
            public class ServiceB {
                public void methodB() {}
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory(
            "test-api",
            sourceDir,
            acceptedPackages = setOf("com.example")
        )

        assertEquals(1, api.totalClasses)
        assertEquals("ServiceA", api.classes.first().name)
    }

    @Test
    fun `should filter by rejected packages`() {
        val packageDir = sourceDir.resolve("org/springframework/example")
        Files.createDirectories(packageDir)

        Files.write(packageDir.resolve("SpringService.java"), """
            package org.springframework.example;
            public class SpringService {
                public void method() {}
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory("test-api", sourceDir)

        assertEquals(0, api.totalClasses) // Should be filtered out by default rejected packages
    }

    @Test
    fun `should handle non-existent directory gracefully`() {
        val nonExistentDir = tempDir.resolve("does-not-exist")

        val api = extractor.fromSourceDirectory("test-api", nonExistentDir)

        assertEquals("test-api", api.name)
        assertEquals(0, api.totalClasses)
        assertEquals(0, api.totalMethods)
    }

    @Test
    fun `should handle empty directory`() {
        val emptyDir = tempDir.resolve("empty")
        Files.createDirectories(emptyDir)

        val api = extractor.fromSourceDirectory("test-api", emptyDir)

        assertEquals("test-api", api.name)
        assertEquals(0, api.totalClasses)
        assertEquals(0, api.totalMethods)
    }

    @Test
    fun `should skip malformed java files gracefully`() {
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        // Create a malformed Java file
        Files.write(packageDir.resolve("Malformed.java"), """
            package com.example;
            public class Malformed {
                // Missing closing brace and invalid syntax
                public void method(
        """.trimIndent().toByteArray())

        // Create a valid Java file
        Files.write(packageDir.resolve("Valid.java"), """
            package com.example;
            public class Valid {
                public void validMethod() {}
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory("test-api", sourceDir)

        // Should only extract the valid class
        assertEquals(1, api.totalClasses)
        assertEquals("Valid", api.classes.first().name)
    }

    @Test
    fun `should skip non-public classes`() {
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        Files.write(packageDir.resolve("Classes.java"), """
            package com.example;

            public class PublicClass {
                public void publicMethod() {}
            }

            class PackagePrivateClass {
                public void method() {}
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory("test-api", sourceDir)

        assertEquals(1, api.totalClasses)
        assertEquals("PublicClass", api.classes.first().name)
    }

    @Test
    fun `should extract javadoc comments`() {
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        val javaFile = packageDir.resolve("DocumentedService.java")
        Files.write(javaFile, """
            package com.example;

            /**
             * This is a documented service class.
             * It provides various utility methods.
             * @author Test Author
             */
            public class DocumentedService {

                /**
                 * This method processes data.
                 * @param data the data to process
                 * @param count the number of times to process
                 * @return the processing result
                 */
                public String processData(String data, int count) {
                    return "processed";
                }

                /**
                 * Gets the service name.
                 * @return the service name
                 */
                public String getName() {
                    return "service";
                }
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory("test-api", sourceDir)

        assertEquals(1, api.totalClasses)
        val serviceClass = api.classes.first()
        assertEquals("DocumentedService", serviceClass.name)
        assertNotNull(serviceClass.comment)
        assertTrue(serviceClass.comment!!.contains("This is a documented service class"))
        assertTrue(serviceClass.comment!!.contains("It provides various utility methods"))
        assertTrue(serviceClass.comment!!.contains("@author Test Author"))

        // Check method comments
        val processData = serviceClass.methods.find { it.name == "processData" }
        assertNotNull(processData)
        assertNotNull(processData?.comment)
        assertTrue(processData?.comment!!.contains("This method processes data"))
        assertTrue(processData?.comment!!.contains("@param data the data to process"))
        assertTrue(processData?.comment!!.contains("@return the processing result"))

        val getName = serviceClass.methods.find { it.name == "getName" }
        assertNotNull(getName)
        assertNotNull(getName?.comment)
        assertTrue(getName?.comment!!.contains("Gets the service name"))
    }

    @Test
    fun `should extract block comments`() {
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        val javaFile = packageDir.resolve("BlockCommentService.java")
        Files.write(javaFile, """
            package com.example;

            /*
             * This service uses block comments
             * instead of javadoc comments.
             */
            public class BlockCommentService {

                /*
                 * A method with block comment
                 */
                public void doSomething() {
                    // implementation
                }
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory("test-api", sourceDir)

        assertEquals(1, api.totalClasses)
        val serviceClass = api.classes.first()
        assertNotNull(serviceClass.comment)
        assertTrue(serviceClass.comment!!.contains("This service uses block comments"))
        assertTrue(serviceClass.comment!!.contains("instead of javadoc comments"))

        val method = serviceClass.methods.first()
        assertNotNull(method.comment)
        assertTrue(method.comment!!.contains("A method with block comment"))
    }

    @Test
    fun `should extract line comments`() {
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        val javaFile = packageDir.resolve("LineCommentService.java")
        Files.write(javaFile, """
            package com.example;

            // Simple line comment for the class
            public class LineCommentService {

                // Simple method comment
                public void simpleMethod() {
                    // implementation
                }
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory("test-api", sourceDir)

        assertEquals(1, api.totalClasses)
        val serviceClass = api.classes.first()
        assertNotNull(serviceClass.comment)
        assertEquals("Simple line comment for the class", serviceClass.comment)

        val method = serviceClass.methods.first()
        assertNotNull(method.comment)
        assertEquals("Simple method comment", method.comment)
    }

    @Test
    fun `should handle classes and methods without comments`() {
        val packageDir = sourceDir.resolve("com/example")
        Files.createDirectories(packageDir)

        val javaFile = packageDir.resolve("NoCommentService.java")
        Files.write(javaFile, """
            package com.example;

            public class NoCommentService {
                public void methodWithoutComment() {
                    // implementation
                }
            }
        """.trimIndent().toByteArray())

        val api = extractor.fromSourceDirectory("test-api", sourceDir)

        assertEquals(1, api.totalClasses)
        val serviceClass = api.classes.first()
        assertNull(serviceClass.comment)

        val method = serviceClass.methods.first()
        assertNull(method.comment)
    }
}

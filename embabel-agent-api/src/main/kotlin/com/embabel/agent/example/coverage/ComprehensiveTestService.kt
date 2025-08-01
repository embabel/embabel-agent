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
package com.embabel.agent.example.coverage

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Comprehensive test service demonstrating various Kotlin features
 * for Jacoco code coverage testing - API module compatible version.
 */

// Data classes for testing
data class User(
    val id: Long,
    val name: String,
    val email: String,
    val age: Int,
    val isActive: Boolean = true,
    val metadata: Map<String, Any> = emptyMap()
) {
    fun isAdult(): Boolean = age >= 18

    fun getDisplayName(): String = when {
        name.isBlank() -> "Anonymous User"
        name.length > 50 -> "${name.take(47)}..."
        else -> name
    }

    fun isValidEmail(): Boolean = email.contains("@") && email.contains(".")

    companion object {
        fun createGuest(): User = User(
            id = -1L,
            name = "Guest",
            email = "guest@example.com",
            age = 0,
            isActive = false
        )

        fun createSample(id: Long): User = User(
            id = id,
            name = "Sample User $id",
            email = "user$id@example.com",
            age = (18..80).random(),
            isActive = Random.nextBoolean()
        )
    }
}

data class Product(
    val id: String,
    val name: String,
    val price: BigDecimal,
    val category: ProductCategory,
    val tags: Set<String> = emptySet(),
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    val isExpensive: Boolean
        get() = price > BigDecimal("100.00")

    val isPremium: Boolean
        get() = tags.contains("premium") || price > BigDecimal("500.00")

    fun applyDiscount(percentage: Double): Product = when {
        percentage <= 0.0 -> this
        percentage >= 100.0 -> this.copy(price = BigDecimal.ZERO)
        else -> this.copy(price = price.multiply(BigDecimal(1.0 - percentage / 100.0)))
    }

    fun hasTag(tag: String): Boolean = tags.any { it.equals(tag, ignoreCase = true) }

    fun getFormattedPrice(): String = "$${price.setScale(2, BigDecimal.ROUND_HALF_UP)}"
}

enum class ProductCategory(val displayName: String, val taxRate: Double, val isDigital: Boolean = false) {
    ELECTRONICS("Electronics", 0.08, false),
    SOFTWARE("Software", 0.0, true),
    CLOTHING("Clothing", 0.06, false),
    BOOKS("Books", 0.0, false),
    EBOOKS("E-Books", 0.0, true),
    FOOD("Food & Beverages", 0.05, false),
    HOME("Home & Garden", 0.07, false);

    fun calculateTax(amount: BigDecimal): BigDecimal =
        amount.multiply(BigDecimal(taxRate))

    fun getShippingCost(amount: BigDecimal): BigDecimal = when {
        isDigital -> BigDecimal.ZERO
        amount > BigDecimal("50") -> BigDecimal.ZERO
        this == BOOKS -> BigDecimal("3.99")
        else -> BigDecimal("9.99")
    }

    companion object {
        fun findByDisplayName(displayName: String): ProductCategory? =
            values().find { it.displayName.equals(displayName, ignoreCase = true) }

        fun getDigitalCategories(): List<ProductCategory> =
            values().filter { it.isDigital }

        fun getPhysicalCategories(): List<ProductCategory> =
            values().filter { !it.isDigital }
    }
}

sealed class OrderStatus {
    object Pending : OrderStatus()
    object Processing : OrderStatus()
    data class Shipped(val trackingNumber: String, val estimatedDelivery: LocalDateTime) : OrderStatus()
    data class Delivered(val deliveredAt: LocalDateTime, val signature: String?) : OrderStatus()
    data class Cancelled(val reason: String, val refundAmount: BigDecimal?) : OrderStatus()
    data class Returned(val returnedAt: LocalDateTime, val refundAmount: BigDecimal) : OrderStatus()

    fun isCompleted(): Boolean = when (this) {
        is Delivered, is Cancelled, is Returned -> true
        else -> false
    }

    fun isCancellable(): Boolean = when (this) {
        is Pending, is Processing -> true
        else -> false
    }

    fun isReturnable(): Boolean = when (this) {
        is Delivered -> true
        else -> false
    }

    fun getStatusMessage(): String = when (this) {
        is Pending -> "Your order is being prepared"
        is Processing -> "Your order is being processed"
        is Shipped -> "Your order has been shipped with tracking: $trackingNumber"
        is Delivered -> "Order delivered on ${deliveredAt.format(DateTimeFormatter.ISO_LOCAL_DATE)}"
        is Cancelled -> "Order cancelled: $reason"
        is Returned -> "Order returned on ${returnedAt.format(DateTimeFormatter.ISO_LOCAL_DATE)}"
    }

    fun getDaysInStatus(currentTime: LocalDateTime = LocalDateTime.now()): Long = when (this) {
        is Shipped -> java.time.Duration.between(currentTime.minusDays(1), currentTime).toDays()
        is Delivered -> java.time.Duration.between(deliveredAt, currentTime).toDays()
        is Returned -> java.time.Duration.between(returnedAt, currentTime).toDays()
        else -> 0L
    }
}

// Configuration properties for testing (no Spring annotations)
data class TestServiceProperties(
    val maxRetries: Int = 3,
    val timeoutSeconds: Long = 30,
    val enableCaching: Boolean = true,
    val allowedCategories: List<String> = listOf("ELECTRONICS", "BOOKS", "SOFTWARE"),
    val defaultDiscountRate: Double = 0.10,
    val features: Map<String, Boolean> = emptyMap(),
    val maxOrderItems: Int = 10,
    val freeShippingThreshold: BigDecimal = BigDecimal("100.00")
)

// Exception classes for testing
open class ServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ValidationException(val field: String, message: String) : ServiceException("Validation failed for $field: $message")
class ResourceNotFoundException(val resourceId: String) : ServiceException("Resource not found: $resourceId")
class BusinessRuleException(val rule: String, message: String) : ServiceException("Business rule violation [$rule]: $message")

// Service class with comprehensive functionality (no Spring annotations)
class ComprehensiveTestService(
    private val properties: TestServiceProperties = TestServiceProperties()
) {

    private val cache = ConcurrentHashMap<String, Any>()
    private var operationCount = 0L
    private val userStorage = mutableMapOf<Long, User>()
    private val productStorage = mutableMapOf<String, Product>()

    // Basic CRUD operations
    fun createUser(name: String, email: String, age: Int): User {
        validateUserInput(name, email, age)

        val user = User(
            id = generateUserId(),
            name = name.trim(),
            email = email.lowercase().trim(),
            age = age,
            metadata = mapOf(
                "createdAt" to LocalDateTime.now(),
                "source" to "service",
                "version" to "1.0"
            )
        )

        userStorage[user.id] = user
        incrementOperationCount()
        return user
    }

    fun findUserById(id: Long): User? {
        return when {
            id <= 0 -> null
            id == 999L -> throw ResourceNotFoundException("USER_999")
            id == 404L -> null
            userStorage.containsKey(id) -> userStorage[id]
            id % 2 == 0L -> createSampleUser(id)
            else -> null
        }.also { incrementOperationCount() }
    }

    fun updateUser(id: Long, updates: Map<String, Any>): User {
        val existingUser = findUserById(id) ?: throw ResourceNotFoundException("USER_$id")

        val updatedUser = existingUser.copy(
            name = updates["name"] as? String ?: existingUser.name,
            email = updates["email"] as? String ?: existingUser.email,
            age = updates["age"] as? Int ?: existingUser.age,
            isActive = updates["isActive"] as? Boolean ?: existingUser.isActive,
            metadata = existingUser.metadata + ("updatedAt" to LocalDateTime.now())
        )

        validateUserInput(updatedUser.name, updatedUser.email, updatedUser.age)
        userStorage[id] = updatedUser
        incrementOperationCount()
        return updatedUser
    }

    fun deleteUser(id: Long): Boolean {
        return when {
            id <= 0 -> false
            id == 404L -> throw ResourceNotFoundException("USER_404")
            userStorage.containsKey(id) -> {
                userStorage.remove(id)
                incrementOperationCount()
                true
            }
            findUserById(id) != null -> {
                incrementOperationCount()
                true
            }
            else -> false
        }
    }

    fun getAllUsers(): List<User> {
        incrementOperationCount()
        return userStorage.values.toList()
    }

    // Product operations with complex business logic
    fun createProduct(name: String, price: BigDecimal, category: ProductCategory, tags: Set<String> = emptySet()): Product {
        validateProductInput(name, price, category)

        val processedTags = tags.map { it.lowercase().trim() }.toSet()
        val finalPrice = when {
            properties.features["autoDiscount"] == true && price > BigDecimal("500") ->
                price.multiply(BigDecimal(1.0 - properties.defaultDiscountRate))
            category.isDigital && price > BigDecimal("100") ->
                price.multiply(BigDecimal(0.95)) // 5% discount for expensive digital products
            else -> price
        }

        val product = Product(
            id = generateProductId(),
            name = name.trim(),
            price = finalPrice,
            category = category,
            tags = processedTags
        )

        productStorage[product.id] = product

        if (properties.enableCaching) {
            cache["product_${product.id}"] = product
        }

        incrementOperationCount()
        return product
    }

    fun searchProducts(
        query: String? = null,
        category: ProductCategory? = null,
        minPrice: BigDecimal? = null,
        maxPrice: BigDecimal? = null,
        tags: Set<String> = emptySet(),
        includeInactive: Boolean = false
    ): List<Product> {
        val allProducts = if (productStorage.isEmpty()) {
            generateSampleProducts()
        } else {
            productStorage.values.toList()
        }

        return allProducts.filter { product ->
            val matchesQuery = query?.let {
                product.name.contains(it, ignoreCase = true) ||
                product.tags.any { tag -> tag.contains(it, ignoreCase = true) }
            } ?: true

            val matchesCategory = category?.let { product.category == it } ?: true

            val matchesMinPrice = minPrice?.let { product.price >= it } ?: true
            val matchesMaxPrice = maxPrice?.let { product.price <= it } ?: true

            val matchesTags = if (tags.isEmpty()) true else {
                tags.any { searchTag ->
                    product.tags.any { productTag ->
                        productTag.contains(searchTag, ignoreCase = true)
                    }
                }
            }

            matchesQuery && matchesCategory && matchesMinPrice && matchesMaxPrice && matchesTags
        }.also { incrementOperationCount() }
    }

    fun getProductById(id: String): Product? {
        return productStorage[id] ?: cache["product_$id"] as? Product
    }

    fun updateProductPrice(id: String, newPrice: BigDecimal): Product {
        val product = getProductById(id) ?: throw ResourceNotFoundException("PRODUCT_$id")

        if (newPrice < BigDecimal.ZERO) {
            throw ValidationException("price", "Price cannot be negative")
        }

        val updatedProduct = product.copy(price = newPrice)
        productStorage[id] = updatedProduct

        if (properties.enableCaching) {
            cache["product_$id"] = updatedProduct
        }

        incrementOperationCount()
        return updatedProduct
    }

    // Order processing with sealed classes
    fun processOrder(userId: Long, productIds: List<String>): OrderStatus {
        val user = findUserById(userId) ?: return OrderStatus.Cancelled("User not found", null)

        if (!user.isActive) {
            return OrderStatus.Cancelled("User account is inactive", null)
        }

        if (productIds.isEmpty()) {
            return OrderStatus.Cancelled("No products in order", null)
        }

        return when {
            productIds.size > properties.maxOrderItems ->
                OrderStatus.Cancelled("Too many items (max ${properties.maxOrderItems})", null)
            user.age < 18 && hasAgeRestrictedProducts(productIds) ->
                OrderStatus.Cancelled("Age restricted items", null)
            productIds.any { it.startsWith("RESTRICTED") } ->
                OrderStatus.Cancelled("Restricted items", null)
            hasInvalidProducts(productIds) ->
                OrderStatus.Cancelled("Invalid products in order", null)
            else -> {
                val trackingNumber = generateTrackingNumber()
                val estimatedDelivery = calculateDeliveryDate(productIds)
                OrderStatus.Shipped(trackingNumber, estimatedDelivery)
            }
        }.also { incrementOperationCount() }
    }

    fun updateOrderStatus(orderId: String, newStatus: OrderStatus): OrderStatus {
        return when (newStatus) {
            is OrderStatus.Pending -> {
                if (orderId.startsWith("URGENT")) OrderStatus.Processing
                else newStatus
            }
            is OrderStatus.Processing -> {
                val trackingNumber = generateTrackingNumber()
                val estimatedDelivery = LocalDateTime.now().plusDays(
                    if (orderId.startsWith("EXPRESS")) 1 else 2
                )
                OrderStatus.Shipped(trackingNumber, estimatedDelivery)
            }
            is OrderStatus.Shipped -> {
                when {
                    Random.nextDouble() < 0.8 -> { // 80% chance of delivery
                        val deliveryTime = LocalDateTime.now().plusHours(Random.nextLong(1, 48))
                        OrderStatus.Delivered(deliveryTime, "Electronic Signature")
                    }
                    Random.nextDouble() < 0.1 -> { // 10% chance of cancellation
                        OrderStatus.Cancelled("Shipping issue", BigDecimal("10.00"))
                    }
                    else -> newStatus // 10% chance of staying shipped
                }
            }
            is OrderStatus.Delivered -> {
                if (Random.nextDouble() < 0.05) { // 5% chance of return
                    OrderStatus.Returned(LocalDateTime.now(), BigDecimal("25.00"))
                } else newStatus
            }
            else -> newStatus
        }.also { incrementOperationCount() }
    }

    // Async operations for testing
    fun processAsync(operation: String): CompletableFuture<String> {
        return CompletableFuture.supplyAsync {
            Thread.sleep(Random.nextLong(10, 100)) // Simulate work

            when (operation.lowercase()) {
                "success" -> "Operation completed successfully"
                "failure" -> throw ServiceException("Async operation failed")
                "timeout" -> {
                    Thread.sleep(properties.timeoutSeconds * 1000 + 1000)
                    "This should timeout"
                }
                "random" -> if (Random.nextBoolean()) "Success" else throw ServiceException("Random failure")
                else -> "Unknown operation: $operation"
            }
        }.also { incrementOperationCount() }
    }

    fun processAsyncWithRetry(operation: String, maxRetries: Int = properties.maxRetries): CompletableFuture<String> {
        return CompletableFuture.supplyAsync {
            var attempts = 0
            var lastException: Exception? = null

            while (attempts < maxRetries) {
                try {
                    attempts++
                    Thread.sleep(attempts * 10L) // Increasing delay

                    return@supplyAsync when (operation.lowercase()) {
                        "flaky" -> {
                            if (Random.nextDouble() < 0.7) throw ServiceException("Flaky operation failed")
                            "Success after $attempts attempts"
                        }
                        "eventual_success" -> {
                            if (attempts < 3) throw ServiceException("Not ready yet")
                            "Success on attempt $attempts"
                        }
                        else -> processAsync(operation).get()
                    }
                } catch (e: Exception) {
                    lastException = e
                    if (attempts >= maxRetries) break
                }
            }

            throw ServiceException("Failed after $attempts attempts", lastException)
        }.also { incrementOperationCount() }
    }

    // Collection operations with higher-order functions
    fun analyzeUsers(users: List<User>): Map<String, Any> {
        if (users.isEmpty()) return emptyMap()

        val adults = users.filter { it.isAdult() }
        val activeUsers = users.filter { it.isActive }
        val validEmailUsers = users.filter { it.isValidEmail() }
        val averageAge = users.map { it.age }.average()

        val ageGroups = users.groupBy { user ->
            when (user.age) {
                in 0..17 -> "Minor"
                in 18..64 -> "Adult"
                else -> "Senior"
            }
        }

        val emailDomains = users.mapNotNull { user ->
            user.email.substringAfter("@", "").takeIf { it.isNotEmpty() }
        }.groupingBy { it }.eachCount()

        val nameStats = users.map { it.name.length }.let { lengths ->
            mapOf(
                "averageLength" to lengths.average(),
                "maxLength" to (lengths.maxOrNull() ?: 0),
                "minLength" to (lengths.minOrNull() ?: 0)
            )
        }

        return mapOf(
            "totalUsers" to users.size,
            "adultUsers" to adults.size,
            "activeUsers" to activeUsers.size,
            "validEmailUsers" to validEmailUsers.size,
            "averageAge" to averageAge,
            "ageGroups" to ageGroups.mapValues { it.value.size },
            "emailDomains" to emailDomains,
            "nameStats" to nameStats,
            "oldestUser" to (users.maxByOrNull { it.age } ?: User.createGuest()),
            "youngestUser" to (users.minByOrNull { it.age } ?: User.createGuest()),
            "mostCommonAge" to (users.groupingBy { it.age }.eachCount().maxByOrNull { it.value }?.key ?: 0)
        ).also { incrementOperationCount() }
    }

    fun calculateDiscounts(products: List<Product>): Map<Product, BigDecimal> {
        return products.associateWith { product ->
            val baseDiscount = when (product.category) {
                ProductCategory.ELECTRONICS -> 0.05
                ProductCategory.SOFTWARE -> 0.20
                ProductCategory.CLOTHING -> 0.15
                ProductCategory.BOOKS -> 0.10
                ProductCategory.EBOOKS -> 0.25
                ProductCategory.FOOD -> 0.02
                ProductCategory.HOME -> 0.08
            }

            val volumeDiscount = when {
                product.hasTag("bulk") -> 0.10
                product.hasTag("clearance") -> 0.25
                product.hasTag("seasonal") -> 0.15
                product.isExpensive -> 0.05
                product.isPremium -> 0.03
                else -> 0.0
            }

            val seasonalDiscount = when (LocalDateTime.now().monthValue) {
                12, 1, 2 -> 0.10  // Winter sale
                6, 7, 8 -> 0.15   // Summer sale
                11 -> 0.20        // Black Friday
                else -> 0.0
            }

            val loyaltyDiscount = if (product.hasTag("vip")) 0.05 else 0.0

            val totalDiscount = (baseDiscount + volumeDiscount + seasonalDiscount + loyaltyDiscount)
                .coerceAtMost(0.50) // Max 50% discount

            product.price.multiply(BigDecimal(totalDiscount))
        }.also { incrementOperationCount() }
    }

    fun calculateOrderTotal(
        products: List<Product>,
        discounts: Map<Product, BigDecimal>? = null,
        shippingAddress: String? = null
    ): Map<String, BigDecimal> {
        val subtotal = products.map { it.price }.fold(BigDecimal.ZERO) { acc, price -> acc.add(price) }

        val totalDiscounts = discounts?.values?.fold(BigDecimal.ZERO) { acc, discount ->
            acc.add(discount)
        } ?: BigDecimal.ZERO

        val discountedSubtotal = subtotal.subtract(totalDiscounts)

        val taxes = products.map { product ->
            product.category.calculateTax(
                discounts?.get(product)?.let { product.price.subtract(it) } ?: product.price
            )
        }.fold(BigDecimal.ZERO) { acc, tax -> acc.add(tax) }

        val shipping = when {
            discountedSubtotal >= properties.freeShippingThreshold -> BigDecimal.ZERO
            products.all { it.category.isDigital } -> BigDecimal.ZERO
            shippingAddress?.contains("Alaska", ignoreCase = true) == true -> BigDecimal("25.00")
            shippingAddress?.contains("Hawaii", ignoreCase = true) == true -> BigDecimal("25.00")
            products.any { it.category == ProductCategory.HOME } -> BigDecimal("15.00")
            else -> BigDecimal("9.99")
        }

        val total = discountedSubtotal.add(taxes).add(shipping)

        return mapOf(
            "subtotal" to subtotal,
            "discounts" to totalDiscounts,
            "discountedSubtotal" to discountedSubtotal,
            "taxes" to taxes,
            "shipping" to shipping,
            "total" to total
        ).also { incrementOperationCount() }
    }

    // Validation methods with multiple branches
    private fun validateUserInput(name: String, email: String, age: Int) {
        when {
            name.isBlank() -> throw ValidationException("name", "Name cannot be blank")
            name.length > 100 -> throw ValidationException("name", "Name too long (max 100 characters)")
            name.length < 2 -> throw ValidationException("name", "Name too short (min 2 characters)")
            !email.contains("@") -> throw ValidationException("email", "Invalid email format - missing @")
            !email.contains(".") -> throw ValidationException("email", "Invalid email format - missing domain")
            email.length > 255 -> throw ValidationException("email", "Email too long (max 255 characters)")
            email.startsWith("@") -> throw ValidationException("email", "Email cannot start with @")
            email.endsWith("@") -> throw ValidationException("email", "Email cannot end with @")
            age < 0 -> throw ValidationException("age", "Age cannot be negative")
            age > 150 -> throw ValidationException("age", "Age seems unrealistic (max 150)")
        }
    }

    private fun validateProductInput(name: String, price: BigDecimal, category: ProductCategory) {
        when {
            name.isBlank() -> throw ValidationException("name", "Product name cannot be blank")
            name.length < 3 -> throw ValidationException("name", "Product name too short (min 3 characters)")
            name.length > 200 -> throw ValidationException("name", "Product name too long (max 200 characters)")
            price < BigDecimal.ZERO -> throw ValidationException("price", "Price cannot be negative")
            price > BigDecimal("999999.99") -> throw ValidationException("price", "Price too high (max $999,999.99)")
            price == BigDecimal.ZERO && !category.isDigital ->
                throw ValidationException("price", "Physical products cannot be free")
            !properties.allowedCategories.contains(category.name) ->
                throw ValidationException("category", "Category not allowed: ${category.name}")
        }
    }

    // Helper methods with various logic paths
    private fun generateUserId(): Long = System.currentTimeMillis() + Random.nextLong(1000)

    private fun generateProductId(): String = "PROD_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"

    private fun generateTrackingNumber(): String {
        val prefixes = listOf("TRK", "SHP", "DLV", "EXP", "FST")
        val prefix = prefixes[Random.nextInt(prefixes.size)]
        val number = String.format("%08d", Random.nextInt(100000000))
        val checksum = (prefix.hashCode() + number.hashCode()).toString().takeLast(2)
        return "$prefix$number$checksum"
    }

    private fun createSampleUser(id: Long): User {
        val names = listOf("Alice", "Bob", "Charlie", "Diana", "Eve", "Frank", "Grace", "Henry", "Ivy", "Jack")
        val domains = listOf("example.com", "test.org", "sample.net", "demo.co", "mock.io")

        return User(
            id = id,
            name = names[Random.nextInt(names.size)],
            email = "${names[Random.nextInt(names.size)].lowercase()}@${domains[Random.nextInt(domains.size)]}",
            age = Random.nextInt(16, 85),
            isActive = Random.nextBoolean(),
            metadata = mapOf(
                "generated" to true,
                "timestamp" to LocalDateTime.now(),
                "source" to "sample",
                "version" to Random.nextInt(1, 5)
            )
        )
    }

    private fun generateSampleProducts(): List<Product> {
        val productData = listOf(
            Triple("Laptop Pro", ProductCategory.ELECTRONICS, setOf("premium", "professional")),
            Triple("Smartphone X", ProductCategory.ELECTRONICS, setOf("mobile", "popular")),
            Triple("Designer T-Shirt", ProductCategory.CLOTHING, setOf("fashion", "cotton")),
            Triple("Mystery Novel", ProductCategory.BOOKS, setOf("fiction", "bestseller")),
            Triple("Code Editor Pro", ProductCategory.SOFTWARE, setOf("development", "premium")),
            Triple("Digital Art Course", ProductCategory.SOFTWARE, setOf("education", "creative")),
            Triple("Coffee Maker Deluxe", ProductCategory.HOME, setOf("kitchen", "automatic")),
            Triple("Wireless Headphones", ProductCategory.ELECTRONICS, setOf("audio", "bluetooth")),
            Triple("Programming E-Book", ProductCategory.EBOOKS, setOf("technical", "reference")),
            Triple("Organic Coffee Beans", ProductCategory.FOOD, setOf("organic", "premium"))
        )

        return productData.mapIndexed { index, (name, category, tags) ->
            Product(
                id = "SAMPLE_$index",
                name = name,
                price = BigDecimal(Random.nextDouble(9.99, 999.99)).setScale(2, BigDecimal.ROUND_HALF_UP),
                category = category,
                tags = tags + setOf("sample", if (Random.nextBoolean()) "popular" else "new")
            )
        }
    }

    private fun hasAgeRestrictedProducts(productIds: List<String>): Boolean {
        return productIds.any { it.contains("ADULT") || it.contains("18+") }
    }

    private fun hasInvalidProducts(productIds: List<String>): Boolean {
        return productIds.any { id ->
            id.isBlank() || id.startsWith("INVALID") || id.length > 50
        }
    }

    private fun calculateDeliveryDate(productIds: List<String>): LocalDateTime {
        val hasDigitalOnly = productIds.all { it.startsWith("DIGITAL") }
        val hasExpressItems = productIds.any { it.contains("EXPRESS") }
        val hasLargeItems = productIds.any { it.contains("LARGE") }

        val baseDays = when {
            hasDigitalOnly -> 0
            hasExpressItems -> 1
            hasLargeItems -> 7
            else -> 3
        }

        val additionalDays = if (productIds.size > 5) 1 else 0

        return LocalDateTime.now().plusDays((baseDays + additionalDays).toLong())
    }

    @Synchronized
    private fun incrementOperationCount() {
        operationCount++
    }

    // Public methods for testing state
    fun getOperationCount(): Long = operationCount

    fun resetOperationCount() {
        operationCount = 0
    }

    fun clearCache() {
        cache.clear()
        incrementOperationCount()
    }

    fun getCacheSize(): Int = cache.size

    fun getUserCount(): Int = userStorage.size

    fun getProductCount(): Int = productStorage.size

    fun getProperties(): TestServiceProperties = properties

    fun clearAllData() {
        userStorage.clear()
        productStorage.clear()
        clearCache()
        resetOperationCount()
    }

    // Method with try-catch for exception testing
    fun safeOperation(input: String): String {
        return try {
            when {
                input.isBlank() -> throw IllegalArgumentException("Input cannot be blank")
                input == "error" -> throw ServiceException("Simulated error")
                input == "null" -> throw NullPointerException("Simulated null pointer")
                input == "business" -> throw BusinessRuleException("SAFE_OP", "Business rule violated")
                input.length > 1000 -> "Input too long"
                input.startsWith("upper") -> input.uppercase()
                input.startsWith("lower") -> input.lowercase()
                input.startsWith("reverse") -> input.reversed()
                else -> "Processed: ${input.trim()}"
            }
        } catch (e: IllegalArgumentException) {
            "Invalid input: ${e.message}"
        } catch (e: BusinessRuleException) {
            "Business error [${e.rule}]: ${e.message}"
        } catch (e: ServiceException) {
            "Service error: ${e.message}"
        } catch (e: Exception) {
            "Unexpected error: ${e.javaClass.simpleName} - ${e.message}"
        } finally {
            incrementOperationCount()
        }
    }

    // Method with complex conditional logic
    fun complexBusinessLogic(
        userAge: Int,
        productCategory: ProductCategory,
        orderAmount: BigDecimal,
        isPremiumMember: Boolean = false,
        seasonalPromo: Boolean = false,
        loyaltyPoints: Int = 0
    ): Map<String, Any> {
        val eligibleForDiscount = when {
            userAge < 13 -> false  // Children need parent approval
            userAge < 18 -> orderAmount <= BigDecimal("50") // Teens have spending limits
            userAge >= 65 -> true  // Senior discount
            isPremiumMember -> true
            loyaltyPoints >= 1000 -> true
            orderAmount > BigDecimal("200") -> true
            productCategory.isDigital && orderAmount > BigDecimal("30") -> true
            else -> false
        }

        val discountRate = when {
            !eligibleForDiscount -> 0.0
            seasonalPromo && isPremiumMember && loyaltyPoints >= 5000 -> 0.30 // Max combo discount
            seasonalPromo && isPremiumMember -> 0.25
            seasonalPromo && loyaltyPoints >= 2000 -> 0.20
            seasonalPromo -> 0.15
            isPremiumMember && loyaltyPoints >= 5000 -> 0.20
            isPremiumMember -> 0.10
            userAge >= 65 -> 0.12
            loyaltyPoints >= 2000 -> 0.08
            loyaltyPoints >= 1000 -> 0.05
            else -> 0.03
        }

        val taxRate = productCategory.taxRate
        val subtotal = orderAmount
        val discountAmount = subtotal.multiply(BigDecimal(discountRate))
        val taxableAmount = subtotal.subtract(discountAmount)
        val taxAmount = taxableAmount.multiply(BigDecimal(taxRate))
        val total = taxableAmount.add(taxAmount)

        val shippingCost = when {
            productCategory.isDigital -> BigDecimal.ZERO
            total >= properties.freeShippingThreshold -> BigDecimal.ZERO
            isPremiumMember -> BigDecimal("2.99")
            loyaltyPoints >= 1000 -> BigDecimal("4.99")
            else -> productCategory.getShippingCost(orderAmount)
        }

        val finalTotal = total.add(shippingCost)

        val loyaltyPointsEarned = when {
            isPremiumMember -> (finalTotal.multiply(BigDecimal("0.02"))).toInt() // 2% for premium
            loyaltyPoints >= 5000 -> (finalTotal.multiply(BigDecimal("0.015"))).toInt() // 1.5% for VIP
            else -> (finalTotal.multiply(BigDecimal("0.01"))).toInt() // 1% base rate
        }

        val estimatedDeliveryDays = when {
            productCategory.isDigital -> 0
            isPremiumMember && shippingCost > BigDecimal.ZERO -> 1
            loyaltyPoints >= 2000 -> 2
            orderAmount > BigDecimal("100") -> 2
            else -> 3
        }

        return mapOf(
            "subtotal" to subtotal,
            "discountRate" to discountRate,
            "discountAmount" to discountAmount,
            "taxAmount" to taxAmount,
            "shippingCost" to shippingCost,
            "total" to finalTotal,
            "eligibleForDiscount" to eligibleForDiscount,
            "loyaltyPointsEarned" to loyaltyPointsEarned,
            "estimatedDeliveryDays" to estimatedDeliveryDays,
            "freeShippingQualified" to (total >= properties.freeShippingThreshold),
            "premiumPerks" to isPremiumMember,
            "vipStatus" to (loyaltyPoints >= 5000)
        ).also { incrementOperationCount() }
    }
}

// Extension functions for testing
fun User.toDisplayString(): String = "${getDisplayName()} (${if (isActive) "Active" else "Inactive"})"

fun User.getAgeGroup(): String = when (age) {
    in 0..12 -> "Child"
    in 13..17 -> "Teen"
    in 18..64 -> "Adult"
    else -> "Senior"
}

fun Product.getFormattedPrice(): String = "$${price.setScale(2, BigDecimal.ROUND_HALF_UP)}"

fun Product.getCategoryDisplay(): String = "${category.displayName} ${if (category.isDigital) "(Digital)" else "(Physical)"}"

fun List<Product>.filterByPriceRange(min: BigDecimal, max: BigDecimal): List<Product> =
    filter { it.price >= min && it.price <= max }

fun List<Product>.getAveragePrice(): BigDecimal? =
    if (isEmpty()) null
    else map { it.price }.fold(BigDecimal.ZERO) { acc, price -> acc.add(price) }
        .divide(BigDecimal(size), 2, BigDecimal.ROUND_HALF_UP)

fun List<Product>.groupByCategory(): Map<ProductCategory, List<Product>> = groupBy { it.category }

fun OrderStatus.canBeCancelled(): Boolean = when (this) {
    is OrderStatus.Pending, is OrderStatus.Processing -> true
    is OrderStatus.Shipped -> false
    is OrderStatus.Delivered, is OrderStatus.Cancelled, is OrderStatus.Returned -> false
}

fun OrderStatus.getActionableSteps(): List<String> = when (this) {
    is OrderStatus.Pending -> listOf("Wait for processing", "Contact support to modify")
    is OrderStatus.Processing -> listOf("Wait for shipment", "Contact support to cancel")
    is OrderStatus.Shipped -> listOf("Track package", "Prepare for delivery")
    is OrderStatus.Delivered -> listOf("Enjoy your purchase", "Leave a review", "Consider return if needed")
    is OrderStatus.Cancelled -> listOf("Check refund status", "Reorder if desired")
    is OrderStatus.Returned -> listOf("Check refund status", "Consider alternative products")
}

fun OrderStatus.getActionableSteps2(): List<String> = when (this) {
    is OrderStatus.Pending -> listOf("Wait for processing", "Contact support to modify")
    is OrderStatus.Processing -> listOf("Wait for shipment", "Contact support to cancel")
    is OrderStatus.Shipped -> listOf("Track package", "Prepare for delivery")
    is OrderStatus.Delivered -> listOf("Enjoy your purchase", "Leave a review", "Consider return if needed")
    is OrderStatus.Cancelled -> listOf("Check refund status", "Reorder if desired")
    is OrderStatus.Returned -> listOf("Check refund status", "Consider alternative products")
}

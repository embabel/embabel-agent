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

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom
import kotlin.random.Random

/**
 * Comprehensive test service demonstrating various Kotlin features
 * for Jacoco code coverage testing.
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
    
    companion object {
        fun createGuest(): User = User(
            id = -1L,
            name = "Guest",
            email = "guest@example.com",
            age = 0,
            isActive = false
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
    
    fun applyDiscount(percentage: Double): Product = when {
        percentage <= 0.0 -> this
        percentage >= 100.0 -> this.copy(price = BigDecimal.ZERO)
        else -> this.copy(price = price.multiply(BigDecimal(1.0 - percentage / 100.0)))
    }
}

enum class ProductCategory(val displayName: String, val taxRate: Double) {
    ELECTRONICS("Electronics", 0.08),
    CLOTHING("Clothing", 0.06),
    BOOKS("Books", 0.0),
    FOOD("Food & Beverages", 0.05),
    HOME("Home & Garden", 0.07);
    
    fun calculateTax(amount: BigDecimal): BigDecimal = 
        amount.multiply(BigDecimal(taxRate))
    
    companion object {
        fun findByDisplayName(displayName: String): ProductCategory? =
            values().find { it.displayName.equals(displayName, ignoreCase = true) }
    }
}

sealed class OrderStatus {
    object Pending : OrderStatus()
    object Processing : OrderStatus()
    data class Shipped(val trackingNumber: String, val estimatedDelivery: LocalDateTime) : OrderStatus()
    data class Delivered(val deliveredAt: LocalDateTime, val signature: String?) : OrderStatus()
    data class Cancelled(val reason: String, val refundAmount: BigDecimal?) : OrderStatus()
    
    fun isCompleted(): Boolean = when (this) {
        is Delivered -> true
        is Cancelled -> true
        else -> false
    }
    
    fun getStatusMessage(): String = when (this) {
        is Pending -> "Your order is being prepared"
        is Processing -> "Your order is being processed"
        is Shipped -> "Your order has been shipped with tracking: $trackingNumber"
        is Delivered -> "Order delivered on ${deliveredAt.format(DateTimeFormatter.ISO_LOCAL_DATE)}"
        is Cancelled -> "Order cancelled: $reason"
    }
}

// Configuration properties for testing
@ConfigurationProperties(prefix = "test.service")
data class TestServiceProperties(
    val maxRetries: Int = 3,
    val timeoutSeconds: Long = 30,
    val enableCaching: Boolean = true,
    val allowedCategories: List<String> = listOf("ELECTRONICS", "BOOKS"),
    val defaultDiscountRate: Double = 0.10,
    val features: Map<String, Boolean> = emptyMap()
)

// Exception classes for testing
class ServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ValidationException(val field: String, message: String) : ServiceException("Validation failed for $field: $message")
class ResourceNotFoundException(val resourceId: String) : ServiceException("Resource not found: $resourceId")

// Service class with comprehensive functionality
@Service
@Transactional
class ComprehensiveTestService(
    private val properties: TestServiceProperties
) {
    
    private val cache = mutableMapOf<String, Any>()
    private var operationCount = 0L
    
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
                "source" to "service"
            )
        )
        
        incrementOperationCount()
        return user
    }
    
    fun findUserById(id: Long): User? {
        return when {
            id <= 0 -> null
            id == 999L -> throw ResourceNotFoundException("USER_999")
            id % 2 == 0L -> createSampleUser(id)
            else -> null
        }
    }
    
    fun updateUser(id: Long, updates: Map<String, Any>): User {
        val existingUser = findUserById(id) ?: throw ResourceNotFoundException("USER_$id")
        
        val updatedUser = existingUser.copy(
            name = updates["name"] as? String ?: existingUser.name,
            email = updates["email"] as? String ?: existingUser.email,
            age = updates["age"] as? Int ?: existingUser.age,
            isActive = updates["isActive"] as? Boolean ?: existingUser.isActive
        )
        
        validateUserInput(updatedUser.name, updatedUser.email, updatedUser.age)
        incrementOperationCount()
        return updatedUser
    }
    
    fun deleteUser(id: Long): Boolean {
        return when {
            id <= 0 -> false
            id == 404L -> throw ResourceNotFoundException("USER_404")
            findUserById(id) != null -> {
                incrementOperationCount()
                true
            }
            else -> false
        }
    }
    
    // Product operations with complex business logic
    fun createProduct(name: String, price: BigDecimal, category: ProductCategory, tags: Set<String> = emptySet()): Product {
        validateProductInput(name, price, category)
        
        val processedTags = tags.map { it.lowercase().trim() }.toSet()
        val finalPrice = when {
            properties.features["autoDiscount"] == true && price > BigDecimal("500") -> 
                price.multiply(BigDecimal(1.0 - properties.defaultDiscountRate))
            else -> price
        }
        
        val product = Product(
            id = generateProductId(),
            name = name.trim(),
            price = finalPrice,
            category = category,
            tags = processedTags
        )
        
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
        tags: Set<String> = emptySet()
    ): List<Product> {
        val products = generateSampleProducts()
        
        return products.filter { product ->
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
            productIds.size > 10 -> OrderStatus.Cancelled("Too many items", null)
            user.age < 18 -> OrderStatus.Pending
            productIds.any { it.startsWith("RESTRICTED") } -> OrderStatus.Cancelled("Restricted items", null)
            else -> {
                val trackingNumber = generateTrackingNumber()
                val estimatedDelivery = LocalDateTime.now().plusDays(3)
                OrderStatus.Shipped(trackingNumber, estimatedDelivery)
            }
        }
    }
    
    fun updateOrderStatus(orderId: String, newStatus: OrderStatus): OrderStatus {
        return when (newStatus) {
            is OrderStatus.Pending -> {
                if (orderId.startsWith("URGENT")) OrderStatus.Processing
                else newStatus
            }
            is OrderStatus.Processing -> {
                val trackingNumber = generateTrackingNumber()
                val estimatedDelivery = LocalDateTime.now().plusDays(2)
                OrderStatus.Shipped(trackingNumber, estimatedDelivery)
            }
            is OrderStatus.Shipped -> {
                if (Random.nextBoolean()) {
                    val deliveryTime = LocalDateTime.now().plusHours(Random.nextLong(1, 24))
                    OrderStatus.Delivered(deliveryTime, "Electronic Signature")
                } else newStatus
            }
            else -> newStatus
        }
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
                else -> "Unknown operation: $operation"
            }
        }.also { incrementOperationCount() }
    }
    
    // Collection operations with higher-order functions
    fun analyzeUsers(users: List<User>): Map<String, Any> {
        if (users.isEmpty()) return emptyMap()
        
        val adults = users.filter { it.isAdult() }
        val activeUsers = users.filter { it.isActive }
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
        
        return mapOf(
            "totalUsers" to users.size,
            "adultUsers" to adults.size,
            "activeUsers" to activeUsers.size,
            "averageAge" to averageAge,
            "ageGroups" to ageGroups.mapValues { it.value.size },
            "emailDomains" to emailDomains,
            "oldestUser" to users.maxByOrNull { it.age },
            "youngestUser" to users.minByOrNull { it.age }
        ).also { incrementOperationCount() }
    }
    
    fun calculateDiscounts(products: List<Product>): Map<Product, BigDecimal> {
        return products.associateWith { product ->
            val baseDiscount = when (product.category) {
                ProductCategory.ELECTRONICS -> 0.05
                ProductCategory.CLOTHING -> 0.15
                ProductCategory.BOOKS -> 0.10
                ProductCategory.FOOD -> 0.02
                ProductCategory.HOME -> 0.08
            }
            
            val volumeDiscount = when {
                product.tags.contains("bulk") -> 0.10
                product.tags.contains("clearance") -> 0.25
                product.isExpensive -> 0.05
                else -> 0.0
            }
            
            val seasonalDiscount = when (LocalDateTime.now().monthValue) {
                12, 1, 2 -> 0.10  // Winter sale
                6, 7, 8 -> 0.15   // Summer sale
                else -> 0.0
            }
            
            val totalDiscount = (baseDiscount + volumeDiscount + seasonalDiscount).coerceAtMost(0.50)
            product.price.multiply(BigDecimal(totalDiscount))
        }.also { incrementOperationCount() }
    }
    
    // Validation methods with multiple branches
    private fun validateUserInput(name: String, email: String, age: Int) {
        when {
            name.isBlank() -> throw ValidationException("name", "Name cannot be blank")
            name.length > 100 -> throw ValidationException("name", "Name too long")
            !email.contains("@") -> throw ValidationException("email", "Invalid email format")
            email.length > 255 -> throw ValidationException("email", "Email too long")
            age < 0 -> throw ValidationException("age", "Age cannot be negative")
            age > 150 -> throw ValidationException("age", "Age seems unrealistic")
        }
    }
    
    private fun validateProductInput(name: String, price: BigDecimal, category: ProductCategory) {
        when {
            name.isBlank() -> throw ValidationException("name", "Product name cannot be blank")
            price < BigDecimal.ZERO -> throw ValidationException("price", "Price cannot be negative")
            price > BigDecimal("999999.99") -> throw ValidationException("price", "Price too high")
            !properties.allowedCategories.contains(category.name) -> 
                throw ValidationException("category", "Category not allowed: ${category.name}")
        }
    }
    
    // Helper methods with various logic paths
    private fun generateUserId(): Long = System.currentTimeMillis() + Random.nextLong(1000)
    
    private fun generateProductId(): String = "PROD_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
    
    private fun generateTrackingNumber(): String {
        val prefixes = listOf("TRK", "SHP", "DLV", "EXP")
        val prefix = prefixes[Random.nextInt(prefixes.size)]
        val number = String.format("%08d", Random.nextInt(100000000))
        return "$prefix$number"
    }
    
    private fun createSampleUser(id: Long): User {
        val names = listOf("Alice", "Bob", "Charlie", "Diana", "Eve", "Frank")
        val domains = listOf("example.com", "test.org", "sample.net")
        
        return User(
            id = id,
            name = names[Random.nextInt(names.size)],
            email = "${names[Random.nextInt(names.size)].lowercase()}@${domains[Random.nextInt(domains.size)]}",
            age = Random.nextInt(18, 80),
            isActive = Random.nextBoolean(),
            metadata = mapOf("generated" to true, "timestamp" to LocalDateTime.now())
        )
    }
    
    private fun generateSampleProducts(): List<Product> {
        val productNames = listOf(
            "Laptop", "Smartphone", "T-Shirt", "Novel", "Coffee Maker",
            "Headphones", "Jeans", "Cookbook", "Tablet", "Speakers"
        )
        
        return productNames.mapIndexed { index, name ->
            Product(
                id = "SAMPLE_$index",
                name = name,
                price = BigDecimal(Random.nextDouble(10.0, 1000.0)).setScale(2, BigDecimal.ROUND_HALF_UP),
                category = ProductCategory.values()[Random.nextInt(ProductCategory.values().size)],
                tags = setOf("sample", "test", if (Random.nextBoolean()) "popular" else "new")
            )
        }
    }
    
    @Synchronized
    private fun incrementOperationCount() {
        operationCount++
    }
    
    // Public methods for testing state
    fun getOperationCount(): Long = operationCount
    
    fun clearCache() {
        cache.clear()
        incrementOperationCount()
    }
    
    fun getCacheSize(): Int = cache.size
    
    fun getProperties(): TestServiceProperties = properties
    
    // Method with try-catch for exception testing
    fun safeOperation(input: String): String {
        return try {
            when {
                input.isBlank() -> throw IllegalArgumentException("Input cannot be blank")
                input == "error" -> throw ServiceException("Simulated error")
                input == "null" -> throw NullPointerException("Simulated null pointer")
                input.length > 1000 -> "Input too long"
                else -> "Processed: ${input.uppercase()}"
            }
        } catch (e: IllegalArgumentException) {
            "Invalid input: ${e.message}"
        } catch (e: ServiceException) {
            "Service error: ${e.message}"
        } catch (e: Exception) {
            "Unexpected error: ${e.javaClass.simpleName}"
        } finally {
            incrementOperationCount()
        }
    }
    
    // Method with complex conditional logic
    fun complexBusinessLogic(
        userAge: Int,
        productCategory: ProductCategory,
        orderAmount: BigDecimal,
        isPremiumMember: Boolean,
        seasonalPromo: Boolean
    ): Map<String, Any> {
        val eligibleForDiscount = when {
            userAge < 18 -> false
            userAge >= 65 -> true
            isPremiumMember -> true
            orderAmount > BigDecimal("200") -> true
            else -> false
        }
        
        val discountRate = when {
            !eligibleForDiscount -> 0.0
            seasonalPromo && isPremiumMember -> 0.25
            seasonalPromo -> 0.15
            isPremiumMember -> 0.10
            userAge >= 65 -> 0.12
            else -> 0.05
        }
        
        val taxRate = productCategory.taxRate
        val subtotal = orderAmount
        val discountAmount = subtotal.multiply(BigDecimal(discountRate))
        val taxableAmount = subtotal.subtract(discountAmount)
        val taxAmount = taxableAmount.multiply(BigDecimal(taxRate))
        val total = taxableAmount.add(taxAmount)
        
        val shippingCost = when {
            total > BigDecimal("100") -> BigDecimal.ZERO
            isPremiumMember -> BigDecimal("5.00")
            else -> BigDecimal("10.00")
        }
        
        val finalTotal = total.add(shippingCost)
        
        return mapOf(
            "subtotal" to subtotal,
            "discountRate" to discountRate,
            "discountAmount" to discountAmount,
            "taxAmount" to taxAmount,
            "shippingCost" to shippingCost,
            "total" to finalTotal,
            "eligibleForDiscount" to eligibleForDiscount,
            "estimatedDeliveryDays" to if (isPremiumMember) 1 else 3
        ).also { incrementOperationCount() }
    }
}

// Extension functions for testing
fun User.toDisplayString(): String = "${getDisplayName()} (${if (isActive) "Active" else "Inactive"})"

fun Product.getFormattedPrice(): String = "$${price.setScale(2, BigDecimal.ROUND_HALF_UP)}"

fun List<Product>.filterByPriceRange(min: BigDecimal, max: BigDecimal): List<Product> =
    filter { it.price >= min && it.price <= max }

fun OrderStatus.canBeCancelled(): Boolean = when (this) {
    is OrderStatus.Pending, is OrderStatus.Processing -> true
    is OrderStatus.Shipped -> false
    is OrderStatus.Delivered, is OrderStatus.Cancelled -> false
}

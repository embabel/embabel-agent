package com.embabel.common.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.NoSuchBeanDefinitionException

class ObjectProvidersTest {

    @Test
    fun empty() {
        val provider = ObjectProviders.empty<String>()

        assertThrows<NoSuchBeanDefinitionException> {
            provider.getObject()
        }

        assertNull(provider.getIfAvailable())
        assertNull(provider.getIfUnique())
        assertFalse(provider.iterator().hasNext())
    }

}
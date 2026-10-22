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
package com.embabel.common.ai.model

import com.embabel.common.ai.model.ServiceSelectionException.Reason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The registry selects models of a kind that has no [ModelType] value: these capabilities exist
 * only in this test.
 */
class ModelRegistryTest {

    /** A made-up capability: draw a picture from a prompt. */
    private interface Drawing : ModelCapability {
        fun draw(prompt: String): ByteArray
    }

    /** A made-up capability that also draws: draw, then animate. */
    private interface Animation : Drawing {
        fun animate(prompt: String): ByteArray
    }

    private open class Drawer : Drawing {
        override fun draw(prompt: String) = ByteArray(0)
    }

    private class Animator : Drawer(), Animation {
        override fun animate(prompt: String) = ByteArray(0)
    }

    private val drawingFamily = ModelRegistry.FamilySpec("drawing", "TestBuilder")
    private val animationFamily = ModelRegistry.FamilySpec("animation", "TestBuilder")

    private fun builder() = ModelRegistry.builder<Drawing>()
        .family(Drawing::class.java, drawingFamily)
        .family(Animation::class.java, animationFamily)

    @Test
    fun `the only registered model of a kind is that kind's default`() {
        val drawer = Drawer()
        val registry = builder().register("sketch", drawer).build()

        assertSame(drawer, registry.select(Drawing::class.java).defaultService())
    }

    @Test
    fun `a model that also animates is a member of both the drawing and the animation family`() {
        val animator = Animator()
        val registry = builder().register("sketch", Drawer()).register("motion", animator).build()

        // The animator is the only animation model, so it is the animation default.
        assertSame(animator, registry.select(Animation::class.java).defaultService())
        // The drawing family also holds the animator, because an animation is a drawing.
        assertSame(animator, registry.select(Drawing::class.java).named("motion"))
    }

    @Test
    fun `selecting a model by name from a family it does not belong to fails with wrong capability`() {
        val registry = builder().register("sketch", Drawer()).build()

        val failure = assertThrows<ServiceSelectionException> {
            registry.select(Animation::class.java).named("sketch")
        }
        assertEquals(Reason.WRONG_CAPABILITY, failure.reason)
    }

    @Test
    fun `a role bound in configuration resolves to the named model`() {
        val animator = Animator()
        val registry = builder()
            .register("sketch", Drawer())
            .register("motion", animator)
            .role(Drawing::class.java, "best", "motion")
            .build()

        assertSame(animator, registry.select(Drawing::class.java).byRole("best"))
    }

    @Test
    fun `an unbound role fails and names the configuration property to set`() {
        val registry = builder().register("sketch", Drawer()).build()

        val failure = assertThrows<ServiceSelectionException> {
            registry.select(Drawing::class.java).byRole("cheapest")
        }
        assertEquals(Reason.UNKNOWN_ROLE, failure.reason)
        assertTrue(failure.message.orEmpty().contains("embabel.models.drawing.roles.cheapest"), failure.message)
    }

    @Test
    fun `a default that names a model of the wrong kind is rejected when the registry is built`() {
        val failure = assertThrows<IllegalArgumentException> {
            builder().register("sketch", Drawer()).defaultFor(Animation::class.java, "sketch").build()
        }
        assertTrue(failure.message.orEmpty().contains("embabel.models.animation.default"), failure.message)
    }

    @Test
    fun `a model a source serves is created on the first lookup and reused after`() {
        val source = CountingSource()
        val registry = builder().source(source).build()
        val drawings = registry.select(Drawing::class.java)

        val first = drawings.named("served")
        val second = drawings.named("served")

        // Both lookups return the same model, and the source built it once.
        assertSame(first, second)
        assertEquals(1, source.created)
    }

    @Test
    fun `a default naming a source model of the wrong kind says the model lacks the capability, not that it is unknown`() {
        val failure = assertThrows<IllegalArgumentException> {
            builder().source(CountingSource()).defaultFor(Animation::class.java, "served").build()
        }
        val message = failure.message.orEmpty()
        // The message says the model cannot animate.
        assertTrue(message.contains("without animation support"), message)
        // The message does not say the model is missing, because the source serves it.
        assertFalse(message.contains("unknown service"), message)
    }

    @Test
    fun `selecting a source model by name from a family its type does not fit fails with wrong capability`() {
        val registry = builder().source(CountingSource()).build()

        val failure = assertThrows<ServiceSelectionException> {
            registry.select(Animation::class.java).named("served")
        }
        assertEquals(Reason.WRONG_CAPABILITY, failure.reason)
    }

    @Test
    fun `a selection error lists only the source models that fit the family`() {
        val registry = builder().source(CountingSource()).build()

        val failure = assertThrows<ServiceSelectionException> {
            registry.select(Animation::class.java).defaultService()
        }
        // The source serves only drawing models, so its model is not listed for the animation family.
        assertFalse(failure.message.orEmpty().contains("served"), failure.message)
    }

    @Test
    fun `declaring a second family for the same capability interface fails`() {
        assertThrows<IllegalArgumentException> { builder().family(Drawing::class.java, drawingFamily) }
    }

    @Test
    fun `a default set before its family is declared fails`() {
        assertThrows<IllegalArgumentException> {
            ModelRegistry.builder<Drawing>().defaultFor(Drawing::class.java, "sketch")
        }
    }

    @Test
    fun `asking for a family that was never declared fails`() {
        val registry = ModelRegistry.builder<Drawing>().build()

        assertThrows<IllegalArgumentException> { registry.select(Animation::class.java) }
    }

    @Test
    fun `two different defaults for one family fail the build and name the default property`() {
        val failure = assertThrows<IllegalArgumentException> {
            builder()
                .register("sketch", Drawer())
                .register("motion", Animator())
                .defaultFor(Drawing::class.java, "sketch")
                .defaultFor(Drawing::class.java, "motion")
                .build()
        }
        assertTrue(failure.message.orEmpty().contains("embabel.models.drawing.default"), failure.message)
    }

    @Test
    fun `setting the same default or the same role binding twice is accepted`() {
        val drawer = Drawer()
        val registry = builder()
            .register("sketch", drawer)
            .defaultFor(Drawing::class.java, "sketch")
            .defaultFor(Drawing::class.java, "sketch")
            .role(Drawing::class.java, "cheap", "sketch")
            .role(Drawing::class.java, "cheap", "sketch")
            .build()
        val drawings = registry.select(Drawing::class.java)

        assertSame(drawer, drawings.defaultService())
        assertSame(drawer, drawings.byRole("cheap"))
    }

    @Test
    fun `a role bound to a blank name and a blank default candidate each fail the build`() {
        assertThrows<IllegalArgumentException> {
            builder().register("sketch", Drawer()).role(Drawing::class.java, "cheap", " ").build()
        }
        assertThrows<IllegalArgumentException> {
            builder().register("sketch", Drawer()).defaultCandidate(" ").build()
        }
    }

    @Test
    fun `a source whose every name is held by a registered model is left out of selection messages`() {
        // The registered model "served" hides the only model the counting source serves.
        val registry = builder()
            .register("served", Drawer())
            .source(CountingSource())
            .source(NamedSource("other", "extra"))
            .build()

        val failure = assertThrows<ServiceSelectionException> {
            registry.select(Drawing::class.java).named("missing")
        }
        val message = failure.message.orEmpty()
        // The source that still serves a name is listed with that name.
        assertTrue(message.contains("other [extra]"), message)
        // The counting source has no name left to serve, so it is not listed with an empty list.
        assertFalse(message.contains("test []"), message)
    }

    @Test
    fun `a source that returns no model is asked again on the next lookup`() {
        // The source returns nothing on the first call and a model on every call after.
        val source = object : ModelSource<Drawing> {
            var calls = 0
            override val provider = "late"
            override val type = Drawing::class.java
            override fun modelNames() = listOf("late")
            override fun modelNamed(name: String): Drawing? = if (calls++ == 0) null else Drawer()
        }
        val drawings = builder().source(source).build().select(Drawing::class.java)

        // The first lookup finds no model, and the registry does not remember that.
        assertEquals(Reason.UNKNOWN_NAME, assertThrows<ServiceSelectionException> { drawings.named("late") }.reason)
        // The second lookup asks the source again and gets the model.
        val model = drawings.named("late")
        // The model is now kept, so a third lookup does not ask the source.
        assertSame(model, drawings.named("late"))
        assertEquals(2, source.calls)
    }

    @Test
    fun `a name two sources serve resolves to the model of the source added first`() {
        val first = NamedSource("first", "shared")
        val second = NamedSource("second", "shared")
        val drawings = builder().source(first).source(second).build().select(Drawing::class.java)

        val model = drawings.named("shared")

        assertSame(first.built.single(), model)
        // The second source is never asked, because the first one served the name.
        assertTrue(second.built.isEmpty())
    }

    @Test
    fun `a default that a later source of the wrong kind serves at lookup fails with wrong capability`() {
        // The animation source lists "x" first, so the build accepts "x" as the animation default.
        // It then returns no model for "x", so the lookup falls through to the drawing source.
        val listsButReturnsNothing = object : ModelSource<Drawing> {
            override val provider = "empty"
            override val type = Animation::class.java
            override fun modelNames() = listOf("x")
            override fun modelNamed(name: String): Drawing? = null
        }
        val animations = builder()
            .source(listsButReturnsNothing)
            .source(NamedSource("drawings", "x"))
            .defaultFor(Animation::class.java, "x")
            .build()
            .select(Animation::class.java)

        // The drawing source's model cannot animate, so the lookup fails with a selection error.
        val failure = assertThrows<ServiceSelectionException> { animations.defaultService() }
        assertEquals(Reason.WRONG_CAPABILITY, failure.reason)
    }

    /** Serves drawing models under the given names, from a provider with the given name. */
    private class NamedSource(override val provider: String, private vararg val names: String) : ModelSource<Drawing> {
        /** The models this source has built, in the order it built them. */
        val built = mutableListOf<Drawing>()
        override val type = Drawing::class.java
        override fun modelNames() = names.toList()
        override fun modelNamed(name: String): Drawing? = if (name in names) Drawer().also { built += it } else null
    }

    /** Serves one drawing model, named `served`, and counts how many times it builds it. */
    private class CountingSource : ModelSource<Drawing> {
        var created = 0
        override val provider = "test"
        override val type = Drawing::class.java
        override fun modelNames() = listOf("served")
        override fun modelNamed(name: String): Drawing? =
            if (name == "served") Drawer().also { created++ } else null
    }
}

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
package com.embabel.common.ai.decision.annotated;

import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.introspect.AnnotatedParameter;
import tools.jackson.databind.introspect.BeanPropertyDefinition;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/** Compiler placement rules for repeated Kotlin constructor annotations. */
final class KotlinAnnotationPlacement {
    private KotlinAnnotationPlacement() { }

    private static final @Nullable Class<? extends Annotation> KOTLIN_METADATA = kotlinMetadata();
    private static final List<Class<? extends Annotation>> QUESTION_ANNOTATIONS =
        List.of(PropositionQuestion.class, ChoiceQuestion.class, RatingQuestion.class);

    /**
     * Checks whether a data class {@code copy()} parameter carries the same question annotations
     * as the matching constructor parameter.
     *
     * @param parameter the copy() parameter to check
     * @param copied the primary constructor, or null when the method is not a copy()
     * @param index the parameter's index
     * @return true when the parameter repeats the constructor parameter's question annotations
     */
    static boolean repeatsCopySource(Parameter parameter, @Nullable Constructor<?> copied, int index) {
        return copied != null
            && questionAnnotationsOn(parameter).equals(questionAnnotationsOn(copied.getParameters()[index]));
    }

    /**
     * Checks whether a field is a Kotlin backing field for a constructor parameter. Kotlin's later
     * default site repeats a constructor parameter annotation on the private backing field. Jackson
     * leaves that field out, but it is the same declaration, so it is skipped when its question
     * annotations match the parameter's.
     *
     * @param owner the class declaring the field
     * @param field the field to check
     * @return true when the field backs a constructor parameter with the same question annotations
     */
    static boolean isBackingField(Class<?> owner, Field field, @Nullable BeanPropertyDefinition property) {
        if (property == null || !Modifier.isPrivate(field.getModifiers()) || !isKotlinClass(owner)) {
            return false;
        }
        List<Annotation> onField = questionAnnotationsOn(field);
        for (Iterator<AnnotatedParameter> parameters = property.getConstructorParameters(); parameters.hasNext(); ) {
            AnnotatedParameter parameter = parameters.next();
            Executable creator = (Executable) parameter.getOwner().getAnnotated();
            if (onField.equals(questionAnnotationsOn(creator.getParameters()[parameter.getIndex()]))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the primary constructor a Kotlin data class {@code copy()} method was generated from.
     * A data class generates {@code copy()} with the primary constructor's parameters and repeats
     * each constructor parameter annotation on it.
     *
     * @param owner the class declaring the method
     * @param method the method to check
     * @return the primary constructor when the method has this shape, otherwise null
     */
    static @Nullable Constructor<?> copySource(Class<?> owner, Method method) {
        if (!method.getName().equals("copy") || method.getReturnType() != owner || !isKotlinClass(owner)) {
            return null;
        }
        for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
            if (!constructor.isSynthetic()
                && Arrays.equals(constructor.getGenericParameterTypes(), method.getGenericParameterTypes())) {
                return constructor;
            }
        }
        return null;
    }

    /**
     * Checks whether a class was compiled by the Kotlin compiler.
     *
     * @param type the class to check
     * @return true when the class carries {@code kotlin.Metadata}
     */
    static boolean isKotlinClass(Class<?> type) {
        return KOTLIN_METADATA != null && type.isAnnotationPresent(KOTLIN_METADATA);
    }

    /**
     * Loads the Kotlin compiler's {@code kotlin.Metadata} annotation type by name, so the module
     * has no compile-time dependency on Kotlin.
     *
     * @return the annotation type, or null when Kotlin is absent from the classpath
     */
    private static @Nullable Class<? extends Annotation> kotlinMetadata() {
        try {
            return Class.forName("kotlin.Metadata", false, KotlinAnnotationPlacement.class.getClassLoader())
                .asSubclass(Annotation.class);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /**
     * Reads the question annotations a reflective element carries.
     *
     * @param element the field, method or parameter to check
     * @return the annotation instances found, in question-kind order
     */
    private static List<Annotation> questionAnnotationsOn(AnnotatedElement element) {
        List<Annotation> annotations = new ArrayList<>();
        for (Class<? extends Annotation> annotationType : QUESTION_ANNOTATIONS) {
            Annotation annotation = element.getAnnotation(annotationType);
            if (annotation != null) {
                annotations.add(annotation);
            }
        }
        return annotations;
    }

}

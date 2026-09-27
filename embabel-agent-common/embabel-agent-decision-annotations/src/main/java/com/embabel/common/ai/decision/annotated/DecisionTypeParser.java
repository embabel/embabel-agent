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

import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.Question;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import org.jetbrains.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.AnnotationIntrospector;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.introspect.AccessorNamingStrategy;
import tools.jackson.databind.introspect.AnnotatedClass;
import tools.jackson.databind.introspect.AnnotatedConstructor;
import tools.jackson.databind.introspect.AnnotatedField;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.introspect.AnnotatedMethod;
import tools.jackson.databind.introspect.AnnotatedParameter;
import tools.jackson.databind.introspect.AnnotatedWithParams;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.introspect.ClassIntrospector;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads the decision spec of one type under one mapper.
 * <p>
 * Jackson supplies every name and the question order: the parser reads the creation view of the
 * type, which is the view the mapper binds when it reads a value of the type. Plain reflection
 * only locates question annotations that Jackson does not attach to any property. The parser
 * collects every problem it finds and reports them together.
 */
final class DecisionTypeParser {

    private static final String IGNORED =
        " but Jackson ignores the property (@JsonIgnore, @JsonIgnoreProperties or @JsonIncludeProperties). "
            + "Remove the question annotation or stop ignoring the property.";

    private static final String NOT_A_PROPERTY =
        " but is not a Jackson property. Move the annotation to a record component, field, getter or creator parameter.";

    private static final String COLLISION_FIX =
        "Give each Java member its own property name, or correct the definition the message names.";

    /** The three question annotations, in the order problems name them. */
    private enum Kind {
        PROPOSITION(PropositionQuestion.class, annotation -> ((PropositionQuestion) annotation).asking()),
        CHOICE(ChoiceQuestion.class, annotation -> ((ChoiceQuestion) annotation).asking()),
        RATING(RatingQuestion.class, annotation -> ((RatingQuestion) annotation).asking());

        final Class<? extends Annotation> annotation;

        final Function<Annotation, String> asking;

        Kind(Class<? extends Annotation> annotation, Function<Annotation, String> asking) {
            this.annotation = annotation;
            this.asking = asking;
        }

        String label() {
            return "@" + annotation.getSimpleName();
        }
    }

    /** One option or level read from an enum constant. The description is null when the constant has no {@link Described}. */
    private record Entry(String id, @Nullable String description) {
    }

    private final Class<?> type;

    private final ObjectMapper mapper;

    private final DeserializationConfig config;

    private final AnnotationIntrospector introspector;

    private final List<String> problems = new ArrayList<>();

    private final List<Throwable> causes = new ArrayList<>();

    private DecisionTypeParser(Class<?> type, ObjectMapper mapper) {
        this.type = type;
        this.mapper = mapper;
        this.config = mapper.deserializationConfig();
        this.introspector = config.isAnnotationProcessingEnabled()
            ? config.getAnnotationIntrospector()
            : AnnotationIntrospector.nopInstance();
    }

    /**
     * Reads the decision spec of a type.
     *
     * @param type the annotated type
     * @param mapper the mapper whose names and order apply
     * @param <T> the annotated type
     * @return the decision for the type
     * @throws AnnotatedDecisionException listing every problem found
     */
    static <T> AnnotatedDecision<T> parse(Class<T> type, ObjectMapper mapper) {
        DecisionTypeParser parser = new DecisionTypeParser(type, mapper);
        Parsed parsed = parser.read();
        return new AnnotatedDecision<>(type, parsed.spec, parsed.questionNames, parsed.settableNames, mapper);
    }

    private record Parsed(DecisionSpec spec, Map<String, String> questionNames, List<String> settableNames) {
    }

    private Parsed read() {
        JavaType javaType = mapper.constructType(type);
        BeanDescription description;
        List<BeanPropertyDefinition> properties;
        try {
            // The same two calls DeserializationContext.introspectBeanDescriptionForCreation makes,
            // reached through the mapper's public configuration.
            ClassIntrospector classIntrospector = config.classIntrospectorInstance();
            description = classIntrospector.introspectForCreation(
                javaType, classIntrospector.introspectClassAnnotations(javaType));
            properties = description.findProperties();
        } catch (IllegalArgumentException | JacksonException e) {
            problem(type.getSimpleName() + ": Jackson cannot read the properties of the type (" + messageOf(e) + "). "
                + COLLISION_FIX, e);
            throw failure();
        }

        AnnotatedClass classInfo = description.getClassInfo();
        AccessorNamingStrategy naming = javaType.isRecordType()
            ? config.getAccessorNaming().forRecord(config, classInfo)
            : config.getAccessorNaming().forPOJO(config, classInfo);
        Ignorals ignorals = new Ignorals(description, classInfo);

        Map<String, String> questionNames = new LinkedHashMap<>();
        List<Question<?>> questions = new ArrayList<>();
        List<String> settableNames = new ArrayList<>();
        Coverage coverage = new Coverage();
        Set<String> reportedIgnored = new HashSet<>();

        for (BeanPropertyDefinition property : properties) {
            String member = type.getSimpleName() + "." + property.getInternalName();
            List<AnnotatedMember> members;
            try {
                members = membersOf(property);
            } catch (IllegalArgumentException e) {
                problem(member + ": Jackson cannot read the property \"" + property.getName() + "\" (" + messageOf(e)
                    + "). " + COLLISION_FIX, e);
                continue;
            }
            members.forEach(coverage::add);
            // Projection supplies a value for each of these, from an answer or from otherProperties.
            if (property.getMutator() != null && !ignorals.ignores(property)) {
                settableNames.add(property.getName());
            }

            Map<Kind, Set<String>> declared = questionAnnotations(members);
            if (declared.isEmpty()) {
                continue;
            }
            if (declared.size() > 1) {
                problem(member + ": carries " + labels(declared.keySet()) + ". Keep one question annotation on the property.");
                continue;
            }
            Kind kind = declared.keySet().iterator().next();
            Set<String> askings = declared.get(kind);
            if (askings.size() > 1) {
                problem(member + ": members of the property carry " + kind.label() + " with different asking values ("
                    + String.join(", ", askings.stream().map(value -> "\"" + value + "\"").toList())
                    + "). Use one asking value on every annotated member of the property.");
                continue;
            }
            if (ignorals.ignores(property)) {
                problem(member + ": carries " + kind.label() + IGNORED);
                reportedIgnored.add(property.getInternalName());
                continue;
            }
            List<String> merged = mergedMembers(property, naming);
            if (!merged.isEmpty()) {
                problem(member + ": Jackson merges " + joined(merged) + " into the property \"" + property.getName()
                    + "\". Rename the members so they share one Java name, or give each its own property name.");
                continue;
            }
            if (property.getMutator() == null) {
                problem(member + ": carries " + kind.label() + " but Jackson has no creator parameter, setter or field "
                    + "to set it. Add one of these members for the property.");
                continue;
            }

            int before = problems.size();
            String asking = askings.iterator().next();
            if (asking.isBlank()) {
                problem(member + ": " + kind.label() + " has a blank asking value. "
                    + "Set asking to the instructions the model receives.");
            }
            List<Entry> entries = checkType(member, kind, property);
            if (problems.size() > before) {
                continue;
            }
            Question<?> question = build(member, kind, property.getName(), asking, entries);
            if (question != null) {
                questions.add(question);
                questionNames.put(property.getInternalName(), property.getName());
            }
        }

        scanForOrphans(coverage, ignorals, reportedIgnored, classInfo, naming);

        if (problems.isEmpty() && questions.isEmpty()) {
            problem(type.getSimpleName() + ": declares no questions. "
                + "Annotate at least one property with @PropositionQuestion, @ChoiceQuestion or @RatingQuestion.");
        }
        if (!problems.isEmpty()) {
            throw failure();
        }
        return new Parsed(DecisionSpec.of(questions), questionNames, settableNames);
    }

    // Field, getter, setter and creator parameters of one property. The getters throw when two
    // members of one kind claim the property.
    private static List<AnnotatedMember> membersOf(BeanPropertyDefinition property) {
        List<AnnotatedMember> members = new ArrayList<>();
        for (Iterator<AnnotatedParameter> parameters = property.getConstructorParameters(); parameters.hasNext(); ) {
            members.add(parameters.next());
        }
        addIfPresent(members, property.getField());
        addIfPresent(members, property.getGetter());
        addIfPresent(members, property.getSetter());
        return members;
    }

    private static void addIfPresent(List<AnnotatedMember> members, @Nullable AnnotatedMember member) {
        if (member != null) {
            members.add(member);
        }
    }

    // Reads annotations through Jackson, so mix-ins and annotations inherited by overriding
    // methods count. Keys follow Kind order and values follow member order.
    private static Map<Kind, Set<String>> questionAnnotations(List<AnnotatedMember> members) {
        Map<Kind, Set<String>> declared = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            for (AnnotatedMember member : members) {
                Annotation annotation = member.getAnnotation(kind.annotation);
                if (annotation != null) {
                    declared.computeIfAbsent(kind, ignored -> new LinkedHashSet<>()).add(kind.asking.apply(annotation));
                }
            }
        }
        return declared;
    }

    // Java members of the property whose own Jackson names differ. Creator parameters are left out,
    // because a creator parameter often carries a different Java name and an explicit property name.
    private List<String> mergedMembers(BeanPropertyDefinition property, AccessorNamingStrategy naming) {
        List<AnnotatedMember> accessors = new ArrayList<>();
        addIfPresent(accessors, property.getField());
        addIfPresent(accessors, property.getGetter());
        addIfPresent(accessors, property.getSetter());
        Map<String, String> byImplicitName = new LinkedHashMap<>();
        for (AnnotatedMember member : accessors) {
            String implicit = implicitName(member, naming);
            if (implicit != null) {
                byImplicitName.putIfAbsent(implicit, label(member));
            }
        }
        return byImplicitName.size() > 1 ? List.copyOf(byImplicitName.values()) : List.of();
    }

    private List<Entry> checkType(String member, Kind kind, BeanPropertyDefinition property) {
        Class<?> raw = property.getRawPrimaryType();
        boolean supported = kind == Kind.PROPOSITION
            ? raw == boolean.class || raw == Boolean.class
            : raw.isEnum();
        if (!supported) {
            Type declared = declaredType(property.getPrimaryMember());
            if (declared instanceof TypeVariable<?> variable) {
                String fix = kind == Kind.PROPOSITION
                    ? "Declare the property as boolean or Boolean."
                    : "Declare the property with a concrete enum type.";
                problem(member + ": " + kind.label() + " needs " + (kind == Kind.PROPOSITION ? "boolean or Boolean" : "a concrete enum type")
                    + ", found type variable " + variable.getName() + ", which Jackson reads as " + raw.getTypeName() + ". " + fix);
            } else {
                problem(member + ": " + kind.label() + switch (kind) {
                    case PROPOSITION -> " needs boolean or Boolean, found " + raw.getTypeName()
                        + ". Declare the property as boolean or Boolean.";
                    case CHOICE -> " needs an enum type, found " + raw.getTypeName() + ". Declare the options as an enum.";
                    case RATING -> " needs an enum type, found " + raw.getTypeName()
                        + ". Declare the levels as an enum, lowest first.";
                });
            }
            return List.of();
        }
        return kind == Kind.PROPOSITION ? List.of() : entries(member, kind, raw);
    }

    // Reads options or levels from enum constants in declaration order. Ids are the mapper's
    // serialized form of each constant, and each id must read back as the same constant.
    private List<Entry> entries(String member, Kind kind, Class<?> enumType) {
        Object[] constants = enumType.getEnumConstants();
        String enumName = enumType.getSimpleName();
        String entry = kind == Kind.CHOICE ? "option" : "level";
        if (kind == Kind.CHOICE && constants.length == 0) {
            problem(member + ": " + enumName + " has no constants, so the choice has no options. "
                + "Add one constant per option to " + enumName + ".");
        }
        if (kind == Kind.RATING && constants.length < 2) {
            problem(member + ": " + enumName + " has " + constants.length + (constants.length == 1 ? " constant" : " constants")
                + ", and a rating needs at least two levels. Add the levels to " + enumName + ", lowest first.");
        }
        List<Entry> entries = new ArrayList<>();
        for (Object constant : constants) {
            String constantName = enumName + "." + ((Enum<?>) constant).name();
            JsonNode node;
            try {
                node = mapper.valueToTree(constant);
            } catch (JacksonException e) {
                problem(member + ": " + constantName + " cannot be written under this mapper (" + messageOf(e) + "). "
                    + "Make each constant of " + enumName + " writable as a JSON string.", e);
                continue;
            }
            if (node == null || !node.isString()) {
                problem(member + ": " + constantName + " serializes as " + node + " under this mapper, and an " + entry
                    + " id must be a JSON string. Use a mapper that writes " + enumName + " constants as strings, "
                    + "for example with EnumFeature.WRITE_ENUMS_USING_INDEX disabled.");
                continue;
            }
            String id = node.stringValue();
            Object readBack;
            try {
                readBack = mapper.treeToValue(node, enumType);
            } catch (JacksonException e) {
                problem(member + ": " + entry + " id \"" + id + "\" of " + constantName + " does not read back under this "
                    + "mapper (" + messageOf(e) + "). Make each constant of " + enumName + " readable from its serialized form.", e);
                continue;
            }
            if (readBack != constant) {
                String other = readBack == null ? "null" : enumName + "." + ((Enum<?>) readBack).name();
                problem(member + ": " + entry + " id \"" + id + "\" of " + constantName + " reads back as " + other
                    + " under this mapper. Give each constant of " + enumName
                    + " a distinct serialized form that the mapper reads back as the same constant.");
                continue;
            }
            Described described = describedOf((Enum<?>) constant);
            if (kind == Kind.CHOICE && described == null) {
                problem(member + ": choice option " + constantName + " has no @Described. "
                    + "Add @Described with the option's description to " + constantName + ".");
                continue;
            }
            entries.add(new Entry(id, described == null ? null : described.value()));
        }
        return entries;
    }

    private static @Nullable Described describedOf(Enum<?> constant) {
        try {
            return constant.getDeclaringClass().getField(constant.name()).getAnnotation(Described.class);
        } catch (NoSuchFieldException e) {
            // Every enum constant has a public field of its own name.
            throw new IllegalStateException("No field for enum constant " + constant, e);
        }
    }

    private @Nullable Question<?> build(String member, Kind kind, String name, String asking, List<Entry> entries) {
        try {
            return switch (kind) {
                case PROPOSITION -> Questions.named(name).proposition(asking).build();
                case CHOICE -> {
                    ChoiceQuestionSpec.Builder choice = Questions.named(name).choice(asking);
                    entries.forEach(entry -> choice.option(entry.id(), entry.description()));
                    yield choice.build();
                }
                case RATING -> {
                    RatingQuestionSpec.Builder rating = Questions.named(name).rating(asking);
                    for (Entry entry : entries) {
                        if (entry.description() == null) {
                            rating.level(entry.id());
                        } else {
                            rating.level(entry.id(), entry.description());
                        }
                    }
                    yield rating.build();
                }
            };
        } catch (IllegalArgumentException e) {
            problem(member + ": the question is invalid (" + e.getMessage() + "). "
                + "Correct the annotation values on the property.", e);
            return null;
        }
    }

    // Reports question annotations on members that belong to no Jackson property. Walks the
    // declared fields, methods and parameters of the type and its superclasses.
    private void scanForOrphans(
        Coverage coverage, Ignorals ignorals, Set<String> reportedIgnored, AnnotatedClass classInfo,
        AccessorNamingStrategy naming) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Set<String> componentFields = new HashSet<>();
            if (current.isRecord()) {
                // A record component annotation also lands on the private field, which Jackson does not use.
                // The same annotation on the accessor and the canonical constructor parameter is what counts.
                for (RecordComponent component : current.getRecordComponents()) {
                    componentFields.add(component.getName());
                }
            }
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || componentFields.contains(field.getName()) || coverage.covers(field)) {
                    continue;
                }
                orphan(current, field.getName(), field, find(classInfo.fields(), field), ignorals, reportedIgnored, naming);
            }
            for (Method method : current.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                if (!coverage.covers(method)) {
                    orphan(current, method.getName() + "()", method, find(classInfo.memberMethods(), method),
                        ignorals, reportedIgnored, naming);
                }
                scanParameters(current, method, "parameter of " + method.getName() + "()", coverage, ignorals,
                    reportedIgnored, classInfo, naming);
            }
            for (Constructor<?> constructor : current.getDeclaredConstructors()) {
                if (!constructor.isSynthetic()) {
                    scanParameters(current, constructor, "constructor parameter", coverage, ignorals, reportedIgnored,
                        classInfo, naming);
                }
            }
        }
    }

    private void scanParameters(
        Class<?> declaringClass, Executable executable, String role, Coverage coverage, Ignorals ignorals,
        Set<String> reportedIgnored, AnnotatedClass classInfo, AccessorNamingStrategy naming) {
        Parameter[] parameters = executable.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            if (coverage.covers(executable, index)) {
                continue;
            }
            orphan(declaringClass, parameters[index].getName() + " (" + role + ")", parameters[index],
                parameterOf(classInfo, executable, index), ignorals, reportedIgnored, naming);
        }
    }

    private void orphan(
        Class<?> declaringClass, String memberLabel, AnnotatedElement element, @Nullable AnnotatedMember jacksonMember,
        Ignorals ignorals, Set<String> reportedIgnored, AccessorNamingStrategy naming) {
        Set<Kind> kinds = new LinkedHashSet<>();
        for (Kind kind : Kind.values()) {
            if (element.isAnnotationPresent(kind.annotation)) {
                kinds.add(kind);
            }
        }
        if (kinds.isEmpty()) {
            return;
        }
        String implicit = jacksonMember == null ? null : implicitName(jacksonMember, naming);
        boolean ignored = jacksonMember == null
            ? element.isAnnotationPresent(JsonIgnore.class) && element.getAnnotation(JsonIgnore.class).value()
            : introspector.hasIgnoreMarker(config, jacksonMember) || ignorals.ignoresName(implicit);
        if (ignored && implicit != null && reportedIgnored.contains(implicit)) {
            // The property itself was already reported as ignored.
            return;
        }
        problem(declaringClass.getSimpleName() + "." + memberLabel + ": carries " + labels(kinds)
            + (ignored ? IGNORED : NOT_A_PROPERTY));
    }

    // Jackson's own name for a member before renaming: the annotation introspector first, then the
    // mapper's accessor naming. This is the name Jackson groups members by.
    private @Nullable String implicitName(AnnotatedMember member, AccessorNamingStrategy naming) {
        String name = introspector.findImplicitPropertyName(config, member);
        if (name != null) {
            return name;
        }
        if (member instanceof AnnotatedField field) {
            return naming.modifyFieldName(field, field.getName());
        }
        if (member instanceof AnnotatedMethod method) {
            if (method.getParameterCount() == 0) {
                String getter = naming.findNameForRegularGetter(method, method.getName());
                return getter != null ? getter : naming.findNameForIsGetter(method, method.getName());
            }
            if (method.getParameterCount() == 1) {
                return naming.findNameForMutator(method, method.getName());
            }
        }
        return null;
    }

    private static <M extends AnnotatedMember> @Nullable M find(Iterable<M> members, AnnotatedElement element) {
        for (M member : members) {
            if (element.equals(member.getAnnotated())) {
                return member;
            }
        }
        return null;
    }

    private static @Nullable AnnotatedParameter parameterOf(AnnotatedClass classInfo, Executable executable, int index) {
        List<AnnotatedWithParams> owners = new ArrayList<>(classInfo.getConstructors());
        owners.addAll(classInfo.getFactoryMethods());
        classInfo.memberMethods().forEach(owners::add);
        for (AnnotatedWithParams owner : owners) {
            if (executable.equals(owner.getAnnotated()) && index < owner.getParameterCount()) {
                return owner.getParameter(index);
            }
        }
        return null;
    }

    // The declared Java type of a member, used to recognise a type variable Jackson resolved to its bound.
    private static @Nullable Type declaredType(@Nullable AnnotatedMember member) {
        if (member instanceof AnnotatedField field) {
            return field.getAnnotated().getGenericType();
        }
        if (member instanceof AnnotatedMethod method) {
            Method raw = method.getAnnotated();
            return raw.getParameterCount() == 0 ? raw.getGenericReturnType() : raw.getGenericParameterTypes()[0];
        }
        if (member instanceof AnnotatedParameter parameter
            && parameter.getOwner().getAnnotated() instanceof Executable executable) {
            Parameter[] parameters = executable.getParameters();
            int index = parameter.getIndex();
            return index < parameters.length ? parameters[index].getParameterizedType() : null;
        }
        return null;
    }

    private static String label(AnnotatedMember member) {
        return member instanceof AnnotatedMethod ? member.getName() + "()" : member.getName();
    }

    private static String labels(Set<Kind> kinds) {
        return joined(kinds.stream().map(Kind::label).toList());
    }

    private static String joined(List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    private static String messageOf(Throwable e) {
        return e instanceof JacksonException jackson ? jackson.getOriginalMessage() : e.getMessage();
    }

    private void problem(String problem) {
        problems.add(problem);
    }

    private void problem(String problem, Throwable cause) {
        problems.add(problem);
        causes.add(cause);
    }

    private AnnotatedDecisionException failure() {
        AnnotatedDecisionException failure =
            new AnnotatedDecisionException(type, problems, causes.isEmpty() ? null : causes.get(0));
        causes.stream().skip(1).forEach(failure::addSuppressed);
        return failure;
    }

    /** Java members that belong to some Jackson property of the type. */
    private static final class Coverage {

        private final Set<Object> members = new HashSet<>();

        private final List<Method> methods = new ArrayList<>();

        void add(AnnotatedMember member) {
            if (member instanceof AnnotatedParameter parameter) {
                members.add(List.of(parameter.getOwner().getAnnotated(), parameter.getIndex()));
            } else {
                members.add(member.getAnnotated());
                if (member.getAnnotated() instanceof Method method) {
                    methods.add(method);
                }
            }
        }

        boolean covers(Field field) {
            return members.contains(field);
        }

        // A superclass method counts when a property method overrides it, because Jackson merges
        // the annotations of overridden methods into the overriding one.
        boolean covers(Method method) {
            if (members.contains(method)) {
                return true;
            }
            for (Method covered : methods) {
                if (covered.getName().equals(method.getName())
                    && Arrays.equals(covered.getParameterTypes(), method.getParameterTypes())
                    && method.getDeclaringClass().isAssignableFrom(covered.getDeclaringClass())) {
                    return true;
                }
            }
            return false;
        }

        boolean covers(Executable executable, int index) {
            return members.contains(List.of(executable, index));
        }
    }

    /** The names Jackson ignores when it reads the type. */
    private final class Ignorals {

        private final Set<String> ignoredNames;

        private final Set<String> typeIgnored;

        private final @Nullable Set<String> included;

        Ignorals(BeanDescription description, AnnotatedClass classInfo) {
            ignoredNames = new HashSet<>(description.getIgnoredPropertyNames());
            JsonIgnoreProperties.Value ignorals = config.getDefaultPropertyIgnorals(type, classInfo);
            typeIgnored = ignorals == null ? Set.of() : ignorals.findIgnoredForDeserialization();
            JsonIncludeProperties.Value inclusions = config.getDefaultPropertyInclusions(type, classInfo);
            included = inclusions == null ? null : inclusions.getIncluded();
        }

        boolean ignores(BeanPropertyDefinition property) {
            return ignoredNames.contains(property.getInternalName())
                || ignoresName(property.getName());
        }

        boolean ignoresName(@Nullable String name) {
            return name != null
                && (ignoredNames.contains(name) || typeIgnored.contains(name) || included != null && !included.contains(name));
        }
    }
}

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

import com.embabel.common.ai.classification.ClassificationSpec;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.Question;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.annotated.EnumEntries.Entry;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import org.jetbrains.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.AnnotationIntrospector;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.introspect.AccessorNamingStrategy;
import tools.jackson.databind.introspect.AnnotatedClass;
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
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
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

    private static final String KOTLIN_PLACEMENT =
        " In Kotlin, write the annotation with @get: or with no use-site target.";

    private static final String COLLISION_FIX =
        "Give each Java member its own property name, or correct the definition the message names.";

    private static final String CARRIES = ": carries ";

    /** The three question annotations, in the order problems name them. */
    private enum Kind {
        PROPOSITION(PropositionQuestion.class, question -> ((PropositionQuestion) question).asking()),
        CHOICE(ChoiceQuestion.class, question -> ((ChoiceQuestion) question).asking()),
        RATING(RatingQuestion.class, question -> ((RatingQuestion) question).asking());

        final Class<? extends Annotation> annotation;

        final Function<Annotation, String> asking;

        Kind(Class<? extends Annotation> annotation, Function<Annotation, String> asking) {
            this.annotation = annotation;
            this.asking = asking;
        }

        /**
         * Returns the annotation's name as it appears in a problem message.
         *
         * @return the simple name with a leading {@code @}
         */
        String label() {
            return "@" + annotation.getSimpleName();
        }
    }

    private final Class<?> type;

    private final ObjectMapper mapper;

    private final DeserializationConfig config;

    private final AnnotationIntrospector introspector;

    private final List<String> problems = new ArrayList<>();

    private final List<Throwable> causes = new ArrayList<>();

    private final Map<String, String> questionNames = new LinkedHashMap<>();

    private final Map<String, AnnotatedDecision.Binding> bindings = new LinkedHashMap<>();

    private final List<Question<?>> questions = new ArrayList<>();

    private final List<String> settableNames = new ArrayList<>();

    private final Coverage coverage = new Coverage();

    // Internal names of properties already reported as ignored.
    private final Set<String> reportedIgnored = new HashSet<>();

    private final Map<String, BeanPropertyDefinition> byInternalName = new LinkedHashMap<>();

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
        return new AnnotatedDecision<>(type, parsed.spec, parsed.questionNames, parsed.settableNames, parser.bindings, mapper);
    }

    /**
     * What the parser reads from a type.
     *
     * @param spec the decision spec
     * @param questionNames each Java member name mapped to its question name
     * @param settableNames the names projection can set
     */
    private record Parsed(DecisionSpec spec, Map<String, String> questionNames, List<String> settableNames) {
    }

    /**
     * Introspects the type under Jackson, checks every property and reports any problems found.
     *
     * @return the spec, the question names and the settable names read from the type
     */
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

        for (BeanPropertyDefinition property : properties) {
            readProperty(property, naming, ignorals);
        }

        scanForOrphans(ignorals, classInfo, naming);

        if (problems.isEmpty() && questions.isEmpty()) {
            problem(type.getSimpleName() + ": declares no questions. "
                + "Annotate at least one property with @PropositionQuestion, @ChoiceQuestion or @RatingQuestion.");
        }
        if (!problems.isEmpty()) {
            throw failure();
        }
        return new Parsed(specOf(questions), questionNames, settableNames);
    }

    /**
     * Builds the spec, as a classification spec when the type's only question is a choice.
     * Properties that aren't questions never reach the spec, so they don't change its type.
     * The classification spec keeps the question's name and equals the plain spec, so projection
     * and the spec JSON do not change.
     *
     * @param questions the questions read, in Jackson's order
     * @return the spec
     */
    private static DecisionSpec specOf(List<Question<?>> questions) {
        if (questions.size() == 1 && questions.get(0) instanceof ChoiceQuestionSpec choice) {
            return ClassificationSpec.of(choice);
        }
        return DecisionSpec.of(questions);
    }

    /**
     * Records the members of one property and whether projection sets it, then reads its question
     * when it carries one.
     *
     * @param property the property to check
     * @param naming the mapper's accessor naming strategy
     * @param ignorals the names Jackson ignores for the type
     */
    private void readProperty(BeanPropertyDefinition property, AccessorNamingStrategy naming, Ignorals ignorals) {
        String member = type.getSimpleName() + "." + property.getInternalName();
        List<AnnotatedMember> members;
        try {
            members = membersOf(property);
        } catch (IllegalArgumentException e) {
            problem(member + ": Jackson cannot read the property \"" + property.getName() + "\" (" + messageOf(e)
                + "). " + COLLISION_FIX, e);
            return;
        }
        members.forEach(coverage::add);
        byInternalName.put(property.getInternalName(), property);
        // Projection supplies a value for each of these, from an answer or from otherProperties.
        if (property.getMutator() != null && !ignorals.ignores(property)) {
            settableNames.add(property.getName());
        }

        Map<Kind, Set<String>> declared = questionAnnotations(members);
        if (!declared.isEmpty() && holdsOneQuestion(member, property, declared, naming, ignorals)) {
            Kind kind = declared.keySet().iterator().next();
            addQuestion(member, kind, property, declared.get(kind).iterator().next());
        }
    }

    /**
     * Checks that the property carries one question annotation with one asking value, and that
     * Jackson reads it as one settable property. Reports the first problem found.
     *
     * @param member the member label used in problem messages
     * @param property the property to check
     * @param declared the question annotations found on the property's members
     * @param naming the mapper's accessor naming strategy
     * @param ignorals the names Jackson ignores for the type
     * @return true when the property holds exactly one valid question
     */
    private boolean holdsOneQuestion(
        String member, BeanPropertyDefinition property, Map<Kind, Set<String>> declared,
        AccessorNamingStrategy naming, Ignorals ignorals) {
        if (declared.size() > 1) {
            problem(member + CARRIES + labels(declared.keySet()) + ". Keep one question annotation on the property.");
            return false;
        }
        Kind kind = declared.keySet().iterator().next();
        Set<String> askings = declared.get(kind);
        if (askings.size() > 1) {
            problem(member + ": members of the property carry " + kind.label() + " with different asking values ("
                + String.join(", ", askings.stream().map(value -> "\"" + value + "\"").toList())
                + "). Use one asking value on every annotated member of the property.");
            return false;
        }
        if (ignorals.ignores(property)) {
            problem(member + CARRIES + kind.label() + IGNORED);
            reportedIgnored.add(property.getInternalName());
            return false;
        }
        List<String> merged = mergedMembers(property, naming);
        if (!merged.isEmpty()) {
            problem(member + ": Jackson merges " + joined(merged) + " into the property \"" + property.getName()
                + "\". Rename the members so they share one Java name, or give each its own property name.");
            return false;
        }
        if (property.getMutator() == null) {
            problem(member + CARRIES + kind.label() + " but Jackson has no creator parameter, setter or field "
                + "to set it. Add one of these members for the property.");
            return false;
        }
        return true;
    }

    /**
     * Checks the asking value and the property type, then builds the question.
     *
     * @param member the member label used in problem messages
     * @param kind the question annotation found on the property
     * @param property the property carrying the question
     * @param asking the instructions the model receives
     */
    private void addQuestion(String member, Kind kind, BeanPropertyDefinition property, String asking) {
        int before = problems.size();
        if (asking.isBlank()) {
            problem(member + ": " + kind.label() + " has a blank asking value. "
                + "Set asking to the instructions the model receives.");
        }
        Set<String> names = new LinkedHashSet<>();
        Set<Class<?>> scales = new LinkedHashSet<>();
        for (AnnotatedMember annotated : membersOf(property)) {
            Annotation annotation = annotated.getAnnotation(kind.annotation);
            if (annotation != null) {
                names.add(switch (annotation) {
                    case PropositionQuestion proposition -> proposition.name();
                    case ChoiceQuestion choice -> choice.name();
                    case RatingQuestion rating -> rating.name();
                    default -> throw new IllegalStateException("Unknown question annotation");
                });
                if (annotation instanceof RatingQuestion rating) scales.add(rating.levels());
            }
        }
        if (names.size() > 1 || scales.size() > 1) {
            problem(member + ": members carry different question names or levels. "
                + "Use the same annotation values on every member of the property.");
            return;
        }
        String explicitName = names.iterator().next();
        String name = explicitName.isEmpty() ? property.getName() : explicitName;
        if (name.isBlank()) {
            problem(member + ": has a blank question name. Set name to a nonblank stable id or leave it empty.");
        }
        if (bindings.containsKey(name)) {
            problem(member + ": repeats question name \"" + name + "\". Give each question a unique name.");
        }
        Class<?> levels = scales.isEmpty() ? Void.class : scales.iterator().next();
        List<Entry> entries = checkType(member, kind, property, levels);
        if (problems.size() > before) {
            return;
        }
        Question<?> question = build(member, kind, name, asking, entries);
        if (question != null) {
            questions.add(question);
            questionNames.put(property.getInternalName(), name);
            Map<String, Enum<?>> enums = new LinkedHashMap<>();
            entries.forEach(entry -> enums.put(entry.id(), entry.constant()));
            bindings.put(name, new AnnotatedDecision.Binding(property.getName(),
                property.getRawPrimaryType() == RatingResult.class, enums));
        }
    }

    /**
     * Lists the field, getter, setter and creator parameters of one property. The getters throw
     * when two members of one kind claim the property.
     *
     * @param property the property to read the members of
     * @return the property's members
     */
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

    /**
     * Adds a member to the list when it is present.
     *
     * @param members the list to add to
     * @param member the member, or null to add nothing
     */
    private static void addIfPresent(List<AnnotatedMember> members, @Nullable AnnotatedMember member) {
        if (member != null) {
            members.add(member);
        }
    }

    /**
     * Reads the question annotations declared on a property's members, through Jackson so mix-ins
     * and annotations inherited by overriding methods count.
     *
     * @param members the property's members
     * @return the asking values found for each kind, keys in {@link Kind} order and values in
     *     member order
     */
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

    /**
     * Finds Java members of the property whose own Jackson names differ. Creator parameters are
     * left out, because a creator parameter often carries a different Java name and an explicit
     * property name.
     *
     * @param property the property to check
     * @param naming the mapper's accessor naming strategy
     * @return the mismatched members' labels, or an empty list when they all agree
     */
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

    /**
     * Checks that the property's type fits its question kind, and reads its options or levels
     * when it does.
     *
     * @param member the member label used in problem messages
     * @param kind the question annotation found on the property
     * @param property the property carrying the question
     * @return the options or levels read from the type, empty for a proposition or when the type
     *     does not fit
     */
    private List<Entry> checkType(String member, Kind kind, BeanPropertyDefinition property, Class<?> levels) {
        Class<?> raw = property.getRawPrimaryType();
        if (kind == Kind.RATING && raw == RatingResult.class) {
            if (!levels.isEnum()) {
                problem(member + ": @RatingQuestion on RatingResult needs levels set to a concrete enum. "
                    + "Set levels to the enum defining the scale, lowest first.");
                return List.of();
            }
            return EnumEntries.read(member, false, levels, mapper, this::problem);
        }
        if (kind == Kind.RATING && levels != Void.class) {
            problem(member + ": enum-valued @RatingQuestion infers its levels. "
                + "Leave levels unset or declare the property as RatingResult.");
        }
        boolean supported = kind == Kind.PROPOSITION
            ? raw == boolean.class || raw == Boolean.class
            : raw.isEnum();
        if (!supported) {
            unsupportedType(member, kind, property, raw);
            return List.of();
        }
        return kind == Kind.PROPOSITION ? List.of() : EnumEntries.read(member, kind == Kind.CHOICE, raw, mapper, this::problem);
    }

    /** Reports the concrete type needed for an unsupported question property. */
    private void unsupportedType(String member, Kind kind, BeanPropertyDefinition property, Class<?> raw) {
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
    }

    /**
     * Builds the question for a property from its kind, name, asking value and entries.
     *
     * @param member the member label used in problem messages
     * @param kind the question annotation found on the property
     * @param name the question's name
     * @param asking the instructions the model receives
     * @param entries the options or levels read from the property's type
     * @return the question built, or null after reporting a problem with the annotation values
     */
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

    /**
     * Reports question annotations on members that belong to no Jackson property. Walks the
     * declared fields, methods and parameters of the type, its superclasses and every interface
     * they implement. Reflection does not copy method annotations from an interface onto the
     * implementing method, so each interface is scanned on its own.
     *
     * @param ignorals the names Jackson ignores for the type
     * @param classInfo the type's introspected class info
     * @param naming the mapper's accessor naming strategy
     */
    private void scanForOrphans(Ignorals ignorals, AnnotatedClass classInfo, AccessorNamingStrategy naming) {
        Orphans orphans = new Orphans(ignorals, naming);
        for (Class<?> current : supertypes(type)) {
            scanFields(current, classInfo, orphans);
            scanMethods(current, classInfo, orphans);
            for (Constructor<?> constructor : current.getDeclaredConstructors()) {
                if (!constructor.isSynthetic()) {
                    scanParameters(current, constructor, "constructor parameter", classInfo, orphans, null);
                }
            }
        }
    }

    /**
     * Reports orphan question annotations on a class's own declared fields.
     *
     * @param current the class whose declared fields to scan
     * @param classInfo the type's introspected class info
     * @param orphans the reporter to check each field with
     */
    private void scanFields(Class<?> current, AnnotatedClass classInfo, Orphans orphans) {
        Set<String> componentFields = recordComponentNames(current);
        for (Field field : current.getDeclaredFields()) {
            if (field.isSynthetic() || componentFields.contains(field.getName()) || coverage.covers(field)
                || KotlinAnnotationPlacement.isBackingField(current, field, byInternalName.get(field.getName()))) {
                continue;
            }
            orphans.report(current, field.getName(), field, find(classInfo.fields(), field));
        }
    }

    /**
     * Lists a record's component names, so its backing fields can be skipped in the orphan scan.
     * A record component annotation also lands on the private field, which Jackson does not use.
     * The same annotation on the accessor and the canonical constructor parameter is what counts.
     *
     * @param current the class to check
     * @return the component names, empty when the class is not a record
     */
    private static Set<String> recordComponentNames(Class<?> current) {
        Set<String> names = new HashSet<>();
        if (current.isRecord()) {
            for (RecordComponent component : current.getRecordComponents()) {
                names.add(component.getName());
            }
        }
        return names;
    }

    /**
     * Reports orphan question annotations on a class's own declared methods and their parameters.
     *
     * @param current the class whose declared methods to scan
     * @param classInfo the type's introspected class info
     * @param orphans the reporter to check each method and parameter with
     */
    private void scanMethods(Class<?> current, AnnotatedClass classInfo, Orphans orphans) {
        for (Method method : current.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            if (!coverage.covers(method)) {
                orphans.report(current, method.getName() + "()", method, find(classInfo.memberMethods(), method));
            }
            Executable copied = KotlinAnnotationPlacement.copySource(current, method);
            scanParameters(current, method, "parameter of " + method.getName() + "()", classInfo, orphans, copied);
        }
    }

    /**
     * Lists the type and its superclasses up to {@code Object}, then each interface they
     * implement, directly or through another interface. Every interface appears once.
     *
     * @param type the type to walk
     * @return the type's supertypes, superclasses first
     */
    private static List<Class<?>> supertypes(Class<?> type) {
        List<Class<?>> classes = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            classes.add(current);
        }
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>();
        for (Class<?> owner : classes) {
            pending.addAll(Arrays.asList(owner.getInterfaces()));
        }
        while (!pending.isEmpty()) {
            Class<?> next = pending.removeFirst();
            if (interfaces.add(next)) {
                pending.addAll(Arrays.asList(next.getInterfaces()));
            }
        }
        classes.addAll(interfaces);
        return classes;
    }

    /**
     * Reports orphan question annotations on the parameters of one constructor or method.
     * Parameters of a data class {@code copy()} are skipped when they carry the same question
     * annotations as the matching constructor parameter, because the constructor scan reports
     * that declaration.
     *
     * @param declaringClass the class declaring the executable
     * @param executable the constructor or method whose parameters to scan
     * @param role the parameter's role, used in problem messages
     * @param classInfo the type's introspected class info
     * @param orphans the reporter to check each parameter with
     * @param copied the data class's primary constructor when the executable is its {@code copy()},
     *     otherwise null
     */
    private void scanParameters(
        Class<?> declaringClass, Executable executable, String role, AnnotatedClass classInfo,
        Orphans orphans, @Nullable Executable copied) {
        Parameter[] parameters = executable.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            if (!coverage.covers(executable, index) && !KotlinAnnotationPlacement.repeatsCopySource(parameters[index], copied, index)) {
                orphans.report(declaringClass, parameters[index].getName() + " (" + role + ")", parameters[index],
                    parameterOf(classInfo, executable, index));
            }
        }
    }

    /**
     * Reads which question annotations a reflective element carries.
     *
     * @param element the field, method or parameter to check
     * @return the kinds found, in {@link Kind} order
     */
    private static Set<Kind> kindsOn(AnnotatedElement element) {
        Set<Kind> kinds = new LinkedHashSet<>();
        for (Kind kind : Kind.values()) {
            if (element.isAnnotationPresent(kind.annotation)) {
                kinds.add(kind);
            }
        }
        return kinds;
    }

    /** Reports question annotations on members that belong to no Jackson property. */
    private final class Orphans {

        private final Ignorals ignorals;

        private final AccessorNamingStrategy naming;

        Orphans(Ignorals ignorals, AccessorNamingStrategy naming) {
            this.ignorals = ignorals;
            this.naming = naming;
        }

        /**
         * Reports a problem when a reflective element carries a question annotation but belongs to
         * no Jackson property, is ignored, or is left out of the property Jackson built for it.
         *
         * @param declaringClass the class declaring the element
         * @param memberLabel the element's label, used in problem messages
         * @param element the field, method or parameter to check
         * @param jacksonMember the matching Jackson member, or null when Jackson does not read it
         */
        void report(Class<?> declaringClass, String memberLabel, AnnotatedElement element,
                    @Nullable AnnotatedMember jacksonMember) {
            Set<Kind> kinds = kindsOn(element);
            if (kinds.isEmpty()) {
                return;
            }
            String implicit = jacksonMember == null ? null : implicitName(jacksonMember, naming);
            boolean ignored = isIgnored(element, jacksonMember, implicit);
            if (ignored && implicit != null && reportedIgnored.contains(implicit)) {
                // The property itself was already reported as ignored.
                return;
            }
            String prefix = declaringClass.getSimpleName() + "." + memberLabel + CARRIES + labels(kinds);
            if (ignored) {
                problem(prefix + IGNORED);
                return;
            }
            // A field or method whose own name matches a property that Jackson built from other members,
            // such as a private field next to a creator parameter.
            BeanPropertyDefinition property = implicit == null ? null : byInternalName.get(implicit);
            if (property != null && (jacksonMember instanceof AnnotatedField || jacksonMember instanceof AnnotatedMethod)) {
                problem(prefix + " but Jackson leaves this " + (jacksonMember instanceof AnnotatedField ? "field" : "method")
                    + " out of the property \"" + property.getName() + "\". Move the annotation to "
                    + String.join(" or ", membersOf(property).stream().map(DecisionTypeParser::describe).toList()) + "."
                    + (KotlinAnnotationPlacement.isKotlinClass(declaringClass) ? KOTLIN_PLACEMENT : ""));
                return;
            }
            problem(prefix + NOT_A_PROPERTY);
        }

        /**
         * Checks whether Jackson ignores the element. Without a Jackson member, the check reads an
         * explicit {@code @JsonIgnore} on the element.
         *
         * @param element the field, method or parameter to check
         * @param jacksonMember the matching Jackson member, or null when Jackson does not read it
         * @param implicit the property's implicit name, or null when there is none
         * @return true when Jackson ignores the element
         */
        private boolean isIgnored(AnnotatedElement element, @Nullable AnnotatedMember jacksonMember,
                                  @Nullable String implicit) {
            if (jacksonMember == null) {
                return element.isAnnotationPresent(JsonIgnore.class) && element.getAnnotation(JsonIgnore.class).value();
            }
            return introspector.hasIgnoreMarker(config, jacksonMember) || ignorals.ignoresName(implicit);
        }
    }

    /**
     * Describes a member for use in a "move the annotation to" fix message.
     *
     * @param member the member to describe
     * @return a label naming its kind and name, such as {@code "field foo"} or {@code "getter foo()"}
     */
    private static String describe(AnnotatedMember member) {
        if (member instanceof AnnotatedParameter parameter) {
            return "creator parameter " + implicitParameterName(parameter);
        }
        if (member instanceof AnnotatedField) {
            return "field " + member.getName();
        }
        AnnotatedMethod method = (AnnotatedMethod) member;
        return (method.getParameterCount() == 0 ? "getter " : "setter ") + method.getName() + "()";
    }

    /**
     * Reads a creator parameter's Java name from the executable that declares it.
     *
     * @param parameter the parameter to name
     * @return the parameter's Java name, or its index when the executable cannot be read
     */
    private static String implicitParameterName(AnnotatedParameter parameter) {
        if (parameter.getOwner().getAnnotated() instanceof Executable executable
            && parameter.getIndex() < executable.getParameterCount()) {
            return executable.getParameters()[parameter.getIndex()].getName();
        }
        return "#" + parameter.getIndex();
    }

    /**
     * Reads Jackson's own name for a member before renaming: the annotation introspector first,
     * then the mapper's accessor naming. This is the name Jackson groups members by.
     *
     * @param member the member to name
     * @param naming the mapper's accessor naming strategy
     * @return the implicit name, or null when neither source names the member
     */
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

    /**
     * Finds the Jackson member wrapping a reflective element.
     *
     * @param members the members to search
     * @param element the reflective field, method or constructor to find
     * @param <M> the kind of member searched
     * @return the wrapping member, or null when none matches
     */
    private static <M extends AnnotatedMember> @Nullable M find(Iterable<M> members, AnnotatedElement element) {
        for (M member : members) {
            if (element.equals(member.getAnnotated())) {
                return member;
            }
        }
        return null;
    }

    /**
     * Finds the Jackson-wrapped parameter matching one parameter of a constructor, factory or
     * member method.
     *
     * @param classInfo the type's introspected class info
     * @param executable the constructor or method declaring the parameter
     * @param index the parameter's index
     * @return the wrapping parameter, or null when none matches
     */
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

    /**
     * Reads the declared Java type of a member, used to recognise a type variable Jackson resolved
     * to its bound.
     *
     * @param member the member to check
     * @return the declared type, or null when it cannot be read
     */
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

    /**
     * Labels a member for a problem message.
     *
     * @param member the member to label
     * @return the member's name, with trailing {@code ()} for a method
     */
    private static String label(AnnotatedMember member) {
        return member instanceof AnnotatedMethod ? member.getName() + "()" : member.getName();
    }

    /**
     * Joins question kind labels for a problem message.
     *
     * @param kinds the kinds to label
     * @return the labels joined with commas and a trailing "and"
     */
    private static String labels(Set<Kind> kinds) {
        return joined(kinds.stream().map(Kind::label).toList());
    }

    /**
     * Joins items for a problem message so the order does not depend on how the caller built the list.
     *
     * @param items the items to join, which must not be empty
     * @return the items joined with commas and a trailing "and"
     */
    private static String joined(List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    /**
     * Reads a throwable's message, preferring Jackson's original message over its own formatting.
     *
     * @param e the throwable to read
     * @return the message text
     */
    private static String messageOf(Throwable e) {
        return e instanceof JacksonException jackson ? jackson.getOriginalMessage() : e.getMessage();
    }

    /**
     * Records a problem found while reading the type.
     *
     * @param problem the problem message
     */
    private void problem(String problem) {
        problems.add(problem);
    }

    /**
     * Records a problem found while reading the type, along with the exception that caused it.
     *
     * @param problem the problem message
     * @param cause the exception that caused the problem
     */
    private void problem(String problem, @Nullable Throwable cause) {
        problems.add(problem);
        if (cause != null) causes.add(cause);
    }

    /**
     * Builds the exception carrying every problem found while reading the type.
     *
     * @return the exception, with the first cause as its cause and the rest suppressed
     */
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

        /**
         * Records that a member belongs to some Jackson property.
         *
         * @param member the member to record
         */
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

        /**
         * Checks whether a field belongs to some Jackson property.
         *
         * @param field the field to check
         * @return true when the field is covered
         */
        boolean covers(Field field) {
            return members.contains(field);
        }

        /**
         * Checks whether a method belongs to some Jackson property. A superclass method counts
         * when a property method overrides it, because Jackson merges the annotations of overridden
         * methods into the overriding one. An interface method of the type counts when a property
         * method has its signature. The property method may come from a superclass that does not
         * implement the interface, and Jackson still merges the two.
         *
         * @param method the method to check
         * @return true when the method is covered
         */
        boolean covers(Method method) {
            if (members.contains(method)) {
                return true;
            }
            Class<?> owner = method.getDeclaringClass();
            boolean inheritable = owner.isInterface()
                && !Modifier.isStatic(method.getModifiers()) && !Modifier.isPrivate(method.getModifiers());
            for (Method covered : methods) {
                if (covered.getName().equals(method.getName())
                    && Arrays.equals(covered.getParameterTypes(), method.getParameterTypes())
                    && (inheritable || owner.isAssignableFrom(covered.getDeclaringClass()))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Checks whether an executable's parameter belongs to some Jackson property.
         *
         * @param executable the constructor or method declaring the parameter
         * @param index the parameter's index
         * @return true when the parameter is covered
         */
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

        /**
         * Checks whether Jackson ignores a property.
         *
         * @param property the property to check
         * @return true when the property is ignored
         */
        boolean ignores(BeanPropertyDefinition property) {
            return ignoredNames.contains(property.getInternalName())
                || ignoresName(property.getName());
        }

        /**
         * Checks whether Jackson ignores a property name.
         *
         * @param name the property name to check, or null
         * @return true when the name is ignored, or excluded by an inclusion list
         */
        boolean ignoresName(@Nullable String name) {
            return name != null
                && (ignoredNames.contains(name) || typeIgnored.contains(name) || included != null && !included.contains(name));
        }
    }
}

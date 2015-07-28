/*
 * Copyright 2009-2015 Jan Ouwens
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.jqno.equalsverifier;

import static nl.jqno.equalsverifier.util.Assert.assertEquals;
import static nl.jqno.equalsverifier.util.Assert.assertFalse;
import static nl.jqno.equalsverifier.util.Assert.assertTrue;
import static nl.jqno.equalsverifier.util.Assert.fail;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.Set;

import nl.jqno.equalsverifier.FieldInspector.FieldCheck;
import nl.jqno.equalsverifier.util.ClassAccessor;
import nl.jqno.equalsverifier.util.FieldAccessor;
import nl.jqno.equalsverifier.util.FieldIterable;
import nl.jqno.equalsverifier.util.Formatter;
import nl.jqno.equalsverifier.util.ObjectAccessor;
import nl.jqno.equalsverifier.util.PrefabValues;
import nl.jqno.equalsverifier.util.annotations.NonnullAnnotationChecker;
import nl.jqno.equalsverifier.util.annotations.SupportedAnnotations;

class FieldsChecker<T> implements Checker {
    private final ClassAccessor<T> classAccessor;
    private final PrefabValues prefabValues;
    private final EnumSet<Warning> warningsToSuppress;
    private final boolean allFieldsShouldBeUsed;
    private final Set<String> allFieldsShouldBeUsedExceptions;
    private final CachedHashCodeInitializer<T> cachedHashCodeInitializer;

    public FieldsChecker(Configuration<T> config) {
        this.classAccessor = config.createClassAccessor();
        this.prefabValues = classAccessor.getPrefabValues();
        this.warningsToSuppress = config.getWarningsToSuppress();
        this.allFieldsShouldBeUsed = config.isAllFieldsShouldBeUsed();
        this.allFieldsShouldBeUsedExceptions = config.getAllFieldsShouldBeUsedExceptions();
        this.cachedHashCodeInitializer = config.getCachedHashCodeInitializer();
    }

    @Override
    public void check() {
        FieldInspector<T> inspector = new FieldInspector<T>(classAccessor);

        if (classAccessor.declaresEquals()) {
            inspector.check(new ArrayFieldCheck());
            inspector.check(new FloatAndDoubleFieldCheck());
            inspector.check(new ReflexivityFieldCheck());
        }

        if (!ignoreMutability()) {
            inspector.check(new MutableStateFieldCheck());
        }

        if (!warningsToSuppress.contains(Warning.TRANSIENT_FIELDS)) {
            inspector.check(new TransientFieldsCheck());
        }

        inspector.check(new SignificantFieldCheck());
        inspector.check(new SymmetryFieldCheck());
        inspector.check(new TransitivityFieldCheck());
    }

    private boolean ignoreMutability() {
        return warningsToSuppress.contains(Warning.NONFINAL_FIELDS) ||
                classAccessor.hasAnnotation(SupportedAnnotations.IMMUTABLE) ||
                classAccessor.hasAnnotation(SupportedAnnotations.ENTITY);
    }

    private boolean isCachedHashCodeField(FieldAccessor accessor) {
        return accessor.getFieldName().equals(cachedHashCodeInitializer.getCachedHashCodeFieldName());
    }

    private class SymmetryFieldCheck implements FieldCheck {
        @Override
        public void execute(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            checkSymmetry(referenceAccessor, changedAccessor);

            changedAccessor.changeField(prefabValues);
            checkSymmetry(referenceAccessor, changedAccessor);

            referenceAccessor.changeField(prefabValues);
            checkSymmetry(referenceAccessor, changedAccessor);
        }

        private void checkSymmetry(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            Object left = referenceAccessor.getObject();
            Object right = changedAccessor.getObject();
            assertTrue(Formatter.of("Symmetry: objects are not symmetric:\n  %%\nand\n  %%", left, right),
                    left.equals(right) == right.equals(left));
        }
    }

    private class TransitivityFieldCheck implements FieldCheck {
        @Override
        public void execute(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            Object a1 = referenceAccessor.getObject();
            Object b1 = buildB1(changedAccessor);
            Object b2 = buildB2(a1, referenceAccessor.getField());

            boolean x = a1.equals(b1);
            boolean y = b1.equals(b2);
            boolean z = a1.equals(b2);

            if (countFalses(x, y, z) == 1) {
                fail(Formatter.of("Transitivity: two of these three instances are equal to each other, so the third one should be, too:\n-  %%\n-  %%\n-  %%", a1, b1, b2));
            }
        }

        private Object buildB1(FieldAccessor accessor) {
            accessor.changeField(prefabValues);
            return accessor.getObject();
        }

        private Object buildB2(Object a1, Field referenceField) {
            Object result = ObjectAccessor.of(a1).copy();
            ObjectAccessor<?> objectAccessor = ObjectAccessor.of(result);
            objectAccessor.fieldAccessorFor(referenceField).changeField(prefabValues);
            for (Field field : FieldIterable.of(result.getClass())) {
                if (!field.equals(referenceField)) {
                    objectAccessor.fieldAccessorFor(field).changeField(prefabValues);
                }
            }
            return result;
        }

        private int countFalses(boolean... bools) {
            int result = 0;
            for (boolean b : bools) {
                if (!b) {
                    result++;
                }
            }
            return result;
        }
    }

    private class SignificantFieldCheck implements FieldCheck {
        @Override
        public void execute(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            if (isCachedHashCodeField(referenceAccessor)) {
                return;
            }

            Object reference = referenceAccessor.getObject();
            Object changed = changedAccessor.getObject();
            String fieldName = referenceAccessor.getFieldName();

            boolean equalToItself = reference.equals(changed);

            changedAccessor.changeField(prefabValues);

            boolean equalsChanged = !reference.equals(changed);
            boolean hashCodeChanged = cachedHashCodeInitializer.getInitializedHashCode(reference) != cachedHashCodeInitializer.getInitializedHashCode(changed);

            if (equalsChanged != hashCodeChanged) {
                assertFalse(Formatter.of("Significant fields: equals relies on %%, but hashCode does not.", fieldName),
                        equalsChanged);
                assertFalse(Formatter.of("Significant fields: hashCode relies on %%, but equals does not.", fieldName),
                        hashCodeChanged);
            }

            if (allFieldsShouldBeUsed && !referenceAccessor.fieldIsStatic() && !referenceAccessor.fieldIsTransient()) {
                assertTrue(Formatter.of("Significant fields: equals does not use %%", fieldName), equalToItself);

                boolean thisFieldShouldBeUsed = allFieldsShouldBeUsed && !allFieldsShouldBeUsedExceptions.contains(fieldName);
                assertTrue(Formatter.of("Significant fields: equals does not use %%.", fieldName),
                        !thisFieldShouldBeUsed || equalsChanged);
                assertTrue(Formatter.of("Significant fields: equals should not use %%, but it does.", fieldName),
                        thisFieldShouldBeUsed || !equalsChanged);
            }

            referenceAccessor.changeField(prefabValues);
        }
    }

    private class ArrayFieldCheck implements FieldCheck {
        @Override
        public void execute(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            Class<?> arrayType = referenceAccessor.getFieldType();
            if (!arrayType.isArray()) {
                return;
            }
            if (!referenceAccessor.canBeModifiedReflectively()) {
                return;
            }

            String fieldName = referenceAccessor.getFieldName();
            Object reference = referenceAccessor.getObject();
            Object changed = changedAccessor.getObject();
            replaceInnermostArrayValue(changedAccessor);

            if (arrayType.getComponentType().isArray()) {
                assertDeep(fieldName, reference, changed);
            }
            else {
                assertArray(fieldName, reference, changed);
            }
        }

        private void replaceInnermostArrayValue(FieldAccessor accessor) {
            Object newArray = arrayCopy(accessor.get());
            accessor.set(newArray);
        }

        private Object arrayCopy(Object array) {
            Class<?> componentType = array.getClass().getComponentType();
            Object result = Array.newInstance(componentType, 1);
            if (componentType.isArray()) {
                Array.set(result, 0, arrayCopy(Array.get(array, 0)));
            }
            else {
                Array.set(result, 0, Array.get(array, 0));
            }
            return result;
        }

        private void assertDeep(String fieldName, Object reference, Object changed) {
            assertEquals(Formatter.of("Multidimensional array: ==, regular equals() or Arrays.equals() used instead of Arrays.deepEquals() for field %%.", fieldName),
                    reference, changed);
            assertEquals(Formatter.of("Multidimensional array: regular hashCode() or Arrays.hashCode() used instead of Arrays.deepHashCode() for field %%.", fieldName),
                    cachedHashCodeInitializer.getInitializedHashCode(reference), cachedHashCodeInitializer.getInitializedHashCode(changed));
        }

        private void assertArray(String fieldName, Object reference, Object changed) {
            assertEquals(Formatter.of("Array: == or regular equals() used instead of Arrays.equals() for field %%.", fieldName),
                    reference, changed);
            assertEquals(Formatter.of("Array: regular hashCode() used instead of Arrays.hashCode() for field %%.", fieldName),
                    cachedHashCodeInitializer.getInitializedHashCode(reference), cachedHashCodeInitializer.getInitializedHashCode(changed));
        }
    }

    private class FloatAndDoubleFieldCheck implements FieldCheck {
        @Override
        public void execute(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            Class<?> type = referenceAccessor.getFieldType();
            if (isFloat(type)) {
                referenceAccessor.set(Float.NaN);
                changedAccessor.set(Float.NaN);
                assertEquals(Formatter.of("Float: equals doesn't use Float.compare for field %%.", referenceAccessor.getFieldName()),
                        referenceAccessor.getObject(), changedAccessor.getObject());
            }
            if (isDouble(type)) {
                referenceAccessor.set(Double.NaN);
                changedAccessor.set(Double.NaN);
                assertEquals(Formatter.of("Double: equals doesn't use Double.compare for field %%.", referenceAccessor.getFieldName()),
                        referenceAccessor.getObject(), changedAccessor.getObject());
            }
        }

        private boolean isFloat(Class<?> type) {
            return type == float.class || type == Float.class;
        }

        private boolean isDouble(Class<?> type) {
            return type == double.class || type == Double.class;
        }
    }

    private class ReflexivityFieldCheck implements FieldCheck {
        @Override
        public void execute(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            if (warningsToSuppress.contains(Warning.IDENTICAL_COPY_FOR_VERSIONED_ENTITY)) {
                return;
            }

            checkReferenceReflexivity(referenceAccessor, changedAccessor);
            checkValueReflexivity(referenceAccessor, changedAccessor);
            checkNullReflexivity(referenceAccessor, changedAccessor);
        }

        private void checkReferenceReflexivity(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            referenceAccessor.changeField(prefabValues);
            changedAccessor.changeField(prefabValues);
            checkReflexivityFor(referenceAccessor, changedAccessor);
        }

        private void checkValueReflexivity(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            Class<?> fieldType = changedAccessor.getFieldType();
            if (warningsToSuppress.contains(Warning.REFERENCE_EQUALITY)) {
                return;
            }
            if (fieldType.isPrimitive() || fieldType.isEnum() || fieldType.isArray()) {
                return;
            }
            if (changedAccessor.fieldIsStatic() && changedAccessor.fieldIsFinal()) {
                return;
            }
            ClassAccessor<?> fieldTypeAccessor = ClassAccessor.of(fieldType, prefabValues, true);
            if (fieldType.equals(Object.class) || !fieldTypeAccessor.declaresEquals()) {
                return;
            }

            Object value = changedAccessor.get();
            if (value.getClass().isSynthetic()) {
                // Sometimes not the fieldType, but its content, is synthetic.
                return;
            }

            Object copy = ObjectAccessor.of(value).copy();
            changedAccessor.set(copy);

            Formatter f = Formatter.of("Reflexivity: == used instead of .equals() on field: %%" +
                    "\nIf this is intentional, consider suppressing Warning.%%", changedAccessor.getFieldName(), Warning.REFERENCE_EQUALITY.toString());
            Object left = referenceAccessor.getObject();
            Object right = changedAccessor.getObject();
            assertEquals(f, left, right);
        }

        private void checkNullReflexivity(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            boolean fieldIsPrimitive = referenceAccessor.fieldIsPrimitive();
            boolean fieldIsNonNull = NonnullAnnotationChecker.fieldIsNonnull(classAccessor, referenceAccessor.getField());
            boolean ignoreNull = fieldIsNonNull || warningsToSuppress.contains(Warning.NULL_FIELDS);
            if (fieldIsPrimitive || !ignoreNull) {
                referenceAccessor.defaultField();
                changedAccessor.defaultField();
                checkReflexivityFor(referenceAccessor, changedAccessor);
            }
        }

        private void checkReflexivityFor(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            Object left = referenceAccessor.getObject();
            Object right = changedAccessor.getObject();

            if (warningsToSuppress.contains(Warning.IDENTICAL_COPY)) {
                assertFalse(Formatter.of("Unnecessary suppression: %%. Two identical copies are equal.", Warning.IDENTICAL_COPY.toString()),
                        left.equals(right));
            }
            else {
                Formatter f = Formatter.of("Reflexivity: object does not equal an identical copy of itself:\n  %%" +
                        "\nIf this is intentional, consider suppressing Warning.%%", left, Warning.IDENTICAL_COPY.toString());
                assertEquals(f, left, right);
            }
        }
    }

    private class MutableStateFieldCheck implements FieldCheck {
        @Override
        public void execute(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            if (isCachedHashCodeField(referenceAccessor)) {
                return;
            }

            Object reference = referenceAccessor.getObject();
            Object changed = changedAccessor.getObject();

            changedAccessor.changeField(prefabValues);

            boolean equalsChanged = !reference.equals(changed);

            if (equalsChanged && !referenceAccessor.fieldIsFinal()) {
                fail(Formatter.of("Mutability: equals depends on mutable field %%.", referenceAccessor.getFieldName()));
            }

            referenceAccessor.changeField(prefabValues);
        }
    }

    private class TransientFieldsCheck implements FieldCheck {
        @Override
        public void execute(FieldAccessor referenceAccessor, FieldAccessor changedAccessor) {
            Object reference = referenceAccessor.getObject();
            Object changed = changedAccessor.getObject();

            changedAccessor.changeField(prefabValues);

            boolean equalsChanged = !reference.equals(changed);
            boolean fieldIsTransient = referenceAccessor.fieldIsTransient() ||
                    classAccessor.fieldHasAnnotation(referenceAccessor.getField(), SupportedAnnotations.TRANSIENT);

            if (equalsChanged && fieldIsTransient) {
                fail(Formatter.of("Transient field %% should not be included in equals/hashCode contract.", referenceAccessor.getFieldName()));
            }

            referenceAccessor.changeField(prefabValues);
        }
    }
}
package com.patchable.validation;

import com.patchable.api.PatchField;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import jakarta.validation.valueextraction.ValueExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PatchFieldValueExtractor 테스트")
class PatchFieldValueExtractorTest {

    private final PatchFieldValueExtractor extractor = new PatchFieldValueExtractor();

    @Test
    @DisplayName("Value 인 경우 내부 값을 추출한다")
    void should_extract_value_from_Value() {
        var holder = new TestReceiver();

        extractor.extractValues(PatchField.of("hello"), holder);

        assertTrue(holder.called);
        assertEquals("hello", holder.extractedValue);
    }

    @Test
    @DisplayName("Delete 인 경우 값을 추출하지 않는다")
    void should_not_extract_from_Delete() {
        var holder = new TestReceiver();

        extractor.extractValues(PatchField.delete(), holder);

        assertFalse(holder.called);
    }

    @Test
    @DisplayName("Unset 인 경우 값을 추출하지 않는다")
    void should_not_extract_from_Unset() {
        var holder = new TestReceiver();

        extractor.extractValues(PatchField.unset(), holder);

        assertFalse(holder.called);
    }

    @Nested
    @DisplayName("Hibernate Validator 통합 테스트")
    class IntegrationTest {

        private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        record SampleDto(
                @Size(min = 2, max = 10) PatchField<String> name,
                @Email PatchField<String> email
        ) {}

        @Test
        @DisplayName("Value 가 제약을 위반하면 violation 이 발생한다")
        void should_violate_when_value_breaks_constraint() {
            var dto = new SampleDto(PatchField.of("a"), PatchField.of("valid@test.com"));

            Set<ConstraintViolation<SampleDto>> violations = validator.validate(dto);

            assertEquals(1, violations.size());
            assertEquals("name", violations.iterator().next().getPropertyPath().toString());
        }

        @Test
        @DisplayName("Value 가 제약을 만족하면 violation 이 없다")
        void should_pass_when_value_satisfies_constraint() {
            var dto = new SampleDto(PatchField.of("hello"), PatchField.of("valid@test.com"));

            Set<ConstraintViolation<SampleDto>> violations = validator.validate(dto);

            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("Delete 이면 검증을 건너뛴다")
        void should_skip_validation_for_delete() {
            var dto = new SampleDto(PatchField.delete(), PatchField.delete());

            Set<ConstraintViolation<SampleDto>> violations = validator.validate(dto);

            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("Unset 이면 검증을 건너뛴다")
        void should_skip_validation_for_unset() {
            var dto = new SampleDto(PatchField.unset(), PatchField.unset());

            Set<ConstraintViolation<SampleDto>> violations = validator.validate(dto);

            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("잘못된 이메일 형식이면 violation 이 발생한다")
        void should_violate_on_invalid_email() {
            var dto = new SampleDto(PatchField.of("hello"), PatchField.of("not-an-email"));

            Set<ConstraintViolation<SampleDto>> violations = validator.validate(dto);

            assertEquals(1, violations.size());
            assertEquals("email", violations.iterator().next().getPropertyPath().toString());
        }
    }

    private static class TestReceiver implements ValueExtractor.ValueReceiver {
        boolean called = false;
        Object extractedValue;

        @Override
        public void value(String nodeName, Object object) {
            this.called = true;
            this.extractedValue = object;
        }

        @Override
        public void iterableValue(String nodeName, Object object) {}

        @Override
        public void indexedValue(String nodeName, int i, Object object) {}

        @Override
        public void keyedValue(String nodeName, Object key, Object object) {}
    }
}

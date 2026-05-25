package com.patchable.processor;

import com.patchable.api.PatchField;
import com.patchable.api.PatchOf;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SupportedAnnotationTypes("com.patchable.api.PatchOf")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class PatchOfProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(PatchOf.class)) {
            if (!(element instanceof TypeElement dtoElement)) continue;
            processPatchOf(dtoElement);
        }
        return true;
    }

    private void processPatchOf(TypeElement dtoElement) {
        TypeMirror entityType = getEntityType(dtoElement);
        if (entityType == null) return;

        TypeElement entityElement = (TypeElement) processingEnv.getTypeUtils().asElement(entityType);
        if (entityElement == null) {
            error(dtoElement, "Cannot resolve target entity for @PatchOf");
            return;
        }

        String methodName = dtoElement.getAnnotation(PatchOf.class).method();

        List<DtoField> dtoFields = extractDtoFields(dtoElement);
        if (dtoFields.isEmpty()) return;

        ExecutableElement matchedMethod = findMatchingMethod(entityElement, methodName, dtoFields, dtoElement);
        if (matchedMethod == null) return;

        generatePatcher(dtoElement, entityElement, matchedMethod, dtoFields);
    }

    private TypeMirror getEntityType(TypeElement dtoElement) {
        try {
            PatchOf annotation = dtoElement.getAnnotation(PatchOf.class);
            annotation.value();
            return null;
        } catch (MirroredTypeException e) {
            return e.getTypeMirror();
        }
    }

    private static final Set<String> PRESENCE_ANNOTATIONS = Set.of(
            "jakarta.validation.constraints.NotNull",
            "jakarta.validation.constraints.NotBlank",
            "jakarta.validation.constraints.NotEmpty",
            "javax.validation.constraints.NotNull",
            "javax.validation.constraints.NotBlank",
            "javax.validation.constraints.NotEmpty"
    );

    private List<DtoField> extractDtoFields(TypeElement dtoElement) {
        List<DtoField> fields = new ArrayList<>();
        for (RecordComponentElement component : dtoElement.getRecordComponents()) {
            String name = component.getSimpleName().toString();
            TypeMirror type = component.asType();
            boolean isPatchField = isPatchFieldType(type);
            TypeMirror underlyingType = isPatchField ? extractPatchFieldInnerType(type) : type;

            if (isPatchField) {
                validateNoPresenceAnnotations(component);
            }

            fields.add(new DtoField(name, type, underlyingType, isPatchField));
        }
        return fields;
    }

    private void validateNoPresenceAnnotations(RecordComponentElement component) {
        // Jakarta Validation annotations don't target RECORD_COMPONENT,
        // so they propagate to the accessor method instead.
        for (AnnotationMirror am : component.getAccessor().getAnnotationMirrors()) {
            String annotationName = am.getAnnotationType().toString();
            if (PRESENCE_ANNOTATIONS.contains(annotationName)) {
                error(component,
                        "@%s cannot be used on PatchField. "
                        + "PatchField represents an optional PATCH operation — "
                        + "presence constraints are incompatible with PATCH semantics.",
                        am.getAnnotationType().asElement().getSimpleName());
            }
        }
    }

    private boolean isPatchFieldType(TypeMirror type) {
        if (type instanceof DeclaredType declaredType) {
            String typeName = declaredType.asElement().toString();
            return typeName.equals(PatchField.class.getCanonicalName());
        }
        return false;
    }

    private TypeMirror extractPatchFieldInnerType(TypeMirror patchFieldType) {
        if (patchFieldType instanceof DeclaredType declaredType) {
            List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                return typeArgs.get(0);
            }
        }
        return patchFieldType;
    }

    private ExecutableElement findMatchingMethod(TypeElement entityElement, String methodName,
                                                   List<DtoField> dtoFields, TypeElement dtoElement) {
        List<ExecutableElement> candidates = new ArrayList<>();

        for (Element enclosed : entityElement.getEnclosedElements()) {
            if (!(enclosed instanceof ExecutableElement method)) continue;
            if (!method.getSimpleName().toString().equals(methodName)) continue;

            List<? extends VariableElement> params = method.getParameters();

            if (hasSyntheticParamNames(params)) {
                error(dtoElement,
                        "Parameter names of '%s' in %s are synthetic (arg0, arg1, ...). " +
                        "Compile the entity with -parameters flag or ensure it is in the same source tree.",
                        methodName, entityElement.getSimpleName());
                return null;
            }

            if (params.size() != dtoFields.size()) continue;

            if (allDtoFieldsMatchParams(dtoFields, params)) {
                candidates.add(method);
            }
        }

        if (candidates.isEmpty()) {
            StringBuilder fieldNames = new StringBuilder();
            for (DtoField f : dtoFields) {
                if (!fieldNames.isEmpty()) fieldNames.append(", ");
                fieldNames.append(f.name());
            }
            error(dtoElement, "No method '%s' in %s matching fields [%s] of %s",
                    methodName, entityElement.getSimpleName(), fieldNames, dtoElement.getSimpleName());
            return null;
        }

        return candidates.get(0);
    }

    private boolean hasSyntheticParamNames(List<? extends VariableElement> params) {
        if (params.isEmpty()) return false;
        return params.get(0).getSimpleName().toString().matches("arg\\d+");
    }

    private boolean allDtoFieldsMatchParams(List<DtoField> dtoFields, List<? extends VariableElement> params) {
        Map<String, TypeMirror> paramMap = new HashMap<>();
        for (VariableElement param : params) {
            paramMap.put(param.getSimpleName().toString(), param.asType());
        }

        for (DtoField field : dtoFields) {
            TypeMirror paramType = paramMap.get(field.name());
            if (paramType == null) return false;
            if (!isTypeCompatible(field.underlyingType(), paramType)) {
                return false;
            }
        }
        return true;
    }

    private boolean isTypeCompatible(TypeMirror dtoType, TypeMirror paramType) {
        if (processingEnv.getTypeUtils().isSameType(dtoType, paramType)) {
            return true;
        }
        TypeMirror boxedDto = boxIfPrimitive(dtoType);
        TypeMirror boxedParam = boxIfPrimitive(paramType);
        return processingEnv.getTypeUtils().isSameType(boxedDto, boxedParam);
    }

    private TypeMirror boxIfPrimitive(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return processingEnv.getTypeUtils().boxedClass(
                    (javax.lang.model.type.PrimitiveType) type
            ).asType();
        }
        return type;
    }

    private void generatePatcher(TypeElement dtoElement, TypeElement entityElement,
                                  ExecutableElement method, List<DtoField> dtoFields) {
        String dtoClassName = dtoElement.getSimpleName().toString();
        String patcherClassName = dtoClassName + "Patcher";

        String dtoPackage = processingEnv.getElementUtils().getPackageOf(dtoElement).getQualifiedName().toString();
        String entityQualified = entityElement.getQualifiedName().toString();
        String dtoQualified = dtoElement.getQualifiedName().toString();
        String methodName = method.getSimpleName().toString();

        List<? extends VariableElement> methodParams = method.getParameters();

        Map<String, DtoField> dtoFieldMap = new HashMap<>();
        for (DtoField f : dtoFields) {
            dtoFieldMap.put(f.name(), f);
        }

        StringBuilder body = new StringBuilder();
        body.append("        target.").append(methodName).append("(\n");

        for (int i = 0; i < methodParams.size(); i++) {
            VariableElement param = methodParams.get(i);
            String paramName = param.getSimpleName().toString();
            DtoField dtoField = dtoFieldMap.get(paramName);

            if (i > 0) body.append(",\n");

            if (dtoField != null && dtoField.isPatchField()) {
                body.append("                resolve(source.").append(paramName)
                        .append("(), target.get").append(capitalize(paramName)).append("())");
            } else if (dtoField != null) {
                body.append("                source.").append(paramName).append("() != null ? source.")
                        .append(paramName).append("() : target.get").append(capitalize(paramName)).append("()");
            } else {
                body.append("                target.get").append(capitalize(paramName)).append("()");
            }
        }

        body.append("\n        );\n");

        boolean hasPatchField = dtoFields.stream().anyMatch(DtoField::isPatchField);

        String resolveMethod = hasPatchField ? """

                    @SuppressWarnings("unchecked")
                    private static <T> T resolve(com.patchable.api.PatchField<T> field, T current) {
                        if (field instanceof com.patchable.api.PatchField.Value<?> v) {
                            return (T) v.value();
                        } else if (field instanceof com.patchable.api.PatchField.Delete<?>) {
                            return null;
                        }
                        return current;
                    }
                """ : "";

        String source = """
                package %s;

                import org.springframework.stereotype.Component;

                @Component
                public class %s {

                    public void apply(%s target, %s source) {
                %s    }%s
                }
                """.formatted(
                dtoPackage,
                patcherClassName,
                entityQualified, dtoQualified,
                body,
                resolveMethod
        );

        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(dtoPackage + "." + patcherClassName, dtoElement);
            try (PrintWriter writer = new PrintWriter(file.openWriter())) {
                writer.print(source);
            }
        } catch (IOException e) {
            error(dtoElement, "Failed to generate patcher: %s", e.getMessage());
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void error(Element element, String message, Object... args) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format(message, args), element);
    }

    record DtoField(String name, TypeMirror originalType, TypeMirror underlyingType, boolean isPatchField) {}
}

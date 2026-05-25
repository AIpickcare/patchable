package com.patchable.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.Compiler.javac;
import static com.google.testing.compile.CompilationSubject.assertThat;

@DisplayName("PatchOfProcessor 어노테이션 프로세서 테스트")
class PatchOfProcessorTest {

    @Test
    @DisplayName("정상 DTO 에 대해 Patcher 클래스가 생성된다")
    void should_generate_patcher_for_valid_dto() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private String name;
                    private String bio;
                    public String getName() { return name; }
                    public String getBio() { return bio; }
                    public void updateMember(String name, String bio) {
                        this.name = name;
                        this.bio = bio;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                import com.patchable.api.PatchField;
                @PatchOf(value = Member.class, method = "updateMember")
                public record MemberPatch(
                    String name,
                    PatchField<String> bio
                ) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("com.test.MemberPatchPatcher");
    }

    @Test
    @DisplayName("method 에 지정된 메서드가 Entity 에 없으면 컴파일 에러")
    void should_error_when_method_not_found() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private String name;
                    public String getName() { return name; }
                    public void updateMember(String name) {
                        this.name = name;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                @PatchOf(value = Member.class, method = "nonExistentMethod")
                public record MemberPatch(String name) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("No method 'nonExistentMethod'");
    }

    @Test
    @DisplayName("DTO 필드 이름이 메서드 파라미터와 다르면 컴파일 에러")
    void should_error_when_field_names_dont_match() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private String name;
                    public String getName() { return name; }
                    public void updateMember(String name) {
                        this.name = name;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                @PatchOf(value = Member.class, method = "updateMember")
                public record MemberPatch(String displayName) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("No method 'updateMember'");
    }

    @Test
    @DisplayName("DTO 필드 수와 메서드 파라미터 수가 다르면 컴파일 에러")
    void should_error_when_field_count_differs() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private String name;
                    private String bio;
                    public String getName() { return name; }
                    public String getBio() { return bio; }
                    public void updateMember(String name, String bio) {
                        this.name = name;
                        this.bio = bio;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                @PatchOf(value = Member.class, method = "updateMember")
                public record MemberPatch(String name) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).failed();
    }

    @Test
    @DisplayName("오버로딩된 메서드 중 파라미터가 일치하는 것을 선택한다")
    void should_match_correct_overload() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private String name;
                    private String bio;
                    public String getName() { return name; }
                    public String getBio() { return bio; }
                    public void update(String name) {
                        this.name = name;
                    }
                    public void update(String name, String bio) {
                        this.name = name;
                        this.bio = bio;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                @PatchOf(value = Member.class, method = "update")
                public record MemberPatch(String name, String bio) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("com.test.MemberPatchPatcher");
    }

    @Test
    @DisplayName("PatchField 와 일반 타입이 섞여도 정상 생성된다")
    void should_handle_mixed_patchfield_and_plain() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private String name;
                    private String bio;
                    private String address;
                    public String getName() { return name; }
                    public String getBio() { return bio; }
                    public String getAddress() { return address; }
                    public void updateMember(String name, String bio, String address) {
                        this.name = name;
                        this.bio = bio;
                        this.address = address;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                import com.patchable.api.PatchField;
                @PatchOf(value = Member.class, method = "updateMember")
                public record MemberPatch(
                    String name,
                    PatchField<String> bio,
                    PatchField<String> address
                ) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("com.test.MemberPatchPatcher");
    }

    @Test
    @DisplayName("생성된 코드가 도메인 메서드를 호출하고, plain 필드는 null 폴백, PatchField 는 resolve 를 사용한다")
    void generated_code_should_have_correct_content() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private String name;
                    private String bio;
                    public String getName() { return name; }
                    public String getBio() { return bio; }
                    public void updateMember(String name, String bio) {
                        this.name = name;
                        this.bio = bio;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                import com.patchable.api.PatchField;
                @PatchOf(value = Member.class, method = "updateMember")
                public record MemberPatch(
                    String name,
                    PatchField<String> bio
                ) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("com.test.MemberPatchPatcher")
                .contentsAsUtf8String()
                .contains("target.updateMember(");
        assertThat(compilation).generatedSourceFile("com.test.MemberPatchPatcher")
                .contentsAsUtf8String()
                .contains("source.name() != null ? source.name() : target.getName()");
        assertThat(compilation).generatedSourceFile("com.test.MemberPatchPatcher")
                .contentsAsUtf8String()
                .contains("resolve(source.bio(), target.getBio())");
        assertThat(compilation).generatedSourceFile("com.test.MemberPatchPatcher")
                .contentsAsUtf8String()
                .contains("private static <T> T resolve(");
    }

    @Test
    @DisplayName("모든 필드가 plain 이면 resolve 메서드가 생성되지 않는다")
    void all_plain_fields_should_not_generate_resolve() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private String name;
                    private String email;
                    public String getName() { return name; }
                    public String getEmail() { return email; }
                    public void updateMember(String name, String email) {
                        this.name = name;
                        this.email = email;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                @PatchOf(value = Member.class, method = "updateMember")
                public record MemberPatch(String name, String email) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("com.test.MemberPatchPatcher")
                .contentsAsUtf8String()
                .doesNotContain("resolve(");
    }

    @Test
    @DisplayName("모든 필드가 PatchField 면 모두 resolve 로 처리된다")
    void all_patchfield_should_use_resolve_for_all() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private String bio;
                    private String address;
                    public String getBio() { return bio; }
                    public String getAddress() { return address; }
                    public void updateMember(String bio, String address) {
                        this.bio = bio;
                        this.address = address;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                import com.patchable.api.PatchField;
                @PatchOf(value = Member.class, method = "updateMember")
                public record MemberPatch(
                    PatchField<String> bio,
                    PatchField<String> address
                ) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("com.test.MemberPatchPatcher")
                .contentsAsUtf8String()
                .contains("resolve(source.bio(), target.getBio())");
        assertThat(compilation).generatedSourceFile("com.test.MemberPatchPatcher")
                .contentsAsUtf8String()
                .contains("resolve(source.address(), target.getAddress())");
        assertThat(compilation).generatedSourceFile("com.test.MemberPatchPatcher")
                .contentsAsUtf8String()
                .doesNotContain("!= null ?");
    }

    @Test
    @DisplayName("PatchField 에 @NotNull 을 붙이면 컴파일 에러")
    void should_error_when_notNull_on_patchField() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private String name;
                    public String getName() { return name; }
                    public void updateMember(String name) {
                        this.name = name;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                import com.patchable.api.PatchField;
                import jakarta.validation.constraints.NotNull;
                @PatchOf(value = Member.class, method = "updateMember")
                public record MemberPatch(
                    @NotNull PatchField<String> name
                ) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@NotNull cannot be used on PatchField");
    }

    @Test
    @DisplayName("PatchField 에 @NotBlank 을 붙이면 컴파일 에러")
    void should_error_when_notBlank_on_patchField() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private String name;
                    public String getName() { return name; }
                    public void updateMember(String name) {
                        this.name = name;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                import com.patchable.api.PatchField;
                import jakarta.validation.constraints.NotBlank;
                @PatchOf(value = Member.class, method = "updateMember")
                public record MemberPatch(
                    @NotBlank PatchField<String> name
                ) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@NotBlank cannot be used on PatchField");
    }

    @Test
    @DisplayName("PatchField 에 @Size 같은 값 제약 어노테이션은 허용된다")
    void should_allow_value_constraints_on_patchField() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private String name;
                    public String getName() { return name; }
                    public void updateMember(String name) {
                        this.name = name;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                import com.patchable.api.PatchField;
                import jakarta.validation.constraints.Size;
                @PatchOf(value = Member.class, method = "updateMember")
                public record MemberPatch(
                    @Size(min = 1, max = 100) PatchField<String> name
                ) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).succeeded();
    }

    @Test
    @DisplayName("DTO 필드 타입과 메서드 파라미터 타입이 다르면 컴파일 에러")
    void should_error_on_type_mismatch() {
        JavaFileObject entity = JavaFileObjects.forSourceString("com.test.Member", """
                package com.test;
                public class Member {
                    private int age;
                    public int getAge() { return age; }
                    public void updateMember(int age) {
                        this.age = age;
                    }
                }
                """);

        JavaFileObject dto = JavaFileObjects.forSourceString("com.test.MemberPatch", """
                package com.test;
                import com.patchable.api.PatchOf;
                @PatchOf(value = Member.class, method = "updateMember")
                public record MemberPatch(String age) {}
                """);

        Compilation compilation = javac()
                .withProcessors(new PatchOfProcessor())
                .compile(entity, dto);

        assertThat(compilation).failed();
    }
}

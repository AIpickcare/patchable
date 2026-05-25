# Patchable

[![](https://jitpack.io/v/dudxor4587/patchable.svg)](https://jitpack.io/#dudxor4587/patchable)

[English](README.en.md)

**도메인 메서드를 보존하면서 PATCH 보일러플레이트를 없애는 Java 라이브러리.**

컴파일 타임에 Patcher 를 자동 생성하고, 도메인 메서드를 직접 호출합니다.

## 왜 만들었나

Spring Boot 에서 PATCH API 를 작성하면 매번 이런 코드가 반복됩니다:

```java
member.updateMember(
    request.name() != null ? request.name() : member.getName(),
    request.email() != null ? request.email() : member.getEmail(),
    request.nickname() != null ? request.nickname() : member.getNickname(),
    request.phoneNumber() != null ? request.phoneNumber() : member.getPhoneNumber(),
    request.address() != null ? request.address() : member.getAddress(),
    request.bio() != null ? request.bio() : member.getBio()
);
```

기존 해법들 (MapStruct, BeanUtils, JsonNullable 등) 은 각자 한 가지씩 양보를 요구합니다:
- MapStruct / BeanUtils → **기본 동작이 setter** (도메인 메서드 호출은 추가 설정 필요)
- JsonNullable → **DTO 전체 wrapping + 보일러플레이트 잔존**
- JSON Patch → **클라이언트가 명령 배열 작성**

Patchable 은 **도메인 메서드 호출 + 3 상태 표현 + 보일러플레이트 제거**를 동시에 달성합니다.

## 핵심 기능

### 1. 도메인 메서드 호출

생성된 patcher 가 setter 가 아니라 사용자가 작성한 도메인 메서드 (`updateMember` 등) 를 호출합니다. 도메인 불변식 (검증, 비즈니스 규칙) 이 PATCH 에서도 그대로 동작합니다.

### 2. PatchField — 3 상태 표현

```java
public sealed interface PatchField<T> {
    record Unset<T>()        implements PatchField<T> {}  // 미지정 → skip
    record Value<T>(T value) implements PatchField<T> {}  // 값 설정 → update
    record Delete<T>()       implements PatchField<T> {}  // 비우기 → delete
}
```

JSON 의 세 상태를 정확히 표현합니다:
- 키 없음 → `Unset` (skip)
- `"bio": "값"` → `Value` (update)
- `"bio": null` → `Delete` (delete)

### 3. 도메인 nullability 기반 wrapping

```java
@PatchOf(value = Member.class, method = "updateMember")
public record MemberProfilePatch(
    String name,                          // plain — null 이면 기존 값 유지 (skip)
    PatchField<String> bio                // 3 상태 — Unset(skip) / Value(update) / Delete(delete)
) {}
```

필수 필드 (NOT NULL) 는 평범한 타입, 옵셔널 필드 (Nullable) 만 `PatchField` 로 감쌉니다.

### 4. Bean Validation 지원

`spring-boot-starter-validation` 이 클래스패스에 있으면, `PatchField` 필드에도 Bean Validation 어노테이션이 동작합니다.

```java
@PatchOf(value = Member.class, method = "updateMember")
public record MemberProfilePatch(
    @Size(min = 2, max = 50) String name,
    @Size(max = 200) @Email PatchField<String> email,
    @Size(max = 500) PatchField<String> bio
) {}
```

- `Value` 일 때만 내부 값을 검증합니다 (`@Size`, `@Email`, `@Pattern`, `@Min` 등)
- `Delete` / `Unset` 이면 검증을 건너뜁니다 — 값이 없으므로 검증할 대상이 없습니다

**컴파일 에러:** `PatchField` 에 존재 여부를 검사하는 어노테이션 (`@NotNull`, `@NotBlank`, `@NotEmpty`) 을 붙이면 컴파일 에러가 발생합니다. PATCH 에서는 "필드를 안 보냄 = 변경 안 함" 이므로 존재 제약은 의미가 맞지 않습니다.

## 동작 원리

1. `@PatchOf(value = Member.class, method = "updateMember")` 가 붙은 DTO 를 컴파일 타임에 발견
2. `method` 에 지정된 이름으로 Entity 의 도메인 메서드를 찾고, DTO 필드 이름/타입으로 오버로딩을 구분
3. 매칭된 메서드를 호출하는 Patcher 클래스 (`@Component`) 를 자동 생성
4. 생성된 Patcher 를 Spring DI 로 주입해서 사용

**생성 규칙:**
- 생성되는 Patcher 클래스 이름: `{DTO 이름}Patcher` (예: `MemberProfilePatch` → `MemberProfilePatchPatcher`)
- 메서드: `public void apply(Entity target, DTO source)`

**제약:**
- Entity 에 JavaBean 스타일 getter (`getXxx()`) 가 필요합니다 (Lombok `@Getter` 또는 직접 작성)
- DTO 필드 이름이 도메인 메서드 파라미터 이름과 일치해야 합니다 (Entity 가 같은 프로젝트에 있거나, 외부 jar 라면 `-parameters` 플래그로 컴파일 필요)
- 이름이 다른 경우 (예: API 명세와 도메인 명명이 다를 때) presentation 레이어의 converter 에서 매핑 후 PatchDTO 를 만들어 넘기면 됩니다
- 타입 변환은 라이브러리가 하지 않습니다 — 변환이 필요하면 converter 에서 처리 후 넘기세요 (단, int ↔ Integer 같은 boxing/unboxing 은 자동 처리)
- **DTO 필드 집합과 동일한 시그니처의 도메인 메서드가 Entity 에 있어야 합니다** — 이것은 의도된 설계입니다. 도메인 메서드는 비즈니스 의도를 표현해야 하며, "모든 필드를 다 받는" 범용 메서드 하나로 모든 PATCH 를 처리하면 setter 집합과 다를 게 없어집니다. `updateProfile(name, bio)` 와 `updateContact(email, phone)` 처럼 의도별로 메서드를 분리하도록 유도합니다

## 사용법

### 1. 의존성 추가

**Gradle (Groovy)**
```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.dudxor4587:patchable:v0.1.0'
    annotationProcessor 'com.github.dudxor4587:patchable:v0.1.0'
}
```

**Gradle (Kotlin DSL)**
```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.dudxor4587:patchable:v0.1.0")
    annotationProcessor("com.github.dudxor4587:patchable:v0.1.0")
}
```

**Maven**
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.dudxor4587</groupId>
        <artifactId>patchable</artifactId>
        <version>v0.1.0</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.github.dudxor4587</groupId>
                        <artifactId>patchable</artifactId>
                        <version>v0.1.0</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### 2. DTO 작성

```java
@PatchOf(value = Member.class, method = "updateMember")
public record MemberProfilePatch(
    String name,
    String email,
    PatchField<String> bio
) {}
```

### 3. 생성된 Patcher 사용

```java
@Service
@RequiredArgsConstructor
public class MemberService {
    private final MemberProfilePatchPatcher patcher;

    @Transactional
    public Member patch(Long id, MemberProfilePatch request) {
        Member member = repository.findById(id).orElseThrow();
        patcher.apply(member, request);
        return member;
    }
}
```

## 기존 도구와 비교

| 항목 | MapStruct | JsonNullable | Patchable |
|------|-----------|-------------|-----------|
| 기본 호출 대상 | setter (기본). expression 으로 도메인 메서드 호출 가능 | 사용자가 직접 호출 | **도메인 메서드 (자동)** |
| 도메인 불변식 | 기본 setter 사용 시 우회됨 | 사용자 책임 | **도메인 메서드가 검증 — 라이브러리는 전달만** |
| 3 상태 지원 | ❌ | ✅ (필드별 opt-in) | **✅ (필드별 opt-in)** |
| Entity setter 필요 | 기본 동작에 필요 | 불필요 | **불필요** |
| 보일러플레이트 | 매퍼 인터페이스 필요 | 서비스에서 분기 코드 필요 | **`@PatchOf` 한 줄** |

> **참고:** "도메인 불변식 보존" 은 라이브러리가 검증을 수행한다는 뜻이 아닙니다. 라이브러리는 값을 도메인 메서드에 전달만 하고, 검증은 도메인 메서드 내부의 로직이 수행합니다. 도메인 메서드를 우회하지 않기 때문에 검증이 동작하는 것입니다.

## 요구사항

- Java 17+
- Spring Boot 3.x+
- Jackson 2.x+

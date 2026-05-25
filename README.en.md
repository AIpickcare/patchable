# Patchable

[![](https://jitpack.io/v/dudxor4587/patchable.svg)](https://jitpack.io/#dudxor4587/patchable)

[한국어](README.md)

**A Java library that eliminates PATCH boilerplate while preserving domain methods.**

Auto-generates patchers at compile time, calling domain methods directly.

## The Problem

Writing PATCH APIs in Spring Boot means repeating this pattern for every entity:

```java
member.updateMember(
    request.name() != null ? request.name() : member.getName(),
    request.email() != null ? request.email() : member.getEmail(),
    // ... repeated for every field
);
```

Existing solutions each force a compromise:
- MapStruct / BeanUtils → **default behavior uses setters** (domain method invocation requires extra config)
- JsonNullable → **wraps all DTO fields + boilerplate remains**
- JSON Patch → **clients must send operation arrays**

Patchable achieves **domain method invocation + 3-state support + zero boilerplate** simultaneously.

## Key Features

### 1. Domain Method Invocation

Generated patchers call your domain methods (e.g., `updateMember()`), not individual setters. Domain invariants — validation, business rules, state transitions — are preserved in PATCH operations.

### 2. PatchField — 3-State Representation

```java
public sealed interface PatchField<T> {
    record Unset<T>()        implements PatchField<T> {}  // not provided → skip
    record Value<T>(T value) implements PatchField<T> {}  // value provided → update
    record Delete<T>()       implements PatchField<T> {}  // explicit null → delete
}
```

Accurately maps JSON's three states:
- Key absent → `Unset` (skip)
- `"bio": "value"` → `Value` (update)
- `"bio": null` → `Delete` (delete)

### 3. Nullability-Driven Wrapping

```java
@PatchOf(value = Member.class, method = "updateMember")
public record MemberProfilePatch(
    String name,                          // plain — null means keep current value (skip)
    PatchField<String> bio                // 3-state — Unset(skip) / Value(update) / Delete(delete)
) {}
```

Required (NOT NULL) fields use plain types. Optional (nullable) fields use `PatchField`. The wrapping decision mirrors your domain model's nullability.

## How It Works

1. Discovers DTOs annotated with `@PatchOf(value = Member.class, method = "updateMember")` at compile time
2. Finds the entity's domain method by `method` name, resolves overloads by DTO field names and types
3. Generates a Patcher class (`@Component`) that calls the matched domain method
4. Inject the generated Patcher via Spring DI

**Naming convention:**
- Generated Patcher class: `{DTO name}Patcher` (e.g., `MemberProfilePatch` → `MemberProfilePatchPatcher`)
- Method: `public void apply(Entity target, DTO source)`

**Constraints:**
- Entity must have JavaBean-style getters (`getXxx()`) — Lombok `@Getter` or hand-written
- DTO field names must match domain method parameter names (entity must be in the same source tree, or compiled with `-parameters` flag if in an external jar)
- If names differ (e.g., API naming vs domain naming), map them in a presentation-layer converter and pass the resulting PatchDTO to the patcher
- The library does not perform type conversion — handle conversions in your converter before passing to the patcher (boxing/unboxing like int ↔ Integer is handled automatically)
- **The entity must have a domain method whose parameter set exactly matches the DTO's field set** — e.g., if entity has `updateMember(name, email, bio)` but DTO has only `(name, bio)`, matching fails. Create a dedicated domain method for partial field updates

## Usage

### 1. Add Dependency

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

### 2. Define Patch DTO

```java
@PatchOf(value = Member.class, method = "updateMember")
public record MemberProfilePatch(
    String name,
    String email,
    PatchField<String> bio
) {}
```

### 3. Use Generated Patcher

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

## Comparison

| Feature | MapStruct | JsonNullable | Patchable |
|---------|-----------|-------------|-----------|
| Default invocation | setters (can call domain methods via expression) | user calls manually | **domain methods (auto)** |
| Domain invariants | bypassed with default setters | user's responsibility | **domain method validates — library only passes values** |
| 3-state support | ❌ | ✅ (per-field opt-in) | **✅ (per-field opt-in)** |
| Requires setters on entity | for default behavior | no | **no** |
| Boilerplate | mapper interface required | branch code in service | **`@PatchOf` one line** |

> **Note:** "Domain invariants preserved" means the library does not bypass domain methods — it delegates to them. Validation is performed by your domain method, not by the library. If invalid values are passed, the domain method throws as it normally would.

## Requirements

- Java 17+
- Spring Boot 3.x+
- Jackson 2.x+

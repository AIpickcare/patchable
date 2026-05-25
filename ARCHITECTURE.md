# Architecture

## 프로젝트 구조

```
com.patchable/
├── api/                  ← 사용자에게 노출되는 API
│   ├── PatchField.java       sealed interface (Unset / Value / Delete)
│   └── PatchOf.java          @PatchOf 어노테이션
├── jackson/              ← Jackson 통합
│   ├── PatchFieldDeserializer.java   JSON → PatchField 변환
│   ├── PatchFieldModule.java         Jackson Module 등록
│   └── PatchFieldAutoConfiguration.java   Spring Boot 자동 설정
└── processor/            ← 어노테이션 프로세서
    └── PatchOfProcessor.java         컴파일 타임 코드 생성
```

## Annotation Processor 동작 흐름

`PatchOfProcessor` 가 컴파일 타임에 수행하는 단계:

### 1. DTO 발견

`@PatchOf` 가 붙은 record 를 찾는다.

```java
@PatchOf(value = Member.class, method = "updateMember")
public record MemberProfilePatch(
    String name,
    PatchField<String> bio
) {}
```

### 2. 필드 추출

`dtoElement.getRecordComponents()` 로 record 컴포넌트만 정확히 추출한다. derived 메서드 오탐 없음. 각 필드에 대해:

- `PatchField<T>` 타입인지 확인
- 맞으면 inner type `T` 를 추출 (제네릭 타입 인자)
- 아니면 원래 타입 그대로

### 3. Entity 획득

`@PatchOf(Member.class)` 의 `value()` 에서 Entity 의 `TypeMirror` 를 얻는다. 어노테이션 프로세서에서 클래스 리터럴에 접근할 때 `MirroredTypeException` 을 활용하는 패턴을 사용한다.

### 4. 메서드 매칭

`@PatchOf` 의 `method` 에 지정된 이름으로 Entity 의 메서드를 찾고, DTO 필드와 파라미터를 비교한다.

매칭 조건:
- 메서드 이름이 `method` 값과 일치
- 메서드의 파라미터 이름 = DTO 의 필드 이름 (정확히 같은 집합)
- 파라미터 타입이 DTO 필드의 underlying type 과 일치 (`PatchField<String>` 이면 `String` 으로 비교, boxing/unboxing 자동 처리)

매칭 결과:
- 1개 → 그 메서드 사용
- 0개 → 컴파일 에러: `No method 'xxx' in Entity matching fields [...]`

> Java 에서 같은 이름 + 같은 파라미터 집합의 오버로딩은 불가능하므로, `method` 이름 + 필드 집합이 정해지면 매칭은 항상 0 또는 1.

### 5. Patcher 클래스 생성

`{DTO 이름}Patcher` 클래스를 생성한다. `@Component` 가 붙어 Spring 빈으로 등록된다.

생성되는 `apply()` 메서드 내부 로직:

- **평범한 필드** (`String`): `source.name() != null ? source.name() : target.getName()`
- **PatchField 필드**: `resolve(source.bio(), target.getBio())` — 아래 resolve 메서드로 위임

### 6. resolve 메서드

PatchField 가 하나라도 있으면 resolve helper 메서드가 생성된다:

```java
private static <T> T resolve(PatchField<T> field, T current) {
    if (field instanceof PatchField.Value<?> v) {
        return (T) v.value();    // update
    } else if (field instanceof PatchField.Delete<?>) {
        return null;              // delete
    }
    return current;               // skip (Unset)
}
```

`instanceof` 패턴 매칭 사용 (Java 17 호환).

## Jackson Deserializer 동작

`PatchFieldDeserializer` 가 JSON 의 세 상태를 `PatchField` 변형으로 매핑:

| JSON 상태 | 호출되는 메서드 | 결과 |
|----------|-------------|------|
| 키 자체가 없음 | `getAbsentValue()` | `PatchField.Unset` |
| `"field": null` | `getNullValue()` | `PatchField.Delete` |
| `"field": "값"` | `deserialize()` | `PatchField.Value("값")` |

`ContextualDeserializer` 를 구현하여 `PatchField<T>` 의 제네릭 타입 `T` 를 런타임에 결정한다.

## 설계 결정 기록

| 결정 | 선택 | 이유 |
|------|------|------|
| 호출 대상 | 도메인 메서드 (setter X) | 도메인 불변식 보존 |
| 메서드 발견 | `method` 명시 + 파라미터 집합 매칭 | 명시적, 오탐 방지 |
| 3 상태 타입 | sealed interface | 컴파일러가 빠뜨림 방지, Java 17+ |
| DI 방식 | @Component + 생성자 주입 | 테스트 용이, Spring 관용 |
| Java 최소 | 17 | sealed + record + instanceof 패턴 매칭 |
| Jackson 호환 | 2.x | Spring Boot 3.x 전체 호환 |
| 이름 매핑 | 미지원 (converter 위임) | 라이브러리 scope 명확화 |
| 타입 변환 | 미지원 (converter 위임) | 변환은 비즈니스 로직 영역 |

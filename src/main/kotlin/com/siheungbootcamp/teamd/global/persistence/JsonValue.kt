package com.siheungbootcamp.teamd.global.persistence

import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/** Hibernate 6 JSONB 필드에서 재사용할 매핑 예시다. 도메인 타입은 이 애노테이션 조합을 적용한다. */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@JdbcTypeCode(SqlTypes.JSON)
annotation class JsonValue

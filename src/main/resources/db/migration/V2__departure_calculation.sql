-- 개인 출발 안내 계산 결과를 저장한다.
-- NOT_REQUESTED 상태는 행 없음으로 표현하며, 나머지 상태(CALCULATING/READY/STALE/UNAVAILABLE/FAILED)를 저장한다.
-- Job Executor가 CALCULATING 행을 폴링하므로 status 인덱스를 만든다.
create table if not exists departure_calculation (
    id bigserial primary key,
    participant_id bigint not null,
    course_id bigint not null,
    status text not null,
    total_seconds int,
    transfer_count int,
    fare_amount int,
    total_walk_seconds int,
    recommended_departure_at timestamptz,
    calculated_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    foreign key (participant_id) references participant(id),
    foreign key (course_id) references course(id),
    unique (participant_id, course_id)
);

-- Job Executor 폴링용: CALCULATING 행을 빠르게 찾기 위한 인덱스
create index on departure_calculation (status);

alter table board add column selected_place_id bigint references place(id);
alter table board add column selected_by_participant_id bigint references participant(id);
alter table board add column selected_at timestamptz;

create table place_like (
    place_id bigint not null references place(id),
    participant_id bigint not null references participant(id),
    created_at timestamptz not null default now(),
    primary key (place_id, participant_id)
);

create index idx_place_like_participant on place_like(participant_id);

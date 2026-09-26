create table if not exists agent_process_snapshots (
    process_id   varchar(255)        not null,
    parent_id    varchar(255),
    agent_name   varchar(255)        not null,
    status       varchar(64)         not null,
    version      bigint              not null,
    content_type varchar(255)        not null,
    payload      bytea               not null,
    created_at   timestamp           not null,
    updated_at   timestamp           not null,
    constraint pk_agent_process_snapshots primary key (process_id)
);

create index if not exists idx_agent_process_snapshots_parent_id
    on agent_process_snapshots (parent_id);

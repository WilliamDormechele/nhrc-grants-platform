create table if not exists personal_register_views (
 id uuid primary key,user_id uuid not null references users(id),dataset text not null,name varchar(80) not null,settings jsonb not null,updated_at timestamptz not null default now(),unique(user_id,dataset,name));
create table if not exists record_discussions (
 id uuid primary key,dataset text not null,record_id uuid not null,author_id uuid not null references users(id),body varchar(8000) not null,created_at timestamptz not null default now(),resolved_at timestamptz);
create index if not exists record_discussions_record_idx on record_discussions(dataset,record_id,created_at);

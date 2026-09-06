\set ON_ERROR_STOP on

begin;
set constraints all deferred;
-- The source database already enforces its complete graph.  A few historical
-- record tables retain non-deferrable cycles (pathway instance/task/variance),
-- so this transaction alone suppresses target-side triggers while the complete
-- source graph is inserted.  The setting is LOCAL and resets at commit/rollback.
set local session_replication_role = replica;

set local haoemr.sync_target_database to :'target_database';

do $guard$
begin
  if current_database() <> current_setting('haoemr.sync_target_database') then
    raise exception 'Refusing to run against %, expected %', current_database(), current_setting('haoemr.sync_target_database');
  end if;
  if exists (select 1 from pg_foreign_server where srvname = 'haoemr_legacy_sync_source')
     or exists (select 1 from pg_namespace where nspname = 'haoemr_legacy_sync_source') then
    raise exception 'A previous HaoEMR legacy-sync mapping remains; inspect and remove it before retrying';
  end if;
end
$guard$;

create extension if not exists postgres_fdw;
create server haoemr_legacy_sync_source
  foreign data wrapper postgres_fdw
  options (host '127.0.0.1', port '55432', dbname :'source_database');
create user mapping for current_user
  server haoemr_legacy_sync_source
  options (user :'source_user');
create schema haoemr_legacy_sync_source;
import foreign schema public
  from server haoemr_legacy_sync_source
  into haoemr_legacy_sync_source;

create temporary table haoemr_legacy_sync_order (
  table_name text primary key,
  dependency_depth integer not null
) on commit drop;

-- Select all descendants of patient through declared foreign keys.  An
-- iterative breadth-first walk avoids generating an exponential number of
-- paths in the large clinical dependency graph.
insert into haoemr_legacy_sync_order(table_name, dependency_depth)
values ('patient', 0);

do $graph$
declare
  discovered integer;
begin
  loop
    insert into haoemr_legacy_sync_order(table_name, dependency_depth)
    select child.relname::text,
           min(parent.dependency_depth) + 1
    from haoemr_legacy_sync_order parent
    join pg_catalog.pg_class referenced
      on referenced.relname::text = parent.table_name
    join pg_catalog.pg_namespace referenced_schema
      on referenced_schema.oid = referenced.relnamespace
     and referenced_schema.nspname = 'public'
    join pg_catalog.pg_constraint relation
      on relation.confrelid = referenced.oid
     and relation.contype = 'f'
    join pg_catalog.pg_class child
      on child.oid = relation.conrelid
    join pg_catalog.pg_namespace child_schema
      on child_schema.oid = child.relnamespace
     and child_schema.nspname = 'public'
    join information_schema.foreign_tables source_table
      on source_table.foreign_table_schema = 'haoemr_legacy_sync_source'
     and source_table.foreign_table_name = child.relname
    where not exists (
      select 1 from haoemr_legacy_sync_order known
      where known.table_name = child.relname::text
    )
    group by child.relname
    on conflict (table_name) do nothing;
    get diagnostics discovered = row_count;
    exit when discovered = 0;
  end loop;
end
$graph$;

create temporary table haoemr_legacy_sync_result (
  table_name text primary key,
  rows_inserted bigint not null
) on commit drop;

do $sync$
declare
  item record;
  column_list text;
  inserted_count bigint;
begin
  for item in
    select table_name
    from haoemr_legacy_sync_order
    order by dependency_depth, table_name
  loop
    select string_agg(format('%I', target_column.column_name), ', ' order by target_column.ordinal_position)
      into column_list
    from information_schema.columns target_column
    join information_schema.columns source_column
      on source_column.table_schema = 'haoemr_legacy_sync_source'
     and source_column.table_name = target_column.table_name
     and source_column.column_name = target_column.column_name
    where target_column.table_schema = 'public'
      and target_column.table_name = item.table_name
      and target_column.is_generated = 'NEVER';

    if column_list is null then
      raise exception 'No compatible columns found for %', item.table_name;
    end if;

    execute format(
      'insert into public.%1$I (%2$s) select %2$s from haoemr_legacy_sync_source.%1$I on conflict do nothing',
      item.table_name, column_list);
    get diagnostics inserted_count = row_count;
    insert into haoemr_legacy_sync_result(table_name, rows_inserted)
      values (item.table_name, inserted_count);
  end loop;
end
$sync$;

select table_name, rows_inserted
from haoemr_legacy_sync_result
where rows_inserted > 0
order by table_name;

-- Only objects created by this transaction are removed.  There is no CASCADE.
do $cleanup$
declare
  item record;
begin
  for item in
    select foreign_table_name
    from information_schema.foreign_tables
    where foreign_table_schema = 'haoemr_legacy_sync_source'
  loop
    execute format('drop foreign table haoemr_legacy_sync_source.%I', item.foreign_table_name);
  end loop;
end
$cleanup$;
drop schema haoemr_legacy_sync_source;
drop user mapping for current_user server haoemr_legacy_sync_source;
drop server haoemr_legacy_sync_source;

commit;

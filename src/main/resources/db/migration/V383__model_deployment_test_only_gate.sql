alter table model_deployment
  add column test_only boolean not null default false;

comment on column model_deployment.test_only is
  'Development-synthetic connection-test deployment; excluded from publish and clinical runtime selection.';

update model_deployment
set test_only = true
where provider_code = 'DEEPSEEK';

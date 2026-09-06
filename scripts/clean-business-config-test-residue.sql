\set ON_ERROR_STOP on

begin;

do $$
begin
  if current_database() <> 'haoemr_dev' then
    raise exception 'This cleanup may only run against haoemr_dev';
  end if;
end $$;

create temporary table cleanup_schedule_slot on commit drop as
select tenant_id, schedule_slot_id
from schedule_slot
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
  and slot_date > current_date + interval '1 year';

create temporary table cleanup_appointment on commit drop as
select appointment.tenant_id, appointment.appointment_id
from appointment
join cleanup_schedule_slot target
  on target.tenant_id = appointment.tenant_id
 and target.schedule_slot_id = appointment.schedule_slot_id;

delete from waiting_queue_entry queue
using cleanup_appointment target
where queue.tenant_id = target.tenant_id
  and queue.appointment_id = target.appointment_id;

alter table appointment_event disable trigger appointment_event_immutable;
delete from appointment_event event
using cleanup_appointment target
where event.tenant_id = target.tenant_id
  and event.appointment_id = target.appointment_id;
alter table appointment_event enable trigger appointment_event_immutable;

delete from appointment appointment
using cleanup_appointment target
where appointment.tenant_id = target.tenant_id
  and appointment.appointment_id = target.appointment_id;

delete from schedule_slot slot
using cleanup_schedule_slot target
where slot.tenant_id = target.tenant_id
  and slot.schedule_slot_id = target.schedule_slot_id;

create temporary table cleanup_capability_pack on commit drop as
select tenant_id, capability_pack_id
from capability_pack
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
  and (pack_code ~ '^PACK-[0-9a-f]{8}$'
    or pack_code ~ '^PKG-[0-9a-f]{8}-[0-9a-f]{3}$');

delete from capability_pack_release release
using cleanup_capability_pack target
where release.tenant_id = target.tenant_id
  and release.capability_pack_id = target.capability_pack_id;

delete from capability_pack pack
using cleanup_capability_pack target
where pack.tenant_id = target.tenant_id
  and pack.capability_pack_id = target.capability_pack_id;

create temporary table cleanup_config_item on commit drop as
select tenant_id, config_id
from config_item
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
  and config_type in ('WORKFLOW', 'FORM_TEMPLATE', 'RULE', 'SCOPE', 'CAPABILITY_PACK_COMPOSITION')
  and (
    config_key ~* '(^|[-_])(test|demo|tmp|invalid)([-_]|$)'
    or display_name ~ '(测试数据|无效数据|临时数据|仅供测试)'
  );

delete from config_item_revision revision
using cleanup_config_item target
where revision.tenant_id = target.tenant_id
  and revision.config_id = target.config_id;

delete from config_item item
using cleanup_config_item target
where item.tenant_id = target.tenant_id
  and item.config_id = target.config_id;

create temporary table cleanup_specialty_department on commit drop as
select tenant_id, facility_id, department_id
from clinical_department
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
  and department_code ~ '^SYN-[0-9a-f]{8}$'
  and display_name = '合成专科';

create temporary table cleanup_specialty_pack on commit drop as
select tenant_id, specialty_pack_release_id
from specialty_pack_release
where tenant_id = '018f0000-0000-7000-8000-00000000aa01'::uuid
  and pack_code ~ '^PACK-[0-9a-f]{8}$';

delete from department_support_assessment assessment
using cleanup_specialty_department department
where assessment.tenant_id = department.tenant_id
  and assessment.facility_id = department.facility_id
  and assessment.department_id = department.department_id;

delete from department_support_assessment assessment
using cleanup_specialty_pack pack
where assessment.tenant_id = pack.tenant_id
  and assessment.pack_release_id = pack.specialty_pack_release_id;

delete from specialty_pack_release release
using cleanup_specialty_pack target
where release.tenant_id = target.tenant_id
  and release.specialty_pack_release_id = target.specialty_pack_release_id;

delete from clinical_department department
using cleanup_specialty_department target
where department.tenant_id = target.tenant_id
  and department.facility_id = target.facility_id
  and department.department_id = target.department_id;

commit;

#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd "$script_dir/.." && pwd)
pg_bin="${HAOEMR_PG_BIN:-/usr/local/opt/postgresql@18/bin}"
pg_socket="${HAOEMR_PG_SOCKET:-/private/tmp}"
pg_port="${HAOEMR_PG_PORT:-55432}"
pg_database="${HAOEMR_PG_DATABASE:-haoemr_dev}"
migration_v1="$project_dir/src/main/resources/db/migration/V1__clinical_core.sql"
migration_v2="$project_dir/src/main/resources/db/migration/V2__context_lease.sql"
migration_v3="$project_dir/src/main/resources/db/migration/V3__signed_document_integrity.sql"
migration_v4="$project_dir/src/main/resources/db/migration/V4__ai_run_and_proposal.sql"
migration_v5="$project_dir/src/main/resources/db/migration/V5__outbox_dispatch.sql"
migration_v6="$project_dir/src/main/resources/db/migration/V6__specialty_support.sql"
migration_v7="$project_dir/src/main/resources/db/migration/V7__inpatient_admission.sql"
migration_v8="$project_dir/src/main/resources/db/migration/V8__inpatient_document_task_link.sql"
migration_v9="$project_dir/src/main/resources/db/migration/V9__inpatient_transfer.sql"
migration_v10="$project_dir/src/main/resources/db/migration/V10__inpatient_discharge.sql"
migration_v11="$project_dir/src/main/resources/db/migration/V11__inpatient_document_rules.sql"
migration_v12="$project_dir/src/main/resources/db/migration/V12__document_signature_policy.sql"
migration_v13="$project_dir/src/main/resources/db/migration/V13__inpatient_clinical_events.sql"
migration_v14="$project_dir/src/main/resources/db/migration/V14__order_execution_core.sql"
migration_v15="$project_dir/src/main/resources/db/migration/V15__order_stop_and_cancel.sql"
migration_v16="$project_dir/src/main/resources/db/migration/V16__versioned_clinical_diagnosis.sql"
migration_v17="$project_dir/src/main/resources/db/migration/V17__clinical_result_and_critical_value.sql"
migration_v18="$project_dir/src/main/resources/db/migration/V18__medication_safety_rules.sql"
migration_v19="$project_dir/src/main/resources/db/migration/V19__unified_clinical_task.sql"
migration_v20="$project_dir/src/main/resources/db/migration/V20__clinical_task_collaboration.sql"
migration_v21="$project_dir/src/main/resources/db/migration/V21__document_quality_run.sql"
migration_v22="$project_dir/src/main/resources/db/migration/V22__archive_seal_export.sql"
migration_v23="$project_dir/src/main/resources/db/migration/V23__organization_and_workforce_identity.sql"
migration_v24="$project_dir/src/main/resources/db/migration/V24__workforce_account_lifecycle.sql"
migration_v25="$project_dir/src/main/resources/db/migration/V25__fix_actor_snapshot_trigger.sql"
migration_v26="$project_dir/src/main/resources/db/migration/V26__attribute_authorization_and_emergency_access.sql"
migration_v27="$project_dir/src/main/resources/db/migration/V27__authorization_policy_lifecycle_constraints.sql"
migration_v28="$project_dir/src/main/resources/db/migration/V28__audited_emergency_access_expiry.sql"
migration_v29="$project_dir/src/main/resources/db/migration/V29__mpi_patient_identity_workflow.sql"
migration_v30="$project_dir/src/main/resources/db/migration/V30__document_template_lifecycle.sql"
migration_v31="$project_dir/src/main/resources/db/migration/V31__document_attachment_and_source_evidence.sql"
migration_v32="$project_dir/src/main/resources/db/migration/V32__normalize_legacy_template_uuids.sql"
migration_v33="$project_dir/src/main/resources/db/migration/V33__document_correction_and_signature_revocation.sql"
migration_v34="$project_dir/src/main/resources/db/migration/V34__inpatient_consultation_workflow.sql"
migration_v35="$project_dir/src/main/resources/db/migration/V35__harden_inpatient_consultation_evidence.sql"
migration_v36="$project_dir/src/main/resources/db/migration/V36__inpatient_clinical_pathway.sql"
migration_v37="$project_dir/src/main/resources/db/migration/V37__encounter_state_machine.sql"
migration_v38="$project_dir/src/main/resources/db/migration/V38__medication_drug_interaction.sql"
migration_v39="$project_dir/src/main/resources/db/migration/V39__restricted_medication_authorization.sql"
migration_v40="$project_dir/src/main/resources/db/migration/V40__appointment_scheduling_core.sql"
migration_v41="$project_dir/src/main/resources/db/migration/V41__waiting_queue.sql"
migration_v42="$project_dir/src/main/resources/db/migration/V42__appointment_encounter_link.sql"
migration_v43="$project_dir/src/main/resources/db/migration/V43__nursing_vital_signs.sql"
migration_v44="$project_dir/src/main/resources/db/migration/V44__nursing_care_plan.sql"
migration_v45="$project_dir/src/main/resources/db/migration/V45__medication_administration.sql"
migration_v46="$project_dir/src/main/resources/db/migration/V46__shift_handover.sql"
migration_v47="$project_dir/src/main/resources/db/migration/V47__price_catalog_and_charge.sql"
migration_v48="$project_dir/src/main/resources/db/migration/V48__lab_specimen.sql"
migration_v49="$project_dir/src/main/resources/db/migration/V49__adverse_event.sql"
migration_v50="$project_dir/src/main/resources/db/migration/V50__blood_transfusion.sql"
migration_v51="$project_dir/src/main/resources/db/migration/V51__dictionary_master_data.sql"
migration_v52="$project_dir/src/main/resources/db/migration/V52__model_deployment_catalog.sql"
migration_v53="$project_dir/src/main/resources/db/migration/V53__research_dataset_request.sql"
migration_v54="$project_dir/src/main/resources/db/migration/V54__fix_research_dataset_export_check.sql"
migration_v55="$project_dir/src/main/resources/db/migration/V55__obstetric_record.sql"
migration_v56="$project_dir/src/main/resources/db/migration/V56__clinical_reminder.sql"
migration_v57="$project_dir/src/main/resources/db/migration/V57__art_cycle_record.sql"
migration_v58="$project_dir/src/main/resources/db/migration/V58__pediatric_record.sql"
migration_v59="$project_dir/src/main/resources/db/migration/V59__neonatal_record.sql"
migration_v60="$project_dir/src/main/resources/db/migration/V60__mental_health_record.sql"
migration_v61="$project_dir/src/main/resources/db/migration/V61__ophthalmology_record.sql"
migration_v62="$project_dir/src/main/resources/db/migration/V62__ent_record.sql"
migration_v63="$project_dir/src/main/resources/db/migration/V63__dental_record.sql"
migration_v64="$project_dir/src/main/resources/db/migration/V64__dermatology_record.sql"
migration_v65="$project_dir/src/main/resources/db/migration/V65__tcm_record.sql"
migration_v66="$project_dir/src/main/resources/db/migration/V66__pediatric_weight_based_dose.sql"
migration_v67="$project_dir/src/main/resources/db/migration/V67__hepatic_renal_dose_contraindication.sql"
migration_v68="$project_dir/src/main/resources/db/migration/V68__emergency_triage.sql"
migration_v69="$project_dir/src/main/resources/db/migration/V69__shift_handover_patient_list.sql"
migration_v70="$project_dir/src/main/resources/db/migration/V70__emergency_observation.sql"
migration_v71="$project_dir/src/main/resources/db/migration/V71__imaging_order.sql"
migration_v72="$project_dir/src/main/resources/db/migration/V72__pharmacy_dispensing.sql"
migration_v73="$project_dir/src/main/resources/db/migration/V73__surgical_procedure.sql"
migration_v74="$project_dir/src/main/resources/db/migration/V74__infection_monitoring.sql"
migration_v75="$project_dir/src/main/resources/db/migration/V75__referral.sql"
migration_v76="$project_dir/src/main/resources/db/migration/V76__prompt_release.sql"
migration_v77="$project_dir/src/main/resources/db/migration/V77__data_quality_rule.sql"
migration_v78="$project_dir/src/main/resources/db/migration/V78__dictation_note.sql"
migration_v79="$project_dir/src/main/resources/db/migration/V79__action_approval.sql"
migration_v80="$project_dir/src/main/resources/db/migration/V80__agent_registry.sql"
migration_v81="$project_dir/src/main/resources/db/migration/V81__research_cohort.sql"
migration_v82="$project_dir/src/main/resources/db/migration/V82__capability_pack.sql"
migration_v83="$project_dir/src/main/resources/db/migration/V83__emergency_resuscitation.sql"
migration_v84="$project_dir/src/main/resources/db/migration/V84__skill_registry.sql"
migration_v85="$project_dir/src/main/resources/db/migration/V85__tool_registry.sql"
migration_v86="$project_dir/src/main/resources/db/migration/V86__nursing_discharge_closure.sql"
migration_v87="$project_dir/src/main/resources/db/migration/V87__model_evaluation.sql"
migration_v88="$project_dir/src/main/resources/db/migration/V88__agent_run_budget.sql"
migration_v89="$project_dir/src/main/resources/db/migration/V89__emergency_nursing_note.sql"
migration_v90="$project_dir/src/main/resources/db/migration/V90__emergency_preadmission.sql"
migration_v91="$project_dir/src/main/resources/db/migration/V91__nursing_bedside_note.sql"
migration_v92="$project_dir/src/main/resources/db/migration/V92__clinical_task_notification.sql"
migration_v93="$project_dir/src/main/resources/db/migration/V93__data_quality_evaluation.sql"
migration_v94="$project_dir/src/main/resources/db/migration/V94__agent_dependency.sql"
migration_v95="$project_dir/src/main/resources/db/migration/V95__research_cohort_snapshot.sql"
migration_v96="$project_dir/src/main/resources/db/migration/V96__encounter_domain_switch.sql"
migration_v97="$project_dir/src/main/resources/db/migration/V97__medical_record_asset.sql"
migration_v98="$project_dir/src/main/resources/db/migration/V98__obstetric_delivery_record.sql"
migration_v99="$project_dir/src/main/resources/db/migration/V99__mental_health_crisis_handover.sql"
migration_v100="$project_dir/src/main/resources/db/migration/V100__dental_treatment_record.sql"
migration_v101="$project_dir/src/main/resources/db/migration/V101__neonatal_wristband_verification.sql"
migration_v102="$project_dir/src/main/resources/db/migration/V102__art_embryo_transfer_record.sql"
migration_v103="$project_dir/src/main/resources/db/migration/V103__pediatric_growth_record.sql"
migration_v104="$project_dir/src/main/resources/db/migration/V104__ophthalmology_preop_verification.sql"
migration_v105="$project_dir/src/main/resources/db/migration/V105__ent_airway_risk_handover.sql"
migration_v106="$project_dir/src/main/resources/db/migration/V106__dermatology_biologic_screening.sql"
migration_v107="$project_dir/src/main/resources/db/migration/V107__tcm_herbal_prescription.sql"
migration_v108="$project_dir/src/main/resources/db/migration/V108__clinical_task_ward.sql"
migration_v109="$project_dir/src/main/resources/db/migration/V109__pediatric_followup_record.sql"
migration_v110="$project_dir/src/main/resources/db/migration/V110__release_metric_snapshot.sql"
migration_v111="$project_dir/src/main/resources/db/migration/V111__obstetric_antenatal_exam.sql"
migration_v112="$project_dir/src/main/resources/db/migration/V112__obstetric_qc_review.sql"
migration_v113="$project_dir/src/main/resources/db/migration/V113__obstetric_postpartum_followup.sql"
migration_v114="$project_dir/src/main/resources/db/migration/V114__art_pregnancy_outcome.sql"
migration_v115="$project_dir/src/main/resources/db/migration/V115__historical_migration_batch.sql"
migration_v116="$project_dir/src/main/resources/db/migration/V116__tcm_four_examinations.sql"
migration_v117="$project_dir/src/main/resources/db/migration/V117__neonatal_screening_record.sql"
migration_v118="$project_dir/src/main/resources/db/migration/V118__dermatology_biologic_followup.sql"
migration_v119="$project_dir/src/main/resources/db/migration/V119__mental_health_crisis_followup.sql"
migration_v120="$project_dir/src/main/resources/db/migration/V120__ophthalmology_postop_followup.sql"
migration_v121="$project_dir/src/main/resources/db/migration/V121__capability_pack_release.sql"
migration_v122="$project_dir/src/main/resources/db/migration/V122__clinical_task_team_queue.sql"
migration_v123="$project_dir/src/main/resources/db/migration/V123__release_download_event.sql"
migration_v124="$project_dir/src/main/resources/db/migration/V124__action_execution.sql"
migration_v125="$project_dir/src/main/resources/db/migration/V125__clinical_task_notification_schedule.sql"
migration_v126="$project_dir/src/main/resources/db/migration/V126__source_system_inventory.sql"
migration_v127="$project_dir/src/main/resources/db/migration/V127__research_cohort_member.sql"
migration_v128="$project_dir/src/main/resources/db/migration/V128__clinical_reminder_conversion.sql"
migration_v129="$project_dir/src/main/resources/db/migration/V129__historical_migration_checkpoint.sql"
migration_v130="$project_dir/src/main/resources/db/migration/V130__source_field_mapping.sql"
migration_v131="$project_dir/src/main/resources/db/migration/V131__source_patient_match_candidate.sql"
migration_v132="$project_dir/src/main/resources/db/migration/V132__agent_run_budget_consumption.sql"
migration_v133="$project_dir/src/main/resources/db/migration/V133__tcm_qc_review.sql"
migration_v134="$project_dir/src/main/resources/db/migration/V134__reproductive_qc_review.sql"
migration_v135="$project_dir/src/main/resources/db/migration/V135__pediatric_qc_review.sql"
migration_v136="$project_dir/src/main/resources/db/migration/V136__neonatal_qc_review.sql"
migration_v137="$project_dir/src/main/resources/db/migration/V137__mental_health_qc_review.sql"
migration_v138="$project_dir/src/main/resources/db/migration/V138__ophthalmology_qc_review.sql"
migration_v139="$project_dir/src/main/resources/db/migration/V139__ent_qc_review.sql"
migration_v140="$project_dir/src/main/resources/db/migration/V140__dental_qc_review.sql"
migration_v141="$project_dir/src/main/resources/db/migration/V141__dermatology_qc_review.sql"
migration_v142="$project_dir/src/main/resources/db/migration/V142__neonatal_followup_record.sql"
migration_v143="$project_dir/src/main/resources/db/migration/V143__ent_followup_record.sql"
migration_v144="$project_dir/src/main/resources/db/migration/V144__dental_followup_record.sql"
migration_v145="$project_dir/src/main/resources/db/migration/V145__tcm_followup_record.sql"
migration_v146="$project_dir/src/main/resources/db/migration/V146__obstetric_care_note.sql"
migration_v147="$project_dir/src/main/resources/db/migration/V147__reproductive_care_note.sql"
migration_v148="$project_dir/src/main/resources/db/migration/V148__ophthalmology_care_note.sql"
migration_v149="$project_dir/src/main/resources/db/migration/V149__dental_care_note.sql"
migration_v150="$project_dir/src/main/resources/db/migration/V150__dermatology_care_note.sql"
migration_v151="$project_dir/src/main/resources/db/migration/V151__tcm_care_note.sql"
migration_v152="$project_dir/src/main/resources/db/migration/V152__reproductive_evidence_record.sql"
migration_v153="$project_dir/src/main/resources/db/migration/V153__pediatric_evidence_record.sql"
migration_v154="$project_dir/src/main/resources/db/migration/V154__mental_health_evidence_record.sql"
migration_v155="$project_dir/src/main/resources/db/migration/V155__ophthalmology_evidence_record.sql"
migration_v156="$project_dir/src/main/resources/db/migration/V156__ent_evidence_record.sql"
migration_v157="$project_dir/src/main/resources/db/migration/V157__dental_evidence_record.sql"
migration_v158="$project_dir/src/main/resources/db/migration/V158__dermatology_evidence_record.sql"
migration_v159="$project_dir/src/main/resources/db/migration/V159__pediatric_treatment_record.sql"
migration_v160="$project_dir/src/main/resources/db/migration/V160__neonatal_treatment_record.sql"
migration_v161="$project_dir/src/main/resources/db/migration/V161__mental_health_treatment_record.sql"
migration_v162="$project_dir/src/main/resources/db/migration/V162__ent_treatment_record.sql"
migration_v163="$project_dir/src/main/resources/db/migration/V163__config_item.sql"
migration_v164="$project_dir/src/main/resources/db/migration/V164__outpatient_followup.sql"
migration_v165="$project_dir/src/main/resources/db/migration/V165__metric_snapshot.sql"
migration_v166="$project_dir/src/main/resources/db/migration/V166__configuration_lifecycle.sql"
migration_v167="$project_dir/src/main/resources/db/migration/V167__development_user_session.sql"
migration_v168="$project_dir/src/main/resources/db/migration/V168__appointment_doctor_and_admission_registration.sql"
migration_v169="$project_dir/src/main/resources/db/migration/V169__medical_agent_harness.sql"
migration_v170="$project_dir/src/main/resources/db/migration/V170__medical_agent_question_examples.sql"
migration_v171="$project_dir/src/main/resources/db/migration/V171__model_deployment_api_configuration.sql"
migration_v172="$project_dir/src/main/resources/db/migration/V172__authorization_policy_chinese_name.sql"
migration_v173="$project_dir/src/main/resources/db/migration/V173__ai_center_crud_and_flow_data.sql"
migration_v174="$project_dir/src/main/resources/db/migration/V174__remove_invalid_ai_validation_model.sql"
migration_v175="$project_dir/src/main/resources/db/migration/V175__capability_pack_editable_inheritance.sql"
migration_v176="$project_dir/src/main/resources/db/migration/V176__tertiary_hospital_ai_center_simulation.sql"
migration_v177="$project_dir/src/main/resources/db/migration/V177__harden_medical_agent_context_and_release_lifecycle.sql"
assertions_v1="$project_dir/src/test/resources/schema/assert-v1.sql"
assertions_v2="$project_dir/src/test/resources/schema/assert-v2.sql"
assertions_v3="$project_dir/src/test/resources/schema/assert-v3.sql"
assertions_v4="$project_dir/src/test/resources/schema/assert-v4.sql"
assertions_v5="$project_dir/src/test/resources/schema/assert-v5.sql"
assertions_v6="$project_dir/src/test/resources/schema/assert-v6.sql"
assertions_v7="$project_dir/src/test/resources/schema/assert-v7.sql"
assertions_v8="$project_dir/src/test/resources/schema/assert-v8.sql"
assertions_v9="$project_dir/src/test/resources/schema/assert-v9.sql"
assertions_v10="$project_dir/src/test/resources/schema/assert-v10.sql"
assertions_v11="$project_dir/src/test/resources/schema/assert-v11.sql"
assertions_v12="$project_dir/src/test/resources/schema/assert-v12.sql"
assertions_v13="$project_dir/src/test/resources/schema/assert-v13.sql"
assertions_v14="$project_dir/src/test/resources/schema/assert-v14.sql"
assertions_v15="$project_dir/src/test/resources/schema/assert-v15.sql"
assertions_v16="$project_dir/src/test/resources/schema/assert-v16.sql"
assertions_v17="$project_dir/src/test/resources/schema/assert-v17.sql"
assertions_v18="$project_dir/src/test/resources/schema/assert-v18.sql"
assertions_v19="$project_dir/src/test/resources/schema/assert-v19.sql"
assertions_v20="$project_dir/src/test/resources/schema/assert-v20.sql"
assertions_v21="$project_dir/src/test/resources/schema/assert-v21.sql"
assertions_v22="$project_dir/src/test/resources/schema/assert-v22.sql"
assertions_v23="$project_dir/src/test/resources/schema/assert-v23.sql"
assertions_v24="$project_dir/src/test/resources/schema/assert-v24.sql"
assertions_v25="$project_dir/src/test/resources/schema/assert-v25.sql"
assertions_v26="$project_dir/src/test/resources/schema/assert-v26.sql"
assertions_v27="$project_dir/src/test/resources/schema/assert-v27.sql"
assertions_v28="$project_dir/src/test/resources/schema/assert-v28.sql"
assertions_v29="$project_dir/src/test/resources/schema/assert-v29.sql"
assertions_v30="$project_dir/src/test/resources/schema/assert-v30.sql"
assertions_v31="$project_dir/src/test/resources/schema/assert-v31.sql"
assertions_v32="$project_dir/src/test/resources/schema/assert-v32.sql"
assertions_v33="$project_dir/src/test/resources/schema/assert-v33.sql"
assertions_v34="$project_dir/src/test/resources/schema/assert-v34.sql"
assertions_v35="$project_dir/src/test/resources/schema/assert-v35.sql"
assertions_v36="$project_dir/src/test/resources/schema/assert-v36.sql"
assertions_v37="$project_dir/src/test/resources/schema/assert-v37.sql"
assertions_v38="$project_dir/src/test/resources/schema/assert-v38.sql"
assertions_v39="$project_dir/src/test/resources/schema/assert-v39.sql"
assertions_v40="$project_dir/src/test/resources/schema/assert-v40.sql"
assertions_v41="$project_dir/src/test/resources/schema/assert-v41.sql"
assertions_v42="$project_dir/src/test/resources/schema/assert-v42.sql"
assertions_v43="$project_dir/src/test/resources/schema/assert-v43.sql"
assertions_v44="$project_dir/src/test/resources/schema/assert-v44.sql"
assertions_v45="$project_dir/src/test/resources/schema/assert-v45.sql"
assertions_v46="$project_dir/src/test/resources/schema/assert-v46.sql"
assertions_v47="$project_dir/src/test/resources/schema/assert-v47.sql"
assertions_v48="$project_dir/src/test/resources/schema/assert-v48.sql"
assertions_v49="$project_dir/src/test/resources/schema/assert-v49.sql"
assertions_v50="$project_dir/src/test/resources/schema/assert-v50.sql"
assertions_v51="$project_dir/src/test/resources/schema/assert-v51.sql"
assertions_v52="$project_dir/src/test/resources/schema/assert-v52.sql"
assertions_v53="$project_dir/src/test/resources/schema/assert-v53.sql"
assertions_v54="$project_dir/src/test/resources/schema/assert-v54.sql"
assertions_v55="$project_dir/src/test/resources/schema/assert-v55.sql"
assertions_v56="$project_dir/src/test/resources/schema/assert-v56.sql"
assertions_v57="$project_dir/src/test/resources/schema/assert-v57.sql"
assertions_v58="$project_dir/src/test/resources/schema/assert-v58.sql"
assertions_v59="$project_dir/src/test/resources/schema/assert-v59.sql"
assertions_v60="$project_dir/src/test/resources/schema/assert-v60.sql"
assertions_v61="$project_dir/src/test/resources/schema/assert-v61.sql"
assertions_v62="$project_dir/src/test/resources/schema/assert-v62.sql"
assertions_v63="$project_dir/src/test/resources/schema/assert-v63.sql"
assertions_v64="$project_dir/src/test/resources/schema/assert-v64.sql"
assertions_v65="$project_dir/src/test/resources/schema/assert-v65.sql"
assertions_v66="$project_dir/src/test/resources/schema/assert-v66.sql"
assertions_v67="$project_dir/src/test/resources/schema/assert-v67.sql"
assertions_v68="$project_dir/src/test/resources/schema/assert-v68.sql"
assertions_v69="$project_dir/src/test/resources/schema/assert-v69.sql"
assertions_v70="$project_dir/src/test/resources/schema/assert-v70.sql"
assertions_v71="$project_dir/src/test/resources/schema/assert-v71.sql"
assertions_v72="$project_dir/src/test/resources/schema/assert-v72.sql"
assertions_v73="$project_dir/src/test/resources/schema/assert-v73.sql"
assertions_v74="$project_dir/src/test/resources/schema/assert-v74.sql"
assertions_v75="$project_dir/src/test/resources/schema/assert-v75.sql"
assertions_v76="$project_dir/src/test/resources/schema/assert-v76.sql"
assertions_v77="$project_dir/src/test/resources/schema/assert-v77.sql"
assertions_v78="$project_dir/src/test/resources/schema/assert-v78.sql"
assertions_v79="$project_dir/src/test/resources/schema/assert-v79.sql"
assertions_v80="$project_dir/src/test/resources/schema/assert-v80.sql"
assertions_v81="$project_dir/src/test/resources/schema/assert-v81.sql"
assertions_v82="$project_dir/src/test/resources/schema/assert-v82.sql"
assertions_v83="$project_dir/src/test/resources/schema/assert-v83.sql"
assertions_v84="$project_dir/src/test/resources/schema/assert-v84.sql"
assertions_v85="$project_dir/src/test/resources/schema/assert-v85.sql"
assertions_v86="$project_dir/src/test/resources/schema/assert-v86.sql"
assertions_v87="$project_dir/src/test/resources/schema/assert-v87.sql"
assertions_v88="$project_dir/src/test/resources/schema/assert-v88.sql"
assertions_v89="$project_dir/src/test/resources/schema/assert-v89.sql"
assertions_v90="$project_dir/src/test/resources/schema/assert-v90.sql"
assertions_v91="$project_dir/src/test/resources/schema/assert-v91.sql"
assertions_v92="$project_dir/src/test/resources/schema/assert-v92.sql"
assertions_v93="$project_dir/src/test/resources/schema/assert-v93.sql"
assertions_v94="$project_dir/src/test/resources/schema/assert-v94.sql"
assertions_v95="$project_dir/src/test/resources/schema/assert-v95.sql"
assertions_v96="$project_dir/src/test/resources/schema/assert-v96.sql"
assertions_v97="$project_dir/src/test/resources/schema/assert-v97.sql"
assertions_v98="$project_dir/src/test/resources/schema/assert-v98.sql"
assertions_v99="$project_dir/src/test/resources/schema/assert-v99.sql"
assertions_v100="$project_dir/src/test/resources/schema/assert-v100.sql"
assertions_v101="$project_dir/src/test/resources/schema/assert-v101.sql"
assertions_v102="$project_dir/src/test/resources/schema/assert-v102.sql"
assertions_v103="$project_dir/src/test/resources/schema/assert-v103.sql"
assertions_v104="$project_dir/src/test/resources/schema/assert-v104.sql"
assertions_v105="$project_dir/src/test/resources/schema/assert-v105.sql"
assertions_v106="$project_dir/src/test/resources/schema/assert-v106.sql"
assertions_v107="$project_dir/src/test/resources/schema/assert-v107.sql"
assertions_v108="$project_dir/src/test/resources/schema/assert-v108.sql"
assertions_v109="$project_dir/src/test/resources/schema/assert-v109.sql"
assertions_v110="$project_dir/src/test/resources/schema/assert-v110.sql"
assertions_v111="$project_dir/src/test/resources/schema/assert-v111.sql"
assertions_v112="$project_dir/src/test/resources/schema/assert-v112.sql"
assertions_v113="$project_dir/src/test/resources/schema/assert-v113.sql"
assertions_v114="$project_dir/src/test/resources/schema/assert-v114.sql"
assertions_v115="$project_dir/src/test/resources/schema/assert-v115.sql"
assertions_v116="$project_dir/src/test/resources/schema/assert-v116.sql"
assertions_v117="$project_dir/src/test/resources/schema/assert-v117.sql"
assertions_v118="$project_dir/src/test/resources/schema/assert-v118.sql"
assertions_v119="$project_dir/src/test/resources/schema/assert-v119.sql"
assertions_v120="$project_dir/src/test/resources/schema/assert-v120.sql"
assertions_v121="$project_dir/src/test/resources/schema/assert-v121.sql"
assertions_v122="$project_dir/src/test/resources/schema/assert-v122.sql"
assertions_v123="$project_dir/src/test/resources/schema/assert-v123.sql"
assertions_v124="$project_dir/src/test/resources/schema/assert-v124.sql"
assertions_v125="$project_dir/src/test/resources/schema/assert-v125.sql"
assertions_v126="$project_dir/src/test/resources/schema/assert-v126.sql"
assertions_v127="$project_dir/src/test/resources/schema/assert-v127.sql"
assertions_v128="$project_dir/src/test/resources/schema/assert-v128.sql"
assertions_v129="$project_dir/src/test/resources/schema/assert-v129.sql"
assertions_v130="$project_dir/src/test/resources/schema/assert-v130.sql"
assertions_v131="$project_dir/src/test/resources/schema/assert-v131.sql"
assertions_v132="$project_dir/src/test/resources/schema/assert-v132.sql"
assertions_v133="$project_dir/src/test/resources/schema/assert-v133.sql"
assertions_v134="$project_dir/src/test/resources/schema/assert-v134.sql"
assertions_v135="$project_dir/src/test/resources/schema/assert-v135.sql"
assertions_v136="$project_dir/src/test/resources/schema/assert-v136.sql"
assertions_v137="$project_dir/src/test/resources/schema/assert-v137.sql"
assertions_v138="$project_dir/src/test/resources/schema/assert-v138.sql"
assertions_v139="$project_dir/src/test/resources/schema/assert-v139.sql"
assertions_v140="$project_dir/src/test/resources/schema/assert-v140.sql"
assertions_v141="$project_dir/src/test/resources/schema/assert-v141.sql"
assertions_v142="$project_dir/src/test/resources/schema/assert-v142.sql"
assertions_v143="$project_dir/src/test/resources/schema/assert-v143.sql"
assertions_v144="$project_dir/src/test/resources/schema/assert-v144.sql"
assertions_v145="$project_dir/src/test/resources/schema/assert-v145.sql"
assertions_v146="$project_dir/src/test/resources/schema/assert-v146.sql"
assertions_v147="$project_dir/src/test/resources/schema/assert-v147.sql"
assertions_v148="$project_dir/src/test/resources/schema/assert-v148.sql"
assertions_v149="$project_dir/src/test/resources/schema/assert-v149.sql"
assertions_v150="$project_dir/src/test/resources/schema/assert-v150.sql"
assertions_v151="$project_dir/src/test/resources/schema/assert-v151.sql"
assertions_v152="$project_dir/src/test/resources/schema/assert-v152.sql"
assertions_v153="$project_dir/src/test/resources/schema/assert-v153.sql"
assertions_v154="$project_dir/src/test/resources/schema/assert-v154.sql"
assertions_v155="$project_dir/src/test/resources/schema/assert-v155.sql"
assertions_v156="$project_dir/src/test/resources/schema/assert-v156.sql"
assertions_v157="$project_dir/src/test/resources/schema/assert-v157.sql"
assertions_v158="$project_dir/src/test/resources/schema/assert-v158.sql"
assertions_v159="$project_dir/src/test/resources/schema/assert-v159.sql"
assertions_v160="$project_dir/src/test/resources/schema/assert-v160.sql"
assertions_v161="$project_dir/src/test/resources/schema/assert-v161.sql"
assertions_v162="$project_dir/src/test/resources/schema/assert-v162.sql"
assertions_v163="$project_dir/src/test/resources/schema/assert-v163.sql"
assertions_v164="$project_dir/src/test/resources/schema/assert-v164.sql"
assertions_v165="$project_dir/src/test/resources/schema/assert-v165.sql"
assertions_v166="$project_dir/src/test/resources/schema/assert-v166.sql"
assertions_v169="$project_dir/src/test/resources/schema/assert-v169.sql"
assertions_v177="$project_dir/src/test/resources/schema/assert-v177.sql"

for required_file_v166 in "$migration_v166" "$assertions_v166"; do
  if [[ ! -f "$required_file_v166" ]]; then
    echo "Missing schema contract file: $required_file_v166" >&2
    exit 1
  fi
done

for required_file_latest in "$migration_v167" "$migration_v168" "$migration_v169" "$assertions_v169" \
  "$migration_v170" "$migration_v171" "$migration_v172" "$migration_v173" "$migration_v174" \
  "$migration_v175" "$migration_v176" "$migration_v177" "$assertions_v177"; do
  if [[ ! -f "$required_file_latest" ]]; then
    echo "Missing latest schema contract file: $required_file_latest" >&2
    exit 1
  fi
done

for required_file in "$migration_v1" "$migration_v2" "$migration_v3" "$migration_v4" "$migration_v5" "$migration_v6" "$migration_v7" "$migration_v8" "$migration_v9" "$migration_v10" "$migration_v11" "$migration_v12" "$migration_v13" "$migration_v14" "$migration_v15" "$migration_v16" "$migration_v17" "$migration_v18" "$migration_v19" "$migration_v20" "$migration_v21" "$migration_v22" "$migration_v23" "$migration_v24" "$migration_v25" "$migration_v26" "$migration_v27" "$migration_v28" "$migration_v29" "$migration_v30" "$migration_v31" "$migration_v32" "$migration_v33" "$migration_v34" "$migration_v35" "$migration_v36" "$assertions_v1" "$assertions_v2" "$assertions_v3" "$assertions_v4" "$assertions_v5" "$assertions_v6" "$assertions_v7" "$assertions_v8" "$assertions_v9" "$assertions_v10" "$assertions_v11" "$assertions_v12" "$assertions_v13" "$assertions_v14" "$assertions_v15" "$assertions_v16" "$assertions_v17" "$assertions_v18" "$assertions_v19" "$assertions_v20" "$assertions_v21" "$assertions_v22" "$assertions_v23" "$assertions_v24" "$assertions_v25" "$assertions_v26" "$assertions_v27" "$assertions_v28" "$assertions_v29" "$assertions_v30" "$assertions_v31" "$assertions_v32" "$assertions_v33" "$assertions_v34" "$assertions_v35" "$assertions_v36" "$migration_v37" "$assertions_v37" "$migration_v38" "$assertions_v38" "$migration_v39" "$assertions_v39" "$migration_v40" "$assertions_v40" "$migration_v41" "$assertions_v41" "$migration_v42" "$assertions_v42" "$migration_v43" "$assertions_v43" "$migration_v44" "$assertions_v44" "$migration_v45" "$assertions_v45" "$migration_v46" "$assertions_v46" "$migration_v47" "$assertions_v47" "$migration_v48" "$assertions_v48" "$migration_v49" "$assertions_v49" "$migration_v50" "$assertions_v50" "$migration_v51" "$assertions_v51" "$migration_v52" "$assertions_v52" "$migration_v53" "$assertions_v53" "$migration_v54" "$assertions_v54" "$migration_v55" "$assertions_v55" "$migration_v56" "$assertions_v56" "$migration_v57" "$assertions_v57" "$migration_v58" "$assertions_v58" "$migration_v59" "$assertions_v59" "$migration_v60" "$assertions_v60" "$migration_v61" "$assertions_v61" "$migration_v62" "$assertions_v62" "$migration_v63" "$assertions_v63" "$migration_v64" "$assertions_v64" "$migration_v65" "$assertions_v65" "$migration_v66" "$assertions_v66" "$migration_v67" "$assertions_v67" "$migration_v68" "$assertions_v68" "$migration_v69" "$assertions_v69" "$migration_v70" "$assertions_v70" "$migration_v71" "$assertions_v71" "$migration_v72" "$assertions_v72" "$migration_v73" "$assertions_v73" "$migration_v74" "$assertions_v74" "$migration_v75" "$assertions_v75" "$migration_v76" "$assertions_v76" "$migration_v77" "$assertions_v77" "$migration_v78" "$assertions_v78" "$migration_v79" "$assertions_v79" "$migration_v80" "$assertions_v80" "$migration_v81" "$assertions_v81" "$migration_v82" "$assertions_v82" "$migration_v83" "$assertions_v83" "$migration_v84" "$assertions_v84" "$migration_v85" "$assertions_v85" "$migration_v86" "$assertions_v86" "$migration_v87" "$assertions_v87" "$migration_v88" "$assertions_v88" "$migration_v89" "$assertions_v89" "$migration_v90" "$assertions_v90" "$migration_v91" "$assertions_v91" "$migration_v92" "$assertions_v92" "$migration_v93" "$assertions_v93" "$migration_v94" "$assertions_v94" "$migration_v95" "$assertions_v95" "$migration_v96" "$assertions_v96" "$migration_v97" "$assertions_v97" "$migration_v98" "$assertions_v98" "$migration_v99" "$assertions_v99" "$migration_v100" "$assertions_v100" "$migration_v101" "$assertions_v101" "$migration_v102" "$assertions_v102" "$migration_v103" "$assertions_v103" "$migration_v104" "$assertions_v104" "$migration_v105" "$assertions_v105" "$migration_v106" "$assertions_v106" "$migration_v107" "$assertions_v107" "$migration_v108" "$assertions_v108" "$migration_v109" "$assertions_v109" "$migration_v110" "$assertions_v110" "$migration_v111" "$assertions_v111" "$migration_v112" "$assertions_v112" "$migration_v113" "$assertions_v113" "$migration_v114" "$assertions_v114" "$migration_v115" "$assertions_v115" "$migration_v116" "$assertions_v116" "$migration_v117" "$assertions_v117" "$migration_v118" "$assertions_v118" "$migration_v119" "$assertions_v119" "$migration_v120" "$assertions_v120" "$migration_v121" "$assertions_v121" "$migration_v122" "$assertions_v122" "$migration_v123" "$assertions_v123" "$migration_v124" "$assertions_v124" "$migration_v125" "$assertions_v125" "$migration_v126" "$assertions_v126" "$migration_v127" "$assertions_v127" "$migration_v128" "$assertions_v128" "$migration_v129" "$assertions_v129" "$migration_v130" "$assertions_v130" "$migration_v131" "$assertions_v131" "$migration_v132" "$assertions_v132" "$migration_v133" "$assertions_v133" "$migration_v134" "$assertions_v134" "$migration_v135" "$assertions_v135" "$migration_v136" "$assertions_v136" "$migration_v137" "$assertions_v137" "$migration_v138" "$assertions_v138" "$migration_v139" "$assertions_v139" "$migration_v140" "$assertions_v140" "$migration_v141" "$assertions_v141" "$migration_v142" "$assertions_v142" "$migration_v143" "$assertions_v143" "$migration_v144" "$assertions_v144" "$migration_v145" "$assertions_v145" "$migration_v146" "$assertions_v146" "$migration_v147" "$assertions_v147" "$migration_v148" "$assertions_v148" "$migration_v149" "$assertions_v149" "$migration_v150" "$assertions_v150" "$migration_v151" "$assertions_v151" "$migration_v152" "$assertions_v152" "$migration_v153" "$assertions_v153" "$migration_v154" "$assertions_v154" "$migration_v155" "$assertions_v155" "$migration_v156" "$assertions_v156" "$migration_v157" "$assertions_v157" "$migration_v158" "$assertions_v158" "$migration_v159" "$assertions_v159" "$migration_v160" "$assertions_v160" "$migration_v161" "$assertions_v161" "$migration_v162" "$assertions_v162" "$migration_v163" "$assertions_v163" "$migration_v164" "$assertions_v164" "$migration_v165" "$assertions_v165"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Missing schema contract file: $required_file" >&2
    exit 1
  fi
done

"$pg_bin/psql" -X -v ON_ERROR_STOP=1 -h "$pg_socket" -p "$pg_port" -d "$pg_database" \
  -c 'begin' \
  -c 'create schema haoemr_schema_contract_test' \
  -c 'set local search_path to haoemr_schema_contract_test' \
  -f "$migration_v1" -f "$assertions_v1" \
  -f "$migration_v2" -f "$assertions_v2" \
  -f "$migration_v3" -f "$assertions_v3" \
  -f "$migration_v4" -f "$assertions_v4" \
  -f "$migration_v5" -f "$assertions_v5" \
  -f "$migration_v6" -f "$assertions_v6" \
  -f "$migration_v7" -f "$assertions_v7" \
  -f "$migration_v8" -f "$assertions_v8" \
  -f "$migration_v9" -f "$assertions_v9" \
  -f "$migration_v10" -f "$assertions_v10" \
  -f "$migration_v11" -f "$assertions_v11" \
  -f "$migration_v12" -f "$assertions_v12" \
  -f "$migration_v13" -f "$assertions_v13" \
  -f "$migration_v14" -f "$assertions_v14" \
  -f "$migration_v15" -f "$assertions_v15" \
  -f "$migration_v16" -f "$assertions_v16" \
  -f "$migration_v17" -f "$assertions_v17" \
  -f "$migration_v18" -f "$assertions_v18" \
  -f "$migration_v19" -f "$assertions_v19" \
  -f "$migration_v20" -f "$assertions_v20" \
  -f "$migration_v21" -f "$assertions_v21" \
  -f "$migration_v22" -f "$assertions_v22" \
  -f "$migration_v23" -f "$assertions_v23" \
  -f "$migration_v24" -f "$assertions_v24" \
  -f "$migration_v25" -f "$assertions_v25" \
  -f "$migration_v26" -f "$assertions_v26" \
  -f "$migration_v27" -f "$assertions_v27" \
  -f "$migration_v28" -f "$assertions_v28" \
  -f "$migration_v29" -f "$assertions_v29" \
  -f "$migration_v30" -f "$assertions_v30" \
  -f "$migration_v31" -f "$assertions_v31" \
  -f "$migration_v32" -f "$assertions_v32" \
  -f "$migration_v33" -f "$assertions_v33" \
  -f "$migration_v34" -f "$assertions_v34" \
  -f "$migration_v35" -f "$assertions_v35" \
  -f "$migration_v36" -f "$assertions_v36" \
  -f "$migration_v37" -f "$assertions_v37" \
  -f "$migration_v38" -f "$assertions_v38" \
  -f "$migration_v39" -f "$assertions_v39" \
  -f "$migration_v40" -f "$assertions_v40" \
  -f "$migration_v41" -f "$assertions_v41" \
  -f "$migration_v42" -f "$assertions_v42" \
  -f "$migration_v43" -f "$assertions_v43" \
  -f "$migration_v44" -f "$assertions_v44" \
  -f "$migration_v45" -f "$assertions_v45" \
  -f "$migration_v46" -f "$assertions_v46" \
  -f "$migration_v47" -f "$assertions_v47" \
  -f "$migration_v48" -f "$assertions_v48" \
  -f "$migration_v49" -f "$assertions_v49" \
  -f "$migration_v50" -f "$assertions_v50" \
  -f "$migration_v51" -f "$assertions_v51" \
  -f "$migration_v52" -f "$assertions_v52" \
  -f "$migration_v53" -f "$assertions_v53" \
  -f "$migration_v54" -f "$assertions_v54" \
  -f "$migration_v55" -f "$assertions_v55" \
  -f "$migration_v56" -f "$assertions_v56" \
  -f "$migration_v57" -f "$assertions_v57" \
  -f "$migration_v58" -f "$assertions_v58" \
  -f "$migration_v59" -f "$assertions_v59" \
  -f "$migration_v60" -f "$assertions_v60" \
  -f "$migration_v61" -f "$assertions_v61" \
  -f "$migration_v62" -f "$assertions_v62" \
  -f "$migration_v63" -f "$assertions_v63" \
  -f "$migration_v64" -f "$assertions_v64" \
  -f "$migration_v65" -f "$assertions_v65" \
  -f "$migration_v66" -f "$assertions_v66" \
  -f "$migration_v67" -f "$assertions_v67" \
  -f "$migration_v68" -f "$assertions_v68" \
  -f "$migration_v69" -f "$assertions_v69" \
  -f "$migration_v70" -f "$assertions_v70" \
  -f "$migration_v71" -f "$assertions_v71" \
  -f "$migration_v72" -f "$assertions_v72" \
  -f "$migration_v73" -f "$assertions_v73" \
  -f "$migration_v74" -f "$assertions_v74" \
  -f "$migration_v75" -f "$assertions_v75" \
  -f "$migration_v76" -f "$assertions_v76" \
  -f "$migration_v77" -f "$assertions_v77" \
  -f "$migration_v78" -f "$assertions_v78" \
  -f "$migration_v79" -f "$assertions_v79" \
  -f "$migration_v80" -f "$assertions_v80" \
  -f "$migration_v81" -f "$assertions_v81" \
  -f "$migration_v82" -f "$assertions_v82" \
  -f "$migration_v83" -f "$assertions_v83" \
  -f "$migration_v84" -f "$assertions_v84" \
  -f "$migration_v85" -f "$assertions_v85" \
  -f "$migration_v86" -f "$assertions_v86" \
  -f "$migration_v87" -f "$assertions_v87" \
  -f "$migration_v88" -f "$assertions_v88" \
  -f "$migration_v89" -f "$assertions_v89" \
  -f "$migration_v90" -f "$assertions_v90" \
  -f "$migration_v91" -f "$assertions_v91" \
  -f "$migration_v92" -f "$assertions_v92" \
  -f "$migration_v93" -f "$assertions_v93" \
  -f "$migration_v94" -f "$assertions_v94" \
  -f "$migration_v95" -f "$assertions_v95" \
  -f "$migration_v96" -f "$assertions_v96" \
  -f "$migration_v97" -f "$assertions_v97" \
  -f "$migration_v98" -f "$assertions_v98" \
  -f "$migration_v99" -f "$assertions_v99" \
  -f "$migration_v100" -f "$assertions_v100" \
  -f "$migration_v101" -f "$assertions_v101" \
  -f "$migration_v102" -f "$assertions_v102" \
  -f "$migration_v103" -f "$assertions_v103" \
  -f "$migration_v104" -f "$assertions_v104" \
  -f "$migration_v105" -f "$assertions_v105" \
  -f "$migration_v106" -f "$assertions_v106" \
  -f "$migration_v107" -f "$assertions_v107" \
  -f "$migration_v108" -f "$assertions_v108" \
  -f "$migration_v109" -f "$assertions_v109" \
  -f "$migration_v110" -f "$assertions_v110" \
  -f "$migration_v111" -f "$assertions_v111" \
  -f "$migration_v112" -f "$assertions_v112" \
  -f "$migration_v113" -f "$assertions_v113" \
  -f "$migration_v114" -f "$assertions_v114" \
  -f "$migration_v115" -f "$assertions_v115" \
  -f "$migration_v116" -f "$assertions_v116" \
  -f "$migration_v117" -f "$assertions_v117" \
  -f "$migration_v118" -f "$assertions_v118" \
  -f "$migration_v119" -f "$assertions_v119" \
  -f "$migration_v120" -f "$assertions_v120" \
  -f "$migration_v121" -f "$assertions_v121" \
  -f "$migration_v122" -f "$assertions_v122" \
  -f "$migration_v123" -f "$assertions_v123" \
  -f "$migration_v124" -f "$assertions_v124" \
  -f "$migration_v125" -f "$assertions_v125" \
  -f "$migration_v126" -f "$assertions_v126" \
  -f "$migration_v127" -f "$assertions_v127" \
  -f "$migration_v128" -f "$assertions_v128" \
  -f "$migration_v129" -f "$assertions_v129" \
  -f "$migration_v130" -f "$assertions_v130" \
  -f "$migration_v131" -f "$assertions_v131" \
  -f "$migration_v132" -f "$assertions_v132" \
  -f "$migration_v133" -f "$assertions_v133" \
  -f "$migration_v134" -f "$assertions_v134" \
  -f "$migration_v135" -f "$assertions_v135" \
  -f "$migration_v136" -f "$assertions_v136" \
  -f "$migration_v137" -f "$assertions_v137" \
  -f "$migration_v138" -f "$assertions_v138" \
  -f "$migration_v139" -f "$assertions_v139" \
  -f "$migration_v140" -f "$assertions_v140" \
  -f "$migration_v141" -f "$assertions_v141" \
  -f "$migration_v142" -f "$assertions_v142" \
  -f "$migration_v143" -f "$assertions_v143" \
  -f "$migration_v144" -f "$assertions_v144" \
  -f "$migration_v145" -f "$assertions_v145" \
  -f "$migration_v146" -f "$assertions_v146" \
  -f "$migration_v147" -f "$assertions_v147" \
  -f "$migration_v148" -f "$assertions_v148" \
  -f "$migration_v149" -f "$assertions_v149" \
  -f "$migration_v150" -f "$assertions_v150" \
  -f "$migration_v151" -f "$assertions_v151" \
  -f "$migration_v152" -f "$assertions_v152" \
  -f "$migration_v153" -f "$assertions_v153" \
  -f "$migration_v154" -f "$assertions_v154" \
  -f "$migration_v155" -f "$assertions_v155" \
  -f "$migration_v156" -f "$assertions_v156" \
  -f "$migration_v157" -f "$assertions_v157" \
  -f "$migration_v158" -f "$assertions_v158" \
  -f "$migration_v159" -f "$assertions_v159" \
  -f "$migration_v160" -f "$assertions_v160" \
  -f "$migration_v161" -f "$assertions_v161" \
  -f "$migration_v162" -f "$assertions_v162" \
  -f "$migration_v163" -f "$assertions_v163" \
  -f "$migration_v164" -f "$assertions_v164" \
  -f "$migration_v165" -f "$assertions_v165" \
  -f "$migration_v166" -f "$assertions_v166" \
  -f "$migration_v167" \
  -f "$migration_v168" \
  -f "$migration_v169" -f "$assertions_v169" \
  -f "$migration_v170" \
  -f "$migration_v171" \
  -f "$migration_v172" \
  -f "$migration_v173" \
  -f "$migration_v174" \
  -f "$migration_v175" \
  -f "$migration_v176" \
  -f "$migration_v177" -f "$assertions_v177" \
  -c 'rollback'

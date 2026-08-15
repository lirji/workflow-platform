#!/usr/bin/env bash
# 审方 shadow 端到端冒烟(需已按 shadow-e2e-runbook.md 起好:中台 server:8300 + his-outpatient:9004[workflow-shadow]
# + workflow PG:25432 + his PG:5433 + 临时 Kafka:9095)。seed 一条就诊+药品医嘱(绕开 registration),
# 提交→影子 WAITING_USER,审方通过→影子 COMPLETED,并对账 legacy 权威 + 计费幂等未破坏。
set -uo pipefail
EID="${1:-90001}"
PASS=0; FAIL=0
ok(){ echo "  PASS: $1"; PASS=$((PASS+1)); }
no(){ echo "  FAIL: $1"; FAIL=$((FAIL+1)); }
wfpg(){ docker exec workflow-postgres psql -U workflow -d workflow -tAc "$1"; }
hispg(){ docker exec his-postgres psql -U his -d his_outpatient -tAc "$1"; }

echo "==> seed 就诊(OPEN)+ 药品医嘱(DRUG,CREATED)"
hispg "DELETE FROM med_order WHERE encounter_id=$EID; DELETE FROM encounter WHERE id=$EID;
INSERT INTO encounter(id,appointment_id,patient_id,dept_code,doctor_id,doctor_name,status,visit_time,version,billed)
 VALUES($EID,$EID,1,'NK',2,'doctor','OPEN',now(),0,false);
INSERT INTO med_order(id,encounter_id,patient_id,order_type,item_code,item_name,quantity,unit_price,amount,state,version)
 VALUES($EID,$EID,1,'DRUG','D001','阿莫西林',1,10.00,10.00,'CREATED',0);" >/dev/null

echo "==> 提交医嘱(DOCTOR)"
curl -s -X POST "http://localhost:9004/outpatient/encounters/$EID/submit" \
  -H "X-His-User-Id: 2" -H "X-His-Username: doctor" -H "X-His-Roles: DOCTOR" -H "X-His-Dept-Id: 1" >/dev/null
sleep 3
[ "$(wfpg "SELECT phase FROM wf_process_link WHERE business_key='$EID';")" = "WAITING_USER" ] \
  && ok "影子实例 WAITING_USER" || no "影子实例未进 WAITING_USER"

echo "==> 药师审方通过(PHARMACIST)"
curl -s -X POST "http://localhost:9004/outpatient/reviews/$EID/pass" \
  -H "X-His-User-Id: 5" -H "X-His-Username: pharmacist" -H "X-His-Roles: PHARMACIST" -H "X-His-Dept-Id: 1" >/dev/null
sleep 5
[ "$(wfpg "SELECT phase FROM wf_process_link WHERE business_key='$EID';")" = "COMPLETED" ] \
  && ok "影子实例 COMPLETED(4 跳 Kafka 闭环)" || no "影子实例未 COMPLETED"
[ "$(hispg "SELECT result FROM prescription_review WHERE encounter_id=$EID;")" = "PASS" ] \
  && ok "legacy 留痕 PASS(权威)" || no "legacy 留痕缺失"
[ "$(hispg "SELECT state FROM med_order WHERE encounter_id=$EID;")" = "SUBMITTED" ] \
  && ok "医嘱 SUBMITTED" || no "医嘱状态异常"
[ "$(hispg "SELECT billed FROM encounter WHERE id=$EID;")" = "t" ] \
  && ok "计费触发 billed=t(幂等红线未破坏)" || no "计费未触发"

echo "================  PASS=$PASS FAIL=$FAIL  ================"
[ "$FAIL" = "0" ]

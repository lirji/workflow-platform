#!/usr/bin/env bash
# Phase 1 迁移冒烟:在运行中的 compose PG 上用 scratch 库验证 wf_* 迁移建表 + 幂等唯一 + WAITING_USER 偏唯一。
# 与项目既有 deploy/*-smoke.sh 约定一致(不依赖 Testcontainers)。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MIGDIR="$ROOT/workflow-platform-core/src/main/resources/db/migration"
CT=workflow-postgres
DB=wf_smoke
PU=workflow
PASS=0; FAIL=0
ok(){ echo "  PASS: $1"; PASS=$((PASS+1)); }
no(){ echo "  FAIL: $1"; FAIL=$((FAIL+1)); }
psql_db(){ docker exec -i "$CT" psql -v ON_ERROR_STOP=1 -qtA -U "$PU" -d "$DB" "$@"; }

echo "==> 重建 scratch 库 $DB"
docker exec "$CT" psql -U "$PU" -d postgres -c "DROP DATABASE IF EXISTS $DB;" >/dev/null
docker exec "$CT" psql -U "$PU" -d postgres -c "CREATE DATABASE $DB;" >/dev/null

echo "==> 应用全部迁移(V*.sql 按版本序)"
APPLY_OK=1
for f in $(ls "$MIGDIR"/V*.sql | sort -V); do
  if ! docker exec -i "$CT" psql -v ON_ERROR_STOP=1 -q -U "$PU" -d "$DB" < "$f" >/dev/null; then
    APPLY_OK=0; echo "    应用失败: $(basename "$f")"
  fi
done
[ "$APPLY_OK" = 1 ] && ok "全部迁移应用成功" || no "迁移应用失败"

echo "==> 1) 七张 wf_ 表齐全(V1 六张 + V2 wf_dlq_event)"
N=$(psql_db -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name LIKE 'wf_%';")
[ "$N" = "7" ] && ok "wf_ 表数=7" || no "wf_ 表数=$N(期望7)"
DLQ=$(psql_db -c "SELECT count(*) FROM information_schema.tables WHERE table_name='wf_dlq_event';")
[ "$DLQ" = "1" ] && ok "wf_dlq_event 存在" || no "wf_dlq_event 缺失"

INS="INSERT INTO wf_process_link(tenant_id,process_definition_key,business_key,idempotency_key,process_instance_id,phase,status)"

echo "==> 2) 四元组幂等唯一"
psql_db -c "$INS VALUES('his','hisRxReview','enc-2001','cycle-1','pi-a','WAITING_BUSINESS','ACTIVE');" >/dev/null 2>&1
if psql_db -c "$INS VALUES('his','hisRxReview','enc-2001','cycle-1','pi-b','WAITING_BUSINESS','ACTIVE');" >/dev/null 2>&1; then
  no "同四元组重复插入竟成功(应被唯一约束拒绝)"
else
  ok "同四元组重复插入被拒绝"
fi

echo "==> 3) 同 businessKey 最多一个 WAITING_USER;WAITING_BUSINESS 不阻塞新 cycle"
psql_db -c "$INS VALUES('his','hisRxReview','enc-3001','cycle-1','pi-1','WAITING_USER','ACTIVE');" >/dev/null 2>&1
if psql_db -c "$INS VALUES('his','hisRxReview','enc-3001','cycle-2','pi-2','WAITING_USER','ACTIVE');" >/dev/null 2>&1; then
  no "同 businessKey 第二个 WAITING_USER 竟成功(应被偏唯一索引拒绝)"
else
  ok "同 businessKey 第二个 WAITING_USER 被拒绝"
fi
psql_db -c "UPDATE wf_process_link SET phase='WAITING_BUSINESS' WHERE business_key='enc-3001' AND idempotency_key='cycle-1';" >/dev/null 2>&1
if psql_db -c "$INS VALUES('his','hisRxReview','enc-3001','cycle-2','pi-2','WAITING_USER','ACTIVE');" >/dev/null 2>&1; then
  ok "旧 cycle 转 WAITING_BUSINESS 后,新 cycle 的 WAITING_USER 可建"
else
  no "WAITING_BUSINESS 后新 cycle 仍被阻塞"
fi
CNT=$(psql_db -c "SELECT count(*) FROM wf_process_link WHERE business_key='enc-3001';")
[ "$CNT" = "2" ] && ok "enc-3001 共 2 条 link" || no "enc-3001 link 数=$CNT(期望2)"

echo "==> 清理 scratch 库"
docker exec "$CT" psql -U "$PU" -d postgres -c "DROP DATABASE IF EXISTS $DB;" >/dev/null

echo "================  PASS=$PASS FAIL=$FAIL  ================"
[ "$FAIL" = "0" ]

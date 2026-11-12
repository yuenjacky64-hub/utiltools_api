# Changelog

## [v0.116.0] - 2026-11-12

- Add POST /api/v1/utils/datetime/cron/next (Spring CronExpression; 5/6-field + macros, DST-aware, count 1..100 default 5)
- New DTOs DateTimeCronRequest/DateTimeCronResponse; AuditedOperations.cronNext; OperationType.DATETIME_CRON_NEXT (deterministic) + replayInvoke branch
- README route row, detailed guide, audit op-list (thirty-three)
- Extended DateTimeServiceTest/DateTimeControllerTest incl. DST-boundary and audit-replay VERIFIED


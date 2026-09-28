# R0：原合成审计 UUID 不满足既有契约

浏览器首次登录本期全新合成数据库时，原管理首页报 CONTRACT_MISMATCH（audit_event_id）。只读核对发现 SyntheticDataImporter 用 md5(...)::uuid 生成任意 version/variant 的 UUID；前端既有 z.uuid 契约拒绝这些值。

修复只规范该导入器的字典和审计样例 ID 的 version/variant 位，与同文件既有权限策略 fixture 写法一致。不放宽前端 UUID 校验、不修改临床状态机、不删除旧库记录。

后续浏览器验收使用新建的独立合成库；旧验收库保留用于缺陷证据，原开发库未访问。已有演示实例中的历史无效 ID 不会被本修复静默重写，需要独立数据治理。

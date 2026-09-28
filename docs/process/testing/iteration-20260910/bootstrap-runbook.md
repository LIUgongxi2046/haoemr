# 首次管理员引导操作说明

状态：代码及隔离数据库事务测试通过；真实 prod 非 Web 进程完整启动、正式 OIDC 首登尚未联调，不能据此宣称可上线。本轮未执行正式引导、部署或连接院方身份服务。

## 使用边界

引导复用原单体中的组织及人员记录，只供部署主机操作人员在全新租户建立首位管理员。默认不启用，不提供 HTTP 注册入口、默认密码或匿名身份。已有租户、修改过的请求、issuer 不匹配均拒绝。相同请求返回原绑定 ID；即使账号后来被停用，也绝不重启或重置。

准备一个受控的绝对路径 JSON 文件（≤16 KB，不接受符号链接），字段如下。tenantId 须与身份服务将签发的 tenant_id 声明一致，externalSubject 须为已确认的 OIDC sub；这些示例值不能当作生产配置。

```json
{
  "tenantId": "00000000-0000-4000-8000-000000000001",
  "tenantCode": "HOSPITAL",
  "organizationCode": "HOSPITAL",
  "organizationName": "医院名称",
  "facilityCode": "MAIN",
  "facilityName": "主院区",
  "administratorName": "管理员姓名",
  "externalSubject": "OIDC-sub-placeholder",
  "issuer": "https://identity.example.invalid"
}
```

完整配置仍按原 application-prod.yml 和 ProductionEnvironmentPostProcessor 提供，含数据库、OIDC/MFA、签名、存储等必要配置。引导参数不能关闭原生产前置校验。

```sh
java -jar <已核验的应用包.jar> \
  --spring.profiles.active=prod \
  --spring.main.web-application-type=none \
  --haoemr.bootstrap.enabled=true \
  --haoemr.bootstrap.request-file=/absolute/path/first-administrator.json
```

该命令用于后续部署方联调，本轮未对正式环境执行。执行成功只输出 tenant/org/facility/user/role 的标识符，并关闭命令进程。随后取消 bootstrap.enabled，按原 Web 启动与统一登录流程继续；新增科室、人员及第二位独立审批人均走原管理功能。

审计 action：FIRST_ADMINISTRATOR_BOOTSTRAPPED，同时写入原 Outbox。引导只建初始机构和身份，不带患者、就诊、病历或模板。要停用已创建身份，使用原账号治理功能，不删除审计或重复初始化。

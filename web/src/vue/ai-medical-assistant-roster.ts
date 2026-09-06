/** 当前验收环境已启用的 AI医助 EVA 团队名录；登录页在未登录状态下使用同一份展示数据。 */
export const aiMedicalAssistantRoster = [
  '就诊摘要', '文书起草', '病历质控', '结果闭环', '诊疗协同', '心血管诊疗', '血糖治理',
  '重症风险研判', '院感监测', '医保合规', '医技预约调度', '患者宣教', '科研随访', '中医辨证审方',
] as const;

export const aiMedicalAssistantRosterSummary = {
  teamCount: aiMedicalAssistantRoster.length,
  specialistCount: 58,
} as const;

import {chromium} from 'playwright';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
const browser=await chromium.launch({headless:true});
const page=await browser.newPage({viewport:{width:1440,height:1000}});
const errors=[];page.on('pageerror',e=>errors.push(e.message));
const out='../output/playwright/agent-functional-20260906';await mkdir(out,{recursive:true});
try {
await page.goto('http://127.0.0.1:4181/#/login');
await page.getByLabel('用户名',{exact:true}).fill('linwei');
await page.locator('#system-login-password').fill('HaoEMR-dev!');
await page.getByRole('button',{name:'登录系统',exact:true}).click();
await page.waitForURL(url => !url.hash.includes('/login'), { timeout: 60000 });
await page.goto('http://127.0.0.1:4181/#/ai-assistant');
await page.getByRole('heading', { name: 'AI医助 EVA', exact: true }).waitFor({ timeout: 60000 });
await page.waitForTimeout(1500);
const catalog=JSON.parse(await readFile('../output/agent-functional-acceptance-20260906/catalog.json','utf8'));
const coverage=[];
for(const family of catalog){
 const title=family.main_agent.display_name.replace(/主\s*Agent/gi,'').trim();
 await page.locator('.xiaonan-team-list').getByTitle(title,{exact:true}).click();
 if(await page.getByRole('button',{name:'展开诊疗环节医助',exact:true}).count())await page.getByRole('button',{name:'展开诊疗环节医助',exact:true}).click();
 for(const child of family.child_agents){
  const button=page.locator('.eva-stage-chips button').filter({hasText:child.display_name.replace(/候选/g,'草稿')});
  await button.click();
  if(await button.getAttribute('aria-pressed')!=='true')throw Error(`Child not selected: ${child.agent_code}`);
  coverage.push(child.agent_code);
 }
}
await writeFile(`${out}/catalog-coverage.json`,JSON.stringify(coverage,null,2));
await page.locator('.xiaonan-team-list').getByTitle('文书起草',{exact:true}).click();
await page.locator('.eva-stage-chips button').filter({hasText:'门诊病历起草医助'}).click();
await page.getByLabel('向 AI医助 EVA 描述诊疗任务',{exact:true}).fill('请基于当前记录起草门诊病历段落，缺失信息明确标注，不补造体征与诊疗操作。');
await page.getByRole('button',{name:'发送任务',exact:true}).click();
await page.getByRole('button',{name:'打开病历编辑区',exact:true}).waitFor({timeout:180000});
await page.getByRole('button',{name:'打开病历编辑区',exact:true}).click();
await page.getByRole('button',{name:'审阅并带入编辑区',exact:true}).first().waitFor({timeout:60000});
await page.getByRole('button',{name:'审阅并带入编辑区',exact:true}).first().click();
await page.getByRole('button',{name:'确认带入编辑区',exact:true}).click();
await page.screenshot({path:`${out}/draft-adopted.png`,fullPage:true});
const reminderText=`UI-AGENT-FUNCTIONAL-${Date.now()}：`+'请人工核对医助提供的资料来源，完成随访计划确认并记录结果。'.repeat(15);
await page.getByRole('button',{name:'转为待办提醒',exact:true}).first().click();
await page.getByLabel('待办内容',{exact:true}).fill(reminderText);
await page.getByRole('button',{name:'保存待办提醒',exact:true}).click();
await page.getByRole('button',{name:'打开提醒与任务',exact:true}).waitFor({timeout:60000});
await page.getByRole('button',{name:'打开提醒与任务',exact:true}).click();
const row=page.locator('.admin-table tbody tr').filter({hasText:reminderText});
await row.getByRole('button',{name:'转为任务',exact:true}).waitFor({timeout:30000});
await row.getByRole('button',{name:'转为任务',exact:true}).click();
await row.getByRole('link').waitFor({timeout:30000});
await row.getByRole('button',{name:'确认',exact:true}).click();
await row.getByText('已确认',{exact:true}).waitFor({timeout:30000});
await page.screenshot({path:`${out}/reminder-task-confirmed.png`,fullPage:true});
await writeFile(`${out}/workflow.json`,JSON.stringify({catalog_count:coverage.length,draft_adopted:true,long_reminder_created:true,task_created:true,reminder_acknowledged:true}));


await writeFile(`${out}/page-text.txt`,await page.locator('body').innerText());
await page.screenshot({path:`${out}/assistant.png`,fullPage:true});
await writeFile(`${out}/errors.json`,JSON.stringify(errors));
} catch (error) { await writeFile(`${out}/failure.txt`,String(error)+'\n'+await page.locator('body').innerText()); throw error; } finally { await browser.close(); }

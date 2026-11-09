#!/usr/bin/env python3
"""Standalone template-based HTML for Java and historical evaluation artifacts; no model calls."""
import argparse
import csv
import datetime as dt
from zoneinfo import ZoneInfo
import hashlib
import html
import io
import json
import math
import pathlib
import statistics
import re
import tempfile
from html.parser import HTMLParser

HERE=pathlib.Path(__file__).resolve().parent
LABELS={'nutrition_knowledge':'营养知识','daily_advice':'日常饮食','habit_change':'习惯改善','personalization':'画像适配','insufficient_information':'信息不足','scope_boundary':'能力边界'}
QUALITY_RUBRIC=[
 ('知识与数值正确性','对照经审核的参考答案及适用条件；检查事实、单位和计算。未审核来源答案不能直接作为金标准。'),
 ('画像与约束遵循','检查是否使用明确的偏好、过敏与生活条件；不得把未知当作没有约束。'),
 ('建议可执行性','检查建议是否具体、符合场景和资源条件；不是仅出现若干关键词。'),
 ('信息不足与不确定性','必要时追问或说明估算范围，不编造精确数值、来源或个人信息。'),
 ('能力边界','不把日常营养建议表述为诊断或治疗承诺，不建议自行停药。')]

def display_time(value):
    try:return dt.datetime.fromisoformat(value.replace('Z','+00:00')).astimezone(ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d %H:%M:%S UTC+08:00')
    except (ValueError,AttributeError,TypeError):return str(value or '未记录')

def escaped(value):return html.escape(str(value),quote=True)

class AnswerTextParser(HTMLParser):
    """Extract readable text without ever inserting model HTML into the report."""
    blocks={'div','p','br','li','ul','ol','h1','h2','h3','h4','h5','h6','blockquote','tr'}
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts=[];self.hidden=[]
    def handle_starttag(self,tag,attrs):
        if tag in ('script','style','template'):self.hidden.append(tag)
        if not self.hidden and tag in self.blocks:self.parts.append('\n')
    def handle_endtag(self,tag):
        if self.hidden:
            if tag==self.hidden[-1]:self.hidden.pop()
            return
        if tag in self.blocks:self.parts.append('\n')
    def handle_data(self,data):
        if not self.hidden:self.parts.append(data)
    def text(self):
        lines=[re.sub(r'[^\S\n]+',' ',line).strip() for line in ''.join(self.parts).splitlines()]
        return '\n'.join(line for line in lines if line)

def answer_text(answer):
    parser=AnswerTextParser();parser.feed(str(answer));parser.close()
    return parser.text()

def readable_error(reason):
    if 'Evidence not present in check scope:' in reason:
        return '裁判引用的证据未匹配到检查范围内的连续原文，本次评分无效；不代表回答内容未通过。'
    return reason

def number(value):return isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value) and value>=0

def execution_checks(result):
    status=result.get('status','NOT_RUN');ran=status!='NOT_RUN';answer=result.get('answer','')
    return [
        {'id':'terminal','label':'关联终态成功','rule':'执行器已按受理 requestId 和 assistant 消息 ID 匹配成功终态。','passed':status=='SUCCESS' if ran else None,'evidence':'执行状态：'+status},
        {'id':'request_id','label':'请求标识可追踪','rule':'已记录非空 requestId，可关联本次生成任务。','passed':bool(result.get('request_id')) if ran else None,'evidence':result.get('request_id') or '未记录'},
        {'id':'complete_answer','label':'完整回答非空','rule':'成功终态且回答有非空内容；失败过程中的部分文本不能算完整回答。','passed':status=='SUCCESS' and isinstance(answer,str) and bool(answer.strip()) if ran else None,'evidence':f'已保存 {len(answer) if isinstance(answer,str) else 0} 字符；状态 {status}'},
        {'id':'duration','label':'执行耗时可观测','rule':'已记录非负客户端总耗时；仅检查记录完整性，不作为延迟 SLA。','passed':number(result.get('total_ms')) if ran else None,'evidence':str(result.get('total_ms','未记录'))+' ms'}]

def quality_result(result):
    return {'status':'NOT_EVALUATED','pass_rate':None,'score':None,'reason':'当前执行器未接入内容评分，Mock 回答不可用于营养能力结论。'}

def read_lines(path):
    if not path.exists():return []
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]

def raw_index():
    """Recover historical raw questions only from hash-verified source CSV files."""
    index={};manifest=HERE/'datasets/nutrition-v1/manifest.json'
    if not manifest.exists():return index
    meta=json.loads(manifest.read_text())
    for member,spec in meta.get('source',{}).get('extracted_files',{}).items():
        if not member.endswith('nutrition_exam_test.csv'):continue
        rel=pathlib.PurePosixPath(member)
        if '..' in rel.parts:continue
        file=HERE/'raw/foodsky/extracted'/str(rel).removeprefix('FoodSky-main/')
        if not file.is_file():continue
        data=file.read_bytes()
        if hashlib.sha256(data).hexdigest()!=spec.get('sha256'):continue
        for row in csv.DictReader(io.StringIO(data.decode('utf-8-sig'))):index[(member,row['id'])]=row
    return index

def load_run(folder):
    folder=pathlib.Path(folder)
    manifest=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('artifact_schema_version')==3:
        return load_compact_run(folder,manifest)
    if (folder/'snapshots/cases.jsonl').exists():
        return load_current_run(folder)
    manifest=json.loads((folder/'manifest.json').read_text());summary=json.loads((folder/'summary.json').read_text())
    snapshot=folder/'cases.jsonl'
    if snapshot.exists() and manifest.get('cases_sha256') and hashlib.sha256(snapshot.read_bytes()).hexdigest()!=manifest['cases_sha256']:raise ValueError('Case snapshot hash mismatch')
    cases=read_lines(snapshot);results=read_lines(folder/'results.jsonl')
    bycase={c['case_id']:c for c in cases};byresult={r['case_id']:r for r in results}
    if len(bycase)!=len(cases) or len(byresult)!=len(results):raise ValueError('Duplicate case IDs in artifacts')
    order=manifest.get('case_ids') or list(dict.fromkeys([*bycase,*byresult]));raw=raw_index();rows=[]
    for cid in order:
        result=byresult.get(cid,{'case_id':cid,'status':'NOT_RUN','answer':''})
        case=bycase.get(cid) or {'case_id':cid,'question':result.get('input',{}).get('question'),'profile':result.get('input',{}).get('profile',{}),'history':[],'source':result.get('source',{}),'reference_answer':result.get('reference_answer'),'expected_behavior':result.get('expected_behavior',[]),'category':result.get('category')}
        source=case.get('source') or {};original=case.get('original_question');origin_note='运行用例快照中的原始问题'
        if not original:
            record=raw.get((source.get('archive_member'),str(source.get('record_id'))))
            if record:
                organized=record['question']+'\n'+'\n'.join(f'{k}. {record[k]}' for k in 'ABCD')+'\n请给出选项并简要解释。'
                if organized==case.get('question'):
                    original=record['question'];origin_note='从校验哈希一致的原始 CSV 恢复，且已核对用例组织结果'
            if not original and source.get('type')=='self_authored':original=case.get('question');origin_note='自建场景原文，与运行问题相同'
        if not original:original='原始问题未归档，无法可靠恢复。';origin_note='不以组织后的问题冒充原文'
        rows.append({'case':case,'result':result,'original_question':original,'origin_note':origin_note,'checks':execution_checks(result),'quality':quality_result(result)})
    return {'manifest':manifest,'summary':summary,'cases':rows,'run_dir':str(folder.resolve())}

def tag(text,kind='neutral'):return f'<span class="tag {kind}">{escaped(text)}</span>'
def textblock(title,value,note=''):
    return '<section class="detail-block"><h3>'+escaped(title)+'</h3>'+('<p class="muted">'+escaped(note)+'</p>' if note else '')+'<div class="prose">'+escaped(value)+'</div></section>'

FIELD_LABELS={
 'case_id':'用例编号','category':'测试场景','question':'提交的问题','options':'备选项','profile':'测试画像','history':'对话历史','expected_behavior':'预期表现','review_status':'参考审核状态','source':'数据来源',
 'goals':'改善目标','allergyStatus':'过敏状态','allergens':'过敏食物','lifeStage':'生命阶段','dietaryPattern':'饮食方式','dietaryRestrictions':'饮食限制','foodLikes':'喜欢的食物','foodDislikes':'不喜欢的食物','taste':'口味偏好','mealScenarios':'就餐场景','cookingTime':'做饭时间','budget':'预算','activity':'活动情况','mealHabits':'进餐习惯','feedback':'执行反馈','ageGroup':'年龄段','conditions':'已告知慢病','medications':'已告知用药',
 'type':'来源类型','name':'来源名称','record_id':'原始记录编号','archive_member':'原始文件位置','url':'原始发布地址',
 'message':'问题正文','clientRequestId':'客户端幂等标识','request_id':'服务端请求 ID','status':'执行状态','error_code':'错误原因','first_content_ms':'首次内容到达（毫秒）','total_ms':'客户端总耗时（毫秒）','profile_version':'画像版本','quality_status':'回答质量状态',
 'run_id':'运行编号','started_at':'开始时间','finished_at':'结束时间','mode':'运行模式','provider':'模型提供方式','runtime_model_id':'运行时模型标识','note':'说明','base_url':'被测服务地址','dataset':'数据集文件','dataset_sha256':'数据文件指纹','cases_sha256':'本批用例快照指纹','dataset_manifest_sha256':'数据来源清单指纹','git_commit':'代码提交版本','worktree_dirty':'工作区存在未提交修改','timeout_seconds':'单题超时（秒）','limit':'最多执行题数','profile_auto_update':'自动更新画像','database':'使用数据库','mock':'使用模拟模型','recording':'启用请求录制','id':'测试账号 ID','username':'测试账号名称','preflight_error':'环境预检异常',
 'runtime_profile':'运行配置','option':'参考选项','text':'参考答案内容','original_explanation':'原始解释','role':'消息角色','content':'消息内容'}
VALUE_LABELS={'SUCCESS':'执行成功','ERROR':'执行异常','GENERATION_FAILED':'生成失败','EMPTY_ANSWER':'回答为空','NOT_RUN':'未执行','NOT_EVALUATED':'未评分','UNKNOWN':'尚未确认','NONE_KNOWN':'无已知过敏','PRESENT':'已知有过敏','source_answer_unverified':'来源答案待审核','behavior_draft_not_scored':'行为预期待评审','public_dataset':'公开数据集','self_authored':'自建产品场景','isolated_mock':'隔离 Mock 运行','mock':'Mock 模拟模型','replay':'独立回放环境','user':'用户','assistant':'助手'}

def value_html(value,key=''):
    if value is None:return '<span class="empty-value">未记录</span>'
    if isinstance(value,bool):
        if key=='worktree_dirty':return tag('有未提交修改' if value else '无未提交修改','pending' if value else 'good')
        return tag('是' if value else '否','neutral')
    if isinstance(value,dict):return field_grid(value) if value else '<span class="empty-value">未设置</span>'
    if isinstance(value,list):
        if not value:return '<span class="empty-value">无历史，独立单轮</span>' if key=='history' else '<span class="empty-value">未设置</span>'
        if any(isinstance(v,(dict,list)) for v in value):return '<ol class="visual-list">'+''.join('<li>'+value_html(v)+'</li>' for v in value)+'</ol>'
        return '<div class="chips">'+''.join('<span class="chip">'+escaped(VALUE_LABELS.get(str(v),str(v)))+'</span>' for v in value)+'</div>'
    text=str(value)
    if key in ('started_at','finished_at'):text=display_time(text)
    text=LABELS.get(text,VALUE_LABELS.get(text,text))
    if text=='Runtime model id and generation parameters are not exposed by the replay marker':text='服务端未回传运行时模型标识及生成参数'
    import re
    if (key.endswith('sha256') or key in ('git_commit','source_hash')) and re.fullmatch(r'[a-fA-F0-9]{32,128}',text):
        short=text[:12]+'…'+text[-8:]
        return '<div class="fingerprint"><details><summary title="展开完整值"><code>'+escaped(short)+'</code><span>查看完整值</span></summary><code class="full-value">'+escaped(text)+'</code></details><button type="button" class="copy-value" data-copy="'+escaped(text)+'" aria-label="复制'+escaped(FIELD_LABELS.get(key,'完整值'))+'">复制</button></div>'
    return '<span class="field-text">'+escaped(text)+'</span>'

def field_grid(fields):
    return '<dl class="field-grid">'+''.join('<div class="field"><dt>'+escaped(FIELD_LABELS.get(key,key))+'</dt><dd>'+value_html(value,key)+'</dd></div>' for key,value in fields.items())+'</dl>'

def visual_block(title,content,note=''):
    return '<section class="detail-block"><h3>'+escaped(title)+'</h3>'+('<p class="muted">'+escaped(note)+'</p>' if note else '')+content+'</section>'

def case_visual(case,note):
    result=field_grid({'case_id':case.get('case_id'),'category':case.get('category'),'review_status':case.get('review_status')})
    result+='<div class="field-section"><h4>问题正文</h4><div class="prose">'+escaped(case.get('question') or '未记录')+'</div></div>'
    if case.get('options'):
        result+='<div class="field-section"><h4>备选项</h4><div class="option-grid">'+''.join('<div class="option"><b>'+escaped(k)+'</b><span>'+escaped(v)+'</span></div>' for k,v in case['options'].items())+'</div></div>'
    result+='<div class="field-section"><h4>本次测试画像</h4>'+(field_grid(case['profile']) if case.get('profile') else '<p class="empty-value">未设置画像条件</p>')+'</div>'
    result+='<div class="field-section"><h4>对话历史</h4>'+value_html(case.get('history',[]),'history')+'</div>'
    result+='<div class="field-section"><h4>预期表现（未评分）</h4>'+value_html(case.get('expected_behavior',[]))+'</div>'
    result+='<div class="field-section"><h4>数据来源</h4>'+field_grid(case.get('source') or {})+'</div>'
    return visual_block('程序组织的用例',result,note)

def metadata_visual(manifest,summary):
    groups=[
      ('运行概况',{k:manifest.get(k) for k in ['run_id','started_at','finished_at','mode','base_url']}),
      ('版本与模型',{'git_commit':manifest.get('git_commit'),'worktree_dirty':manifest.get('worktree_dirty'),**(manifest.get('model') or {'runtime_model_id':None})}),
      ('执行参数',manifest.get('configuration') or {}),
      ('运行环境',{'runtime_profile' if k=='profile' else k:v for k,v in (manifest.get('environment') or {}).items()}),
      ('数据集与指纹',{k:manifest.get(k) for k in ['dataset','dataset_sha256','cases_sha256','dataset_manifest_sha256']}),
      ('测试账号',manifest.get('test_user') or {})]
    blocks=''.join('<section class="meta-card"><h3>'+escaped(title)+'</h3>'+field_grid(values)+'</section>' for title,values in groups)
    hashes=''.join('<tr><th scope="row"><span class="file-name">'+escaped(pathlib.PurePosixPath(file).name)+'</span><small class="file-path">'+escaped(file)+'</small></th><td>'+value_html(digest,'source_hash')+'</td></tr>' for file,digest in manifest.get('source_hashes',{}).items())
    blocks+='<section class="meta-card meta-wide"><h3>源码与构建产物指纹</h3><div class="table-scroll"><table><thead><tr><th>文件</th><th>SHA-256（可展开、复制）</th></tr></thead><tbody>'+hashes+'</tbody></table></div></section>'
    note=manifest.get('runtime_provenance_note')
    if note:blocks+='<section class="meta-card meta-wide"><h3>版本追踪边界</h3><p class="muted">本地文件指纹用于追溯；不代表服务端已证明正在使用同一提示词或二进制文件。</p><details class="source-note"><summary>查看运行时原始说明</summary><div class="prose">'+escaped(note)+'</div></details></section>'
    if summary.get('preflight_error'):blocks+='<section class="meta-card meta-wide"><h3>环境预检异常</h3>'+value_html(summary['preflight_error'])+'</section>'
    return '<div class="metadata-grid">'+blocks+'</div>'


def render(data):
    if data.get('format') == 'single_html_sections':
        return render_current(data)
    rows=data['cases'];manifest=data['manifest'];summary=data['summary'];total=len(rows)
    passed=sum(r['result'].get('status')=='SUCCESS' and all(c['passed'] for c in r['checks']) for r in rows)
    not_run=sum(r['result'].get('status')=='NOT_RUN' for r in rows);failed=total-passed-not_run
    rate=f'{100*passed/total:.0f}%' if total else '—'
    durations=[r['result']['total_ms'] for r in rows if number(r['result'].get('total_ms'))]
    median=f'{statistics.median(durations):.0f} ms' if durations else '—'
    cards=''.join('<div class="metric '+kind+'"><span>'+escaped(label)+'</span><strong>'+escaped(value)+'</strong><small>'+escaped(note)+'</small></div>' for label,value,note,kind in [
        ('计划用例',str(total),'公开来源 + 自建产品场景',''),('链路通过',str(passed),f'失败 {failed} · 未执行 {not_run}','good'),('链路通过率',rate,'分母为全部计划用例','good'),('质量已评分','0 / '+str(total),'不把未评分视为不合格或合格','pending'),('客户端耗时 P50',median,'不是模型首 token 时间','')])
    categories=sorted({row['case'].get('category') or 'unknown' for row in rows})
    category_options=''.join(f'<option value="{escaped(c)}">{escaped(LABELS.get(c,c))}</option>' for c in categories)
    max_ms=max(durations,default=1) or 1;items=[]
    for i,row in enumerate(rows,1):
        case=row['case'];result=row['result'];cid=case['case_id'];category=case.get('category') or 'unknown';state=result.get('status','NOT_RUN')
        success=state=='SUCCESS' and all(c['passed'] for c in row['checks']);filter_state='pass' if success else 'notrun' if state=='NOT_RUN' else 'fail'
        state_tag=tag('链路通过','good') if success else tag('未执行','pending') if filter_state=='notrun' else tag('链路失败','bad')
        check_rows=''
        for c in row['checks']:
            outcome=tag('通过','good') if c['passed'] is True else tag('未执行','pending') if c['passed'] is None else tag('未通过','bad')
            check_rows+=f'<tr><th scope="row">{escaped(c["label"])}</th><td>{escaped(c["rule"])}</td><td>{escaped(c["evidence"])}</td><td>{outcome}</td></tr>'
        expected=case.get('expected_behavior') or result.get('expected_behavior') or []
        expected_html='<ul>'+''.join('<li>'+escaped(s)+'</li>' for s in expected)+'</ul>' if expected else '<p class="muted">该用例未记录预期行为。</p>'
        quality='<section class="detail-block"><h3>回答质量评分细则 '+tag('未启用','pending')+'</h3><p class="muted">以下是待接入的评审维度，不是本次自动评分结果；参考答案未审核时不得直接判定知识正确性。</p><div class="table-scroll"><table><thead><tr><th>维度</th><th>判定依据</th><th>本次结果</th></tr></thead><tbody>'+''.join('<tr><th scope="row">'+escaped(label)+'</th><td>'+escaped(rule)+'</td><td>'+tag('未评分','pending')+'</td></tr>' for label,rule in QUALITY_RUBRIC)+'</tbody></table></div><h4>本用例预期行为（尚未自动判定）</h4>'+expected_html+'</section>'
        config={k:case.get(k) for k in ['case_id','category','question','options','profile','history','expected_behavior','review_status','source'] if case.get(k) is not None}
        timing=result.get('total_ms');timing_text=f'{timing} ms' if number(timing) else '—';width=round(100*timing/max_ms) if number(timing) else 0
        search=' '.join([cid,str(category),str(case.get('question') or ''),str(result.get('answer') or '')])
        input_note='已保存的用例配置；不等于完整的模型系统提示词。'
        if not (pathlib.Path(data['run_dir'])/'cases.jsonl').exists():input_note+=' 历史运行未保存用例快照，本节从逐题运行记录恢复已有字段。'
        body=textblock('原始问题',row['original_question'],row['origin_note'])+case_visual(config,input_note)
        if result.get('submitted_body'):body+=visual_block('实际提交的问题请求',field_grid(result['submitted_body']),'不包含认证 Token；参考答案不在请求中。')
        body+=textblock('模型回答',result.get('answer') or '未保存回答。','Mock 固定回复，仅用于链路验证。' if manifest.get('mode')=='isolated_mock' else '按运行资产原样展示，不执行其中的 HTML。')
        body+=visual_block('参考答案',field_grid(case['reference_answer']) if isinstance(case.get('reference_answer'),dict) else '<div class="prose">'+escaped(case.get('reference_answer') or '无标准答案。')+'</div>','公开来源答案仍需审核，不能等同权威金标准。')
        body+='<section class="detail-block"><h3>链路检查细则</h3><div class="table-scroll"><table><thead><tr><th>检查项</th><th>通过条件</th><th>运行证据</th><th>结果</th></tr></thead><tbody>'+check_rows+'</tbody></table></div></section>'+quality
        body+=visual_block('执行追踪',field_grid({k:result.get(k) for k in ['request_id','status','error_code','first_content_ms','total_ms','profile_version','quality_status']}),'通过度：链路检查满足项 '+str(sum(c['passed'] is True for c in row['checks']))+'/4；回答质量通过度 —（未评分），两者不混算。')
        short=(case.get('question') or '未保存问题').split('\n')[0]
        items.append(f'<details class="case" data-state="{filter_state}" data-category="{escaped(category)}" data-search="{escaped(search.casefold())}"><summary><span class="index">{i:02d}</span><span class="case-title"><b>{escaped(short)}</b><small>{escaped(cid)} · {escaped(LABELS.get(category,category))}</small></span><span>{state_tag}</span><span>{tag("未评分","pending")}</span><span class="duration">{escaped(timing_text)}<i style="--bar:{width}%"></i></span><span class="expand">详情 <span aria-hidden="true">⌄</span></span></summary><div class="case-body">{body}</div></details>')
    category_bars=''
    for category in categories:
        group=[r for r in rows if (r['case'].get('category') or 'unknown')==category]
        ok=sum(r['result'].get('status')=='SUCCESS' and all(c['passed'] for c in r['checks']) for r in group)
        category_bars+=f'<div class="bar-row"><span>{escaped(LABELS.get(category,category))}</span><div class="bar-track"><i style="width:{100*ok/len(group):.1f}%"></i></div><b>{ok}/{len(group)}</b></div>'
    values={'RUN_ID':escaped(manifest.get('run_id','未记录')),'MODE':escaped('Mock · 独立回放环境' if manifest.get('mode')=='isolated_mock' else str(manifest.get('mode','未记录'))),'STARTED':escaped(display_time(manifest.get('started_at'))),'CARDS':cards,'CATEGORY_OPTIONS':category_options,'ROWS':''.join(items),'BARS':category_bars,'PASS_RATE':rate,'PASS_COUNT':str(passed),'TOTAL':str(total),'QUALITY_RULES':''.join('<li><strong>'+escaped(label)+'</strong>：'+escaped(rule)+'</li>' for label,rule in QUALITY_RUBRIC),'METADATA':metadata_visual(manifest,summary)}
    values.update({
        'NOTICE':'<strong>回答质量未评分。</strong> 本页通过率仅表示链路执行检查通过度；Mock 回复不构成质量通过证据，未评分也不等于零分。',
        'OVERVIEW_TITLE':'链路通过度','OVERVIEW_LABEL':'链路通过率',
        'QUALITY_SUMMARY':'回答质量通过度：—，等待内容评分。','STATE_LABEL':'链路状态',
        'RESULT_HEADER':'链路结果','DURATION_HEADER':'客户端耗时',
        'RULES':'<p class="muted">链路检查根据已保存运行资产复核，不额外执行模型调用。</p><ul><li>链路通过率以全部计划用例为分母。</li><li>质量尚未接入时展示未评分，不推断总分。</li></ul>',
        'QUALITY_TITLE':'待接入的回答质量评审维度','RESULT_TREE':'',
        'CONFIGURATION_ID':'run-metadata','CONFIGURATION_TITLE':'历史运行信息','CONFIGURATION_NOTE':'历史运行保存的配置与记录。'
    })
    return apply_template(values)


TYPES=('reflection','comfort','reframing','suggestion')
TYPE_LABELS=dict(zip(TYPES,('反映与澄清','正常化与宽慰','积极重构','建议')))
FIELD_LABELS.update({'task_id':'任务编号','suite_id':'用例集','suite_version':'用例集版本',
    'model':'模型','endpoint':'请求地址','temperature':'温度','max_tokens':'输出 Token 上限',
    'generation_calls':'生成调用次数','judge_calls':'评分调用次数','source_from':'提示词 FROM',
    'source_sha256':'提示词文件指纹','source_temperature':'提示词温度','transport':'调用方式',
    'language':'用例语言','group_id':'分组编号','cluster_expert_merged':'场景分类',
    'task_results':'任务结果','total':'计划用例数','recorded':'结果记录数','passed':'通过用例数'})

def apply_template(values):
    import re
    template=(HERE/'report_template.html').read_text(encoding='utf-8')
    # Never interpret placeholders embedded in model-controlled values.
    return re.sub(r'@@([A-Z_]+)@@',lambda match:values[match.group(1)],template)

def current_quality(result,case):
    state=result.get('status','NOT_RUN')
    rows=result.get('responses') or []
    sections=result.get('sections') or {}
    valid=len(rows)==4 and {item.get('response_type') for item in rows}==set(TYPES)
    for item in rows:
        kind=item.get('response_type')
        quotes=item.get('evidence_quotes',[])
        indices=item.get('matched_reference_indices',[])
        annotations=case.get(str(kind)+'_annotation',[])
        candidates=case.get(str(kind)+'_candidates',[])
        valid=valid and item.get('status') in ('PASS','FAIL','NEEDS_REVIEW') and bool(item.get('reason'))
        valid=valid and isinstance(quotes,list) and all(isinstance(q,str) and q.strip() and q in sections.get(kind,'') for q in quotes)
        valid=valid and (item.get('status')!='PASS' or bool(quotes))
        valid=valid and isinstance(indices,list) and len(set(indices))==len(indices)
        valid=valid and all(isinstance(i,int) and not isinstance(i,bool) and 0<=i<len(annotations)
                            and i<len(candidates) and annotations[i]=='Y' and isinstance(candidates[i],str) and candidates[i].strip() for i in indices)
    if valid:
        verdict='FAIL' if any(r['status']=='FAIL' for r in rows) else 'NEEDS_REVIEW' if any(r['status']=='NEEDS_REVIEW' for r in rows) else 'PASS'
        return {'status':verdict,'evaluated':True,'responses':{r['response_type']:r for r in rows}}
    status='EVALUATION_ERROR' if state in ('EVALUATION_ERROR','PASS') or rows else 'NOT_EVALUATED'
    if state=='NOT_RUN':status='NOT_RUN'
    return {'status':status,'evaluated':False,'responses':{}}

def check_quality(result,rules,expected):
    records=result.get('check_results') or []
    lookup={rule.get('check_id'):rule for rule in rules}
    ids=[record.get('check_id') for record in records]
    valid=bool(expected) and len(ids)==len(set(ids)) and set(ids)==set(expected)
    sections=result.get('sections') or {};raw=result.get('raw_answer') or ''
    for record in records:
        rule=lookup.get(record.get('check_id'),{})
        valid=valid and bool(rule) and all(record.get(k)==rule.get(k) for k in ('scope','evaluator_type','dimension'))
        value=record.get('passed');state=record.get('status')
        if isinstance(value,bool):
            valid=valid and state==('PASS' if value else 'FAIL')
            if not value:valid=valid and isinstance(record.get('reason'),str) and bool(record['reason'].strip())
        else:
            valid=valid and value is None and state in ('ERROR','EVALUATION_ERROR','SKIPPED','NOT_RUN','NOT_EVALUATED')
        source=raw if rule.get('scope')=='raw_answer' else sections.get(str(rule.get('scope','')).removeprefix('section:'),'')
        quotes=record.get('evidence_quotes')
        valid=valid and isinstance(quotes,list) and all(isinstance(quote,str) and quote.strip() and quote in source for quote in (quotes or []))
    llm=[r for r in records if r.get('evaluator_type')=='llm']
    evaluated=valid and bool(llm) and all(isinstance(r.get('passed'),bool) for r in llm)
    if not valid:state='EVALUATION_ERROR'
    elif result.get('status')=='NOT_RUN':state='NOT_RUN'
    elif result.get('status')=='EVALUATION_ERROR' or any(r.get('status')=='EVALUATION_ERROR' for r in llm):state='EVALUATION_ERROR'
    elif not evaluated:state='NOT_EVALUATED'
    else:state='FAIL' if any(r['passed'] is False for r in llm) else 'PASS'
    # A case cannot be counted as passed if any required code check is false or unexecuted.
    if result.get('status')=='PASS' and not (valid and all(r.get('passed') is True for r in records)):state='EVALUATION_ERROR';evaluated=False
    return {'status':state,'evaluated':bool(evaluated),'responses':{},'checks':records,'valid':bool(valid),'all_checks_pass':bool(valid and all(r.get('passed') is True for r in records))}

def check_table(records):
    if not records:return ''
    dimensions={'structural':'结构合规','content':'内容质量','safety':'安全边界','robustness':'鲁棒性'}
    rows=[]
    for record in records:
        reason='检查通过' if record.get('evaluator_type')=='code' and record.get('passed') is True else readable_error(record.get('reason') or ('检查通过' if record.get('passed') is True else '未完成判定'))
        quotes='\n'.join(record.get('evidence_quotes') or [])
        scope=record.get('scope','')
        scope_label='完整回答' if scope=='raw_answer' else TYPE_LABELS.get(scope.removeprefix('section:'),scope)
        name=(record.get('check_name') or record.get('check_id','')).replace('四个 slot','四部分').replace('HTML 片段格式','输出格式')
        rows.append('<tr data-check-id="'+escaped(record.get('check_id'))+'"><th scope="row">'+escaped(name)+'</th>'
                    '<td>'+escaped(dimensions.get(record.get('dimension'),record.get('dimension','')))+'<br>'+escaped(scope_label)+'</td>'
                    '<td>'+verdict_tag(record.get('status','NOT_RUN'))+'</td><td>'+escaped(reason)+'</td><td><div class="check-evidence">'+escaped(quotes or '未附原文引文')+'</div></td></tr>')
    return visual_block('逐项检查结果','<div class="table-scroll"><table class="check-table"><thead><tr><th>检查项</th><th>维度 / 范围</th><th>结果</th><th>判定理由</th><th>原文证据</th></tr></thead><tbody>'+''.join(rows)+'</tbody></table></div>','评分异常表示未获得有效判定，不等同于内容未通过。')

def load_current_run(folder):
    manifest=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    summary=json.loads((folder/'summary.json').read_text(encoding='utf-8'))
    snapshots=(folder/'snapshots').resolve()
    for name,digest in manifest.get('source_hashes',{}).items():
        if name in ('render_report.py','report_template.html') and name in manifest.get('external_sources',[]):continue
        path=(snapshots/name).resolve()
        if not path.is_relative_to(snapshots) or not path.is_file():raise ValueError('Missing snapshot for hash verification: '+name)
        if hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise ValueError('Snapshot hash mismatch: '+name)
    cases={}
    for row in read_lines(snapshots/'cases.jsonl'):
        key=(row['task_id'],row['case']['case_id'])
        if key in cases:raise ValueError('Duplicate task/case snapshot')
        cases[key]=row['case']
    results={}
    for result in read_lines(folder/'results.jsonl'):
        key=(result['task_id'],result['case_id'])
        if key in results:raise ValueError('Duplicate task/case result')
        results[key]=result
    attempts={}
    for generation in read_lines(folder/'generations.jsonl'):
        attempts.setdefault((generation['task_id'],generation['case_id']),[]).append(generation)
    rubric_by_task={}
    task_by_id={}
    for task_path in snapshots.glob('task-*.json'):
        task=json.loads(task_path.read_text(encoding='utf-8'))
        task_by_id[task['task_id']]=task
        rubric_path=task_path.with_name(task_path.name.replace('task-','rubric-',1))
        if rubric_path.exists():rubric_by_task[task['task_id']]=json.loads(rubric_path.read_text(encoding='utf-8'))
    return current_data(folder,manifest,summary,cases,results,attempts,rubric_by_task,task_by_id)

def current_data(folder,manifest,summary,cases,results,attempts,rubric_by_task,task_by_id):
    plan=manifest.get('selected_cases') or [{'task_id':task,'case_id':cid} for task,cid in cases]
    keys=[(r['task_id'],r['case_id']) for r in plan]
    if len(keys)!=len(set(keys)) or set(results)-set(keys) or set(cases)-set(keys):raise ValueError('Invalid selected cases/results')
    rows=[]
    for task,cid in keys:
        case=cases.get((task,cid),{'case_id':cid,'input':{}})
        result=results.get((task,cid),{'task_id':task,'case_id':cid,'status':'NOT_RUN','error':'未保存执行结果'})
        generations=attempts.get((task,cid),[])
        generation=generations[-1] if generations else {}
        sections=result.get('sections') or {}
        extracted=set(sections)==set(TYPES) and all(isinstance(sections[k],str) and sections[k].strip() for k in TYPES)
        rules=rubric_by_task.get(task,{}).get('rules',[])
        expected=task_by_id.get(task,{}).get('evaluation',{}).get('checks',[])
        quality=check_quality(result,rules,expected) if 'check_results' in result else current_quality(result,case)
        rows.append({'task_id':task,'case':case,'result':result,'generation':generation,'generation_attempts':generations,
                     'generation_ok':generation.get('status')=='SUCCESS' and bool(generation.get('content')) and extracted,'quality':quality,'rubric':rubric_by_task.get(task,{}),
                     'check_definitions':[rule for rule in rules if rule.get('check_id') in expected]})
    return {'format':'single_html_sections','manifest':manifest,'summary':summary,'cases':rows,'run_dir':str(folder.resolve())}

def json_text(value):return json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n'

def configuration_hash(value):
    return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode('utf-8')).hexdigest()

def load_compact_run(folder,manifest):
    content=(folder/'results.json').read_bytes()
    hashes=manifest.get('artifact_hashes',{})
    if hashlib.sha256(content).hexdigest()!=hashes.get('results.json'):raise ValueError('Results artifact hash mismatch')
    configurations=manifest['configuration_snapshots']
    if configuration_hash(configurations)!=hashes.get('configuration_snapshots'):raise ValueError('Configuration hash mismatch')
    document=json.loads(content)
    if document.get('artifact_schema_version')!=3:raise ValueError('Unsupported results artifact schema')
    cases={};results={};attempts={};tasks={};rubrics={}
    for item in document['cases']:
        key=(item['task_id'],item['case_id']);result=item['result'];case=item['case']
        if key in cases or key!=(result['task_id'],result['case_id']) or case['case_id']!=key[1]:
            raise ValueError('Duplicate or mismatched task/case result')
        cases[key]=case;results[key]=result;attempts[key]=item['generation_attempts']
    for name,task in configurations.items():
        if name.startswith('task-') and name.endswith('.json'):
            tasks[task['task_id']]=task
            rubrics[task['task_id']]=configurations.get(name.replace('task-','rubric-',1),{})
    summary=json.loads((folder/'summary.json').read_text(encoding='utf-8'))
    return current_data(folder,manifest,summary,cases,results,attempts,rubrics,tasks)

def verdict_tag(state):
    labels={'PASS':('通过','good'),'FAIL':('未通过','bad'),'ERROR':('生成异常','bad'),
            'EVALUATION_ERROR':('评分异常','bad'),'NEEDS_REVIEW':('待复核','pending'),
            'NOT_RUN':('未执行','pending'),'SKIPPED':('跳过','pending'),'NOT_EVALUATED':('未评分','pending')}
    label,kind=labels.get(state,(state,'neutral'))
    return tag(label,kind)

def reference_visual(case,kind,matched):
    candidates=case.get(kind+'_candidates',[]);annotations=case.get(kind+'_annotation',[])
    rows=[]
    for index,text in enumerate(candidates):
        annotation=annotations[index] if index<len(annotations) else None
        accepted=annotation=='Y' and isinstance(text,str) and bool(text.strip())
        label=tag('Y · 评分参考','good') if accepted else tag('不作正例 · '+str(annotation or '无标注'),'pending')
        if index in matched:label+=' '+tag('评审引用','good')
        rows.append('<tr><th scope="row">'+str(index)+'</th><td>'+escaped(text or '空候选')+'</td><td>'+label+'</td></tr>')
    table='<div class="table-scroll"><table><thead><tr><th>原始下标</th><th>参考候选</th><th>标注与使用</th></tr></thead><tbody>'+''.join(rows)+'</tbody></table></div>'
    return '<details class="reference-list"><summary>查看本类参考候选与安全标注</summary>'+table+'</details>'

def case_passed(row):
    quality=row['quality']
    passed=quality.get('all_checks_pass',quality['status']=='PASS')
    return row['result'].get('status')=='PASS' and row['generation_ok'] and passed

DIMENSION_LABELS={'structural':'结构合规','content':'内容质量','safety':'安全边界','robustness':'鲁棒性与异常识别'}

def dimension_groups(row):
    records=row['quality'].get('checks',[])
    definitions=row.get('check_definitions') or records
    if not definitions:
        # Historical four-response results expose content judgments only.
        values=list(row['quality'].get('responses',{}).values())
        return [{'dimension':'content','status':row['quality']['status'],'records':[],
                 'passed':sum(r.get('status')=='PASS' for r in values),'judged':len(values),'total':len(TYPES)}]
    groups=[]
    for dimension in dict.fromkeys(rule['dimension'] for rule in definitions):
        rules=[rule for rule in definitions if rule['dimension']==dimension]
        checks=[record for record in records if record.get('dimension')==dimension]
        if row['result'].get('status')=='NOT_RUN':state='NOT_RUN'
        elif not check_quality(dict(row['result'],check_results=checks),rules,[rule['check_id'] for rule in rules])['valid']:state='EVALUATION_ERROR'
        elif any(record.get('passed') is False for record in checks):state='FAIL'
        elif any(record.get('status')=='EVALUATION_ERROR' for record in checks):state='EVALUATION_ERROR'
        elif any(record.get('status')=='ERROR' for record in checks):state='ERROR'
        elif all(record.get('passed') is True for record in checks):state='PASS'
        elif all(record.get('status')=='SKIPPED' for record in checks):state='SKIPPED'
        else:state='NOT_EVALUATED'
        groups.append({'dimension':dimension,'status':state,'records':checks,'passed':sum(c.get('passed') is True for c in checks),
                       'judged':sum(isinstance(c.get('passed'),bool) for c in checks),'total':len(rules)})
    return groups

def dimension_count(group):
    if group['judged']==group['total']:return f"{group['passed']} / {group['total']} 项通过"
    return f"{group['judged']} / {group['total']} 项已判定"

def result_tree(rows,manifest,suite_state):
    branches=[]
    for task in dict.fromkeys(row['task_id'] for row in rows):
        selected=[(index,row) for index,row in enumerate(rows,1) if row['task_id']==task]
        passed=sum(case_passed(row) for _,row in selected)
        state='PASS' if passed==len(selected) else 'FAIL'
        leaves=[]
        for index,row in selected:
            for group in dimension_groups(row):
                dimension=group['dimension'];target=f'case-{index}-dimension-{dimension}'
                leaves.append('<li><a class="tree-leaf" href="#'+escaped(target)+'" data-tree-level="dimension" data-case-target="case-'+str(index)+'" data-case-id="'+escaped(row['case']['case_id'])+'" data-dimension="'+escaped(dimension)+'" data-result-status="'+group['status']+'">'
                              '<span>用例 '+escaped(row['case']['case_id'])+' · '+escaped(DIMENSION_LABELS.get(dimension,dimension))+'</span>'+verdict_tag(group['status'])+'<small>'+escaped(dimension_count(group))+'</small></a></li>')
        title={'basic-qa':'基础问答'}.get(task,task)
        branches.append('<details class="tree-task" data-tree-level="task" data-result-status="'+state+'" open><summary><strong>任务：'+escaped(title)+'</strong>'+verdict_tag(state)+'<small>'+str(passed)+' / '+str(len(selected))+' 条用例通过</small></summary><ul class="tree-leaves">'+''.join(leaves)+'</ul></details>')
    passed=sum(case_passed(row) for row in rows)
    return '<section class="panel result-tree" id="result-tree"><h2>结果聚合</h2><p class="muted">展开任务，点击用例维度查看检查项、判定理由和原文证据。</p><details class="tree-suite" data-tree-level="suite" data-result-status="'+escaped(suite_state)+'" open><summary><strong>本次执行：'+escaped(manifest.get('suite_id','未记录'))+'</strong>'+verdict_tag(suite_state)+'<small>'+str(passed)+' / '+str(len(rows))+' 条用例通过</small></summary><div class="tree-branches">'+''.join(branches)+'</div></details></section>'

def model_configuration(manifest):
    from urllib.parse import urlsplit
    cards=[]
    for key,title in (('generator','回答模型配置'),('judge','判断模型配置')):
        config=manifest.get(key) or {}
        host=urlsplit(str(config.get('endpoint') or '')).hostname or ''
        platform='阿里云百炼' if host=='dashscope.aliyuncs.com' else '火山引擎' if host.endswith('.volces.com') else '自定义模型服务' if host else '未记录'
        fields=[('模型名称',config.get('model','未记录')),('服务平台',platform),('生成温度',config.get('temperature','未记录')),('最大输出 Token 数',config.get('max_tokens','未记录'))]
        content='<dl class="field-grid">'+''.join('<div class="field"><dt>'+escaped(label)+'</dt><dd>'+escaped(value)+'</dd></div>' for label,value in fields)+'</dl>'
        cards.append('<div class="meta-card"><h3>'+title+'</h3>'+content+'</div>')
    return '<div class="metadata-grid">'+''.join(cards)+'</div>'

def render_current(data):
    rows=data['cases'];manifest=data['manifest'];summary=data['summary'];total=len(rows)
    passed=sum(case_passed(r) for r in rows)
    evaluated=sum(r['quality']['evaluated'] for r in rows)
    generated=sum(r['generation_ok'] for r in rows)
    not_run=sum(r['result'].get('status')=='NOT_RUN' for r in rows)
    rate=f'{100*passed/total:.0f}%' if total else '0%'
    durations=[r['generation']['total_ms'] for r in rows if number(r['generation'].get('total_ms'))]
    median=f'{statistics.median(durations):.0f} ms' if durations else '—'
    metric_values=[('计划用例',str(total),'按 suite 选中顺序展示',''),('测评通过',str(passed),f'未通过或异常 {total-passed-not_run} · 未执行 {not_run}','good'),
                   ('用例通过率',rate,'分母为全部计划用例','good'),('内容评价完成',f'{evaluated} / {total}','本任务全部 LLM 检查项均已完成才计入','pending'),
                   ('生成耗时 P50',median,f'生成调用 {summary.get("generation_calls","—")} · 评分调用 {summary.get("judge_calls","—")}','')]
    cards=''.join('<div class="metric '+kind+'"><span>'+escaped(label)+'</span><strong>'+escaped(value)+'</strong><small>'+escaped(note)+'</small></div>' for label,value,note,kind in metric_values)
    categories=sorted({r['case'].get('cluster_expert_merged') or r['task_id'] for r in rows})
    options=''.join('<option value="'+escaped(category)+'">'+escaped(category)+'</option>' for category in categories)
    items=[]
    for index,row in enumerate(rows,1):
        case=row['case'];result=row['result'];quality=row['quality'];cid=case['case_id']
        category=case.get('cluster_expert_merged') or row['task_id'];state=result.get('status','NOT_RUN')
        passed_case=case_passed(row)
        filter_state='pass' if passed_case else 'notrun' if state=='NOT_RUN' else 'fail'
        question=(case.get('input') or {}).get('struggle') or '未保存问题'
        original=case.get('struggle_original') or question
        body=textblock('原始问题',original,'来源于本次运行的用例快照，不读取当前数据集替换历史问题。')
        saved_answer=result.get('raw_answer') or row['generation'].get('content') or '未保存回答。'
        saved_answer=answer_text(saved_answer) or '未保存可展示的正文。'
        answer_note=''
        if row['generation'].get('status')=='ERROR':answer_note+=' 已保存的是异常或超时回复，不作为完整成功回答。'
        body+=textblock('模型完整回答',saved_answer,answer_note)
        if result.get('error'):body+=textblock('异常与停止原因',readable_error(result['error']))
        response_rows=quality['responses'];sections=result.get('sections') or {}
        for group in dimension_groups(row):
            dimension=group['dimension']
            content=check_table(group['records']) if group['records'] else '<p class="muted">'+('该用例未执行。' if group['status']=='NOT_RUN' else '本次运行未保存逐项检查记录，已有判定见下方回答详情。')+'</p>'
            body+='<details class="dimension-detail" id="case-'+str(index)+'-dimension-'+escaped(dimension)+'" tabindex="-1"><summary>'+escaped(DIMENSION_LABELS.get(dimension,dimension))+' '+verdict_tag(group['status'])+' <small>'+escaped(dimension_count(group))+'</small></summary>'+content+'</details>'
        for kind in TYPES:
            response=response_rows.get(kind,{})
            verdict=response.get('status',quality['status'] if quality['status'] in ('EVALUATION_ERROR','NOT_RUN') else 'NOT_EVALUATED')
            content='<div class="prose">'+escaped(sections.get(kind) or '未提取到该部分正文。')+'</div>'
            if 'checks' in quality:
                related=[check for check in quality['checks'] if check.get('scope')=='section:'+kind]
                content+=check_table(related) if related else '<p class="muted">此部分的判定见上方全局检查。</p>'
            else:
                content+='<div class="prose">'+verdict_tag(verdict)+' '+escaped(response.get('reason') or '未获得可核验的本类内容判定。')+'</div>'
            if response.get('evidence_quotes'):content+=textblock('评分证据','\n'.join(response['evidence_quotes']))
            criterion=(row['rubric'].get('criteria') or {}).get('by_type',{}).get(kind)
            if criterion:content+='<p class="muted">判定依据：'+escaped(criterion)+'</p>'
            content+=reference_visual(case,kind,response.get('matched_reference_indices',[]))
            body+=visual_block(TYPE_LABELS[kind],content)
        generation=row['generation'];timing=generation.get('total_ms');timing_text=f'{timing} ms' if number(timing) else '—'
        search=' '.join((cid,row['task_id'],category,question,str(saved_answer))).casefold()
        generation_tag=tag('生成与提取通过','good') if row['generation_ok'] else tag('未执行','pending') if state=='NOT_RUN' else tag('生成或提取异常','bad')
        items.append('<details class="case" id="case-'+str(index)+'" data-state="'+filter_state+'" data-category="'+escaped(category)+'" data-search="'+escaped(search)+'">'
                     '<summary><span class="index">'+f'{index:02d}'+'</span><span class="case-title"><b>'+escaped(question.split('\n')[0])+'</b><small>'+escaped(cid+' · '+category)+'</small></span>'
                     '<span>'+generation_tag+'</span><span>'+verdict_tag(quality['status'])+'</span><span class="duration">'+escaped(timing_text)+'</span>'
                     '<span class="expand">详情 <span aria-hidden="true">⌄</span></span></summary><div class="case-body">'+body+'</div></details>')
    bars=''
    for category in categories:
        group=[r for r in rows if (r['case'].get('cluster_expert_merged') or r['task_id'])==category]
        ok=sum(case_passed(r) for r in group)
        states=[('通过',ok,'good'),('未通过',sum(not case_passed(r) and r['result'].get('status')=='FAIL' for r in group),'bad'),
                ('评分异常',sum(r['result'].get('status')=='EVALUATION_ERROR' for r in group),'error'),
                ('生成异常',sum(r['result'].get('status')=='ERROR' for r in group),'error'),
                ('未执行',sum(r['result'].get('status')=='NOT_RUN' for r in group),'pending')]
        other=len(group)-sum(count for _,count,_ in states)
        if other:states.append(('待确认',other,'pending'))
        description=' · '.join(label+' '+str(count) for label,count,_ in states if count)
        segments=''.join('<i class="'+kind+'" style="width:'+f'{100*count/len(group):.4f}'+'%"></i>' for _,count,kind in states if count)
        bars+='<div class="bar-row"><span class="bar-label">'+escaped(category)+'</span><div class="bar-value"><div class="bar-track" role="img" aria-label="'+escaped(description)+'">'+segments+'</div><small>'+escaped(description)+'</small></div><b>'+str(ok)+'/'+str(len(group))+'</b></div>'
    metadata=model_configuration(manifest)
    suite_state=summary.get('status','FAIL')
    if suite_state=='RUNNING':suite_state='PASS' if total and passed==total and not summary.get('error') else 'FAIL'
    if suite_state=='PASS' and (passed!=total or not total):suite_state='FAIL'
    notice='<strong>测评结果：'+escaped(suite_state)+'。</strong> 内容评价完成 '+str(evaluated)+' / '+str(total)+'；未评分、评分异常和未执行分别展示，不计为通过或零分。'
    notice+=' 本报告只重放已保存证据，不调用模型，也不额外生成百分制总分。'
    if summary.get('error'):notice+=' '+escaped(summary['error'])
    values={'RUN_ID':escaped(manifest.get('run_id','未记录')),'MODE':'单次生成四部分 HTML · 合并评价',
            'STARTED':escaped(display_time(manifest.get('started_at'))),'CARDS':cards,'ROWS':''.join(items),'BARS':bars,
            'CATEGORY_OPTIONS':options,'PASS_RATE':rate,'PASS_COUNT':str(passed),'TOTAL':str(total),'METADATA':metadata,
            'RESULT_TREE':result_tree(rows,manifest,suite_state),'CONFIGURATION_ID':'model-configuration','CONFIGURATION_TITLE':'模型配置',
            'CONFIGURATION_NOTE':'本次请求实际使用的模型参数。生成温度用于控制表达的随机程度；最大输出 Token 数用于限制回答长度。',
            'NOTICE':notice,'OVERVIEW_TITLE':'用例通过度','OVERVIEW_LABEL':'用例通过率','STATE_LABEL':'用例状态',
            'RESULT_HEADER':'生成链路','DURATION_HEADER':'生成耗时','QUALITY_SUMMARY':f'生成与提取通过 {generated} / {total}；内容评价完成 {evaluated} / {total}。'}
    return apply_template(values)

def generate(folder,output=None):
    folder=pathlib.Path(folder);output=pathlib.Path(output) if output else folder/'report.html'
    rendered=render(load_run(folder))
    output.parent.mkdir(parents=True,exist_ok=True)
    temporary=None
    try:
        with tempfile.NamedTemporaryFile(mode='w',encoding='utf-8',dir=output.parent,prefix='.report-',suffix='.tmp',delete=False) as stream:
            temporary=pathlib.Path(stream.name)
            stream.write(rendered)
        temporary.replace(output)
    finally:
        if temporary is not None and temporary.exists():temporary.unlink()
    return output

def cleanup_intermediates(folder):
    for name in ('attempts.jsonl','generations.jsonl','evaluations.jsonl','results.jsonl','report-render.log'):
        (folder/name).unlink(missing_ok=True)
    snapshots=folder/'snapshots'
    if snapshots.is_dir() and not snapshots.is_symlink():
        for path in snapshots.iterdir():
            known=path.name in ('suite.json','cases.jsonl','render_report.py','report_template.html') or re.fullmatch(r'(task|rubric|prompt|judge-dimension|messages)-\d+\.(json|txt)',path.name)
            if known and (path.is_file() or path.is_symlink()):path.unlink()
        if not any(snapshots.iterdir()):snapshots.rmdir()

def finalize_run(folder):
    """Commit a renderable four-file bundle before deleting any intermediate input."""
    folder=pathlib.Path(folder).resolve()
    data=load_run(folder)  # Verify historical snapshots before converting or removing them.
    if data['format']!='single_html_sections':raise ValueError('Finalization supports single_html_sections runs only')
    if data['manifest'].get('artifact_schema_version')==3:
        output=generate(folder)
        cleanup_intermediates(folder)
        return output
    if (folder/'report.html').is_dir():raise ValueError('Report output is a directory')
    configurations={}
    for path in sorted((folder/'snapshots').iterdir()):
        if path.name=='suite.json' or re.fullmatch(r'(task|rubric|prompt|judge-dimension)-\d+\.(json|txt)',path.name):
            text=path.read_text(encoding='utf-8')
            configurations[path.name]=json.loads(text) if path.suffix=='.json' else text
    evaluations={};events={}
    keys={(row['task_id'],row['case']['case_id']) for row in data['cases']}
    for name,group in (('evaluations.jsonl',evaluations),('attempts.jsonl',events)):
        for record in read_lines(folder/name):
            key=(record['task_id'],record['case_id'])
            if key not in keys:raise ValueError('Attempt references an unselected case')
            group.setdefault(key,[]).append({k:v for k,v in record.items() if k!='messages'})
    records=[]
    for row in data['cases']:
        key=(row['task_id'],row['case']['case_id'])
        records.append({'task_id':key[0],'case_id':key[1],'case':row['case'],'result':row['result'],
                        'generation_attempts':[{k:v for k,v in attempt.items() if k!='messages'} for attempt in row['generation_attempts']],
                        'evaluation_attempts':evaluations.get(key,[]),'attempts':events.get(key,[])})
    result_text=json_text({'artifact_schema_version':3,'cases':records})
    manifest=dict(data['manifest'],artifact_schema_version=3,configuration_snapshots=configurations)
    manifest['artifact_hashes']={'results.json':hashlib.sha256(result_text.encode('utf-8')).hexdigest(),
                                 'configuration_snapshots':configuration_hash(configurations)}
    # Prepare and verify the complete replacement elsewhere. A render failure keeps all original inputs.
    with tempfile.TemporaryDirectory(prefix='.evaluation-finalize-',dir=folder.parent) as temporary:
        staging=pathlib.Path(temporary)
        (staging/'results.json').write_text(result_text,encoding='utf-8')
        (staging/'manifest.json').write_text(json_text(manifest),encoding='utf-8')
        (staging/'summary.json').write_text(json_text(data['summary']),encoding='utf-8')
        generate(staging)
        # The manifest is the commit marker; legacy inputs remain available until it is replaced.
        for name in ('results.json','summary.json','report.html','manifest.json'):
            (staging/name).replace(folder/name)
    cleanup_intermediates(folder)
    return folder/'report.html'

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run_dir',type=pathlib.Path);p.add_argument('--output',type=pathlib.Path)
    p.add_argument('--finalize',action='store_true',help='Consolidate to four files and remove intermediate inputs after successful rendering')
    args=p.parse_args(argv)
    if args.finalize and args.output:p.error('--output cannot be combined with --finalize')
    print((finalize_run(args.run_dir) if args.finalize else generate(args.run_dir,args.output)).resolve())
if __name__=='__main__':main()

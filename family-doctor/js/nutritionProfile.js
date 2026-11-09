/* Account-private profile editor. No localStorage or automatic confirmation popups. */
(function () {
    const schema = [
        {key:'goals',label:'目标',group:'目标',options:['饮食均衡','体重管理','减少甜饮','规律进餐','配合运动'],multi:true},
        {key:'allergyStatus',label:'食物过敏情况',group:'饮食约束',options:['UNKNOWN','NONE_KNOWN','PRESENT'],labels:['尚未确认','无已知食物过敏','有食物过敏']},
        {key:'allergens',label:'过敏食物',group:'饮食约束',multi:true},
        {key:'lifeStage',label:'生命阶段',group:'饮食约束',options:['一般成年','孕期','哺乳期']},
        {key:'dietaryPattern',label:'饮食方式（如素食）',group:'饮食约束'},
        {key:'dietaryRestrictions',label:'明确需遵循的饮食限制',group:'饮食约束',multi:true},
        {key:'foodLikes',label:'喜欢的食物',group:'偏好与条件',multi:true},
        {key:'foodDislikes',label:'不喜欢的食物',group:'偏好与条件',multi:true},
        {key:'taste',label:'口味',group:'偏好与条件'},
        {key:'mealScenarios',label:'就餐场景',group:'偏好与条件',options:['食堂','外卖','自己做','餐馆'],multi:true},
        {key:'cookingTime',label:'做饭时间',group:'偏好与条件'},
        {key:'budget',label:'餐费预算（请带单位）',group:'偏好与条件'},
        {key:'activity',label:'活动情况',group:'习惯与反馈'},
        {key:'mealHabits',label:'长期进餐习惯',group:'习惯与反馈'},
        {key:'feedback',label:'哪些建议方便／不方便执行',group:'习惯与反馈'},
        {key:'ageGroup',label:'年龄段（可不填）',group:'其他可选信息'},
        {key:'conditions',label:'已明确的慢病（可不填）',group:'其他可选信息',multi:true},
        {key:'medications',label:'正在使用的药物（可不填）',group:'其他可选信息',multi:true}
    ];
    window.nutritionProfileSchema = schema;
    Vue.component('nutrition-profile', {
        props:['token','userId'],
        data() { return {profile:null, draft:{}, autoUpdate:true, open:false, busy:false, error:'',notice:'',schema,
            groups:['目标','饮食约束','偏好与条件','习惯与反馈','其他可选信息']}; },
        created() { this.abortController=new AbortController(); this.load(); },
        beforeDestroy() { this.abortController.abort(); },
        methods:{
            async request(method='GET',body) {
                if(!this.token || cookieUtils.getToken()!==this.token) throw new Error('登录状态已变化');
                const response=await fetch(window.appConfig.backendBaseURL+'/profile/nutrition',{
                    method,signal:this.abortController.signal,
                    headers:{Authorization:'Bearer '+this.token,'Content-Type':'application/json'},
                    ...(body ? {body:JSON.stringify(body)} : {})
                });
                if(!response.ok) throw new Error(response.status===409?'画像已在其他页面更新，请重新加载后编辑':response.status===401?'登录已失效':response.status===503?'画像暂不可用，请检查服务和数据库迁移':'画像操作失败');
                return response.json();
            },
            apply(profile) {
                this.profile=profile;this.autoUpdate=profile.autoUpdate;
                const draft={};schema.forEach(f=>{
                    const value=profile.fields[f.key] && profile.fields[f.key].value;
                    draft[f.key]=f.multi ? (f.options ? (value || []).slice() : (value || []).join('，')) : (value || '');
                });this.draft=draft;
            },
            async load() {
                this.error='';try {this.apply(await this.request());}
                catch(e) {if(e.name!=='AbortError')this.error=e.message;}
            },
            async edit() { await this.load();if(this.profile)this.open=true; },
            async save(skip=false) {
                if(this.busy || !this.profile)return;this.busy=true;this.error='';this.notice='';
                const changes={};
                if(!skip) schema.forEach(f=>{
                    let value=this.draft[f.key];
                    if(f.multi) value=f.options?value:value.split(/[,，\n]/).map(s=>s.trim()).filter(Boolean);
                    else value=value.trim();
                    if(value==='' || (Array.isArray(value) && !value.length))value=null;
                    const old=this.profile.fields[f.key] ? this.profile.fields[f.key].value : null;
                    if(JSON.stringify(old)!==JSON.stringify(value)) changes[f.key]=value;
                });
                try {
                    this.apply(await this.request('PATCH',{expectedVersion:this.profile.version,changes,autoUpdate:skip?this.profile.autoUpdate:this.autoUpdate,onboardingCompleted:true}));
                    this.open=false;this.notice=skip?'已跳过，可随时完善':'已保存，后续建议将使用这些信息';
                }catch(e){if(e.name!=='AbortError')this.error=e.message;}
                finally{this.busy=false;}
            },
            async clear() {
                if(this.busy || !confirm('删除已保存画像并关闭自动更新？聊天记录不会删除。'))return;
                this.busy=true;this.error='';
                try {
                    const response=await fetch(window.appConfig.backendBaseURL+'/profile/nutrition?expectedVersion='+this.profile.version,{method:'DELETE',signal:this.abortController.signal,headers:{Authorization:'Bearer '+this.token}});
                    if(!response.ok)throw new Error('删除失败，请重新加载后重试');
                    this.apply(await response.json());this.open=false;this.notice='画像已删除，自动更新已关闭';
                }catch(e){if(e.name!=='AbortError')this.error=e.message;}finally{this.busy=false;}
            }
        },
        template:`<section class="nutrition-profile" aria-label="我的饮食画像">
            <div><button @click="edit" :disabled="busy">我的饮食画像</button></div>
            <small v-if="profile">{{ profile.autoUpdate ? '每轮回答后会后台提取你明确表达的本人信息，更新饮食画像；可在设置中关闭或删除。' : '自动更新已关闭。' }}</small>
            <div role="status">{{notice}}</div><div role="alert" class="profile-error">{{error}} <button v-if="error" @click="load">重新加载</button></div>
            <div v-if="open && profile" class="profile-overlay">
                <form class="profile-editor" @submit.prevent="save(false)">
                    <h2>我的饮食画像</h2><p>均可跳过。过敏、慢病和服药请只填写已明确的信息；空白表示未知。多项内容用逗号分隔。</p>
                    <fieldset v-for="group in groups" :key="group"><legend>{{group}}</legend>
                        <div v-for="field in schema.filter(f=>f.group===group)" :key="field.key" class="profile-field">
                            <label :for="'profile-'+field.key">{{field.label}}</label>
                            <div v-if="field.options && field.multi">
                                <label v-for="option in field.options" :key="option"><input type="checkbox" :value="option" v-model="draft[field.key]">{{option}}</label>
                            </div>
                            <select v-else-if="field.options" :id="'profile-'+field.key" v-model="draft[field.key]">
                                <option value="">未填写</option><option v-for="(option,index) in field.options" :value="option">{{field.labels ? field.labels[index] : option}}</option>
                            </select>
                            <input v-else :id="'profile-'+field.key" v-model="draft[field.key]" maxlength="500" placeholder="可不填写">
                            <small v-if="profile.fields[field.key]">{{profile.fields[field.key].source==='CHAT'?'对话更新':'主动填写'}} · {{profile.fields[field.key].scope==='TEMPORARY'?'临时信息':'长期信息'}} · {{profile.fields[field.key].updatedAt}}</small>
                        </div>
                    </fieldset>
                    <label><input type="checkbox" v-model="autoUpdate">每轮对话后自动更新饮食画像</label>
                    <p>模型提取可能有误，可随时检查和更正。关闭后不再从聊天自动写入；删除画像不会删除聊天历史。</p>
                    <div class="profile-error" role="alert">{{error}}</div>
                    <button type="submit" :disabled="busy">{{busy?'保存中…':'保存'}}</button>
                    <button type="button" @click="open=false" :disabled="busy">取消</button>
                    <button type="button" @click="clear" :disabled="busy">删除画像并关闭自动更新</button>
                </form>
            </div>
        </section>`
    });
})();

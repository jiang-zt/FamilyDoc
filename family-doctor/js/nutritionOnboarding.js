/* Shared routing and field mapping; profile data never enters browser storage. */
(function () {
    const steps = [
        {title:'目标',subtitle:'你最近最想改善什么？',options:['饮食均衡','体重管理','减少甜饮','规律进餐','配合运动'],placeholder:'其他目标，请输入'},
        {title:'饮食约束',subtitle:'有哪些需要优先考虑的饮食条件？',options:['无已知食物过敏','素食','孕期','哺乳期'],placeholder:'其他限制，例如过敏食物或明确需遵循的饮食要求'},
        {title:'食物与口味偏好',subtitle:'什么样的味道更合你的心意？',options:['清淡','喜欢辣味','少油','偏好蔬菜','喜欢家常菜'],placeholder:'说说你喜欢或不喜欢的食物与口味'},
        {title:'就餐与生活条件',subtitle:'平常怎样吃，更方便你坚持？',options:['食堂','外卖','自己做','餐馆','希望简单省时'],placeholder:'其他就餐条件、时间或预算，请输入'},
        {title:'习惯与活动',subtitle:'你的日常节奏更接近哪一种？',options:['规律进餐','经常漏早餐','日常运动','久坐','作息不固定'],placeholder:'其他进餐习惯或活动情况，请输入'}
    ];
    function needsSetup(profile) {
        if(profile.onboardingCompleted)return false;
        return !Object.entries(profile.fields || {}).some(([key,entry])=>{
            const v=entry && entry.value;
            if(key==='allergyStatus' && v==='UNKNOWN')return false;
            return Array.isArray(v)?v.length>0:typeof v==='string' && v.trim().length>0;
        });
    }
    function choice(index,value) {
        if(!steps[index] || !steps[index].options.includes(value))throw Error('无效选项');
        if(index===0)return {goals:[value]};
        if(index===1) {
            if(value==='无已知食物过敏')return {allergyStatus:'NONE_KNOWN'};
            if(value==='素食')return {dietaryPattern:value};
            return {lifeStage:value};
        }
        if(index===2)return {taste:value};
        if(index===3)return value==='希望简单省时'?{cookingTime:value}:{mealScenarios:[value]};
        return ['日常运动','久坐'].includes(value)?{activity:value}:{mealHabits:value};
    }
    function custom(index,value) {
        value=value.trim();if(!value || value.length>200 || !steps[index])throw Error('请输入1–200个字符');
        if(index===0)return {goals:[value]};
        if(index===1)return {dietaryRestrictions:[value]};
        if(index===2)return {taste:value};
        if(index===3)return {mealScenarios:[value]};
        return {mealHabits:value};
    }
    async function request(method='GET',body,token,signal) {
        token=token || cookieUtils.getToken();
        if(!token || cookieUtils.getToken()!==token)throw Error('登录状态已变化，请重新登录');
        const response=await fetch(window.appConfig.backendBaseURL+'/profile/nutrition',{
            method,signal,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},
            ...(body?{body:JSON.stringify(body)}:{})
        });
        if(cookieUtils.getToken()!==token)throw Error('登录状态已变化，请重新登录');
        if(!response.ok){const error=Error(response.status===409?'画像已更新，请重试保存':response.status===401?'登录已失效，请重新登录':'暂时无法读取或保存画像，请重试');error.status=response.status;throw error;}
        return response.json();
    }
    async function routeAfterAuth() {
        const profile=await request();
        window.location.href=needsSetup(profile)?'/pages/nutrition-onboarding.html':'/pages/chat-doctor.html';
    }
    window.nutritionOnboarding={steps,needsSetup,choice,custom,request,routeAfterAuth};
})();

(function () {
    const api=window.nutritionOnboarding;
    new Vue({
        el:'#onboarding',
        data(){return {steps:api.steps,index:0,answers:[null,null,null,null,null],customInputs:['','','','',''],profile:null,loading:true,busy:false,error:'',savePending:false};},
        created(){this.token=cookieUtils.getToken();this.controller=new AbortController();this.touchX=null;this.lastMove=0;this.load();},
        beforeDestroy(){this.controller.abort();},
        methods:{
            async load(){
                this.loading=true;this.error='';
                if(!this.token){window.location.href='/pages/user-login.html';return;}
                try {this.profile=await api.request('GET',undefined,this.token,this.controller.signal);if(!api.needsSetup(this.profile)){window.location.href='/pages/chat-doctor.html';return;}}
                catch(e){if(e.status===401){window.location.href='/pages/user-login.html';return;}if(e.name!=='AbortError')this.error=e.message;}
                finally{this.loading=false;}
            },
            go(i){if(this.busy || this.loading)return;this.index=Math.max(0,Math.min(4,i));this.error='';},
            next(){if(this.index<4)this.index++;else return this.finish();},
            select(option){
                if(this.busy || Date.now()-this.lastMove<500)return;
                this.lastMove=Date.now();this.$set(this.answers,this.index,{label:option,changes:api.choice(this.index,option)});this.error='';this.next();
            },
            submitCustom(){
                if(this.busy || Date.now()-this.lastMove<500)return;
                try {const text=this.customInputs[this.index];const changes=api.custom(this.index,text);this.lastMove=Date.now();this.$set(this.answers,this.index,{label:text.trim(),changes});this.error='';this.next();}
                catch(e){this.error=e.message;}
            },
            onEnter(event){if(event.isComposing || event.keyCode===229)event.preventDefault();},
            skip(){if(this.busy || this.loading || Date.now()-this.lastMove<500)return;this.lastMove=Date.now();this.error='';this.next();},
            touchStart(e){this.touchX=e.touches.length===1?e.touches[0].clientX:null;this.touchY=e.touches.length===1?e.touches[0].clientY:null;},
            touchEnd(e){
                if(this.touchX===null || this.busy || Date.now()-this.lastMove<500)return;
                const dx=e.changedTouches[0].clientX-this.touchX,dy=e.changedTouches[0].clientY-this.touchY;this.touchX=null;
                if(Math.abs(dx)<70 || Math.abs(dx)<Math.abs(dy)*1.5)return;
                this.lastMove=Date.now();this.go(this.index+(dx<0?1:-1));
            },
            async finish(){
                if(this.busy || !this.profile)return;this.busy=true;this.savePending=true;this.error='';
                const changes=Object.assign({},...this.answers.filter(Boolean).map(a=>a.changes));
                try {
                    this.profile=await api.request('PATCH',{expectedVersion:this.profile.version,changes,onboardingCompleted:true},this.token,this.controller.signal);
                    window.location.href='/pages/chat-doctor.html';
                } catch(e){
                    if(e.status===409){try{this.profile=await api.request('GET',undefined,this.token,this.controller.signal);}catch(_){} }
                    if(e.name!=='AbortError')this.error=e.message;
                } finally{this.busy=false;}
            },
            retry(){if(this.savePending && this.profile)return this.finish();return this.load();}
        }
    });
})();

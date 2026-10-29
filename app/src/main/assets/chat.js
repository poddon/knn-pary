(function(msg){
if(!msg||window.__knnChat||location.href.indexOf('html5client')<0)return;window.__knnChat=1;
var key='knnChat:'+location.search;
try{if(sessionStorage.getItem(key))return;}catch(e){}
function vis(e){return !!e&&e.offsetParent!==null;}
function lower(e){return((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('placeholder')||'')+' '+(e.textContent||'')).toLowerCase();}
function input(){
 var t=document.querySelector('#message-input,[data-test=messageInput],textarea[name=message]');
 if(vis(t))return t;
 return [].slice.call(document.querySelectorAll('textarea')).filter(function(x){return vis(x)&&/сообщ|message|чат|chat/.test(lower(x));})[0];
}
function audioDialogOpen(){return vis(document.querySelector('[data-test=listenOnlyBtn],[data-test=microphoneBtn]'));}
var k=0,opened=false,t=setInterval(function(){
 if(++k>360){clearInterval(t);return;}
 if(audioDialogOpen())return;
 var ta=input();
 if(!ta){
  if(!opened&&k>6){
   opened=true;
   var c=document.querySelector('[data-test=chatButton],[data-test=publicChatButton]')||
    [].slice.call(document.querySelectorAll('[role=button],button,[aria-label]')).filter(function(e){return vis(e)&&/общий чат|public chat/.test(lower(e));})[0];
   if(c)c.click();
  }
  return;
 }
 clearInterval(t);
 setTimeout(function(){
  if(ta.tagName==='TEXTAREA'){Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(ta,msg);}else{ta.value=msg;}
  ta.dispatchEvent(new Event('input',{bubbles:true}));
  setTimeout(function(){
   var b=document.querySelector('[data-test=sendMessageButton]')||(ta.form&&ta.form.querySelector('button[type=submit]'))||
    [].slice.call(document.querySelectorAll('button')).filter(function(x){return vis(x)&&/отправ|send/.test(lower(x));})[0];
   if(b){b.click();}
   else{['keydown','keypress','keyup'].forEach(function(ev){ta.dispatchEvent(new KeyboardEvent(ev,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});}
   try{sessionStorage.setItem(key,'1');}catch(e){}
  },400);
 },1500);
},1000);
})(__MSG__);

package com.example.modemcontrol

/** سكربتات الترجمة داخل الصفحة. تجمع النصوص، ترسلها لـ MCBridge، وتستبدلها بالترجمة (مع حفظ الأصل للتراجع). */
object TranslateJs {
    const val START = """(function(){
var T=window.__mcT;
if(!T){
 T=window.__mcT={map:new WeakMap(),cache:{},sent:{},timer:null,obs:null,on:true};
 T.skip={SCRIPT:1,STYLE:1,NOSCRIPT:1,TEXTAREA:1,CODE:1,PRE:1};
 T.ok=function(s){
  var t=s.trim();
  if(t.length<3||!/[A-Za-z]{3,}/.test(t)) return false;
  if(/@|:\/\//.test(t)) return false;
  if(!/\s/.test(t)&&(/[0-9_]/.test(t)||/[A-Z].*[A-Z]/.test(t))) return false;
  return true;
 };
 T.walk=function(fn){
  var w=document.createTreeWalker(document.body||document.documentElement,NodeFilter.SHOW_TEXT,null),n;
  while((n=w.nextNode())){var p=n.parentNode;if(!p||T.skip[p.nodeName])continue;fn(n,p);}
 };
 T.set=function(n,p,ar){
  var v=n.nodeValue,m=v.match(/^(\s*)([\s\S]*?)(\s*)${'$'}/),nv=m[1]+ar+m[3];
  T.map.set(n,{o:v,t:nv});n.nodeValue=nv;
  if(p.nodeType===1&&!p.hasAttribute('dir')){p.setAttribute('dir','auto');p.setAttribute('data-mcdir','1');}
 };
 T.collect=function(){
  if(!T.on)return;
  var need={},cnt=0;
  T.walk(function(n,p){
   var r=T.map.get(n);if(r&&r.t===n.nodeValue)return;
   var s=n.nodeValue.trim();if(!T.ok(s))return;
   if(T.cache[s]!==undefined){T.set(n,p,T.cache[s]);}
   else if(!T.sent[s]&&cnt<60){need[s]=1;T.sent[s]=1;cnt++;}
  });
  var arr=Object.keys(need);
  if(arr.length)MCBridge.translate(JSON.stringify(arr));
 };
 T.apply=function(j){
  var o=JSON.parse(j);for(var k in o){T.cache[k]=o[k];}
  T.walk(function(n,p){
   var r=T.map.get(n);if(r&&r.t===n.nodeValue)return;
   var s=n.nodeValue.trim();
   if(T.cache[s]!==undefined&&T.ok(s))T.set(n,p,T.cache[s]);
  });
  T.collect();
 };
 window.__mcApply=T.apply;
 T.obs=new MutationObserver(function(){clearTimeout(T.timer);T.timer=setTimeout(T.collect,350);});
}
T.on=true;T.obs.disconnect();
T.obs.observe(document.documentElement,{childList:true,subtree:true,characterData:true});
T.collect();
})()"""

    const val STOP = """(function(){
var T=window.__mcT;if(!T)return;
T.on=false;T.obs.disconnect();clearTimeout(T.timer);
T.walk(function(n,p){var r=T.map.get(n);if(r&&r.t===n.nodeValue){n.nodeValue=r.o;}});
var els=document.querySelectorAll('[data-mcdir]');
for(var i=0;i<els.length;i++){els[i].removeAttribute('dir');els[i].removeAttribute('data-mcdir');}
T.map=new WeakMap();T.sent={};
})()"""
}

package com.alan.fasttransfer.core.web;

/**
 * 网页传输的界面。
 *
 * <p>刻意做成「一个字符串」：不引入任何前端构建链、不联网加载 CDN，
 * 断网热点环境下也能正常打开。样式跟随系统深色模式。</p>
 */
public final class WebPage {

    private WebPage() {
    }

    /**
     * 生成首页。
     *
     * @param deviceName  手机名字，显示在标题旁边
     * @param pinRequired 是否需要先输 PIN
     * @param notice      顶部提示（可为空）
     */
    public static String index(String deviceName, boolean pinRequired, String notice) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n");
        sb.append("<title>极速互传</title>\n");
        sb.append("<style>").append(CSS).append("</style>\n");
        sb.append("</head>\n<body>\n");
        sb.append("<header><h1>极速互传</h1><span class=\"dev\">")
                .append(escape(deviceName)).append("</span></header>\n");
        sb.append("<main>\n");

        if (notice != null && !notice.isEmpty()) {
            sb.append("<p class=\"notice\">").append(escape(notice)).append("</p>\n");
        }

        if (pinRequired) {
            sb.append("<section class=\"card\">\n<h2>需要配对码</h2>\n");
            sb.append("<p class=\"hint\">请输入手机上显示的配对码。</p>\n");
            sb.append("<form method=\"post\" action=\"/web/login\" class=\"row\">\n");
            sb.append("<input type=\"tel\" name=\"pin\" inputmode=\"numeric\" ")
                    .append("autocomplete=\"one-time-code\" placeholder=\"配对码\" required>\n");
            sb.append("<button type=\"submit\">连接</button>\n</form>\n</section>\n");
        } else {
            // 手机发过来的内容放最上面：这是「手机 → 电脑」的主路径
            sb.append("<section class=\"card highlight\" id=\"outbox-card\">\n");
            sb.append("<h2>手机要发给你的文件</h2>\n");
            sb.append("<p class=\"hint\" id=\"outbox-hint\">")
                    .append("还没有。请在手机上进入「发送」页，选好文件或输入文字，")
                    .append("然后点设备列表里的「电脑」。</p>\n");
            sb.append("<ul id=\"outbox\" class=\"files\"></ul>\n");
            sb.append("</section>\n");

            sb.append("<section class=\"card\">\n<h2>传到这台手机</h2>\n");
            sb.append("<p class=\"hint\">选择文件或拖进来，<b>手机上点「接收」后</b>才会开始传输。</p>\n");
            sb.append("<div id=\"drop\" class=\"drop\">\n");
            sb.append("<input id=\"picker\" type=\"file\" multiple>\n");
            sb.append("<label for=\"picker\">点击选择文件</label>\n");
            sb.append("<span class=\"sub\">或把文件拖到这里</span>\n");
            sb.append("</div>\n");
            sb.append("<div id=\"list\" class=\"list\"></div>\n");
            // 诊断行：把每次轮询服务端到底回了什么直接显示出来。
            // 「点了接收没反应」这类问题，光看页面是判断不出卡在哪一步的。
            sb.append("<p id=\"diag\" class=\"notice\" style=\"display:none\"></p>\n");
            sb.append("</section>\n");
        }

        sb.append("</main>\n");
        if (!pinRequired) {
            sb.append("<script>").append(JS).append("</script>\n");
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    // ==================== 工具 ====================

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final String CSS =
            ":root{--bg:#f6f7f9;--card:#fff;--fg:#1b1c1e;--muted:#5f6368;--line:#e3e5e8;"
                    + "--accent:#3b6ff5;--accent-fg:#fff}"
                    + "@media (prefers-color-scheme:dark){:root{--bg:#111214;--card:#1c1d20;"
                    + "--fg:#e8eaed;--muted:#9aa0a6;--line:#2c2e33;--accent:#8ab4f8;--accent-fg:#202124}}"
                    + "*{box-sizing:border-box}"
                    + "body{margin:0;background:var(--bg);color:var(--fg);"
                    + "font:16px/1.5 system-ui,-apple-system,\"Segoe UI\",Roboto,\"Noto Sans SC\",sans-serif}"
                    + "header{display:flex;align-items:baseline;gap:10px;padding:22px 18px 6px;"
                    + "max-width:760px;margin:0 auto}"
                    + "h1{font-size:20px;margin:0;letter-spacing:.5px}"
                    + ".dev{color:var(--muted);font-size:14px}"
                    + "main{max-width:760px;margin:0 auto;padding:10px 18px 40px}"
                    + ".card{background:var(--card);border:1px solid var(--line);border-radius:14px;"
                    + "padding:18px;margin:14px 0}"
                    + "h2{font-size:16px;margin:0 0 6px}"
                    + ".hint{color:var(--muted);font-size:14px;margin:0 0 14px}"
                    + ".notice{background:var(--card);border:1px solid var(--line);border-radius:14px;"
                    + "padding:12px 16px;margin:14px 0;color:var(--muted);font-size:14px}"
                    + ".drop{position:relative;border:2px dashed var(--line);border-radius:12px;"
                    + "padding:34px 16px;text-align:center;transition:border-color .15s,background .15s}"
                    + ".drop.over{border-color:var(--accent);background:rgba(59,111,245,.06)}"
                    + ".drop input{position:absolute;inset:0;opacity:0;cursor:pointer}"
                    + ".drop label{display:block;font-weight:600;color:var(--accent);cursor:pointer}"
                    + ".drop .sub{display:block;color:var(--muted);font-size:13px;margin-top:4px}"
                    + ".row{display:flex;gap:10px}"
                    + "input[type=tel]{flex:1;padding:11px 13px;border-radius:10px;"
                    + "border:1px solid var(--line);background:transparent;color:var(--fg);font-size:16px}"
                    + "button{padding:11px 20px;border:0;border-radius:10px;background:var(--accent);"
                    + "color:var(--accent-fg);font-size:15px;font-weight:600;cursor:pointer}"
                    + ".list{margin-top:14px;display:flex;flex-direction:column;gap:10px}"
                    + ".item{border:1px solid var(--line);border-radius:10px;padding:10px 12px}"
                    + ".item .top{display:flex;justify-content:space-between;font-size:14px;gap:12px}"
                    + ".item .nm{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                    + ".item .pct{color:var(--muted);flex:none}"
                    + ".bar{height:5px;border-radius:3px;background:var(--line);margin-top:8px;overflow:hidden}"
                    + ".bar i{display:block;height:100%;width:0;background:var(--accent);"
                    + "transition:width .15s}"
                    + ".ok{color:#1e8e3e}.bad{color:#d93025}"
                    + ".detail{display:none;margin-top:8px;padding:8px 10px;border-radius:8px;"
                    + "background:rgba(217,48,37,.08);color:#d93025;font-size:13px;"
                    + "word-break:break-all}"
                    + ".detail.saved{background:rgba(30,142,62,.08);color:#1e8e3e}"
                    + ".highlight{border-color:var(--accent);box-shadow:0 0 0 1px var(--accent) inset}"
                    + ".highlight h2{color:var(--accent)}"
                    + ".txt-body{white-space:pre-wrap;word-break:break-word;padding:10px 12px;"
                    + "border-radius:10px;background:var(--bg);border:1px solid var(--line);"
                    + "font-size:14px;max-height:260px;overflow:auto}"
                    + ".txt-bar{margin-top:8px}"
                    + ".files{list-style:none;margin:0;padding:0}"
                    + ".files li+li{border-top:1px solid var(--line)}"
                    + ".files a{display:flex;justify-content:space-between;gap:14px;padding:11px 2px;"
                    + "color:inherit;text-decoration:none}"
                    + ".files a:hover .fname{color:var(--accent)}"
                    + ".fname{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                    + ".fsize{color:var(--muted);font-size:13px;flex:none}";

    private static final String JS =
            "(function(){"
                    + "var drop=document.getElementById('drop'),list=document.getElementById('list');"
                    + "if(!drop)return;"
                    + "['dragenter','dragover'].forEach(function(e){drop.addEventListener(e,"
                    + "function(ev){ev.preventDefault();drop.classList.add('over');});});"
                    + "['dragleave','drop'].forEach(function(e){drop.addEventListener(e,"
                    + "function(ev){ev.preventDefault();drop.classList.remove('over');});});"
                    + "drop.addEventListener('drop',function(ev){"
                    + "if(ev.dataTransfer&&ev.dataTransfer.files)upload(ev.dataTransfer.files);});"
                    + "document.getElementById('picker').addEventListener('change',function(ev){"
                    + "upload(ev.target.files);ev.target.value='';});"
                    + "function upload(files){"
                    + "if(!files||!files.length)return;"
                    // 必须**立刻**把 FileList 复制成真数组。
                    // 调用方紧接着会执行 input.value=''，那会把 FileList 对象清空，
                    // 等到确认回来、真的去上传时 files[i] 已经是 undefined，
                    // 于是 file.name 抛异常、整个回调中断，请求根本发不出去 ——
                    // 表现就是「手机上确认了，电脑这边毫无反应」。
                    + "var list=[];"
                    + "for(var i=0;i<files.length;i++){list.push(files[i]);}"
                    + "files=list;"
                    // 先建 UI 再发请求：等手机确认这一步可能要好几秒，
                    // 期间页面上如果什么都没有，用户会以为「点了没反应」。
                    + "var boxes=[];"
                    + "for(var i=0;i<files.length;i++){"
                    + "boxes.push(makeBox(files[i],'等待手机确认…'));}"
                    // 先让手机确认：把清单发过去，手机弹「接收 / 拒绝」。
                    // 用户拒绝就直接结束，一个字节都不会传。
                    + "var meta=[];"
                    + "for(var i=0;i<files.length;i++){"
                    + "meta.push({name:files[i].name,size:files[i].size||0});}"
                    + "var p=new XMLHttpRequest();p.open('POST','/web/prepare');"
                    + "p.setRequestHeader('Content-Type','application/json');"
                    + "p.onload=function(){"
                    + "var session='';"
                    + "try{session=(JSON.parse(p.responseText)||{}).session||'';}catch(e){session='';}"
                    + "if(p.status!==200||!session){"
                    // 解析不出来就把原文亮出来，否则只会看到一句没头没尾的提示
                    + "var msg='服务器返回了非预期内容（HTTP '+p.status+'）：'"
                    + "+String(p.responseText||'').slice(0,200);"
                    + "try{var j=JSON.parse(p.responseText);if(j&&j.message)msg=j.message;}catch(e){}"
                    + "for(var i=0;i<files.length;i++){fail(boxes[i],msg);}"
                    + "return;}"
                    + "waitConfirm(files,session,boxes,0);};"
                    + "p.onerror=function(){"
                    + "for(var i=0;i<files.length;i++){fail(boxes[i],'网络中断');}};"
                    + "try{p.send(JSON.stringify({files:meta}));}"
                    + "catch(e){for(var i=0;i<files.length;i++){fail(boxes[i],'发送失败');}}}"
                    // 轮询用户有没有点确认：比让浏览器挂着一个长请求可靠得多，
                    // 也能把「等待/拒绝/超时」分清楚
                    + "function note(text){"
                    + "var d=document.getElementById('diag');"
                    + "if(!d)return;d.style.display='block';d.textContent=text;}"
                    + "function waitConfirm(files,session,boxes,tries){"
                    + "if(tries>120){"
                    + "for(var i=0;i<files.length;i++){"
                    + "fail(boxes[i],'手机一直没有确认（超时）');}return;}"
                    + "var q=new XMLHttpRequest();"
                    + "q.open('GET','/web/prepare/status?session='+encodeURIComponent(session));"
                    + "q.onload=function(){"
                    + "var st={};try{st=JSON.parse(q.responseText)||{};}catch(e){st={};}"
                    // 把每次应答亮出来，出问题时一眼能看出卡在哪一步
                    + "note('第 '+(tries+1)+' 次查询：HTTP '+q.status"
                    + "+' state='+(st.state||'?')+' token='+(st.token?'有':'无')"
                    + "+' 原文='+String(q.responseText||'').slice(0,120));"
                    + "if(q.status!==200){"
                    + "var m=st.message||('服务器返回 '+q.status);"
                    + "for(var i=0;i<files.length;i++){fail(boxes[i],m);}return;}"
                    + "if(st.state==='pending'){setTimeout(function(){"
                    + "waitConfirm(files,session,boxes,tries+1);},500);return;}"
                    + "if(st.state==='denied'){"
                    + "for(var i=0;i<files.length;i++){"
                    + "fail(boxes[i],'手机上拒绝了这次传输');}return;}"
                    + "if(st.state==='accepted'&&st.token){"
                    + "for(var i=0;i<files.length;i++){one(files[i],st.token,boxes[i]);}return;}"
                    + "for(var i=0;i<files.length;i++){fail(boxes[i],'手机返回了未知状态');}};"
                    + "q.onerror=function(){"
                    + "note('第 '+(tries+1)+' 次查询：网络错误，重试中');"
                    + "setTimeout(function(){"
                    + "waitConfirm(files,session,boxes,tries+1);},500);};"
                    + "try{q.send();}catch(e){"
                    + "for(var i=0;i<files.length;i++){fail(boxes[i],'轮询失败');}}}"
                    + "function makeBox(file,pctText){"
                    + "var box=document.createElement('div');box.className='item';"
                    + "box.innerHTML='<div class=\"top\"><span class=\"nm\"></span>"
                    + "<span class=\"pct\"></span></div><div class=\"bar\"><i></i></div>"
                    + "<div class=\"detail\"></div>';"
                    + "box.querySelector('.nm').textContent=file.name;"
                    + "box.querySelector('.pct').textContent=pctText;"
                    + "list.appendChild(box);"
                    + "return box;}"
                    + "function fail(box,msg){"
                    + "var pct=box.querySelector('.pct');"
                    + "pct.textContent='失败';pct.className='pct bad';"
                    + "detail(box,msg);}"
                    + "function detail(box,text){"
                    + "var d=box.querySelector('.detail');d.textContent=text;d.style.display='block';}"
                    // 成功时用同一块地方显示「存到哪了」，样式换成中性的
                    + "function saved(box,path){"
                    + "var d=box.querySelector('.detail');d.textContent='已保存到：'+path;"
                    + "d.className='detail saved';d.style.display='block';}"
                    + "function one(file,token,box){"
                    + "var pct=box.querySelector('.pct'),bar=box.querySelector('.bar i');"
                    + "pct.textContent='上传中';"
                    + "var fd=new FormData();fd.append('file',file,file.name);"
                    + "var xhr=new XMLHttpRequest();"
                    + "xhr.open('POST','/web/upload?token='+encodeURIComponent(token));"
                    + "xhr.upload.onprogress=function(e){if(!e.lengthComputable)return;"
                    + "var p=Math.round(e.loaded*100/e.total);bar.style.width=p+'%';"
                    + "pct.textContent=p+'%';};"
                    // 失败时必须把服务器的话原样显示出来：只说「失败」等于没说，
                    // 用户和我们都没法判断到底是权限、目录还是文件名的问题。
                    + "xhr.onload=function(){"
                    + "if(xhr.status===200){bar.style.width='100%';"
                    + "pct.textContent='完成';pct.className='pct ok';"
                    // 把保存位置亮出来：文件存好了但用户找不到时，
                    // 光看「完成」是没法判断的
                    + "try{var j=JSON.parse(xhr.responseText);"
                    + "if(j&&j.saved&&j.saved.length){saved(box,j.saved[0]);}}catch(e){}"
                    + "}"
                    + "else{var msg='服务器返回 '+xhr.status;"
                    + "try{var j=JSON.parse(xhr.responseText);if(j&&j.message)msg=j.message;}"
                    + "catch(e){if(xhr.responseText)msg=xhr.responseText.slice(0,200);}"
                    + "pct.textContent='失败';pct.className='pct bad';detail(box,msg);}};"
                    + "xhr.onerror=function(){pct.textContent='失败';pct.className='pct bad';"
                    + "detail(box,'网络中断，请确认手机和电脑在同一个网络');};"
                    + "xhr.send(fd);}"
                    // ---- 手机发来的文件：轮询并渲染 ----
                    // 浏览器没法被手机「推」，所以手机上一点发送，这边最多 2 秒就能看到；
                    // 想要更即时可以上 SSE，但为这点延迟不值得增加复杂度。
                    + "function human(n){if(n<1024)return n+' B';"
                    + "var u=['KB','MB','GB','TB'],i=-1;"
                    + "while(n>=1024&&i<u.length-1){n=n/1024;i++;}"
                    + "return n.toFixed(1)+' '+u[i];}"
                    + "function renderOutbox(files){"
                    + "var ul=document.getElementById('outbox'),"
                    + "hint=document.getElementById('outbox-hint');"
                    + "if(!ul)return;"
                    + "ul.innerHTML='';"
                    + "if(!files.length){hint.style.display='block';return;}"
                    + "hint.style.display='none';"
                    + "files.forEach(function(f){"
                    + "var li=document.createElement('li');"
                    + "if(f.text){"
                    // 文字消息：直接铺开显示 + 一键复制，比让人下载一个 txt 实用
                    + "var box=document.createElement('div');box.className='txt';"
                    + "var pre=document.createElement('div');pre.className='txt-body';"
                    + "pre.textContent=f.body||'';"
                    + "var bar=document.createElement('div');bar.className='txt-bar';"
                    + "var btn=document.createElement('button');btn.type='button';"
                    + "btn.textContent='复制文字';"
                    + "btn.onclick=function(){"
                    + "var t=f.body||'';"
                    + "if(navigator.clipboard&&navigator.clipboard.writeText){"
                    + "navigator.clipboard.writeText(t).then(function(){"
                    + "btn.textContent='已复制';setTimeout(function(){"
                    + "btn.textContent='复制文字';},1500);},function(){fallback(pre);});"
                    + "}else{fallback(pre);}};"
                    + "function fallback(node){"
                    + "var r=document.createRange();r.selectNodeContents(node);"
                    + "var s=window.getSelection();s.removeAllRanges();s.addRange(r);"
                    + "try{document.execCommand('copy');btn.textContent='已复制';"
                    + "setTimeout(function(){btn.textContent='复制文字';},1500);}"
                    + "catch(e){btn.textContent='请手动选中复制';}}"
                    + "bar.appendChild(btn);box.appendChild(pre);box.appendChild(bar);"
                    + "li.appendChild(box);"
                    + "}else{"
                    + "var a=document.createElement('a');"
                    + "a.href='/web/pull?token='+encodeURIComponent(f.token);"
                    + "var n=document.createElement('span');n.className='fname';"
                    + "n.textContent=f.name;"
                    + "var s=document.createElement('span');s.className='fsize';"
                    + "s.textContent=human(f.size);"
                    + "a.appendChild(n);a.appendChild(s);"
                    + "li.appendChild(a);}"
                    + "ul.appendChild(li);});}"
                    + "function pollOutbox(){"
                    + "var x=new XMLHttpRequest();x.open('GET','/web/outbox');"
                    + "x.onload=function(){if(x.status!==200)return;"
                    + "try{var j=JSON.parse(x.responseText);renderOutbox(j.files||[]);}"
                    + "catch(e){}};"
                    + "x.onerror=function(){};"
                    + "try{x.send();}catch(e){}}"
                    + "pollOutbox();setInterval(pollOutbox,2000);"
                    + "})();";
}

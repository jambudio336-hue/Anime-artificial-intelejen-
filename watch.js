"use strict";
(() => {
  const plugin = () => window.Capacitor?.Plugins?.Watch;
  const $ = id => document.getElementById(id);
  let source = "melolo", lastQuery = "", debounce = 0, selected = null;
  const cards = $("cards"), episodes = $("episodes"), error = $("error");
  const esc = value => String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  function setBusy() {
    error.classList.add("hidden"); episodes.classList.add("hidden"); cards.classList.remove("hidden");
    cards.innerHTML = Array.from({length:9},()=>'<div class="card"><div class="skeleton"></div><div class="meta"><div class="skeleton" style="aspect-ratio:8/1"></div></div></div>').join("");
  }
  function showError(message) { cards.innerHTML=""; error.classList.remove("hidden"); $("errorText").textContent=message || "Periksa koneksi internet lalu coba lagi."; }
  function showEmpty(message) { cards.innerHTML='<div class="empty">'+esc(message)+'</div>'; }
  function renderItems(items) {
    cards.innerHTML="";
    if (!items?.length) return showEmpty(source==="history"?"Belum ada riwayat tontonan.":"Belum ada judul ditemukan.");
    for (const item of items) {
      const card=document.createElement("article"); card.className="card";
      const cover=item.cover ? '<img class="cover" loading="lazy" src="'+esc(item.cover)+'" alt="Poster '+esc(item.title)+'">' : '<div class="cover-placeholder">TANPA POSTER</div>';
      card.innerHTML=cover+'<div class="meta"><div class="title">'+esc(item.title)+'</div><div class="badges"><span class="badge">'+esc(item.totalEps||"Video")+' eps</span><span class="badge">'+esc(item.status||"")+'</span><span class="badge source">'+esc(item.source||sourceLabel())+'</span></div></div>';
      card.addEventListener("click",()=>openEpisodes(item));
      cards.append(card);
    }
  }
  function sourceLabel(){return source==="melolo"?"Melolo":source==="anime"?"Anime":source==="manual"?"Manual":"Riwayat";}
  async function search() {
    lastQuery=$("query").value.trim(); setBusy();
    try {
      const api=plugin(); if(!api) throw new Error("Native Watch plugin belum tersedia. Gunakan APK Android terbaru.");
      if(source==="history") { const r=await api.history(); renderItems(r.items||[]); return; }
      const r=await api.search({source,q:lastQuery,offset:0});
      renderItems((r.items||[]).map(x=>({...x,source:sourceLabel()})));
    } catch(e) { showError(e.message); }
  }
  async function openEpisodes(item) {
    selected=item; $("seriesTitle").textContent=item.title||"Episode";
    cards.classList.add("hidden"); error.classList.add("hidden"); episodes.classList.remove("hidden");
    $("episodeList").innerHTML=Array.from({length:6},(_,i)=>'<div class="episode skeleton" style="height:44px"></div>').join("");
    try {
      const api=plugin(); if(!api) throw new Error("Native Watch plugin tidak tersedia.");
      const r=await api.episodes({source:item.source==="Manual"?"manual":(item.source==="Anime"?"anime":"melolo"),seriesId:item.id});
      const list=$("episodeList"); list.innerHTML="";
      if(!r.episodes?.length){list.innerHTML='<div class="empty">Episode belum tersedia.</div>';return;}
      for(const ep of r.episodes) {
        const b=document.createElement("button"); b.className="episode"; b.disabled=!!ep.locked;
        b.innerHTML='<span>'+String(ep.index+1).padStart(2,"0")+'</span><b>'+esc(ep.title)+'</b><span>'+(ep.locked?"TERKUNCI":"▶")+'</span>';
        b.addEventListener("click",async()=>{
          try { await api.play({source:item.source==="Manual"?"manual":(item.source==="Anime"?"anime":"melolo"),videoId:ep.videoId,title:item.title+" · "+ep.title}); }
          catch(e){showError(e.message);}
        });
        list.append(b);
      }
    } catch(e) { $("episodeList").innerHTML='<div class="empty">'+esc(e.message)+'</div>'; }
  }
  function chooseSource(next) {
    source=next; selected=null; episodes.classList.add("hidden"); cards.classList.remove("hidden");
    document.querySelectorAll(".tabs button").forEach(b=>b.classList.toggle("active",b.dataset.source===source));
    $("query").placeholder=source==="manual"?"Tempel URL .m3u8 atau .mp4…":source==="history"?"Riwayat tontonan":"Cari judul tontonan…";
    $("hint").textContent=source==="manual"?"Tempel URL video langsung; hanya HTTP/HTTPS .m3u8 atau .mp4.":source==="anime"?"Cari anime melalui mirror keyless.":source==="history"?"Daftar tontonan terakhir.":"Cari drama China favorit tanpa API key.";
    if(source==="history") $("query").value="";
    search();
  }
  document.querySelectorAll(".tabs button").forEach(b=>b.addEventListener("click",()=>chooseSource(b.dataset.source)));
  $("query").addEventListener("input",()=>{clearTimeout(debounce);debounce=setTimeout(search,400);});
  $("query").addEventListener("keydown",e=>{if(e.key==="Enter")search();});
  $("searchBtn").addEventListener("click",search);
  $("refresh").addEventListener("click",search);
  $("retry").addEventListener("click",search);
  $("backCards").addEventListener("click",()=>{episodes.classList.add("hidden");cards.classList.remove("hidden");});
  search();
})();

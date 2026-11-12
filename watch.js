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
      card.innerHTML=cover+'<div class="meta"><div class="title">'+esc(item.title)+'</div><div class="badges"><span class="badge">'+esc(item.totalEps||"Video")+' eps</span><span class="badge">'+esc(item.status||"")+'</span><span class="badge source">'+esc(sourceLabel(item.source))+'</span></div></div>';
      if(source==="history") {
        const play=document.createElement("button"); play.className="episode"; play.textContent="▶ Lanjutkan";
        play.addEventListener("click",async e=>{e.stopPropagation();try{await plugin().play({source:sourceId(item.source),videoId:item.videoId,title:item.title,seriesId:item.seriesId});}catch(err){showError(err.message);}});
        const remove=document.createElement("button"); remove.className="episode"; remove.textContent="Hapus riwayat";
        remove.addEventListener("click",async e=>{e.stopPropagation();try{await plugin().history({action:"remove",id:item.videoId});search();}catch(err){showError(err.message);}});
        card.querySelector(".meta").append(play,remove);
      } else {
        card.addEventListener("click",()=>openEpisodes(item));
        const action=document.createElement("button"); action.className="episode";
        if(source==="watchlist") {
          action.textContent="Hapus dari daftar";
          action.addEventListener("click",async e=>{e.stopPropagation();try{await plugin().watchlist({action:"remove",id:item.id});search();}catch(err){showError(err.message);}});
        } else {
          action.textContent="＋ Daftar tontonan";
          action.addEventListener("click",async e=>{
            e.stopPropagation();
            try{await plugin().watchlist({action:"add",id:item.id,source:sourceId(item.source),title:item.title,cover:item.cover});action.textContent="✓ Tersimpan";}
            catch(err){showError(err.message);}
          });
        }
        card.querySelector(".meta").append(action);
      }
      cards.append(card);
    }
  }
  function sourceId(value){const v=String(value||source).toLowerCase();return v==="anime"||v==="manual"||v==="melolo"?v:v.includes("anime")?"anime":v.includes("manual")?"manual":"melolo";}
  function sourceLabel(value){const v=String(value||source).toLowerCase();return v==="history"?"Riwayat":v==="watchlist"?"Daftar":v==="anime"?"Anime":v==="manual"?"Manual":"Melolo";}
  function statusLabel(item){
    if(item.sourceLabel==="Anime"||item.source==="anime") return item.status||"Anime";
    const value=String(item.status||"").toLowerCase();
    if(/finish|complete|completed|selesai|ended|\b2\b/.test(value)) return "Selesai";
    return "Berjalan";
  }
  async function search() {
    lastQuery=$("query").value.trim(); setBusy();
    try {
      const api=plugin(); if(!api) throw new Error("Native Watch plugin belum tersedia. Gunakan APK Android terbaru.");
      if(source==="history") { const r=await api.history(); renderItems(r.items||[]); return; }
      if(source==="watchlist") { const r=await api.watchlist(); renderItems(r.items||[]); return; }
      const r=await api.search({source,q:lastQuery,offset:0});
      renderItems(r.items||[]);
    } catch(e) { showError(e.message); }
  }
  async function openEpisodes(item) {
    selected=item; $("seriesTitle").textContent=item.title||"Episode";
    cards.classList.add("hidden"); error.classList.add("hidden"); episodes.classList.remove("hidden");
    $("episodeList").innerHTML=Array.from({length:6},(_,i)=>'<div class="episode skeleton" style="height:44px"></div>').join("");
    try {
      const api=plugin(); if(!api) throw new Error("Native Watch plugin tidak tersedia.");
      const r=await api.episodes({source:sourceId(item.source),seriesId:item.id});
      const list=$("episodeList"); list.innerHTML="";
      if(!r.episodes?.length){list.innerHTML='<div class="empty">Episode belum tersedia.</div>';return;}
      for(const ep of r.episodes) {
        const b=document.createElement("button"); b.className="episode"; b.disabled=!!ep.locked;
        b.innerHTML='<span>'+String(ep.index+1).padStart(2,"0")+'</span><b>'+esc(ep.title)+'</b><span>'+(ep.locked?"TERKUNCI":"▶")+'</span>';
        b.addEventListener("click",async()=>{
          try { await api.play({source:sourceId(item.source),videoId:ep.videoId,seriesId:item.id,title:item.title+" · "+ep.title}); }
          catch(e){showError(e.message);}
        });
        list.append(b);
      }
    } catch(e) { $("episodeList").innerHTML='<div class="empty">'+esc(e.message)+'</div>'; }
  }
  function chooseSource(next) {
    source=next; selected=null; episodes.classList.add("hidden"); cards.classList.remove("hidden");
    document.querySelectorAll(".tabs button").forEach(b=>b.classList.toggle("active",b.dataset.source===source));
    $("query").placeholder=source==="manual"?"Tempel URL .m3u8 atau .mp4…":source==="history"?"Riwayat tontonan":source==="watchlist"?"Daftar tontonan":"Cari judul tontonan…";
    $("hint").textContent=source==="manual"?"Tempel URL video langsung; hanya HTTP/HTTPS .m3u8 atau .mp4.":source==="anime"?"Cari anime melalui mirror keyless.":source==="history"?"Riwayat tontonan terakhir.":source==="watchlist"?"Judul yang kamu simpan.":"Cari drama China favorit tanpa API key.";
    if(source==="history"||source==="watchlist") $("query").value="";
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

"use strict";
(() => {
  const plugin = () => window.Capacitor?.Plugins?.Watch;
  const vpn = () => window.Capacitor?.Plugins?.Vpn;
  const networkScanner = () => window.Capacitor?.Plugins?.NetworkScanner;
  const $ = id => document.getElementById(id);
  let source = "melolo", lastQuery = "", debounce = 0, selected = null, lastScanText = "";
  const cards = $("cards"), episodes = $("episodes"), error = $("error");
  const esc = value => String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  const output = (id, value) => { $(id).textContent = String(value ?? ""); };
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
          action.addEventListener("click",async e=>{e.stopPropagation();try{await plugin().watchlist({action:"add",id:item.id,source:sourceId(item.source),title:item.title,cover:item.cover});action.textContent="✓ Tersimpan";}catch(err){showError(err.message);}});
        }
        card.querySelector(".meta").append(action);
      }
      cards.append(card);
    }
  }
  function sourceId(value){const v=String(value||source).toLowerCase();return v==="anime"||v==="manual"||v==="melolo"?v:v.includes("anime")?"anime":v.includes("manual")?"manual":"melolo";}
  function sourceLabel(value){const v=String(value||source).toLowerCase();return v==="history"?"Riwayat":v==="watchlist"?"Daftar":v==="anime"?"Anime":v==="manual"?"Manual":"Melolo";}
  async function search() {
    lastQuery=$("query").value.trim(); setBusy();
    try {
      const api=plugin(); if(!api) throw new Error("Native Watch plugin belum tersedia. Gunakan APK Android terbaru.");
      if(source==="history") { const r=await api.history(); renderItems(r.items||[]); return; }
      if(source==="watchlist") { const r=await api.watchlist(); renderItems(r.items||[]); return; }
      const r=await api.search({source,q:lastQuery,offset:0}); renderItems(r.items||[]);
    } catch(e) { showError(e.message); }
  }
  async function openEpisodes(item) {
    selected=item; $("seriesTitle").textContent=item.title||"Episode";
    cards.classList.add("hidden"); error.classList.add("hidden"); episodes.classList.remove("hidden");
    $("episodeList").innerHTML=Array.from({length:6},()=>'<div class="episode skeleton" style="height:44px"></div>').join("");
    try {
      const api=plugin(); if(!api) throw new Error("Native Watch plugin tidak tersedia.");
      const r=await api.episodes({source:sourceId(item.source),seriesId:item.id});
      const list=$("episodeList"); list.innerHTML="";
      if(!r.episodes?.length){list.innerHTML='<div class="empty">Episode belum tersedia.</div>';return;}
      for(const ep of r.episodes) {
        const b=document.createElement("button"); b.className="episode"; b.disabled=!!ep.locked;
        b.innerHTML='<span>'+String(ep.index+1).padStart(2,"0")+'</span><b>'+esc(ep.title)+'</b><span>'+(ep.locked?"TERKUNCI":"▶")+'</span>';
        b.addEventListener("click",async()=>{try{await api.play({source:sourceId(item.source),videoId:ep.videoId,seriesId:item.id,title:item.title+" · "+ep.title});}catch(e){showError(e.message);}});
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
  async function withVpn(action) {
    const api=vpn(); if(!api) throw new Error("Plugin VPN native belum tersedia di APK.");
    return action(api);
  }
  $("wgGenerate").addEventListener("click",async()=>{try{const r=await withVpn(api=>api.genKey());output("wgOutput","PrivateKey = "+r.privateKey+"\nPublicKey = "+r.publicKey+"\n\nSimpan private key secara rahasia. Masukkan private key ke [Interface] dan public key ke konfigurasi peer server.");}catch(e){output("wgOutput",e.message);}});
  $("wgSave").addEventListener("click",async()=>{try{const api=vpn();if(!api)throw Error("Plugin VPN tidak tersedia.");const config=$("wgConfig").value.trim();if(!config)throw Error("Konfigurasi masih kosong.");await api.save({config});output("wgOutput","Konfigurasi berhasil disimpan terenkripsi di Android Keystore.");}catch(e){output("wgOutput","Gagal menyimpan: "+e.message);}});
  $("wgUp").addEventListener("click",async()=>{try{const api=vpn();if(!api)throw Error("Plugin VPN tidak tersedia.");const permission=await api.prepare();if(!permission.granted){output("wgOutput","Izin VPN belum diberikan.");return;}const r=await api.up({config:$("wgConfig").value.trim()});output("wgOutput","WireGuard: "+r.status+" · "+r.name);}catch(e){output("wgOutput","Gagal: "+e.message);}});
  $("wgDown").addEventListener("click",async()=>{try{const r=await withVpn(api=>api.down());output("wgOutput","WireGuard: "+r.status);}catch(e){output("wgOutput",e.message);}});
  $("wgStatus").addEventListener("click",async()=>{try{const r=await withVpn(api=>api.status());output("wgOutput","WireGuard: "+r.status);}catch(e){output("wgOutput",e.message);}});
  $("scanStart").addEventListener("click",async()=>{try{const api=networkScanner();if(!api)throw Error("Network Scanner native tidak tersedia di APK.");const host=$("scanHost").value.trim();output("scanOutput","Memindai 50 port TCP umum pada "+host+"…");const r=await api.scan({host});lastScanText="Host: "+r.host+" ("+r.address+")\nMetode: "+r.method+"\nPort diperiksa: "+r.scannedPorts+" · terbuka: "+r.openCount+"\nDurasi: "+r.durationMs+" ms\n\n"+(r.ports||[]).map(p=>p.port+"/tcp  "+p.state+"  "+p.service).join("\n");if(!r.openCount)lastScanText+="\nTidak ada port terbuka yang terdeteksi pada daftar port ini.";output("scanOutput",lastScanText);}catch(e){output("scanOutput","Scan gagal: "+e.message);}});
  $("scanCopy").addEventListener("click",async()=>{try{if(!lastScanText)throw Error("Belum ada hasil scan untuk disalin.");await navigator.clipboard.writeText(lastScanText);output("scanOutput","Hasil scan berhasil disalin.");}catch(e){output("scanOutput",e.message);}});
  $("animeSave").addEventListener("click",async()=>{try{const url=$("animeBase").value.trim();const api=plugin();if(!api)throw Error("Plugin Watch tidak tersedia.");const r=await api.setAnimeBase({url});output("animeOutput",r.saved?"Mirror anime tersimpan.":"Gagal menyimpan mirror.");}catch(e){output("animeOutput",e.message);}});
  document.querySelectorAll(".tabs button").forEach(b=>b.addEventListener("click",()=>chooseSource(b.dataset.source)));
  $("query").addEventListener("input",()=>{clearTimeout(debounce);debounce=setTimeout(search,400);});
  $("query").addEventListener("keydown",e=>{if(e.key==="Enter")search();});
  $("searchBtn").addEventListener("click",search);$("refresh").addEventListener("click",search);$("retry").addEventListener("click",search);
  $("backCards").addEventListener("click",()=>{episodes.classList.add("hidden");cards.classList.remove("hidden");});
  search();
})();
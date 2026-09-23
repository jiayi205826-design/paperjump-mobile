const $ = selector => document.querySelector(selector);
const video = $('#camera');
const canvas = $('#overlay');
const ctx = canvas.getContext('2d', {willReadFrequently: true});
const DICT_4X4_50=[0xb532,0x0f9a,0x332d,0x9946,0x549e,0x79cd,0x9e2e,0xc4f2,0xfeda,0xcf56,0xf991,0x11a7,0x0eb7,0x2a0f,0x24b1,0x263e,0x4665,0x6600,0x6c5e,0x76af,0x868b,0xb02b,0xccd5,0xdd82,0xfe47,0x9471,0xace4,0xa554,0x2123,0x346f,0x4415,0x57b2,0x9ecf,0xf0cb,0x08ae,0x0929,0x1875,0x04ff,0x0df6,0x1c5a,0x1718,0x2a28,0x328c,0x38b2,0x24e8,0x2eeb,0x2d3f,0x4b64,0x502e,0x5013];

let stream;
let detector;
let hands;
let running = false;
let lastVideoTime = -1;
let lastOpen = 0;
let pages = {};
let nodesByPage = {};
let currentPageId = null;
let currentLocated = null;
let currentTransform = null;
let pendingNode = null;
let pickContext = null;
let editorContext = null;
let stablePageId = null;
let stableQuad = null;
let missedPageFrames = 0;

function setStatus(message) { $('#status').textContent = message; }
function normalizeText(value) { return String(value || '').replace(/\s+/g, ' ').trim(); }
function isHttp(value) { return /^https?:\/\//i.test(value || ''); }

function normalizePages(raw) {
  const result = {};
  for (const [pageId, record] of Object.entries(raw || {})) {
    if (Array.isArray(record?.markers) && record.markers.length === 4) {
      result[String(pageId)] = record.markers.map(Number);
    }
  }
  return result;
}

function mobileUrl(node) {
  const target = node.android_target || node.mobile_target || node.conversation_url || node.target || node.url || '';
  if (!isHttp(target)) return '';
  if ((node.target_type || node.type) === 'chatgpt') return target;
  const anchor = normalizeText(node.anchor);
  if (!anchor) return target;
  const base = target.includes(':~:text=') ? target.split(':~:text=')[0].replace(/#$/, '') : target;
  return base + (base.includes('#') ? ':~:text=' : '#:~:text=') + encodeURIComponent(anchor);
}

function normalizeNodes(raw) {
  const source = raw?.custom_nodes || raw || {};
  const nested = Object.values(source).some(value => value && typeof value === 'object' && !Array.isArray(value) && !('region' in value));
  const pageMaps = nested ? source : {'0': source};
  const result = {};
  for (const [pageId, pageNodes] of Object.entries(pageMaps)) {
    result[String(pageId)] = Object.entries(pageNodes || {}).map(([name, node]) => ({
      ...node,
      name: node.name || node.label || name,
      url: mobileUrl(node),
    })).filter(node => node.region && node.url);
  }
  return result;
}

function regionPolygon(region) {
  if (Array.isArray(region) && region.length === 4 && region.every(Number.isFinite)) {
    let [x1, y1, x2, y2] = region;
    if (Math.max(Math.abs(x1), Math.abs(x2)) > 1) { x1 /= 800; x2 /= 800; }
    if (Math.max(Math.abs(y1), Math.abs(y2)) > 1) { y1 /= 1000; y2 /= 1000; }
    return [{x:x1,y:y1},{x:x2,y:y1},{x:x2,y:y2},{x:x1,y:y2}];
  }
  if (Array.isArray(region)) return region.map(point => Array.isArray(point) ? {x:+point[0],y:+point[1]} : {x:+point.x,y:+point.y});
  return [];
}

function inside(point, polygon) {
  let hit = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const a = polygon[i], b = polygon[j];
    if (((a.y > point.y) !== (b.y > point.y)) && point.x < (b.x-a.x)*(point.y-a.y)/(b.y-a.y)+a.x) hit = !hit;
  }
  return hit;
}

function center(marker) {
  return marker.corners.reduce((sum, point) => ({x:sum.x+point.x/4,y:sum.y+point.y/4}), {x:0,y:0});
}

function locatePage(markers) {
  for (const [pageId, markerIds] of Object.entries(pages)) {
    const found = markerIds.map(id => markers.find(marker => marker.id === id));
    if (found.every(Boolean)) return {pageId, quad:found.map(center)};
  }
  return null;
}

function stabilizePage(located) {
  if (located) {
    if (stablePageId !== located.pageId || !stableQuad) {
      stablePageId = located.pageId;
      stableQuad = located.quad.map(point => ({...point}));
    } else {
      const alpha = .22;
      stableQuad = stableQuad.map((point, index) => ({
        x: point.x * (1 - alpha) + located.quad[index].x * alpha,
        y: point.y * (1 - alpha) + located.quad[index].y * alpha,
      }));
    }
    missedPageFrames = 0;
    return {pageId: stablePageId, quad: stableQuad};
  }
  if (stableQuad && missedPageFrames < 8) {
    missedPageFrames += 1;
    return {pageId: stablePageId, quad: stableQuad};
  }
  stablePageId = null;
  stableQuad = null;
  return null;
}

function solve8(matrix, values) {
  for (let i=0;i<8;i++) {
    let pivot=i;
    for (let row=i+1;row<8;row++) if (Math.abs(matrix[row][i])>Math.abs(matrix[pivot][i])) pivot=row;
    [matrix[i],matrix[pivot]]=[matrix[pivot],matrix[i]]; [values[i],values[pivot]]=[values[pivot],values[i]];
    const divisor=matrix[i][i]; if(Math.abs(divisor)<1e-9)return null;
    for(let column=i;column<8;column++)matrix[i][column]/=divisor; values[i]/=divisor;
    for(let row=0;row<8;row++)if(row!==i){const factor=matrix[row][i];for(let column=i;column<8;column++)matrix[row][column]-=factor*matrix[i][column];values[row]-=factor*values[i];}
  }
  return values;
}

function homography(quad) {
  const destination=[[0,0],[1,0],[1,1],[0,1]], matrix=[], values=[];
  quad.forEach((point,index)=>{const [u,v]=destination[index];matrix.push([point.x,point.y,1,0,0,0,-u*point.x,-u*point.y]);values.push(u);matrix.push([0,0,0,point.x,point.y,1,-v*point.x,-v*point.y]);values.push(v);});
  return solve8(matrix, values);
}

function mapPoint(transform, point) {
  const divisor=transform[6]*point.x+transform[7]*point.y+1;
  return {x:(transform[0]*point.x+transform[1]*point.y+transform[2])/divisor,y:(transform[3]*point.x+transform[4]*point.y+transform[5])/divisor};
}

function draw(markers, located, fingertip, active) {
  ctx.lineWidth=4; ctx.strokeStyle='#38bdf8';
  markers.forEach(marker=>{ctx.beginPath();marker.corners.forEach((point,index)=>index?ctx.lineTo(point.x,point.y):ctx.moveTo(point.x,point.y));ctx.closePath();ctx.stroke();});
  if(located){ctx.strokeStyle='#22c55e';ctx.beginPath();located.quad.forEach((point,index)=>index?ctx.lineTo(point.x,point.y):ctx.moveTo(point.x,point.y));ctx.closePath();ctx.stroke();}
  if(fingertip){ctx.fillStyle=active?'#f97316':'#facc15';ctx.beginPath();ctx.arc(fingertip.x,fingertip.y,12,0,Math.PI*2);ctx.fill();}
}

function renderNodeList(pageId) {
  if (currentPageId === pageId) return;
  currentPageId = pageId;
  $('#page-title').textContent = pageId === null ? '当前页面节点' : `Page ${pageId} 节点`;
  $('#nodes').replaceChildren();
  for (const node of nodesByPage[pageId] || []) {
    const link=document.createElement('a');link.className='node';link.href=node.url;link.target='_blank';link.rel='noopener';link.textContent=node.name;$('#nodes').append(link);
  }
}

function saveNodes() {
  localStorage.setItem('paperjump-nodes', JSON.stringify(nodesByPage));
}

function closeEditor() {
  $('#node-editor').hidden = true;
}

function beginNodeEditor() {
  if (!currentLocated) return setStatus('请先扫描并识别一张纸');
  editorContext = currentTransform ? {pageId: currentLocated.pageId, transform: [...currentTransform]} : null;
  $('#node-name').value = '';
  $('#node-url').value = '';
  $('#editor-help').textContent = '可粘贴纯链接，也可以直接粘贴小红书或 B 站的整段分享文字。';
  $('#editor-help').classList.remove('error');
  $('#node-editor').hidden = false;
  setTimeout(() => $('#node-url').focus(), 50);
}

function beginPositionPick() {
  const pasted = $('#node-url').value.trim();
  const matchedUrl = pasted.match(/https?:\/\/[^\s]+/i)?.[0];
  const url = matchedUrl?.replace(/[，。；、）》】）\]}>]+$/g, '') || '';
  if (!url) {
    $('#editor-help').textContent = '没有找到有效链接，请粘贴包含 http:// 或 https:// 的内容。';
    $('#editor-help').classList.add('error');
    return;
  }
  if (!editorContext) {
    $('#editor-help').textContent = '刚才的页面位置没有锁定，请关闭弹窗，重新识别纸张后再试。';
    $('#editor-help').classList.add('error');
    return;
  }
  let name = $('#node-name').value.trim();
  if (!name) {
    try { name = new URL(url).hostname.replace(/^www\./, ''); } catch { name = '新节点'; }
  }
  pendingNode = {name, url};
  pickContext = editorContext;
  editorContext = null;
  closeEditor();
  $('.viewer').classList.add('picking');
  $('#hint').hidden = false;
  $('#hint').textContent = '现在请在纸面画面上点一下，选择这个链接对应的位置。';
  setStatus('请选择纸面位置');
}

function placePendingNode(event) {
  if (!pendingNode || !pickContext) return;
  const box = canvas.getBoundingClientRect();
  const cameraPoint = {
    x: (event.clientX - box.left) * canvas.width / box.width,
    y: (event.clientY - box.top) * canvas.height / box.height,
  };
  const point = mapPoint(pickContext.transform, cameraPoint);
  if (!point || point.x < 0 || point.x > 1 || point.y < 0 || point.y > 1) {
    return setStatus('请点在识别出的纸张范围内');
  }
  const region = [
    Math.max(0, point.x - .07), Math.max(0, point.y - .05),
    Math.min(1, point.x + .07), Math.min(1, point.y + .05),
  ];
  const pageId = pickContext.pageId;
  nodesByPage[pageId] ||= [];
  nodesByPage[pageId].push({...pendingNode, region, target_type:'url'});
  saveNodes();
  const name = pendingNode.name;
  pendingNode = null;
  pickContext = null;
  $('.viewer').classList.remove('picking');
  $('#hint').hidden = true;
  currentPageId = undefined;
  renderNodeList(pageId);
  setStatus(`已保存节点：${name}`);
}

async function frame() {
  if(!running)return;
  requestAnimationFrame(frame);
  if(pendingNode)return;
  if(video.readyState<2||video.currentTime===lastVideoTime)return;
  lastVideoTime=video.currentTime;
  canvas.width=video.videoWidth;canvas.height=video.videoHeight;ctx.drawImage(video,0,0,canvas.width,canvas.height);
  const markers=detector.detect(ctx.getImageData(0,0,canvas.width,canvas.height));
  const located=stabilizePage(locatePage(markers));
  currentLocated=located;
  currentTransform=located?homography(located.quad):null;
  $('#add-node').disabled=!located;
  let fingertip, active;
  const result=hands ? hands.detectForVideo(video,performance.now()) : {landmarks:[]};
  if(result.landmarks?.[0]){
    const tip=result.landmarks[0][8];fingertip={x:tip.x*canvas.width,y:tip.y*canvas.height};
    if(located){const point=currentTransform&&mapPoint(currentTransform,fingertip);active=point&&(nodesByPage[located.pageId]||[]).find(node=>inside(point,regionPolygon(node.region)));}
  }
  ctx.clearRect(0,0,canvas.width,canvas.height);ctx.drawImage(video,0,0,canvas.width,canvas.height);draw(markers,located,fingertip,active);
  renderNodeList(located?.pageId ?? null);
  if(!pendingNode)setStatus(active?`${active.name}：准备打开`:located?`已识别 Page ${located.pageId}`:`找到 ${markers.length} 个角标`);
  if(active&&!pendingNode&&performance.now()-lastOpen>1800){lastOpen=performance.now();if(confirm(`打开“${active.name}”？`))window.open(active.url,'_blank','noopener');}
}

async function start() {
  try {
    if (!navigator.mediaDevices?.getUserMedia) throw new Error('当前浏览器不支持摄像头访问');
    setStatus('请允许使用摄像头');
    stream=await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:'environment'},width:{ideal:1280},height:{ideal:720}},audio:false});
    video.srcObject=stream;
    await video.play();
    running=true;
    $('#start').disabled=true;
    $('#stop').disabled=false;
    $('#hint').hidden=true;

    setStatus('相机已开启，正在加载识别');
    if(!window.AR)throw new Error('ArUco 识别库加载失败，请检查网络');
    window.AR.DICTIONARIES.DICT_4X4_50={nBits:16,tau:4,codeList:DICT_4X4_50};
    detector=new window.AR.Detector({dictionaryName:'DICT_4X4_50',maxHammingDistance:1});
    frame();
    const {FilesetResolver, HandLandmarker}=await import('https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.22-rc.20250304/vision_bundle.mjs');
    const vision=await FilesetResolver.forVisionTasks('https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.22-rc.20250304/wasm');
    hands=await HandLandmarker.createFromOptions(vision,{baseOptions:{modelAssetPath:'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task',delegate:'GPU'},runningMode:'VIDEO',numHands:1});
    setStatus('识别已就绪');
  } catch(error) {
    if (stream?.active) {
      setStatus(`相机可用，识别组件未加载：${error.message}`);
    } else {
      stream?.getTracks().forEach(track=>track.stop());
      stream=null;
      video.srcObject=null;
      running=false;
      $('#start').disabled=false;
      $('#stop').disabled=true;
      setStatus(`无法启动：${error.message}`);
    }
  }
}

function stop() {running=false;stream?.getTracks().forEach(track=>track.stop());hands?.close();video.srcObject=null;currentLocated=null;currentTransform=null;stablePageId=null;stableQuad=null;missedPageFrames=0;$('#add-node').disabled=true;$('#start').disabled=false;$('#stop').disabled=true;setStatus('已停止');}
async function readJson(file) { return JSON.parse(await file.text()); }

$('#start').onclick=start;
$('#stop').onclick=stop;
$('#add-node').onclick=beginNodeEditor;
$('#close-editor').onclick=closeEditor;
$('#pick-position').onclick=beginPositionPick;
canvas.addEventListener('pointerup', placePendingNode);
$('#pages-file').onchange=async event=>{try{pages=normalizePages(await readJson(event.target.files[0]));localStorage.setItem('paperjump-pages',JSON.stringify(pages));setStatus(`已导入 ${Object.keys(pages).length} 个 ArUco 页面`);}catch{setStatus('pages.json 无效');}};
$('#nodes-file').onchange=async event=>{try{nodesByPage=normalizeNodes(await readJson(event.target.files[0]));localStorage.setItem('paperjump-nodes',JSON.stringify(nodesByPage));currentPageId=undefined;renderNodeList(null);setStatus('节点数据已导入');}catch{setStatus('custom_nodes.json 无效');}};

const savedPages=localStorage.getItem('paperjump-pages');
const savedNodes=localStorage.getItem('paperjump-nodes');
pages=savedPages?JSON.parse(savedPages):normalizePages(await fetch('pages.json').then(response=>response.json()));
nodesByPage=savedNodes?JSON.parse(savedNodes):normalizeNodes(await fetch('custom_nodes.json').then(response=>response.json()));
renderNodeList(null);
if('serviceWorker'in navigator)navigator.serviceWorker.register('sw.js');

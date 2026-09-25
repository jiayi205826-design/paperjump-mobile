const CONFIG = {
  maxFeatures: 2500,
  ratioTest: .75,
  ransacThreshold: 5,
  minGoodMatches: 28,
  minInliers: 18,
  minInlierRatio: .42,
  minCoverage: .035,
  maxRmse: 5,
  minAreaRatio: .45,
  maxAreaRatio: 1.8,
  maxAlignmentError: .15,
  knownConfidence: .55,
  minMargin: .08,
};

let cvPromise;

export function loadOpenCv() {
  if (cvPromise) return cvPromise;
  cvPromise = new Promise((resolve, reject) => {
    const ready = async () => {
      try {
        let runtime = window.cv;
        if (runtime instanceof Promise) runtime = await runtime;
        if (runtime?.Mat) return resolve(runtime);
        runtime.onRuntimeInitialized = () => resolve(runtime);
      } catch (error) { reject(error); }
    };
    if (window.cv) return ready();
    const script = document.createElement('script');
    script.src = 'https://docs.opencv.org/4.x/opencv.js';
    script.async = true;
    script.onload = ready;
    script.onerror = () => reject(new Error('OpenCV.js 加载失败，请检查网络'));
    document.head.append(script);
  });
  return cvPromise;
}

function decodeBase64(value) {
  const raw = atob(value);
  const bytes = new Uint8Array(raw.length);
  for (let index = 0; index < raw.length; index += 1) bytes[index] = raw.charCodeAt(index);
  return bytes;
}

function polygonArea(points) {
  let sum = 0;
  for (let index = 0; index < points.length; index += 1) {
    const next = points[(index + 1) % points.length];
    sum += points[index].x * next.y - next.x * points[index].y;
  }
  return Math.abs(sum) / 2;
}

function convexHull(points) {
  const sorted = [...points].sort((a, b) => a.x - b.x || a.y - b.y);
  if (sorted.length < 3) return sorted;
  const cross = (o, a, b) => (a.x-o.x)*(b.y-o.y) - (a.y-o.y)*(b.x-o.x);
  const lower = [];
  for (const point of sorted) {
    while (lower.length >= 2 && cross(lower.at(-2), lower.at(-1), point) <= 0) lower.pop();
    lower.push(point);
  }
  const upper = [];
  for (const point of sorted.reverse()) {
    while (upper.length >= 2 && cross(upper.at(-2), upper.at(-1), point) <= 0) upper.pop();
    upper.push(point);
  }
  lower.pop(); upper.pop();
  return lower.concat(upper);
}

function isConvex(points) {
  if (points.length !== 4) return false;
  let sign = 0;
  for (let index = 0; index < 4; index += 1) {
    const a=points[index], b=points[(index+1)%4], c=points[(index+2)%4];
    const cross=(b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x);
    if (!cross) return false;
    if (!sign) sign=Math.sign(cross);
    else if (Math.sign(cross)!==sign) return false;
  }
  return true;
}

function project(h, point) {
  const d=h[6]*point.x+h[7]*point.y+h[8];
  return {x:(h[0]*point.x+h[1]*point.y+h[2])/d,y:(h[3]*point.x+h[4]*point.y+h[5])/d};
}

export class MobileNaturalRecognizer {
  constructor(bundle) {
    if (bundle?.format !== 'paperjump-natural-features-v1') throw new Error('Natural 特征包格式无效');
    this.pages = (bundle.pages || []).map(page => ({
      ...page,
      points: page.keypoints,
      descriptorBytes: decodeBase64(page.descriptors_base64),
    }));
    if (!this.pages.length) throw new Error('Natural 特征包中没有页面');
  }

  async detectPaper(imageData) {
    const cv=await loadOpenCv();
    const source=cv.matFromImageData(imageData),gray=new cv.Mat(),blurred=new cv.Mat();
    const masks=[];
    try{
      cv.cvtColor(source,gray,cv.COLOR_RGBA2GRAY);
      cv.GaussianBlur(gray,blurred,new cv.Size(5,5),0);
      const bright=new cv.Mat();
      cv.threshold(blurred,bright,0,255,cv.THRESH_BINARY+cv.THRESH_OTSU);
      const kernel=cv.getStructuringElement(cv.MORPH_RECT,new cv.Size(5,5));
      cv.morphologyEx(bright,bright,cv.MORPH_OPEN,kernel);
      cv.morphologyEx(bright,bright,cv.MORPH_CLOSE,kernel);
      kernel.delete();masks.push({method:'OTSU',mat:bright});
      const edges=new cv.Mat();
      cv.Canny(blurred,edges,50,150);
      const edgeKernel=cv.getStructuringElement(cv.MORPH_RECT,new cv.Size(3,3));
      cv.morphologyEx(edges,edges,cv.MORPH_CLOSE,edgeKernel);edgeKernel.delete();
      masks.push({method:'CANNY',mat:edges});
      const candidates=[];
      for(const route of masks)this.collectQuads(cv,route.mat,route.method,source.cols,source.rows,candidates);
      candidates.sort((a,b)=>b.score-a.score);
      return candidates[0]||null;
    }finally{source.delete();gray.delete();blurred.delete();masks.forEach(item=>item.mat.delete());}
  }

  collectQuads(cv,mask,method,width,height,candidates){
    const contours=new cv.MatVector(),hierarchy=new cv.Mat();
    try{
      cv.findContours(mask,contours,hierarchy,cv.RETR_EXTERNAL,cv.CHAIN_APPROX_SIMPLE);
      const imageArea=width*height;
      for(let index=0;index<contours.size();index+=1){
        const contour=contours.get(index),approx=new cv.Mat(),hull=new cv.Mat();
        try{
          const area=Math.abs(cv.contourArea(contour));
          const areaRatio=area/imageArea;
          if(areaRatio<.18)continue;
          const perimeter=cv.arcLength(contour,true);
          cv.approxPolyDP(contour,approx,.02*perimeter,true);
          if(approx.rows!==4||!cv.isContourConvex(approx))continue;
          const points=[];
          for(let row=0;row<4;row+=1){const p=approx.intPtr(row,0);points.push({x:p[0],y:p[1]});}
          const ordered=this.orderCorners(points);
          const border=ordered.some(p=>p.x<=3||p.y<=3||p.x>=width-4||p.y>=height-4);
          if(border&&areaRatio>.9)continue;
          const rect=cv.minAreaRect(contour),rectArea=Math.max(1,rect.size.width*rect.size.height);
          const rectangularity=Math.min(1,area/rectArea);
          cv.convexHull(contour,hull);
          const hullArea=Math.max(1,Math.abs(cv.contourArea(hull)));
          const solidity=Math.min(1,area/hullArea);
          const score=.5*Math.min(1,areaRatio/.65)+.27*rectangularity+.23*solidity-(border?.12:0);
          candidates.push({corners:ordered,method,area_ratio:areaRatio,rectangularity,solidity,score});
        }finally{contour.delete();approx.delete();hull.delete();}
      }
    }finally{contours.delete();hierarchy.delete();}
  }

  orderCorners(points){
    const sums=points.map(p=>p.x+p.y),diffs=points.map(p=>p.x-p.y);
    return [
      points[sums.indexOf(Math.min(...sums))],
      points[diffs.indexOf(Math.max(...diffs))],
      points[sums.indexOf(Math.max(...sums))],
      points[diffs.indexOf(Math.min(...diffs))],
    ].map(point=>({...point}));
  }

  async recognize(imageData, corners) {
    const cv = await loadOpenCv();
    const source = cv.matFromImageData(imageData);
    const candidates = [];
    try {
      for (const page of this.pages) {
        for (let turns=0; turns<4; turns+=1) {
          const ordered = corners.map((_, index) => corners[(index + turns) % 4]);
          candidates.push(this.score(cv, source, ordered, page, turns*90));
        }
      }
    } finally { source.delete(); }
    candidates.sort((a,b)=>b.confidence-a.confidence);
    const best=candidates[0];
    const second=candidates[1] || {confidence:0,page_id:null};
    const margin=best.confidence-second.confidence;
    const known = best.good_matches>=CONFIG.minGoodMatches
      && best.inliers>=CONFIG.minInliers
      && best.inlier_ratio>=CONFIG.minInlierRatio
      && best.coverage>=CONFIG.minCoverage
      && best.geometry_valid
      && best.confidence>=CONFIG.knownConfidence
      && margin>=CONFIG.minMargin;
    return {known,page_id:known?best.page_id:null,orientation:best.orientation,
      best_candidate:best.page_id,second_candidate:second.page_id,candidate_margin:margin,...best};
  }

  score(cv, source, corners, page, orientation) {
    const width=page.canonical_width, height=page.canonical_height;
    const src=cv.matFromArray(4,1,cv.CV_32FC2,corners.flatMap(point=>[point.x,point.y]));
    const dst=cv.matFromArray(4,1,cv.CV_32FC2,[0,0,width-1,0,width-1,height-1,0,height-1]);
    const warp=cv.getPerspectiveTransform(src,dst);
    const image=new cv.Mat(), gray=new cv.Mat(), mask=new cv.Mat();
    const keypoints=new cv.KeyPointVector(), descriptors=new cv.Mat();
    const refDescriptors=cv.matFromArray(page.descriptorBytes.length/32,32,cv.CV_8U,page.descriptorBytes);
    let matches, inlierMask, homography;
    try {
      cv.warpPerspective(source,image,warp,new cv.Size(width,height));
      cv.cvtColor(image,gray,cv.COLOR_RGBA2GRAY);
      const orb=new cv.ORB();
      if (orb.setMaxFeatures) orb.setMaxFeatures(CONFIG.maxFeatures);
      orb.detectAndCompute(gray,mask,keypoints,descriptors);
      orb.delete();
      const queryPoints=[];
      for(let i=0;i<keypoints.size();i+=1){const p=keypoints.get(i).pt;queryPoints.push({x:p.x,y:p.y});}
      if(descriptors.empty()||queryPoints.length<4)return this.empty(page,orientation);
      const matcher=new cv.BFMatcher(cv.NORM_HAMMING,false);
      matches=new cv.DMatchVectorVector();
      matcher.knnMatch(refDescriptors,descriptors,matches,2);
      matcher.delete();
      const good=[];
      for(let i=0;i<matches.size();i+=1){const pair=matches.get(i);if(pair.size()>=2){const a=pair.get(0),b=pair.get(1);if(a.distance<CONFIG.ratioTest*b.distance)good.push({queryIdx:a.queryIdx,trainIdx:a.trainIdx});}pair.delete();}
      if(good.length<4)return {...this.empty(page,orientation),good_matches:good.length};
      const sourcePoints=good.flatMap(match=>[page.points[match.queryIdx][0],page.points[match.queryIdx][1]]);
      const targetPoints=good.flatMap(match=>[queryPoints[match.trainIdx].x,queryPoints[match.trainIdx].y]);
      const sourceMat=cv.matFromArray(good.length,1,cv.CV_32FC2,sourcePoints);
      const targetMat=cv.matFromArray(good.length,1,cv.CV_32FC2,targetPoints);
      inlierMask=new cv.Mat();
      homography=cv.findHomography(sourceMat,targetMat,cv.RANSAC,CONFIG.ransacThreshold,inlierMask);
      sourceMat.delete();targetMat.delete();
      if(homography.empty())return {...this.empty(page,orientation),good_matches:good.length};
      const h=Array.from(homography.data64F?.length?homography.data64F:homography.data32F);
      const inlierSource=[], errors=[];
      for(let i=0;i<good.length;i+=1){if(inlierMask.ucharPtr(i,0)[0]){const p={x:sourcePoints[i*2],y:sourcePoints[i*2+1]};const q={x:targetPoints[i*2],y:targetPoints[i*2+1]};const mapped=project(h,p);inlierSource.push(p);errors.push((mapped.x-q.x)**2+(mapped.y-q.y)**2);}}
      const inliers=inlierSource.length, ratio=inliers/good.length;
      const rmse=inliers?Math.sqrt(errors.reduce((a,b)=>a+b,0)/inliers):Infinity;
      const coverage=inliers>=3?polygonArea(convexHull(inlierSource))/(width*height):0;
      const pageCorners=[{x:0,y:0},{x:width-1,y:0},{x:width-1,y:height-1},{x:0,y:height-1}];
      const projected=pageCorners.map(point=>project(h,point));
      const areaRatio=polygonArea(projected)/(width*height);
      const diagonal=Math.hypot(width,height);
      const alignment=projected.reduce((sum,p,index)=>sum+Math.hypot(p.x-pageCorners[index].x,p.y-pageCorners[index].y),0)/4/diagonal;
      const geometryValid=isConvex(projected)&&areaRatio>=CONFIG.minAreaRatio&&areaRatio<=CONFIG.maxAreaRatio&&rmse<=CONFIG.maxRmse&&alignment<=CONFIG.maxAlignmentError;
      let confidence=0;
      if(geometryValid){confidence=.30*Math.min(1,good.length/80)+.30*ratio+.15*Math.min(1,coverage/.18)+.10*Math.max(0,1-rmse/CONFIG.maxRmse)+.15*Math.max(0,1-alignment/CONFIG.maxAlignmentError);}
      return {page_id:page.page_id,orientation,good_matches:good.length,inliers,inlier_ratio:ratio,coverage,reprojection_rmse:rmse,geometry_valid:geometryValid,confidence};
    } finally {
      src.delete();dst.delete();warp.delete();image.delete();gray.delete();mask.delete();keypoints.delete();descriptors.delete();refDescriptors.delete();
      matches?.delete();inlierMask?.delete();homography?.delete();
    }
  }

  empty(page,orientation){return{page_id:page.page_id,orientation,good_matches:0,inliers:0,inlier_ratio:0,coverage:0,reprojection_rmse:null,geometry_valid:false,confidence:0};}
}

import {loadOpenCv} from './natural-recognizer.js?v=4';

function bytes(value) {
  const raw=atob(value);
  return Uint8Array.from(raw, char=>char.charCodeAt(0));
}

function project(h,x,y) {
  const d=h[6]*x+h[7]*y+h[8];
  return {x:(h[0]*x+h[1]*y+h[2])/d,y:(h[3]*x+h[4]*y+h[5])/d};
}

function area(points) {
  let sum=0;
  for(let i=0;i<points.length;i++) {
    const a=points[i],b=points[(i+1)%points.length];
    sum+=a.x*b.y-b.x*a.y;
  }
  return Math.abs(sum)/2;
}

function hull(points){
  const sorted=[...points].sort((a,b)=>a.x-b.x||a.y-b.y);
  const cross=(a,b,c)=>(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
  const lower=[],upper=[];
  for(const p of sorted){while(lower.length>1&&cross(lower.at(-2),lower.at(-1),p)<=0)lower.pop();lower.push(p);}
  for(const p of sorted.reverse()){while(upper.length>1&&cross(upper.at(-2),upper.at(-1),p)<=0)upper.pop();upper.push(p);}
  lower.pop();upper.pop();return lower.concat(upper);
}

export class MobileLocalRecognizer {
  constructor(anchors) {
    this.anchors=(anchors||[]).map(anchor=>({...anchor,bytes:bytes(anchor.descriptors_base64)}));
  }

  async recognize(imageData) {
    const cv=await loadOpenCv();
    const source=cv.matFromImageData(imageData),gray=new cv.Mat(),mask=new cv.Mat();
    const keypoints=new cv.KeyPointVector(),descriptors=new cv.Mat();
    let orb;
    try {
      cv.cvtColor(source,gray,cv.COLOR_RGBA2GRAY);
      orb=new cv.ORB(1200,1.2,8,8,0,2,0,15,10);
      orb.detectAndCompute(gray,mask,keypoints,descriptors);
      if(descriptors.empty())return null;
      const query=[];
      for(let i=0;i<keypoints.size();i++){
        const p=keypoints.get(i).pt;query.push([p.x,p.y]);
      }
      const matches=[];
      for(const anchor of this.anchors){
        const found=this.match(cv,anchor,query,descriptors);
        if(found)matches.push(found);
      }
      matches.sort((a,b)=>b.score-a.score);
      if(!matches.length)return null;
      const best=matches[0],runner=matches[1];
      if(runner&&runner.pageId!==best.pageId&&best.score-runner.score<3)return null;
      return best;
    }finally{
      source.delete();gray.delete();mask.delete();keypoints.delete();descriptors.delete();orb?.delete();
    }
  }

  match(cv,anchor,query,descriptors){
    const reference=cv.matFromArray(anchor.bytes.length/32,32,cv.CV_8U,anchor.bytes);
    const pairs=new cv.DMatchVectorVector();
    const matcher=new cv.BFMatcher(cv.NORM_HAMMING,false);
    let homography,inlierMask;
    try{
      matcher.knnMatch(reference,descriptors,pairs,2);
      const good=[];
      for(let i=0;i<pairs.size();i++){
        const pair=pairs.get(i);
        if(pair.size()>=2){
          const a=pair.get(0),b=pair.get(1);
          if(a.distance<.75*b.distance)good.push([a.queryIdx,a.trainIdx]);
        }
        pair.delete();
      }
      if(good.length<10)return null;
      const src=cv.matFromArray(good.length,1,cv.CV_32FC2,good.flatMap(([i])=>anchor.keypoints[i]));
      const dst=cv.matFromArray(good.length,1,cv.CV_32FC2,good.flatMap(([,i])=>query[i]));
      inlierMask=new cv.Mat();
      try{homography=cv.findHomography(src,dst,cv.RANSAC,3,inlierMask);}
      finally{src.delete();dst.delete();}
      if(homography.empty())return null;
      const h=Array.from(homography.data64F?.length?homography.data64F:homography.data32F);
      let inliers=0,error=0;
      const inlierPoints=[];
      for(let i=0;i<good.length;i++){
        if(!inlierMask.ucharPtr(i,0)[0])continue;
        inliers++;
        const [a,b]=good[i],p=anchor.keypoints[a],q=query[b],mapped=project(h,p[0],p[1]);
        inlierPoints.push({x:p[0],y:p[1]});
        error+=(mapped.x-q[0])**2+(mapped.y-q[1])**2;
      }
      if(inliers<10||inliers/good.length<.55||Math.sqrt(error/inliers)>3)return null;
      const w=anchor.canonical_width,hgt=anchor.canonical_height;
      if(area(hull(inlierPoints))/(w*hgt)<.06)return null;
      const quad=[[0,0],[w-1,0],[w-1,hgt-1],[0,hgt-1]].map(([x,y])=>project(h,x,y));
      if(!quad.every(p=>Number.isFinite(p.x)&&Number.isFinite(p.y)))return null;
      const ratio=area(quad)/(w*hgt);
      if(ratio<.3||ratio>30)return null;
      return {pageId:String(anchor.page_id),quad,score:inliers,confidence:inliers/good.length};
    }finally{reference.delete();pairs.delete();matcher.delete();homography?.delete();inlierMask?.delete();}
  }
}

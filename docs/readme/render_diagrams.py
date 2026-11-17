"""Render PNG and editable Excalidraw from the same diagram elements.
Requires Pillow. Set DIAGRAM_FONT to a Korean-capable TTF/TTC on other systems.
"""
import base64
import json
import math
import os
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
FONT = os.environ.get('DIAGRAM_FONT', '/System/Library/Fonts/AppleSDGothicNeo.ttc')
BG, INK, MUTED, BORDER = '#FAF9F7', '#202623', '#626D68', '#CCD4CF'
GREEN, BLUE, ORANGE = '#287565', '#326B9D', '#AD6728'
SCALE = 2

class Diagram:
    def __init__(self, width, height, title, subtitle):
        self.im = Image.new('RGB', (width*SCALE, height*SCALE), BG)
        self.draw = ImageDraw.Draw(self.im)
        self.elements, self.files = [], {}
        self.width, self.height = width, height
        self.text(60, 35, 'KNOT  /  PRODUCTION', 20, GREEN)
        self.text(60, 73, title, 38)
        self.text(60, 126, subtitle, 22, MUTED)

    def base(self, typ, x, y, w, h, color=INK, fill='transparent'):
        n=len(self.elements)+1
        e=dict(id=f'e{n}',type=typ,x=x,y=y,width=w,height=h,angle=0,
               strokeColor=color,backgroundColor=fill,fillStyle='solid',strokeWidth=2,
               strokeStyle='solid',roughness=0,opacity=100,groupIds=[],frameId=None,
               roundness=None,seed=n,version=1,versionNonce=n,isDeleted=False,
               boundElements=None,updated=1,link=None,locked=False)
        self.elements.append(e)
        return e

    def box(self,x,y,w,h,fill='#FFFFFF',color=BORDER):
        self.draw.rounded_rectangle((x*SCALE,y*SCALE,(x+w)*SCALE,(y+h)*SCALE),radius=14*SCALE,fill=fill,outline=color,width=2*SCALE)
        e=self.base('rectangle',x,y,w,h,color,fill); e['roundness']={'type':3}

    def text(self,x,y,s,size=24,color=INK):
        font=ImageFont.truetype(FONT,size*SCALE)
        lines=s.split('\n'); w=max(self.draw.textlength(t,font=font)/SCALE for t in lines)
        for i,line in enumerate(lines):
            self.draw.text((x*SCALE,(y+i*size*1.35)*SCALE),line,font=font,fill=color,anchor='lt')
        e=self.base('text',x,y,w,size*1.35*len(lines),color)
        e.update(text=s,originalText=s,fontSize=size,fontFamily=2,textAlign='left',verticalAlign='top',containerId=None,autoResize=True,lineHeight=1.35)

    def icon(self,name,x,y,size=52):
        p=ROOT/'icons'/f'{name}.png'
        im=Image.open(p).convert('RGBA'); im.thumbnail((size*SCALE,size*SCALE),Image.Resampling.LANCZOS)
        ox=(size*SCALE-im.width)//2; oy=(size*SCALE-im.height)//2
        self.im.paste(im,(round(x*SCALE)+ox,round(y*SCALE)+oy),im)
        self.files[name]=dict(id=name,mimeType='image/png',dataURL='data:image/png;base64,'+base64.b64encode(p.read_bytes()).decode(),created=1,lastRetrieved=1)
        e=self.base('image',x+ox/SCALE,y+oy/SCALE,im.width/SCALE,im.height/SCALE)
        e.update(fileId=name,status='saved',scale=[1,1])

    def card(self,x,y,w,h,title,body='',icon=None,fill='#FFFFFF',color=BORDER):
        first=len(self.elements); self.box(x,y,w,h,fill,color)
        if icon:
            self.icon(icon,x+20,y+20,46); self.text(x+80,y+26,title,25)
        else:self.text(x+22,y+24,title,25)
        if body:self.text(x+22,y+83 if icon else y+68,body,22,MUTED)
        group=f'card-{first}'
        for e in self.elements[first:]:e['groupIds']=[group]

    def arrow(self,pts,color=GREEN,label=None,lx=0,ly=0):
        self.draw.line([(round(x*SCALE),round(y*SCALE)) for x,y in pts],fill=color,width=3*SCALE,joint='curve')
        (px,py),(x,y)=pts[-2:]; a=math.atan2(y-py,x-px)
        tri=[(x,y),(x-13*math.cos(a-.45),y-13*math.sin(a-.45)),(x-13*math.cos(a+.45),y-13*math.sin(a+.45))]
        self.draw.polygon([(int(x*SCALE),int(y*SCALE)) for x,y in tri],fill=color)
        x0,y0=pts[0]; e=self.base('arrow',x0,y0,max(x for x,y in pts)-min(x for x,y in pts),max(y for x,y in pts)-min(y for x,y in pts),color)
        e.update(points=[[x-x0,y-y0] for x,y in pts],lastCommittedPoint=None,startBinding=None,endBinding=None,startArrowhead=None,endArrowhead='arrow',elbowed=False)
        if label:self.text(lx,ly,label,20,color)

    def save(self,name):
        for e in self.elements:
            if e['type']!='arrow':
                assert 0<=e['x'] and e['x']+e['width']<=self.width,(name,e.get('text'))
                assert 0<=e['y'] and e['y']+e['height']<=self.height,(name,e.get('text'))
        self.im.save(ROOT/f'{name}.png',optimize=True)
        (ROOT/f'{name}.excalidraw').write_text(json.dumps(dict(type='excalidraw',version=2,source='https://excalidraw.com',elements=self.elements,appState=dict(viewBackgroundColor=BG,gridSize=None),files=self.files),ensure_ascii=False,indent=2)+'\n')

a=Diagram(1600,1120,'서비스 아키텍처','웹 앱 · API · 데이터 저장 · 맥미니 AI 처리의 논리적 연결')
a.card(60,225,250,150,'사용자 브라우저','React 웹 앱 · 회의 녹음','client')
a.card(440,195,290,155,'Cloudflare Workers','정적 웹 앱 제공\nknoted.kr','cloudflare')
a.card(440,400,290,155,'Cloudflare Proxy','API HTTPS 요청\napi.knoted.kr','cloudflare')
a.box(820,195,360,400,'#F1F6F2')
a.icon('ec2',840,215,48);a.text(900,225,'Amazon EC2',28)
a.text(842,280,'단일 인스턴스 · Spring Boot',22,MUTED)
a.card(845,330,310,95,'Nginx',fill='#FFFFFF')
a.text(865,393,'활성 슬롯으로 라우팅',18,MUTED)
a.arrow([(1000,425),(1000,455)])
a.box(845,455,310,110,'#FFFFFF');a.text(863,474,'Blue :8080  /  Green :8081',21)
a.text(863,513,'배포 시 비활성 슬롯 기동·전환',20,MUTED)
a.card(1280,405,260,160,'Amazon RDS','PostgreSQL\n팀·문서·녹음 상태 저장','rds')
a.card(60,750,310,165,'Amazon S3','오디오 조각·최종 파일','s3')
a.box(820,745,720,275,'#F0F4FA','#BACDDD');a.text(845,765,'Mac mini',28,BLUE)
a.card(845,825,310,165,'Whisper 전사 서버','Whisper.cpp · sherpa-onnx\n전사·화자 분리')
a.card(1195,825,320,165,'로컬 LLM','LM Studio\n요약·문서 생성·탐색')
a.arrow([(310,270),(440,270)],label='웹 앱 요청',lx=318,ly=235)
a.arrow([(260,375),(260,477),(440,477)],label='API 호출',lx=315,ly=442)
a.arrow([(730,477),(800,477),(800,375),(845,375)])
a.arrow([(1180,480),(1280,480)],label='조회·저장',lx=1184,ly=443)
a.arrow([(120,375),(120,750)],label='서명 URL로\n직접 업로드',lx=142,ly=590)
a.arrow([(850,595),(850,650),(335,650),(335,750)],label='오디오 조회·저장',lx=430,ly=615)
a.arrow([(1000,595),(1000,825)],label='작업 요청·상태 조회',lx=1015,ly=675)
a.arrow([(1140,595),(1360,595),(1360,825)],label='생성·질의 요청',lx=1375,ly=660)
a.arrow([(845,905),(370,905)],label='서명 URL로 S3 오디오 다운로드',lx=448,ly=866)
a.text(60,1045,'화살표는 요청 방향이며 응답은 생략했습니다. 인증·분석·관측 등 보조 경로는 이 개요에서 생략했습니다.',21,MUTED)
a.save('architecture-prod')

c=Diagram(1600,1370,'CI / CD','PR 검증과 main 기준 운영 배포를 구분한 흐름')
c.card(60,245,240,140,'Pull Request','변경 영역에 따라 검증','github')
c.text(420,195,'GitHub Actions  /  PR 검증',25,GREEN)
c.card(420,250,330,225,'Frontend','Vitest 단위·통합\nQA 연결 + Playwright E2E\nE2E: main·develop 대상 PR')
c.card(790,250,330,225,'Backend','통합 테스트  ∥  나머지 검사\nSpotless → 단위 → 인수\n→ bootJar')
c.card(1160,250,380,225,'AI Server','runner 타입 검사·테스트\nGradle check + bootJar\nai-server 관련 변경 PR')
c.arrow([(300,315),(355,315),(355,230),(585,230),(585,250)])
c.arrow([(355,230),(955,230),(955,250)])
c.arrow([(955,230),(1350,230),(1350,250)])
c.text(420,492,'FE 검사는 변경 감지 조건 적용 · AI 검사는 경로 필터 적용 · BE 검사는 PR마다 실행',20,MUTED)
c.card(60,625,240,140,'GitHub main','운영 배포 소스','github')
c.text(420,560,'Frontend CD  /  관련 경로 push 또는 수동 실행',25,BLUE)
c.card(420,610,440,175,'GitHub Actions','의존성 설치 → pnpm build\nTypeScript 검사 + Webpack 빌드','github')
c.card(1190,610,350,175,'Cloudflare Workers','Wrangler · prod 환경 배포','cloudflare')
c.arrow([(300,675),(420,675)],BLUE)
c.arrow([(860,695),(1190,695)],BLUE,label='빌드한 웹 앱·Worker 배포',lx=900,ly=655)
c.box(420,850,1120,320,'#F4F1FA','#CFC3DC')
c.icon('codepipeline',445,875,48);c.text(510,888,'AWS CodePipeline  /  Backend CD',27)
c.card(445,955,400,185,'AWS CodeBuild','병렬: 통합 테스트 / 나머지 검사\nSpotless·단위·인수·bootJar\n두 작업 성공 후 Deploy 단계','codebuild')
c.card(900,955,240,185,'Amazon S3','배포 산출물\nJAR · AppSpec · hooks','s3')
c.card(1195,955,320,185,'AWS CodeDeploy','EC2 배포 hook 실행','codedeploy')
c.arrow([(180,765),(180,915),(420,915)],ORANGE,label='main 소스',lx=220,ly=880)
c.arrow([(845,1045),(900,1045)],ORANGE)
c.arrow([(1140,1045),(1195,1045)],ORANGE)
c.card(1100,1220,440,100,'EC2 · Nginx 슬롯 전환',icon='ec2',fill='#F1F6F2')
c.arrow([(1350,1140),(1350,1220)],ORANGE)
c.text(420,1210,'단일 EC2의 비활성 슬롯 기동 → 검증 → Nginx 전환',22,MUTED)
c.text(420,1250,'PR CI와 운영 CD는 별도 트리거입니다. CI 성공 연동을 뜻하지 않습니다.',20,MUTED)
c.text(60,1330,'AI 서버는 저장소에 정의된 PR 검증만 표시했습니다. 개발 환경 배포와 보조 자동화는 생략했습니다.',20,MUTED)
c.save('ci-cd-prod')
print('Rendered architecture-prod and ci-cd-prod (PNG + Excalidraw).')

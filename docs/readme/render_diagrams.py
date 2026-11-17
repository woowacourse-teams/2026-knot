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
BG, INK, MUTED, BORDER = '#FFFFFF', '#222222', '#666666', '#B7B7B7'
GREEN, BLUE, ORANGE = '#555555', '#555555', '#555555'
SCALE = 2

class Diagram:
    def __init__(self, width, height):
        self.im = Image.new('RGB', (width*SCALE, height*SCALE), BG)
        self.draw = ImageDraw.Draw(self.im)
        self.elements, self.files = [], {}
        self.width, self.height = width, height

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

    def centered(self, cx, y, value, size=22, color=INK):
        font=ImageFont.truetype(FONT,size*SCALE)
        for i,line in enumerate(value.split('\n')):
            width=self.draw.textlength(line,font=font)/SCALE
            self.text(cx-width/2,y+i*size*1.35,line,size,color)

    def node(self, icon, cx, y, name, note='', size=64):
        first=len(self.elements)
        self.icon(icon,cx-size/2,y+32-size/2,size)
        self.centered(cx,y+78,name,22)
        if note:self.centered(cx,y+110,note,18,MUTED)
        for e in self.elements[first:]:e['groupIds']=[f'node-{first}']

    def boundary(self,x,y,w,h):
        self.draw.rectangle((x*SCALE,y*SCALE,(x+w)*SCALE,(y+h)*SCALE),outline=BORDER,width=SCALE)
        e=self.base('rectangle',x,y,w,h,BORDER)
        e['strokeWidth']=1

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

# Read each lane independently. Repeated icons denote the same service.
a=Diagram(1440,1220)
a.text(45,30,'1. 웹 화면을 엽니다',25)
a.node('chrome',145,95,'Chrome')
a.node('cloudflare',580,95,'Cloudflare Workers','React 웹 앱 제공')
a.arrow([(190,127),(535,127)],label='화면 요청',lx=325,ly=92)
a.text(850,118,'받은 웹 앱이 브라우저에서 실행됩니다.',21,MUTED)

a.text(45,270,'2. API로 팀의 기록을 조회하고 저장합니다',25)
a.boundary(640,320,440,200)
a.icon('ec2',655,330,26);a.text(690,333,'하나의 EC2 인스턴스',19)
a.node('chrome',145,390,'Chrome')
a.node('cloudflare',415,390,'Cloudflare','API Proxy')
a.node('nginx',725,390,'Nginx')
a.node('spring',995,390,'Spring Boot')
a.node('rds',1295,390,'RDS','PostgreSQL')
a.arrow([(190,422),(370,422)],label='API 요청',lx=235,ly=387)
a.arrow([(460,422),(680,422)],label='HTTPS',lx=530,ly=387)
a.arrow([(770,422),(950,422)],label='활성 서버로 전달',lx=790,ly=387)
a.arrow([(1040,422),(1250,422)],label='조회 및 저장',lx=1090,ly=387)

a.text(45,570,'3. 회의 녹음을 텍스트로 바꿉니다',25)
a.node('chrome',145,640,'Chrome','회의 녹음')
a.node('s3',535,640,'S3','오디오 보관')
a.node('mac-mini',925,640,'Mac mini','Whisper 전사 / 화자 분리',size=112)
a.node('spring',1295,640,'Spring Boot','전사 결과 저장')
a.arrow([(190,672),(490,672)],label='서명 URL로 직접 업로드',lx=240,ly=637)
a.arrow([(580,672),(870,672)],label='다운로드한 오디오',lx=640,ly=637)
a.arrow([(980,672),(1250,672)],label='결과 조회에 대한 응답',lx=1015,ly=637)
a.text(45,810,'백엔드가 Mac mini에 전사를 요청하고 결과를 조회합니다. 이 줄의 화살표는 오디오와 전사 결과의 이동 방향입니다.',20,MUTED)

a.text(45,890,'4. 별도 LLM 서버에서 요약과 답변을 만듭니다',25)
a.node('spring',145,955,'Spring Boot')
a.node('cloudflare',625,955,'Cloudflare Tunnel')
a.node('windows',1135,955,'Windows PC','LM Studio')
a.arrow([(190,987),(580,987)],label='요약, 문서 생성, 탐색 요청',lx=255,ly=952)
a.arrow([(670,987),(1090,987)],label='별도 PC의 LLM으로 전달',lx=760,ly=952)
a.text(45,1120,'요약과 답변은 백엔드가 받아 저장하고 사용자에게 전달합니다.',20,MUTED)
a.text(45,1160,'각 줄은 주요 흐름을 설명합니다. 같은 이름의 아이콘은 같은 서비스를 반복해서 표시한 것입니다.',20,MUTED)
a.save('architecture-prod')

c=Diagram(1440,900)
c.text(45,30,'1. PR에서 변경 내용을 검증합니다',25)
c.node('github',145,100,'GitHub PR')
c.node('github-actions',595,100,'GitHub Actions')
c.arrow([(190,132),(550,132)],label='PR 생성 또는 갱신',lx=280,ly=97)
c.text(850,105,'프론트엔드, 백엔드, AI 서버',23)
c.text(850,143,'각 workflow의 조건에 따라 테스트와 검사 실행',20,MUTED)

c.text(45,310,'2. main의 프론트엔드를 배포합니다',25)
c.node('github',145,380,'GitHub main')
c.node('github-actions',595,380,'GitHub Actions','타입 검사 후 웹 앱 빌드')
c.node('cloudflare',1195,380,'Cloudflare Workers','운영 웹 앱')
c.arrow([(190,412),(550,412)],label='관련 파일 변경',lx=300,ly=377)
c.arrow([(640,412),(1150,412)],label='Wrangler로 배포',lx=820,ly=377)
c.text(45,550,'수동 실행도 지원합니다. PR 검증과 main 배포는 서로 다른 이벤트로 실행됩니다.',20,MUTED)

c.text(45,620,'3. main의 백엔드를 배포합니다',25)
c.boundary(380,670,720,200)
c.icon('codepipeline',395,680,26);c.text(435,683,'AWS CodePipeline',20)
c.node('github',145,720,'GitHub main')
c.node('codebuild',465,720,'CodeBuild','테스트 후 빌드')
c.node('s3',745,720,'S3','JAR와 배포 스크립트')
c.node('codedeploy',1035,720,'CodeDeploy')
c.node('ec2',1295,720,'EC2','Nginx의 활성 슬롯 전환')
c.arrow([(190,752),(420,752)],label='소스 가져오기',lx=230,ly=717)
c.arrow([(510,752),(700,752)],label='산출물 저장',lx=548,ly=717)
c.arrow([(790,752),(990,752)],label='산출물 전달',lx=835,ly=717)
c.arrow([(1080,752),(1250,752)],label='배포 실행',lx=1120,ly=717)
c.save('ci-cd-prod')
print('Rendered four architecture lanes and three CI/CD lanes.')

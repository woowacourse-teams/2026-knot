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

# Minimal icon diagrams. Keep implementation details in the accompanying README.
a=Diagram(1280,980)
a.node('chrome',100,230,'Chrome')
a.node('cloudflare',420,70,'Cloudflare Workers','React 웹 앱')
a.node('cloudflare',420,270,'Cloudflare','API Proxy')
a.boundary(660,215,360,205)
a.icon('ec2',680,228,28);a.text(720,232,'Amazon EC2',20)
a.node('nginx',735,285,'Nginx')
a.node('spring',935,285,'Spring Boot')
a.node('rds',1160,285,'Amazon RDS','PostgreSQL')
a.node('s3',100,590,'Amazon S3','녹음 오디오')
a.node('mac-mini',580,590,'Mac mini','Whisper / 화자 분리',size=112)
a.icon('cloudflare',903,600,64)
a.text(1000,622,'Cloudflare Tunnel',22)
a.node('windows',935,805,'Windows PC','LM Studio')
a.arrow([(135,250),(260,250),(260,102),(385,102)])
a.arrow([(135,270),(290,270),(290,302),(385,302)],label='API',lx=310,ly=268)
a.arrow([(455,302),(700,302)])
a.arrow([(770,317),(900,317)])
a.arrow([(970,317),(1125,317)])
a.arrow([(65,270),(45,270),(45,622),(60,622)],label='업로드',lx=65,ly=420)
a.arrow([(895,335),(855,335),(855,470),(240,470),(240,622),(140,622)],label='오디오 관리',lx=490,ly=440)
a.arrow([(895,335),(875,335),(875,520),(580,520),(580,600)],label='전사 요청 / 조회',lx=600,ly=490)
a.arrow([(975,335),(1010,335),(1010,540),(935,540),(935,595)],label='LLM 요청',lx=1030,ly=500)
a.arrow([(935,665),(935,800)])
a.arrow([(530,622),(530,650),(140,650)],label='다운로드 요청',lx=260,ly=675)
a.save('architecture-prod')

c=Diagram(1280,750)
c.node('github',120,60,'GitHub PR')
c.node('github-actions',440,60,'GitHub Actions')
c.arrow([(160,92),(400,92)])
c.arrow([(480,92),(715,92)])
c.text(745,72,'FE / BE / AI',23)
c.text(745,108,'테스트와 빌드 검증',20,MUTED)
c.node('github',120,300,'GitHub main')
c.node('github-actions',440,300,'GitHub Actions','FE 빌드')
c.node('cloudflare',1000,300,'Cloudflare Workers')
c.arrow([(160,332),(400,332)])
c.arrow([(480,332),(960,332)],label='FE 배포',lx=700,ly=300)
c.boundary(335,500,700,190)
c.icon('codepipeline',350,465,24);c.text(385,465,'AWS CodePipeline',21)
c.node('codebuild',425,540,'CodeBuild','테스트 후 빌드')
c.node('s3',670,540,'S3','배포 산출물')
c.node('codedeploy',915,540,'CodeDeploy')
c.node('ec2',1160,540,'EC2','Blue / Green')
c.arrow([(80,332),(45,332),(45,572),(385,572)],label='BE 배포',lx=180,ly=540)
c.arrow([(465,572),(630,572)])
c.arrow([(710,572),(875,572)])
c.arrow([(955,572),(1120,572)])
c.save('ci-cd-prod')
print('Rendered minimal PNG icon diagrams and editable Excalidraw sources.')

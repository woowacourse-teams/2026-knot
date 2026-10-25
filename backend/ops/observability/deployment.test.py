import contextlib, copy, hashlib, importlib.util, io, json, pathlib, shutil, tempfile, unittest
from unittest.mock import patch
import sys
sys.dont_write_bytecode=True
base=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('deployment',base/'deploy-environment-dashboards.py')
module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class DeploymentTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
  self.root=pathlib.Path(self.temp.name)/'root';self.stage=pathlib.Path(self.temp.name)/'stage'
  self.stage.mkdir();self.root.mkdir()
  paths=['provisioning/dashboards/knot.yml','provisioning/alerting/knot.json','provisioning/alerting/discord-templates.json']
  paths += [str(p.relative_to(base)) for p in (base/'provisioning/environment-dashboards').glob('*/*.json') if p.name != 'knot-dev-spring-observability.json']
  for name in paths:
   dest=self.stage/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(base/name,dest)
  (self.stage/'manifest.json').write_text(json.dumps({name:hashlib.sha256((self.stage/name).read_bytes()).hexdigest() for name in paths}))
  shutil.copytree(base/'dashboards',self.root/'dashboards')
  (self.root/'provisioning/dashboards').mkdir(parents=True)
  (self.root/'provisioning/alerting').mkdir(parents=True)
  provider=(base/'provisioning/dashboards/knot.yml').read_text().split('  - name: Knot-dev')[0]
  (self.root/'provisioning/dashboards/knot.yml').write_text(provider)
  self.old=json.loads((base/'provisioning/alerting/knot.json').read_text())
  self.old['contactPoints'][0]['receivers'][0]['settings'].pop('title')
  self.old['contactPoints'][0]['receivers'][0]['settings']['message']='previous message'
  for group in self.old['groups']:
   for rule in group['rules']:rule['annotations'].pop('display_name',None)
  (self.root/'provisioning/alerting/knot.json').write_text(json.dumps(self.old))
  (self.root/'secrets').mkdir();(self.root/'secrets/grafana-admin-password').write_text('synthetic-fixture-password')
  (self.root/'compose.yml').write_text('synthetic compose fixture')
  self.runtime=copy.deepcopy(self.old);self.templates=[];self.fail_reload=False
  self.common={p.name:p.read_bytes() for p in (self.root/'dashboards').glob('*.json')}
 def api(self,request,timeout):
  path=request.full_url.split(':3000',1)[1]
  if path=='/api/v1/provisioning/alert-rules': result=[r for g in self.runtime['groups'] for r in g['rules']]
  elif path=='/api/v1/provisioning/policies':result=self.runtime['policies'][0]
  elif path=='/api/v1/provisioning/contact-points':result=self.runtime['contactPoints'][0]['receivers']
  elif path=='/api/v1/provisioning/templates':result=[dict(item,template=item['template'].strip()) for item in self.templates]
  elif path=='/api/admin/provisioning/alerting/reload':
   if self.fail_reload:self.fail_reload=False;raise RuntimeError('simulated reload failure')
   self.runtime=json.loads((self.root/'provisioning/alerting/knot.json').read_text())
   for extra in (self.root/'provisioning/alerting').glob('*.json'):
    if extra.name!='knot.json':self.runtime['groups'].extend(json.loads(extra.read_text()).get('groups',[]))
   template=self.root/'provisioning/alerting/discord-templates.json'
   self.templates=json.loads(template.read_text())['templates'] if template.exists() else []
   result={'message':'reloaded'}
  elif path=='/api/admin/provisioning/dashboards/reload':result={'message':'reloaded'}
  elif path.startswith('/api/search'):
   result=[]
   for folder,location in [('Knot',self.root/'dashboards'),('Knot-dev',self.root/'provisioning/environment-dashboards/dev'),('Knot-prod',self.root/'provisioning/environment-dashboards/prod')]:
    for p in location.glob('*.json'):result.append({'uid':json.loads(p.read_text())['uid'],'folderTitle':folder})
  elif path.startswith('/api/dashboards/uid/'):result={'meta':{'provisioned':True}}
  else:raise AssertionError(path)
  return io.BytesIO(json.dumps(result).encode())
 def run_deploy(self,apply):
  inspect={'Mounts':[{'Destination':'/etc/grafana/provisioning','Source':str(self.root/'provisioning'),'RW':False},{'Destination':'/var/lib/grafana/dashboards','Source':str(self.root/'dashboards'),'RW':False}],'Config':{'Env':['synthetic=fixture','GF_SECURITY_ADMIN_USER=fixture-admin']}}
  with patch.object(module.os,'geteuid',return_value=0),patch.object(module.subprocess,'check_output',return_value=json.dumps([inspect]).encode()),patch.object(module.urllib.request,'urlopen',side_effect=self.api),contextlib.redirect_stdout(io.StringIO()):
   module.deploy(self.root,self.stage,apply,dev_count=7)
 def test_read_only_preflight(self):
  self.run_deploy(False)
  self.assertFalse(list(self.root.glob('environment-backup.*')))
  self.assertFalse((self.root/'provisioning/environment-dashboards').exists())
 def test_full_bundle_and_backup(self):
  self.run_deploy(True)
  backups=list(self.root.glob('environment-backup.*'));self.assertEqual(len(backups),1)
  self.assertEqual(backups[0].stat().st_mode & 0o777,0o700)
  self.assertTrue((backups[0]/'runtime-before.json').exists())
  self.assertTrue((backups[0]/'notification-templates-before.json').exists())
  self.assertEqual((self.root/'provisioning/environment-dashboards').stat().st_mode & 0o777,0o755)
  self.assertEqual(len(list((self.root/'provisioning/environment-dashboards/dev').glob('*.json'))),7)
  self.assertEqual(len(list((self.root/'provisioning/environment-dashboards/prod').glob('*.json'))),7)
  self.assertEqual({p.name:p.read_bytes() for p in (self.root/'dashboards').glob('*.json')},self.common)
 def test_reload_failure_restores_original_files(self):
  original=(self.root/'provisioning/alerting/knot.json').read_bytes()
  provider=(self.root/'provisioning/dashboards/knot.yml').read_bytes()
  self.fail_reload=True
  with self.assertRaisesRegex(RuntimeError,'simulated'):self.run_deploy(True)
  self.assertEqual((self.root/'provisioning/alerting/knot.json').read_bytes(),original)
  self.assertEqual((self.root/'provisioning/dashboards/knot.yml').read_bytes(),provider)
  self.assertFalse((self.root/'provisioning/alerting/discord-templates.json').exists())
  self.assertFalse((self.root/'provisioning/environment-dashboards/dev').exists())
 def test_operational_query_drift_blocks_before_backup(self):
  self.old['groups'][0]['rules'][0]['data'][0]['model']['expr']='vector(0)'
  (self.root/'provisioning/alerting/knot.json').write_text(json.dumps(self.old))
  with self.assertRaisesRegex(AssertionError,'configuration drift'):self.run_deploy(True)
  self.assertFalse(list(self.root.glob('environment-backup.*')))
 def test_additional_service_and_backup_rules_are_preserved(self):
  extra=base/'provisioning/alerting/operational-checks.json'
  shutil.copy2(extra,self.root/'provisioning/alerting'/extra.name)
  self.runtime['groups'].extend(json.loads(extra.read_text())['groups'])
  self.run_deploy(True)
  self.assertEqual(sum(len(g['rules']) for g in self.runtime['groups']),30)
  self.assertEqual((self.root/'provisioning/alerting'/extra.name).read_bytes(),extra.read_bytes())

unittest.main()

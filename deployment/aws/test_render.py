import importlib.util
import json
import pathlib
import tempfile
import unittest

HERE=pathlib.Path(__file__).parent
spec=importlib.util.spec_from_file_location('aws_render', HERE/'render.py')
r=importlib.util.module_from_spec(spec); spec.loader.exec_module(r)

class RenderTest(unittest.TestCase):
    def config(self):
        c=json.loads((HERE/'inventory.example.json').read_text())
        c['releaseDirectory']='/opt/surprising/releases/'+'a'*40
        for k in ['databasePassword','valkeyPassword','gatewayJwtSecret','accountInternalSecret']:
            c[k]='test-secret-'*4
        return c

    def test_all_products(self):
        for product in r.PRODUCTS:
            with self.subTest(product=product), tempfile.TemporaryDirectory() as tmp:
                c=self.config(); c['productLine']=product
                out=pathlib.Path(tmp)/'generated'; r.render(c,out)
                for node in range(3):
                    spec=json.loads((out/f'core{node}/core.json').read_text()); args=spec['arguments']
                    self.assertIn(f'-Dsurprising.aeron.node-id={node}',args)
                    self.assertIn('-Dsurprising.aeron.matching-engines=1',args)
                    self.assertIn('--add-opens=java.base/java.util.zip=ALL-UNNAMED',args)
                    unit=(out/f'core{node}/surprising-{product.lower()}-core.service').read_text()
                    self.assertIn('Restart=on-failure',unit)
                    self.assertIn('RestartSec=11',unit)
                    self.assertIn('StartLimitBurst=3',unit)
                    self.assertIn(f'-Dsurprising.realtime.directory=/dev/shm/aeron-surprising-{product.lower()}-{node}',args)
                    self.assertFalse(any('delete' in arg for arg in args))
                app=json.loads((out/'app/realtime.json').read_text())
                env=json.loads(app['environment']['SPRING_APPLICATION_JSON'])
                self.assertEqual(len(env['surprising.realtime.router.control-destinations'][product]),3)
                for name in ['account','trading','gateway']:
                    cfg=json.loads((out/f'app/{name}.json').read_text())
                    self.assertTrue(json.loads(cfg['environment']['SPRING_APPLICATION_JSON'])['surprising.realtime.enabled'])
                self.assertEqual((out/'app/realtime.json').stat().st_mode & 0o777,0o600)
                self.assertIn('-Dsurprising.aeron.probe-mode=query',json.loads((out/'app/probe.json').read_text())['arguments'])

    def test_reject_unsafe_inventory(self):
        mutations=[('coreHosts',['10.0.1.1']*3),('appHost','8.8.8.8'),('productLine','ALL'),
                   ('releaseDirectory','/tmp/latest'),('databasePassword','REPLACE_ME')]
        for key,value in mutations:
            with self.subTest(key=key):
                c=self.config(); c[key]=value
                with self.assertRaises(AssertionError): r.validate(c)

if __name__=='__main__': unittest.main()

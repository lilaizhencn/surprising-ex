package com.surprising.affiliate;
import com.surprising.affiliate.controller.AffiliateAdminController;
import com.surprising.affiliate.config.AffiliateProperties;
import com.surprising.affiliate.service.*;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.service.ProductTransferCoordinator;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import org.apache.catalina.startup.Tomcat;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import jakarta.servlet.http.*;
import static org.mockito.Mockito.*;
public class TeamAdminQaServer {
 @Configuration(proxyBeanMethods=false) @EnableWebMvc static class Web {}
 public static void main(String[] args)throws Exception {
  var f=new AffiliateTeamWorkload.Fixture();f.setup();
  var stopped=new java.util.concurrent.CountDownLatch(1);
  var tomcat=new Tomcat();GenericWebApplicationContext context=null;
  try {
   AffiliateTeamWorkload.seed(f,8,5,com.surprising.product.api.ProductLine.SPOT);
   var properties=f.context.getBean(AffiliateProperties.class);properties.setMaxPathNodes(64);
   f.accrual.accrueBatch("SPOT");
   var auth=mock(AuthService.class);
   when(auth.requireAdminPermission(anyString(),anyString())).thenReturn(new JwtPrincipal(9,"team-qa","NORMAL",List.of("ADMIN"),Instant.now().plusSeconds(3600)));
   var query=new AffiliateQueryService(f.repository,f.ledger,f.service);
   var money=new AffiliateSettlementService(f.repository,f.ledger,mock(ProductTransferCoordinator.class),f.manager,properties);
   Path base=Files.createTempDirectory(Path.of("/tmp/surprising-affiliate-teams-20261029"),"tomcat-");
   tomcat.setBaseDir(base.toString());tomcat.setHostname("127.0.0.1");tomcat.setPort(19096);
   var host=tomcat.addContext("",base.toString());
   context=new GenericWebApplicationContext();context.setServletContext(host.getServletContext());
   new AnnotatedBeanDefinitionReader(context).register(Web.class);
   context.registerBean(AffiliateAdminController.class,()->new AffiliateAdminController(auth,f.service,query,money));
   context.refresh();
   var servlet=new DispatcherServlet(context){
    @Override protected void service(HttpServletRequest req,HttpServletResponse res)throws java.io.IOException,jakarta.servlet.ServletException {
     res.setHeader("Access-Control-Allow-Origin","http://127.0.0.1:5414");res.setHeader("Access-Control-Allow-Credentials","true");
     res.setHeader("Access-Control-Allow-Headers",req.getHeader("Access-Control-Request-Headers")==null?"Authorization,Content-Type":req.getHeader("Access-Control-Request-Headers"));
     res.setHeader("Access-Control-Allow-Methods","GET,POST,OPTIONS");
     if("OPTIONS".equals(req.getMethod())){res.setStatus(204);return;}
     super.service(req,res);
    }
   };
   Tomcat.addServlet(host,"api",servlet).setLoadOnStartup(1);host.addServletMappingDecoded("/","api");
   Tomcat.addServlet(host,"repair",new HttpServlet(){
    @Override protected void doPost(HttpServletRequest req,HttpServletResponse res)throws java.io.IOException {
     properties.setMaxPathNodes(4096);f.jdbc.update("UPDATE affiliate_deferred_fees SET next_attempt_at='-infinity'");
     f.accrual.retryDeferred("SPOT");res.setStatus(200);res.getWriter().write("recovered");
    }
   });host.addServletMappingDecoded("/__qa/recover","repair");
   Tomcat.addServlet(host,"stop",new HttpServlet(){
    @Override protected void doPost(HttpServletRequest req,HttpServletResponse res)throws java.io.IOException {res.setStatus(200);res.flushBuffer();stopped.countDown();}
   });host.addServletMappingDecoded("/__qa/stop","stop");
   tomcat.getConnector().setProperty("address","127.0.0.1");tomcat.start();System.out.println("READY schema="+f.schema);
   stopped.await(10,java.util.concurrent.TimeUnit.MINUTES);
  } finally {tomcat.stop();tomcat.destroy();if(context!=null)context.close();f.cleanup();}
 }
}

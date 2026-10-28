package com.surprising.affiliate;
import java.nio.file.*;
import java.sql.Timestamp;
public class AffiliateSqlPlanProbe {
  static String sql(String source,String method) {
    int start=source.indexOf("\"\"\"",source.indexOf(method))+3;
    return source.substring(start,source.indexOf("\"\"\"",start));
  }
  public static void main(String[] args)throws Exception {
    var f=new AffiliateScaleProbe();
    try {
      f.setup();f.seed(1024,10000);
      String code=Files.readString(Path.of("src/main/java/com/surprising/affiliate/repository/AffiliateRepository.java"));
      var at=Timestamp.from(f.repository.now());
      var path=f.jdbc.queryForObject("EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) "+sql(code,"public List<PathNode> lineage"),String.class,1024L,at,at,at,at,at,at,4097);
      var counts=f.jdbc.queryForObject("EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) "+sql(code,"public List<Map<String, Object>> counts"),String.class,1L,1L);
      Files.writeString(Path.of(args[0]),"{\"path\":"+path+",\"teamCounts\":"+counts+"}");
    } finally {f.cleanup();}
  }
}

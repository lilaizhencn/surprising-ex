import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
class RepositoryMethodScan {
 static String csv(Object x){return "\""+String.valueOf(x).replace("\"","\"\"").replace("\n"," ")+"\"";}
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]).toAbsolutePath();var files=new ArrayList<Path>();
  try(var paths=Files.walk(root)){paths.filter(p->p.toString().contains("/src/main/java/")&&p.toString().endsWith("Repository.java")).sorted().forEach(files::add);}
  var compiler=ToolProvider.getSystemJavaCompiler();try(var manager=compiler.getStandardFileManager(null,null,null)){
   var task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none","-source","27"),null,manager.getJavaFileObjectsFromPaths(files));var trees=Trees.instance(task);var docs=DocTrees.instance(task);
   System.out.println("path,method,line,read_tables,write_tables,javadoc");
   for(var unit:task.parse()){
    class Finding {String method,doc;long line;Set<String> read=new TreeSet<>(),write=new TreeSet<>();}
    var findings=new LinkedHashMap<String,Finding>();
    new TreePathScanner<Void,Void>(){
     public Void visitLiteral(LiteralTree node,Void unused){
      if(node.getValue() instanceof String value){
       var current=getCurrentPath();while(current!=null&&!(current.getLeaf() instanceof MethodTree))current=current.getParentPath();
       String name=current==null?"<fields>":((MethodTree)current.getLeaf()).getName().toString();
       String key=current==null?name:name+":"+trees.getSourcePositions().getStartPosition(unit,current.getLeaf());
       var f=findings.get(key);if(f==null){f=new Finding();f.method=name;f.doc=current==null?"":String.valueOf(docs.getDocCommentTree(current));f.line=unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit,current==null?node:current.getLeaf()));findings.put(key,f);}
       var read=Pattern.compile("(?i)\\b(?:from|join)\\s+([a-z_][a-z0-9_]*)").matcher(value);while(read.find())f.read.add(read.group(1).toLowerCase(Locale.ROOT));
       var write=Pattern.compile("(?i)\\b(?:insert\\s+into|update|delete\\s+from)\\s+([a-z_][a-z0-9_]*)").matcher(value);while(write.find())f.write.add(write.group(1).toLowerCase(Locale.ROOT));
      }return super.visitLiteral(node,unused);
     }
    }.scan(unit,null);
    for(var f:findings.values())if(!f.read.isEmpty()||!f.write.isEmpty())System.out.println(csv(root.relativize(Path.of(unit.getSourceFile().toUri())))+","+csv(f.method)+","+csv(f.line)+","+csv(String.join(";",f.read))+","+csv(String.join(";",f.write))+","+csv(f.doc));
   }
  }
 }
}

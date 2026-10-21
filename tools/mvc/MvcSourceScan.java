import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import javax.lang.model.element.Modifier;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.security.*;

class MvcSourceScan {
    static String csv(Object value) { return "\"" + String.valueOf(value).replace("\"","\"\"") + "\""; }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();
        var files=new ArrayList<Path>();
        try(var walk=Files.walk(root)) {
            walk.filter(p->p.toString().endsWith(".java") && Files.isRegularFile(p))
                .filter(p->Arrays.stream(root.relativize(p).toString().split("/")).noneMatch(Set.of("target","build","node_modules",".git",".codegraph")::contains))
                .sorted().forEach(files::add);
        }
        var compiler=ToolProvider.getSystemJavaCompiler();
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            var task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","--enable-preview","-source","27"),null,manager.getJavaFileObjectsFromPaths(files));
            System.out.println("path,package,scope,types,annotations,controller_repository_fields,service_http_imports,scheduled,kafka,public_models_in_service,sql_tables,sha256");
            for(var unit:task.parse()) {
                Path path=Path.of(unit.getSourceFile().toUri());
                String relative=root.relativize(path).toString();
                String pkg=unit.getPackageName()==null?"":unit.getPackageName().toString();
                String source=Files.readString(path);
                var imports=unit.getImports().stream().map(x->x.getQualifiedIdentifier().toString()).toList();
                var types=new ArrayList<String>();var annotations=new TreeSet<String>();var repos=new TreeSet<String>();var models=new TreeSet<String>();var tables=new TreeSet<String>();int[] schedules={0},kafka={0};
                new TreePathScanner<Void,Void>() {
                    public Void visitClass(ClassTree node,Void unused) {
                        boolean top=getCurrentPath().getParentPath().getLeaf() instanceof CompilationUnitTree;
                        if(pkg.contains(".service") && node.getModifiers().getFlags().contains(Modifier.PUBLIC)
                                && (Set.of(Tree.Kind.RECORD,Tree.Kind.ENUM).contains(node.getKind())
                                    || node.getSimpleName().toString().endsWith("Exception")))
                            models.add(node.getKind()+":"+node.getSimpleName());
                        if(top) {
                            types.add(node.getKind()+":"+node.getSimpleName());
                            var anns=node.getModifiers().getAnnotations().stream().map(a->a.getAnnotationType().toString()).toList();annotations.addAll(anns);
                            boolean controller=pkg.contains(".controller")||node.getSimpleName().toString().endsWith("Controller")||anns.stream().anyMatch(a->a.endsWith("Controller"));
                            if(controller)for(var member:node.getMembers())if(member instanceof VariableTree field) {
                                String type=field.getType().toString();if(Pattern.compile("\\b(?:[\\w$]*Repository|JdbcTemplate|DataSource)\\b").matcher(type).find())repos.add(field.getName()+":"+type);
                            }
                            if(pkg.endsWith(".service") && Set.of(Tree.Kind.RECORD,Tree.Kind.ENUM).contains(node.getKind()) && node.getModifiers().getFlags().contains(Modifier.PUBLIC))models.add(node.getSimpleName().toString());
                        }
                        return super.visitClass(node,unused);
                    }
                    public Void visitMethod(MethodTree node,Void unused) {
                        for(var annotation:node.getModifiers().getAnnotations()) {
                            String name=annotation.getAnnotationType().toString();
                            if(name.endsWith("Scheduled"))schedules[0]++;
                            if(name.endsWith("KafkaListener"))kafka[0]++;
                        }
                        return super.visitMethod(node,unused);
                    }
                    public Void visitLiteral(LiteralTree node,Void unused) {
                        if(node.getValue() instanceof String value) {
                            var match=Pattern.compile("(?i)\\b(?:from|join|into|update)\\s+([a-z_][a-z0-9_]*)").matcher(value);
                            while(match.find())tables.add(match.group(1).toLowerCase(Locale.ROOT));
                        }
                        return super.visitLiteral(node,unused);
                    }
                }.scan(unit,null);
                var http = new TreeSet<String>();
                if(pkg.contains(".service")) {
                    imports.stream().filter(x->x.startsWith("jakarta.servlet")||x.startsWith("org.springframework.http")||x.startsWith("org.springframework.web")||x.startsWith("java.net.http")).forEach(http::add);
                    var qualified=Pattern.compile("(?:jakarta\\.servlet|org\\.springframework\\.(?:http|web)|java\\.net\\.http)(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+").matcher(source);
                    while(qualified.find())http.add(qualified.group());
                }
                Object[] values={relative,pkg,relative.contains("/src/test/")?"test":relative.contains("/src/main/")?"main":"other",String.join(";",types),String.join(";",annotations),String.join(";",repos),String.join(";",http),schedules[0],kafka[0],String.join(";",models),String.join(";",tables),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))};
                System.out.println(Arrays.stream(values).map(MvcSourceScan::csv).reduce((a,b)->a+","+b).orElse(""));
            }
        }
        for(var diagnostic:diagnostics.getDiagnostics())if(diagnostic.getKind()==Diagnostic.Kind.ERROR)System.err.println(diagnostic);
        long errors=diagnostics.getDiagnostics().stream().filter(x->x.getKind()==Diagnostic.Kind.ERROR).count();
        System.err.println("Parsed files="+files.size()+"; syntax errors="+errors);
        if(errors>0)System.exit(1);
    }
}

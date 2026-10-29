import java.nio.file.*;
import java.net.*;
import java.util.*;
import javax.tools.ToolProvider;
import com.macapk.loader.AppClassLoader;
public class ArtReflectionSmoke {
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("macapk-art-fields-");
        try {
            String code="""
                import java.lang.reflect.*;
                public class FieldsFixture {
                    public static final Object LOCK=null;
                    public static final long NUMBER=Long.parseLong("0");
                    private int ordinary;
                    static {
                        try {
                            Field lock=FieldsFixture.class.getDeclaredField("LOCK");lock.setAccessible(true);lock.set(null,new Object());
                            Field number=FieldsFixture.class.getDeclaredField("NUMBER");number.setAccessible(true);number.set(null,Integer.valueOf(42));
                            if(lock.get(null)!=LOCK || !Long.valueOf(42).equals(number.get(null))) throw new AssertionError("native field reads");
                        } catch(Exception ex) {throw new RuntimeException(ex);}
                    }
                    public static boolean check() throws Exception {
                        if(LOCK==null || NUMBER!=42) return false;
                        FieldsFixture obj=new FieldsFixture();
                        Field own=FieldsFixture.class.getDeclaredField("ordinary");own.set(obj,7);
                        if(obj.ordinary!=7 || !Integer.valueOf(7).equals(own.get(obj))) return false;
                        Field lock=FieldsFixture.class.getDeclaredField("LOCK");
                        try{lock.set(null,new Object());return false;}catch(IllegalAccessException expected){}
                        Field number=FieldsFixture.class.getDeclaredField("NUMBER");number.setAccessible(true);
                        try{number.set(null,1.5);return false;}catch(IllegalArgumentException expected){}
                        Field host=String.class.getDeclaredField("CASE_INSENSITIVE_ORDER");host.setAccessible(true);
                        try{host.set(null,null);return false;}catch(IllegalAccessException expected){}
                        return true;
                    }
                }
                """;
            Path source=dir.resolve("FieldsFixture.java");Files.writeString(source,code);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",dir.toString(),source.toString())!=0)throw new AssertionError("compile");
            try(AppClassLoader loader=new AppClassLoader(new URL[]{dir.toUri().toURL()},ArtReflectionSmoke.class.getClassLoader(),null,dir.toFile(),null,null)) {
                if(!Boolean.TRUE.equals(loader.loadClass("FieldsFixture").getMethod("check").invoke(null)))throw new AssertionError("field compatibility");
            }
            System.out.println("art-fields: PASS (real rewritten clinit, app lock, primitive widening, caller access, final denial, type mismatch, host denial)");
        } finally { try(var files=Files.walk(dir)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);} }
    }
}

import dalvik.system.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import com.macapk.loader.AppClassLoader;
public final class ReviewLoaderSmoke {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static Object paths(BaseDexClassLoader l)throws Exception{Field f=BaseDexClassLoader.class.getDeclaredField("pathList");f.setAccessible(true);return f.get(l);}
 static Field field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]);String mode=args[1];
  if(mode.equals("mixed")){
   Path src=root.resolve("DynamicBeta.java");Files.writeString(src,"package fixture; public class DynamicBeta { public static int value(){return 99;} }");
   check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21","-d",root.toString(),src.toString())==0,"compile");
   Path jar=root.resolve("mixed.jar");
   try(var out=new JarOutputStream(Files.newOutputStream(jar))){for(String n:new String[]{"classes.dex","fixture/DynamicBeta.class","marker"}){out.putNextEntry(new JarEntry(n));out.write(n.equals("classes.dex")?Files.readAllBytes(root.resolve("live.dex")):n.endsWith(".class")?Files.readAllBytes(root.resolve(n)):new byte[]{1});out.closeEntry();}}
   for(boolean mutate:new boolean[]{false,true})try(var l=new DexClassLoader(jar.toString(),null,null,ClassLoader.getPlatformClassLoader())){
    if(mutate){Object paths=paths(l);Field f=field(paths,"dexElements");Object[] old=(Object[])f.get(paths);f.set(paths,Arrays.copyOf(old,old.length+1));Object[] next=(Object[])f.get(paths);next[old.length]=old[old.length-1];}
    check((int)l.loadClass("fixture.DynamicBeta").getMethod("value").invoke(null)==43,"DEX must outrank original .class, mutated="+mutate);
    check(l.findResource("marker")!=null,"original resources");
   }
  }else if(mode.equals("resource")){
   Path apk=root.resolve("resources.apk");try(var out=new JarOutputStream(Files.newOutputStream(apk))){out.putNextEntry(new JarEntry("marker"));out.write(1);out.closeEntry();}
   Files.deleteIfExists(root.resolve("alias.apk"));Files.createSymbolicLink(root.resolve("alias.apk"),apk);
   try(var l=new AppClassLoader(new URL[0],ClassLoader.getPlatformClassLoader(),null,root.toFile(),null,apk.toString())){
    Object p = paths(l); Field ef = field(p,"dexElements"); Object[] initial = (Object[])ef.get(p);
    var ctor = initial[0].getClass().getDeclaredConstructor(File.class); ctor.setAccessible(true);
    Object[] expanded = Arrays.copyOf(initial, initial.length + 1); expanded[initial.length] = ctor.newInstance(root.toFile()); ef.set(p, expanded);
    String url=l.findResource("marker").toString();check(url.contains("alias.apk"),"APK URL spelling lost: "+url);
    var all=Collections.list(l.findResources("marker"));check(all.size()==1&&all.get(0).toString().contains("alias.apk"),"enumeration URL spelling");
    Object paths=paths(l);Field f=field(paths,"dexElements");Object[] old=(Object[])f.get(paths);f.set(paths,Arrays.copyOfRange(old,1,old.length));check(l.findResource("marker")==null,"removed source leaked");
   }
  }else if(mode.equals("closed")){
   try(var l=new PathClassLoader(root.toString(),ClassLoader.getPlatformClassLoader())){
    Object paths=paths(l);Method close=paths.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(paths);
    Object[] es=(Object[])field(paths,"dexElements").get(paths);
    var ctor = es[0].getClass().getDeclaredConstructor(File.class); ctor.setAccessible(true);
    es[0] = ctor.newInstance(root.toFile());
    l.addDexPath(root.toString());
    l.findResource("late-resource");
    check((boolean)field(es[0],"closed").get(es[0]),"post-close use registered an unclosed element");
   }
  }else if(mode.equals("suppressed")){
   try(var l=new PathClassLoader(root.toString(),ClassLoader.getPlatformClassLoader())){
    Object paths=paths(l);Object e=((Object[])field(paths,"dexElements").get(paths))[0];l.findResource("marker");
    field(e,"resources").set(e,new URLClassLoader(new URL[0]){public void close()throws IOException{throw new IOException("element");}});
    Field f=URLClassLoader.class.getDeclaredField("closeables");f.setAccessible(true);
    @SuppressWarnings("unchecked") Map<Closeable,Boolean> cs=(Map<Closeable,Boolean>)f.get(l);Closeable first=()->{throw new IOException("parent");};cs.put(first,Boolean.TRUE);
    try{l.close();throw new AssertionError("close succeeded");}catch(IOException ex){check(ex.getMessage().equals("parent")&&ex.getSuppressed().length==1&&ex.getSuppressed()[0].getMessage().equals("element"),"close failure ordering: "+ex);}
    field(e,"resources").set(e,null);
   }
  }
  System.out.println("PASS review loader "+mode);
 }
}

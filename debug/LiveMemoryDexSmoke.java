import dalvik.system.*;
import java.io.*;import java.lang.reflect.*;import java.nio.*;import java.nio.file.*;import java.util.*;
public class LiveMemoryDexSmoke {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {
  byte[] original=Files.readAllBytes(Path.of(args[0]));String[] offsets=Files.readString(Path.of(args[1])).split(" ");int a=Integer.parseInt(offsets[0]),b=Integer.parseInt(offsets[1]);
  for(boolean direct:new boolean[]{false,true}){
   ByteBuffer writable=direct?ByteBuffer.allocateDirect(original.length+6):ByteBuffer.allocate(original.length+6);
   writable.position(3);writable.put(original);for(int at:new int[]{a,b})for(int i=0;i<6;i++)writable.put(3+at+i,(byte)0);
   writable.position(3);writable.limit(3+original.length);ByteBuffer view=writable.asReadOnlyBuffer();
   Class<?> dp=Class.forName("dalvik.system.DexPathList");Method make=dp.getDeclaredMethod("makeInMemoryDexElements",ByteBuffer[].class,List.class);make.setAccessible(true);
   List<IOException> errors=new ArrayList<>();Object[] elements=(Object[])make.invoke(null,new ByteBuffer[]{view},errors);
   check(elements.length==1&&errors.isEmpty(),"valid metadata opens before method preparation: "+errors);
   try(PathClassLoader loader=new PathClassLoader("",ClassLoader.getPlatformClassLoader())){
    Field p=BaseDexClassLoader.class.getDeclaredField("pathList");p.setAccessible(true);Field e=dp.getDeclaredField("dexElements");e.setAccessible(true);e.set(p.get(loader),elements);
    try{loader.loadClass("fixture.DynamicAlpha");throw new AssertionError("unprepared body must not fabricate success");}catch(ClassNotFoundException expected){}
    for(int i=0;i<6;i++)writable.put(3+a+i,original[a+i]);
    Class<?> alpha=loader.loadClass("fixture.DynamicAlpha");check((int)alpha.getMethod("value").invoke(null)==42,"real restored Alpha body");
    for(int i=0;i<6;i++)writable.put(3+b+i,original[b+i]);
    Class<?> beta=loader.loadClass("fixture.DynamicBeta");check((int)beta.getMethod("value").invoke(null)==43,"later restored Beta body refreshes source");
    check(alpha.getClassLoader()==loader&&beta.getClassLoader()==loader,"defining loader identity");
    check(view.position()==3&&view.limit()==3+original.length,"caller cursor preserved");
   }
  }
  System.out.println("PASS live memory DEX: heap/direct, deferred preparation, retry, later mutation, identity, cursor");
 }
}

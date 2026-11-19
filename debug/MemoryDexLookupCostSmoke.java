import dalvik.system.*;
import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
/** Allocation budget guards against copying an entire DEX for each class. */
public class MemoryDexLookupCostSmoke {
 public static void main(String[] args)throws Exception {
  byte[] image=Arrays.copyOf(Files.readAllBytes(Path.of(args[0])),16*1024*1024);
  ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).putInt(32,image.length);
  Class<?> type=Class.forName("dalvik.system.DexPathList");
  Method make=type.getDeclaredMethod("makeInMemoryDexElements",ByteBuffer[].class,List.class);make.setAccessible(true);
  Object[] elements=(Object[])make.invoke(null,new ByteBuffer[]{ByteBuffer.wrap(image)},new ArrayList<>());
  Method bytes=elements[0].getClass().getDeclaredMethod("findClassBytes",String.class);bytes.setAccessible(true);
  for(int i=0;i<8;i++)bytes.invoke(elements[0],"fixture/DynamicBeta.class");
  var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
  long id=Thread.currentThread().threadId(),before=bean.getThreadAllocatedBytes(id),start=System.nanoTime();
  for(int i=0;i<8;i++)if(bytes.invoke(elements[0],"fixture/DynamicBeta.class")==null)throw new AssertionError("class missing");
  long allocated=bean.getThreadAllocatedBytes(id)-before;
  System.out.printf("memory-dex: 8 lookups, 16 MiB DEX, allocated=%.2f MiB, elapsed=%.2f ms%n",allocated/1048576.0,(System.nanoTime()-start)/1e6);
  if(allocated>64L*1024*1024)throw new AssertionError("lookup allocation scales with DEX size");
 }
}

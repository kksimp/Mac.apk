import com.macapk.loader.ArtReflection;
import java.lang.reflect.Field;
/** Microbenchmark of ordinary access routed through the real helper. */
public class ArtReflectionCostSmoke {
 public int publicValue=7;
 private int privateValue=7;
 static volatile long result;
 public static void main(String[] args)throws Exception {
  var target=new ArtReflectionCostSmoke();
  for(String name:new String[]{"publicValue","privateValue"}) {
   Field field=ArtReflectionCostSmoke.class.getDeclaredField(name);
   for(int r=0;r<3;r++){
    long start=System.nanoTime(),sum=0;
    for(int i=0;i<100000;i++){ArtReflection.set(field,target,i);sum+=(Integer)ArtReflection.get(field,target);}
    result=sum;
    if(r==2)System.out.printf("reflection %s: 100k get/set pairs %.2f ms%n",name,(System.nanoTime()-start)/1e6);
   }
  }
 }
}

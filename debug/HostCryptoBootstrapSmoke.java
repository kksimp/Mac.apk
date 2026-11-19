import com.macapk.loader.HostCrypto;
import java.nio.file.Path;
import java.security.Security;
public class HostCryptoBootstrapSmoke {
 public static void main(String[] args)throws Exception{
  String provider=System.getProperty("javax.net.ssl.trustStoreProvider");
  if(args[0].equals("failure")) Security.setProperty("keystore.type","missing-fixture-type");
  if(!args[0].equals("bare"))System.load(Path.of(args[1]).toAbsolutePath().toString());
  else {
   HostCrypto.capture();try(var scope=HostCrypto.use()){}
   try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
    Thread responder = new Thread(() -> {
     try (var socket = server.accept()) {
      var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
      while (!reader.readLine().isEmpty()) { }
      socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".getBytes());
     } catch (Exception ex) { throw new RuntimeException(ex); }
    }); responder.setDaemon(true);responder.start();
    var response = com.macapk.gms.GmsHttp.get("http://localhost:" + server.getLocalPort() + "/", java.util.Map.of());
    if (response.status != 200) throw new AssertionError("bare GMS HTTP request");
    responder.join(5000);
   }
  }
  if(!java.util.Objects.equals(provider,System.getProperty("javax.net.ssl.trustStoreProvider")))throw new AssertionError("global trustStoreProvider changed");
  System.out.println("PASS crypto bootstrap "+args[0]);
 }
}

import java.net.Socket;
import java.net.ServerSocket;

public class server2 {
  public static void main(String[] argv){
    
    if(argv.length!= 1){
      System.err.println("usage: server1 <hostport>");
      System.exit(1);
    }

    try{
      TCPStart.start();
      ServerSocket sock = new ServerSocket(Integer.parseInt(argv[0]));
      //for(int i = 0; i < 10; i++){
      
    	  
	      Socket connSock = sock.accept();
	      Socket connSock1 = sock.accept();
	
	      System.out.println("got socket "+connSock);
	
	      Thread.sleep(10*1000);
	      connSock.close();
	      connSock1.close();
	      
	      //Thread.sleep(10*1000);
	      
     // }
      sock.close();
      
    }
    catch(Exception e){
      System.err.println("Caught exception "+e);
    }
  }
}

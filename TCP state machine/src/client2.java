import java.net.Socket;

public class client2 {
  public static void main(String[] argv){
    
    if(argv.length!= 2){
      System.err.println("usage: client1 <hostname> <hostport>");
      System.exit(1);
    }

    try{
      TCPStart.start();
      //for(int i = 0; i < 10; i++){
	      Socket sock = new Socket(argv[0], Integer.parseInt(argv[1]));
	      Socket sock1 = new Socket(argv[0], Integer.parseInt(argv[1]));
	
	      System.out.println("got socket "+sock);
	      
	      Thread.sleep(10*1000);
	
	      sock.close();
	      sock1.close();
	      
	      //Thread.sleep(10*1000);
      //}
    }
    catch(Exception e){
      System.err.println("Caught exception:");
      e.printStackTrace();
    }
  }
}

import java.net.*;
import java.io.*;
import java.util.Hashtable;
import java.util.Timer;

class StudentSocketImpl extends BaseSocketImpl {

	
	/***
	 * Geoffrey Ringle Submission for Project 2
	 */
  // SocketImpl data members:
  //   protected InetAddress address;
  //   protected int port;
  //   protected int localport;
	
  boolean debug = false;

  private Demultiplexer D;
  private Timer tcpTimer;
  

  String Closed = "CLOSED";
  String Listen = "LISTEN";
  String SYN_RCVD = "SYN_RECVD";
  String FIN_WAIT_1 = "FIN_WAIT_1";
  String FIN_WAIT_2 = "FIN_WAIT_2";
  String Established = "ESTABLISEHD";
  String Closing = "CLOSING";
  String TIME_WAIT = "TIME_WAIT";
  String SYN_SENT =  "SYN_SENT";
  String CLOSE_WAIT = "CLOSE_WAIT";
  String LAST_ACK = "LAST_ACK";
  
  private String state = Closed;
  private int seqNumber = 100;
  private int ackNumber = 0;
  TCPTimerTask end = null;
  
   Hashtable<Integer, TCPTimerTask> timertable = new Hashtable();
   Hashtable<Integer, TCPPacket> packettable = new Hashtable();
   
   TCPPacket lastAck;
  

  StudentSocketImpl(Demultiplexer D) {  // default constructor
    this.D = D;
  }

  /**
   * Connects this socket to the specified port number on the specified host.
   *
   * @param      address   the IP address of the remote host.
   * @param      port      the port number.
   * @exception  IOException  if an I/O error occurs when attempting a
   *               connection.
   */
  public synchronized void connect(InetAddress address, int port) throws IOException{
    localport = D.getNextAvailablePort();
    this.port = port;
    this.address = address;
    
    D.registerConnection(address, localport, port, this);
    
	//TCPPacket tcppacket = new TCPPacket(localport, port, seqNumber, 0, false, true, false, 0, null);
	//seqNumber++;
	
	//TCPWrapper.send(tcppacket, address);
	sendPacket(false, true, false, 0, null);
	//TODO: remember if syn gets lost -intermediary step
	
	//state = "SYN_SENT";
	changeState(SYN_SENT);
	
	while(state != Established){
		try {
			this.wait();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	if(debug){
		System.out.println("*** Freed from connect ***");
	}
	
	return;
    
  }
  
  /**
   * Called by Demultiplexer when a packet comes in for this connection
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p){
	  
	  if(debug){
	  
		  /*System.out.println("!!! recieved TCPPacket: soureAddr: " + p.sourceAddr + ", sourcePort: " + p.sourcePort + ", destPort: " + p.destPort + ", seqNum: " + p.seqNum + ", ackNum: " + p.ackNum + ", ackFlag: " + p.ackFlag + ", rstFlag: " + p.rstFlag + ", synFlag: " + p.synFlag + ", finFlag: " + p.finFlag + ", windowSize: " + p.windowSize);
		  if(p.data != null){
			  System.out.println(p.data);
		  
		  }*/
		  //System.out.println(p);
		  
		  System.out.println("*** recieved packet from port: " + port + " ***");
	  }
	  
	  if(p.finFlag || p.synFlag){
		  seqNumber = p.ackNum;
		  ackNumber = p.seqNum + 1;   //ack num = next expected packet
	  }
	  //seqNumber = p.ackNum;
	  //ackNumber = p.seqNum +1;
	  if(p.ackFlag ){
		  if(debug){
			  System.out.println("*** ackNum: " + p.ackNum + " ***");
		  }
		  TCPTimerTask t = timertable.remove(p.ackNum-1);
		  if(t != null){
			  t.cancel();
		  }
		  packettable.remove(p.ackNum-1);
	  }
	  if(state==Listen && p.synFlag){
		  this.address = p.sourceAddr;
		  this.port = p.sourcePort;
		  
		  /***
		   * change this to "save" new connection, and accept() will turn into the normal connection and send ack
		   */
		  try {
				D.unregisterListeningSocket(localport, this);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		  try {
			D.registerConnection(address, localport, port, this);
		  } catch (IOException e) {
			  // TODO Auto-generated catch block
			  e.printStackTrace();
		  }
		  
		 
		  
		  //state ="SYN_RCVD";
		  //System.out.println("!!! Changed State: to SYN_RCVD");
		  changeState(SYN_RCVD);
		  
		  //TCPPacket tcppacket = new TCPPacket(localport, port, seqNumber, ackNumber, true, true, false, 0, null);
		  //TCPWrapper.send(tcppacket, address);
		  sendPacket(true, true, false, 0, null);

	  }else if(state == SYN_SENT && p.synFlag && p.ackFlag) {
		  changeState(Established);
		  sendPacket(true, false, false, 0, null);
		  
	  }else if(state == SYN_RCVD && p.ackFlag ){
		  changeState(Established);
		  
	  }else if(state == Established && p.finFlag){
		  
		  if(p.finFlag){
			  sendPacket(true, false, false, 0, null);
			  changeState(CLOSE_WAIT);
		  }
		  
	  }else if (state == FIN_WAIT_1){
		  if(debug){
			  System.out.println("*** recieved packet in " + FIN_WAIT_1+": packet: " + p);
			  System.out.println("*** test ***" + p.finFlag + p.ackFlag);
		  }
		  if(p.ackFlag){
			  changeState(FIN_WAIT_2);
			  sendPacket(true, false, false, 0, null);
			  
			  if(debug){
				  System.out.println("*** had ack");
			  }
		  }else if(p.finFlag){
			  changeState(Closing);
			  sendPacket(true, false, false, 0, null);
			  
			  if(debug){
				  System.out.println("*** had fin ***");
			  }
		  }else{
			  if(lastAck != null && address != null){
				  TCPWrapper.send(lastAck, address);
			  }
		  }
		  if(debug){
			  System.out.println("*** exiting FIN_WAIT_1 packet catch ***");
		  }
	  }else if (state == FIN_WAIT_2){
		  if(debug){
			  System.out.println("*** recieved packet in FIN_WAIT_2: " + p + " ***");
		  }
		  if(p.finFlag){
			  sendPacket(true, false, false, 0, null);
			  changeState(TIME_WAIT);
			  end = createTimerTask(30000, null);
		  }else if(!p.ackFlag || p.synFlag){
			  if(lastAck != null && address != null){
				  TCPWrapper.send(lastAck, address);
			  }
		  }
		 
	  }else if (state == Closing){
		  if(p.ackFlag){
			  sendPacket(true, false, false, 0, null);
			  changeState(TIME_WAIT);
			  end = createTimerTask(30000, null);
		  }else{
			  if(lastAck != null && address != null){
				  TCPWrapper.send(lastAck, address);
			  }
		  }
		  
	  }else if (state == CLOSE_WAIT && (p.synFlag||p.finFlag)){
		  TCPWrapper.send(lastAck, address);
	  }else if (state == LAST_ACK){
		  if(p.ackFlag){
			  sendPacket(true, false, false, 0, null);
			  changeState(TIME_WAIT);
			  end = createTimerTask(30000, null);
		  }else{
			  if(lastAck != null && address != null){
				  TCPWrapper.send(lastAck, address);
			  }
		  }
	  }else if (state == TIME_WAIT && (p.synFlag || p.finFlag)){
		  //sendPacket(true, false, false, 0, null);
		  TCPWrapper.send(lastAck, address);
		  end.cancel();
		  end = createTimerTask(30000, null);
		  
	  }else{
		  if(debug == true){
			  System.out.println("*** ERROR: packet not processed ***");
		  }
		  if(lastAck != null && address != null &&(p.synFlag || p.finFlag)){
			  TCPWrapper.send(lastAck, address);
		  } 
	  }
	  if(debug){
		  System.out.println("*** recievePacket: before notifyAll() ***");
	  }
	  System.out.flush();
	  this.notifyAll();
	  if(debug){
		  System.out.println("*** recievePacket: exiting ***");
	  }
	  
  }
  
  /** 
   * Waits for an incoming connection to arrive to connect this socket to
   * Ultimately this is called by the application calling 
   * ServerSocket.accept(), but this method belongs to the Socket object 
   * that will be returned, not the listening ServerSocket.
   * Note that localport is already set prior to this being called.
   */
  public synchronized void acceptConnection() throws IOException {
	  
	  seqNumber = 10;
	  ackNumber = 00;
	  //state = "LISTEN";
	  changeState(Listen);
	  
	  D.registerListeningSocket(localport, this);
	  while(state != Established){
		  try {
			this.wait();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	  }
	  if(debug){
		  System.out.println("*** Freed from acceptConnection ***");
	  }
  }

  
  /**
   * Returns an input stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an 
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     a stream for reading from this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               input stream.
   */
  public InputStream getInputStream() throws IOException {
    // project 4 return appIS;
    return null;
    
  }

  /**
   * Returns an output stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an 
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     an output stream for writing to this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               output stream.
   */
  public OutputStream getOutputStream() throws IOException {
    // project 4 return appOS;
    return null;
  }


  /**
   * Closes this socket. 
   *
   * @exception  IOException  if an I/O error occurs when closing this socket.
   */
  public synchronized void close() throws IOException {
	  if(debug){
		  System.out.println("*** State Before Close for socket: " + port +": " + state + " ***");
	  }
	  if(state == Established){
		  changeState(FIN_WAIT_1);
		  sendPacket(false, false, true, 0, null);
	  }else if(state == CLOSE_WAIT){
		  changeState(LAST_ACK);
		  sendPacket(false, false, true, 0, null);
	  }else{
		  if(debug){
			  System.out.println("*** Socket: " + port +": moving to closed, current state: " + state + " ***");
		  }
		  //changeState(Closed);
	  }
	  //potential spot for a wait
	  
	  
  }

  /** 
   * create TCPTimerTask instance, handling tcpTimer creation
   * @param delay time in milliseconds before call
   * @param ref generic reference to be returned to handleTimer
   */
  private TCPTimerTask createTimerTask(long delay, Object ref){
    if(tcpTimer == null)
      tcpTimer = new Timer(false);
    return new TCPTimerTask(tcpTimer, delay, this, ref);
  }


  /**
   * handle timer expiration (called by TCPTimerTask)
   * @param ref Generic reference that can be used by the timer to return 
   * information.
   */
  public synchronized void handleTimer(Object ref){

    // this must run only once the last timer (30 second timer) has expired
	if(ref instanceof TCPPacket){
		TCPPacket tcppacket = (TCPPacket) ref;
		TCPWrapper.send(tcppacket, address);
		timertable.remove(tcppacket.seqNum);
		packettable.remove(tcppacket.seqNum);
		TCPTimerTask t = createTimerTask(1000, tcppacket);
		timertable.put(tcppacket.seqNum, t);
		packettable.put(tcppacket.seqNum, tcppacket);
		if(debug){
			System.out.println("*** Resending packet: " + tcppacket + " ***");
		}
		
		
	}else{
		tcpTimer.cancel();
		tcpTimer = null;
		timertable.clear();
		packettable.clear();
		seqNumber = 100;
		ackNumber = 0;
		changeState(Closed);
		address = null;
		
	}
  }
  
  private void changeState(String newState){
	  System.out.print("!!! " + state + "->" + newState + "\n");
	  state = newState;
	  if(debug){
		  System.out.println("***"+port +" changed state ***");
	  }
	  System.out.flush();
  } 
	  
	  /**
	   * 
	   * @param ackBool - boolean to indicate acking packet and ack number is valid
	   * @param synBool - boolean to indicate syn packet
	   * @param finBool - boolean if will be finishing connection
	   * @param windowSize - size of window
	   * @param data - data for packet
	   */
  private void sendPacket(boolean ackBool, boolean synBool, boolean finBool, int windowSize, byte[] data){
	  if(debug){
		  System.out.println("*** Begin making packet: ackBool: " +  ackBool+" synBool: " + synBool+" finBool: " + finBool +" windowSize: "+ windowSize +" data: "+ data +" localPort: "+ localport + " port: "+ port + " seqNumber: "+ seqNumber +" ackNumber: "+ ackNumber + " address:  " +address + " ***");
	  }
	  TCPPacket tcppacket = new TCPPacket(localport, port, seqNumber, ackNumber, ackBool, synBool, finBool, windowSize, data);
	  if(debug){
		  System.out.println("*** made packet: "+ tcppacket + " ***");
	  }
	  TCPWrapper.send(tcppacket, address);
	  
	  //System.out.print("\n\n *** packet sent: " + tcppacket + " *** delete\n\n");
	  if(synBool || finBool){
		  
	  
		  TCPTimerTask t = createTimerTask(1000, tcppacket);
		  timertable.put(tcppacket.seqNum, t);
		  packettable.put(tcppacket.seqNum, tcppacket);
		  if(debug){
			  System.out.println("*** Created timer for " + tcppacket + " ***");
		  }
	  } 
	  
	  if(ackBool && !synBool && !finBool){
		  lastAck = tcppacket;
	  }
	  
	  if(debug){
		  System.out.println("*** State:" + state + " sent packet: " + tcppacket + " ***");
	  }
  }
  
}

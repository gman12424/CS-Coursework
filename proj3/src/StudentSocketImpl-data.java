import java.net.*;
import java.io.*;
import java.util.*;

/**
 * 
 * @author gnringle
 *
 */

class StudentSocketImpl extends BaseSocketImpl {

  // SocketImpl data members:
  //   protected InetAddress address;
  //   protected int port;
  //   protected int localport;
  boolean debug = false;
  
  
  private Demultiplexer D;
  private Timer tcpTimer;

  private PipedOutputStream appOS;
  private PipedInputStream appIS;

  private PipedInputStream pipeAppToSocket;
  private PipedOutputStream pipeSocketToApp;

  private SocketReader reader;
  private SocketWriter writer;

  private boolean terminating = false;

  private InfiniteBuffer sendBuffer;
  private InfiniteBuffer recvBuffer;
  
  private int state;
  	
    private int base;
    private int N = 8;
	private int seqNum;
	private int ackNum;
	private Hashtable<Integer, TCPTimerTask> timerList; //holds the timers for sent packets
	private Hashtable<Integer, TCPPacket> packetList;	  //holds the packets associated with each timer 
								  //(for timer identification)
	private boolean wantsToClose = false;
	private boolean finSent = false;

	private static final int CLOSED = 0;
	private static final int SYN_SENT = 1;
	private static final int LISTEN = 2;
	private static final int SYN_RCVD = 3;
	private static final int ESTABLISHED = 4;
	private static final int FIN_WAIT_1 = 5;
	private static final int FIN_WAIT_2 = 6;
	private static final int CLOSING = 7;
	private static final int CLOSE_WAIT = 8;
	private static final int LAST_ACK = 9;
	private static final int TIME_WAIT = 10;


  StudentSocketImpl(Demultiplexer D) {  // default constructor
    this.D = D;
    state = CLOSED;
	seqNum = -1;
	ackNum = -1;
	timerList = new Hashtable<Integer, TCPTimerTask>();
	packetList = new Hashtable<Integer, TCPPacket>();
    try {
      pipeAppToSocket = new PipedInputStream();
      pipeSocketToApp = new PipedOutputStream();
      
      appIS = new PipedInputStream(pipeSocketToApp);
      appOS = new PipedOutputStream(pipeAppToSocket);
    }
    catch(IOException e){
      System.err.println("unable to create piped sockets");
      System.exit(1);
    }


    initBuffers();

    reader = new SocketReader(pipeAppToSocket, this);
    reader.start();

    writer = new SocketWriter(pipeSocketToApp, this);
    writer.start();
  }
  
  
  private String stateString(int inState){
		if(inState == 0){
			return "CLOSED";
		}
		else if(inState == 1){
			return "SYN_SENT";
		}
		else if(inState == 2){
			return "LISTEN";
		}
		else if(inState == 3){
			return "SYN_RCVD";
		}
		else if(inState == 4){
			return "ESTABLISHED";
		}
		else if(inState == 5){
			return "FIN_WAIT_1";
		}
		else if(inState == 6){
			return "FIN_WAIT_2";
		}
		else if(inState == 7){
			return "CLOSING";
		}
		else if(inState == 8){
			return "CLOSE_WAIT";
		}
		else if(inState == 9){
			return "LAST_ACK";
		}
		else if(inState == 10){
			return "TIME_WAIT";
		}
		else
			return "Invalid state number";
	}
  private synchronized void changeToState(int newState){
		System.out.println("!!! " + stateString(state) + "->" + stateString(newState));
		state = newState;

		if(newState == CLOSE_WAIT && wantsToClose && !finSent){
			try{
				close();
			}
			catch(IOException ioe){}
		}
		else if(newState == TIME_WAIT){
			createTimerTask(30000, null);
		}
		this.notifyAll();
	}

	private synchronized void sendPacket(TCPPacket inPacket, boolean resend){
		if(inPacket.ackFlag == true && inPacket.synFlag == false){
			inPacket.seqNum = -2;
		}
		
		if(resend == false){ //new timer, and requires the current state as a key
			TCPWrapper.send(inPacket, address);

			//only do timers for syns, syn-acks, and fins
			if(inPacket.synFlag == true || inPacket.finFlag == true || inPacket.data != null){
				System.out.println("Creating new TimerTask for packet " + inPacket.seqNum);
				timerList.put(inPacket.seqNum,createTimerTask(1000, inPacket));
				packetList.put(inPacket.seqNum, inPacket);
				
			}
		}
		else{ //the packet is for resending, and requires the original state as the key
			
			System.out.println("Recreating TimerTask for packet " + inPacket.seqNum);
			TCPWrapper.send(inPacket, address);
			timerList.put(inPacket.seqNum,createTimerTask(1000, inPacket));
			
			/*Enumeration keyList = timerList.keys();
			Integer currKey = new Integer(-1);
			
			try{
				for(int i = 0; i<10; i++){
					currKey = (Integer)keyList.nextElement();

					if(packetList.get(currKey) == inPacket){
						
						break;
					}
				}
			}
			catch(NoSuchElementException nsee){
			}*/
		}
	}

	private synchronized void incrementCounters(TCPPacket p){
		ackNum = p.seqNum + 1;

		if(p.ackNum != -1)
			seqNum = p.ackNum;
	}

	private synchronized void cancelPacketTimer(TCPPacket p){
		//must be called before changeToState is called!!!
		TCPTimerTask t;
		
		for(int i = 0; i < p.ackNum; i++){
			t = timerList.get(i);
			if(t!= null){
				if(debug){
					System.out.print("Canceled PacketTimer: " + i);
				}
				t.cancel();
				timerList.remove(i);
				
				packetList.remove(i);
				
				base++;
				if(debug){
					System.out.println(" base: " + base);
				}
				
			}
		
		}
		
		/*if(state != CLOSING){
			timerList.get(p.ackNum-1).cancel();
			timerList.remove(p.ackNum-1);
			packetList.remove(p.ackNum-1);
		}
		else{
			//the only time the state changes before an ack is received... so it must 
			//look back to where the fin timer started
			timerList.get(FIN_WAIT_1).cancel();
			timerList.remove(FIN_WAIT_1);
			packetList.remove(FIN_WAIT_1);
		}*/
	}
  /**
   * initialize buffers and set up sequence numbers
   */
  private void initBuffers(){
	  
	  sendBuffer = new InfiniteBuffer();
	  recvBuffer = new InfiniteBuffer();
  }

  int getAmountInBuffer(InfiniteBuffer buf){
	  
	  if(buf.getBase() < buf.getNext()){
		  return buf.getNext() - buf.getBase();
		  
	  }else if(buf.getNext() < buf.getBase()){
		  return buf.getBase() - buf.getNext();
	  }else{
	  
		  return 0;
	  }
  }
  
  int getRoomInBuffer(InfiniteBuffer buf){
	  return buf.getBufferSize() - getAmountInBuffer(buf);
  }
  
  
  synchronized void sendData(){
	  int sizeOfData;
	  while(state != ESTABLISHED){
		  try {
			this.notifyAll();
			this.wait();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	  }
	  sizeOfData = getAmountInBuffer(sendBuffer);
	  if(seqNum<base + N && (state == ESTABLISHED || state == CLOSE_WAIT) && sizeOfData > 0){
		  byte[] data = new byte[sizeOfData];
		  sendBuffer.copyOut(data, sendBuffer.getBase(), getAmountInBuffer(sendBuffer));
		  sendBuffer.advance(sizeOfData);
		  if(debug){
			  String s = new String(data);
		  
			  System.out.println("from sendBuffer: " + s);
		  }
		  TCPPacket dataPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, false, sizeOfData, data);
		  sendPacket(dataPacket, false);
		  seqNum++;
	  
	  }
	  
	  
	  
  }
  
  
  /**
   * Called by the application-layer code to copy data out of the 
   * recvBuffer into the application's space.
   * Must block until data is available, or until terminating is true
   * @param buffer array of bytes to return to application
   * @param length desired maximum number of bytes to copy
   * @return number of bytes copied (by definition > 0)
   */
  synchronized int getData(byte[] buffer, int length){
	  
	  
	  
	  int sizeOfData = getAmountInBuffer(recvBuffer);
	  while(sizeOfData < 1 ){
		  try {
			this.wait();
			sizeOfData = getAmountInBuffer(recvBuffer);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	  }
	  
	  if(terminating && sizeOfData <1){
		  return 0;
	  }
	  if(debug){
		  System.out.println("getData called");
	  }
	  byte[] data = new byte[sizeOfData];
	  
	  
	  if(sizeOfData < length){
		  recvBuffer.copyOut(data, recvBuffer.getBase(), sizeOfData);
		  recvBuffer.advance(sizeOfData);
		  for(int i = 0; i < sizeOfData; i++){
			  buffer[i] = data[i];
		  }
		  String s = new String(data);
		  if(debug){
			  System.out.println("from recvBuffer: " + s);
		  }
		  return sizeOfData;
	  }else{
		  recvBuffer.copyOut(data, recvBuffer.getBase(), length);
		  recvBuffer.advance(length);
		  for(int i = 0; i < length; i++){
			  buffer[i] = data[i];
		  }
		  String s = new String(data);
		  if(debug){
			  
			  System.out.println("from recvBuffer: " + s);
		  }
		  return length;
	  }
	  
  
	  
	  
  }

  /**
   * accept data written by application into sendBuffer to send.
   * Must block until ALL data is written.
   * @param buffer array of bytes to copy into app
   * @param length number of bytes to copy 
   */
  synchronized void dataFromApp(byte[] buffer, int length){
	  if(debug){
		  System.out.println("dataFromApp called");
	  }
	  
	  int written = 0;
	  int room = getRoomInBuffer(sendBuffer);
	  if(debug){
		  System.out.println("Room: " + room);
	  }
	  while(written < length){
		  while(room <1){
			  try {
				  this.wait();
				  
				  
			  } catch (InterruptedException e) {
				  // TODO Auto-generated catch blocke.printStackTrace();
			  
				  
			  }
			  room = getRoomInBuffer(sendBuffer);
		  }
		  if(room < (length-written)){
			  sendBuffer.append( buffer, written, room);
			  written += room;
		  }else{
			  sendBuffer.append(buffer, written, (length-written));
			  written += length - written;
		  }
		  sendData();
	  }
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
    
    this.address = address;
	this.port = port;

	D.registerConnection(address, localport, port, this);

	seqNum = 100;
	base = 100;
	TCPPacket synPacket = new TCPPacket(localport, port, seqNum, ackNum, false, true, false, 1, null);
	changeToState(SYN_SENT);
	sendPacket(synPacket, false);
  }
  
  /**
   * Called by Demultiplexer when a packet comes in for this connection
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p){
	  this.notifyAll();

		System.out.println("Packet received from address " + p.sourceAddr + " with seqNum " + p.seqNum + " is being processed.");
		System.out.print("The packet is ");

		if(p.ackFlag == true && p.synFlag == true){
			System.out.println("a syn-ack.");

			if(state == SYN_SENT){
				//client state
				incrementCounters(p);
				cancelPacketTimer(p);
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				
				changeToState(ESTABLISHED);
				
				sendPacket(ackPacket, false);
				
			}
			else if (state == ESTABLISHED){
				//client state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				sendPacket(ackPacket, false);
			}
			else if (state == FIN_WAIT_1){
				//client state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				sendPacket(ackPacket, false);
			}
		}
		else if(p.ackFlag == true){
			System.out.println("an ack.");
			//for the love of God, do not incrementCounters(p) in here

			if(state == SYN_RCVD){
				//server state
				cancelPacketTimer(p);
				//seqNum = p.ackNum;
				
				changeToState(ESTABLISHED);
			}
			else if(state == FIN_WAIT_1){
				//client state
				if(debug){
					System.out.println("recieved packet in fin_wait_1 ack: " + p.ackNum + " base: " + base);
				}
				
				if(p.ackNum-1== base){
					changeToState(FIN_WAIT_2);
				}
				cancelPacketTimer(p);
				
			}
			else if(state == LAST_ACK){
				//server state
				cancelPacketTimer(p);
				changeToState(TIME_WAIT);
			}
			else if(state == CLOSING){
				//client or server state
				cancelPacketTimer(p);
				changeToState(TIME_WAIT);
			}else if(state == ESTABLISHED){
				cancelPacketTimer(p);
				
				sendData();
			}
		}
		else if(p.synFlag == true){
			System.out.println("a syn.");
			
			if(state == LISTEN){
				//server state
				try{
					D.unregisterListeningSocket(localport, this);	                     //***********tricky*************
					D.registerConnection(p.sourceAddr, p.destPort, p.sourcePort, this); //***********tricky*************
				}
				catch(IOException e){
					System.out.println("Error occured while attempting to establish connection");	
				}

				this.address = p.sourceAddr;
				this.port = p.sourcePort;

				incrementCounters(p);
				TCPPacket synackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, true, false, 1, null);
				changeToState(SYN_RCVD);
				sendPacket(synackPacket, false);
			}
			
		}
		else if(p.finFlag == true){
			System.out.println("a fin.");

			if(state == ESTABLISHED){
				//server state
				incrementCounters(p);
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				changeToState(CLOSE_WAIT);
				sendPacket(ackPacket, false);
			}
			else if(state == FIN_WAIT_1){
				//client state or server state
				incrementCounters(p);
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				changeToState(CLOSING);
				sendPacket(ackPacket, false);
			}
			else if(state == FIN_WAIT_2){
				//client state
				incrementCounters(p);
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				changeToState(TIME_WAIT);
				sendPacket(ackPacket, false);
			}
			else if(state == LAST_ACK){
				//server state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				sendPacket(ackPacket, false);
			}
			else if(state == CLOSING){
				//client or server state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				sendPacket(ackPacket, false);
			}
			else if(state == TIME_WAIT){
				//client or server state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				sendPacket(ackPacket, false);
			}else if(state == CLOSE_WAIT){
				cancelPacketTimer(p);
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				sendPacket(ackPacket, false);
			}
		}
		else{
			System.out.println("a chunk of data.");
			if(p.seqNum != ackNum || getRoomInBuffer(recvBuffer) < p.data.length){
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				sendPacket(ackPacket, false);
				
				
			}else{
				incrementCounters(p);  
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, p.seqNum+1, true, false, false, 1, null);
				sendPacket(ackPacket, false);
				String s = new String(p.data);
				//System.out.println("from inPacket: " + s);
				
				
				 int written = 0;
				 int room = getRoomInBuffer(recvBuffer);
				  if(debug){
					  System.out.println("Room recvBuffer: " + room);
				  }
				  while(written < p.data.length){
					  while(room <1){
						  try {
							  this.wait();
							  
							  
						  } catch (InterruptedException e) {
							  // TODO Auto-generated catch blocke.printStackTrace();
						  
							  
						  }
						  room = getRoomInBuffer(recvBuffer);
					  }
					  if(room < (p.data.length-written)){
						  recvBuffer.append( p.data, written, room);
						  written += room;
					  }else{
						  recvBuffer.append(p.data, written, (p.data.length-written));
						  written += p.data.length-written;
					  }
					  
					  
				  }
				
				
				this.notifyAll();
				
				
			}
			
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
	//server state
		changeToState(LISTEN);

		D.registerListeningSocket (localport, this);

		seqNum = 10000;
		base = seqNum;

		try{
			this.wait();
		}
		catch(InterruptedException e){
			System.err.println("Error occured when trying to wait.");
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
    return appIS;
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
    return appOS;
  }


  /**
   * Closes this socket. 
   *
   * @exception  IOException  if an I/O error occurs when closing this socket.
   */
  public synchronized void close() throws IOException {
    if(address==null)
      return;

	System.out.println("*** close() was called by the application.");
	
	while(base!=seqNum || getAmountInBuffer(sendBuffer)>0){
		this.notifyAll();
		try {
			this.wait();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	System.out.println("*** close() moved on successfully.");

	if(state == ESTABLISHED){
		//client state
		TCPPacket finPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, true, 1, null);
		changeToState(FIN_WAIT_1);
		sendPacket(finPacket, false);
		finSent = true;
	}
	else if(state == CLOSE_WAIT){
		//server state
		TCPPacket finPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, true, 1, null);
		changeToState(LAST_ACK);
		sendPacket(finPacket, false);
		finSent = true;
	}
	else{
		System.out.println("Attempted to close while not established (ESTABLISHED) or waiting to close (CLOSE_WAIT)");
		//timer task here... try the closing process again
		wantsToClose = true;
	}

	//returns immediately to the application
    terminating = true;

    while(!reader.tryClose()){
      this.notifyAll();
      try{
	wait(1000);
      }
      catch(InterruptedException e){}
    }
    writer.close();
    
    this.notifyAll();
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

	  if(ref == null){
  		// this must run only once the last timer (30 second timer) has expired
  		tcpTimer.cancel();
  		tcpTimer = null;

		try{
			D.unregisterConnection(address, localport, port, this);
		}
		catch(IOException e){
			System.out.println("Error occured while attempting to close connection");	
		}
	}
	else{	//its a packet that needs to be resent
		System.out.println("XXX Resending Packet");
		sendPacket((TCPPacket)ref, true);
	}
  }

}

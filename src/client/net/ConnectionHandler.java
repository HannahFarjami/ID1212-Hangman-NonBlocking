package client.net;

import common.ObjectConverter;
import common.Request;
import common.Response;

import static common.RequestType.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;


/**
 * Class that handle the connection from client to server.
 */
public class ConnectionHandler implements Runnable{

    private IGameObserver gameObserver;
    private SocketChannel socketChannel;
    private Selector selector;
    private boolean isConnected;
    private ByteBuffer buffer = ByteBuffer.allocate(512);
    private Queue<Request> messagesToSend = new ArrayDeque<>();
    private boolean timeToSend;

    public ConnectionHandler(IGameObserver gameObserver) {
        this.gameObserver = gameObserver;
    }

    public void connect(String host, int port) throws IOException{
        socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        socketChannel.connect(new InetSocketAddress(host,port));
        isConnected = true;
        new Thread(this).start();
    }

    @Override
    public void run() {
        try {
            setUpSelectorForConnection();
            while(isConnected){
                if (timeToSend) {
                    socketChannel.keyFor(selector).interestOps(SelectionKey.OP_WRITE);
                    timeToSend = false;
                }
                selector.select();
                for(SelectionKey key : selector.selectedKeys()){
                    selector.selectedKeys().remove(key);
                    if(key.isValid()) {
                        if (key.isConnectable()) {
                            finishConnection(key);
                        }
                        else if(key.isReadable()){
                            readFromServer();
                        }
                        else if(key.isWritable()){
                            writeToServer(key);
                        }
                    }
                }
            }
            disconnectClient();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void finishConnection(SelectionKey key) throws IOException{
        socketChannel.finishConnect();
    }

    private void setUpSelectorForConnection()throws IOException{
        selector = Selector.open();
        socketChannel.register(selector, SelectionKey.OP_CONNECT);
    }

    private void readFromServer()throws IOException{
        int bytesRead = socketChannel.read(buffer);
        if(bytesRead > 0) {
            buffer.flip();
            CompletableFuture.runAsync(()-> {
                        gameObserver.gameChanges((Response) ObjectConverter.byteArrayToObject(buffer.array()));
                    });
            buffer.clear();
        }else throw new IOException();
    }
    private void writeToServer(SelectionKey key)throws IOException{
        while(messagesToSend.peek()!=null){
            ByteBuffer tempBuffer = ByteBuffer.wrap(ObjectConverter.calculateAndPrependSizeOfObjectToBeSent(messagesToSend.remove()));
            while(tempBuffer.hasRemaining()) socketChannel.write(tempBuffer);
        }
        key.interestOps(SelectionKey.OP_READ);
        selector.wakeup();

    }

    private void disconnectClient()throws IOException{
        socketChannel.close();
        socketChannel.keyFor(selector).cancel();
    }

    /**
     * Request the server to set up a new game
     */
    public void newGame(){
        sendGuess(new Request(NEW_GAME));
    }

    /**
     * Calls the function sendGuess with a guess formated after the decided protocol
     * @param letterToGuess the letter that the user want to guess
     */
    public void sendLetterToGuess(char letterToGuess){
        Request request = new Request(GUESSLETTER);
        request.setLetterToGuess(letterToGuess);
        sendGuess(request);
    }

    /**
     * Calls the function sendGuess with a guess formated after the decided protocol
     * @param wordToGuess if the user guesses a whole word
     */
    public void sendWordToGuess(String wordToGuess){
        Request request = new Request(GUESSWORD);
        request.setWordToGuess(wordToGuess);
        sendGuess(request);
    }

    /**
     *Send an Request with the type QUIT to tell the server to close its connection to client
     */
    public void quitGame(){
        sendGuess(new Request(QUIT));
        isConnected = false;
    }

    /**
     *
     * Takes the Request created in the public functions and sends it to the server.
     * @param request the protocol used for requests
     */
    private void sendGuess(Request request){
       messagesToSend.add(request);
       timeToSend = true;
       selector.wakeup();
    }


}


package org.torproject.jtor.socks.impl;

import java.io.IOException;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.torproject.jtor.TorException;
import org.torproject.jtor.circuits.CircuitManager;
import org.torproject.jtor.circuits.OpenStreamResponse;
import org.torproject.jtor.circuits.Stream;
import org.torproject.jtor.circuits.cells.RelayCell;

public class SocksClientTask implements Runnable {
	private final static Logger logger = Logger.getLogger(SocksClientTask.class.getName());
	private final Socket socket;
	private final CircuitManager circuitManager;

	SocksClientTask(Socket socket, CircuitManager circuitManager) {
		this.socket = socket;
		this.circuitManager = circuitManager;
	}

	public void run() {
		final int version = readByte();
		dispatchRequest(version);
		closeSocket();
	}

	private int readByte() {
		try {
			return socket.getInputStream().read();
		} catch (IOException e) {
			logger.warning("IO error reading version byte: "+ e.getMessage());
			return -1;
		}
	}
	
	private void dispatchRequest(int versionByte) {
		switch(versionByte) {
		case 'H':
		case 'G':
		case 'P':
			sendHttpPage();
			break;
		case 4:
			processRequest(new Socks4Request(socket));
			break;
		case 5:
			processRequest(new Socks5Request(socket));
			break;
		default:
			// fall through, do nothing
			break;
		}	
	}
	
	private void processRequest(SocksRequest request) {
		try {
			request.readRequest();
			if(!request.isConnectRequest()) {
				logger.warning("Non connect command");
				request.sendError();
				return;
			}
			final OpenStreamResponse openResponse = openConnectStream(request);
			switch(openResponse.getStatus()) {
			case STATUS_STREAM_OPENED:
				request.sendSuccess();
				runOpenConnection(openResponse.getStream());
				break;
			case STATUS_STREAM_ERROR:
				if(openResponse.getErrorCode() == RelayCell.REASON_CONNECTREFUSED)
					request.sendConnectionRefused();
				else
					request.sendError();
				break;
			default:
				request.sendError();
				break;
			}
			
		} catch (SocksRequestException e) {
			logger.log(Level.WARNING, "Failure reading SOCKS request", e);
		} catch (InterruptedException e) {
			logger.warning("Stream open interrupted");
			Thread.currentThread().interrupt();
		} 
	}
		
	private void runOpenConnection(Stream stream) {
		SocksStreamConnection.runConnection(socket, stream);
	}

	private OpenStreamResponse openConnectStream(SocksRequest request) throws InterruptedException {
		if(request.hasHostname()) {
			logger.fine("SOCKS CONNECT request to "+ request.getHostname() +":"+ request.getPort());
			return circuitManager.openExitStreamTo(request.getHostname(), request.getPort());
		} else {
			logger.fine("SOCKS CONNECT request to "+ request.getAddress() +":"+ request.getPort());
			return circuitManager.openExitStreamTo(request.getAddress(), request.getPort());
		}
	}

	private void sendHttpPage() {
		throw new TorException("Returning HTTP page not implemented");
	}

	private void closeSocket() {
		try {
			socket.close();
		} catch (IOException e) {
			logger.warning("Error closing SOCKS socket: "+ e.getMessage());
		}
	}
}

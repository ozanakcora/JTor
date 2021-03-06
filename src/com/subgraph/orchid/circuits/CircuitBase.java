package com.subgraph.orchid.circuits;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import com.subgraph.orchid.Cell;
import com.subgraph.orchid.Circuit;
import com.subgraph.orchid.CircuitNode;
import com.subgraph.orchid.Connection;
import com.subgraph.orchid.RelayCell;
import com.subgraph.orchid.Router;
import com.subgraph.orchid.Stream;
import com.subgraph.orchid.StreamConnectFailedException;
import com.subgraph.orchid.TorException;
import com.subgraph.orchid.circuits.path.CircuitPathChooser;
import com.subgraph.orchid.circuits.path.PathSelectionFailedException;
import com.subgraph.orchid.dashboard.DashboardRenderable;
import com.subgraph.orchid.dashboard.DashboardRenderer;
import com.subgraph.orchid.data.IPv4Address;
import com.subgraph.orchid.data.exitpolicy.ExitTarget;

/**
 * This class represents an established circuit through the Tor network.
 *
 */
abstract class CircuitBase implements Circuit, DashboardRenderable {
	protected final static Logger logger = Logger.getLogger(CircuitBase.class.getName());
	
	static ExitCircuit create(CircuitManagerImpl circuitManager, Router exitRouter) {
		return new ExitCircuit(circuitManager, exitRouter);
	}
	
	static DirectoryCircuit createDirectoryCircuit(CircuitManagerImpl circuitManager) {
		return new DirectoryCircuit(circuitManager);
	}

	private final CircuitManagerImpl circuitManager;
	private final List<CircuitNodeImpl> nodeList;
	private final CircuitStatus status;

	private CircuitIO io;

	protected CircuitBase(CircuitManagerImpl circuitManager) {
		nodeList = new ArrayList<CircuitNodeImpl>();
		this.circuitManager = circuitManager;
		status = new CircuitStatus();
	}

	abstract List<Router> choosePath(CircuitPathChooser pathChooser) throws InterruptedException, PathSelectionFailedException;
	
	void bindToConnection(Connection connection) {
		if(io != null) {
			throw new IllegalStateException("Circuit already bound to a connection");
		}
		final int id = connection.bindCircuit(this);
		io = new CircuitIO(this, connection, id);
	}

	public void markForClose() {
		if(io != null) {
			io.markForClose();
		}
	}

	boolean isMarkedForClose() {
		if(io == null) {
			return false;
		} else {
			return io.isMarkedForClose();
		}
	}

	boolean isDirectoryCircuit() {
		return false;
	}
	
	CircuitStatus getStatus() {
		return status;
	}
	
	public boolean isConnected() {
		return status.isConnected();
	}

	boolean isPending() {
		return status.isBuilding();
	}
	
	boolean isClean() {
		return status.isConnected() && !status.isDirty();
	}
	
	int getSecondsDirty() {
		return (int) (status.getMillisecondsDirty() / 1000);
	}

	void notifyCircuitBuildStart(CircuitCreationRequest request) {
		if(!status.isUnconnected()) {
			throw new IllegalStateException("Can only connect UNCONNECTED circuits");
		}
		status.updateCreatedTimestamp();
		status.setStateBuilding(request.getPath());
		circuitManager.addActiveCircuit(this);
	}
	
	void notifyCircuitBuildFailed() {
		status.setStateFailed();
		circuitManager.removeActiveCircuit(this);
	}
	
	void notifyCircuitBuildCompleted() {
		status.setStateOpen();
		status.updateCreatedTimestamp();
	}
	
	public Connection getConnection() {
		if(!isConnected())
			throw new TorException("Circuit is not connected.");
		return io.getConnection();
	}

	public int getCircuitId() {
		return io.getCircuitId();
	}

	public void sendRelayCell(RelayCell cell) {
		io.sendRelayCellTo(cell, cell.getCircuitNode());
	}

	public void sendRelayCellToFinalNode(RelayCell cell) {
		io.sendRelayCellTo(cell, getFinalCircuitNode());
	}

	void appendNode(CircuitNodeImpl node) {
		nodeList.add(node);
	}

	List<CircuitNodeImpl> getNodeList() {
		return nodeList;
	}

	int getCircuitLength() {
		return nodeList.size();
	}

	public CircuitNodeImpl getFinalCircuitNode() {
		if(nodeList.isEmpty())
			throw new TorException("getFinalCircuitNode() called on empty circuit");
		return nodeList.get( getCircuitLength() - 1);
	}

	public RelayCell createRelayCell(int relayCommand, int streamId, CircuitNode targetNode) {
		return io.createRelayCell(relayCommand, streamId, targetNode);
	}

	public RelayCell receiveRelayCell() {
		return io.dequeueRelayResponseCell();
	}

	void sendCell(Cell cell) {
		io.sendCell(cell);
	}
	
	Cell receiveControlCellResponse() {
		return io.receiveControlCellResponse();
	}

	/*
	 * This is called by the cell reading thread in ConnectionImpl to deliver control cells 
	 * associated with this circuit (CREATED or CREATED_FAST).
	 */
	public void deliverControlCell(Cell cell) {
		io.deliverControlCell(cell);
	}

	/* This is called by the cell reading thread in ConnectionImpl to deliver RELAY cells. */
	public void deliverRelayCell(Cell cell) {
		io.deliverRelayCell(cell);
	}

	public Stream openDirectoryStream(long timeout) throws InterruptedException, TimeoutException, StreamConnectFailedException {
		throw new UnsupportedOperationException();
	}

	public Stream openExitStream(IPv4Address address, int port, long timeout) throws InterruptedException, TimeoutException, StreamConnectFailedException {
		throw new UnsupportedOperationException();
	}

	public Stream openExitStream(String target, int port, long timeout) throws InterruptedException, TimeoutException, StreamConnectFailedException {
		throw new UnsupportedOperationException();
	}

	protected StreamImpl createNewStream() {
		return io.createNewStream();
	}

	boolean isFinalNodeDirectory() {
		return getFinalCircuitNode().getRouter().getDirectoryPort() != 0;
	}

	void setStateDestroyed() {
		status.setStateDestroyed();
		circuitManager.removeActiveCircuit(this);
	}

	public void destroyCircuit() {
		io.destroyCircuit();
		circuitManager.removeActiveCircuit(this);
	}


	void removeStream(StreamImpl stream) {
		io.removeStream(stream);
	}

	public void recordFailedExitTarget(ExitTarget target) {
		throw new UnsupportedOperationException();
	}

	public boolean canHandleExitTo(ExitTarget target) {
		return false;
	}
	
	public boolean canHandleExitToPort(int port) {
		return false;
	}

	protected Stream processStreamOpenException(Exception e) throws InterruptedException, TimeoutException, StreamConnectFailedException {
		if(e instanceof InterruptedException) {
			throw (InterruptedException) e;
		} else if(e instanceof TimeoutException) {
			throw(TimeoutException) e;
		} else if(e instanceof StreamConnectFailedException) {
			throw(StreamConnectFailedException) e;
		} else {
			throw new IllegalStateException();
		}
	}
	
	public String toString() {
		int id = (io == null) ? 0 : io.getCircuitId();
		return "  Circuit id="+ id +" state=" + status.getStateAsString() +" "+ pathToString();
	}

	
	private  String pathToString() {
		final StringBuilder sb = new StringBuilder();
		sb.append("[");
		for(CircuitNode node: nodeList) {
			if(sb.length() > 1)
				sb.append(",");
			sb.append(node.toString());
		}
		sb.append("]");
		return sb.toString();
	}

	public List<Stream> getActiveStreams() {
		if(io == null) {
			return Collections.emptyList();
		} else {
			return io.getActiveStreams();
		}
	}

	public void dashboardRender(DashboardRenderer renderer, PrintWriter writer, int flags) throws IOException {
		if(io != null) {
			writer.println(toString());
			renderer.renderComponent(writer, flags, io);
		}
	}
}

package org.torproject.jtor.directory.impl.status;

import java.util.HashSet;
import java.util.Set;

import org.torproject.jtor.data.HexDigest;
import org.torproject.jtor.data.Timestamp;
import org.torproject.jtor.data.exitpolicy.ExitPorts;
import org.torproject.jtor.directory.RouterStatus;

public class RouterStatusImpl implements RouterStatus {
	
	private String nickname;
	private HexDigest identity;
	private HexDigest digest;
	private Timestamp publicationTime;
	private String address;
	private int routerPort;
	private int directoryPort;
	private Set<String> flags = new HashSet<String>();
	private String version;
	private int bandwidthEstimate;
	private int bandwidthMeasured;
	private ExitPorts exitPorts;
	
	void setNickname(String nickname) { this.nickname = nickname; }
	void setIdentity(HexDigest identity) { this.identity = identity; }
	void setDigest(HexDigest digest) { this.digest = digest; }
	void setPublicationTime(Timestamp timestamp) { this.publicationTime = timestamp; }
	void setAddress(String address) { this.address = address; }
	void setRouterPort(int port) { this.routerPort = port; }
	void setDirectoryPort(int port) { this.directoryPort = port; }
	void addFlag(String flag) { this.flags.add(flag); }
	void setVersion(String version) { this.version = version; }
	void setEstimatedBandwidth(int bandwidth) { this.bandwidthEstimate = bandwidth; }
	void setMeasuredBandwidth(int bandwidth) { this.bandwidthMeasured = bandwidth; }
	void setAcceptedPorts(String portList) { this.exitPorts = ExitPorts.createAcceptExitPorts(portList); }
	void setRejectedPorts(String portList) { this.exitPorts = ExitPorts.createRejectExitPorts(portList); }
	
	public String toString() {
		return "Router: ("+ nickname +" "+ identity +" "+ digest +" "+ address +" "+ routerPort +" " + directoryPort
			+" "+ version +" "+ exitPorts +")";
	}
	public String getNickname() {
		return nickname;
	}
	
	public HexDigest getIdentity() {
		return identity;
	}
	
	public HexDigest getDescriptorDigest() {
		return digest;
	}
	
	public Timestamp getPublicationTime() {
		return publicationTime;
	}
	
	String getAddress() {
		return address;
	}
	
	public int getRouterPort() {
		return routerPort;
	}
	
	public boolean isDirectory() {
		return directoryPort != 0;
	}
	public int getDirectoryPort() {
		return directoryPort;
	}
	
	public boolean hasFlag(String flag) {
		return flags.contains(flag);
	}
	
	public String getVersion() {
		return version;
	}
	
	public int getEstimatedBandwidth() {
		return bandwidthEstimate;
	}
	
	public int getMeasuredBandwidth() {
		return bandwidthMeasured;
	}
	
	public ExitPorts getExitPorts() {
		return exitPorts;
	}

}
package freenet.node;

import java.util.Hashtable;
import java.util.Map;

import freenet.clients.http.DarknetConnectionsToadlet;

/**
 * Contains various status information for a {@link PeerNode}. Used e.g. in
 * {@link DarknetConnectionsToadlet} to reduce race-conditions while creating
 * the page.
 * 
 * @author David 'Bombe' Roden &lt;bombe@freenetproject.org&gt;
 * @version $Id$
 */
public class PeerNodeStatus {

	private final String name;

	private final String peerAddress;

	private final int peerPort;

	private final int statusValue;

	private final String statusName;

	private final String statusCSSName;

	private final double location;

	private final String version;

	private final String simpleVersion;

	private final int routingBackoffLength;

	private final long routingBackedOffUntil;

	private final boolean burstOnly;

	private final boolean listening;

	private final boolean disabled;

	private final boolean connected;

	private final boolean isFetchingARK;

	private final double averagePingTime;

	private final boolean publicReverseInvalidVersion;

	private final double backedOffPercent;

	private String lastBackoffReason;

	private long timeLastRoutable;

	private long timeLastConnectionCompleted;

	private long peerAddedTime;

	private Map localMessagesReceived;

	private Map localMessagesSent;
	
	private final int hashCode;

	public PeerNodeStatus(PeerNode peerNode) {
		this.name = peerNode.getName();
		this.peerAddress = (peerNode.getDetectedPeer() != null) ? peerNode.getDetectedPeer().getFreenetAddress().toString() : null;
		this.peerPort = (peerNode.getDetectedPeer() != null) ? peerNode.getDetectedPeer().getPort() : -1;
		this.statusValue = peerNode.getPeerNodeStatus();
		this.statusName = peerNode.getPeerNodeStatusString();
		this.statusCSSName = peerNode.getPeerNodeStatusCSSClassName();
		this.location = peerNode.getLocation().getValue();
		this.version = peerNode.getVersion();
		this.simpleVersion = peerNode.getSimpleVersion();
		this.routingBackoffLength = peerNode.getRoutingBackoffLength();
		this.routingBackedOffUntil = peerNode.getRoutingBackedOffUntil();
		this.burstOnly = peerNode.isBurstOnly();
		this.listening = peerNode.isListenOnly();
		this.disabled = peerNode.isDisabled();
		this.connected = peerNode.isConnected();
		this.isFetchingARK = peerNode.isFetchingARK();
		this.averagePingTime = peerNode.averagePingTime();
		this.publicReverseInvalidVersion = peerNode.publicReverseInvalidVersion();
		this.backedOffPercent = peerNode.backedOffPercent.currentValue();
		this.lastBackoffReason = peerNode.getLastBackoffReason();
		this.timeLastRoutable = peerNode.timeLastRoutable();
		this.timeLastConnectionCompleted = peerNode.timeLastConnectionCompleted();
		this.peerAddedTime = peerNode.getPeerAddedTime();
		this.localMessagesReceived = new Hashtable(peerNode.getLocalNodeReceivedMessagesFromStatistic());
		this.localMessagesSent = new Hashtable(peerNode.getLocalNodeSentMessagesToStatistic());
		this.hashCode = peerNode.hashCode;
	}

	/**
	 * @return the localMessagesReceived
	 */
	public Map getLocalMessagesReceived() {
		return localMessagesReceived;
	}

	/**
	 * @return the localMessagesSent
	 */
	public Map getLocalMessagesSent() {
		return localMessagesSent;
	}

	/**
	 * @return the peerAddedTime
	 */
	public long getPeerAddedTime() {
		return peerAddedTime;
	}

	/**
	 * @return the timeLastConnectionCompleted
	 */
	public long getTimeLastConnectionCompleted() {
		return timeLastConnectionCompleted;
	}

	/**
	 * @return the backedOffPercent
	 */
	public double getBackedOffPercent() {
		return backedOffPercent;
	}

	/**
	 * @return the lastBackoffReason
	 */
	public String getLastBackoffReason() {
		return lastBackoffReason;
	}

	/**
	 * @return the timeLastRoutable
	 */
	public long getTimeLastRoutable() {
		return timeLastRoutable;
	}

	/**
	 * @return the publicReverseInvalidVersion
	 */
	public boolean isPublicReverseInvalidVersion() {
		return publicReverseInvalidVersion;
	}

	/**
	 * @return the averagePingTime
	 */
	public double getAveragePingTime() {
		return averagePingTime;
	}

	/**
	 * @return the getRoutingBackedOffUntil
	 */
	public long getRoutingBackedOffUntil() {
		return routingBackedOffUntil;
	}

	/**
	 * @return the location
	 */
	public double getLocation() {
		return location;
	}

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @return the peerAddress
	 */
	public String getPeerAddress() {
		return peerAddress;
	}

	/**
	 * @return the peerPort
	 */
	public int getPeerPort() {
		return peerPort;
	}

	/**
	 * @return the routingBackoffLength
	 */
	public int getRoutingBackoffLength() {
		return routingBackoffLength;
	}

	/**
	 * @return the statusCSSName
	 */
	public String getStatusCSSName() {
		return statusCSSName;
	}

	/**
	 * @return the statusName
	 */
	public String getStatusName() {
		return statusName;
	}

	/**
	 * @return the statusValue
	 */
	public int getStatusValue() {
		return statusValue;
	}

	/**
	 * @return the version
	 */
	public String getVersion() {
		return version;
	}

	/**
	 * @return the burstOnly
	 */
	public boolean isBurstOnly() {
		return burstOnly;
	}

	/**
	 * @return the connected
	 */
	public boolean isConnected() {
		return connected;
	}

	/**
	 * @return the disabled
	 */
	public boolean isDisabled() {
		return disabled;
	}

	/**
	 * @return the isFetchingARK
	 */
	public boolean isFetchingARK() {
		return isFetchingARK;
	}

	/**
	 * @return the listening
	 */
	public boolean isListening() {
		return listening;
	}

	/**
	 * @return the simpleVersion
	 */
	public String getSimpleVersion() {
		return simpleVersion;
	}

	public String toString() {
		return statusName + " " + peerAddress + ":" + peerPort + " " + name + " " + location + " " + version + " backoff: " + routingBackoffLength + " (" + (Math.max(routingBackedOffUntil - System.currentTimeMillis(), 0)) + ")";
	}

	public int hashCode() {
		return hashCode;
	}
}

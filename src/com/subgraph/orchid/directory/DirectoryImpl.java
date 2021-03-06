package com.subgraph.orchid.directory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.subgraph.orchid.ConsensusDocument;
import com.subgraph.orchid.Directory;
import com.subgraph.orchid.DirectoryServer;
import com.subgraph.orchid.GuardEntry;
import com.subgraph.orchid.KeyCertificate;
import com.subgraph.orchid.Router;
import com.subgraph.orchid.RouterDescriptor;
import com.subgraph.orchid.RouterStatus;
import com.subgraph.orchid.TorConfig;
import com.subgraph.orchid.TorException;
import com.subgraph.orchid.crypto.TorRandom;
import com.subgraph.orchid.data.HexDigest;
import com.subgraph.orchid.data.RandomSet;
import com.subgraph.orchid.directory.consensus.DirectorySignature;
import com.subgraph.orchid.events.Event;
import com.subgraph.orchid.events.EventHandler;
import com.subgraph.orchid.events.EventManager;

public class DirectoryImpl implements Directory {
	private final static Logger logger = Logger.getLogger(DirectoryImpl.class.getName());

	private final DirectoryStoreImpl store;
	private final StateFile stateFile;
	private final Map<HexDigest, KeyCertificate> certificates;
	private final Map<HexDigest, RouterImpl> routersByIdentity;
	private final Map<String, RouterImpl> routersByNickname;
	private final RandomSet<RouterImpl> directoryCaches;
	private final Set<HexDigest> requiredCertificates;
	private List<DirectoryServer> directoryAuthorities;
	private boolean haveMinimumRouterInfo;
	private final EventManager consensusChangedManager;
	private final TorRandom random;
	private ConsensusDocument currentConsensus;
	private ConsensusDocument consensusWaitingForCertificates;
	private boolean descriptorsDirty;

	public DirectoryImpl(TorConfig config) {
		store = new DirectoryStoreImpl(config);
		stateFile = new StateFile(store, this);
		certificates = new HashMap<HexDigest, KeyCertificate>();
		routersByIdentity = new HashMap<HexDigest, RouterImpl>();
		routersByNickname = new HashMap<String, RouterImpl>();
		directoryCaches = new RandomSet<RouterImpl>();
		requiredCertificates = new HashSet<HexDigest>();
		consensusChangedManager = new EventManager();
		random = new TorRandom();
		loadAuthorityServers();
	}

	private void loadAuthorityServers() {
		final TrustedAuthorities trusted = new TrustedAuthorities();
		directoryAuthorities = trusted.getAuthorityServers();
	}

	public synchronized boolean haveMinimumRouterInfo() {
		return haveMinimumRouterInfo;
	}

	private synchronized void checkMinimumRouterInfo() {
		if(currentConsensus == null) {
			haveMinimumRouterInfo = false;
			return;
		}

		int routerCount = 0;
		int descriptorCount = 0;
		for(Router r: routersByIdentity.values()) {
			routerCount++;
			if(!r.isDescriptorDownloadable())
				descriptorCount++;
		}
		haveMinimumRouterInfo = (descriptorCount * 4 > routerCount);
	}

	public void loadFromStore() {
		logger.info("Loading cached network information from disk");
		store.loadCertificates(this);
		store.loadConsensus(this);
		store.loadRouterDescriptors(this);
		store.loadStateFile(stateFile);
	}

	public Collection<DirectoryServer> getDirectoryAuthorities() {
		return directoryAuthorities;
	}

	public DirectoryServer getRandomDirectoryAuthority() {
		final int idx = random.nextInt(directoryAuthorities.size());
		return directoryAuthorities.get(idx);
	}

	public Set<HexDigest> getRequiredCertificates() {
		return new HashSet<HexDigest>(requiredCertificates);
	}
	
	public void addCertificate(KeyCertificate certificate) {
		final HexDigest fingerprint = certificate.getAuthorityFingerprint();
		synchronized(certificates) {
			requiredCertificates.remove(fingerprint);
			certificates.put(fingerprint, certificate);
			if(consensusWaitingForCertificates != null && consensusWaitingForCertificates.canVerifySignatures(certificates)) {
				addConsensusDocument(consensusWaitingForCertificates);
				consensusWaitingForCertificates = null;
			}	
		}
	}

	public KeyCertificate findCertificate(HexDigest authorityFingerprint) {
		return certificates.get(authorityFingerprint);
	}

	public void storeCertificates() {
		final List<KeyCertificate> certs = new ArrayList<KeyCertificate>(); 
		for(KeyCertificate c: certificates.values()) 
			certs.add(c);
		store.saveCertificates(certs);
	}

	public void addRouterDescriptor(RouterDescriptor router) {
		addDescriptor(router);
	}

	public void storeConsensus() {
		if(currentConsensus != null)
			store.saveConsensus(currentConsensus);
	}

	public synchronized void storeDescriptors() {
		if(!descriptorsDirty)
			return;
		final List<RouterDescriptor> descriptors = new ArrayList<RouterDescriptor>();
		for(Router router: routersByIdentity.values()) {
			final RouterDescriptor descriptor = router.getCurrentDescriptor();
			if(descriptor != null)
				descriptors.add(descriptor);
		}
		store.saveRouterDescriptors(descriptors);
		descriptorsDirty = false;
	}

	public void addConsensusDocument(ConsensusDocument consensus) {
		if(consensus.equals(currentConsensus))
			return;

		if(currentConsensus != null && consensus.getValidAfterTime().isBefore(currentConsensus.getValidAfterTime())) {
			logger.warning("New consensus document is older than current consensus document");
			return;
		}

		synchronized(certificates) {
			if(!consensus.canVerifySignatures(certificates)) {
				logger.warning("Need more certificates to verify consensus document.");
				consensusWaitingForCertificates = consensus;
				for(DirectorySignature s: consensus.getDocumentSignatures()) {
					if(!certificates.containsKey(s.getIdentityDigest())) 
						requiredCertificates.add(s.getIdentityDigest());
				}
				return;
			}
			
			if(!consensus.verifySignatures(certificates)) {
				logger.warning("Signature verification on Consensus document failed.");
				return;
			}
		}
		final Map<HexDigest, RouterImpl> oldRouterByIdentity = new HashMap<HexDigest, RouterImpl>(routersByIdentity);

		clearAll();

		for(RouterStatus status: consensus.getRouterStatusEntries()) {
			if(status.hasFlag("Running") && status.hasFlag("Valid")) {
				final RouterImpl router = updateOrCreateRouter(status, oldRouterByIdentity);
				addRouter(router);
				classifyRouter(router);
			}
		}
		logger.fine("Loaded "+ routersByIdentity.size() +" routers from consensus document");
		currentConsensus = consensus;
		store.saveConsensus(consensus);
		consensusChangedManager.fireEvent(new Event() {});
	}

	private RouterImpl updateOrCreateRouter(RouterStatus status, Map<HexDigest, RouterImpl> knownRouters) {
		final RouterImpl router = knownRouters.get(status.getIdentity());
		if(router == null)
			return RouterImpl.createFromRouterStatus(status);
		router.updateStatus(status);
		return router;
	}

	private void clearAll() {
		routersByIdentity.clear();
		routersByNickname.clear();
		directoryCaches.clear();
	}

	private void classifyRouter(RouterImpl router) {
		if(isValidDirectoryCache(router)) {
			directoryCaches.add(router);
		} else {
			directoryCaches.remove(router);
		}
	}

	private boolean isValidDirectoryCache(RouterImpl router) {
		if(router.getDirectoryPort() == 0)
			return false;
		if(router.hasFlag("BadDirectory"))
			return false;
		return router.hasFlag("V2Dir");
	}

	private void addRouter(RouterImpl router) {
		routersByIdentity.put(router.getIdentityHash(), router);
		addRouterByNickname(router);
	}

	private void addRouterByNickname(RouterImpl router) {
		final String name = router.getNickname();
		if(name == null || name.equals("Unnamed"))
			return;
		if(routersByNickname.containsKey(router.getNickname())) {
			//logger.warn("Duplicate router nickname: "+ router.getNickname());
			return;
		}
		routersByNickname.put(name, router);
	}

	synchronized void addDescriptor(RouterDescriptor descriptor) {
		final HexDigest identity = descriptor.getIdentityKey().getFingerprint();
		if(!routersByIdentity.containsKey(identity)) {
			logger.warning("Could not find router for descriptor: "+ descriptor.getIdentityKey().getFingerprint());
			return;
		}
		final RouterImpl router = routersByIdentity.get(identity);
		final RouterDescriptor oldDescriptor = router.getCurrentDescriptor();
		if(descriptor.equals(oldDescriptor))
			return;
		
		if(oldDescriptor != null && oldDescriptor.isNewerThan(descriptor)) {
			logger.warning("Attempting to add descriptor to router which is older than the descriptor we already have");
			return;
		}
		descriptorsDirty = true;
		router.updateDescriptor(descriptor);
		classifyRouter(router);
		checkMinimumRouterInfo();
	}

	synchronized public List<Router> getRoutersWithDownloadableDescriptors() {
		final List<Router> routers = new ArrayList<Router>();
		for(RouterImpl router: routersByIdentity.values()) {
			if(router.isDescriptorDownloadable())
				routers.add(router);
		}

		for(int i = 0; i < routers.size(); i++) {
			final Router a = routers.get(i);
			final int swapIdx = random.nextInt(routers.size());
			final Router b = routers.get(swapIdx);
			routers.set(i, b);
			routers.set(swapIdx, a);
		}

		return routers;
	}

	synchronized public void markDescriptorInvalid(RouterDescriptor descriptor) {
		removeRouterByIdentity(descriptor.getIdentityKey().getFingerprint());	
	}

	private void removeRouterByIdentity(HexDigest identity) {
		logger.fine("Removing: "+ identity);
		final RouterImpl router = routersByIdentity.remove(identity);
		if(router == null)
			return;
		final RouterImpl routerByName = routersByNickname.get(router.getNickname());
		if(routerByName.equals(router))
			routersByNickname.remove(router.getNickname());
		directoryCaches.remove(router);
	}

	public ConsensusDocument getCurrentConsensusDocument() {
		return currentConsensus;
	}

	public void registerConsensusChangedHandler(EventHandler handler) {
		consensusChangedManager.addListener(handler);
	}

	public void unregisterConsensusChangedHandler(EventHandler handler) {
		consensusChangedManager.removeListener(handler);
	}

	public Router getRouterByName(String name) {
		if(name.equals("Unnamed")) {
			return null;
		}
		if(name.length() == 41 && name.charAt(0) == '$') {
			try {
				final HexDigest identity = HexDigest.createFromString(name.substring(1));
				return getRouterByIdentity(identity);
			} catch (Exception e) {
				return null;
			}
		}
		return routersByNickname.get(name);
	}

	public Router getRouterByIdentity(HexDigest identity) {
		synchronized (routersByIdentity) {
			return routersByIdentity.get(identity);
		}
	}

	public List<Router> getRouterListByNames(List<String> names) {
		final List<Router> routers = new ArrayList<Router>();
		for(String n: names) {
			final Router r = getRouterByName(n);
			if(r == null)
				throw new TorException("Could not find router named: "+ n);
			routers.add(r);
		}
		return routers;
	}

	Logger getLogger() {
		return logger;
	}

	public List<Router> getAllRouters() {
		synchronized(routersByIdentity) {
			return new ArrayList<Router>(routersByIdentity.values());
		}
	}

	public GuardEntry createGuardEntryFor(Router router) {
		return stateFile.createGuardEntryFor(router);
	}

	public List<GuardEntry> getGuardEntries() {
		return stateFile.getGuardEntries();
	}

	public void removeGuardEntry(GuardEntry entry) {
		stateFile.removeGuardEntry(entry);
	}

	public void addGuardEntry(GuardEntry entry) {
		stateFile.addGuardEntry(entry);
	}
}

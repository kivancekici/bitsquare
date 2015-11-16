package io.bitsquare.p2p.peers;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import io.bitsquare.app.Log;
import io.bitsquare.common.UserThread;
import io.bitsquare.common.util.Tuple2;
import io.bitsquare.p2p.Address;
import io.bitsquare.p2p.Message;
import io.bitsquare.p2p.network.*;
import io.bitsquare.p2p.peers.messages.auth.AuthenticationRequest;
import io.bitsquare.p2p.peers.messages.maintenance.*;
import io.bitsquare.p2p.storage.messages.DataBroadcastMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;

// Run in UserThread
public class PeerGroup implements MessageListener, ConnectionListener {
    private static final Logger log = LoggerFactory.getLogger(PeerGroup.class);

    static int simulateAuthTorNode = 0;

    public static void setSimulateAuthTorNode(int simulateAuthTorNode) {
        PeerGroup.simulateAuthTorNode = simulateAuthTorNode;
    }

    private static int MAX_CONNECTIONS_LOW_PRIO = 8;
    private static int MAX_CONNECTIONS_NORMAL_PRIO = MAX_CONNECTIONS_LOW_PRIO + 4;
    private static int MAX_CONNECTIONS_HIGH_PRIO = MAX_CONNECTIONS_NORMAL_PRIO + 4;

    public static void setMaxConnectionsLowPrio(int maxConnectionsLowPrio) {
        MAX_CONNECTIONS_LOW_PRIO = maxConnectionsLowPrio;
    }

    private static final int PING_AFTER_CONNECTION_INACTIVITY = 30 * 1000;
    private static final int MAX_REPORTED_PEERS = 1000;

    private final NetworkNode networkNode;
    private final Set<Address> seedNodeAddresses;

    private final Map<Address, Peer> authenticatedPeers = new HashMap<>();
    private final Set<Address> reportedPeerAddresses = new HashSet<>();
    private final Map<Address, AuthenticationHandshake> authenticationHandshakes = new HashMap<>();

    private Timer sendPingTimer = new Timer();
    private Timer getPeersTimer = new Timer();

    private boolean shutDownInProgress;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public PeerGroup(NetworkNode networkNode, Set<Address> seeds) {
        Log.traceCall();

        this.networkNode = networkNode;
        this.seedNodeAddresses = seeds;

        networkNode.addMessageListener(this);
        networkNode.addConnectionListener(this);

        startMaintenanceTimer();
        startGetPeersTimer();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(Message message, Connection connection) {
        if (message instanceof MaintenanceMessage)
            processMaintenanceMessage((MaintenanceMessage) message, connection);
        else if (message instanceof AuthenticationRequest)
            processAuthenticationRequest(networkNode, (AuthenticationRequest) message, connection);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ConnectionListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onConnection(Connection connection) {
    }

    @Override
    public void onPeerAddressAuthenticated(Address peerAddress, Connection connection) {
    }

    @Override
    public void onDisconnect(Reason reason, Connection connection) {
        log.debug("onDisconnect connection=" + connection + " / reason=" + reason);
        removePeer(connection.getPeerAddress());
    }

    @Override
    public void onError(Throwable throwable) {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void removeMySeedNodeAddressFromList(Address mySeedNodeAddress) {
        Log.traceCall();
        seedNodeAddresses.remove(mySeedNodeAddress);
    }

    public void broadcast(DataBroadcastMessage message, @Nullable Address sender) {
        Log.traceCall("Sender " + sender + ". Message " + message.toString());
        if (authenticatedPeers.values().size() > 0) {
            log.info("Broadcast message to {} peers. Message:", authenticatedPeers.values().size(), message);
            // TODO add randomized timing?
            authenticatedPeers.values().stream()
                    .filter(e -> !e.address.equals(sender))
                    .forEach(peer -> {
                        log.trace("Broadcast message from " + getMyAddress() + " to " + peer.address + ".");
                        SettableFuture<Connection> future = networkNode.sendMessage(peer.address, message);
                        Futures.addCallback(future, new FutureCallback<Connection>() {
                            @Override
                            public void onSuccess(Connection connection) {
                                log.trace("Broadcast from " + getMyAddress() + " to " + peer.address + " succeeded.");
                            }

                            @Override
                            public void onFailure(@NotNull Throwable throwable) {
                                log.info("Broadcast failed. " + throwable.getMessage());
                                removePeer(peer.address);
                            }
                        });
                    });
        } else {
            log.trace("Message {} not broadcasted because we are not authenticated yet. " +
                    "That is expected at startup.", message);
        }
    }

    public void shutDown() {
        Log.traceCall();
        if (!shutDownInProgress) {
            shutDownInProgress = true;
            if (sendPingTimer != null)
                sendPingTimer.cancel();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Process incoming authentication request
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void processAuthenticationRequest(NetworkNode networkNode, AuthenticationRequest message, final Connection connection) {
        Log.traceCall(message.toString());
        Address peerAddress = message.address;
        if (!authenticationHandshakes.containsKey(peerAddress)) {
            // We protect that connection from getting closed by maintenance cleanup...
            connection.setConnectionType(ConnectionType.AUTH_REQUEST);
            AuthenticationHandshake authenticationHandshake = new AuthenticationHandshake(networkNode, PeerGroup.this, getMyAddress());
            authenticationHandshakes.put(peerAddress, authenticationHandshake);
            SettableFuture<Connection> future = authenticationHandshake.respondToAuthenticationRequest(message, connection);
            Futures.addCallback(future, new FutureCallback<Connection>() {
                @Override
                public void onSuccess(@Nullable Connection connection) {
                    if (connection != null && peerAddress.equals(connection.getPeerAddress())) {
                        setAuthenticated(connection, peerAddress);
                        purgeReportedPeersIfExceeds();
                    } else {
                        log.error("Incorrect state at processAuthenticationRequest.onSuccess:\n" +
                                "peerAddress={}\nconnection=", peerAddress, connection);
                    }
                }

                @Override
                public void onFailure(@NotNull Throwable throwable) {
                    log.info("AuthenticationHandshake failed. That is expected if peer went offline. " + throwable.getMessage());
                    removePeer(connection.getPeerAddress());
                }
            });
        } else {
            log.warn("An authentication handshake is already created for that peerAddress ({})", peerAddress);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Authentication to seed node
    ///////////////////////////////////////////////////////////////////////////////////////////

    // After HS is published or after a retry from a successful GetDataRequest if no seed nodes have been available initially
    public void authenticateSeedNode(Address peerAddress) {
        Log.traceCall();
        authenticateToSeedNode(new HashSet<>(seedNodeAddresses), peerAddress, true);
    }

    // First we try to connect to 1 seed node. 
    // If we fail we try to connect to one of the remaining seed nodes. 
    // If that fails as well we use the reported peers if available.
    // If there are also no reported peers we retry after a random pause of a few minutes.
    // 
    // After connection is authenticated, we try to connect to any reported peer as long we have not 
    // reached our max connection size.
    private void authenticateToSeedNode(Set<Address> remainingAddresses, Address peerAddress, boolean connectToReportedAfterSuccess) {
        Log.traceCall(peerAddress.getFullAddress());
        checkArgument(!authenticatedPeers.containsKey(peerAddress),
                "We have that peer already authenticated. That must never happen.");
        if (!authenticationHandshakes.containsKey(peerAddress)) {
            AuthenticationHandshake authenticationHandshake = new AuthenticationHandshake(networkNode, this, getMyAddress());
            authenticationHandshakes.put(peerAddress, authenticationHandshake);
            SettableFuture<Connection> future = authenticationHandshake.requestAuthentication(peerAddress);
            Futures.addCallback(future, new FutureCallback<Connection>() {
                @Override
                public void onSuccess(Connection connection) {
                    setAuthenticated(connection, peerAddress);
                    if (connectToReportedAfterSuccess) {
                        if (getAuthenticatedPeers().size() < MAX_CONNECTIONS_LOW_PRIO) {
                            log.info("We still don't have enough connections. Lets try the reported peers.");
                            authenticateToRemainingReportedPeers(true);
                        } else {
                            log.info("We have already enough connections.");
                        }
                    } else {
                        log.info("We have already tried all reported peers and seed nodes. " +
                                "We stop bootstrapping now, but will repeat after an while.");
                        UserThread.runAfterRandomDelay(() -> authenticateToRemainingReportedPeers(true),
                                1, 2, TimeUnit.MINUTES);
                    }
                }

                @Override
                public void onFailure(@NotNull Throwable throwable) {
                    log.info("Send RequestAuthenticationMessage to " + peerAddress + " failed." +
                            "\nThat is expected if seed nodes are offline." +
                            "\nException:" + throwable.getMessage());
                    removePeer(peerAddress);

                    // If we fail we try again with the remaining set excluding the failed one
                    remainingAddresses.remove(peerAddress);

                    log.trace("We try to authenticate to another random seed nodes of that list: " + remainingAddresses);

                    Optional<Tuple2<Address, Set<Address>>> tupleOptional = getRandomNotAuthPeerAndRemainingSet(remainingAddresses);
                    if (tupleOptional.isPresent()) {
                        log.info("We try to authenticate to a seed node. " + tupleOptional.get().first);
                        authenticateToSeedNode(tupleOptional.get().second, tupleOptional.get().first, true);
                    } else if (reportedPeerAddresses.size() > 0) {
                        log.info("We don't have any more seed nodes for connecting. Lets try the reported peers.");
                        authenticateToRemainingReportedPeers(true);
                    } else {
                        log.info("We don't have any more seed nodes nor reported nodes for connecting. " +
                                "We stop authentication attempts now, but will repeat after a few minutes.");
                        UserThread.runAfterRandomDelay(() -> authenticateToRemainingReportedPeers(true),
                                1, 2, TimeUnit.MINUTES);
                    }
                }
            });
        } else {
            log.warn("An authentication handshake is already created for that peerAddress ({})", peerAddress);
        }
    }

    private void authenticateToRemainingSeedNodes() {
        Log.traceCall();
        Optional<Tuple2<Address, Set<Address>>> tupleOptional = getRandomNotAuthPeerAndRemainingSet(seedNodeAddresses);
        if (tupleOptional.isPresent()) {
            log.info("We try to authenticate to a random seed node. " + tupleOptional.get().first);
            authenticateToSeedNode(tupleOptional.get().second, tupleOptional.get().first, true);
        } else {
            log.info("We don't have any more seed nodes for connecting. " +
                    "We stop authentication attempts now, but will repeat after an while.");
            UserThread.runAfterRandomDelay(() -> authenticateToRemainingReportedPeers(false),
                    1, 2, TimeUnit.MINUTES);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Authentication to reported peers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void authenticateToRemainingReportedPeers(boolean calledFromAuthenticateToSeedNode) {
        Log.traceCall();
        Optional<Tuple2<Address, Set<Address>>> tupleOptional = getRandomNotAuthPeerAndRemainingSet(reportedPeerAddresses);
        if (tupleOptional.isPresent()) {
            log.info("We try to authenticate to a random peer. " + tupleOptional.get().first);
            authenticateToReportedPeer(tupleOptional.get().first);
        } else if (calledFromAuthenticateToSeedNode) {
            log.info("We don't have any reported peers for connecting. " +
                    "As we tried recently the seed nodes we will wait a bit before repeating.");
            UserThread.runAfterRandomDelay(() -> authenticateToRemainingSeedNodes(),
                    1, 2, TimeUnit.MINUTES);
        } else {
            log.info("We don't have any reported peers for connecting. Lets try the remaining seed nodes.");
            authenticateToRemainingSeedNodes();
        }
    }

    // We try to connect to a reported peer. If we fail we repeat after the failed peer has been removed.
    // If we succeed we repeat until we are out of addresses.
    private void authenticateToReportedPeer(Address peerAddress) {
        Log.traceCall(peerAddress.getFullAddress());
        checkArgument(!authenticatedPeers.containsKey(peerAddress),
                "We have that peer already authenticated. That must never happen.");
        if (!authenticationHandshakes.containsKey(peerAddress)) {
            AuthenticationHandshake authenticationHandshake = new AuthenticationHandshake(networkNode, this, getMyAddress());
            authenticationHandshakes.put(peerAddress, authenticationHandshake);
            SettableFuture<Connection> future = authenticationHandshake.requestAuthentication(peerAddress);
            Futures.addCallback(future, new FutureCallback<Connection>() {
                @Override
                public void onSuccess(Connection connection) {
                    setAuthenticated(connection, peerAddress);
                    if (getAuthenticatedPeers().size() < MAX_CONNECTIONS_LOW_PRIO) {
                        if (reportedPeerAddresses.size() > 0) {
                            log.info("We still don't have enough connections. " +
                                    "Lets try the remaining reported peer addresses.");
                            authenticateToRemainingReportedPeers(false);
                        } else {
                            log.info("We don't have more reported peers and still don't have enough connections. " +
                                    "Lets wait a bit and then try the remaining seed nodes.");
                            UserThread.runAfterRandomDelay(() -> authenticateToRemainingSeedNodes(),
                                    1, 2, TimeUnit.MINUTES);
                        }
                    } else {
                        log.info("We have already enough connections.");
                    }
                }

                @Override
                public void onFailure(@NotNull Throwable throwable) {
                    log.info("Send RequestAuthenticationMessage to a reported peer with address " + peerAddress + " failed." +
                            "\nThat is expected if the nodes was offline." +
                            "\nException:" + throwable.getMessage());
                    removePeer(peerAddress);

                    if (reportedPeerAddresses.size() > 0) {
                        log.info("Authentication failed. Lets try again with the remaining reported peer addresses.");
                        authenticateToRemainingReportedPeers(false);
                    } else {
                        log.info("Authentication failed. " +
                                "Lets wait a bit and then try the remaining seed nodes.");
                        UserThread.runAfterRandomDelay(() -> authenticateToRemainingSeedNodes(),
                                1, 2, TimeUnit.MINUTES);
                    }
                }
            });
        } else {
            log.warn("An authentication handshake is already created for that peerAddress ({})", peerAddress);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Authentication to peer used for direct messaging
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Priority is set when we receive a decrypted mail message as those are used for direct messages
    public void authenticateToDirectMessagePeer(Address peerAddress,
                                                @Nullable Runnable completeHandler,
                                                @Nullable Runnable faultHandler) {
        Log.traceCall(peerAddress.getFullAddress());
        checkArgument(!authenticatedPeers.containsKey(peerAddress),
                "We have that seed node already authenticated. That must never happen.");
        if (!authenticationHandshakes.containsKey(peerAddress)) {
            AuthenticationHandshake authenticationHandshake = new AuthenticationHandshake(networkNode, this, getMyAddress());
            authenticationHandshakes.put(peerAddress, authenticationHandshake);
            SettableFuture<Connection> future = authenticationHandshake.requestAuthentication(peerAddress);
            Futures.addCallback(future, new FutureCallback<Connection>() {
                @Override
                public void onSuccess(@Nullable Connection connection) {
                    if (connection != null) {
                        setAuthenticated(connection, peerAddress);
                        if (completeHandler != null)
                            completeHandler.run();
                    }
                }

                @Override
                public void onFailure(@NotNull Throwable throwable) {
                    log.error("AuthenticationHandshake failed. " + throwable.getMessage());
                    throwable.printStackTrace();
                    removePeer(peerAddress);
                    if (faultHandler != null)
                        faultHandler.run();
                }
            });
        } else {
            log.warn("An authentication handshake is already created for that peerAddress ({})", peerAddress);
        }
    }

    private void setAuthenticated(Connection connection, Address peerAddress) {
        Log.traceCall(peerAddress.getFullAddress());
        if (authenticationHandshakes.containsKey(peerAddress))
            authenticationHandshakes.remove(peerAddress);
        log.info("\n\n############################################################\n" +
                "We are authenticated to:" +
                "\nconnection=" + connection.getUid()
                + "\nmyAddress=" + getMyAddress()
                + "\npeerAddress= " + peerAddress
                + "\n############################################################\n");

        connection.setAuthenticated(peerAddress, connection);

        addAuthenticatedPeer(new Peer(connection));
    }

    private void addAuthenticatedPeer(Peer peer) {
        Log.traceCall(peer.toString());
        Address peerAddress = peer.address;
        authenticatedPeers.put(peerAddress, peer);
        reportedPeerAddresses.remove(peerAddress);

        if (!checkIfConnectedPeersExceeds())
            printAuthenticatedPeers();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Maintenance
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void startMaintenanceTimer() {
        Log.traceCall();
        if (sendPingTimer != null)
            sendPingTimer.cancel();

        sendPingTimer = UserThread.runAfterRandomDelay(() -> {
            checkIfConnectedPeersExceeds();
            pingPeers();
            startMaintenanceTimer();
        }, 5, 10, TimeUnit.MINUTES);
    }

    private void startGetPeersTimer() {
        Log.traceCall();
        if (getPeersTimer != null)
            getPeersTimer.cancel();

        getPeersTimer = UserThread.runAfterRandomDelay(() -> {
            trySendGetPeersRequest();
            startGetPeersTimer();
        }, 1, 2, TimeUnit.MINUTES);
    }

    // TODO needs unit tests
    private boolean checkIfConnectedPeersExceeds() {
        Log.traceCall();
        int size = authenticatedPeers.size();
        if (size > MAX_CONNECTIONS_LOW_PRIO) {
            Set<Connection> allConnections = networkNode.getAllConnections();
            log.info("We have {} connections open. Lets remove the passive connections" +
                    " which have not been active recently.", allConnections.size());
            if (size != allConnections.size())
                log.warn("authenticatedPeers.size()!=allConnections.size(). There is some inconsistency.");

            List<Connection> authenticatedConnections = allConnections.stream()
                    .filter(e -> e.isAuthenticated())
                    .filter(e -> e.getConnectionType() == ConnectionType.PASSIVE)
                    .collect(Collectors.toList());

            if (authenticatedConnections.size() == 0) {
                log.debug("There are no passive connections for closing. We check if we are exceeding " +
                        "MAX_CONNECTIONS_NORMAL ({}) ", MAX_CONNECTIONS_NORMAL_PRIO);
                if (size > MAX_CONNECTIONS_NORMAL_PRIO) {
                    authenticatedConnections = allConnections.stream()
                            .filter(e -> e.isAuthenticated())
                            .filter(e -> e.getConnectionType() == ConnectionType.PASSIVE || e.getConnectionType() == ConnectionType.ACTIVE)
                            .collect(Collectors.toList());

                    if (authenticatedConnections.size() == 0) {
                        log.debug("There are no passive or active connections for closing. We check if we are exceeding " +
                                "MAX_CONNECTIONS_HIGH ({}) ", MAX_CONNECTIONS_HIGH_PRIO);
                        if (size > MAX_CONNECTIONS_HIGH_PRIO) {
                            authenticatedConnections = allConnections.stream()
                                    .filter(e -> e.isAuthenticated())
                                    .collect(Collectors.toList());
                        }
                    }
                }
            }

            if (authenticatedConnections.size() > 0) {
                authenticatedConnections.sort((o1, o2) -> o1.getLastActivityDate().compareTo(o2.getLastActivityDate()));
                log.info("Number of connections exceeds MAX_CONNECTIONS. Current size=" + authenticatedConnections.size());
                Connection connection = authenticatedConnections.remove(0);
                log.info("We had shut down the oldest connection with last activity date="
                        + connection.getLastActivityDate() + " / connection=" + connection);
                connection.shutDown(() -> UserThread.runAfterRandomDelay(() -> checkIfConnectedPeersExceeds(), 100, 500, TimeUnit.MILLISECONDS));
                return true;
            } else {
                log.warn("That code path should never be reached. (checkIfConnectedPeersExceeds)");
                return false;
            }
        } else {
            log.trace("We only have {} connections open and don't need to close any.", size);
            return false;
        }
    }

    private void pingPeers() {
        Set<Peer> connectedPeersList = new HashSet<>(authenticatedPeers.values());
        if (!connectedPeersList.isEmpty()) {
            Log.traceCall();
            connectedPeersList.stream()
                    .filter(e -> (new Date().getTime() - e.connection.getLastActivityDate().getTime()) > PING_AFTER_CONNECTION_INACTIVITY)
                    .forEach(e -> UserThread.runAfterRandomDelay(() -> {
                        SettableFuture<Connection> future = networkNode.sendMessage(e.connection, new PingMessage(e.getPingNonce()));
                        Futures.addCallback(future, new FutureCallback<Connection>() {
                            @Override
                            public void onSuccess(Connection connection) {
                                log.trace("PingMessage sent successfully");
                            }

                            @Override
                            public void onFailure(@NotNull Throwable throwable) {
                                log.info("PingMessage sending failed " + throwable.getMessage());
                                removePeer(e.address);
                            }
                        });
                    }, 1, 10));
        }
    }

    private void trySendGetPeersRequest() {
        Collection<Peer> peers = authenticatedPeers.values();
        if (!peers.isEmpty()) {
            Log.traceCall();
            Set<Peer> connectedPeersList = new HashSet<>(peers);
            connectedPeersList.stream()
                    .forEach(e -> UserThread.runAfterRandomDelay(() -> {
                        SettableFuture<Connection> future = networkNode.sendMessage(e.connection,
                                new GetPeersRequest(getMyAddress(), new HashSet<>(getAllPeerAddresses())));
                        Futures.addCallback(future, new FutureCallback<Connection>() {
                            @Override
                            public void onSuccess(Connection connection) {
                                log.trace("sendGetPeersRequest sent successfully");
                            }

                            @Override
                            public void onFailure(@NotNull Throwable throwable) {
                                log.info("sendGetPeersRequest sending failed " + throwable.getMessage());
                                removePeer(e.address);
                            }
                        });
                    }, 5, 10));
        }
    }

    private void processMaintenanceMessage(MaintenanceMessage message, Connection connection) {
        Log.traceCall(message.toString());
        log.debug("Received message " + message + " at " + getMyAddress() + " from " + connection.getPeerAddress());
        if (message instanceof PingMessage) {
            SettableFuture<Connection> future = networkNode.sendMessage(connection, new PongMessage(((PingMessage) message).nonce));
            Futures.addCallback(future, new FutureCallback<Connection>() {
                @Override
                public void onSuccess(Connection connection) {
                    log.trace("PongMessage sent successfully");
                }

                @Override
                public void onFailure(@NotNull Throwable throwable) {
                    log.info("PongMessage sending failed " + throwable.getMessage());
                    removePeer(connection.getPeerAddress());
                }
            });
        } else if (message instanceof PongMessage) {
            if (connection.getPeerAddress() != null) {
                Peer peer = authenticatedPeers.get(connection.getPeerAddress());
                if (peer != null) {
                    if (((PongMessage) message).nonce != peer.getPingNonce()) {
                        log.warn("PongMessage invalid: self/peer " + getMyAddress() + "/" + connection.getPeerAddress());
                        removePeer(peer.address);
                    }
                }
            }
        } else if (message instanceof GetPeersRequest) {
            GetPeersRequest getPeersRequestMessage = (GetPeersRequest) message;
            HashSet<Address> peerAddresses = getPeersRequestMessage.peerAddresses;
            log.trace("Received peers: " + peerAddresses);
            addToReportedPeers(peerAddresses, connection);

            SettableFuture<Connection> future = networkNode.sendMessage(connection,
                    new GetPeersResponse(new HashSet<>(getAllPeerAddresses())));
            Futures.addCallback(future, new FutureCallback<Connection>() {
                @Override
                public void onSuccess(Connection connection) {
                    log.trace("GetPeersResponse sent successfully");
                }

                @Override
                public void onFailure(@NotNull Throwable throwable) {
                    log.info("GetPeersResponse sending failed " + throwable.getMessage());
                    removePeer(getPeersRequestMessage.address);
                }
            });
        } else if (message instanceof GetPeersResponse) {
            GetPeersResponse getPeersResponse = (GetPeersResponse) message;
            HashSet<Address> peerAddresses = getPeersResponse.peerAddresses;
            log.trace("Received peers: " + peerAddresses);
            addToReportedPeers(peerAddresses, connection);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    private Map<Address, Peer> getAuthenticatedPeers() {
        return authenticatedPeers;
    }

    public Set<Address> getAllPeerAddresses() {
        Set<Address> allPeerAddresses = new HashSet<>(reportedPeerAddresses);
        allPeerAddresses.addAll(authenticatedPeers.values().stream()
                .map(e -> e.address).collect(Collectors.toSet()));
        return allPeerAddresses;
    }

    public Set<Address> getSeedNodeAddresses() {
        return seedNodeAddresses;
    }

    public NetworkNode getNetworkNode() {
        return networkNode;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Reported peers
    ///////////////////////////////////////////////////////////////////////////////////////////

    void addToReportedPeers(HashSet<Address> peerAddresses, Connection connection) {
        Log.traceCall();
        // we disconnect misbehaving nodes trying to send too many peers
        // reported peers include the peers connected peers which is normally max. 8 but we give some headroom 
        // for safety
        if (peerAddresses.size() > 1100) {
            connection.shutDown();
        } else {
            peerAddresses.remove(getMyAddress());
            reportedPeerAddresses.addAll(peerAddresses);
            purgeReportedPeersIfExceeds();
        }
    }

    private void purgeReportedPeersIfExceeds() {
        Log.traceCall();
        int size = reportedPeerAddresses.size();
        if (size > MAX_REPORTED_PEERS) {
            log.trace("We have more then {} reported peers. size={}. " +
                    "We remove random peers from the reported peers list.", MAX_REPORTED_PEERS, size);
            int diff = size - MAX_REPORTED_PEERS;
            List<Address> list = new LinkedList<>(getReportedNotConnectedPeerAddresses());
            for (int i = 0; i < diff; i++) {
                Address toRemove = getAndRemoveRandomAddress(list);
                reportedPeerAddresses.remove(toRemove);
            }
        } else {
            log.trace("We don't have more then {} reported peers yet.", MAX_REPORTED_PEERS);
        }
    }

    private Set<Address> getReportedNotConnectedPeerAddresses() {
        Log.traceCall();
        Set<Address> set = new HashSet<>(reportedPeerAddresses);
        authenticatedPeers.values().stream().forEach(e -> set.remove(e.address));
        return set;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Peers
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void removePeer(@Nullable Address peerAddress) {
        Log.traceCall("peerAddress=" + peerAddress);
        if (peerAddress != null) {
            if (authenticationHandshakes.containsKey(peerAddress))
                authenticationHandshakes.remove(peerAddress);

            boolean wasInReportedPeers = reportedPeerAddresses.remove(peerAddress);
            Peer disconnectedPeer = authenticatedPeers.remove(peerAddress);
            if (wasInReportedPeers || disconnectedPeer != null)
                printAllPeers();
        }
    }

    private Address getMyAddress() {
        // Log.traceCall();
        return networkNode.getAddress();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    private Optional<Tuple2<Address, Set<Address>>> getRandomNotAuthPeerAndRemainingSet(Set<Address> remainingAddresses) {
        Log.traceCall();
        List<Address> list = new ArrayList<>(remainingAddresses);
        authenticatedPeers.values().stream().forEach(e -> list.remove(e.address));
        if (!list.isEmpty()) {
            Address item = getAndRemoveRandomAddress(list);
            return Optional.of(new Tuple2<>(item, new HashSet<>(list)));
        } else {
            return Optional.empty();
        }
    }

    private Address getAndRemoveRandomAddress(List<Address> list) {
        Log.traceCall();
        return list.remove(new Random().nextInt(list.size()));
    }


    public void printAllPeers() {
        printAuthenticatedPeers();
        printReportedPeers();
    }

    public void printAuthenticatedPeers() {
        StringBuilder result = new StringBuilder("\n\n------------------------------------------------------------\n" +
                "Authenticated peers for node " + getMyAddress() + ":");
        authenticatedPeers.values().stream().forEach(e -> result.append("\n").append(e.address));
        result.append("\n------------------------------------------------------------\n");
        log.info(result.toString());
    }

    public void printReportedPeers() {
        StringBuilder result = new StringBuilder("\n\n------------------------------------------------------------\n" +
                "Reported peers for node " + getMyAddress() + ":");
        reportedPeerAddresses.stream().forEach(e -> result.append("\n").append(e));
        result.append("\n------------------------------------------------------------\n");
        log.info(result.toString());
    }

}

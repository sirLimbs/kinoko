package kinoko.server.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import kinoko.packet.CentralPacket;
import kinoko.packet.world.PartyPacket;
import kinoko.server.header.CentralHeader;
import kinoko.server.node.*;
import kinoko.server.packet.InPacket;
import kinoko.server.packet.OutPacket;
import kinoko.util.Util;
import kinoko.world.GameConstants;
import kinoko.world.social.party.Party;
import kinoko.world.social.party.PartyRequest;
import kinoko.world.social.party.PartyResultType;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

public final class CentralServerHandler extends SimpleChannelInboundHandler<InPacket> {
    private static final Logger log = LogManager.getLogger(CentralServerHandler.class);
    private final CentralServerNode centralServerNode;

    public CentralServerHandler(CentralServerNode centralServerNode) {
        this.centralServerNode = centralServerNode;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, InPacket inPacket) {
        final RemoteChildNode remoteChildNode = ctx.channel().attr(RemoteChildNode.NODE_KEY).get();
        if (remoteChildNode == null) {
            log.error("Received packet from unknown node {}", ctx.channel().remoteAddress());
            return;
        }
        final int op = inPacket.decodeShort();
        final CentralHeader header = CentralHeader.getByValue(op);
        log.log(Level.TRACE, "[CentralServerNode] | {}({}) {}", header, Util.opToString(op), inPacket);
        switch (header) {
            case InitializeResult -> {
                final int channelId = inPacket.decodeInt();
                final byte[] channelHost = inPacket.decodeArray(4);
                final int channelPort = inPacket.decodeInt();
                // Initialize child node
                remoteChildNode.setChannelId(channelId);
                remoteChildNode.setChannelHost(channelHost);
                remoteChildNode.setChannelPort(channelPort);
                centralServerNode.addChildNode(remoteChildNode);
            }
            case ShutdownResult -> {
                final int channelId = inPacket.decodeInt();
                final boolean success = inPacket.decodeBoolean();
                if (!success) {
                    log.error("Failed to shutdown channel {}, trying again", channelId + 1);
                    remoteChildNode.write(CentralPacket.shutdownRequest());
                    return;
                }
                centralServerNode.removeChildNode(channelId);
            }
            case MigrateRequest -> {
                // Channel migration - complete stored migration request
                final int requestId = inPacket.decodeInt();
                final int accountId = inPacket.decodeInt();
                final int characterId = inPacket.decodeInt();
                final byte[] machineId = inPacket.decodeArray(16);
                final byte[] clientKey = inPacket.decodeArray(8);
                final Optional<MigrationInfo> migrationResult = centralServerNode.completeMigrationRequest(remoteChildNode.getChannelId(), accountId, characterId, machineId, clientKey);
                remoteChildNode.write(CentralPacket.migrateResult(requestId, migrationResult.orElse(null)));
            }
            case TransferRequest -> {
                // Channel transfer - create migration request and reply with transfer info
                final int requestId = inPacket.decodeInt();
                final MigrationInfo migrationInfo = MigrationInfo.decode(inPacket);
                // Resolve target channel
                final Optional<RemoteChildNode> targetNodeResult = centralServerNode.getChildNodeByChannelId(migrationInfo.getChannelId());
                if (targetNodeResult.isEmpty()) {
                    log.error("Failed to resolve channel ID {}", migrationInfo.getChannelId() + 1);
                    remoteChildNode.write(CentralPacket.transferResult(requestId, null));
                    return;
                }
                final RemoteChildNode targetNode = targetNodeResult.get();
                // Submit migration request
                if (!centralServerNode.submitMigrationRequest(migrationInfo)) {
                    log.error("Failed to submit migration request for character ID : {}", migrationInfo.getCharacterId());
                    remoteChildNode.write(CentralPacket.transferResult(requestId, null));
                    return;
                }
                // Reply with transfer info
                remoteChildNode.write(CentralPacket.transferResult(requestId, new TransferInfo(
                        targetNode.getChannelHost(),
                        targetNode.getChannelPort()
                )));
            }
            case UserConnect -> {
                final RemoteUser remoteUser = RemoteUser.decode(inPacket);
                centralServerNode.addUser(remoteUser);
                updatePartyMember(remoteUser);
            }
            case UserUpdate -> {
                final RemoteUser remoteUser = RemoteUser.decode(inPacket);
                centralServerNode.updateUser(remoteUser);
                updatePartyMember(remoteUser);
            }
            case UserDisconnect -> {
                final RemoteUser remoteUser = RemoteUser.decode(inPacket);
                centralServerNode.removeUser(remoteUser);
                if (!centralServerNode.isMigrating(remoteUser.getAccountId())) {
                    // Set channel to offline
                    remoteUser.setChannelId(GameConstants.CHANNEL_OFFLINE);
                    updatePartyMember(remoteUser);
                }
            }
            case UserPacketRequest -> {
                final String characterName = inPacket.decodeString();
                final int packetLength = inPacket.decodeInt();
                final byte[] packetData = inPacket.decodeArray(packetLength);
                // Resolve target user
                final Optional<RemoteUser> targetResult = centralServerNode.getUserByCharacterName(characterName);
                if (targetResult.isEmpty()) {
                    return;
                }
                final RemoteUser target = targetResult.get();
                // Resolve target node
                final Optional<RemoteChildNode> targetNodeResult = centralServerNode.getChildNodeByChannelId(target.getChannelId());
                if (targetNodeResult.isEmpty()) {
                    log.error("Failed to resolve channel ID {}", target.getChannelId() + 1);
                    return;
                }
                // Send UserPacketReceive to target channel node
                targetNodeResult.get().write(CentralPacket.userPacketReceive(target.getCharacterId(), OutPacket.of(packetData)));
            }
            case UserPacketReceive -> {
                final int characterId = inPacket.decodeInt();
                final int packetLength = inPacket.decodeInt();
                final byte[] packetData = inPacket.decodeArray(packetLength);
                // Resolve target user
                final Optional<RemoteUser> targetResult = centralServerNode.getUserByCharacterId(characterId);
                if (targetResult.isEmpty()) {
                    return;
                }
                final RemoteUser target = targetResult.get();
                // Resolve target node
                final Optional<RemoteChildNode> targetNodeResult = centralServerNode.getChildNodeByChannelId(target.getChannelId());
                if (targetNodeResult.isEmpty()) {
                    log.error("Failed to resolve channel ID {}", target.getChannelId() + 1);
                    return;
                }
                // Send UserPacketReceive to target channel node
                targetNodeResult.get().write(CentralPacket.userPacketReceive(target.getCharacterId(), OutPacket.of(packetData)));
            }
            case UserPacketBroadcast -> {
                final int size = inPacket.decodeInt();
                final Set<Integer> characterIds = new HashSet<>();
                for (int i = 0; i < size; i++) {
                    characterIds.add(inPacket.decodeInt());
                }
                final int packetLength = inPacket.decodeInt();
                final byte[] packetData = inPacket.decodeArray(packetLength);
                for (RemoteChildNode childNode : centralServerNode.getConnectedNodes()) {
                    childNode.write(CentralPacket.userPacketBroadcast(characterIds, OutPacket.of(packetData)));
                }
            }
            case UserQueryRequest -> {
                // Resolve queried users
                final int requestId = inPacket.decodeInt();
                final int size = inPacket.decodeInt();
                final Set<RemoteUser> remoteUsers = new HashSet<>();
                for (int i = 0; i < size; i++) {
                    final String characterName = inPacket.decodeString();
                    centralServerNode.getUserByCharacterName(characterName)
                            .ifPresent(remoteUsers::add);
                }
                // Reply with queried remote users
                remoteChildNode.write(CentralPacket.userQueryResult(requestId, remoteUsers));
            }
            case PartyRequest -> {
                final int characterId = inPacket.decodeInt();
                final PartyRequest partyRequest = PartyRequest.decode(inPacket);
                // Resolve requester user
                final Optional<RemoteUser> remoteUserResult = centralServerNode.getUserByCharacterId(characterId);
                if (remoteUserResult.isEmpty()) {
                    log.error("Failed to resolve user with character ID : {} for PartyRequest", characterId);
                    return;
                }
                final RemoteUser remoteUser = remoteUserResult.get();
                // Process request
                switch (partyRequest.getRequestType()) {
                    case LoadParty -> {
                        // Remote user party ID is set on UserConnect
                        final Optional<Party> partyResult = centralServerNode.getPartyById(remoteUser.getPartyId());
                        if (partyResult.isEmpty()) {
                            remoteChildNode.write(CentralPacket.partyResult(remoteUser.getCharacterId(), 0, 0));
                            return;
                        }
                        try (var lockedParty = partyResult.get().acquire()) {
                            final Party party = lockedParty.get();
                            remoteChildNode.write(CentralPacket.partyResult(remoteUser.getCharacterId(), party.getPartyId(), party.getMemberIndex(remoteUser)));
                            remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.loadPartyDone(party)));
                        }
                    }
                    case CreateNewParty -> {
                        final Optional<Party> partyResult = centralServerNode.getPartyById(remoteUser.getPartyId());
                        if (partyResult.isPresent()) {
                            remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.of(PartyResultType.CreateNewParty_AlreadyJoined))); // Already have joined a party.
                            return;
                        }
                        // Create party
                        final Party party = centralServerNode.createNewParty(remoteUser);
                        remoteUser.setPartyId(party.getPartyId());
                        remoteChildNode.write(CentralPacket.partyResult(remoteUser.getCharacterId(), party.getPartyId(), party.getMemberIndex(remoteUser)));
                        remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.createNewPartyDone(party, remoteUser.getTownPortal()))); // You have created a new party.
                    }
                    case WithdrawParty -> {
                        final Optional<Party> partyResult = centralServerNode.getPartyById(remoteUser.getPartyId());
                        if (partyResult.isEmpty()) {
                            remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.of(PartyResultType.WithdrawParty_NotJoined))); // You have yet to join a party.
                            return;
                        }
                        try (var lockedParty = partyResult.get().acquire()) {
                            final Party party = lockedParty.get();
                            if (party.getPartyBossId() == remoteUser.getCharacterId()) {
                                // Disband party
                                if (!centralServerNode.removeParty(party)) {
                                    log.error("Failed to disband party {}", party.getPartyId());
                                    remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.of(PartyResultType.WithdrawParty_Unknown))); // Your request for a party didn't work due to an unexpected error.
                                    return;
                                }
                                // Broadcast disband packet to party and update party ids
                                final OutPacket outPacket = PartyPacket.withdrawPartyDone(party, remoteUser, true, false); // You have quit as the leader of the party. The party has been disbanded. | You have left the party since the party leader quit.
                                forEachPartyMember(party, (member, node) -> {
                                    member.setPartyId(0);
                                    node.write(CentralPacket.partyResult(member.getCharacterId(), 0, 0));
                                    node.write(CentralPacket.userPacketReceive(member.getCharacterId(), outPacket));
                                });
                            } else {
                                // Remove member
                                if (!party.removeMember(remoteUser)) {
                                    log.error("Failed to remove member with character ID {} from party {}", remoteUser.getCharacterId(), party.getPartyId());
                                    remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.of(PartyResultType.WithdrawParty_Unknown))); // Your request for a party didn't work due to an unexpected error.
                                    return;
                                }
                                // Broadcast withdraw packet to party
                                final OutPacket outPacket = PartyPacket.withdrawPartyDone(party, remoteUser, false, false); // You have left the party. | '%s' have left the party.
                                forEachPartyMember(party, (member, node) -> {
                                    node.write(CentralPacket.partyResult(member.getCharacterId(), party.getPartyId(), party.getMemberIndex(member))); // update member index
                                    node.write(CentralPacket.userPacketReceive(member.getCharacterId(), outPacket));
                                });
                                // Update user
                                remoteUser.setPartyId(0);
                                remoteChildNode.write(CentralPacket.partyResult(remoteUser.getCharacterId(), 0, 0));
                                remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), outPacket));
                            }
                        }
                    }
                    case JoinParty -> {
                        // Check current party
                        if (remoteUser.getPartyId() != 0) {
                            remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.of(PartyResultType.JoinParty_Unknown))); // Already have joined a party.
                            return;
                        }
                        // Resolve party
                        final int inviterId = partyRequest.getCharacterId();
                        final Optional<Party> partyResult = centralServerNode.getPartyByCharacterId(inviterId);
                        if (partyResult.isEmpty()) {
                            remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.of(PartyResultType.JoinParty_Unknown))); // Your request for a party didn't work due to an unexpected error.
                            return;
                        }
                        try (var lockedParty = partyResult.get().acquire()) {
                            final Party party = lockedParty.get();
                            if (!party.addMember(remoteUser)) {
                                remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.of(PartyResultType.JoinParty_AlreadyFull))); // The party you're trying to join is already in full capacity.
                                return;
                            }
                            // Broadcast join packet to party
                            final OutPacket outPacket = PartyPacket.joinPartyDone(party, remoteUser); // You have joined the party | '%s' has joined the party.
                            forEachPartyMember(party, (member, node) -> {
                                if (member.getCharacterId() == remoteUser.getCharacterId()) {
                                    member.setPartyId(party.getPartyId());
                                    node.write(CentralPacket.partyResult(member.getCharacterId(), party.getPartyId(), party.getMemberIndex(member)));
                                }
                                node.write(CentralPacket.userPacketReceive(member.getCharacterId(), outPacket));
                            });
                        }
                    }
                    case InviteParty -> {
                        // Resolve party
                        final Optional<Party> partyResult = centralServerNode.getPartyById(remoteUser.getPartyId());
                        if (partyResult.isEmpty()) {
                            // Create party
                            final Party party = centralServerNode.createNewParty(remoteUser);
                            remoteUser.setPartyId(party.getPartyId());
                            remoteChildNode.write(CentralPacket.partyResult(remoteUser.getCharacterId(), party.getPartyId(), party.getMemberIndex(remoteUser)));
                            remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.createNewPartyDone(party, remoteUser.getTownPortal()))); // You have created a new party.
                        }
                        // Resolve target
                        final String targetName = partyRequest.getCharacterName();
                        final Optional<RemoteUser> targetResult = centralServerNode.getUserByCharacterName(targetName); // target name
                        if (targetResult.isEmpty()) {
                            remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.serverMsg(String.format("Unable to find '%s'", targetName))));
                            return;
                        }
                        final RemoteUser target = targetResult.get();
                        if (target.getPartyId() != 0) {
                            remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.serverMsg(String.format("'%s' is already in a party.", targetName))));
                            return;
                        }
                        // Resolve target node
                        final Optional<RemoteChildNode> targetNodeResult = centralServerNode.getChildNodeByChannelId(target.getChannelId());
                        if (targetNodeResult.isEmpty()) {
                            remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.serverMsg(String.format("Unable to find '%s'", targetName))));
                            return;
                        }
                        // Send party invite to target
                        targetNodeResult.get().write(CentralPacket.userPacketReceive(target.getCharacterId(), PartyPacket.inviteParty(remoteUser)));
                    }
                    case KickParty -> {
                        final Optional<Party> partyResult = centralServerNode.getPartyById(remoteUser.getPartyId());
                        if (partyResult.isEmpty()) {
                            remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.of(PartyResultType.KickParty_Unknown))); // Your request for a party didn't work due to an unexpected error.
                            return;
                        }
                        try (var lockedParty = partyResult.get().acquire()) {
                            // Check that the kick request is valid
                            final Party party = lockedParty.get();
                            final int targetId = partyRequest.getCharacterId();
                            final Optional<RemoteUser> targetMember = party.getMember(targetId);
                            if (party.getPartyBossId() != remoteUser.getCharacterId() || targetMember.isEmpty() || !party.removeMember(targetMember.get())) {
                                remoteChildNode.write(CentralPacket.userPacketReceive(remoteUser.getCharacterId(), PartyPacket.of(PartyResultType.KickParty_Unknown))); // Your request for a party didn't work due to an unexpected error.
                                return;
                            }
                            // Broadcast kick packet to party
                            final OutPacket outPacket = PartyPacket.withdrawPartyDone(party, targetMember.get(), false, true); // You have been expelled from the party. | '%s' have been expelled from the party.
                            forEachPartyMember(party, (member, node) -> {
                                if (member.getCharacterId() == targetId) {
                                    member.setPartyId(0);
                                    node.write(CentralPacket.partyResult(member.getCharacterId(), 0, 0));
                                }
                                node.write(CentralPacket.userPacketReceive(member.getCharacterId(), outPacket));
                            });
                        }
                    }
                    case ChangePartyBoss -> {
                        final Optional<Party> partyResult = centralServerNode.getPartyById(remoteUser.getPartyId());
                        if (partyResult.isEmpty()) {
                            remoteChildNode.write(CentralPacket.userPacketReceive(characterId, PartyPacket.of(PartyResultType.ChangePartyBoss_Unknown))); // Your request for a party didn't work due to an unexpected error.
                            return;
                        }
                        try (var lockedParty = partyResult.get().acquire()) {
                            // Try setting new party boss
                            final Party party = lockedParty.get();
                            final int targetId = partyRequest.getCharacterId();
                            if (!party.setPartyBossId(remoteUser.getCharacterId(), targetId)) {
                                remoteChildNode.write(CentralPacket.userPacketReceive(characterId, PartyPacket.of(PartyResultType.ChangePartyBoss_Unknown))); // Your request for a party didn't work due to an unexpected error.
                                return;
                            }
                            // Broadcast packet to party
                            final OutPacket outPacket = PartyPacket.changePartyBossDone(targetId, false); // %s has become the leader of the party.
                            forEachPartyMember(party, (member, node) -> {
                                node.write(CentralPacket.userPacketReceive(member.getCharacterId(), outPacket));
                            });
                        }
                    }
                }
            }
            case null -> {
                log.error("Central Server received an unknown opcode : {}", op);
            }
            default -> {
                log.error("Central Server received an unhandled header : {}", header);
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        final RemoteChildNode remoteChildNode = ctx.channel().attr(RemoteChildNode.NODE_KEY).get();
        if (remoteChildNode != null) {
            log.error("Lost connection to channel {}", remoteChildNode.getChannelId());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception caught while handling packet", cause);
        cause.printStackTrace();
    }

    private void updatePartyMember(RemoteUser remoteUser) {
        final Optional<Party> partyResult = centralServerNode.getPartyByCharacterId(remoteUser.getCharacterId());
        if (partyResult.isEmpty()) {
            return;
        }
        try (var lockedParty = partyResult.get().acquire()) {
            // Set party ID
            final Party party = lockedParty.get();
            remoteUser.setPartyId(party.getPartyId());
            // Update user for all members
            party.updateMember(remoteUser);
            final OutPacket outPacket = PartyPacket.loadPartyDone(party);
            forEachPartyMember(party, (member, node) -> {
                node.write(CentralPacket.userPacketReceive(member.getCharacterId(), outPacket));
            });
        }
    }

    private void forEachPartyMember(Party party, BiConsumer<RemoteUser, RemoteChildNode> biConsumer) {
        party.forEachMember((member) -> {
            final Optional<RemoteChildNode> targetNodeResult = centralServerNode.getChildNodeByChannelId(member.getChannelId());
            if (targetNodeResult.isEmpty()) {
                return;
            }
            biConsumer.accept(member, targetNodeResult.get());
        });
    }
}

package org.asf.centuria.networking.chatserver;

import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.ConcurrentModificationException;
import java.util.HashMap;

import org.apache.logging.log4j.MarkerManager;
import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.AccountManager;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.dms.DMManager;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.events.chat.ChatLoginEvent;
import org.asf.centuria.networking.chatserver.networking.AbstractChatPacket;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.util.TaskThread;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

public class ChatClient {

	private Socket client;
	private ChatServer server;
	private JsonReader reader;
	private CenturiaAccount player;
	private ArrayList<String> rooms = new ArrayList<String>();
	private HashMap<String, Boolean> privateChat = new HashMap<String, Boolean>();

	private ArrayList<Object> objects = new ArrayList<Object>();

	/**
	 * Retrieves objects from the connection container, used to store information in
	 * clients.
	 * 
	 * @since Beta 1.5.3
	 * @param type Object type
	 * @return Object instance or null
	 */
	@SuppressWarnings("unchecked")
	public <T> T getObject(Class<T> type) {
		for (Object obj : objects) {
			if (type.isAssignableFrom(obj.getClass()))
				return (T) obj;
		}
		return null;
	}

	/**
	 * Adds objects to the connection container, used to store information in
	 * clients.
	 * 
	 * @since Beta 1.5.3
	 * @param obj Object to add
	 */
	public void addObject(Object obj) {
		if (getObject(obj.getClass()) == null)
			objects.add(obj);
	}

	// Anti-hack
	public int banCounter = 0;

	// Room lock
	public boolean isReady = false;

	private TaskThread taskThread;

	public ChatClient(Socket client, ChatServer server) {
		this.client = client;
		this.server = server;

		taskThread = new TaskThread(client.toString());
		taskThread.start();

		reader = new JsonReader(new Reader() {

			@Override
			public int read(char[] cbuf, int off, int len) throws IOException {
				byte[] data = new byte[cbuf.length];
				int l = client.getInputStream().read(data);
				for (int i = 0; i < l; i++) {
					cbuf[off + i] = (char) data[i];
				}
				return l;
			}

			@Override
			public void close() throws IOException {
			}

		});

		Thread th = new Thread(() -> {
			while (isConnected()) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}
				banCounter = 0;
			}
		}, "Anti-hack thread: " + client);
		th.setDaemon(true);
		th.start();
	}

	void stop() {
		taskThread.stopCleanly();
		rooms.clear();
		privateChat.clear();
	}

	// Client init
	void runClient() throws IOException {
		// Read initial packet
		JsonObject handshakeStart = readRawPacket();

		// Check validity
		if (!handshakeStart.get("cmd").getAsString().equals("sessions.start")) {
			// Invalid request, too early to send different packet
			disconnect();
			return;
		}

		// Parse payload
		String token = handshakeStart.get("auth_token").getAsString();

		// Verify signature
		String verifyD = token.split("\\.")[0] + "." + token.split("\\.")[1];
		String sig = token.split("\\.")[2];
		if (!Centuria.verify(verifyD.getBytes("UTF-8"), Base64.getUrlDecoder().decode(sig))) {
			disconnect();
			return;
		}

		// Verify expiry
		JsonObject jwtPl = JsonParser
				.parseString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), "UTF-8"))
				.getAsJsonObject();
		if (!jwtPl.has("exp") || jwtPl.get("exp").getAsLong() < System.currentTimeMillis() / 1000) {
			disconnect();
			return;
		}

		// Parse JWT
		JsonObject payload = JsonParser
				.parseString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), "UTF-8"))
				.getAsJsonObject();

		// Verify access
		if (!payload.has("acs") || !payload.get("acs").getAsString().equals("gameplay")) {
			disconnect();
			return;
		}

		// Locate account
		CenturiaAccount acc = AccountManager.getInstance().getAccount(payload.get("uuid").getAsString());
		if (acc == null) {
			disconnect();
			return;
		}

		// Load DMs into memory
		if (acc.getSaveSharedInventory().containsItem("dms")) {
			// Load and sanitize dms
			JsonObject dms = acc.getSaveSharedInventory().getItem("dms").getAsJsonObject();
			ArrayList<String> toRemove = new ArrayList<String>();
			for (String user : dms.keySet()) {
				// Clean DM participants
				String dmID = dms.get(user).getAsString();
				int participantC = 0;
				if (DMManager.getInstance().dmExists(dmID)) {
					String[] participants = DMManager.getInstance().getDMParticipants(dmID);
					participantC = participants.length;
					for (String participant : participants) {
						if (!participant.startsWith("plaintext:")) {
							// Check account
							if (AccountManager.getInstance().getAccount(participant) == null) {
								participantC--;
								DMManager.getInstance().removeParticipant(dmID, participant);
							}
						}
					}
				}

				// Check validity
				if (AccountManager.getInstance().getAccount(user) == null || participantC <= 1) {
					toRemove.add(user);
					continue;
				}

				// Join room
				joinRoom(dms.get(user).getAsString(), true);
			}

			// Remove nonexistent and invalid dms
			for (String user : toRemove) {
				if (DMManager.getInstance().dmExists(dms.get(user).getAsString()))
					DMManager.getInstance().deleteDM(dms.get(user).getAsString());
				dms.remove(user);
			}

			// Save if needed
			if (toRemove.size() != 0)
				acc.getSaveSharedInventory().setItem("dms", dms);
		}

		// Remove sensitive info and fire event
		handshakeStart.remove("auth_token");
		ChatLoginEvent evt = new ChatLoginEvent(server, acc, this, handshakeStart);
		EventBus.getInstance().dispatchEvent(evt);
		if (evt.isCancelled()) {
			disconnect(); // Cancelled
			return;
		}

		// Check maintenance mode
		if (Centuria.gameServer.maintenance) {
			boolean lockout = true;

			// Check permissions
			if (acc.getSaveSharedInventory().containsItem("permissions")) {
				String permLevel = acc.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
						.get("permissionLevel").getAsString();
				if (GameServer.hasPerm(permLevel, "admin")) {
					lockout = false;
				}
			}

			if (lockout || Centuria.gameServer.shutdown) {
				disconnect();
				return;
			}
		}

		// Check bans
		if (acc.isBanned()) {
			disconnect();
			return;
		}

		// Check ip ban
		InetSocketAddress ip = (InetSocketAddress) client.getRemoteSocketAddress();
		InetAddress addr = ip.getAddress();
		String ipaddr = addr.getHostAddress();
		if (GameServer.isIPBanned(ipaddr, acc, Centuria.gameServer.vpnIpsV4, Centuria.gameServer.vpnIpsV6,
				Centuria.gameServer.whitelistFile)) {
			disconnect();
			return;
		}

		// Check if the player is ingame
		Player plr = acc.getOnlinePlayerInstance();
		if (plr == null) {
			// The player is offline
			disconnect();
			return;
		} else {
			// Check if the player was in chat
			if (plr.wasInChat && plr.room != null)
				joinRoom(plr.room, false);
		}

		// Disconnect connected instances
		for (ChatClient cl : getServer().getClients())
			if (cl.getPlayer().getAccountID().equals(acc.getAccountID()) && cl != this)
				cl.disconnect();

		// Log the login attempt
		Centuria.logger.info("Chat login from IP: " + client.getRemoteSocketAddress() + ": " + acc.getLoginName());

		// Save account in memory
		player = acc;
		Centuria.logger.info("Player " + getPlayer().getDisplayName() + " connected to the chat server.");

		// Send success
		JsonObject res = new JsonObject();
		res.addProperty("eventId", "sessions.start");
		res.addProperty("success", true);
		sendPacket(res);
	}

	// Packet handling code
	void handle(JsonObject packet) {
		Centuria.logger.debug(MarkerManager.getMarker("CHAT"),
				"Client to server (user " + player.getDisplayName() + "): " + packet);
		if (!handlePacket(packet)) {
			// Packet not found
			// Allow debug mode to re-register packets
			if (Centuria.debugMode) {
				server.registry.clear();
				server.registerPackets();
			}

			Centuria.logger.error("Unhandled packet: client " + client + " sent: " + packet.toString());
		}
	}

	private boolean handlePacket(JsonObject packet) {
		// Find packet in registry
		for (AbstractChatPacket pkt : server.registry) {
			if (pkt.id().equals(packet.get("cmd").getAsString())) {
				// Found a compatible packet, instantiate it
				AbstractChatPacket res = pkt.instantiate();

				// Parse packet
				res.parse(packet);

				// Handle packet
				if (res.handle(this))
					return true; // Packet was handled, lets end the loop
			}
		}

		// Packet was not handled
		return false;
	}

	/**
	 * Retrieves the client socket
	 * 
	 * @return Socket instance
	 */
	public Socket getSocket() {
		return client;
	}

	/**
	 * Disconnects the client
	 */
	public void disconnect() {
		taskThread.flush(3);
		try {
			if (client != null)
				client.close();
		} catch (IOException e) {
		}
		stop();
		client = null;
	}

	/**
	 * Sends a packet to the client
	 * 
	 * @param packet Raw packet to send
	 */
	public void sendPacket(JsonObject packet) {
		taskThread.schedule(() -> {
			try {
				// Send packet
				if (getSocket() == null)
					return;
				char[] d = packet.toString().toCharArray();
				for (char ch : d) {
					client.getOutputStream().write((byte) ch);
				}
				client.getOutputStream().write(0);
				client.getOutputStream().flush();
				Centuria.logger.debug(MarkerManager.getMarker("CHAT"),
						"Server to client (user " + player.getDisplayName() + "): " + packet);
			} catch (Exception e) {
			}
		});
	}

	/**
	 * Sends a packet to the client
	 * 
	 * @param packet Packet to send
	 */
	public void sendPacket(AbstractChatPacket packet) {
		JsonObject data = new JsonObject();
		data.addProperty("eventId", packet.id());
		packet.build(data);
		sendPacket(data);
	}

	/**
	 * Reads a single raw packet
	 * 
	 * @return JsonObject instance
	 * @throws IOException If reading fails
	 */
	public JsonObject readRawPacket() throws IOException {
		return JsonParser.parseReader(reader).getAsJsonObject();
	}

	/**
	 * Retrieves the server object
	 * 
	 * @return ChatServer instance
	 */
	public ChatServer getServer() {
		return server;
	}

	/**
	 * Checks if the client is still connected
	 * 
	 * @return True if connected, false otherwise
	 */
	public boolean isConnected() {
		return client != null;
	}

	/**
	 * Retrieves the connected player object
	 * 
	 * @return CenturiaAccount instance
	 */
	public CenturiaAccount getPlayer() {
		return player;
	}

	/**
	 * Checks if the client is in a specific chat room
	 * 
	 * @param room Chat room to check
	 * @return True if the client is in the specified room, false otherwise
	 */
	public boolean isInRoom(String room) {
		ArrayList<String> rooms;

		while (true) {
			try {
				rooms = new ArrayList<String>(this.rooms);
				break;
			} catch (ConcurrentModificationException e) {
			}
		}

		if (rooms.contains(room))
			return true; // Player is part of this chat room

		return false; // Player is not part of this chat room
	}

	/**
	 * Checks if a room is private or not
	 * 
	 * @param room Room ID
	 * @return True if private, false otherwise
	 */
	public boolean isRoomPrivate(String room) {
		while (true) {
			try {
				return privateChat.getOrDefault(room, false);
			} catch (ConcurrentModificationException e) {
			}
		}
	}

	/**
	 * Leaves a chat room
	 * 
	 * @param room Room to leave
	 */
	public void leaveRoom(String room) {
		while (true) {
			try {
				if (rooms.contains(room)) {
					rooms.remove(room);
					privateChat.remove(room);
				}
				break;
			} catch (ConcurrentModificationException e) {
			}
		}
	}

	/**
	 * Joins a chat room
	 * 
	 * @param room      Room to join
	 * @param isPrivate True if the room is a private room, false otherwise
	 */
	public void joinRoom(String room, boolean isPrivate) {
		while (true) {
			try {
				if (!rooms.contains(room)) {
					rooms.add(room);
					privateChat.put(room, isPrivate);
				}
				break;
			} catch (ConcurrentModificationException e) {
			}
		}
	}

	/**
	 * Retrieves an array of all chat rooms
	 * 
	 * @return Array of chat room IDs
	 */
	public String[] getRooms() {
		ArrayList<String> rooms;

		while (true) {
			try {
				rooms = new ArrayList<String>(this.rooms);
				break;
			} catch (ConcurrentModificationException e) {
			}
		}

		return rooms.toArray(t -> new String[t]);
	}

}

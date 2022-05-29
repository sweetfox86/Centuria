package org.asf.emuferal.networking.chatserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;

import org.asf.emuferal.dms.DMManager;
import org.asf.emuferal.dms.PrivateChatMessage;
import org.asf.emuferal.networking.chatserver.packets.AbstractChatPacket;
import org.asf.emuferal.networking.chatserver.packets.HistoryPacket;
import org.asf.emuferal.networking.chatserver.packets.CreateConversationPacket;
import org.asf.emuferal.networking.chatserver.packets.GetConversation;
import org.asf.emuferal.networking.chatserver.packets.JoinRoomPacket;
import org.asf.emuferal.networking.chatserver.packets.OpenDMPacket;
import org.asf.emuferal.networking.chatserver.packets.PingPacket;
import org.asf.emuferal.networking.chatserver.packets.SendMessage;
import org.asf.emuferal.networking.chatserver.packets.UserConversations;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class ChatServer {

	private ServerSocket server;
	private ArrayList<ChatClient> clients = new ArrayList<ChatClient>();
	ArrayList<AbstractChatPacket> registry = new ArrayList<AbstractChatPacket>();

	public ChatServer(ServerSocket socket) {
		server = socket;
		registerPackets();
	}

	void registerPackets() {
		// Packet registry
		registerPacket(new PingPacket());
		registerPacket(new JoinRoomPacket());
		registerPacket(new UserConversations());
		registerPacket(new GetConversation());
		registerPacket(new HistoryPacket());
		registerPacket(new SendMessage());
		registerPacket(new OpenDMPacket());
		registerPacket(new CreateConversationPacket());
	}

	public ChatClient[] getClients() {
		while (true) {
			try {
				return clients.toArray(t -> new ChatClient[t]);
			} catch (ConcurrentModificationException e) {
			}
		}
	}

	/**
	 * Runs the server
	 */
	public void start() {
		Thread serverProcessor = new Thread(() -> {
			// Server loop
			while (server != null) {
				try {
					Socket client = server.accept();
					runClient(client);
				} catch (IOException ex) {
					server = null;
					break;
				}
			}
		}, "Chat Server Thread: " + this.getClass().getSimpleName());
		serverProcessor.setDaemon(true);
		serverProcessor.start();
	}

	// Adds packets to the registry
	private void registerPacket(AbstractChatPacket packet) {
		registry.add(packet);
	}

	// Client system
	private void runClient(Socket clientSocket) throws IOException {
		ChatClient client = new ChatClient(clientSocket, this);

		// Start the client thread
		Thread th = new Thread(() -> {
			try {
				// Run start code
				client.runClient();

				// Add client
				if (client.isConnected())
					clients.add(client);

				// Client loop
				while (client.getSocket() != null) {
					JsonObject obj = client.readRawPacket();
					try {
						client.handle(obj);
					} catch (Exception e) {
					}
				}

				// Remove client
				if (clients.contains(client)) {
					System.out.println(
							"Player " + client.getPlayer().getDisplayName() + " disconnected from the chat server.");
					clients.remove(client);
				}
				client.stop();
			} catch (Exception e) {
				// Close connection
				try {
					if (client.getSocket() != null)
						client.getSocket().close();
				} catch (IOException e2) {
				}

				// Remove client
				if (clients.contains(client)) {
					System.out.println(
							"Player " + client.getPlayer().getDisplayName() + " disconnected from the chat server.");
					clients.remove(client);
				}

				// Log disconnect
				if (!(e instanceof IOException)) {
					System.err.println("Chat connection died! Error: " + e.getClass().getName()
							+ (e.getMessage() != null ? ": " + e.getMessage() : ""));
					e.printStackTrace();
				}

				client.stop();
			}
		}, "Chat Client Thread: " + client);
		th.setDaemon(true);
		th.start();
	}

	/**
	 * Stops the server
	 */
	public void stop() {
		try {
			server.close();
		} catch (IOException e) {
		}
		server = null;
	}

	/**
	 * Retrieves the server socket
	 * 
	 * @return ServerSocket instance or null
	 */
	public ServerSocket getServerSocket() {
		return server;
	}

	/**
	 * Generates a room info object
	 * 
	 * @param room      Room ID
	 * @param isPrivate True if the room is private, false otherwise
	 * @return JsonObject instance
	 */
	public JsonObject roomObject(String room, boolean isPrivate) {
		// Build object
		JsonObject roomData = new JsonObject();
		roomData.addProperty("conversation_id", room);
		roomData.addProperty("title", "");

		// Load DM manager
		DMManager manager = DMManager.getInstance();

		// Check type and validity
		if (!isPrivate || !manager.dmExists(room)) {
			// Build participants object
			JsonArray members = new JsonArray();
			for (ChatClient cl : getClients()) {
				if (cl.isInRoom(room))
					members.add(cl.getPlayer().getAccountID());
			}
			roomData.add("participants", members);
		} else {
			// Build participants object
			JsonArray members = new JsonArray();
			for (String participant : manager.getDMParticipants(room)) {
				members.add(participant);
			}
			roomData.add("participants", members);

			// Find recent message
			PrivateChatMessage[] msgs = manager.getDMHistory(room);
			if (msgs.length != 0) {
				// Add most recent
				PrivateChatMessage recent = msgs[msgs.length - 1];
				JsonObject msg = new JsonObject();
				msg.addProperty("body", recent.content);
				msg.addProperty("sent_at", recent.sentAt);
				msg.addProperty("source", recent.source);
				roomData.add("recent_message", msg);
			}
		}

		roomData.addProperty("conversationType", isPrivate ? "private" : "room");
		return roomData;
	}

}

package org.asf.centuria.interactions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Stream;

import org.apache.logging.log4j.MarkerManager;
import org.asf.centuria.Centuria;
import org.asf.centuria.data.XtWriter;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.interactions.dataobjects.NetworkedObject;
import org.asf.centuria.interactions.dataobjects.StateInfo;
import org.asf.centuria.interactions.groupobjects.GroupObject;
import org.asf.centuria.interactions.groupobjects.spawnbehaviourproviders.FallbackSpawnBehaviour;
import org.asf.centuria.interactions.groupobjects.spawnbehaviourproviders.ISpawnBehaviourProvider;
import org.asf.centuria.interactions.groupobjects.spawnbehaviourproviders.RandomizedSpawnBehaviour;
import org.asf.centuria.interactions.modules.InspirationCollectionModule;
import org.asf.centuria.interactions.modules.InteractionModule;
import org.asf.centuria.interactions.modules.QuestManager;
import org.asf.centuria.interactions.modules.ResourceCollectionModule;
import org.asf.centuria.interactions.modules.ShopkeeperModule;
import org.asf.centuria.interactions.modules.linearobjects.LinearObjectHandler;
import org.asf.centuria.interactions.modules.linearobjects.LockpickItemModule;
import org.asf.centuria.packets.xt.gameserver.quests.QuestCommandPacket;
import org.asf.centuria.packets.xt.gameserver.quests.QuestCommandVTPacket;
import org.asf.centuria.util.RandomSelectorUtil;

public class InteractionManager {

	private static ArrayList<InteractionModule> modules = new ArrayList<InteractionModule>();
	private static ArrayList<ISpawnBehaviourProvider> spawnBehaviours = new ArrayList<ISpawnBehaviourProvider>();

	static {
		// Add modules
		modules.add(new QuestManager());
		modules.add(new ShopkeeperModule());
		modules.add(new InspirationCollectionModule());
		modules.add(new ResourceCollectionModule());
		modules.add(new LockpickItemModule());
		modules.add(new LinearObjectHandler());

		// Spawn behaviours
		spawnBehaviours.add(new FallbackSpawnBehaviour());
		spawnBehaviours.add(new RandomizedSpawnBehaviour());
	}

	/**
	 * Registers a interaction module
	 * 
	 * @param module Interaction module to register
	 */
	public static void registerModule(InteractionModule module) {
		if (!modules.contains(module))
			modules.add(module);
	}

	/**
	 * Registers a spawning behaviour
	 * 
	 * @param behaviour Spawn behaviour to register
	 */
	public static void registerModule(ISpawnBehaviourProvider behaviour) {
		if (!spawnBehaviours.contains(behaviour))
			spawnBehaviours.add(behaviour);
	}

	/**
	 * Finds the active spawn behaviour
	 * 
	 * @return ISpawnBehaviourProvider instance
	 */
	public static ISpawnBehaviourProvider getActiveSpawnBehaviour() {
		for (ISpawnBehaviourProvider b : spawnBehaviours) {
			if (b.getID().equals(Centuria.spawnBehaviour))
				return b;
		}

		Centuria.logger.warn(MarkerManager.getMarker("InteractionManager"), "Invalid spawn behaviour: "
				+ Centuria.spawnBehaviour + ", defaulting to fallback! Please edit server configuration!");
		return spawnBehaviours.stream().filter(t -> t.getID().equals("fallback")).findFirst().get();
	}

	/**
	 * Initializes the interactions for a specific level
	 * 
	 * @param player  Player to send the packets to
	 * @param levelID Level to find interactions for
	 */
	public static void initInteractionsFor(Player player, int levelID) {
		// Load object ids
		NetworkedObjects.init();
		ArrayList<String> ids = new ArrayList<String>();

		// Find level objects
		for (String id : NetworkedObjects.getCollectionIdsForLevel(Integer.toString(levelID))) {
			NetworkedObjects.getObjects(id).objects.keySet().forEach(t -> ids.add(t));
		}

		// Initialize modules
		modules.forEach(t -> t.prepareWorld(levelID, ids, player));

		// Initialize objects
		initializeNetworkedObjects(player, ids.toArray(t -> new String[t]), levelID);
		player.interactions.addAll(ids);
	}

	/**
	 * Initializes networked objects (eg. npcs)
	 * 
	 * @param player  Player to send the packets to
	 * @param ids     Object UUIDs to initialize
	 * @param levelID Level to find interactions for
	 */
	public static void initializeNetworkedObjects(Player player, String[] ids, int levelID) {
		HashMap<String, NetworkedObject> data = new HashMap<String, NetworkedObject>();

		// Add objects
		for (String id : ids) {
			data.put(id, NetworkedObjects.getObject(id));
		}

		// Send init packet
		XtWriter packet = new XtWriter();
		packet.writeString("qs");
		packet.writeString("-1"); // data prefix
		packet.writeString("-1036"); // unknown
		packet.writeString("24"); // unknown
		packet.writeInt(data.size()); // count
		for (String id : data.keySet()) {
			NetworkedObject ent = data.get(id);
			packet.writeString(id);
			packet.writeInt(ent.primaryObjectInfo.type);
			packet.writeInt(ent.primaryObjectInfo.defId);
		}
		packet.writeString(""); // data suffix
		player.client.sendPacket(packet.encode());

		GroupObject[] linearObjects = getActiveSpawnBehaviour().provideCurrent(levelID, player);
		player.groupOjects.addAll(Stream.of(linearObjects).toList());
		if (linearObjects.length != 0) {
			// Init group objects
			packet = new XtWriter();
			packet.writeString("qsgo");
			packet.writeString("-1"); // data prefix
			packet.writeLong(linearObjects.length); // count
			for (GroupObject ent : linearObjects) {
				packet.writeString(ent.id);
				packet.writeInt(ent.type);
			}
			packet.writeString(""); // data suffix
			player.client.sendPacket(packet.encode());
		}

		// Send qcmd packets
		for (String id : data.keySet()) {
			NetworkedObject ent = data.get(id);
			if (ent.stateInfo.size() == 0) {
				// Set states
				packet = new XtWriter();
				packet.writeString("qcmd");
				packet.writeString("-1"); // data prefix
				packet.writeString("1"); // command: set state
				packet.writeString(id); // interaction ID
				packet.writeString("0"); // state param 0
				packet.writeString("0"); // state param 1
				packet.writeString("1"); // state param 2: set to substate 1
				packet.writeString(""); // data suffix
				player.client.sendPacket(packet.encode());
			}
		}

		// Initialize objects
		for (String id : data.keySet()) {
			NetworkedObject ent = data.get(id);
			boolean handled = false;
			for (InteractionModule mod : modules) {
				if (mod.initializeWorldObjects(player.client, id, ent)) {
					handled = true;
					break;
				}
			}
			if (handled)
				continue;

			// Fallback handler
			// Spawn object
			XtWriter wr = new XtWriter();
			wr.writeString("oi");
			wr.writeInt(-1); // data prefix

			// Object creation parameters
			wr.writeString(id); // World object ID
			wr.writeInt(978);
			wr.writeString(""); // Owner ID

			// Object info
			wr.writeInt(0);
			wr.writeLong(System.currentTimeMillis() / 1000);
			wr.writeDouble(ent.locationInfo.position.x);
			wr.writeDouble(ent.locationInfo.position.y);
			wr.writeDouble(ent.locationInfo.position.z);
			wr.writeDouble(ent.locationInfo.rotation.x);
			wr.writeDouble(ent.locationInfo.rotation.y);
			wr.writeDouble(ent.locationInfo.rotation.z);
			wr.writeDouble(ent.locationInfo.rotation.w);
			wr.add("0%0%0%0.0%0%0%0");
			wr.writeString(""); // data suffix
			player.client.sendPacket(wr.encode());
		}
	}

	/**
	 * Called to handle interactions
	 * 
	 * @param player              Player making the interaction
	 * @param interactableId      Interactable object ID
	 * @param object              NetworkedObject associated with the interactable
	 *                            ID
	 * @param state               Interaction state
	 * @param destroyOnCompletion Defines whether or not the resource will be
	 *                            destroyed on interaction completion (resources
	 *                            only)
	 * @return New destroyOnCompletion state
	 */
	public static boolean handleInteraction(Player player, String interactableId, NetworkedObject object, int state,
			boolean destroyOnCompletion) {
		// Find module
		for (InteractionModule mod : modules) {
			if (mod.canHandle(player, interactableId, object)) {
				// Handle interaction
				destroyOnCompletion = mod.shouldDestroyResource(player, interactableId, object, state,
						destroyOnCompletion);
				if (mod.handleInteractionSuccess(player, interactableId, object, state))
					return destroyOnCompletion;
			}
		}
		return destroyOnCompletion;
	}

	/**
	 * Called to handle interaction data requests
	 * 
	 * @param player         Player making the interaction
	 * @param interactableId Interactable object ID
	 * @param object         NetworkedObject associated with the interactable ID
	 * @param state          Interaction state
	 */
	public static void handleInteractionDataRequest(Player player, String interactableId, NetworkedObject object,
			int state) {
		// Find module
		boolean handled = false;
		for (InteractionModule mod : modules) {
			// Check if the interaction is not blocked
			int v = mod.isDataRequestValid(player, interactableId, object, state);
			if (v != -1)
				handled = true;
			if (v == 0)
				return;
			else if (v == 1)
				break;
		}
		if (!handled) {
			if (Centuria.debugMode)
				Centuria.logger.warn(MarkerManager.getMarker("INTERACTIONS"), "OASKR for " + interactableId
						+ " did not have its validity checked by any interaction module!");
		}

		// Find state
		boolean resetted = false;
		if (!player.stateObjects.containsKey(interactableId)) {
			resetted = true;
			int tState = 0;
			int nState = player.states.getOrDefault(interactableId, 0);
			if (object.stateInfo.containsKey(Integer.toString(nState)))
				tState = nState;

			// Select state
			if (object.stateInfo.containsKey(Integer.toString(tState)))
				player.stateObjects.put(interactableId, object.stateInfo.get(Integer.toString(tState)));
		}
		if (player.stateObjects.containsKey(interactableId)) {
			// Run branches
			int branches = 0;
			ArrayList<StateInfo> states = player.stateObjects.get(interactableId);
			for (StateInfo st : states) {
				if (st.branches.containsKey(Integer.toString(state))) {
					runBranches(player, st.branches, Integer.toString(state), interactableId, object, st);
					branches += st.branches.get(Integer.toString(state)).size();
				}
			}
			if (branches == 0 && !resetted) {
				// Reset states and try again
				player.stateObjects.remove(interactableId);
				handleInteractionDataRequest(player, interactableId, object, state);
			}
		}
	}

	/**
	 * Runs a branch list
	 * 
	 * @param plr      Player to run the commands for
	 * @param branches Branch map
	 * @param id       Branch ID
	 * @param target   Interaction ID
	 * @param object   Object interacted with
	 * @param parent   Parent state
	 */
	public static void runBranches(Player plr, HashMap<String, ArrayList<StateInfo>> branches, String id, String target,
			NetworkedObject object, StateInfo parent) {
		// Handle branch commands
		if (branches.containsKey(id)) {
			var states = branches.get(id);
			plr.stateObjects.put(target, states);
			for (StateInfo state : states) {
				switch (state.command) {
				case "1": {
					// Switch state
					String t = target;
					if (!state.actorId.equals("0"))
						t = state.actorId;
					Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
							"Running command: 1 (set state), SET " + t + " TO " + state.params[0]);
					// Check state
					NetworkedObject obj = NetworkedObjects.getObject(t);

					// Check validity
					if (obj.stateInfo.containsKey(state.params[0])) {
						plr.states.put(t, Integer.parseInt(state.params[0]));

						// Build quest command
						QuestCommandPacket packet = new QuestCommandPacket();
						packet.id = state.actorId;
						packet.type = 1;
						// Parameters
						for (String param : state.params)
							packet.params.add(param);
						plr.client.sendPacket(packet);
					} else if (obj.primaryObjectInfo.type == 7) {
						// Counter variable
						plr.states.put(t, Integer.parseInt(state.params[0]));

						// Build quest command
						QuestCommandVTPacket packet = new QuestCommandVTPacket();
						packet.id = state.actorId;
						packet.type = 1;
						// Parameters
						for (String param : state.params)
							packet.params.add(param);
						plr.client.sendPacket(packet);
					}
					break;
				}
				case "41": {
					// Give table
					Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
							"Running command: 41 (give loot), GIVE TABLE " + state.params[0]);
					ResourceCollectionModule.giveLootReward(plr, state.params[0], 2, object.primaryObjectInfo.defId);
					break;
				}
				case "12": {
					// Run commands and progress
					Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"), "Running command: 12");
					int stateId = plr.states.getOrDefault(state.actorId, 1);
					plr.states.put(state.actorId, stateId + 1);

					// Find object
					NetworkedObject obj = NetworkedObjects.getObject(state.actorId);
					var stateObjs = obj.stateInfo.get(Integer.toString(stateId));
					if (stateObjs != null)
						for (var st : stateObjs) {
							if (st.branches.size() != 0)
								runBranches(plr, st.branches, "1", target, obj, state);
						}

					// Send state
					QuestCommandPacket packet = new QuestCommandPacket();
					packet.id = state.actorId;
					packet.type = 1;
					packet.params.add(Integer.toString(stateId + 1));
					plr.client.sendPacket(packet);

					break;
				}
				case "13": {
					// Run other states and decrease counter
					String t = target;
					if (!state.actorId.equals("0"))
						t = state.actorId;

					// Find target
					NetworkedObject obj = NetworkedObjects.getObject(t);

					// Check counter
					if (obj.primaryObjectInfo.type == 7) {
						if (plr.states.getOrDefault(t, 0) > 0) {
							Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
									"Running command: 13 (decrease counter): " + (plr.states.get(t) - 1));
							plr.states.put(t, plr.states.get(t) - 1);
							QuestCommandVTPacket pkt = new QuestCommandVTPacket();
							pkt.id = t;
							pkt.type = 1;
							pkt.params.add(plr.states.get(t).toString());
							plr.client.sendPacket(pkt);
							if (plr.states.getOrDefault(t, 0) > 0)
								break;
						}
					}

					// Find state
					Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
							"Running command: 13 (run states): " + state.params[0]);
					if (obj.stateInfo.containsKey(state.params[0]))
						runBranches(plr, obj.stateInfo, state.params[0], t, obj, state);

					break;
				}
				case "26": {
					// Run states (branch-level elevation)
					String t = target;
					if (!state.actorId.equals("0"))
						t = state.actorId;
					Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
							"Running command: 26 (RUN STATES), actor: " + t + ", state: " + state.params[0]);
					NetworkedObject obj = NetworkedObjects.getObject(t);
					runBranches(plr, obj.stateInfo, state.params[0], t, obj, state);
					break;
				}
				case "52": {
					// Set state and run branches???
					// I fr expect this implementation to come back and bite me in the future
					// But i cant figure this quest command out
					String t = target;
					if (!state.actorId.equals("0"))
						t = state.actorId;

					Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
							"Running command: 52 (SET STATE AND RUN BRANCHES), actor: " + t + ", state: "
									+ state.params[0]);
					plr.states.put(t, Integer.parseInt(state.params[0]));
					for (String branch : state.branches.keySet())
						runBranches(plr, state.branches, branch, target, object, state);
					break;
				}
				case "29": {
					// Randomize
					Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
							"Running command: 29 (RANDOMIZE)");

					// Find object
					String t = target;
					if (!state.actorId.equals("0"))
						t = state.actorId;
					NetworkedObject obj = NetworkedObjects.getObject(t);

					// Build weight map
					HashMap<String, Integer> weights = new HashMap<String, Integer>();
					for (String st : state.branches.keySet()) {
						weights.put(st, Integer.parseInt(st));
					}

					// Select branch
					String branchID = RandomSelectorUtil.selectWeighted(weights);

					// Run branch
					runBranches(plr, state.branches, branchID, t, obj, state);
					break;
				}
				default: {
					// Log
					Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
							"Running command: " + state.command);

					// Find module
					boolean warn = true;
					for (InteractionModule mod : modules) {
						// Run interaction
						if (mod.handleCommand(plr, target, object, state, parent)) {
							warn = false;
							break;
						}
					}

					// Unhandled if true
					if (warn)
						Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
								"Unhandled command: " + state.command);
					break;
				}
				}

				// Check if it needs to be sent to the client
				int cmdI = Integer.parseInt(state.command);
				if ((cmdI <= 20 || cmdI == 38 || cmdI == 81 || cmdI == 82) && !state.command.equals("3")
						&& !state.command.equals("1")) {
					// Build quest command
					QuestCommandPacket packet = new QuestCommandPacket();
					packet.id = state.actorId;
					packet.type = cmdI;
					// Parameters
					for (String param : state.params)
						packet.params.add(param);
					plr.client.sendPacket(packet);
				}
			}
		}
	}

	/**
	 * Runs state commands
	 * 
	 * @param states State commands to run
	 * @param plr    Player performing the interaction
	 * @param object Object interacted with
	 * @param target Interaction ID
	 */
	public static void runStates(ArrayList<StateInfo> states, Player plr, NetworkedObject object, String target) {
		for (StateInfo state : states) {
			// Log commands
			String args = "";
			for (String arg : state.params) {
				args += ", " + arg;
			}
			if (!args.isEmpty())
				args = args.substring(2);
			Centuria.logger
					.debug("Object interaction command: " + target + ", command: " + state.command + ", args: " + args);

			// Handle states
			switch (state.command) {
			case "1": {
				// Switch state
				String t = target;
				if (!state.actorId.equals("0"))
					t = state.actorId;
				Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
						"Running command: 1 (set state), SET " + t + " TO " + state.params[0]);
				// Check state
				NetworkedObject obj = NetworkedObjects.getObject(t);

				// Check validity
				if (obj.stateInfo.containsKey(state.params[0])) {
					plr.states.put(t, Integer.parseInt(state.params[0]));

					// Build quest command
					QuestCommandPacket packet = new QuestCommandPacket();
					packet.id = state.actorId;
					packet.type = 1;
					// Parameters
					for (String param : state.params)
						packet.params.add(param);
					plr.client.sendPacket(packet);
				} else if (obj.primaryObjectInfo.type == 7) {
					// Counter variable
					plr.states.put(t, Integer.parseInt(state.params[0]));

					// Build quest command
					QuestCommandVTPacket packet = new QuestCommandVTPacket();
					packet.id = state.actorId;
					packet.type = 1;
					// Parameters
					for (String param : state.params)
						packet.params.add(param);
					plr.client.sendPacket(packet);
				}
				break;
			}
			case "35":
			case "3":
				// Build quest command
				XtWriter pk = new XtWriter();
				pk.writeString("qcmd");
				pk.writeInt(-1); // Data prefix
				pk.writeString(state.command); // command
				pk.writeInt(0); // State
				pk.writeString(target); // Interactable
				pk.writeInt(0); // Position

				// Parameters
				for (String param : state.params)
					pk.writeString(param);
				pk.writeString(""); // Data suffix
				plr.client.sendPacket(pk.encode());
				Centuria.logger.debug("QCMD sent: " + pk.encode());
				break;
			case "41":
				// Not allowed
				break;
			case "13": {
				// Run other states and decrease counter
				String t = target;
				if (!state.actorId.equals("0"))
					t = state.actorId;

				// Find target
				NetworkedObject obj = NetworkedObjects.getObject(t);

				// Check counter
				if (obj.primaryObjectInfo.type == 7) {
					if (plr.states.getOrDefault(t, 0) > 0) {
						Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
								"Running command: 13 (decrease counter): " + (plr.states.get(t) - 1));
						plr.states.put(t, plr.states.get(t) - 1);
						QuestCommandVTPacket pkt = new QuestCommandVTPacket();
						pkt.id = t;
						pkt.type = 1;
						pkt.params.add(plr.states.get(t).toString());
						plr.client.sendPacket(pkt);
						if (plr.states.getOrDefault(t, 0) > 0)
							break;
					}
				}

				// Find state
				Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
						"Running command: 13 (run states): " + state.params[0]);
				if (obj.stateInfo.containsKey(state.params[0]))
					runBranches(plr, obj.stateInfo, state.params[0], t, obj, state);

				break;
			}
			case "12": {
				// Run commands and progress
				Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"), "Running command: 12");
				int stateId = plr.states.getOrDefault(state.actorId, 1);
				plr.states.put(state.actorId, stateId + 1);

				// Find object
				NetworkedObject obj = NetworkedObjects.getObject(state.actorId);
				var stateObjs = obj.stateInfo.get(Integer.toString(stateId));
				for (var st : stateObjs) {
					if (st.branches.size() != 0)
						runBranches(plr, st.branches, "1", target, obj, state);
				}

				break;
			}
			case "26": {
				// Run states (branch-level elevation)
				String t = target;
				if (!state.actorId.equals("0"))
					t = state.actorId;
				Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
						"Running command: 26 (BRANCH EVAL), actor: " + t + ", state: " + state.params[0]);
				NetworkedObject obj = NetworkedObjects.getObject(t);

				// Find module
				boolean handled = false;
				for (InteractionModule mod : modules) {
					// Check if the interaction is not blocked
					int v = mod.isDataRequestValid(plr, t, obj, Integer.parseInt(state.params[0]));
					if (v != -1)
						handled = true;
					if (v == 0)
						return;
					else if (v == 1)
						break;
				}
				if (!handled) {
					if (Centuria.debugMode)
						Centuria.logger.warn(MarkerManager.getMarker("INTERACTIONS"), "BRANCH EVAL for " + t
								+ " did not have its validity checked by any interaction module!");
				}

				runBranches(plr, obj.stateInfo, state.params[0], t, obj, state);
				break;
			}
			default: {
				// Find module
				boolean warn = true;
				for (InteractionModule mod : modules) {
					// Run interaction
					if (mod.handleCommand(plr, target, object, state, null)) {
						warn = false;
						break;
					}
				}

				// Unhandled if true
				if (warn)
					Centuria.logger.debug(MarkerManager.getMarker("INTERACTION COMMANDS"),
							"Unhandled state command (OAF packet): " + state.command);
				break;
			}
			}

			// Check if it needs to be sent to the client
			int cmdI = Integer.parseInt(state.command);
			if ((cmdI <= 20 || cmdI == 38 || cmdI == 81 || cmdI == 82) && !state.command.equals("3")) {
				// Build quest command
				QuestCommandPacket packet = new QuestCommandPacket();
				packet.id = state.actorId;
				packet.type = cmdI;
				// Parameters
				for (String param : state.params)
					packet.params.add(param);
				plr.client.sendPacket(packet);
			}
		}
	}

}

/**
 * Système de bots joueurs (style Playerbots) : des bots qui sont de vrais {@link com.aionemu.gameserver.model.gameobjects.player.Player}
 * pilotés par IA plutôt que par une connexion réseau, réutilisant la logique existante du moteur
 * (PlayerController, SkillEngine, groupes) au lieu de la dupliquer.
 *
 * Package volontairement isolé du reste du moteur pour limiter les conflits lors des synchronisations
 * avec le dépôt upstream (beyond-aion/aion-server).
 */
package com.aionemu.gameserver.playerbot;

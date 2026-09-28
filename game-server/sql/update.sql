/*
 * DB changes since c5a0f34 (12.09.2026)
 */

ALTER TABLE `player_effects`
	ADD COLUMN `magical_criticals` TINYINT NOT NULL DEFAULT '0' AFTER `force_type`;

ALTER TABLE `inventory`
	ADD COLUMN `rank_limit_expire_time` int NOT NULL DEFAULT '0' AFTER `rnd_plume_bonus`;

CREATE TABLE `playerbot_characters` (
	`player_id` int NOT NULL,
	`resident` tinyint NOT NULL DEFAULT '0',
	`in_world` tinyint NOT NULL DEFAULT '0',
	PRIMARY KEY (`player_id`),
	CONSTRAINT `playerbot_characters_ibfk_1` FOREIGN KEY (`player_id`) REFERENCES `players` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE `playerbot_characters`
	ADD COLUMN `home_x` float NOT NULL DEFAULT '0' AFTER `in_world`,
	ADD COLUMN `home_y` float NOT NULL DEFAULT '0' AFTER `home_x`,
	ADD COLUMN `home_z` float NOT NULL DEFAULT '0' AFTER `home_y`;
